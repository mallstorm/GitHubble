package app.githubble.mobile.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.widget.Toast;
import app.githubble.mobile.translation.LanguageDetector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Reads agent messages aloud with the device voice. One message at a time; tapping again stops. */
public final class AgentSpeaker {
    private static AgentSpeaker instance;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Runnable> watchers = new HashMap<>();
    private TextToSpeech tts;
    private boolean ready;
    private Runnable pending;
    private volatile String current = "";

    private AgentSpeaker(Context context) { this.context = context.getApplicationContext(); }

    public static synchronized AgentSpeaker of(Context context) {
        if (instance == null) instance = new AgentSpeaker(context);
        return instance;
    }

    public boolean speaking(String key) { return key != null && key.equals(current); }

    /** Called on the main thread whenever the playing message changes; one watcher per message key. */
    public void watch(String key, Runnable onChange) { watchers.put(key, onChange); }

    public void toggle(String key, String markdown) {
        if (speaking(key)) { stop(); return; }
        final String text = SpeechText.speakable(markdown);
        if (text.isEmpty()) { Toast.makeText(context, "Нечего озвучивать", Toast.LENGTH_SHORT).show(); return; }
        stopQuietly();
        current = key;
        notifyAll_();
        Runnable start = () -> begin(key, text);
        if (ready) start.run();
        else { pending = start; ensure(); }
    }

    public void stop() { stopQuietly(); notifyAll_(); }

    public void shutdown() {
        stopQuietly();
        if (tts != null) { tts.shutdown(); tts = null; ready = false; }
    }

    private void stopQuietly() {
        pending = null;
        current = "";
        if (tts != null) tts.stop();
    }

    private void ensure() {
        if (tts != null) return;
        tts = new TextToSpeech(context, status -> main.post(() -> {
            ready = status == TextToSpeech.SUCCESS;
            if (!ready) { current = ""; notifyAll_(); Toast.makeText(context, "Озвучивание недоступно на этом устройстве", Toast.LENGTH_LONG).show(); return; }
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { }
                @Override public void onDone(String id) { if (id != null && id.equals(current + ":last")) main.post(() -> { current = ""; notifyAll_(); }); }
                @Override public void onError(String id) { main.post(() -> { current = ""; notifyAll_(); }); }
            });
            Runnable run = pending; pending = null;
            if (run != null) run.run();
        }));
    }

    private void begin(String key, String text) {
        if (!speaking(key)) return;
        Locale locale = SpeechText.localeOf(LanguageDetector.detect(text).code);
        int language = tts.setLanguage(locale);
        if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
            Toast.makeText(context, "Нет голоса для этого языка, читаю голосом по умолчанию", Toast.LENGTH_SHORT).show();
        }
        List<String> parts = SpeechText.chunks(text, Math.max(500, Math.min(3500, TextToSpeech.getMaxSpeechInputLength() - 200)));
        for (int i = 0; i < parts.size(); i++) {
            tts.speak(parts.get(i), i == 0 ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, null, key + (i == parts.size() - 1 ? ":last" : ":" + i));
        }
    }

    private void notifyAll_() {
        for (Runnable watcher : new ArrayList<>(watchers.values())) { try { watcher.run(); } catch (RuntimeException ignored) { } }
    }
}
