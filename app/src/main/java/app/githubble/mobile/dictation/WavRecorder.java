package app.githubble.mobile.dictation;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;

/** Records 16 kHz mono PCM16 from the microphone until {@link #stop()}. */
public final class WavRecorder {
    public interface Limit { void reached(); }

    private final AudioRecord record;
    private final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
    private final Thread thread;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running = true;
    private boolean released;
    private volatile int level;

    public WavRecorder(int maxSeconds, Limit onLimit) {
        int min = AudioRecord.getMinBufferSize(DictationClient.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) throw new IllegalStateException("Микрофон недоступен");
        int size = Math.max(min * 2, DictationClient.RATE / 2);
        record = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, DictationClient.RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size);
        if (record.getState() != AudioRecord.STATE_INITIALIZED) { record.release(); throw new IllegalStateException("Не удалось открыть микрофон"); }
        final int limitBytes = maxSeconds * DictationClient.RATE * 2;
        final byte[] buffer = new byte[size];
        record.startRecording();
        thread = new Thread(() -> {
            while (running) {
                int n = record.read(buffer, 0, buffer.length);
                if (n <= 0) continue;
                synchronized (pcm) { pcm.write(buffer, 0, n); }
                int peak = 0;
                for (int i = 0; i + 1 < n; i += 2) peak = Math.max(peak, Math.abs((short) ((buffer[i] & 0xff) | (buffer[i + 1] << 8))));
                level = peak;
                boolean full; synchronized (pcm) { full = pcm.size() >= limitBytes; }
                if (full && running) { running = false; if (onLimit != null) main.post(onLimit::reached); }
            }
        }, "dictation-recorder");
        thread.start();
    }

    /** Loudness of the last buffer, 0..32767, for a level indicator. */
    public int level() { return level; }

    /** Stops recording and returns everything captured so far. */
    public synchronized byte[] stop() {
        running = false;
        if (released) { synchronized (pcm) { return pcm.toByteArray(); } }
        released = true;
        try { thread.join(800); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        try { record.stop(); } catch (RuntimeException ignored) { }
        record.release();
        synchronized (pcm) { return pcm.toByteArray(); }
    }

    public void cancel() { stop(); }
}
