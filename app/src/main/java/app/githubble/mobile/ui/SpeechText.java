package app.githubble.mobile.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pure text rules for reading messages aloud. */
public final class SpeechText {
    private SpeechText() {}

    public static Locale localeOf(String code) {
        switch (code == null ? "" : code) {
            case "en": return Locale.ENGLISH;
            case "ko": return Locale.KOREAN;
            case "ja": return Locale.JAPANESE;
            case "zh": return Locale.CHINESE;
            case "uk": return new Locale("uk");
            case "ar": return new Locale("ar");
            default: return new Locale("ru");
        }
    }

    /** Markdown → what a person would say: no code blocks, no URLs, no markup symbols. */
    public static String speakable(String markdown) {
        String s = markdown == null ? "" : markdown;
        s = s.replaceAll("(?s)```.*?```", " ");
        s = s.replaceAll("!\\[([^\\]]*)\\]\\([^)]*\\)", "$1");
        s = s.replaceAll("\\[([^\\]]+)\\]\\((?:https?|githubble[-a-z]*)://[^)]*\\)", "$1");
        s = s.replaceAll("https?://\\S+", " ");
        s = s.replaceAll("`([^`]*)`", "$1");
        s = s.replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", "");
        s = s.replaceAll("(?m)^\\s*[-*+]\\s+", "");
        s = s.replaceAll("(?m)^\\s*\\d+\\.\\s+", "");
        s = s.replaceAll("(?m)^\\s*>\\s?", "");
        s = s.replaceAll("[*_~|]{1,3}", "");
        s = s.replaceAll("[ \\t]+", " ").replaceAll("\\n{2,}", "\n").trim();
        return s;
    }

    /** Splits on sentence/line boundaries so no piece exceeds the engine limit. */
    public static List<String> chunks(String text, int max) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String sentence : text.split("(?<=[.!?…\\n])\\s*")) {
            if (sentence.isEmpty()) continue;
            while (sentence.length() > max) {
                if (cur.length() > 0) { out.add(cur.toString().trim()); cur.setLength(0); }
                int cut = sentence.lastIndexOf(' ', max);
                if (cut < max / 2) cut = max;
                out.add(sentence.substring(0, cut).trim());
                sentence = sentence.substring(cut).trim();
            }
            if (cur.length() + sentence.length() + 1 > max && cur.length() > 0) { out.add(cur.toString().trim()); cur.setLength(0); }
            if (cur.length() > 0) cur.append(' ');
            cur.append(sentence);
        }
        if (cur.length() > 0) out.add(cur.toString().trim());
        return out;
    }
}
