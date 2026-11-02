package app.githubble.mobile.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import app.githubble.mobile.R;
import app.githubble.mobile.agent.AgentProjectStore;

/**
 * Native Android Views adaptation of OmniChatRoot conversation UI.
 * The GitHub engineering actions remain available under the + and overflow sheets,
 * but the main view is a conversation, never a settings dashboard.
 */
public final class AgentChatScreen {
    public interface Actions {
        void newProject();
        void createRepository();
        void attachRepository();
        void openRepository();
        void showProviderCatalog();
        void configureProvider();
        void selectProvider();
        void disconnectProvider();
        void discoverModels();
        void clearConversation();
        void showCopilotTasks();
        void startWorkflow();
        void showBuildArtifacts();
        void generateProjectCode();
        void selectExecutor(boolean copilot);
        void selectMode(boolean agentMode);
        void send(String prompt, boolean agentMode, boolean copilot);
        void showConversations();
        void openTools();
        void openAgentSettings();
        void voiceInput(EditText input, ImageView microphone);
        void voiceSettings();
        void cancelRequest();
        void draftChanged(String draft);
    }

    private final Activity activity;
    private final LinearLayout root;
    private final int background, surface, subtle, text, muted, accent, border;
    private final Actions actions;
    private final boolean dark;

    public AgentChatScreen(Activity activity, LinearLayout root, int background, int surface,
                           int subtle, int text, int muted, int accent, int border, Actions actions) {
        this.activity = activity;
        this.root = root;
        this.background = background;
        this.surface = surface;
        this.subtle = subtle;
        this.text = text;
        this.muted = muted;
        this.accent = accent;
        this.border = border;
        this.actions = actions;
        this.dark = Color.luminance(background) < .4f;
    }

    public void render(String accountLabel, AgentProjectStore.Project project, JSONArray messages,
                       int providerCount, boolean selectedCopilot, boolean agentMode,
                       String providerLabel, String modelLabel, boolean connected,
                       String conversationTitle, boolean busy, String draft) {
        root.removeAllViews();
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0,0,0,0);
        root.setBackgroundColor(background);

        LinearLayout top = horizontal();
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8),dp(4),dp(8),dp(4));
        top.addView(glyph("☰", "Список диалогов", actions::showConversations, 24),
                new LinearLayout.LayoutParams(dp(46),dp(49)));
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.VERTICAL);
        TextView brand = label("OmniChat",19,true,text);
        brand.setSingleLine();
        header.addView(brand);
        TextView detail = label(conversationTitle.isEmpty() ? "GitHubble · Чат" : conversationTitle,
                11,false,muted);
        detail.setSingleLine();
        detail.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(detail);
        top.addView(header,new LinearLayout.LayoutParams(0,-2,1));
        top.addView(glyph("✎","Новый диалог",actions::clearConversation,25),
                new LinearLayout.LayoutParams(dp(48),dp(49)));
        top.addView(glyph("⋮","Меню OmniChat",actions::openTools,28),
                new LinearLayout.LayoutParams(dp(43),dp(49)));
        root.addView(top,new LinearLayout.LayoutParams(-1,dp(60)));
        root.addView(separator(),new LinearLayout.LayoutParams(-1,dp(1)));

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        LinearLayout transcript = new LinearLayout(activity);
        transcript.setOrientation(LinearLayout.VERTICAL);
        transcript.setPadding(dp(12),dp(13),dp(12),dp(14));
        scroll.addView(transcript);
        if(messages==null||messages.length()==0)empty(transcript,project,connected);
        else for(int i=0;i<messages.length();i++){
            JSONObject entry=messages.optJSONObject(i);
            if(entry!=null)message(transcript,entry,modelLabel,selectedCopilot);
        }
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if(messages!=null&&messages.length()>0)scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN));

        if(busy){
            LinearLayout progress=horizontal();
            progress.setGravity(Gravity.CENTER_VERTICAL);
            progress.setPadding(dp(12),dp(3),dp(12),dp(3));
            progress.addView(label("●  AI обрабатывает сообщение…",12,true,accent),
                    new LinearLayout.LayoutParams(0,-2,1));
            progress.addView(actionLabel("Остановить",actions::cancelRequest));
            root.addView(progress,new LinearLayout.LayoutParams(-1,dp(39)));
        }

        LinearLayout dock=new LinearLayout(activity);
        dock.setOrientation(LinearLayout.VERTICAL);
        dock.setPadding(dp(9),dp(6),dp(9),dp(8));
        dock.addView(separator(),new LinearLayout.LayoutParams(-1,dp(1)));
        LinearLayout composer=new LinearLayout(activity);
        composer.setOrientation(LinearLayout.VERTICAL);
        composer.setPadding(dp(7),dp(5),dp(7),dp(4));
        composer.setBackground(shape(surface,26,border,1));
        LinearLayout.LayoutParams panelParams=new LinearLayout.LayoutParams(-1,-2);
        panelParams.topMargin=dp(7);
        dock.addView(composer,panelParams);

        EditText input=new EditText(activity);
        input.setTextColor(text);
        input.setHintTextColor(muted);
        input.setTextSize(15);
        input.setGravity(Gravity.TOP|Gravity.START);
        input.setSingleLine(false);
        input.setMinLines(1);
        input.setMaxLines(6);
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setPadding(dp(12),dp(9),dp(10),dp(5));
        input.setHint("Спросить OmniChat");
        input.setText(draft==null?"":draft);
        input.setSelection(input.length());
        input.addTextChangedListener(new TextWatcher(){
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count){
                actions.draftChanged(s.toString());
            }
            @Override public void afterTextChanged(Editable e){}
        });
        composer.addView(input,new LinearLayout.LayoutParams(-1,-2));

        LinearLayout tools=horizontal();
        tools.setGravity(Gravity.CENTER_VERTICAL);
        tools.addView(glyph("+","Вложения и инструменты",actions::openTools,28),
                new LinearLayout.LayoutParams(dp(42),dp(44)));

        ImageView robot=icon(R.drawable.ic_omni_robot,"Агентский режим",agentMode?0xFF2E9D68:muted);
        robot.setBackground(shape(subtle,50,Color.TRANSPARENT,0));
        robot.setContentDescription(agentMode?"Агент включён. Нажмите для выключения":
                "Обычный чат. Нажмите для включения агента");
        final boolean[] currentMode={agentMode};
        robot.setOnClickListener(v->{
            currentMode[0]=!currentMode[0];
            robot.setColorFilter(currentMode[0]?0xFF2E9D68:muted);
            robot.setContentDescription(currentMode[0]?"Агент включён":"Обычный чат");
            actions.selectMode(currentMode[0]);
        });
        robot.setOnLongClickListener(v->{actions.openAgentSettings();return true;});
        tools.addView(robot,new LinearLayout.LayoutParams(dp(43),dp(43)));

        LinearLayout model=new LinearLayout(activity);
        model.setOrientation(LinearLayout.VERTICAL);
        model.setGravity(Gravity.CENTER);
        String modelTitle=selectedCopilot?"GitHub Copilot":connected?modelLabel:"Выбрать модель";
        TextView modelName=label(modelTitle,13,true,text);
        modelName.setSingleLine();
        modelName.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        modelName.setGravity(Gravity.CENTER);
        model.addView(modelName,new LinearLayout.LayoutParams(-1,-2));
        TextView modelSub=label(selectedCopilot?"Cloud Agent ▾":
                connected?providerLabel+" ▾":"Добавить подключение ▾",10,false,muted);
        modelSub.setGravity(Gravity.CENTER);
        modelSub.setSingleLine();
        modelSub.setEllipsize(TextUtils.TruncateAt.END);
        model.addView(modelSub,new LinearLayout.LayoutParams(-1,-2));
        model.setOnClickListener(v->actions.selectProvider());
        model.setContentDescription("Выбрать AI: "+modelTitle);
        tools.addView(model,new LinearLayout.LayoutParams(0,dp(44),1));

        ImageView mic=icon(R.drawable.ic_mic,"Диктовка",muted);
        mic.setOnClickListener(v->actions.voiceInput(input,mic));
        mic.setOnLongClickListener(v->{actions.voiceSettings();return true;});
        tools.addView(mic,new LinearLayout.LayoutParams(dp(43),dp(43)));

        TextView send=glyph(busy?"■":"↑",busy?"Остановить запрос":"Отправить",null,busy?19:27);
        send.setTextColor(Color.WHITE);
        send.setBackground(shape(busy?0xFFC07055:accent,50,Color.TRANSPARENT,0));
        send.setGravity(Gravity.CENTER);
        send.setOnClickListener(v->{
            if(busy){actions.cancelRequest();return;}
            String value=input.getText().toString().trim();
            if(value.isEmpty()){input.setError("Введите сообщение");return;}
            if(value.length()>4900){input.setError("Сообщение превышает 4900 символов. Разбейте его на части.");return;}
            actions.draftChanged("");
            actions.send(value,currentMode[0],selectedCopilot);
        });
        LinearLayout.LayoutParams sendParams=new LinearLayout.LayoutParams(dp(42),dp(42));
        sendParams.leftMargin=dp(3);
        tools.addView(send,sendParams);
        composer.addView(tools,new LinearLayout.LayoutParams(-1,dp(47)));
        root.addView(dock,new LinearLayout.LayoutParams(-1,-2));
    }

    private void empty(LinearLayout transcript,AgentProjectStore.Project p,boolean connected){
        LinearLayout panel=new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(12),dp(72),dp(12),dp(20));
        ImageView logo=icon(R.drawable.ic_omni_robot,"OmniChat",accent);
        LinearLayout.LayoutParams logoLp=new LinearLayout.LayoutParams(dp(56),dp(56));
        logoLp.bottomMargin=dp(14);
        panel.addView(logo,logoLp);
        TextView title=label("Чем могу помочь?",24,true,text);
        title.setGravity(Gravity.CENTER);
        panel.addView(title);
        TextView subtitle=label(connected?"Задайте вопрос, напишите код или включите агента":
                "Добавьте AI-подключение или выберите Copilot.",13,false,muted);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(dp(12),dp(11),dp(12),dp(12));
        panel.addView(subtitle);
        if(!connected)panel.addView(actionLabel("Добавить AI",actions::configureProvider));
        if(!p.repository.isEmpty()){
            TextView repo=label("Проект: "+p.repository,11,false,muted);
            repo.setGravity(Gravity.CENTER);
            repo.setPadding(0,dp(14),0,0);
            panel.addView(repo);
        }
        transcript.addView(panel,new LinearLayout.LayoutParams(-1,-1));
    }

    private void message(LinearLayout transcript,JSONObject entry,String model,boolean copilot){
        String role=entry.optString("role","system");
        String body=entry.optString("body","");
        boolean user="user".equals(role),system="system".equals(role);
        LinearLayout wrapper=horizontal();
        wrapper.setGravity(user?Gravity.END:Gravity.START);
        wrapper.setPadding(user?dp(28):dp(1),dp(5),user?dp(1):dp(10),dp(6));
        LinearLayout bubble=new LinearLayout(activity);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(user?dp(14):dp(5),dp(8),user?dp(14):dp(5),dp(5));
        if(user)bubble.setBackground(shape(subtle,21,Color.TRANSPARENT,0));
        else if(system)bubble.setBackground(shape(dark?0xFF272E37:0xFFF1F3F5,13,Color.TRANSPARENT,0));
        if(!user){
            TextView tag=label(system?"Система":copilot?"GitHub Copilot":
                    "OmniChat"+(model.isEmpty()?"":" · "+model),11,true,system?muted:accent);
            bubble.addView(tag);
        }
        TextView content=label(body,14,false,text);
        content.setTextIsSelectable(true);
        content.setLineSpacing(dp(2),1.05f);
        content.setPadding(0,dp(3),0,dp(4));
        content.setText(markdown(body));
        bubble.addView(content,new LinearLayout.LayoutParams(-1,-2));
        if(!system){
            TextView copy=label("⧉  Копировать",11,false,muted);
            copy.setPadding(dp(3),dp(5),dp(3),dp(3));
            copy.setOnClickListener(v->{
                ClipboardManager cb=(ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE);
                if(cb!=null)cb.setPrimaryClip(ClipData.newPlainText("OmniChat",body));
                android.widget.Toast.makeText(activity,"Скопировано",android.widget.Toast.LENGTH_SHORT).show();
            });
            bubble.addView(copy);
        }
        wrapper.addView(bubble,new LinearLayout.LayoutParams(-2,-2));
        transcript.addView(wrapper,new LinearLayout.LayoutParams(-1,-2));
    }

    private static CharSequence markdown(String source){
        SpannableStringBuilder text=new SpannableStringBuilder(source==null?"":source);
        String raw=text.toString();
        int cursor=0;
        while(cursor<raw.length()){
            int open=raw.indexOf("**",cursor);
            if(open<0)break;
            int end=raw.indexOf("**",open+2);
            if(end<0)break;
            text.setSpan(new StyleSpan(Typeface.BOLD),open,end+2,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            cursor=end+2;
        }
        return text;
    }

    private LinearLayout horizontal(){
        LinearLayout row=new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private TextView label(String value,int size,boolean bold,int color){
        TextView label=new TextView(activity);
        label.setText(value);
        label.setTextColor(color);
        label.setTextSize(size);
        if(bold)label.setTypeface(null,Typeface.BOLD);
        return label;
    }

    private TextView glyph(String value,String description,Runnable click,int size){
        TextView label=label(value,size,false,text);
        label.setGravity(Gravity.CENTER);
        label.setContentDescription(description);
        if(click!=null)label.setOnClickListener(v->click.run());
        return label;
    }

    private ImageView icon(int resource,String description,int tint){
        ImageView icon=new ImageView(activity);
        icon.setImageResource(resource);
        icon.setColorFilter(tint);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        icon.setPadding(dp(9),dp(9),dp(9),dp(9));
        icon.setContentDescription(description);
        return icon;
    }

    private TextView actionLabel(String name,Runnable click){
        TextView button=label(name,12,true,accent);
        button.setPadding(dp(12),dp(10),dp(12),dp(10));
        button.setGravity(Gravity.CENTER);
        button.setBackground(shape(subtle,17,border,1));
        button.setOnClickListener(v->click.run());
        return button;
    }

    private View separator(){
        View view=new View(activity);
        view.setBackgroundColor(border);
        return view;
    }

    private GradientDrawable shape(int color,int radius,int edge,int stroke){
        GradientDrawable background=new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        if(stroke>0)background.setStroke(dp(stroke),edge);
        return background;
    }

    private int dp(int value){
        return (int)(value*activity.getResources().getDisplayMetrics().density+.5f);
    }
}
