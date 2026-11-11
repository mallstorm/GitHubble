package app.githubble.mobile.agent.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Durable record of one agent task: validated state, event log, checkpoints (TZ 4.3).
 * The journal is the single source of truth for task progress; a model's prose can never
 * advance it. Not thread-safe by itself: callers synchronize on the instance.
 */
public final class TaskJournal {
    public static final class Event {
        public final int seq;
        public final long at;
        public final String type;   // STATE, MODE, NOTE, CHECKPOINT, APPROVAL
        public final String from, to, detail;
        Event(int seq, long at, String type, String from, String to, String detail) {
            this.seq = seq; this.at = at; this.type = type;
            this.from = from == null ? "" : from; this.to = to == null ? "" : to;
            this.detail = detail == null ? "" : detail;
        }
    }

    public interface Clock { long now(); }

    public final String id;
    public final String project;
    private final Clock clock;
    private final List<Event> events = new ArrayList<>();
    private final List<String> checkpoints = new ArrayList<>();
    private TaskState state = TaskState.CREATED;
    private TaskState resumeTo;               // set while suspended
    private ExecutionMode mode;

    public TaskJournal(String project, ExecutionMode mode) {
        this(UUID.randomUUID().toString(), project, mode, System::currentTimeMillis);
    }

    public TaskJournal(String id, String project, ExecutionMode mode, Clock clock) {
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("id");
        this.id = id; this.project = project == null ? "" : project;
        this.mode = mode == null ? ExecutionMode.MANUAL : mode;
        this.clock = clock;
        log("STATE", "", state.name(), "created");
    }

    private void log(String type, String from, String to, String detail) {
        events.add(new Event(events.size() + 1, clock.now(), type, from, to, detail));
    }

    public synchronized TaskState state() { return state; }
    public synchronized ExecutionMode mode() { return mode; }
    public synchronized TaskState resumeState() { return resumeTo; }
    public synchronized List<Event> events() { return Collections.unmodifiableList(new ArrayList<>(events)); }
    public synchronized List<String> checkpoints() { return Collections.unmodifiableList(new ArrayList<>(checkpoints)); }

    /** Validated transition. Suspension/failure remembers the interrupted state. */
    public synchronized void transition(TaskState to, String reason) {
        if (to == null) throw new IllegalArgumentException("to");
        boolean resuming = state.isSuspended() && resumeTo != null && to == resumeTo;
        if (!resuming && !state.canMoveTo(to))
            throw new IllegalStateException("Недопустимый переход " + state + " → " + to);
        TaskState from = state;
        if (from.isActive() && (to.isSuspended() || to == TaskState.FAILED)) resumeTo = from;
        state = to;
        if (to.isActive() || to.isTerminal()) resumeTo = null;
        log("STATE", from.name(), to.name(), reason);
    }

    public synchronized void pause(String reason) { transition(TaskState.PAUSED, reason); }

    public synchronized void resume(String reason) {
        if (!state.isSuspended() || resumeTo == null)
            throw new IllegalStateException("Задача не приостановлена");
        transition(resumeTo, reason);
    }

    /** FAILED is recoverable: resume point is the state that failed. */
    public synchronized void fail(String reason) { transition(TaskState.FAILED, reason); }

    public synchronized void recover(String reason) {
        if (!state.canMoveTo(TaskState.RECOVERING))
            throw new IllegalStateException("Восстановление невозможно из " + state);
        transition(TaskState.RECOVERING, reason);
    }

    /**
     * Changing mode keeps state and history. Tightening to MANUAL while work is in
     * flight parks the task in WAITING_PERMISSION so nothing new runs without consent.
     */
    public synchronized void switchMode(ExecutionMode next, String reason) {
        if (next == null || next == mode) return;
        ExecutionMode previous = mode;
        mode = next;
        log("MODE", previous.name(), next.name(), reason);
        if (next == ExecutionMode.MANUAL && (state == TaskState.EXECUTING || state == TaskState.TESTING
                || state == TaskState.BUILDING || state == TaskState.DELIVERING))
            transition(TaskState.WAITING_PERMISSION, "mode switched to MANUAL");
    }

    public synchronized void checkpoint(String label) {
        checkpoints.add(label);
        log("CHECKPOINT", "", "", label);
    }

    public synchronized void note(String text) { log("NOTE", "", "", text); }

    public synchronized void approval(String what, boolean granted) {
        log("APPROVAL", "", granted ? "GRANTED" : "DENIED", what);
    }

    // ---- serialization: one escaped line per record; survives process death ----

    private static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') b.append("\\\\"); else if (c == '\n') b.append("\\n");
            else if (c == '\t') b.append("\\t"); else if (c == '\r') b.append("\\r"); else b.append(c);
        }
        return b.toString();
    }

    private static String unesc(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                b.append(n == 'n' ? '\n' : n == 't' ? '\t' : n == 'r' ? '\r' : n);
            } else b.append(c);
        }
        return b.toString();
    }

    public synchronized String serialize() {
        StringBuilder out = new StringBuilder("GHJ1\n");
        out.append("id\t").append(esc(id)).append('\n');
        out.append("project\t").append(esc(project)).append('\n');
        out.append("mode\t").append(mode).append('\n');
        out.append("state\t").append(state).append('\n');
        out.append("resume\t").append(resumeTo == null ? "" : resumeTo.name()).append('\n');
        for (String c : checkpoints) out.append("cp\t").append(esc(c)).append('\n');
        for (Event e : events)
            out.append("ev\t").append(e.seq).append('\t').append(e.at).append('\t').append(e.type)
                .append('\t').append(e.from).append('\t').append(e.to).append('\t')
                .append(esc(e.detail)).append('\n');
        return out.toString();
    }

    public static TaskJournal parse(String text, Clock clock) {
        String[] lines = text.split("\n", -1);
        if (lines.length == 0 || !"GHJ1".equals(lines[0])) throw new IllegalArgumentException("Неизвестный формат журнала");
        String id = null, project = "", resume = "";
        ExecutionMode mode = ExecutionMode.MANUAL;
        TaskState state = TaskState.CREATED;
        List<String> cps = new ArrayList<>();
        List<Event> evs = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isEmpty()) continue;
            String[] f = lines[i].split("\t", -1);
            switch (f[0]) {
                case "id": id = unesc(f[1]); break;
                case "project": project = unesc(f[1]); break;
                case "mode": mode = ExecutionMode.valueOf(f[1]); break;
                case "state": state = TaskState.valueOf(f[1]); break;
                case "resume": resume = f[1]; break;
                case "cp": cps.add(unesc(f[1])); break;
                case "ev":
                    if (f.length < 7) throw new IllegalArgumentException("Повреждена запись журнала");
                    evs.add(new Event(Integer.parseInt(f[1]), Long.parseLong(f[2]), f[3], f[4], f[5], unesc(f[6])));
                    break;
                default: throw new IllegalArgumentException("Неизвестная запись: " + f[0]);
            }
        }
        if (id == null) throw new IllegalArgumentException("Нет id задачи");
        TaskJournal j = new TaskJournal(id, project, mode, clock);
        j.events.clear();
        j.events.addAll(evs);
        j.checkpoints.addAll(cps);
        j.state = state;
        j.resumeTo = resume.isEmpty() ? null : TaskState.valueOf(resume);
        return j;
    }
}
