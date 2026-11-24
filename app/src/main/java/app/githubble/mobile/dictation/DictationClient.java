package app.githubble.mobile.dictation;

import app.githubble.mobile.network.AiNetwork;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Sends recorded speech to Whisper (Groq / OpenAI / own server), Yandex SpeechKit or Google Cloud Speech-to-Text. */
public final class DictationClient {
    public static final int RATE = 16000;

    public static final class Failure extends IOException {
        public final int code;
        public Failure(String message, int code) { super(message); this.code = code; }
    }

    public static final class Outcome {
        public final String text, provider;
        public final List<String> skipped;
        Outcome(String text, String provider, List<String> skipped) { this.text = text; this.provider = provider; this.skipped = skipped; }
    }

    private DictationClient() { }

    /** Tries the services in the user's order; the first one that returns text wins. Runs on a worker thread. */
    public static Outcome transcribe(DictationSettings settings, List<String> order, byte[] pcm, String language) throws IOException {
        if (pcm.length < RATE * 2 * 4 / 10) throw new Failure("Слишком короткая запись", 0);
        if (peak(pcm) < 250) throw new Failure("Тишина — речь не обнаружена", 0);
        List<String> skipped = new ArrayList<>();
        IOException last = null;
        for (String id : order) {
            if (DictationProviders.SYSTEM.equals(id) || !settings.configured(id)) continue;
            try {
                String text = transcribeWith(settings, id, pcm, language);
                if (!text.isEmpty()) return new Outcome(text, id, skipped);
                last = new Failure("Сервис не распознал речь", 0);
            } catch (IOException error) {
                last = error;
                skipped.add(DictationProviders.get(id).title + ": " + error.getMessage());
            }
        }
        throw last == null ? new Failure("Нет настроенного сервиса диктовки", 0) : last;
    }

    public static String transcribeWith(DictationSettings settings, String id, byte[] pcm, String language) throws IOException {
        DictationProviders.Info info = DictationProviders.get(id);
        StringBuilder out = new StringBuilder();
        for (byte[] part : split(pcm, info.chunkSeconds)) {
            String text = one(settings, info, part, language).trim();
            if (text.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(text);
        }
        return out.toString();
    }

    /** Key / endpoint check: one second of silence must be accepted by the service (the text is irrelevant). */
    public static void check(DictationSettings settings, String id) throws IOException {
        one(settings, DictationProviders.get(id), new byte[RATE * 2], settings.language());
    }

    private static String one(DictationSettings s, DictationProviders.Info info, byte[] pcm, String language) throws IOException {
        switch (info.id) {
            case DictationProviders.GROQ: return whisper("https://api.groq.com/openai/v1/audio/transcriptions", s.key(info.id), s.model(info.id), pcm, language);
            case DictationProviders.OPENAI: return whisper("https://api.openai.com/v1/audio/transcriptions", s.key(info.id), s.model(info.id), pcm, language);
            case DictationProviders.CUSTOM: return whisper(whisperUrl(s.extra(info.id)), s.key(info.id), s.model(info.id), pcm, language);
            case DictationProviders.YANDEX: return yandex(s.key(info.id), s.extra(info.id), pcm, language);
            case DictationProviders.GOOGLE: return google(s.key(info.id), pcm, language);
            default: throw new Failure("Этот сервис работает через системное распознавание", 0);
        }
    }

    // ---- Whisper (OpenAI-compatible) ----

    private static String whisper(String url, String key, String model, byte[] pcm, String language) throws IOException {
        String boundary = "----hubble" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream(pcm.length + 1024);
        field(body, boundary, "model", model);
        field(body, boundary, "response_format", "json");
        field(body, boundary, "temperature", "0");
        String iso = isoLanguage(language);
        if (!iso.isEmpty()) field(body, boundary, "language", iso);
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\nContent-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(toWav(pcm));
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        List<String[]> headers = new ArrayList<>();
        if (!key.isEmpty()) headers.add(new String[]{"Authorization", "Bearer " + key});
        String json = post(url, headers, "multipart/form-data; boundary=" + boundary, body.toByteArray());
        try { return new JSONObject(json).optString("text", ""); }
        catch (org.json.JSONException notJson) { return json.trim(); }
    }

    private static void field(ByteArrayOutputStream out, String boundary, String name, String value) throws IOException {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    static String whisperUrl(String endpoint) {
        String base = endpoint == null ? "" : endpoint.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (base.isEmpty()) return "";
        if (!base.startsWith("http://") && !base.startsWith("https://")) base = "http://" + base;
        return base.endsWith("/audio/transcriptions") ? base : base + "/audio/transcriptions";
    }

    // ---- Yandex SpeechKit (sync API, raw LPCM) ----

    private static final Set<String> YANDEX_LANGS = new HashSet<>(Arrays.asList("auto", "de-DE", "en-US", "es-ES", "fi-FI", "fr-FR",
            "he-HE", "it-IT", "kk-KZ", "nl-NL", "pl-PL", "pt-PT", "pt-BR", "ru-RU", "sv-SE", "tr-TR", "uz-UZ"));

    static String yandexLanguage(String language) {
        return language != null && YANDEX_LANGS.contains(language) ? language : "auto";
    }

    private static String yandex(String key, String folder, byte[] pcm, String language) throws IOException {
        String url = "https://stt.api.cloud.yandex.net/speech/v1/stt:recognize?lang=" + yandexLanguage(language)
                + "&format=lpcm&sampleRateHertz=" + RATE + "&topic=general"
                + (folder.isEmpty() ? "" : "&folderId=" + URLEncoder.encode(folder, "UTF-8"));
        List<String[]> headers = new ArrayList<>();
        headers.add(new String[]{"Authorization", (key.startsWith("t1.") ? "Bearer " : "Api-Key ") + key});
        String json = post(url, headers, "application/octet-stream", pcm);
        try { return new JSONObject(json).optString("result", ""); }
        catch (org.json.JSONException e) { throw new Failure("Неверный ответ Яндекса: " + e.getMessage(), 0); }
    }

    // ---- Google Cloud Speech-to-Text v1 ----

    private static String google(String key, byte[] pcm, String language) throws IOException {
        JSONObject config = new JSONObject();
        try {
            config.put("encoding", "LINEAR16").put("sampleRateHertz", RATE).put("enableAutomaticPunctuation", true);
            if (language == null || "auto".equals(language)) {
                config.put("languageCode", "ru-RU").put("alternativeLanguageCodes", new JSONArray().put("en-US"));
            } else config.put("languageCode", language);
            JSONObject request = new JSONObject().put("config", config)
                    .put("audio", new JSONObject().put("content", java.util.Base64.getEncoder().encodeToString(pcm)));
            String json = post("https://speech.googleapis.com/v1/speech:recognize?key=" + URLEncoder.encode(key, "UTF-8"),
                    new ArrayList<>(), "application/json; charset=utf-8", request.toString().getBytes(StandardCharsets.UTF_8));
            return googleText(json);
        } catch (org.json.JSONException e) { throw new Failure("Неверный ответ Google: " + e.getMessage(), 0); }
    }

    static String googleText(String json) throws org.json.JSONException {
        JSONArray results = new JSONObject(json).optJSONArray("results");
        StringBuilder out = new StringBuilder();
        if (results != null) for (int i = 0; i < results.length(); i++) {
            JSONArray alt = results.getJSONObject(i).optJSONArray("alternatives");
            if (alt == null || alt.length() == 0) continue;
            String t = alt.getJSONObject(0).optString("transcript", "").trim();
            if (t.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(t);
        }
        return out.toString();
    }

    // ---- shared ----

    static String isoLanguage(String language) {
        if (language == null || language.isEmpty() || "auto".equals(language)) return "";
        int dash = language.indexOf('-');
        return (dash > 0 ? language.substring(0, dash) : language).toLowerCase(Locale.ROOT);
    }

    private static String post(String url, List<String[]> headers, String contentType, byte[] body) throws IOException {
        if (url.isEmpty()) throw new Failure("Не указан адрес сервиса", 0);
        HttpURLConnection c = AiNetwork.open(url);
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(120000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", contentType);
            for (String[] h : headers) c.setRequestProperty(h[0], h[1]);
            try (OutputStream out = c.getOutputStream()) { out.write(body); }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = in == null ? "" : new String(readAll(in), StandardCharsets.UTF_8);
            if (code >= 400) throw new Failure(friendly(code, text), code);
            return text;
        } finally { c.disconnect(); }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream source = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = source.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    static String friendly(int code, String body) {
        String detail = "";
        try {
            JSONObject json = new JSONObject(body);
            Object error = json.opt("error");
            if (error instanceof JSONObject) detail = ((JSONObject) error).optString("message", "");
            else if (error != null) detail = String.valueOf(error);
            if (detail.isEmpty()) detail = json.optString("message", "");
        } catch (Exception notJson) { detail = body == null ? "" : body.trim(); }
        if (detail.length() > 160) detail = detail.substring(0, 160) + "…";
        String head = code == 401 || code == 403 ? "Ключ отклонён сервисом (" + code + ")"
                : code == 429 ? "Превышен лимит запросов сервиса (429)"
                : code == 413 ? "Запись слишком большая для сервиса (413)"
                : code >= 500 ? "Сервис временно недоступен (" + code + ")"
                : "Ошибка сервиса (" + code + ")";
        return detail.isEmpty() ? head : head + ": " + detail;
    }

    /** Highest absolute sample, 0..32768. */
    static int peak(byte[] pcm) {
        int peak = 0;
        for (int i = 0; i + 1 < pcm.length; i += 2) peak = Math.max(peak, Math.abs((short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8))));
        return peak;
    }

    /** Cuts long audio into pieces of at most {@code seconds}, preferring the quietest moment near each cut. */
    static List<byte[]> split(byte[] pcm, int seconds) {
        List<byte[]> parts = new ArrayList<>();
        int max = Math.max(1, seconds) * RATE * 2;
        if (seconds <= 0 || pcm.length <= max) { parts.add(pcm); return parts; }
        int pos = 0;
        while (pos < pcm.length) {
            int end = Math.min(pcm.length, pos + max);
            if (end < pcm.length) end = quietCut(pcm, Math.max(pos + max / 2, end - RATE * 2 * 2), end);
            end &= ~1;
            parts.add(Arrays.copyOfRange(pcm, pos, end));
            pos = end;
        }
        return parts;
    }

    private static int quietCut(byte[] pcm, int from, int to) {
        int window = RATE / 10 * 2, best = to, bestEnergy = Integer.MAX_VALUE;
        for (int start = from; start + window <= to; start += window / 2) {
            long energy = 0;
            for (int i = start; i + 1 < start + window; i += 2) energy += Math.abs((short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8)));
            int avg = (int) (energy / (window / 2));
            if (avg < bestEnergy) { bestEnergy = avg; best = start + window / 2; }
        }
        return best;
    }

    /** Wraps raw PCM16 mono 16 kHz in a canonical 44-byte WAV header. */
    static byte[] toWav(byte[] pcm) {
        int rate = RATE, byteRate = rate * 2, total = pcm.length + 36;
        byte[] wav = new byte[pcm.length + 44];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, wav, 0, 4);
        le32(wav, 4, total);
        System.arraycopy("WAVEfmt ".getBytes(StandardCharsets.US_ASCII), 0, wav, 8, 8);
        le32(wav, 16, 16); wav[20] = 1; wav[22] = 1;
        le32(wav, 24, rate); le32(wav, 28, byteRate);
        wav[32] = 2; wav[34] = 16;
        System.arraycopy("data".getBytes(StandardCharsets.US_ASCII), 0, wav, 36, 4);
        le32(wav, 40, pcm.length);
        System.arraycopy(pcm, 0, wav, 44, pcm.length);
        return wav;
    }

    private static void le32(byte[] b, int off, int v) {
        b[off] = (byte) v; b[off + 1] = (byte) (v >> 8); b[off + 2] = (byte) (v >> 16); b[off + 3] = (byte) (v >> 24);
    }
}
