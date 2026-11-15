package app.githubble.mobile.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.TextView;
import android.view.View;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import app.githubble.mobile.R;

/** Theme-aware repository language selector. Selection is applied immediately. */
public final class RepositoryLanguageSheet {
    private RepositoryLanguageSheet() {}

    public static void show(Activity activity, List<String> languages, String current,
                            int card, int text, int muted, int accent, int border,
                            int subtle, Consumer<String> onSelected) {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout sheet = vertical(activity);
        sheet.setFocusableInTouchMode(true);
        sheet.setPadding(dp(activity,18),dp(activity,11),dp(activity,18),dp(activity,14));
        sheet.setBackground(shape(activity,card,25,0,0));

        LinearLayout grabArea = new LinearLayout(activity);
        grabArea.setGravity(Gravity.CENTER);
        View handle = new View(activity);
        handle.setBackground(shape(activity,mix(card,muted,.40f),5,0,0));
        grabArea.addView(handle,new LinearLayout.LayoutParams(dp(activity,38),dp(activity,4)));
        sheet.addView(grabArea,new LinearLayout.LayoutParams(-1,dp(activity,23)));

        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView symbol = label(activity,"</>",18,true,accent);
        symbol.setGravity(Gravity.CENTER);
        symbol.setBackground(shape(activity,mix(card,accent,.12f),12,0,0));
        header.addView(symbol,new LinearLayout.LayoutParams(dp(activity,47),dp(activity,47)));
        TextView heading = label(activity,"Язык репозитория",20,true,text);
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(-1,-2);
        headingParams.setMargins(dp(activity,12),0,0,0);
        header.addView(heading,headingParams);
        sheet.addView(header,new LinearLayout.LayoutParams(-1,dp(activity,57)));

        EditText query = new EditText(activity);
        query.setSingleLine(true);
        query.setTextSize(14);
        query.setTextColor(text);
        query.setHintTextColor(muted);
        query.setHint("⌕  Поиск языка");
        query.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        query.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        query.setPadding(dp(activity,16),0,dp(activity,12),0);
        query.setBackground(shape(activity,mix(card,subtle,.55f),14,border,1));
        LinearLayout.LayoutParams queryParams=new LinearLayout.LayoutParams(-1,dp(activity,49));
        queryParams.setMargins(0,dp(activity,12),0,dp(activity,13));
        sheet.addView(query,queryParams);

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout list = vertical(activity);
        list.setPadding(0,0,0,dp(activity,12));
        scroll.addView(list,new ScrollView.LayoutParams(-1,-2));
        sheet.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));

        Runnable render = () -> {
            list.removeAllViews();
            String term=query.getText().toString().trim().toLowerCase(Locale.ROOT);
            int count=0;
            for(String language : languages) {
                String description=description(language);
                if(!term.isEmpty()
                        &&!(language+" "+description).toLowerCase(Locale.ROOT).contains(term)) continue;
                count++;
                boolean checked=language.equalsIgnoreCase(current.isEmpty()?"Все языки":current);
                LinearLayout row = new LinearLayout(activity);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(activity,10),dp(activity,9),dp(activity,12),dp(activity,9));
                row.setMinimumHeight(dp(activity,66));
                row.setBackground(shape(activity,checked?mix(card,accent,.11f):card,13,
                        checked?mix(border,accent,.36f):0,checked?1:0));

                RadioButton radio = new RadioButton(activity);
                radio.setChecked(checked);
                radio.setButtonTintList(new ColorStateList(
                        new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},
                        new int[]{accent,muted}));
                radio.setClickable(false);
                radio.setFocusable(false);
                row.addView(radio,new LinearLayout.LayoutParams(dp(activity,40),dp(activity,45)));

                LinearLayout details = vertical(activity);
                TextView name=label(activity,language,14,true,text);
                details.addView(name,new LinearLayout.LayoutParams(-1,-2));
                TextView subtitle=label(activity,description,11,false,muted);
                LinearLayout.LayoutParams subtitleParams=new LinearLayout.LayoutParams(-1,-2);
                subtitleParams.topMargin=dp(activity,4);
                details.addView(subtitle,subtitleParams);
                LinearLayout.LayoutParams detailsParams = new LinearLayout.LayoutParams(0,-2,1);
                detailsParams.leftMargin=dp(activity,7);
                row.addView(details,detailsParams);
                row.setContentDescription(language+", "+description+(checked?", выбран":""));
                row.setOnClickListener(v -> {
                    dialog.dismiss();
                    onSelected.accept("Все языки".equals(language)?"":language);
                });

                LinearLayout.LayoutParams rowParams=new LinearLayout.LayoutParams(-1,-2);
                rowParams.bottomMargin=dp(activity,2);
                list.addView(row,rowParams);
            }
            if(count==0) {
                TextView empty=label(activity,"Языки не найдены",13,false,muted);
                empty.setGravity(Gravity.CENTER);
                list.addView(empty,new LinearLayout.LayoutParams(-1,dp(activity,80)));
            }
            scroll.scrollTo(0,0);
        };
        query.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count) {render.run();}
            @Override public void afterTextChanged(Editable text) {}
        });
        render.run();
        sheet.requestFocus();
        dialog.setContentView(sheet);
        Window window=dialog.getWindow();
        if(window!=null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(.45f);
            window.setGravity(Gravity.BOTTOM);
            window.setWindowAnimations(R.style.RepositoryLanguageSheetAnimation);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    |WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }
        dialog.show();
        if(window!=null) {
            int height=Math.round(activity.getResources().getDisplayMetrics().heightPixels*.93f);
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,height);
            window.setNavigationBarColor(card);
        }
    }

    static String description(String language) {
        switch(language.toLowerCase(Locale.ROOT)) {
            case "все языки": return "Показывать репозитории на всех языках";
            case "без языка": return "Репозитории без указанного языка";
            case "c++": return "Системное и высокопроизводительное программирование";
            case "c": return "Системное программирование";
            case "c#": return "Приложения на платформе .NET";
            case "gdscript": return "Скриптовый язык для Godot";
            case "go": return "Быстрые серверные и сетевые приложения";
            case "html": return "Разметка веб-страниц";
            case "css": return "Стили и оформление веб-страниц";
            case "java": return "Android и кроссплатформенные приложения";
            case "kotlin": return "Современная разработка под Android";
            case "mql4": return "Торговые роботы и индикаторы MetaTrader 4";
            case "mql5": return "Торговые роботы и индикаторы MetaTrader 5";
            case "php": return "Серверная веб-разработка";
            case "powershell": return "Автоматизация и администрирование Windows";
            case "python": return "Скрипты, ИИ и автоматизация";
            case "javascript": return "Интерактивные веб-приложения";
            case "typescript": return "Типизированная разработка на JavaScript";
            case "rust": return "Безопасное и высокопроизводительное программирование";
            case "swift": return "Разработка приложений для Apple";
            case "dart": return "Кроссплатформенные приложения Flutter";
            case "ruby": return "Веб-приложения и автоматизация";
            case "shell": return "Сценарии командной оболочки";
            default: return "Репозитории с языком "+language;
        }
    }

    private static LinearLayout vertical(Activity activity) {
        LinearLayout view=new LinearLayout(activity);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }
    private static TextView label(Activity activity,String value,int sp,boolean bold,int color) {
        TextView view=new TextView(activity);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        if(bold)view.setTypeface(null,Typeface.BOLD);
        return view;
    }
    private static GradientDrawable shape(Activity activity,int fill,int radius,int stroke,int width) {
        GradientDrawable drawable=new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(activity,radius));
        if(width>0)drawable.setStroke(dp(activity,width),stroke);
        return drawable;
    }
    private static int dp(Activity activity,int amount) {
        return (int)(amount*activity.getResources().getDisplayMetrics().density+.5f);
    }
    private static int mix(int a,int b,float w) {
        return Color.rgb((int)(Color.red(a)*(1-w)+Color.red(b)*w),
                (int)(Color.green(a)*(1-w)+Color.green(b)*w),
                (int)(Color.blue(a)*(1-w)+Color.blue(b)*w));
    }
}
