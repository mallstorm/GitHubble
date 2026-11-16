package app.githubble.mobile.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Pure routing/quota rules for a pool of API keys of one provider (no Android dependencies).
 *
 * Behaviour mirrors a multi-account gateway: several keys per service, every key has its own
 * statistics and optional limits, a key can be switched off (it then leaves rotation AND the total
 * quota), and a failing key is paused or marked exhausted so that the next one is used.
 */
public final class PoolPolicy {
    private PoolPolicy() {}

    public static final int PRIORITY = 0, ROUND_ROBIN = 1, LEAST_USED = 2, MOST_QUOTA = 3;
    public static final int RESET_NEVER = 0, RESET_DAILY = 1, RESET_MONTHLY = 2;
    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final long MONTH = 30L * DAY;

    public static String strategyName(int strategy) {
        switch (strategy) {
            case ROUND_ROBIN: return "По кругу";
            case LEAST_USED: return "Наименее используемый";
            case MOST_QUOTA: return "Больше остаток квоты";
            default: return "По порядку (приоритет)";
        }
    }

    public static String strategyHint(int strategy) {
        switch (strategy) {
            case ROUND_ROBIN: return "Запросы чередуются между доступными ключами";
            case LEAST_USED: return "Берётся ключ с наименьшим числом запросов за период";
            case MOST_QUOTA: return "Берётся ключ с наибольшей долей свободной квоты";
            default: return "Первый доступный ключ в списке; остальные — запасные";
        }
    }

    /** What went wrong with a call; decides whether the next key may be tried. */
    public enum Failure { AUTH, RATE_LIMIT, QUOTA, TRANSIENT, REQUEST }

    public enum Status { OFF, EXHAUSTED, LIMIT, AUTH_ERROR, COOLDOWN, OK }

    public static final class Slot {
        public String id = "", label = "";
        public boolean enabled = true, exhausted;
        public long quotaTokens, quotaRequests;
        public int resetMode;
        public long periodStart, periodTokens, periodRequests;
        public long calls, okCalls, failCalls, inTokens, outTokens;
        public long lastUsed, lastErrorAt, cooldownUntil, createdAt;
        public int consecutiveFails;
        public String lastError = "", failKind = "";

        public JSONObject toJson() {
            try {
                return new JSONObject().put("id", id).put("label", label).put("enabled", enabled)
                        .put("exhausted", exhausted).put("quotaTokens", quotaTokens)
                        .put("quotaRequests", quotaRequests).put("resetMode", resetMode)
                        .put("periodStart", periodStart).put("periodTokens", periodTokens)
                        .put("periodRequests", periodRequests).put("calls", calls).put("okCalls", okCalls)
                        .put("failCalls", failCalls).put("inTokens", inTokens).put("outTokens", outTokens)
                        .put("lastUsed", lastUsed).put("lastErrorAt", lastErrorAt)
                        .put("cooldownUntil", cooldownUntil).put("createdAt", createdAt)
                        .put("consecutiveFails", consecutiveFails).put("lastError", lastError)
                        .put("failKind", failKind);
            } catch (org.json.JSONException impossible) {
                throw new IllegalStateException(impossible);
            }
        }

        public static Slot fromJson(JSONObject j) {
            Slot s = new Slot();
            s.id = j.optString("id"); s.label = j.optString("label");
            s.enabled = j.optBoolean("enabled", true); s.exhausted = j.optBoolean("exhausted");
            s.quotaTokens = Math.max(0, j.optLong("quotaTokens")); s.quotaRequests = Math.max(0, j.optLong("quotaRequests"));
            s.resetMode = j.optInt("resetMode"); s.periodStart = j.optLong("periodStart");
            s.periodTokens = Math.max(0, j.optLong("periodTokens")); s.periodRequests = Math.max(0, j.optLong("periodRequests"));
            s.calls = j.optLong("calls"); s.okCalls = j.optLong("okCalls"); s.failCalls = j.optLong("failCalls");
            s.inTokens = j.optLong("inTokens"); s.outTokens = j.optLong("outTokens");
            s.lastUsed = j.optLong("lastUsed"); s.lastErrorAt = j.optLong("lastErrorAt");
            s.cooldownUntil = j.optLong("cooldownUntil"); s.createdAt = j.optLong("createdAt");
            s.consecutiveFails = j.optInt("consecutiveFails");
            s.lastError = j.optString("lastError"); s.failKind = j.optString("failKind");
            return s;
        }

        public Slot copy() { return fromJson(toJson()); }
        public long totalTokens() { return inTokens + outTokens; }
    }

    public static final class State {
        public int strategy = PRIORITY;
        public int cursor;
        public final List<Slot> slots = new ArrayList<>();

        public String toJson() {
            try {
                JSONArray list = new JSONArray();
                for (Slot s : slots) list.put(s.toJson());
                return new JSONObject().put("strategy", strategy).put("cursor", cursor).put("slots", list).toString();
            } catch (org.json.JSONException impossible) {
                throw new IllegalStateException(impossible);
            }
        }

        public static State fromJson(String raw) {
            State state = new State();
            if (raw == null || raw.isEmpty()) return state;
            try {
                JSONObject j = new JSONObject(raw);
                state.strategy = Math.max(0, Math.min(MOST_QUOTA, j.optInt("strategy")));
                state.cursor = Math.max(0, j.optInt("cursor"));
                JSONArray list = j.optJSONArray("slots");
                if (list != null) for (int i = 0; i < list.length(); i++) {
                    JSONObject item = list.optJSONObject(i);
                    if (item != null && !item.optString("id").isEmpty()) state.slots.add(Slot.fromJson(item));
                }
            } catch (org.json.JSONException damaged) {
                return new State();
            }
            return state;
        }

        public Slot find(String id) {
            for (Slot s : slots) if (s.id.equals(id)) return s;
            return null;
        }
    }

    public static final class Summary {
        public int total, enabled, usable;
        public long calls, okCalls, failCalls, inTokens, outTokens;
        /** Sums over enabled keys that have a limit; zero means no key has one. */
        public long quotaTokens, usedOfQuotaTokens, quotaRequests, usedOfQuotaRequests;
        public long remainingTokens() { return Math.max(0, quotaTokens - usedOfQuotaTokens); }
        public long remainingRequests() { return Math.max(0, quotaRequests - usedOfQuotaRequests); }
        public boolean hasTokenQuota() { return quotaTokens > 0; }
        public boolean hasRequestQuota() { return quotaRequests > 0; }
    }

    // ------------------------------------------------------------------ state of one key

    public static boolean overQuota(Slot s) {
        return s.quotaTokens > 0 && s.periodTokens >= s.quotaTokens
                || s.quotaRequests > 0 && s.periodRequests >= s.quotaRequests;
    }

    public static boolean usable(Slot s, long now) {
        return s.enabled && !s.exhausted && s.cooldownUntil <= now && !overQuota(s);
    }

    public static Status status(Slot s, long now) {
        if (!s.enabled) return Status.OFF;
        if (s.exhausted) return Status.EXHAUSTED;
        if (overQuota(s)) return Status.LIMIT;
        if (s.cooldownUntil > now) return "auth".equals(s.failKind) ? Status.AUTH_ERROR : Status.COOLDOWN;
        return Status.OK;
    }

    /** Starts a fresh counting window when the configured period has elapsed. */
    public static void rollPeriod(Slot s, long now) {
        if (s.periodStart <= 0) { s.periodStart = now; return; }
        long length = s.resetMode == RESET_DAILY ? DAY : s.resetMode == RESET_MONTHLY ? MONTH : 0;
        if (length > 0 && now - s.periodStart >= length) {
            long windows = (now - s.periodStart) / length;
            s.periodStart += windows * length;
            s.periodTokens = 0; s.periodRequests = 0;
            if (s.exhausted && "quota".equals(s.failKind)) { s.exhausted = false; s.failKind = ""; }
        }
    }

    public static void setEnabled(Slot s, boolean on) {
        s.enabled = on;
        if (on) { s.exhausted = false; s.cooldownUntil = 0; s.consecutiveFails = 0; s.failKind = ""; }
    }

    public static void resetStats(Slot s, long now) {
        s.calls = 0; s.okCalls = 0; s.failCalls = 0; s.inTokens = 0; s.outTokens = 0;
        s.periodTokens = 0; s.periodRequests = 0; s.periodStart = now;
        s.lastError = ""; s.lastErrorAt = 0; s.failKind = ""; s.exhausted = false;
        s.cooldownUntil = 0; s.consecutiveFails = 0;
    }

    // ------------------------------------------------------------------ choosing a key

    /** Picks the next key (or null when none can be used) and advances the rotation cursor. */
    public static Slot pick(State state, Set<String> exclude, long now) {
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < state.slots.size(); i++) {
            Slot s = state.slots.get(i);
            rollPeriod(s, now);
            if (usable(s, now) && (exclude == null || !exclude.contains(s.id))) candidates.add(i);
        }
        if (candidates.isEmpty()) return null;
        int chosen = candidates.get(0);
        switch (state.strategy) {
            case ROUND_ROBIN: {
                int start = state.cursor % Math.max(1, state.slots.size());
                chosen = -1;
                for (int step = 0; step < state.slots.size() && chosen < 0; step++) {
                    int index = (start + step) % state.slots.size();
                    if (candidates.contains(index)) chosen = index;
                }
                if (chosen < 0) chosen = candidates.get(0);
                state.cursor = chosen + 1;
                break;
            }
            case LEAST_USED: {
                for (int index : candidates)
                    if (state.slots.get(index).periodRequests < state.slots.get(chosen).periodRequests) chosen = index;
                break;
            }
            case MOST_QUOTA: {
                double best = headroom(state.slots.get(chosen));
                for (int index : candidates) {
                    double value = headroom(state.slots.get(index));
                    if (value > best) { best = value; chosen = index; }
                }
                break;
            }
            default:
                break;
        }
        return state.slots.get(chosen);
    }

    static double headroom(Slot s) {
        double tokens = s.quotaTokens > 0 ? 1.0 - (double) s.periodTokens / s.quotaTokens : 1.0;
        double requests = s.quotaRequests > 0 ? 1.0 - (double) s.periodRequests / s.quotaRequests : 1.0;
        return Math.min(tokens, requests);
    }

    // ------------------------------------------------------------------ results of a call

    public static Failure classify(int httpStatus, String message) {
        String text = message == null ? "" : message.toLowerCase(Locale.ROOT);
        boolean quotaWords = text.contains("insufficient_quota") || text.contains("insufficient quota")
                || text.contains("exceeded your current quota") || text.contains("quota exceeded")
                || text.contains("out of credits") || text.contains("insufficient balance")
                || text.contains("insufficient funds") || text.contains("billing") || text.contains("недостаточно средств")
                || text.contains("credit balance") || text.contains("payment required");
        if (httpStatus == 402) return Failure.QUOTA;
        if (httpStatus == 401 || httpStatus == 403) return quotaWords ? Failure.QUOTA : Failure.AUTH;
        if (httpStatus == 429) return quotaWords ? Failure.QUOTA : Failure.RATE_LIMIT;
        if (httpStatus == 408 || httpStatus >= 500) return Failure.TRANSIENT;
        if (httpStatus >= 400) return quotaWords ? Failure.QUOTA : Failure.REQUEST;
        if (quotaWords) return Failure.QUOTA;
        if (text.contains("invalid api key") || text.contains("invalid_api_key") || text.contains("incorrect api key")
                || text.contains("unauthorized")) return Failure.AUTH;
        if (text.contains("rate limit") || text.contains("too many requests")) return Failure.RATE_LIMIT;
        if (text.contains("timeout") || text.contains("timed out") || text.contains("unable to resolve")
                || text.contains("connection reset") || text.contains("failed to connect")
                || text.contains("unexpected end of stream") || text.contains("software caused connection abort")
                || text.contains("canceled")) return Failure.TRANSIENT;
        return Failure.REQUEST;
    }

    /** Only key-related and infrastructure failures justify trying another key. */
    public static boolean failover(Failure failure) { return failure != Failure.REQUEST; }

    public static void onSuccess(Slot s, long input, long output, boolean reported, long now) {
        rollPeriod(s, now);
        s.calls++; s.okCalls++; s.periodRequests++;
        if (reported) {
            long in = Math.max(0, input), out = Math.max(0, output);
            s.inTokens += in; s.outTokens += out; s.periodTokens += in + out;
        }
        s.lastUsed = now; s.cooldownUntil = 0; s.consecutiveFails = 0;
        if (!s.exhausted) s.failKind = "";
    }

    public static void onFailure(Slot s, Failure failure, String message, long now) {
        rollPeriod(s, now);
        s.calls++; s.failCalls++; s.consecutiveFails++;
        s.lastUsed = now; s.lastErrorAt = now;
        String text = message == null ? "" : message.trim();
        s.lastError = text.length() > 220 ? text.substring(0, 220) : text;
        switch (failure) {
            case QUOTA:
                s.exhausted = true; s.failKind = "quota"; break;
            case AUTH:
                s.failKind = "auth"; s.cooldownUntil = now + 60L * 60 * 1000; break;
            case RATE_LIMIT:
                s.failKind = "rate";
                s.cooldownUntil = now + Math.min(15L * 60 * 1000, 30_000L << Math.min(5, s.consecutiveFails - 1));
                break;
            case TRANSIENT:
                s.failKind = "net"; s.cooldownUntil = now + 10_000L; break;
            default:
                s.failKind = "request"; break;
        }
    }

    // ------------------------------------------------------------------ totals

    /** Totals over ENABLED keys only: switching a key off removes it from the shared quota. */
    public static Summary summarize(List<Slot> slots, long now) {
        Summary sum = new Summary();
        sum.total = slots.size();
        for (Slot original : slots) {
            Slot s = original;
            if (!s.enabled) continue;
            sum.enabled++;
            if (usable(s, now)) sum.usable++;
            sum.calls += s.calls; sum.okCalls += s.okCalls; sum.failCalls += s.failCalls;
            sum.inTokens += s.inTokens; sum.outTokens += s.outTokens;
            if (s.quotaTokens > 0) { sum.quotaTokens += s.quotaTokens; sum.usedOfQuotaTokens += Math.min(s.periodTokens, s.quotaTokens); }
            if (s.quotaRequests > 0) { sum.quotaRequests += s.quotaRequests; sum.usedOfQuotaRequests += Math.min(s.periodRequests, s.quotaRequests); }
        }
        return sum;
    }

    /** Human-readable compact number, e.g. 1.2M. */
    public static String compact(long value) {
        if (value >= 1_000_000_000L) return String.format(Locale.ROOT, "%.1fB", value / 1e9);
        if (value >= 1_000_000L) return String.format(Locale.ROOT, "%.1fM", value / 1e6);
        if (value >= 10_000L) return String.format(Locale.ROOT, "%.0fK", value / 1e3);
        if (value >= 1_000L) return String.format(Locale.ROOT, "%.1fK", value / 1e3);
        return Long.toString(value);
    }
}
