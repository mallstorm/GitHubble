package app.githubble.mobile.agent;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * How much one run of the agent may do before it stops and asks to continue: model calls ("rounds"), tool calls and tokens.
 * The user picks it in Settings; each new message (or "Продолжить") opens a fresh window of this size.
 * Without {@link #init} (unit tests) the standard preset applies.
 */
public final class AgentQuota {
    private AgentQuota() {}

    public static final class Preset {
        public final String name;
        public final int rounds, tools;
        public final long tokens;
        Preset(String name, int rounds, int tools, long tokens) {
            this.name = name; this.rounds = rounds; this.tools = tools; this.tokens = tokens;
        }
    }

    public static final Preset[] PRESETS = {
        new Preset("Стандартный", 12, 100, 180_000L),
        new Preset("Расширенный", 40, 300, 600_000L),
        new Preset("Большой", 100, 800, 2_000_000L),
        new Preset("Очень большой", 300, 3000, 10_000_000L),
    };
    private static final String FILE = "githubble_agent_quota";
    private static volatile int rounds = PRESETS[0].rounds, tools = PRESETS[0].tools;
    private static volatile long tokens = PRESETS[0].tokens;

    /** Loads the saved choice; a first start gets the "Расширенный" preset. */
    public static void init(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        Preset first = PRESETS[1];
        set(prefs.getInt("rounds", first.rounds), prefs.getInt("tools", first.tools), prefs.getLong("tokens", first.tokens));
    }

    public static void save(Context context, int newRounds, int newTools, long newTokens) {
        set(newRounds, newTools, newTokens);
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt("rounds", rounds).putInt("tools", tools).putLong("tokens", tokens).apply();
    }

    static void set(int newRounds, int newTools, long newTokens) {
        rounds = Math.max(1, Math.min(newRounds, 5000));
        tools = Math.max(1, Math.min(newTools, 50_000));
        tokens = Math.max(20_000L, Math.min(newTokens, 200_000_000L));
    }

    public static int rounds() { return rounds; }
    public static int tools() { return tools; }
    public static long tokens() { return tokens; }

    public static String describe() {
        return rounds + " раундов · " + tools + " инструментов · " + format(tokens) + " токенов";
    }

    public static String format(long value) {
        if (value >= 1_000_000L) return (value % 1_000_000L == 0 ? String.valueOf(value / 1_000_000L) : String.format(java.util.Locale.US, "%.1f", value / 1_000_000.0)) + " млн";
        return (value / 1000) + "k";
    }

    /** Index of the preset equal to the current values, or -1 for custom values. */
    public static int currentPreset() {
        for (int i = 0; i < PRESETS.length; i++)
            if (PRESETS[i].rounds == rounds && PRESETS[i].tools == tools && PRESETS[i].tokens == tokens) return i;
        return -1;
    }
}
