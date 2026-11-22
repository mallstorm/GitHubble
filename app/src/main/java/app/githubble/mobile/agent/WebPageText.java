package app.githubble.mobile.agent;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure helpers for reading web pages as plain text: URL policy and HTML → readable text. */
public final class WebPageText {
    private WebPageText() {}

    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    /**
     * Only public https pages: no credentials, no IP literals, no local names, no odd ports.
     * (The HTTP client additionally refuses private addresses at DNS level.)
     */
    public static String checkUrl(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || value.length() > 2000) throw new IllegalArgumentException("Адрес страницы: 1–2000 символов");
        URI uri;
        try { uri = new URI(value); } catch (Exception bad) { throw new IllegalArgumentException("Некорректный адрес"); }
        if (!"https".equalsIgnoreCase(uri.getScheme())) throw new IllegalArgumentException("Читаются только https-страницы");
        if (uri.getUserInfo() != null) throw new IllegalArgumentException("Адрес с логином и паролем запрещён");
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (host.isEmpty() || !host.contains(".") || host.contains(":") || IPV4.matcher(host).matches() || host.startsWith("[")
                || host.equals("localhost") || host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".localhost") || host.endsWith(".lan"))
            throw new IllegalArgumentException("Недопустимый узел: " + host);
        if (uri.getPort() != -1 && uri.getPort() != 443) throw new IllegalArgumentException("Допустим только порт 443");
        return stripFragment(value);
    }

    private static String stripFragment(String value) {
        int hash = value.indexOf('#');
        return hash < 0 ? value : value.substring(0, hash);
    }

    public static String title(String html) {
        Matcher m = Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(html == null ? "" : html);
        return m.find() ? collapse(decode(strip(m.group(1)))).trim() : "";
    }

    /** Readable text of an HTML page; main content when the page marks it, headings and lists kept as Markdown-ish lines. */
    public static String extractText(String html, int maxChars) {
        if (html == null) return "";
        String s = html;
        s = s.replaceAll("(?is)<!--.*?-->", " ").replaceAll("(?is)<head\\b[^>]*>.*?</head>", " ");
        s = s.replaceAll("(?is)<(script|style|noscript|svg|template|iframe|canvas|select|button|form)\\b[^>]*>.*?</\\1>", " ");
        String main = firstBlock(s, "article");
        if (main.length() < 400) main = firstBlock(s, "main");
        if (main.length() >= 400) s = main;
        else s = s.replaceAll("(?is)<(nav|footer|header|aside)\\b[^>]*>.*?</\\1>", " ");
        s = s.replaceAll("(?is)<h([1-6])\\b[^>]*>", "\n\n# ");
        s = s.replaceAll("(?is)</h[1-6]>", "\n");
        s = s.replaceAll("(?is)<li\\b[^>]*>", "\n- ");
        s = s.replaceAll("(?is)<(br|hr)\\b[^>]*>", "\n");
        s = s.replaceAll("(?is)</?(p|div|section|tr|ul|ol|table|pre|blockquote|figure|dd|dt)\\b[^>]*>", "\n");
        s = s.replaceAll("(?is)</t[dh]>", " | ");
        s = strip(s);
        s = decode(s);
        s = s.replace('\u00a0', ' ').replaceAll("[\\t\\x0B\\f\\r ]+", " ");
        s = s.replaceAll(" ?\\n ?", "\n").replaceAll("\\n{3,}", "\n\n").trim();
        return maxChars > 0 && s.length() > maxChars ? s.substring(0, maxChars) : s;
    }

    /** True when the page gave almost no text (JavaScript-rendered shell): a reader fallback may help. */
    public static boolean thin(String text) { return text == null || text.trim().length() < 300; }

    private static String firstBlock(String html, String tag) {
        Matcher m = Pattern.compile("(?is)<" + tag + "\\b[^>]*>(.*?)</" + tag + ">").matcher(html);
        String best = "";
        while (m.find()) if (m.group(1).length() > best.length()) best = m.group(1);
        return best;
    }

    private static String strip(String s) { return s.replaceAll("(?is)<[^>]+>", ""); }
    private static String collapse(String s) { return s.replaceAll("\\s+", " "); }

    static String decode(String s) {
        s = s.replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&mdash;", "—").replace("&ndash;", "–").replace("&hellip;", "…").replace("&laquo;", "«").replace("&raquo;", "»");
        Matcher m = Pattern.compile("&#(x?)([0-9a-fA-F]{1,6});").matcher(s);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String repl = " ";
            try {
                int cp = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
                if (Character.isValidCodePoint(cp)) repl = new String(Character.toChars(cp));
            } catch (NumberFormatException ignored) { }
            m.appendReplacement(out, Matcher.quoteReplacement(repl));
        }
        m.appendTail(out);
        return out.toString().replace("&amp;", "&");
    }
}
