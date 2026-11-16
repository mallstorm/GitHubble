package app.githubble.mobile.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.Button;
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

/** Compact OneShot transfer and journal UI. Configuration belongs to Settings. */
public final class MirrorManagerScreen {
    private MirrorManagerScreen(){}

    public static void render(Activity activity,LinearLayout host,MirrorManager manager,
            GitHubAccountStore store,String source,int card,int text,int muted,int accent,int border,
            Executor background,Runnable reload,Consumer<String> openUrl) {

        LinearLayout intro=vertical(activity);
        eyebrow(activity,intro,"MIRROR MANAGER",accent);
        heading(activity,intro,"Зеркалирование репозиториев",text,19);
        subtitle(activity,intro,"Создай копию, обнови существующее зеркало или верни доработки "
                +"обратно через Pull Request. Без Fork и загрузки исходников на телефон.",muted);
        wrap(activity,host,intro,card,border);

        if(source!=null && source.contains("/")) {
            GitHubAccountStore.Account active=null;
            String sourceOwner=source.substring(0,source.indexOf('/'));
            for(GitHubAccountStore.Account candidate:store.listAccounts())
                if(sourceOwner.equalsIgnoreCase(candidate.login)){active=candidate;break;}
            final GitHubAccountStore.Account srcAccount=active;
            List<GitHubAccountStore.Account> targets=new ArrayList<>();
            for(GitHubAccountStore.Account item:store.listAccounts())
                if(srcAccount!=null&&!srcAccount.id.equals(item.id))targets.add(item);
            LinearLayout form=vertical(activity);
            heading(activity,form,"Новая копия",text,17);
            infoLabel(activity,form,"ИСТОЧНИК",muted);
            TextView src=body(activity,source,text,14);
            src.setTypeface(null,Typeface.BOLD);form.addView(src);
            gap(activity,form,9);
            if(srcAccount==null||targets.isEmpty()){
                subtitle(activity,form,"Для переноса подключите исходный аккаунт и хотя бы один аккаунт назначения.",muted);
            }else{
                infoLabel(activity,form,"КОПИРОВАТЬ В АККАУНТ",muted);
                Spinner selector=new Spinner(activity);
                List<String> labels=new ArrayList<>();
                for(GitHubAccountStore.Account a:targets)labels.add("@"+a.login);
                ArrayAdapter<String> adapter=new ArrayAdapter<>(activity,
                        android.R.layout.simple_spinner_dropdown_item,labels);
                selector.setAdapter(adapter);
                selector.setPadding(dp(activity,8),0,0,0);
                selector.setBackground(null);
                LinearLayout selectRow=new LinearLayout(activity);
                selectRow.setOrientation(LinearLayout.HORIZONTAL);
                selectRow.setGravity(Gravity.CENTER_VERTICAL);
                selectRow.addView(selector,new LinearLayout.LayoutParams(0,-1,1));
                TextView chevron=body(activity,"▾",muted,22);
                chevron.setGravity(Gravity.CENTER);
                chevron.setOnClickListener(v->selector.performClick());
                selectRow.addView(chevron,new LinearLayout.LayoutParams(dp(activity,42),-1));
                bordered(activity,selectRow,card,border,12);
                form.addView(selectRow,new LinearLayout.LayoutParams(-1,dp(activity,50)));
                gap(activity,form,12);
                infoLabel(activity,form,"ИМЯ НОВОГО РЕПОЗИТОРИЯ",muted);
                EditText name=new EditText(activity);
                name.setSingleLine(true);
                name.setText(source.substring(source.indexOf('/')+1));
                name.setTextSize(15);name.setTextColor(text);
                name.setSelectAllOnFocus(true);
                name.setHint("Имя репозитория");
                name.setPadding(dp(activity,12),0,dp(activity,12),0);
                bordered(activity,name,card,border,12);
                form.addView(name,new LinearLayout.LayoutParams(-1,dp(activity,50)));
                gap(activity,form,12);
                subtitle(activity,form,"Создаётся новый независимый репозиторий. "
                        +"Существующие проекты не перезаписываются.",muted);
                Button start=button(activity,"Создать копию  →",accent,card,border,true);
                form.addView(start,new LinearLayout.LayoutParams(-1,dp(activity,48)));
                start.setOnClickListener(v->{
                    int index=selector.getSelectedItemPosition();
                    if(index<0||index>=targets.size())return;
                    String repoName=name.getText().toString().trim();
                    try{MirrorPolicy.requireName(repoName);}
                    catch(Exception invalid){name.setError(invalid.getMessage());return;}
                    GitHubAccountStore.Account dstAccount=targets.get(index);
                    String destination=dstAccount.login+"/"+repoName;
                    new AlertDialog.Builder(activity)
                        .setTitle("Копировать репозиторий?")
                        .setMessage(source+"  →  "+destination
                                +"\n\nСоздадим новый пустой репозиторий и перенесём "
                                +"Git-историю, ветки, теги и LFS.\n\n"
                                +"Не переносятся Issues, PR, Releases, Actions secrets и настройки. "
                                +"В исходной ветке останутся два технических коммита создания "
                                +"и удаления временного workflow.\n\n"
                                +"Существующий репозиторий назначения не изменяется.")
                        .setNegativeButton("Отмена",null)
                        .setPositiveButton("Копировать",(d,w)->run(activity,background,
                                "OneShot",()->manager.start(source,srcAccount.id,dstAccount.id,repoName,false),reload))
                        .show();
                });
            }
            wrap(activity,host,form,card,border);
        }else{
            LinearLayout noSource=vertical(activity);
            heading(activity,noSource,"Выбери исходный репозиторий",text,16);
            subtitle(activity,noSource,"Открой нужный проект в разделе «Репозитории» и выбери «Зеркалировать».",muted);
            wrap(activity,host,noSource,card,border);
        }

        List<MirrorManager.Job> jobs=manager.list();
        LinearLayout historyHeader=new LinearLayout(activity);
        historyHeader.setOrientation(LinearLayout.HORIZONTAL);
        historyHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=body(activity,"История переносов",text,17);
        title.setTypeface(null,Typeface.BOLD);
        historyHeader.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        TextView count=body(activity,String.valueOf(jobs.size()),muted,12);
        count.setPadding(dp(activity,10),dp(activity,5),dp(activity,10),dp(activity,5));
        bordered(activity,count,card,border,12);
        historyHeader.addView(count);
        LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,-2);
        hp.setMargins(dp(activity,2),dp(activity,15),dp(activity,2),dp(activity,10));
        host.addView(historyHeader,hp);

        if(jobs.isEmpty()){
            LinearLayout empty=vertical(activity);
            subtitle(activity,empty,"Переносов пока нет. После копирования здесь появятся история, статус и ссылки на GitHub.",muted);
            wrap(activity,host,empty,card,border);
        }

        for(MirrorManager.Job job:jobs){
            LinearLayout record=vertical(activity);
            boolean completed=MirrorManager.VERIFIED.equals(job.state)&&!job.cleanupPending;
            boolean failed=MirrorManager.FAILED.equals(job.state)||MirrorManager.DIVERGED.equals(job.state);
            boolean obsolete=MirrorManager.FORK.equals(job.mode)||MirrorManager.MANAGED.equals(job.mode);
            String operation=MirrorManager.RETURN.equals(job.mode)?"ВОЗВРАТ ИЗМЕНЕНИЙ":
                    MirrorManager.SYNC.equals(job.mode)?"ОБНОВЛЕНИЕ ЗЕРКАЛА":"РАЗОВАЯ КОПИЯ";
            TextView operationText=body(activity,operation,muted,11);
            operationText.setTypeface(null,Typeface.BOLD);
            record.addView(operationText);
            gap(activity,record,6);
            TextView state=body(activity,(completed?"✓  ":"")+
                    (obsolete?"Прежняя операция":status(job.state)),completed?accent:muted,12);
            state.setTypeface(null,Typeface.BOLD);
            record.addView(state);
            gap(activity,record,9);
            TextView from=body(activity,job.source,text,14);from.setTypeface(null,Typeface.BOLD);
            record.addView(from);
            TextView arrow=body(activity,"↓  КОПИЯ В",muted,11);
            arrow.setPadding(0,dp(activity,5),0,dp(activity,5));record.addView(arrow);
            TextView to=body(activity,job.destination,text,14);to.setTypeface(null,Typeface.BOLD);
            record.addView(to);
            if(!job.baselineSha.isEmpty()){
                gap(activity,record,6);
                subtitle(activity,record,"Снимок: "+job.baselineSha.substring(0,Math.min(10,job.baselineSha.length())),muted);
            }
            if(job.cleanupPending){
                gap(activity,record,7);
                subtitle(activity,record,"Требуется завершить очистку временного секрета и workflow.",muted);
            }
            if(!job.error.isEmpty()){
                gap(activity,record,6);
                subtitle(activity,record,job.error,muted);
            }
            gap(activity,record,10);
            LinearLayout actions=new LinearLayout(activity);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setGravity(Gravity.CENTER_VERTICAL);
            Button verify=button(activity,completed?"Проверено":"Проверить",accent,card,border,false);
            verify.setEnabled(!obsolete);
            verify.setOnClickListener(v->run(activity,background,"Проверка OneShot",
                    ()->manager.refresh(job.id),reload));
            LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(0,dp(activity,44),1);
            ap.setMargins(0,0,dp(activity,7),0);actions.addView(verify,ap);
            Button destination=button(activity,"Репозиторий ↗",accent,card,border,false);
            destination.setOnClickListener(v->openUrl.accept("https://github.com/"+job.destination));
            actions.addView(destination,new LinearLayout.LayoutParams(0,dp(activity,44),1));
            record.addView(actions);
            if(MirrorManager.COPY.equals(job.mode) && completed){
                gap(activity,record,8);
                LinearLayout updates=new LinearLayout(activity);
                updates.setOrientation(LinearLayout.HORIZONTAL);
                Button sync=button(activity,"Обновить зеркало",accent,card,border,false);
                updates.addView(sync,new LinearLayout.LayoutParams(0,dp(activity,46),1));
                LinearLayout.LayoutParams actionSpacing=new LinearLayout.LayoutParams(0,dp(activity,46),1);
                actionSpacing.setMargins(dp(activity,7),0,0,0);
                Button reverse=button(activity,"Вернуть изменения",accent,card,border,false);
                updates.addView(reverse,actionSpacing);
                record.addView(updates);
                sync.setOnClickListener(v->new AlertDialog.Builder(activity)
                    .setTitle("Обновить существующее зеркало?")
                    .setMessage(job.source+" → "+job.destination
                            +"\n\nДобавим новые ветки, коммиты и теги в уже существующий "
                            +"репозиторий. Обновление только fast-forward: никакого force push, "
                            +"удаления или перезаписи самостоятельных изменений. "
                            +"При конфликте операция остановится.")
                    .setNegativeButton("Отмена",null)
                    .setPositiveButton("Обновить",(d,w)->run(activity,background,"Обновление зеркала",
                            ()->manager.updateMirror(job.id),reload)).show());
                reverse.setOnClickListener(v->new AlertDialog.Builder(activity)
                    .setTitle("Вернуть изменения?")
                    .setMessage(job.destination+" → "+job.source
                            +"\n\nСоздадим отдельную ветку возврата в исходном репозитории "
                            +"и черновик Pull Request. Основная ветка НЕ будет перезаписана. "
                            +"Ты сможешь проверить изменения и нажать Merge самостоятельно.")
                    .setNegativeButton("Отмена",null)
                    .setPositiveButton("Подготовить PR",(d,w)->run(activity,background,"Подготовка возврата",
                            ()->manager.returnChanges(job.id),reload)).show());
            }
            if(!job.prUrl.isEmpty()){
                Button pr=button(activity,"Открыть Pull Request ↗",accent,card,border,true);
                LinearLayout.LayoutParams prParams=new LinearLayout.LayoutParams(-1,dp(activity,46));
                prParams.setMargins(0,dp(activity,8),0,0);
                record.addView(pr,prParams);
                pr.setOnClickListener(v->openUrl.accept(job.prUrl));
            }
            if(!job.runUrl.isEmpty()){
                TextView logs=body(activity,"Журнал GitHub Actions  ↗",accent,13);
                logs.setPadding(0,dp(activity,12),0,dp(activity,3));
                logs.setOnClickListener(v->openUrl.accept(job.runUrl));
                record.addView(logs);
            }
            wrap(activity,host,record,card,border);
        }
        LinearLayout foot=vertical(activity);
        subtitle(activity,foot,"Обновление существующего зеркала выполняется без force push. "
                +"Возврат изменений создаёт Draft Pull Request; слияние выполняется "
                +"только после твоего подтверждения на GitHub.",muted);
        host.addView(foot);
    }

    private interface Task{MirrorManager.Job execute()throws Exception;}
    private static void run(Activity a,Executor background,String label,Task task,Runnable reload){
        ProgressDialog dialog=ProgressDialog.show(a,label,"Связываемся с GitHub…",true,false);
        background.execute(()->{
            try{
                task.execute();
                a.runOnUiThread(()->{if(a.isFinishing())return;dialog.dismiss();reload.run();});
            }catch(Exception error){
                a.runOnUiThread(()->{
                    if(a.isFinishing())return;
                    dialog.dismiss();reload.run();
                    new AlertDialog.Builder(a).setTitle("OneShot")
                            .setMessage(error.getMessage()==null?"Ошибка GitHub":error.getMessage())
                            .setPositiveButton("OK",null).show();
                });
            }
        });
    }
    public static String status(String state){
        if(MirrorManager.PREPARING.equals(state))return "Подготовка";
        if(MirrorManager.DEST_CREATED.equals(state))return "Назначение создано";
        if(MirrorManager.DISPATCHED.equals(state))return "Запуск отправлен";
        if(MirrorManager.RUNNING.equals(state))return "Копирование в GitHub Actions";
        if(MirrorManager.VERIFIED.equals(state))return "Git-снимок совпадает";
        if(MirrorManager.PR_READY.equals(state))return "Draft Pull Request готов";
        if(MirrorManager.PR_MERGED.equals(state))return "Pull Request объединён";
        if(MirrorManager.PR_CLOSED.equals(state))return "Pull Request закрыт";
        if(MirrorManager.FAILED.equals(state))return "Ошибка переноса";
        if(MirrorManager.DIVERGED.equals(state))return "Различаются Git-снимки";
        if(MirrorManager.UNKNOWN.equals(state))return "Требуется проверка";
        if(MirrorManager.CLEANUP_PENDING.equals(state))return "Завершение очистки";
        return state;
    }

    private static void infoLabel(Activity a,LinearLayout box,String value,int muted){
        TextView t=body(a,value,muted,11);t.setTypeface(null,Typeface.BOLD);
        t.setPadding(0,dp(a,1),0,dp(a,7));box.addView(t);
    }
    private static void eyebrow(Activity a,LinearLayout box,String value,int accent){
        TextView t=body(a,value,accent,11);t.setTypeface(null,Typeface.BOLD);
        t.setPadding(0,0,0,dp(a,8));box.addView(t);
    }
    private static void heading(Activity a,LinearLayout parent,String value,int color,int size){
        TextView view=body(a,value,color,size);
        view.setTypeface(null,Typeface.BOLD);
        view.setPadding(0,0,0,dp(a,7));parent.addView(view);
    }
    private static void subtitle(Activity a,LinearLayout parent,String value,int color){
        TextView view=body(a,value,color,12);
        view.setLineSpacing(dp(a,2),1f);
        parent.addView(view);
    }
    private static TextView body(Activity a,String value,int color,int size){
        TextView view=new TextView(a);
        view.setText(value);view.setTextColor(color);view.setTextSize(size);
        return view;
    }
    private static void gap(Activity a,LinearLayout parent,int amount){
        parent.addView(new android.view.View(a),new LinearLayout.LayoutParams(1,dp(a,amount)));
    }
    private static void wrap(Activity a,LinearLayout parent,LinearLayout body,int bg,int border){
        body.setPadding(dp(a,17),dp(a,16),dp(a,17),dp(a,17));
        bordered(a,body,bg,border,17);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.setMargins(0,dp(a,5),0,dp(a,9));parent.addView(body,p);
    }
    private static void bordered(Activity a,android.view.View view,int bg,int border,int radius){
        GradientDrawable shape=new GradientDrawable();
        shape.setColor(bg);shape.setCornerRadius(dp(a,radius));
        shape.setStroke(dp(a,1),border);
        view.setBackground(shape);
    }
    private static Button button(Activity a,String label,int accent,int card,int border,boolean primary){
        Button b=new Button(a);
        b.setText(label);b.setAllCaps(false);b.setTextSize(13);
        b.setTypeface(null,Typeface.BOLD);
        b.setTextColor(primary?Color.WHITE:accent);
        b.setMinHeight(0);b.setMinimumHeight(0);
        b.setPadding(dp(a,9),0,dp(a,9),0);
        GradientDrawable bg=new GradientDrawable();
        bg.setColor(primary?accent:card);bg.setCornerRadius(dp(a,12));
        if(!primary)bg.setStroke(dp(a,1),border);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(
                primary?0x44FFFFFF:0x18000000),bg,null));
        b.setBackgroundTintList(null);
        return b;
    }
    private static LinearLayout vertical(Activity a){
        LinearLayout box=new LinearLayout(a);box.setOrientation(LinearLayout.VERTICAL);return box;
    }
    private static int dp(Activity a,int n){return (int)(n*a.getResources().getDisplayMetrics().density+.5f);}
}
