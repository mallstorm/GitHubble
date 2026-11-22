package app.githubble.mobile.translation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Which connected AI translates. Pure rules, no Android: order of preference and model choice.
 * Subscriptions the user already pays for come first (ChatGPT, Grok), then keys with a free tier,
 * then whatever is selected in the agent.
 */
public final class TranslationRoute {
    private TranslationRoute() {}
    public static final String AUTO = "auto";

    /** Most preferred first. Only connected services are used. */
    static final List<String> ORDER = Arrays.asList(
            "chatgpt-plan", "openai-subscription", "grok-subscription", "claude-subscription", "kimi-subscription",
            "gemini", "groq", "cerebras", "openrouter-account", "kilo-account", "mistral", "deepseek",
            "openai", "anthropic", "xai", "cline-subscription", "github-copilot");

    /** @param preferred AUTO or a service id; @param configured connected services; @param selected agent's current service */
    public static List<String> order(String preferred, Collection<String> configured, String selected) {
        List<String> result = new ArrayList<>();
        if (preferred != null && !preferred.isEmpty() && !AUTO.equals(preferred)) {
            if (configured.contains(preferred)) result.add(preferred);
            return result;
        }
        for (String id : ORDER) if (configured.contains(id)) result.add(id);
        if (selected != null && configured.contains(selected) && !result.contains(selected)) result.add(selected);
        for (String id : configured) if (!result.contains(id) && !id.startsWith("custom") && !"proxy-external".equals(id)) result.add(id);
        return result;
    }

    private static final String[] SMALL = {"mini", "flash", "lite", "haiku", "nano", "instant", "small", "fast", "turbo"};
    private static final String[] NOT_TEXT = {"image", "audio", "tts", "embed", "realtime", "transcribe", "moderation", "dall", "whisper",
            "search", "vision", "live", "robotics", "computer-use", "guard", "rerank"};

    /** A fast text model of the account if there is one, otherwise the model the user picked. */
    public static String pickModel(String stored, List<String> available) {
        if (available != null) for (String key : SMALL) for (String model : available) {
            String lower = model.toLowerCase(Locale.ROOT);
            if (lower.contains(key) && !notText(lower)) return model;
        }
        if (stored != null && !stored.isEmpty()) return stored;
        if (available != null) for (String model : available) if (!notText(model.toLowerCase(Locale.ROOT))) return model;
        return "";
    }

    static boolean notText(String lower) { for (String bad : NOT_TEXT) if (lower.contains(bad)) return true; return false; }
}
