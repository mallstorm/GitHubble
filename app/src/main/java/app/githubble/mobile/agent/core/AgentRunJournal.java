package app.githubble.mobile.agent.core;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Binds one chat agent run to a durable {@link TaskJournal} and an in-memory {@link ToolLedger}.
 * Pure Java: the caller (AgentRun) passes canonical argument strings, never raw tool arguments,
 * so no tool payloads are written to disk. Persistence is best-effort and never breaks a run.
 */
public final class AgentRunJournal {
    /** Tools whose repetition with identical arguments is never intended (creation and merge). */
    private static final Set<String> ONCE = new HashSet<>(java.util.Arrays.asList(
        "create_repository", "create_branch", "create_issue", "create_pull_request", "merge_pull_request", "create_release"));
    private static final TaskState[] FORWARD = {TaskState.ANALYZING, TaskState.PLANNING, TaskState.APPROVAL,
        TaskState.EXECUTING, TaskState.TESTING, TaskState.BUILDING, TaskState.VERIFYING};

    private final TaskJournal journal;
    private final JournalStore store;
    private final ToolLedger ledger;
    private final Map<String, ToolLedger.Invocation> open = new HashMap<>();
    private final Set<String> dispatched = new HashSet<>();
    private volatile boolean persistFailed;

    public AgentRunJournal(String runId, String project, ExecutionMode mode, File dir, TaskJournal.Clock clock) {
        this.journal = new TaskJournal(runId, project == null ? "" : project, mode, clock);
        this.ledger = new ToolLedger(clock);
        JournalStore s;
        try { s = new JournalStore(dir); } catch (RuntimeException e) { s = null; persistFailed = true; }
        this.store = s;
    }

    public synchronized TaskJournal journal() { return journal; }
    public synchronized ToolLedger ledger() { return ledger; }
    public boolean persistFailed() { return persistFailed; }

    public synchronized void start() {
        journal.transition(TaskState.ANALYZING, "задача принята в работу");
        save();
    }

    /** @return null, or why this call must be refused as a repeat of a finished or unknown operation */
    public synchronized String toolBegin(String opId, String tool, String canonicalArgs) {
        TaskState target = targetFor(tool);
        if (target != null && journal.state().isActive()) moveTo(target, "инструмент " + tool);
        try {
            ToolLedger.Invocation inv = ledger.begin(journal.id, tool, 1,
                ToolLedger.fingerprint(canonicalArgs), ONCE.contains(tool));
            open.put(opId, inv);
            save();
        } catch (ToolLedger.DuplicateOperationException dup) {
            journal.note("повтор отклонён: " + tool);
            save();
            return dup.getMessage();
        }
        return null;
    }

    public synchronized void toolDispatched(String opId) { if (opId != null) dispatched.add(opId); }

    public synchronized void toolResult(String opId, String status, String error) {
        ToolLedger.Invocation inv = open.remove(opId);
        if (inv == null) return;
        boolean sent = dispatched.remove(opId);
        String shown = error == null ? "" : error.substring(0, Math.min(200, error.length()));
        if ("succeeded".equals(status)) ledger.succeed(inv, "");
        else if (sent) ledger.unknown(inv, shown);
        else if ("cancelled".equals(status)) ledger.cancel(inv, shown);
        else ledger.fail(inv, shown);
        journal.note(inv.tool + " → " + inv.state() + (shown.isEmpty() ? "" : ": " + shown));
        save();
    }

    public synchronized void approval(boolean waiting) {
        TaskState s = journal.state();
        if (waiting && s.isActive()) {
            journal.note("ожидание подтверждения пользователя");
            journal.transition(TaskState.WAITING_PERMISSION, "нужно подтверждение пользователя");
        } else if (!waiting && s == TaskState.WAITING_PERMISSION) {
            journal.resume("подтверждение получено или отклонено");
        }
        save();
    }

    public synchronized void finish(String status, String message) {
        if (journal.state().isTerminal()) return;
        // Operations still open at the end are not assumed done: sent ones are UNKNOWN.
        for (Map.Entry<String, ToolLedger.Invocation> e : new HashMap<>(open).entrySet()) {
            if (dispatched.remove(e.getKey())) ledger.unknown(e.getValue(), "задача завершена до ответа");
            else ledger.cancel(e.getValue(), "задача завершена");
        }
        open.clear();
        String why = message == null ? "" : message;
        TaskState s = journal.state();
        if ("succeeded".equals(status)) {
            if (s == TaskState.BUILDING) {
                journal.pause("сборка запущена; её результат ещё не проверен");
            } else if (s == TaskState.VERIFYING) {
                journal.transition(TaskState.DELIVERING, "проверка выполнена");
                journal.transition(TaskState.COMPLETED, why);
            } else if (s.isSuspended()) {
                journal.fail("задача завершилась в состоянии " + s);
            } else {
                journal.transition(TaskState.COMPLETED, why);
            }
        } else if ("cancelled".equals(status)) {
            journal.transition(TaskState.CANCELLED, why);
        } else {
            journal.fail(why.isEmpty() ? status : why);
        }
        save();
    }

    /** Marks journals left active by a process that died; paused/waiting ones stay as they are. */
    public static int recoverInterrupted(File dir, TaskJournal.Clock clock) {
        int marked = 0;
        try {
            JournalStore store = new JournalStore(dir);
            for (TaskJournal j : store.unfinished(clock)) {
                if (j.state().isActive() || j.state() == TaskState.WAITING_PERMISSION) {
                    j.fail("приложение было закрыто во время выполнения задачи");
                    store.save(j);
                    marked++;
                }
            }
        } catch (IOException | RuntimeException ignored) { /* recovery is best-effort */ }
        return marked;
    }

    private static TaskState targetFor(String tool) {
        switch (tool) {
            case "dispatch_workflow": case "await_build": return TaskState.BUILDING;
            case "verify_build": return TaskState.VERIFYING;
            case "create_repository": case "create_branch": case "update_repository": case "update_account_profile":
            case "create_issue": case "update_issue": case "create_pull_request": case "update_pull_request":
            case "comment_issue": case "merge_pull_request": case "create_release": case "write_files": case "publish_workspace":
            case "create_workspace": case "import_repository": case "attach_repository":
                return TaskState.EXECUTING;
            default: return null; // reads stay where they are
        }
    }

    private void moveTo(TaskState target, String reason) {
        TaskState current = journal.state();
        if (current == target) return;
        if (target == TaskState.EXECUTING && (current == TaskState.TESTING || current == TaskState.BUILDING
                || current == TaskState.VERIFYING)) {
            journal.transition(TaskState.EXECUTING, "возврат к правкам: " + reason);
            return;
        }
        if (target == TaskState.BUILDING && current == TaskState.VERIFYING) {
            journal.transition(TaskState.BUILDING, reason);
            return;
        }
        int from = index(current), to = index(target);
        if (from < 0 || to <= from) return;
        for (int i = from + 1; i <= to; i++)
            journal.transition(FORWARD[i], i == to ? reason : "неявно (чат без отдельного шага): " + FORWARD[i]);
    }

    private static int index(TaskState s) {
        for (int i = 0; i < FORWARD.length; i++) if (FORWARD[i] == s) return i;
        return -1;
    }

    private void save() {
        if (store == null) return;
        try { store.save(journal); } catch (IOException | RuntimeException e) { persistFailed = true; }
    }
}
