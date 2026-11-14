package app.githubble.mobile.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.EditText;
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
    public void show() {
        if(!current.getAsBoolean())return;
        try{
            store.activeConversation(account); // Complete safe migration before listing.
            JSONArray projects=store.registry.list(account);
            String[] labels=new String[projects.length()];
            for(int i=0;i<labels.length;i++){
                JSONObject p=projects.getJSONObject(i);
                labels[i]=p.getString("name")+" · задач: "+store.tasks.list(account,p.getString("projectId")).length();
            }
            new AlertDialog.Builder(activity).setTitle("Проекты · "+labels.length)
                .setItems(labels,(d,index)->{if(current.getAsBoolean())tasks(projects.optJSONObject(index).optString("projectId"));})
                .setPositiveButton("Новый проект",(d,w)->createProject()).setNegativeButton("Закрыть",null).show();
        }catch(Exception error){failure(error);}
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
                JSONObject t=tasks.getJSONObject(i);labels[i]=t.optString("title")+" · "+t.optString("state");
            }
            new AlertDialog.Builder(activity).setTitle(project.optString("name"))
                .setItems(labels,(d,index)->{
                    if(!current.getAsBoolean())return;
                    JSONObject task=tasks.optJSONObject(index);
                    new AlertDialog.Builder(activity).setTitle(task.optString("title"))
                        .setMessage("Состояние: "+task.optString("state")+"\nВетка: "+task.optString("workingBranch")
                            +"\nБюджет: "+task.optJSONObject("budget").optInt("rounds")+" раундов, "+task.optJSONObject("budget").optInt("tools")+" инструментов")
                        .setPositiveButton("Открыть",(x,y)->{if(current.getAsBoolean())try{store.switchConversation(account,task.optString("taskId"));refresh.run();}catch(Exception error){failure(error);}})
                        .setItems(new String[]{"Изменения и diff"},(x,index2)->{if(current.getAsBoolean()){store.switchConversation(account,task.optString("taskId"));refresh.run();changes.accept(task.optString("taskId"));}})
                        .setNeutralButton("Разрешить до 25 раундов",(x,y)->{
                            if(!current.getAsBoolean())return;
                            store.tasks.update(account,task.optString("taskId"),t->{try{t.getJSONObject("budget").put("maxRounds",25).put("maxTokens",360000);}
                                catch(org.json.JSONException error){throw new IllegalStateException(error);}});tasks(projectId);
                        }).setNegativeButton("Назад",(x,y)->tasks(projectId)).show();
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
