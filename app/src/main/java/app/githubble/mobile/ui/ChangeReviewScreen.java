package app.githubble.mobile.ui;

import android.app.*;
import android.widget.*;
import app.githubble.mobile.agent.*;
import java.util.*;
import java.util.concurrent.Executor;
import org.json.*;

/** Per-task review: whole set, one file, exact line hunk, rollback and delta publication. */
public final class ChangeReviewScreen {
    public interface Service {TaskGitService get()throws Exception;}
    private final Activity activity;private final Service factory;private final Executor worker;
    private final java.util.function.BooleanSupplier current;private final Runnable refresh;
    private TaskGitService service;
    public ChangeReviewScreen(Activity activity,Service factory,Executor worker,java.util.function.BooleanSupplier current,Runnable refresh){this.activity=activity;this.factory=factory;this.worker=worker;this.current=current;this.refresh=refresh;}
    private interface Action {void run()throws Exception;}
    private void background(Action action){worker.execute(()->{try{if(!current.getAsBoolean())return;if(service==null)service=factory.get();action.run();}
        catch(Exception error){ui(()->new AlertDialog.Builder(activity).setTitle("Изменения задачи").setMessage(error.getMessage()).setPositiveButton("ОК",null).show());}});}
    private void ui(Runnable action){activity.runOnUiThread(()->{if(current.getAsBoolean()&&!activity.isFinishing()&&!activity.isDestroyed())action.run();});}
    public void show(){background(()->{JSONArray sets=service.changes.list();String[] labels=new String[sets.length()];
        for(int i=0;i<labels.length;i++){JSONObject set=sets.getJSONObject(i);labels[i]=set.optString("summary")+" · "+set.getString("state");}
        ui(()->new AlertDialog.Builder(activity).setTitle("Изменения задачи · "+labels.length)
            .setItems(labels,(d,i)->files(sets.optJSONObject(i)))
            .setPositiveButton("Опубликовать дельту",(d,w)->publish())
            .setNeutralButton("Статус публикации",(d,w)->status()).setNegativeButton("Закрыть",null).show());});}
    private void files(JSONObject set){try{JSONArray entries=set.getJSONArray("entries");String[] labels=new String[entries.length()];for(int i=0;i<labels.length;i++)labels[i]=entries.getJSONObject(i).getString("path")+" · "+entries.getJSONObject(i).getString("state");
        new AlertDialog.Builder(activity).setTitle(set.optString("summary")).setItems(labels,(d,i)->file(set,entries.optJSONObject(i)))
            .setPositiveButton("Принять всё",(d,w)->apply(set.optString("id"),null))
            .setNeutralButton("Откатить",(d,w)->confirm("Откатить изменения задачи?","Общие файлы проекта не изменятся.",()->{service.changes.rollback(set.getString("id"));show();refresh.run();}))
            .setNegativeButton("Назад",(d,w)->show()).show();
        }catch(Exception error){background(()->{throw error;});}}
    private void file(JSONObject set,JSONObject entry){try{
        String path=entry.getString("path"),before=entry.isNull("before")?null:entry.getString("before"),after=entry.isNull("after")?null:entry.getString("after");
        TextView text=new TextView(activity);text.setText(TextEdits.diff(path,before,after));text.setTextIsSelectable(true);text.setTypeface(android.graphics.Typeface.MONOSPACE);text.setTextSize(12);text.setPadding(24,16,24,16);
        ScrollView scroll=new ScrollView(activity);scroll.addView(text);
        new AlertDialog.Builder(activity).setTitle(path).setView(scroll)
            .setPositiveButton("Принять файл",(d,w)->apply(set.optString("id"),Collections.singleton(path)))
            .setNeutralButton("Блоки",(d,w)->hunks(set,entry))
            .setNegativeButton("Отклонить файл",(d,w)->background(()->{service.changes.reject(set.getString("id"),Collections.singleton(path));show();})).show();
        }catch(Exception error){background(()->{throw error;});}}
    private void hunks(JSONObject set,JSONObject entry){try{
        if(entry.isNull("before")||entry.isNull("after"))throw new IllegalArgumentException("Создание и удаление принимаются целиком по файлу");
        String before=entry.getString("before"),after=entry.getString("after");List<TextEdits.Hunk> hunks=TextEdits.hunks(before,after);String[] labels=new String[hunks.size()];boolean[] selected=new boolean[labels.length];
        for(int i=0;i<labels.length;i++)labels[i]=hunks.get(i).preview(before);
        new AlertDialog.Builder(activity).setTitle("Выберите блоки").setMultiChoiceItems(labels,selected,(d,i,on)->selected[i]=on)
            .setPositiveButton("Принять выбранные",(d,w)->background(()->{Set<Integer> indices=new HashSet<>();for(int i=0;i<selected.length;i++)if(selected[i])indices.add(i);
                service.changes.approveHunks(set.getString("id"),entry.getString("path"),indices,service.source());service.recordChanges();show();refresh.run();}))
            .setNegativeButton("Отмена",null).show();
        }catch(Exception error){background(()->{throw error;});}}
    private void apply(String id,Set<String> paths){background(()->{service.changes.approve(id,paths);service.changes.apply(id,service.source());show();refresh.run();});}
    private void confirm(String title,String detail,Action action){ui(()->new AlertDialog.Builder(activity).setTitle(title).setMessage(detail)
        .setPositiveButton("Подтвердить",(d,w)->background(action)).setNegativeButton("Отмена",null).show());}
    public void publish(){background(()->{JSONObject preview=service.preview();if(preview.getInt("count")==0)throw new IllegalArgumentException("Нет подтверждённых файлов для публикации");
        confirm("Опубликовать изменения задачи?",preview.toString(2),()->{JSONObject result=service.publishApproved(preview,"feat: task changes");refresh.run();ui(()->new AlertDialog.Builder(activity).setTitle("Commit подтверждён")
            .setMessage(result.toString()).setPositiveButton("ОК",null).show());});});}
    private void status(){background(()->{JSONObject status=service.publicationStatus();ui(()->new AlertDialog.Builder(activity).setTitle("Публикация")
        .setMessage(status.toString()).setPositiveButton("Сверить ref",(d,w)->background(()->{service.reconcile();show();refresh.run();})).setNegativeButton("Закрыть",null).show());});}
}
