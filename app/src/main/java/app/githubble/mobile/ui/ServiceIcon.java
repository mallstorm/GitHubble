package app.githubble.mobile.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;
import java.util.Locale;

/**
 * Original, simple icon for every AI service: a gradient tile with a glyph that hints at what the service is
 * (chat, code, routing, cloud, local chip, speed…). Nothing here copies a vendor logo; services without a
 * dedicated glyph get a monogram on a colour derived from their id.
 */
public final class ServiceIcon extends Drawable {
    private enum Glyph { BUBBLE, BURST, SPARK, ROUTE, WAVE, BOLT, CROSS, BARS, CODE, MOON, CHIP, CLOUD, SLIDERS, KEY, MONOGRAM }

    private final Glyph glyph;
    private final int top, bottom;
    private final String letter;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), ink = new Paint(Paint.ANTI_ALIAS_FLAG);

    private ServiceIcon(Glyph glyph, int top, int bottom, String letter) {
        this.glyph = glyph; this.top = top; this.bottom = bottom; this.letter = letter;
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeCap(Paint.Cap.ROUND);
        ink.setStrokeJoin(Paint.Join.ROUND);
        ink.setColor(Color.WHITE);
    }

    public static ServiceIcon of(String provider, String displayName) {
        String id = provider == null ? "" : provider.toLowerCase(Locale.ROOT);
        String name = displayName == null ? "" : displayName;
        String letter = name.isEmpty() ? (id.isEmpty() ? "?" : id.substring(0, 1)) : name.substring(0, 1);
        letter = letter.toUpperCase(Locale.ROOT);
        if (has(id, "chatgpt", "openai", "codex")) return new ServiceIcon(Glyph.BUBBLE, 0xFF1FB58F, 0xFF0B6E58, letter);
        if (has(id, "anthropic", "claude")) return new ServiceIcon(Glyph.BURST, 0xFFE58A63, 0xFFB5522F, letter);
        if (has(id, "gemini", "google", "vertex")) return new ServiceIcon(Glyph.SPARK, 0xFF4F8DF7, 0xFF8E5BD9, letter);
        if (has(id, "openrouter", "nanogpt", "polza", "vsegpt", "litellm", "portkey", "helicone", "unify")) return new ServiceIcon(Glyph.ROUTE, 0xFF6C63F0, 0xFF3A2FB5, letter);
        if (has(id, "deepseek")) return new ServiceIcon(Glyph.WAVE, 0xFF4D78FF, 0xFF2437B8, letter);
        if (has(id, "groq", "cerebras", "fireworks", "sambanova", "together")) return new ServiceIcon(Glyph.BOLT, 0xFFFF9A3D, 0xFFD9481C, letter);
        if (has(id, "xai", "grok")) return new ServiceIcon(Glyph.CROSS, 0xFF4A515C, 0xFF14171C, letter);
        if (has(id, "mistral")) return new ServiceIcon(Glyph.BARS, 0xFFFFB02E, 0xFFE0661A, letter);
        if (has(id, "copilot", "github", "kilo", "cline", "cursor", "codestral")) return new ServiceIcon(Glyph.CODE, 0xFF8B5CF6, 0xFF4C2A9E, letter);
        if (has(id, "kimi", "moonshot", "minimax", "zai", "glm")) return new ServiceIcon(Glyph.MOON, 0xFF3E7BFA, 0xFF1B3A94, letter);
        if (has(id, "nvidia", "ollama", "lmstudio", "vllm", "localai", "llama", "local", "jan", "koboldcpp")) return new ServiceIcon(Glyph.CHIP, 0xFF5BB971, 0xFF2D7A47, letter);
        if (has(id, "azure", "bedrock", "cloudflare", "huggingface", "replicate", "cloudru", "volcengine", "alibaba", "yandex", "gigachat")) return new ServiceIcon(Glyph.CLOUD, 0xFF45A6E8, 0xFF1F63B5, letter);
        if (id.startsWith("custom-") || id.startsWith("proxy")) return new ServiceIcon(Glyph.SLIDERS, 0xFF5E7388, 0xFF2F4052, letter);
        if (has(id, "perplexity", "cohere", "ai21", "siliconflow")) return new ServiceIcon(Glyph.KEY, 0xFF14B8A6, 0xFF0E766C, letter);
        int hash = Math.abs(id.hashCode());
        float hue = hash % 360;
        return new ServiceIcon(Glyph.MONOGRAM, Color.HSVToColor(new float[]{hue, .52f, .86f}), Color.HSVToColor(new float[]{(hue + 24) % 360, .68f, .58f}), letter);
    }

    /** Main colour of the service, for tinted cards. */
    public int accent() { return bottom; }

    public static int accentOf(String provider) { return of(provider, "").bottom; }

    public static ImageView view(Context context, String provider, String displayName, int sizeDp) {
        ImageView view = new ImageView(context);
        view.setImageDrawable(of(provider, displayName));
        int px = (int) (sizeDp * context.getResources().getDisplayMetrics().density + .5f);
        view.setLayoutParams(new android.widget.LinearLayout.LayoutParams(px, px));
        return view;
    }

    private static boolean has(String id, String... parts) {
        for (String part : parts) if (id.contains(part)) return true;
        return false;
    }

    @Override public void draw(Canvas canvas) {
        RectF b = new RectF(getBounds());
        float s = Math.min(b.width(), b.height());
        if (s <= 0) return;
        float left = b.left + (b.width() - s) / 2f, topY = b.top + (b.height() - s) / 2f;
        fill.setShader(new LinearGradient(left, topY, left + s, topY + s, top, bottom, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(new RectF(left, topY, left + s, topY + s), s * .28f, s * .28f, fill);
        if (glyph == Glyph.MONOGRAM) {
            Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
            t.setColor(Color.WHITE);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setTextAlign(Paint.Align.CENTER);
            t.setTextSize(s * .5f);
            canvas.drawText(letter, left + s / 2f, topY + s / 2f + s * .18f, t);
            return;
        }
        canvas.save();
        canvas.translate(left, topY);
        canvas.scale(s, s);
        drawGlyph(canvas, s);
        canvas.restore();
    }

    private void line(Canvas c, float s, float x1, float y1, float x2, float y2, float width) {
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeWidth(width / s);
        c.drawLine(x1, y1, x2, y2, ink);
    }

    private void drawGlyph(Canvas c, float s) {
        Paint solid = new Paint(Paint.ANTI_ALIAS_FLAG);
        solid.setColor(Color.WHITE);
        float w = s * .075f;
        ink.setStrokeWidth(w / s);
        switch (glyph) {
            case BUBBLE: {
                RectF body = new RectF(.24f, .26f, .76f, .62f);
                Path p = new Path();
                p.addRoundRect(body, .12f, .12f, Path.Direction.CW);
                p.moveTo(.38f, .60f); p.lineTo(.34f, .76f); p.lineTo(.54f, .62f); p.close();
                c.drawPath(p, solid);
                Paint hole = new Paint(Paint.ANTI_ALIAS_FLAG);
                hole.setColor(bottom);
                for (int i = 0; i < 3; i++) c.drawCircle(.38f + i * .12f, .44f, .035f, hole);
                break;
            }
            case BURST: {
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI * i / 4.0;
                    float x = (float) Math.cos(a), y = (float) Math.sin(a);
                    float inner = i % 2 == 0 ? .10f : .13f, outer = i % 2 == 0 ? .30f : .22f;
                    line(c, s, .5f + x * inner, .5f + y * inner, .5f + x * outer, .5f + y * outer, w * 1.1f);
                }
                break;
            }
            case SPARK: {
                Path p = new Path();
                p.moveTo(.5f, .2f);
                p.quadTo(.54f, .46f, .8f, .5f);
                p.quadTo(.54f, .54f, .5f, .8f);
                p.quadTo(.46f, .54f, .2f, .5f);
                p.quadTo(.46f, .46f, .5f, .2f);
                p.close();
                c.drawPath(p, solid);
                break;
            }
            case ROUTE: {
                Path p = new Path();
                p.moveTo(.28f, .68f);
                p.cubicTo(.28f, .38f, .72f, .62f, .72f, .32f);
                ink.setStyle(Paint.Style.STROKE);
                c.drawPath(p, ink);
                c.drawCircle(.28f, .68f, .07f, solid);
                c.drawCircle(.72f, .32f, .07f, solid);
                break;
            }
            case WAVE: {
                for (int i = 0; i < 3; i++) {
                    float y = .36f + i * .14f;
                    Path p = new Path();
                    p.moveTo(.24f, y);
                    p.cubicTo(.36f, y - .09f, .44f, y + .09f, .56f, y);
                    p.cubicTo(.64f, y - .06f, .70f, y + .04f, .76f, y);
                    ink.setStyle(Paint.Style.STROKE);
                    c.drawPath(p, ink);
                }
                break;
            }
            case BOLT: {
                Path p = new Path();
                p.moveTo(.56f, .20f); p.lineTo(.30f, .54f); p.lineTo(.48f, .54f);
                p.lineTo(.42f, .80f); p.lineTo(.70f, .44f); p.lineTo(.52f, .44f); p.close();
                c.drawPath(p, solid);
                break;
            }
            case CROSS: {
                line(c, s, .30f, .30f, .70f, .70f, w * 1.3f);
                line(c, s, .70f, .30f, .30f, .70f, w * 1.3f);
                break;
            }
            case BARS: {
                line(c, s, .26f, .34f, .74f, .34f, w * 1.3f);
                line(c, s, .26f, .50f, .62f, .50f, w * 1.3f);
                line(c, s, .26f, .66f, .52f, .66f, w * 1.3f);
                break;
            }
            case CODE: {
                Path p = new Path();
                p.moveTo(.38f, .34f); p.lineTo(.22f, .50f); p.lineTo(.38f, .66f);
                p.moveTo(.62f, .34f); p.lineTo(.78f, .50f); p.lineTo(.62f, .66f);
                ink.setStyle(Paint.Style.STROKE);
                c.drawPath(p, ink);
                line(c, s, .54f, .30f, .46f, .70f, w * .9f);
                break;
            }
            case MOON: {
                Path outer = new Path(), cut = new Path();
                outer.addCircle(.5f, .5f, .24f, Path.Direction.CW);
                cut.addCircle(.60f, .43f, .20f, Path.Direction.CW);
                outer.op(cut, Path.Op.DIFFERENCE);
                c.drawPath(outer, solid);
                break;
            }
            case CHIP: {
                RectF core = new RectF(.34f, .34f, .66f, .66f);
                ink.setStyle(Paint.Style.STROKE);
                c.drawRoundRect(core, .05f, .05f, ink);
                for (int i = 0; i < 2; i++) {
                    float p = .43f + i * .14f;
                    line(c, s, p, .22f, p, .34f, w * .8f); line(c, s, p, .66f, p, .78f, w * .8f);
                    line(c, s, .22f, p, .34f, p, w * .8f); line(c, s, .66f, p, .78f, p, w * .8f);
                }
                break;
            }
            case CLOUD: {
                Path p = new Path();
                p.addCircle(.40f, .54f, .13f, Path.Direction.CW);
                p.addCircle(.54f, .46f, .17f, Path.Direction.CW);
                p.addCircle(.66f, .56f, .12f, Path.Direction.CW);
                p.addRoundRect(new RectF(.30f, .54f, .74f, .68f), .07f, .07f, Path.Direction.CW);
                c.drawPath(p, solid);
                break;
            }
            case SLIDERS: {
                line(c, s, .26f, .36f, .74f, .36f, w * .9f);
                line(c, s, .26f, .64f, .74f, .64f, w * .9f);
                c.drawCircle(.42f, .36f, .075f, solid);
                c.drawCircle(.60f, .64f, .075f, solid);
                break;
            }
            case KEY: {
                ink.setStyle(Paint.Style.STROKE);
                c.drawCircle(.38f, .50f, .12f, ink);
                line(c, s, .50f, .50f, .76f, .50f, w);
                line(c, s, .66f, .50f, .66f, .60f, w);
                break;
            }
            default:
                break;
        }
    }

    @Override public void setAlpha(int alpha) { fill.setAlpha(alpha); ink.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter filter) { fill.setColorFilter(filter); ink.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
