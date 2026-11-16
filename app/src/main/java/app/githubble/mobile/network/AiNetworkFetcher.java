package app.githubble.mobile.network;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Production fetcher for the endpoint check: the app's own network stack and short timeouts. Redirects
 * are followed (at most twice) only to the same host over HTTPS, so the key never reaches another site.
 */
public final class AiNetworkFetcher implements EndpointProbe.Fetcher {
    @Override public EndpointProbe.Reply send(String method, String url, Map<String, String> headers, String body) throws Exception {
        String current = url;
        for (int hop = 0; hop < 3; hop++) {
            long started = System.nanoTime();
            HttpURLConnection connection = AiNetwork.open(current);
            try {
                connection.setRequestMethod(method);
                connection.setConnectTimeout(7000);
                connection.setReadTimeout("POST".equals(method) ? 25000 : 9000);
                connection.setInstanceFollowRedirects(false);
                for (Map.Entry<String, String> header : headers.entrySet()) connection.setRequestProperty(header.getKey(), header.getValue());
                if (body != null) {
                    connection.setDoOutput(true);
                    try (OutputStream out = connection.getOutputStream()) { out.write(body.getBytes(StandardCharsets.UTF_8)); }
                }
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String next = sameHostTarget(current, connection.getHeaderField("Location"));
                    if (next != null && hop < 2) { current = next; continue; }
                }
                String text;
                try (InputStream input = status >= 200 && status < 400 ? connection.getInputStream() : connection.getErrorStream()) {
                    text = read(input);
                }
                String server = connection.getHeaderField("Server");
                return new EndpointProbe.Reply(status, text, server, (System.nanoTime() - started) / 1_000_000);
            } finally {
                connection.disconnect();
            }
        }
        return new EndpointProbe.Reply(310, "", "", 0);
    }

    /** Absolute target of a redirect when it stays on the same host and keeps HTTPS (or local HTTP); else null. */
    static String sameHostTarget(String from, String location) {
        if (location == null || location.isEmpty()) return null;
        try {
            URI base = URI.create(from);
            URI target = base.resolve(location);
            if (target.getHost() == null || !target.getHost().equalsIgnoreCase(base.getHost())) return null;
            if (target.getRawQuery() != null) return null;
            boolean secure = "https".equalsIgnoreCase(target.getScheme());
            boolean sameScheme = base.getScheme().equalsIgnoreCase(target.getScheme());
            if (!secure && !sameScheme) return null;
            return target.toString();
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static String read(InputStream input) throws Exception {
        if (input == null) return "";
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int n, total = 0;
            while ((n = input.read(buffer)) != -1) {
                total += n;
                if (total > 512 * 1024) break;
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
