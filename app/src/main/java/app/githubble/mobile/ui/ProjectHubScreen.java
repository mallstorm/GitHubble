package app.githubble.mobile.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.LinearLayout;
import app.githubble.mobile.agent.AgentProjectStore;
import org.json.JSONArray;
import org.json.JSONObject;

/** Basic Project Hub and Task Screen navigation. A project is created without phone files. */
public final class ProjectHubScreen {
    private final Activity activity;
    private final AgentProjectStore store;
    private final String account;
    private final java.util.function.BooleanSupplier current;
    private final Runnable refresh;
    private final java.util.function.Consumer<String> changes;
    public ProjectHubScreen(Activity activity,AgentProjectStore store,String account,
            java.util.function.BooleanSupplier current,Runnable refresh,java.util.function.Consumer<String> changes) {
        this.activity=activity;this.store=store;this.account=account;this.current=current;this.refresh=refresh;this.changes=changes;
    }
    private void failure(Exception error) {
        if(current.getAsBoolean())new AlertDialog.Builder(activity).setMessage(error.getMessage()).setPositiveButton("ОК",null).show();
    }
    private int bg=0xFFF4F7F9,card=0xFFFFFFFF,ink=0xFF1B2733,muted=0xFF6B7885,accent=0xFF2E9D68,line=0xFFD9E0E6;
    public ProjectHubScreen palette(int bg,int card,int ink,int muted,int accent,int line) {
        this.bg=bg;this.card=card;this.ink=ink;this.muted=muted;this.accent=accent;this.line=line;return this;
    }
    private int dp(int v){return (int)(v*activity.getResources().getDisplayMetrics().density+.5f);}
    private android.graphics.drawable.GradientDrawable shape(int fill,int radius,int stroke){
        android.graphics.drawable.GradientDrawable d=new android.graphics.drawable.GradientDrawable();
        d.setColor(fill);d.setCornerRadius(dp(radius));if(stroke!=0)d.setStroke(dp(1),stroke);return d;
    }
    private TextView text(String value,int sp,boolean bold,int color){
        TextView v=new TextView(activity);v.setText(value);v.setTextSize(sp);v.setTextColor(color);
        if(bold)v.setTypeface(null,android.graphics.Typeface.BOLD);return v;
    }

    /** Full-screen list of projects; every row has a cross that erases the project and takes it off the agent. */
    public void show() {
        if(!current.getAsBoolean())return;
        try{store.activeConversation(account);}catch(Exception error){failure(error);return;} // Complete safe migration before listing.
        final android.app.Dialog page=new android.app.Dialog(activity,android.R.style.Theme_Material_NoActionBar);
        LinearLayout root=new LinearLayout(activity);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(bg);
        LinearLayout top=new LinearLayout(activity);top.setGravity(Gravity.CENTER_VERTICAL);top.setPadding(dp(4),dp(4),dp(10),dp(4));
        TextView back=text("←",24,true,ink);back.setGravity(Gravity.CENTER);back.setContentDescription("Закрыть");
        back.setOnClickListener(v->page.dismiss());
        top.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));
        final TextView title=text("Проекты",19,true,ink);
        top.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        TextView create=text("+  Новый",13,true,0xFFFFFFFF);create.setGravity(Gravity.CENTER);
        create.setBackground(shape(accent,19,0));
        create.setOnClickListener(v->{page.dismiss();createProject();});
        top.addView(create,new LinearLayout.LayoutParams(dp(100),dp(38)));
        root.addView(top,new LinearLayout.LayoutParams(-1,dp(56)));
        View divider=new View(activity);divider.setBackgroundColor(line);root.addView(divider,new LinearLayout.LayoutParams(-1,dp(1)));
        ScrollView scroll=new ScrollView(activity);
        LinearLayout list=new LinearLayout(activity);list.setOrientation(LinearLayout.VERTICAL);list.setPadding(dp(14),dp(12),dp(14),dp(20));
        scroll.addView(list);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        final Runnable[] render={null};
        render[0]=()->{
            list.removeAllViews();
            JSONArray projects;
            try{projects=store.registry.list(account);}catch(Exception error){failure(error);return;}
            title.setText("Проекты · "+projects.length());
            if(projects.length()==0){
                TextView empty=text("Проектов нет. Нажмите «+ Новый», чтобы создать проект для агента.",14,false,muted);
                empty.setPadding(dp(8),dp(24),dp(8),0);list.addView(empty);return;
            }
            for(int i=0;i<projects.length();i++){
                JSONObject p=projects.optJSONObject(i);if(p==null)continue;
                final String id=p.optString("projectId"),name=p.optString("name","Проект");
                int count=store.tasks.list(account,id).length();
                LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(14),dp(12),dp(4),dp(12));row.setBackground(shape(card,16,line));
                LinearLayout labels=new LinearLayout(activity);labels.setOrientation(LinearLayout.VERTICAL);
                TextView n=text(name,16,true,ink);n.setSingleLine();n.setEllipsize(android.text.TextUtils.TruncateAt.END);labels.addView(n);
                String repo=p.optString("repository");
                TextView where=text(repo.isEmpty()?"Локальный проект":repo+" · "+p.optString("baseBranch","main"),12,false,muted);
                where.setSingleLine();where.setEllipsize(android.text.TextUtils.TruncateAt.END);where.setPadding(0,dp(2),0,0);labels.addView(where);
                TextView meta=text("задач: "+count+" · доступ AI: "+(p.optBoolean("modelAccess")?"включён":"выключен"),12,false,muted);
                meta.setPadding(0,dp(2),0,0);labels.addView(meta);
                row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
                TextView cross=text("✕",18,true,0xFFC75B66);cross.setGravity(Gravity.CENTER);
                cross.setContentDescription("Удалить проект "+name);
                cross.setOnClickListener(v->confirmDelete(id,name,count,render[0]));
                row.addView(cross,new LinearLayout.LayoutParams(dp(52),dp(52)));
                row.setOnClickListener(v->{page.dismiss();if(current.getAsBoolean())tasks(id);});
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(10);
                list.addView(row,lp);
            }
        };
        render[0].run();
        page.setContentView(root);
        android.view.Window window=page.getWindow();
        if(window!=null){
            window.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,android.view.ViewGroup.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(bg));
            window.setStatusBarColor(bg);window.setNavigationBarColor(bg);
        }
        page.show();
    }
    private void confirmDelete(String projectId,String name,int tasks,Runnable done) {
        new AlertDialog.Builder(activity).setTitle("Удалить проект «"+name+"»?")
            .setMessage("Проект будет полностью стёрт и снят с работы агента: доступ модели отозван, привязка репозитория удалена. "
                +"Задач в проекте: "+tasks+". Диалоги можно оставить (без привязки к проекту) или удалить вместе с проектом.")
            .setPositiveButton("Удалить проект",(d,w)->remove(projectId,false,done))
            .setNeutralButton("Вместе с диалогами",(d,w)->remove(projectId,true,done))
            .setNegativeButton("Отмена",null).show();
    }
    private void remove(String projectId,boolean withChats,Runnable done) {
        if(!current.getAsBoolean())return;
        try{store.deleteProject(account,projectId,withChats);done.run();refresh.run();}catch(Exception error){failure(error);}
    }
    public void createProject() {
        if(!current.getAsBoolean())return;
        LinearLayout fields=new LinearLayout(activity);fields.setOrientation(LinearLayout.VERTICAL);fields.setPadding(24,8,24,8);
        EditText name=new EditText(activity);name.setHint("Название проекта");name.setSingleLine(true);fields.addView(name);
        EditText repo=new EditText(activity);repo.setHint("OWNER/REPO · необязательно");repo.setSingleLine(true);fields.addView(repo);
        new AlertDialog.Builder(activity).setTitle("Новый проект").setView(fields)
            .setPositiveButton("Создать",(d,w)->{
                if(!current.getAsBoolean())return;
                try{
                    if(name.getText().toString().trim().isEmpty())throw new IllegalArgumentException("Введите название проекта");
                    JSONObject project=store.registry.create(account,name.getText().toString().trim(),repo.getText().toString().trim(),"main");
                    tasks(project.getString("projectId"));
                }catch(Exception error){failure(error);}
            }).setNegativeButton("Отмена",null).show();
    }
    public void tasks(String projectId) {
        if(!current.getAsBoolean())return;
        try{
            JSONObject project=store.registry.get(account,projectId);
            JSONArray tasks=store.tasks.list(account,projectId);String[] labels=new String[tasks.length()];
            for(int i=0;i<labels.length;i++){
                JSONObject t=tasks.getJSONObject(i);labels[i]=t.optString("title")+" · "+TaskWorkPanel.state(t.optString("state"));
            }
            new AlertDialog.Builder(activity).setTitle(project.optString("name"))
                .setItems(labels,(d,index)->{
                    if(!current.getAsBoolean())return;
                    JSONObject task=tasks.optJSONObject(index);
                    new TaskWorkPanel(activity,store,account,task.optString("taskId"),current,refresh,changes).show();
                }).setPositiveButton("Новая задача",(d,w)->newTask(projectId))
                .setNeutralButton(project.optBoolean("modelAccess")?"Отозвать доступ AI":"Доступ AI",(d,w)->{
                    if(!current.getAsBoolean())return;
                    if(project.optBoolean("modelAccess")){store.registry.modelAccess(account,projectId,false);tasks(projectId);return;}
                    new AlertDialog.Builder(activity).setTitle("Доступ модели к проекту")
                        .setMessage("Разрешить передачу данных этого проекта выбранному AI-провайдеру? Файлы и GitHub дополнительно ограничены существующими настройками. CI-логи требуют отдельного согласия.")
                        .setPositiveButton("Разрешить",(x,y)->{if(current.getAsBoolean()){store.registry.modelAccess(account,projectId,true);tasks(projectId);}})
                        .setNegativeButton("Отмена",null).show();
                }).setNegativeButton("Проекты",(d,w)->show()).show();
        }catch(Exception error){failure(error);}
    }
    private void newTask(String projectId) {
        EditText goal=new EditText(activity);goal.setHint("Цель задачи");
        new AlertDialog.Builder(activity).setTitle("Новая задача").setView(goal).setPositiveButton("Создать",(d,w)->{
            if(!current.getAsBoolean())return;
            try{
                String text=goal.getText().toString().trim();
                if(text.isEmpty())throw new IllegalArgumentException("Опишите задачу");
                store.newTask(account,projectId,text.substring(0,Math.min(96,text.length())),text);refresh.run();
            }catch(Exception error){failure(error);}
        }).setNegativeButton("Отмена",null).show();
    }
}
