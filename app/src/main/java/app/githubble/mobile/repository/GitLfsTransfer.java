package app.githubble.mobile.repository;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/**
 * Copies Git LFS objects between two GitHub repositories through the LFS batch API.
 * The object is verified against the SHA-256 oid of its pointer before it is uploaded,
 * so a corrupted or substituted download can never be stored in the destination.
 */
public final class GitLfsTransfer {
    public static final long MAX_OBJECT_BYTES = 32L * 1024 * 1024;
    public static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;
    private static final String TYPE = "application/vnd.git-lfs+json";
    private static final Pattern POINTER = Pattern.compile(
            "^version https://git-lfs\\.github\\.com/spec/v1\\n(?:ext-[^\\n]*\\n)*oid sha256:([0-9a-f]{64})\\nsize ([0-9]{1,15})\\n?$");

    public static final class Pointer {
        public final String oid;
        public final long size;
        Pointer(String oid, long size) { this.oid = oid; this.size = size; }
    }

    public static final class Response {
        public final int code;
        public final byte[] body;
        public Response(int code, byte[] body) { this.code = code; this.body = body; }
        String text() { return new String(body, StandardCharsets.UTF_8); }
    }

    /** HTTP seam; the production implementation is {@link #http()}. */
    public interface Transport {
        Response send(String method, String url, Map<String, String> headers, byte[] body)
                throws Exception;
    }

    private final Transport transport;
    public GitLfsTransfer(Transport transport) { this.transport = transport; }

    /** @return the pointer, or null when the text is not a Git LFS pointer. */
    public static Pointer parsePointer(byte[] content) {
        if (content == null || content.length > 1024) return null;
        Matcher m = POINTER.matcher(new String(content, StandardCharsets.UTF_8));
        if (!m.matches()) return null;
        long size = Long.parseLong(m.group(2));
        return new Pointer(m.group(1), size);
    }

    public static boolean looksLikePointer(byte[] content) {
        return content != null && content.length < 1024 && new String(content, StandardCharsets.UTF_8)
                .startsWith("version https://git-lfs.github.com/spec/v1");
    }

    static String sha256(byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder out = new StringBuilder();
        for (byte b : digest) out.append(String.format(Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }

    private static String endpoint(String repo) {
        return "https://github.com/" + repo + ".git/info/lfs/objects/batch";
    }

    private static Map<String, String> batchHeaders(String login, String token) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Accept", TYPE);
        h.put("Content-Type", TYPE);
        h.put("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                (login + ":" + token).getBytes(StandardCharsets.UTF_8)));
        return h;
    }

    private JSONObject batch(String repo, String login, String token, String operation, Pointer p)
            throws Exception {
        JSONObject body = new JSONObject().put("operation", operation)
                .put("transfers", new org.json.JSONArray().put("basic"))
                .put("objects", new org.json.JSONArray().put(
                        new JSONObject().put("oid", p.oid).put("size", p.size)));
        Response r = transport.send("POST", endpoint(repo), batchHeaders(login, token),
                body.toString().getBytes(StandardCharsets.UTF_8));
        if (r.code == 401 || r.code == 403)
            throw new SecurityException("Нет доступа к Git LFS репозитория " + repo);
        if (r.code != 200)
            throw new IllegalStateException("Git LFS ответил HTTP " + r.code + " для " + repo);
        JSONObject root = new JSONObject(r.text());
        org.json.JSONArray objects = root.optJSONArray("objects");
        if (objects == null || objects.length() != 1)
            throw new IllegalStateException("Некорректный ответ Git LFS");
        JSONObject object = objects.getJSONObject(0);
        if (!p.oid.equals(object.optString("oid")))
            throw new SecurityException("Git LFS вернул другой объект");
        JSONObject error = object.optJSONObject("error");
        if (error != null)
            throw new IllegalStateException("Git LFS: " + error.optString("message", "ошибка объекта"));
        return object;
    }

    private static Map<String, String> actionHeaders(JSONObject action) {
        Map<String, String> headers = new LinkedHashMap<>();
        JSONObject raw = action.optJSONObject("header");
        if (raw != null) for (Iterator<String> it = raw.keys(); it.hasNext(); ) {
            String key = it.next();
            headers.put(key, raw.optString(key));
        }
        return headers;
    }

    private static String https(JSONObject action) {
        String href = action.optString("href", "");
        if (!href.startsWith("https://"))
            throw new SecurityException("Git LFS предложил небезопасный адрес");
        return href;
    }

    public byte[] download(String repo, String login, String token, Pointer p) throws Exception {
        if (p.size > MAX_OBJECT_BYTES)
            throw new IllegalStateException("LFS-объект больше " + (MAX_OBJECT_BYTES >> 20) + " МиБ");
        JSONObject actions = batch(repo, login, token, "download", p).optJSONObject("actions");
        JSONObject download = actions == null ? null : actions.optJSONObject("download");
        if (download == null) throw new IllegalStateException("LFS-объект недоступен в источнике");
        Response r = transport.send("GET", https(download), actionHeaders(download), null);
        if (r.code != 200) throw new IllegalStateException("Загрузка LFS-объекта: HTTP " + r.code);
        if (r.body.length != p.size || !p.oid.equals(sha256(r.body)))
            throw new SecurityException("LFS-объект не совпал с SHA-256 указателя");
        return r.body;
    }

    /** Uploads the verified bytes; objects that already exist in the destination are skipped. */
    public void upload(String repo, String login, String token, Pointer p, byte[] data)
            throws Exception {
        if (data.length != p.size || !p.oid.equals(sha256(data)))
            throw new SecurityException("LFS-данные не совпали с указателем");
        JSONObject object = batch(repo, login, token, "upload", p);
        JSONObject actions = object.optJSONObject("actions");
        JSONObject upload = actions == null ? null : actions.optJSONObject("upload");
        if (upload == null) return; // already stored
        Map<String, String> headers = actionHeaders(upload);
        if (!headers.containsKey("Content-Type")) headers.put("Content-Type", "application/octet-stream");
        Response put = transport.send("PUT", https(upload), headers, data);
        if (put.code < 200 || put.code > 299)
            throw new IllegalStateException("Отправка LFS-объекта: HTTP " + put.code
                    + (put.code == 403 ? " (возможно, исчерпана квота LFS)" : ""));
        JSONObject verify = actions.optJSONObject("verify");
        if (verify != null) {
            Map<String, String> vh = actionHeaders(verify);
            vh.put("Accept", TYPE); vh.put("Content-Type", TYPE);
            Response v = transport.send("POST", https(verify), vh, new JSONObject()
                    .put("oid", p.oid).put("size", p.size).toString().getBytes(StandardCharsets.UTF_8));
            if (v.code < 200 || v.code > 299)
                throw new IllegalStateException("Проверка LFS-объекта: HTTP " + v.code);
        }
    }

    public static Transport http() {
        return (method, url, headers, body) -> {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(20000);
            c.setReadTimeout(120000);
            c.setInstanceFollowRedirects(true);
            for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
            if (body != null) {
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(body.length);
                try (OutputStream out = c.getOutputStream()) { out.write(body); }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            if (in != null) try (InputStream stream = in) {
                byte[] chunk = new byte[16384];
                for (int n; (n = stream.read(chunk)) >= 0; ) {
                    buffer.write(chunk, 0, n);
                    if (buffer.size() > MAX_OBJECT_BYTES + 4096)
                        throw new IllegalStateException("Ответ LFS превышает допустимый размер");
                }
            }
            c.disconnect();
            return new Response(code, buffer.toByteArray());
        };
    }
}
