package app.githubble.mobile.agent.core;

import java.util.HashMap;
import java.util.Map;

/**
 * Bounded repair loop (TZ 9.3, 5.1 limits): limited attempts, repeat detection, exponential
 * back-off for transient faults, hand-off to the user for external blockers. Never loops forever.
 */
public final class RetryPolicy {
    public static final class Limits {
        public int maxBuildRuns = 8, maxAutoFixes = 5, maxTransientRetries = 3, sameErrorLimit = 2;
        public long baseDelayMs = 30_000, maxDelayMs = 15 * 60_000L;
    }

    public enum Action { FIX_SOURCE, RETRY_AFTER, WAIT_EXTERNAL, FAIL }

    public static final class Decision {
        public final Action action;
        public final long delayMs;
        public final String reason;
        /** Task state the journal should move to (TZ 6.3: return to the right state). */
        public final TaskState nextState;
        Decision(Action a, long d, String r, TaskState s) { action = a; delayMs = d; reason = r; nextState = s; }
    }

    private final Limits limits;
    private int buildRuns, autoFixes, transientRetries;
    private final Map<String, Integer> signatures = new HashMap<>();

    public RetryPolicy(Limits limits) { this.limits = limits == null ? new Limits() : limits; }

    public synchronized int buildRuns() { return buildRuns; }
    public synchronized int autoFixes() { return autoFixes; }

    /** Call when a build is about to be started. @return false when the budget is spent. */
    public synchronized boolean tryStartBuild() {
        if (buildRuns >= limits.maxBuildRuns) return false;
        buildRuns++;
        return true;
    }

    /** @param retryAfterMs server-provided wait (Retry-After / rate-limit reset), or 0 */
    public synchronized Decision onFailure(BuildFailureClassifier.Result failure, long retryAfterMs) {
        FailureClass c = failure.failureClass;
        int seen = 0;
        if (!failure.signature.isEmpty()) {
            seen = signatures.containsKey(failure.signature) ? signatures.get(failure.signature) + 1 : 1;
            signatures.put(failure.signature, seen);
        }
        if (c == FailureClass.PERMISSION_ERROR || c == FailureClass.SIGNING_FAILURE || c == FailureClass.ANDROID_SDK_ERROR
                || c == FailureClass.BILLING_OR_QUOTA_BLOCK) {
            return new Decision(Action.WAIT_EXTERNAL, 0,
                c + ": нужны действия владельца (права, ключи или окружение); обход не выполняется", TaskState.WAITING_EXTERNAL);
        }
        if (buildRuns >= limits.maxBuildRuns) {
            return new Decision(Action.FAIL, 0, "Исчерпан лимит запусков сборки (" + limits.maxBuildRuns + ")", TaskState.FAILED);
        }
        if (c.transientFailure) {
            if (transientRetries >= limits.maxTransientRetries)
                return new Decision(Action.WAIT_EXTERNAL, 0, c + ": временный сбой не прошёл после "
                    + limits.maxTransientRetries + " повторов", TaskState.WAITING_EXTERNAL);
            long delay = backoff(transientRetries);
            if (retryAfterMs > 0) delay = Math.max(delay, retryAfterMs);
            if (delay > limits.maxDelayMs)
                return new Decision(Action.WAIT_EXTERNAL, delay, c + ": ожидание дольше допустимого", TaskState.WAITING_EXTERNAL);
            transientRetries++;
            return new Decision(Action.RETRY_AFTER, delay, c + ": повтор без изменения кода", TaskState.BUILDING);
        }
        if (c.sourceFixable) {
            if (seen > limits.sameErrorLimit)
                return new Decision(Action.FAIL, 0, "Одна и та же ошибка повторилась " + seen + " раз: автоисправления не помогают", TaskState.FAILED);
            if (autoFixes >= limits.maxAutoFixes)
                return new Decision(Action.FAIL, 0, "Исчерпан лимит автоисправлений (" + limits.maxAutoFixes + ")", TaskState.FAILED);
            autoFixes++;
            return new Decision(Action.FIX_SOURCE, 0, c + ": исправление исходников", TaskState.EXECUTING);
        }
        return new Decision(Action.FAIL, 0, "Не удалось классифицировать ошибку сборки", TaskState.FAILED);
    }

    /** Persisted per task; recreating a host must never reset repair allowances. */
    public synchronized org.json.JSONObject snapshot() {
        try{return new org.json.JSONObject().put("buildRuns",buildRuns).put("autoFixes",autoFixes)
            .put("transientRetries",transientRetries).put("signatures",new org.json.JSONObject(signatures));}
        catch(org.json.JSONException bad){throw new IllegalStateException(bad);}
    }
    public static RetryPolicy restore(org.json.JSONObject value) {
        RetryPolicy p=new RetryPolicy(null);if(value==null)return p;
        p.buildRuns=Math.max(0,value.optInt("buildRuns"));p.autoFixes=Math.max(0,value.optInt("autoFixes"));
        p.transientRetries=Math.max(0,value.optInt("transientRetries"));org.json.JSONObject sig=value.optJSONObject("signatures");
        if(sig!=null){java.util.Iterator<String> keys=sig.keys();while(keys.hasNext()){String key=keys.next();p.signatures.put(key,Math.max(0,sig.optInt(key)));}}
        return p;
    }

    private long backoff(int attempt) {
        long delay = limits.baseDelayMs;
        for (int i = 0; i < attempt && delay < limits.maxDelayMs; i++) delay *= 2;
        return Math.min(delay, limits.maxDelayMs);
    }
}
