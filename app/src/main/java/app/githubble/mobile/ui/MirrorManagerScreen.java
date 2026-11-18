package app.githubble.mobile.ui;

import android.app.Activity;
import android.app.AlertDialog;
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
import app.githubble.mobile.mirror.MirrorLinkRegistry;
import app.githubble.mobile.mirror.MirrorCloudStore;
import app.githubble.mobile.mirror.GoogleMirrorCloudStore;
import app.githubble.mobile.mirror.MirrorPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Compact mirror transfer and journal UI. Configuration belongs to Settings. */
public final class MirrorManagerScreen {
    private MirrorManagerScreen(){}

    public static void render(Activity activity,LinearLayout host,MirrorManager manager,
            GitHubAccountStore store,String source,int card,int text,int muted,int accent,int border,
            Executor background,Runnable reload,Consumer<String> openUrl,Runnable openCloudSettings) {

        MirrorLiveProgress.reset();
        LinearLayout intro=vertical(activity);
        eyebrow(activity,intro,"MIRROR MANAGER",accent);
        heading(activity,intro,"Зеркалирование репозиториев",text,19);
        subtitle(activity,intro,"Создай копию, обнови существующее зеркало или верни доработки "
                +"обратно через Pull Request. Без Fork и загрузки исходников на телефон.",muted);
        wrap(activity,host,intro,card,border);

        String runningOperation=operation;
        if(runningOperation!=null){
            LinearLayout busy=vertical(activity);
            eyebrow(activity,busy,"ВЫПОЛНЯЕТСЯ",accent);
            busy.addView(MirrorLiveProgress.panel(activity,null,operationSince,runningOperation+"…",text,muted,accent));
            wrap(activity,host,busy,card,border);
        }

        cloudStatus(activity,host,manager,card,text,muted,accent,border,
                background,reload,openCloudSettings);
        showLinkedPairs(activity,host,manager,source,card,text,muted,accent,border,
                background,reload,openUrl);

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
                Button restore=button(activity,"Связать существующие репозитории",accent,card,border,false);
                LinearLayout.LayoutParams recoverParams=new LinearLayout.LayoutParams(-1,dp(activity,44));
                recoverParams.setMargins(0,dp(activity,8),0,0);
                form.addView(restore,recoverParams);
                restore.setOnClickListener(v->{
                    int index=selector.getSelectedItemPosition();
                    if(index<0||index>=targets.size())return;
                    String repoName=name.getText().toString().trim();
                    try{MirrorPolicy.requireName(repoName);}
                    catch(Exception invalid){name.setError(invalid.getMessage());return;}
                    String existing=targets.get(index).login+"/"+repoName;
                    new AlertDialog.Builder(activity)
                        .setTitle("Связать существующие репозитории?")
                        .setMessage(source+" ↔ "+existing
                                +"\n\nНикакие файлы, ветки и настройки репозиториев не меняются. "
                                +"GitHubble проверит общую историю коммитов и сохранит "
                                +"связанную пару только при подтверждённом совпадении. "
                                +"Обе учётные записи должны быть подключены.")
                        .setNegativeButton("Отмена",null)
                        .setPositiveButton("Проверить связь",(d,w)->runLink(activity,background,
                                "Проверка общей Git-истории",()->manager.recoverLink(source,existing),
                                reload)).show();
                });
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
                                "Создание копии",()->manager.start(source,srcAccount.id,dstAccount.id,repoName,false),reload))
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

        List<MirrorManager.Job> jobs=new ArrayList<>();
        for(MirrorManager.Job j:manager.list())
            if(source==null||!source.contains("/")
                    ||j.source.equalsIgnoreCase(source)||j.destination.equalsIgnoreCase(source))
                jobs.add(j);
        LinearLayout historyHeader=new LinearLayout(activity);
        historyHeader.setOrientation(LinearLayout.HORIZONTAL);
        historyHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=body(activity,"История переносов",text,17);
        title.setTypeface(null,Typeface.BOLD);
        historyHeader.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        TextView count=body(activity,String.valueOf(jobs.size()),muted,12);
        count.setPadding(dp(activity,10),dp(activity,5),dp(activity,10),dp(activity,5));
        bordered(activity,count,card,border,12);
        boolean anyFailed=false;
        for(MirrorManager.Job j:jobs)
            if(MirrorManager.FAILED.equals(j.state)||MirrorManager.DIVERGED.equals(j.state)||MirrorManager.UNKNOWN.equals(j.state))anyFailed=true;
        if(anyFailed){
            TextView clear=body(activity,"Очистить ошибки",accent,12);
            clear.setPadding(dp(activity,4),dp(activity,5),dp(activity,12),dp(activity,5));
            clear.setOnClickListener(v->new AlertDialog.Builder(activity)
                    .setTitle("Убрать неудачные записи?")
                    .setMessage("Будут удалены записи со статусами «Ошибка», «Требуется проверка» и «Различаются снимки». Репозитории на GitHub не затрагиваются.")
                    .setNegativeButton("Отмена",null)
                    .setPositiveButton("Убрать",(d2,w)->run(activity,background,"Очистка истории",
                            ()->{manager.discardFailed();return null;},reload))
                    .show());
            historyHeader.addView(clear);
        }
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
            if(MirrorManager.PREPARING.equals(job.state)||MirrorManager.DEST_CREATED.equals(job.state)
                    ||MirrorManager.DISPATCHED.equals(job.state)||MirrorManager.RUNNING.equals(job.state)){
                gap(activity,record,8);
                record.addView(MirrorLiveProgress.panel(activity,job.id,job.createdAt,status(job.state),text,muted,accent));
            }
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
            verify.setOnClickListener(v->run(activity,background,"Проверка копирования",
                    ()->manager.refresh(job.id),reload));
            LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(0,dp(activity,44),1);
            ap.setMargins(0,0,dp(activity,7),0);actions.addView(verify,ap);
            Button destination=button(activity,"Репозиторий ↗",accent,card,border,false);
            destination.setOnClickListener(v->openUrl.accept("https://github.com/"+job.destination));
            actions.addView(destination,new LinearLayout.LayoutParams(0,dp(activity,44),1));
            record.addView(actions);
            boolean busy=MirrorManager.PREPARING.equals(job.state)||MirrorManager.DEST_CREATED.equals(job.state)
                    ||MirrorManager.DISPATCHED.equals(job.state)||MirrorManager.RUNNING.equals(job.state);
            if(!busy||obsolete){
                Button discard=button(activity,"Удалить запись из истории",0xFFC75B66,card,border,false);
                LinearLayout.LayoutParams dp0=new LinearLayout.LayoutParams(-1,dp(activity,44));
                dp0.setMargins(0,dp(activity,8),0,0);
                discard.setOnClickListener(v->new AlertDialog.Builder(activity)
                        .setTitle("Удалить запись?")
                        .setMessage("Запись о переносе "+job.source+" → "+job.destination
                                +" исчезнет из истории. Репозитории на GitHub не затрагиваются; временные файлы переноса будут удалены, если они остались.")
                        .setNegativeButton("Отмена",null)
                        .setPositiveButton("Удалить",(d2,w)->run(activity,background,"Удаление записи",
                                ()->{String note=manager.discard(job.id);
                                    if(!note.isEmpty())throw new IllegalStateException(note);return null;},reload))
                        .show());
                record.addView(discard,dp0);
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
        MirrorLiveProgress.poll(activity,manager,reload);
        LinearLayout foot=vertical(activity);
        subtitle(activity,foot,"Связь работает в обе стороны независимо от активного аккаунта. "
                +"Fast-forward обновляет существующее зеркало без force push. "
                +"При расхождении Git-истории создаётся Draft Pull Request для проверки.",muted);
        host.addView(foot);
    }

    private static void cloudStatus(Activity a,LinearLayout host,MirrorManager manager,
            int card,int text,int muted,int accent,int border,Executor background,
            Runnable reload,Runnable openCloudSettings){
        GoogleMirrorCloudStore google=manager.googleCloud();
        MirrorCloudStore fallback=manager.cloud();
        LinearLayout panel=vertical(a);
        eyebrow(a,panel,"ОБЛАЧНЫЙ РЕЕСТР",accent);
        if(google.connected()){
            heading(a,panel,"Google Drive подключён",text,15);
            subtitle(a,panel,google.email()
                    +(google.pending()?" · ожидает синхронизации":""),muted);
            if(google.lastSync()>0){
                String time=new java.text.SimpleDateFormat("dd.MM HH:mm",
                        java.util.Locale.getDefault())
                        .format(new java.util.Date(google.lastSync()));
                subtitle(a,panel,"Последняя синхронизация: "+time,muted);
            }
        }else if(fallback.connected()){
            heading(a,panel,"Облачный документ подключён",text,15);
            subtitle(a,panel,"Источник: "+fallback.location(),muted);
        }else{
            heading(a,panel,"Сохраняй связи в Google Drive",text,15);
            subtitle(a,panel,"Войди через Google, и GitHubble сам создаст "
                    +"папку и реестр зеркал. Файлы GitHub не загружаются.",muted);
        }
        Button setup=button(a,google.connected()||fallback.connected()?
                "Управление облаком":"Войти через Google",
                accent,card,border,!google.connected()&&!fallback.connected());
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(a,44));
        p.setMargins(0,dp(a,10),0,0);panel.addView(setup,p);
        setup.setOnClickListener(v->openCloudSettings.run());
        wrap(a,host,panel,card,border);
    }

    private static void showLinkedPairs(Activity a,LinearLayout host,MirrorManager manager,
            String source,int card,int text,int muted,int accent,int border,
            Executor background,Runnable reload,Consumer<String> openUrl){
        List<MirrorLinkRegistry.Link> pairs=(source!=null&&source.contains("/"))
                ?manager.linksFor(source):manager.listLinks();
        if(pairs.isEmpty())return;
        LinearLayout sectionTitle=vertical(a);
        heading(a,sectionTitle,"Связанные репозитории",text,17);
        subtitle(a,sectionTitle,"Отправляй или принимай изменения с любого из подключённых аккаунтов.",muted);
        host.addView(sectionTitle);
        for(MirrorLinkRegistry.Link link:pairs){
            String current=(source!=null&&link.contains(source))?source:link.original;
            String partner=link.partner(current);
            LinearLayout panel=vertical(a);
            eyebrow(a,panel,"СВЯЗАННАЯ ПАРА",accent);
            TextView currentName=body(a,current,text,15);
            currentName.setTypeface(null,Typeface.BOLD);
            panel.addView(currentName);
            TextView arrow=body(a,"⇄  "+partner,muted,14);
            arrow.setPadding(0,dp(a,6),0,dp(a,7));
            panel.addView(arrow);
            subtitle(a,panel,"Направление выбирается автоматически. "
                    +"При конфликте создаётся ветка для проверки и черновик Pull Request.",muted);
            gap(a,panel,12);
            LinearLayout controls=new LinearLayout(a);
            controls.setOrientation(LinearLayout.HORIZONTAL);
            Button send=button(a,"Отправить",accent,card,border,true);
            Button receive=button(a,"Получить",accent,card,border,false);
            LinearLayout.LayoutParams left=new LinearLayout.LayoutParams(0,dp(a,46),1);
            left.setMargins(0,0,dp(a,6),0);
            controls.addView(send,left);
            controls.addView(receive,new LinearLayout.LayoutParams(0,dp(a,46),1));
            panel.addView(controls);
            send.setOnClickListener(v->confirmTransfer(a,background,manager,link,
                    current,partner,reload));
            receive.setOnClickListener(v->confirmTransfer(a,background,manager,link,
                    partner,current,reload));
            LinearLayout misc=new LinearLayout(a);
            misc.setOrientation(LinearLayout.HORIZONTAL);
            Button inspect=button(a,"Сравнить",accent,card,border,false);
            Button partnerButton=button(a,"Репозиторий ↗",accent,card,border,false);
            LinearLayout.LayoutParams leftMinor=new LinearLayout.LayoutParams(0,dp(a,42),1);
            leftMinor.setMargins(0,0,dp(a,6),0);
            misc.addView(inspect,leftMinor);
            misc.addView(partnerButton,new LinearLayout.LayoutParams(0,dp(a,42),1));
            inspect.setOnClickListener(v->runInspect(a,background,manager,link.id,current));
            partnerButton.setOnClickListener(v->openUrl.accept("https://github.com/"+partner));
            gap(a,panel,8);
            panel.addView(misc);
            wrap(a,host,panel,card,border);
        }
    }

    private static void confirmTransfer(Activity a,Executor executor,MirrorManager manager,
            MirrorLinkRegistry.Link link,String from,String to,Runnable reload){
        new AlertDialog.Builder(a)
            .setTitle("Перенести изменения?")
            .setMessage(from+"  →  "+to
                    +"\n\nЕсли Git-история допускает fast-forward, существующий "
                    +"репозиторий обновится без force push и удаления веток. "
                    +"Если истории разошлись, вместо перезаписи будет создана "
                    +"отдельная ветка и Draft Pull Request.\n\n"
                    +"Оба репозитория останутся независимыми.")
            .setNegativeButton("Отмена",null)
            .setPositiveButton("Продолжить",(d,w)->run(a,executor,
                    "Проверяем историю Git",()->manager.transferLinked(link.id,from),reload))
            .show();
    }

    private static void runInspect(Activity a,Executor executor,MirrorManager manager,
            String linkId,String from){
        ProgressDialog progress=ProgressDialog.show(a,"Mirror Manager",
                "Проверяем состояние двух репозиториев…",true,false);
        executor.execute(()->{
            try{
                MirrorManager.PairState p=manager.inspectLink(linkId,from);
                String relation;
                switch(p.relation){
                    case "ahead":relation="Источник впереди назначения";break;
                    case "behind":relation="Источник отстаёт от назначения";break;
                    case "identical":relation="Общий коммит совпадает";break;
                    default:relation="Истории разошлись; нужен Pull Request";break;
                }
                String message=p.from+" → "+p.to+"\n\n"+relation
                        +"\n"+p.fromBranch+": "+shortSha(p.fromSha)
                        +"\n"+p.toBranch+": "+shortSha(p.toSha)
                        +"\nФайлы: "+(p.contentsDiffer?"отличаются":"совпадают")
                        +"\n\nПри конфликте основная ветка не переписывается.";
                a.runOnUiThread(()->{
                    if(a.isFinishing())return;
                    progress.dismiss();
                    new AlertDialog.Builder(a).setTitle("Сравнение Git-истории")
                            .setMessage(message).setPositiveButton("Закрыть",null).show();
                });
            }catch(Exception error){a.runOnUiThread(()->{
                if(a.isFinishing())return;
                progress.dismiss();
                new AlertDialog.Builder(a).setTitle("Не удалось сравнить")
                        .setMessage(error.getMessage()).setPositiveButton("OK",null).show();
            });}
        });
    }

    private interface LinkTask{MirrorLinkRegistry.Link execute()throws Exception;}
    private static void runLink(Activity a,Executor background,String label,LinkTask task,Runnable reload){
        ProgressDialog progress=ProgressDialog.show(a,label,"Проверяем Git-историю…",true,false);
        background.execute(()->{
            try{
                MirrorLinkRegistry.Link link=task.execute();
                a.runOnUiThread(()->{
                    if(a.isFinishing())return;
                    progress.dismiss();
                    reload.run();
                    new AlertDialog.Builder(a).setTitle("Репозитории связаны")
                            .setMessage(link.left+" ⇄ "+link.right
                                    +"\n\nТеперь доступны «Отправить» и «Получить» с обеих сторон.")
                            .setPositiveButton("OK",null).show();
                });
            }catch(Exception error){a.runOnUiThread(()->{
                if(a.isFinishing())return;
                progress.dismiss();
                new AlertDialog.Builder(a).setTitle("Связь не создана")
                        .setMessage(error.getMessage()).setPositiveButton("OK",null).show();
            });}
        });
    }
    private static String shortSha(String sha){
        return sha==null?"":sha.substring(0,Math.min(10,sha.length()));
    }

    private interface Task{MirrorManager.Job execute()throws Exception;}
    private static volatile String operation;
    private static volatile long operationSince;

    /** Runs a mirror action in the background; progress is shown inline on the page, not in a dialog. */
    private static void run(Activity a,Executor background,String label,Task task,Runnable reload){
        operation=label;operationSince=System.currentTimeMillis();
        reload.run();
        background.execute(()->{
            String failure=null;
            try{task.execute();}
            catch(Exception error){failure=error.getMessage()==null?"Ошибка GitHub":error.getMessage();}
            final String message=failure;
            operation=null;
            a.runOnUiThread(()->{
                if(a.isFinishing()||!MirrorLiveProgress.attached())return;
                reload.run();
                if(message!=null)new AlertDialog.Builder(a).setTitle("Зеркалирование")
                        .setMessage(message).setPositiveButton("OK",null).show();
            });
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
