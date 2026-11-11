package app.githubble.mobile.agent.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Typed record of every tool call (TZ 7.3) plus a double-execution guard: a mutating call
 * that already succeeded — or whose outcome is unknown — in the same task is refused until
 * its real state has been verified.
 */
public final class ToolLedger {
    public enum State { RUNNING, SUCCEEDED, FAILED, CANCELLED, UNKNOWN }

    public static final class Invocation {
        public final String operationId, taskId, tool, argsFingerprint;
        public final int schemaVersion;
        public final boolean mutating;
        public final long startedAt;
        private State state = State.RUNNING;
        private String error = "", artifactRef = "";
        private long finishedAt;
        Invocation(String taskId, String tool, int schemaVersion, String fp, boolean mutating, long now) {
            this.operationId = UUID.randomUUID().toString();
            this.taskId = taskId; this.tool = tool; this.schemaVersion = schemaVersion;
            this.argsFingerprint = fp; this.mutating = mutating; this.startedAt = now;
        }
        public State state() { return state; }
        public String error() { return error; }
        public String artifactRef() { return artifactRef; }
        public long durationMs() { return state == State.RUNNING ? 0 : Math.max(0, finishedAt - startedAt); }
    }

    public static final class DuplicateOperationException extends IllegalStateException {
        public DuplicateOperationException(String message) { super(message); }
    }

    private final TaskJournal.Clock clock;
    private final List<Invocation> all = new ArrayList<>();
    private final Map<String, Invocation> byKey = new HashMap<>();

    public ToolLedger(TaskJournal.Clock clock) { this.clock = clock; }

    private static String key(String task, String tool, String fp) { return task + "\u0000" + tool + "\u0000" + fp; }

    /** Stable fingerprint of validated arguments (caller passes canonical text). */
    public static String fingerprint(String canonicalArgs) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                .digest(canonicalArgs.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12; i++) sb.append(String.format("%02x", d[i] & 255));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public synchronized Invocation begin(String taskId, String tool, int schemaVersion,
            String argsFingerprint, boolean mutating) {
        if (taskId == null || taskId.isEmpty() || tool == null || tool.isEmpty())
            throw new IllegalArgumentException("taskId/tool");
        String k = key(taskId, tool, argsFingerprint);
        Invocation prior = byKey.get(k);
        if (mutating && prior != null) {
            if (prior.state == State.RUNNING)
                throw new DuplicateOperationException("Операция уже выполняется: " + tool);
            if (prior.state == State.SUCCEEDED)
                throw new DuplicateOperationException("Операция уже выполнена: " + tool);
            if (prior.state == State.UNKNOWN)
                throw new DuplicateOperationException("Результат прошлого запуска неизвестен; проверьте состояние в GitHub: " + tool);
        }
        Invocation inv = new Invocation(taskId, tool, schemaVersion, argsFingerprint, mutating, clock.now());
        all.add(inv);
        byKey.put(k, inv);
        return inv;
    }

    public synchronized void succeed(Invocation inv, String artifactRef) {
        finish(inv, State.SUCCEEDED, "", artifactRef);
    }

    public synchronized void fail(Invocation inv, String error) { finish(inv, State.FAILED, error, ""); }

    public synchronized void cancel(Invocation inv, String why) { finish(inv, State.CANCELLED, why, ""); }

    /** Request left the device but the answer was lost (timeout, 5xx on a write). */
    public synchronized void unknown(Invocation inv, String error) { finish(inv, State.UNKNOWN, error, ""); }

    /** After the user/host verified the server state, an UNKNOWN call may be settled. */
    public synchronized void settle(Invocation inv, boolean appliedOnServer) {
        if (inv.state != State.UNKNOWN) throw new IllegalStateException("Операция не в состоянии UNKNOWN");
        inv.state = appliedOnServer ? State.SUCCEEDED : State.FAILED;
    }

    private void finish(Invocation inv, State s, String error, String ref) {
        if (inv.state != State.RUNNING) throw new IllegalStateException("Операция уже завершена: " + inv.state);
        inv.state = s; inv.error = error == null ? "" : error; inv.artifactRef = ref == null ? "" : ref;
        inv.finishedAt = clock.now();
    }

    public synchronized List<Invocation> invocations() { return Collections.unmodifiableList(new ArrayList<>(all)); }

    public synchronized boolean succeeded(String taskId, String tool) {
        for (Invocation i : all) if (i.taskId.equals(taskId) && i.tool.equals(tool) && i.state == State.SUCCEEDED) return true;
        return false;
    }

    /** Invocations whose result is not known: the task must not report success past them. */
    public synchronized List<Invocation> unresolved(String taskId) {
        List<Invocation> out = new ArrayList<>();
        for (Invocation i : all)
            if (i.taskId.equals(taskId) && (i.state == State.UNKNOWN || i.state == State.RUNNING)) out.add(i);
        return out;
    }
}
