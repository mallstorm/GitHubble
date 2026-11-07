package app.githubble.mobile.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Original WelcomeBrainArt paths from Digger401/OmniChat, 407cc672.
 * Direct Canvas adaptation: same coordinates, colors, strokes and orbit geometry.
 */
public final class WelcomeBrainView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    public WelcomeBrainView(Context context) { super(context); setContentDescription("Git Agent — мозг"); }
    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth() * .82f, h = w / .78f;
        c.save(); c.translate((getWidth()-w)/2, (getHeight()-h)/2);
        float cx=w/2, cy=h*.43f, bw=w*.46f, bh=h*.40f;
        p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(0x2E48C9A6); p.setStrokeWidth(dp(1.5f));
        c.drawOval(w*.10f,h*.25f,w*.90f,h*.59f,p);
        p.setColor(0x3848C9A6); p.setStrokeWidth(dp(1));
        c.drawOval(w*.18f,h*.18f,w*.82f,h*.66f,p);
        p.setColor(0xFF2E5E59); p.setStrokeWidth(dp(4));
        for(int sign : new int[]{-1,1}) {
            Path path=new Path(); path.moveTo(cx,cy-bh*.52f);
            path.cubicTo(cx+sign*bw*.10f,cy-bh*.68f,cx+sign*bw*.42f,cy-bh*.60f,cx+sign*bw*.44f,cy-bh*.30f);
            path.cubicTo(cx+sign*bw*.64f,cy-bh*.22f,cx+sign*bw*.62f,cy+bh*.04f,cx+sign*bw*.50f,cy+bh*.16f);
            path.cubicTo(cx+sign*bw*.58f,cy+bh*.38f,cx+sign*bw*.34f,cy+bh*.56f,cx+sign*bw*.16f,cy+bh*.44f);
            path.cubicTo(cx+sign*bw*.08f,cy+bh*.58f,cx,cy+bh*.48f,cx,cy+bh*.34f);
            c.drawPath(path,p);
        }
        p.setColor(0xFF48C9A6); p.setStrokeWidth(dp(2.2f));
        c.drawLine(cx,cy-bh*.48f,cx,cy+bh*.34f,p);
        for(int sign : new int[]{-1,1}) {
            float[] xs={cx+sign*bw*.28f,cx+sign*bw*.22f,cx+sign*bw*.30f};
            float[] ys={cy-bh*.24f,cy+bh*.03f,cy+bh*.27f};
            p.setStyle(Paint.Style.STROKE); p.setColor(0xBF48C9A6);p.setStrokeWidth(dp(1.6f));
            c.drawLine(xs[0],ys[0],xs[1],ys[1],p); c.drawLine(xs[1],ys[1],xs[2],ys[2],p);
            p.setStyle(Paint.Style.FILL);p.setColor(0xFF48C9A6);
            for(int i=0;i<3;i++) c.drawCircle(xs[i],ys[i],dp(4),p);
        }
        c.drawCircle(w*.83f,h*.28f,dp(5),p);
        p.setColor(0xBF2E5E59);c.drawCircle(w*.18f,h*.57f,dp(3),p);
        c.restore();
    }
}
