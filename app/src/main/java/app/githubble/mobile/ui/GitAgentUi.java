package app.githubble.mobile.ui;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.*;

/** Native equivalents of OmniChat's Material 3 sheets, typography and cards. */
public final class GitAgentUi {
    public final Activity activity;
    public final boolean dark;
    public final int text, muted, background, line, primary;
    public GitAgentUi(Activity activity,int appBackground) {
        this.activity=activity;dark=Color.luminance(appBackground)<.4f;
        text=dark?0xFFF2F3F5:0xFF222B31;muted=dark?0xFFDFE4E9:0xFF52616B;
        background=dark?0xFF424850:0xFFF8F2FA;line=dark?0xFF6F7B86:0xFFD7DFE5;
        primary=dark?0xFFCCD8E0:0xFF435B69;
    }
    public int dp(float v){return Math.round(v*activity.getResources().getDisplayMetrics().density);}
    public GradientDrawable shape(int color,int radius,int border){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));
        if(border!=0)d.setStroke(dp(1),border);return d;
    }
    public TextView label(String value,int size,boolean bold){
        TextView t=new TextView(activity);t.setText(value);t.setTextSize(size);t.setTextColor(text);
        if(bold)t.setTypeface(null,Typeface.BOLD);return t;
    }
    public LinearLayout column(){LinearLayout v=new LinearLayout(activity);v.setOrientation(1);return v;}
    public LinearLayout row(){LinearLayout v=new LinearLayout(activity);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    public LinearLayout body(String title,boolean handle){
        LinearLayout body=column();body.setPadding(dp(18),dp(16),dp(18),dp(20));
        if(handle){View grip=new View(activity);grip.setBackground(shape(muted,3,0));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(32),dp(4));lp.gravity=Gravity.CENTER_HORIZONTAL;
            lp.bottomMargin=dp(24);body.addView(grip,lp);}
        TextView heading=label(title,24,true);heading.setPadding(0,0,0,dp(8));body.addView(heading);return body;
    }
    public Dialog present(LinearLayout body,boolean bottom){
        Dialog dialog=new Dialog(activity);ScrollView scroll=new ScrollView(activity);
        scroll.setFillViewport(false);scroll.setVerticalScrollBarEnabled(false);scroll.addView(body);
        scroll.setBackground(shape(!bottom&&!dark?0xFFEDE7F0:background,28,0));scroll.setClipToOutline(true);
        dialog.setContentView(scroll);dialog.setCanceledOnTouchOutside(true);
        Window w=dialog.getWindow();
        if(w!=null){w.setBackgroundDrawableResource(android.R.color.transparent);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            WindowManager.LayoutParams p=w.getAttributes();p.dimAmount=.38f;w.setAttributes(p);}
        dialog.show();
        if(w!=null){w.setGravity(bottom?Gravity.BOTTOM:Gravity.CENTER);
            int maxHeight=activity.getResources().getDisplayMetrics().heightPixels-dp(bottom?100:64);
            int width=activity.getResources().getDisplayMetrics().widthPixels-(bottom?0:dp(48));
            scroll.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(maxHeight,View.MeasureSpec.AT_MOST));
            w.setLayout(width,Math.min(scroll.getMeasuredHeight(),maxHeight));}
        body.setAlpha(0);body.setTranslationY(dp(12));body.animate().alpha(1).translationY(0).setDuration(180).start();
        return dialog;
    }
    public void note(LinearLayout body,String value){TextView t=label(value,13,false);t.setTextColor(muted);
        t.setPadding(0,dp(3),0,dp(16));body.addView(t);}
    public void section(LinearLayout body,String value){TextView t=label(value,16,true);
        t.setPadding(0,dp(20),0,dp(12));body.addView(t);}
    public TextView action(LinearLayout body,String title,String detail,int tint,Runnable action){
        LinearLayout row=row();row.setPadding(dp(14),dp(12),dp(14),dp(12));
        int fill=dark?0xFF515963:blend(background,tint,.055f);
        row.setBackground(shape(fill,16,dark?line:blend(background,tint,.20f)));
        int icon=0;
        if(title.equals("Анализ фотографии"))icon=app.githubble.mobile.R.drawable.ic_agent_auto_awesome;
        else if(title.equals("Вопрос по изображению"))icon=app.githubble.mobile.R.drawable.ic_agent_question_answer;
        else if(title.equals("Создание изображения"))icon=app.githubble.mobile.R.drawable.ic_agent_photo;
        else if(title.equals("Веб-поиск"))icon=app.githubble.mobile.R.drawable.ic_agent_travel_explore;
        else if(title.equals("Плагины"))icon=app.githubble.mobile.R.drawable.ic_agent_extension;
        if(icon!=0){
            ImageView symbol=new ImageView(activity);symbol.setImageResource(icon);symbol.setColorFilter(tint);
            symbol.setPadding(dp(10),dp(10),dp(10),dp(10));
            symbol.setBackground(shape(blend(background,tint,.18f),50,0));
            LinearLayout.LayoutParams iconLp=new LinearLayout.LayoutParams(dp(40),dp(40));iconLp.rightMargin=dp(12);
            row.addView(symbol,iconLp);
        }
        LinearLayout words=column();TextView name=label(title,14,true);words.addView(name);
        if(!detail.isEmpty()){TextView sub=label(detail,12,false);sub.setTextColor(muted);words.addView(sub);}
        row.addView(words,new LinearLayout.LayoutParams(0,-2,1));
        TextView arrow=label("›",25,false);arrow.setTextColor(tint);row.addView(arrow);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(10);
        row.setMinimumHeight(dp(60));body.addView(row,lp);row.setOnClickListener(v->action.run());
        row.setContentDescription(title+". "+detail);return name;
    }
    public void tile(LinearLayout row,String title,int icon,int tint,Runnable action){
        LinearLayout tile=column();tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(5),dp(12),dp(5),dp(12));
        tile.setBackground(shape(dark?0xFF515963:blend(background,tint,.07f),20,dark?line:blend(background,tint,.20f)));
        ImageView image=new ImageView(activity);image.setImageResource(icon);image.setColorFilter(tint);
        image.setPadding(dp(10),dp(10),dp(10),dp(10));image.setBackground(shape(blend(background,tint,.15f),50,0));
        tile.addView(image,new LinearLayout.LayoutParams(dp(42),dp(42)));
        TextView caption=label(title,title.startsWith("Добавить")||title.startsWith("Subscription")?14:12,true);caption.setGravity(Gravity.CENTER);
        caption.setPadding(0,dp(7),0,0);tile.addView(caption);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-1,1);lp.setMargins(dp(2),0,dp(2),0);
        row.addView(tile,lp);tile.setOnClickListener(v->action.run());tile.setContentDescription(title);
    }
    public TextView chip(LinearLayout row,String title,boolean selected,Runnable action){
        TextView t=label((selected?"✓  ":"")+title,14,true);t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12),dp(9),dp(12),dp(9));t.setTextColor(primary);
        t.setBackground(shape(selected?(dark?0xFF515963:0xFFE9EFF3):Color.TRANSPARENT,9,selected?0:line));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);lp.rightMargin=dp(8);lp.bottomMargin=dp(8);
        row.addView(t,lp);t.setOnClickListener(v->action.run());return t;
    }
    public TextView modeChip(LinearLayout row,String title,boolean selected,boolean available,Runnable action){
        TextView button=label((selected?"✓ ":"")+title,12,true);
        button.setGravity(Gravity.CENTER);button.setSingleLine(true);button.setHorizontallyScrolling(false);
        button.setAutoSizeTextTypeUniformWithConfiguration(10,13,1,android.util.TypedValue.COMPLEX_UNIT_SP);
        button.setPadding(dp(4),dp(8),dp(4),dp(8));
        button.setTextColor(available?(selected?(dark?0xFFFFFFFF:0xFF253944):text):(dark?0xFFBCC5CF:0xFF68717C));
        button.setBackground(shape(selected?(dark?0xFF52616E:0xFFDCE8EF):(dark?0xFF424850:0xFFF4F1F6),10,
                selected?primary:(dark?0xFF8A96A2:0xFF9AA5AF)));
        button.setEnabled(available);button.setAlpha(1f);
        button.setContentDescription(title+(available?"":" · пока недоступно"));
        float weight=title.equals("Выкл")||title.equals("Авто")?.65f:title.equals("Один агент")?1.15f:1.45f;
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(44),weight);lp.setMargins(dp(2),dp(4),dp(2),dp(8));
        row.addView(button,lp);if(available)button.setOnClickListener(v->action.run());return button;
    }
    private static int blend(int a,int b,float f){return Color.rgb((int)(Color.red(a)*(1-f)+Color.red(b)*f),
            (int)(Color.green(a)*(1-f)+Color.green(b)*f),(int)(Color.blue(a)*(1-f)+Color.blue(b)*f));}
}
