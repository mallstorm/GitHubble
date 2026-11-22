package app.githubble.mobile.ui;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Web sources an answer cites: distinct https links found in its Markdown. Pure, no Android. */
public final class MessageSources {
    private MessageSources() {}

    public static final class Source {
        public final String host, url, title;
        Source(String host, String url, String title) { this.host = host; this.url = url; this.title = title; }
    }

    private static final Pattern LINK = Pattern.compile("\\[([^\\]]{1,200})\\]\\((https://[^)\\s]+)\\)|(https://[^\\s)<>\\]]+)");

    /** Distinct pages in order of appearance; a link with text keeps its text as the title. GitHub-internal and app links are not sources. */
    public static List<Source> extract(String markdown) {
        Map<String, Source> found = new LinkedHashMap<>();
        Matcher m = LINK.matcher(markdown == null ? "" : markdown);
        while (m.find()) {
            String url = clean(m.group(2) != null ? m.group(2) : m.group(3));
            String title = m.group(1) == null ? "" : m.group(1).trim();
            String host = hostOf(url);
            if (host.isEmpty()) continue;
            String key = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
            if (!found.containsKey(key)) found.put(key, new Source(host, url, title.isEmpty() ? host : title));
        }
        return new ArrayList<>(found.values());
    }

    private static String clean(String url) {
        String u = url;
        while (!u.isEmpty() && ".,;:!?»”'\"".indexOf(u.charAt(u.length() - 1)) >= 0) u = u.substring(0, u.length() - 1);
        int hash = u.indexOf('#');
        return hash < 0 ? u : u.substring(0, hash);
    }

    static String hostOf(String url) {
        try {
            String host = new URI(url).getHost();
            if (host == null) return "";
            host = host.toLowerCase(Locale.ROOT);
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (Exception bad) { return ""; }
    }

    /** One-letter badge for a source (like a favicon placeholder). */
    public static String letter(Source source) {
        return source.host.isEmpty() ? "?" : source.host.substring(0, 1).toUpperCase(Locale.ROOT);
    }

    /** "1 источник", "3 источника", "30 источников". */
    public static String count(int n) {
        int mod100 = n % 100, mod10 = n % 10;
        String word = mod100 >= 11 && mod100 <= 14 ? "источников" : mod10 == 1 ? "источник" : mod10 >= 2 && mod10 <= 4 ? "источника" : "источников";
        return n + " " + word;
    }
}
