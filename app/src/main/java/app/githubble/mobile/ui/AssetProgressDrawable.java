package app.githubble.mobile.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * Card background that doubles as a progress bar: the card is filled from the left with a soft green
 * gradient (slightly darker at the start). At progress 0 it is the plain rounded card.
 */
public final class AssetProgressDrawable extends Drawable {
  private final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final int accent;
  private final float radius;
  private final float strokeWidth;
  private final RectF rect = new RectF();
  private final Path clip = new Path();
  private float progress;

  public AssetProgressDrawable(int fillColor, int borderColor, int accentColor, float radiusPx, float strokePx) {
    base.setStyle(Paint.Style.FILL);
    base.setColor(fillColor);
    stroke.setStyle(Paint.Style.STROKE);
    stroke.setColor(borderColor);
    stroke.setStrokeWidth(strokePx);
    fill.setStyle(Paint.Style.FILL);
    accent = accentColor;
    radius = radiusPx;
    strokeWidth = strokePx;
  }

  /** 0..1; values outside are clamped. */
  public void setProgress(float value) {
    float next = Math.max(0f, Math.min(1f, value));
    if (next != progress) {
      progress = next;
      invalidateSelf();
    }
  }

  @Override
  public void draw(Canvas canvas) {
    rect.set(getBounds());
    rect.inset(strokeWidth / 2f, strokeWidth / 2f);
    canvas.drawRoundRect(rect, radius, radius, base);
    if (progress > 0f) {
      float right = rect.left + rect.width() * progress;
      int start = Color.argb(0xB8, (int) (Color.red(accent) * 0.82f), (int) (Color.green(accent) * 0.82f), (int) (Color.blue(accent) * 0.82f));
      int middle = Color.argb(0x90, Color.red(accent), Color.green(accent), Color.blue(accent));
      int end = Color.argb(0x38, Color.red(accent), Color.green(accent), Color.blue(accent));
      fill.setShader(new LinearGradient(rect.left, 0, Math.max(right, rect.left + 1f), 0, new int[] {start, middle, end}, new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
      canvas.save();
      clip.reset();
      clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
      canvas.clipPath(clip);
      canvas.drawRect(rect.left, rect.top, right, rect.bottom, fill);
      canvas.restore();
    }
    canvas.drawRoundRect(rect, radius, radius, stroke);
  }

  @Override
  public void setAlpha(int alpha) {
    base.setAlpha(alpha);
    stroke.setAlpha(alpha);
    fill.setAlpha(alpha);
    invalidateSelf();
  }

  @Override
  public void setColorFilter(ColorFilter filter) {
    base.setColorFilter(filter);
    stroke.setColorFilter(filter);
    fill.setColorFilter(filter);
    invalidateSelf();
  }

  @Override
  public int getOpacity() {
    return PixelFormat.TRANSLUCENT;
  }
}
