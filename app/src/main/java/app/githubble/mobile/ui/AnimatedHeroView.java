package app.githubble.mobile.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;

import java.util.Random;

/**
 * Living banner backgrounds drawn with Canvas: a neural network with travelling signals and a
 * rotating DNA double helix. Animation runs only while the view is drawn (it stops by itself when
 * hidden or when the screen is not visible) at ~30 fps, and is static when the system has
 * animations turned off.
 */
public final class AnimatedHeroView extends View {
    public static final int NEURAL = 0, DNA = 1;
    private static final int NODES = 30;
    private static final long FRAME_MS = 33L;

    private int style = NEURAL;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] nx = new float[NODES], ny = new float[NODES];
    private final float[] vx = new float[NODES], vy = new float[NODES];
    private final float[] phase = new float[NODES];
    private final Random random = new Random(7);
    private LinearGradient background;
    private int backgroundStyle = -1;
    private long last;
    private boolean seeded;
    private final boolean animate;

    public AnimatedHeroView(Context context) {
        super(context);
        float scale = 1f;
        try {
            scale = Settings.Global.getFloat(context.getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        } catch (Exception ignored) { }
        animate = scale > 0f;
        setWillNotDraw(false);
        setContentDescription("Анимированный фон");
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void setStyle(int style) {
        this.style = style == DNA ? DNA : NEURAL;
        invalidate();
    }

    private void seed(int w, int h) {
        for (int i = 0; i < NODES; i++) {
            nx[i] = random.nextFloat() * w;
            ny[i] = random.nextFloat() * h;
            double angle = random.nextDouble() * Math.PI * 2;
            float speed = (6f + random.nextFloat() * 10f) * getResources().getDisplayMetrics().density;
            vx[i] = (float) Math.cos(angle) * speed;
            vy[i] = (float) Math.sin(angle) * speed;
            phase[i] = random.nextFloat() * 6.28f;
        }
        seeded = true;
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        seeded = false;
        backgroundStyle = -1;
    }

    private void ensureBackground(int w, int h) {
        if (backgroundStyle == style && background != null) return;
        backgroundStyle = style;
        background = style == DNA
                ? new LinearGradient(0, 0, w, h, new int[] {0xFF0B1230, 0xFF1B1747, 0xFF2A1250},
                        null, Shader.TileMode.CLAMP)
                : new LinearGradient(0, 0, w, h, new int[] {0xFF06222B, 0xFF0A3A44, 0xFF0B2F3E},
                        null, Shader.TileMode.CLAMP);
    }

    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        long now = SystemClock.uptimeMillis();
        float dt = last == 0 ? 0f : Math.min(0.1f, (now - last) / 1000f);
        last = now;
        ensureBackground(w, h);
        paint.setShader(background);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
        if (style == DNA) drawDna(canvas, w, h, now / 1000f);
        else drawNeural(canvas, w, h, dt, now / 1000f);
        if (animate && isShown()) postInvalidateDelayed(FRAME_MS);
    }

    private void drawNeural(Canvas canvas, int w, int h, float dt, float t) {
        if (!seeded) seed(w, h);
        float density = getResources().getDisplayMetrics().density;
        float link = 78f * density;
        for (int i = 0; i < NODES; i++) {
            if (animate) {
                nx[i] += vx[i] * dt;
                ny[i] += vy[i] * dt;
                if (nx[i] < 0 || nx[i] > w) { vx[i] = -vx[i]; nx[i] = Math.max(0, Math.min(w, nx[i])); }
                if (ny[i] < 0 || ny[i] > h) { vy[i] = -vy[i]; ny[i] = Math.max(0, Math.min(h, ny[i])); }
            }
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.1f * density);
        for (int i = 0; i < NODES; i++) {
            for (int j = i + 1; j < NODES; j++) {
                float dx = nx[i] - nx[j], dy = ny[i] - ny[j];
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                if (d > link) continue;
                float k = 1f - d / link;
                paint.setColor(((int) (k * 120) << 24) | 0x4FE3D0);
                canvas.drawLine(nx[i], ny[i], nx[j], ny[j], paint);
                // a signal pulse travelling along the edge
                float pulse = (t * 0.6f + (i * 31 + j * 17) % 10 / 10f) % 1f;
                if ((i + j) % 4 == 0) {
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(((int) (k * 255) << 24) | 0xFFFFFF);
                    canvas.drawCircle(nx[i] + (nx[j] - nx[i]) * pulse,
                            ny[i] + (ny[j] - ny[i]) * pulse, 1.8f * density, paint);
                    paint.setStyle(Paint.Style.STROKE);
                }
            }
        }
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < NODES; i++) {
            float glow = 0.6f + 0.4f * (float) Math.sin(t * 1.6f + phase[i]);
            paint.setColor(((int) (glow * 70) << 24) | 0x53D96B);
            canvas.drawCircle(nx[i], ny[i], 6.5f * density * glow, paint);
            paint.setColor(0xFFB8FFF0);
            canvas.drawCircle(nx[i], ny[i], 2.1f * density, paint);
        }
    }

    private void drawDna(Canvas canvas, int w, int h, float t) {
        float density = getResources().getDisplayMetrics().density;
        int rungs = 26;
        float amplitude = h * 0.34f;
        float slope = -h * 0.28f;
        float spin = t * 1.1f;
        float prevAx = 0, prevAy = 0, prevBx = 0, prevBy = 0;
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i <= rungs; i++) {
            float u = i / (float) rungs;
            float x = u * (w + 40 * density) - 20 * density;
            float cy = h * 0.55f + slope * (u - 0.5f);
            float angle = u * 13f + spin;
            float s = (float) Math.sin(angle), c = (float) Math.cos(angle);
            float ay = cy + s * amplitude, by = cy - s * amplitude;
            float depthA = 0.55f + 0.45f * c, depthB = 0.55f - 0.45f * c;
            if (i > 0) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth((1.4f + 1.8f * depthA) * density);
                paint.setColor(((int) (120 + 135 * depthA) << 24) | 0x39D5FF);
                canvas.drawLine(prevAx, prevAy, x, ay, paint);
                paint.setStrokeWidth((1.4f + 1.8f * depthB) * density);
                paint.setColor(((int) (120 + 135 * depthB) << 24) | 0xFF4FD8);
                canvas.drawLine(prevBx, prevBy, x, by, paint);
            }
            // base-pair rung, brighter when facing the viewer
            paint.setStrokeWidth(1.2f * density);
            paint.setColor(((int) (40 + 110 * Math.abs(c)) << 24) | 0xE8F4FF);
            canvas.drawLine(x, ay, x, by, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(((int) (150 + 105 * depthA) << 24) | 0x9BE8FF);
            canvas.drawCircle(x, ay, (1.8f + 1.8f * depthA) * density, paint);
            paint.setColor(((int) (150 + 105 * depthB) << 24) | 0xFFA3EC);
            canvas.drawCircle(x, by, (1.8f + 1.8f * depthB) * density, paint);
            prevAx = x; prevAy = ay; prevBx = x; prevBy = by;
        }
    }
}
