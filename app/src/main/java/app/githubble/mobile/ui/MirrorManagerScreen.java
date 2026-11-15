package app.githubble.mobile.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import app.githubble.mobile.auth.GitHubAccountStore;
import app.githubble.mobile.mirror.MirrorManager;
import app.githubble.mobile.mirror.MirrorPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Native account-scoped Mirror Manager screen; background calls never block the UI thread. */
public final class MirrorManagerScreen {
    private MirrorManagerScreen(){}

    public static void render(Activity activity,LinearLayout host,MirrorManager manager,
            GitHubAccountStore store,String source,int card,int text,int muted,int accent,int border,
            Executor background,Runnable reload,Consumer<String> openUrl) {
        panel(activity,host,card,border,text,muted,"Mirror Manager",
                "Перенос всех Git-веток, тегов, коммитов и LFS на runner GitHub. "
                +"На телефон исходники и архивы не скачиваются.");
        if(source!=null&&!source.isEmpty()) {
            GitHubAccountStore.Account active=store.activeAccount();
            List<GitHubAccountStore.Account> targets=new ArrayList<>();
            for(GitHubAccountStore.Account account:store.listAccounts())
                if(active!=null&&!active.id.equals(account.id))targets.add(account);
            LinearLayout box=vertical(activity);
            heading(activity,box,"Новое зеркало · "+source,text);
            if(active==null||targets.isEmpty()) {
                subtitle(activity,box,"Подключите второй аккаунт GitHub, чтобы выбрать назначение.",muted);
            } else {
                subtitle(activity,box,"Источник: @"+active.login+" · публичный репозиторий",muted);
                List<String> labels=new ArrayList<>();
                for(GitHubAccountStore.Account a:targets)labels.add(a.displayName());
                Spinner selector=new Spinner(activity);
                selector.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,labels));
                box.addView(selector,new LinearLayout.LayoutParams(-1,dp(activity,52)));
                EditText name=new EditText(activity);
                name.setSingleLine(true);name.setHint("Название нового репозитория");
                name.setText(source.substring(source.indexOf('/')+1));box.addView(name);
                CheckBox managed=new CheckBox(activity);
                managed.setChecked(true);
                managed.setText("Управляемое зеркало · повторная синхронизация вручную");
                box.addView(managed);
                subtitle(activity,box,"Назначение должно быть новым. Повторное обновление "
                        +"перезаписывает ветки и удаляет отсутствующие в источнике. "
                        +"Приватные репозитории будут поддержаны только через защищённый runner.",muted);
                Button start=button(activity,"Создать зеркало",accent);
                box.addView(start);
                start.setOnClickListener(v->{
                    int index=selector.getSelectedItemPosition();
                    if(index<0||index>=targets.size())return;
                    String repoName=name.getText().toString().trim();
                    try {MirrorPolicy.requireName(repoName);}
                    catch(Exception invalid){name.setError(invalid.getMessage());return;}
                    GitHubAccountStore.Account target=targets.get(index);
                    boolean isManaged=managed.isChecked();
                    new AlertDialog.Builder(activity).setTitle("Подтвердить зеркалирование")
                            .setMessage(source+" → "+target.login+"/"+repoName
                                    +"\n\nПередача выполняется только в облаке GitHub. "
                                    +"В приложении не сохраняются общие токены двух аккаунтов. "
                                    +"Новый репозиторий будет создан на целевом аккаунте.")
                            .setNegativeButton("Отмена",null)
                            .setPositiveButton("Создать",(dialog,which)->run(activity,background,
                                    "Создание зеркала",()->manager.start(source,active.id,target.id,repoName,isManaged),reload))
                            .show();
                });
            }
            wrap(activity,host,box,card,border);
        }else {
            panel(activity,host,card,border,text,muted,"Новое зеркало",
                    "Откройте репозиторий и выберите «Зеркалировать» в меню проекта.");
        }
        heading(activity,host,"Мои зеркала",text);
        List<MirrorManager.Job> jobs=manager.list();
        if(jobs.isEmpty())subtitle(activity,host,"Операций пока нет.",muted);
        for(MirrorManager.Job job:jobs) {
            LinearLayout box=vertical(activity);
            heading(activity,box,job.source+" → "+job.destination,text);
            subtitle(activity,box,status(job.state)+" · "+(job.isManaged()?"Управляемое":"Разовая копия")
                    +(job.error.isEmpty()?"":"\n"+job.error),muted);
            LinearLayout controls=new LinearLayout(activity);
            Button verify=button(activity,"Проверить",accent);
            controls.addView(verify,new LinearLayout.LayoutParams(0,dp(activity,48),1));
            verify.setOnClickListener(v->run(activity,background,"Проверяем GitHub",
                    ()->manager.refresh(job.id),reload));
            if(job.isManaged()&&(MirrorManager.VERIFIED.equals(job.state)
                    ||MirrorManager.FAILED.equals(job.state)
                    ||MirrorManager.DIVERGED.equals(job.state))) {
                Button sync=button(activity,"Обновить",accent);
                controls.addView(sync,new LinearLayout.LayoutParams(0,dp(activity,48),1));
                sync.setOnClickListener(v->new AlertDialog.Builder(activity)
                        .setTitle("Обновить зеркало?")
                        .setMessage(job.source+" → "+job.destination
                                +"\n\nВнимание: целевые ветки, отсутствующие в источнике, "
                                +"будут удалены. Изменения только на назначении могут быть утрачены.")
                        .setNegativeButton("Отмена",null)
                        .setPositiveButton("Синхронизировать",(d,w)->run(activity,background,
                                "Синхронизация",()->manager.sync(job.id),reload)).show());
            }
            box.addView(controls);
            if(!job.runUrl.isEmpty()) {
                Button run=button(activity,"Открыть запуск Actions #"+job.runId,accent);
                run.setOnClickListener(v->openUrl.accept(job.runUrl));
                box.addView(run);
            }
            wrap(activity,host,box,card,border);
        }
    }

    private interface Task{MirrorManager.Job execute()throws Exception;}
    private static void run(Activity a,Executor executor,String title,Task task,Runnable refresh){
        ProgressDialog progress=ProgressDialog.show(a,title,"GitHub обрабатывает операцию…",true,false);
        executor.execute(()->{
            try{
                task.execute();
                a.runOnUiThread(()->{if(a.isFinishing())return;progress.dismiss();refresh.run();});
            }catch(Exception ex){
                a.runOnUiThread(()->{
                    if(a.isFinishing())return;
                    progress.dismiss();refresh.run();
                    new AlertDialog.Builder(a).setTitle("Mirror Manager")
                            .setMessage(ex.getMessage()==null?"Ошибка GitHub":ex.getMessage())
                            .setPositiveButton("OK",null).show();
                });
            }
        });
    }
    public static String status(String state){
        if(MirrorManager.PREPARING.equals(state))return "Подготовка";
        if(MirrorManager.DEST_CREATED.equals(state))return "Назначение создано";
        if(MirrorManager.DISPATCHED.equals(state))return "Запуск отправлен";
        if(MirrorManager.RUNNING.equals(state))return "Выполняется";
        if(MirrorManager.VERIFIED.equals(state))return "Проверено · все Git refs совпадают";
        if(MirrorManager.FAILED.equals(state))return "Ошибка GitHub Actions";
        if(MirrorManager.DIVERGED.equals(state))return "Ветки или теги различаются";
        if(MirrorManager.UNKNOWN.equals(state))return "Результат неизвестен · проверьте на GitHub";
        return state;
    }
    private static void panel(Activity a,LinearLayout parent,int card,int border,
            int text,int muted,String title,String description){
        LinearLayout box=vertical(a);heading(a,box,title,text);
        subtitle(a,box,description,muted);wrap(a,parent,box,card,border);
    }
    private static void wrap(Activity a,LinearLayout parent,LinearLayout box,int card,int border){
        box.setPadding(dp(a,14),dp(a,13),dp(a,14),dp(a,13));
        GradientDrawable drawable=new GradientDrawable();drawable.setColor(card);
        drawable.setCornerRadius(dp(a,16));drawable.setStroke(dp(a,1),border);
        box.setBackground(drawable);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.setMargins(0,dp(a,5),0,dp(a,10));parent.addView(box,p);
    }
    private static void heading(Activity a,LinearLayout parent,String value,int color){
        TextView label=new TextView(a);label.setText(value);label.setTextColor(color);
        label.setTextSize(16);label.setTypeface(null,Typeface.BOLD);
        label.setPadding(0,dp(a,5),0,dp(a,7));parent.addView(label);
    }
    private static void subtitle(Activity a,LinearLayout parent,String value,int color){
        TextView label=new TextView(a);label.setText(value);label.setTextColor(color);
        label.setTextSize(12);label.setPadding(0,0,0,dp(a,9));parent.addView(label);
    }
    private static LinearLayout vertical(Activity a) {
        LinearLayout l=new LinearLayout(a);l.setOrientation(LinearLayout.VERTICAL);return l;
    }
    private static Button button(Activity a,String label,int tint) {
        Button b=new Button(a);b.setText(label);b.setTextColor(tint);
        b.setAllCaps(false);return b;
    }
    private static int dp(Activity a,int x){return (int)(x*a.getResources().getDisplayMetrics().density+.5f);}
}
