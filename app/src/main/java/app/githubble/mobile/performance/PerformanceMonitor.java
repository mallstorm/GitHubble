package app.githubble.mobile.performance;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Debug;
import android.os.SystemClock;
import org.json.JSONObject;

/** Lightweight release-safe performance telemetry stored only on device. */
public final class PerformanceMonitor {
    private final Context context;
    private final long processStartedAt = SystemClock.elapsedRealtime();
    private long firstFrameAt;

    public PerformanceMonitor(Context context) { this.context = context.getApplicationContext(); }

    public synchronized void markFirstFrame() {
        if (firstFrameAt == 0L) firstFrameAt = SystemClock.elapsedRealtime();
    }

    public synchronized long coldStartMillis() {
        return firstFrameAt == 0L ? -1L : Math.max(0L, firstFrameAt - processStartedAt);
    }

    public JSONObject snapshot() {
        JSONObject out = new JSONObject();
        try {
            ActivityManager.MemoryInfo system = new ActivityManager.MemoryInfo();
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager != null) manager.getMemoryInfo(system);
            Runtime runtime = Runtime.getRuntime();
            out.put("cold_start_ms", coldStartMillis());
            out.put("java_heap_used_bytes", runtime.totalMemory() - runtime.freeMemory());
            out.put("java_heap_max_bytes", runtime.maxMemory());
            out.put("native_heap_bytes", Debug.getNativeHeapAllocatedSize());
            out.put("system_low_memory", system.lowMemory);
            out.put("system_available_bytes", system.availMem);
        } catch (Exception ignored) { }
        return out;
    }

    /** Decodes large images near the requested viewport instead of allocating the source resolution. */
    public static Bitmap decodeSampled(byte[] bytes, int requestedWidth, int requestedHeight) {
        if (bytes == null || bytes.length == 0) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        int sample = 1;
        int targetW = Math.max(1, requestedWidth);
        int targetH = Math.max(1, requestedHeight);
        while (bounds.outWidth / (sample * 2) >= targetW && bounds.outHeight / (sample * 2) >= targetH) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = Math.max(1, sample);
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }
}
