package app.githubble.mobile.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.githubble.mobile.auth.GitHubAccountStore;
import app.githubble.mobile.mirror.MirrorManager;
import app.githubble.mobile.mirror.MirrorCloudStore;
import app.githubble.mobile.mirror.GoogleMirrorCloudStore;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** OneShot help page in Settings: no controller, no PEM, no permanent credentials. */
public final class MirrorSetupWizardScreen {
    private MirrorSetupWizardScreen(){}

    public static void render(Activity activity,LinearLayout host,MirrorManager manager,
            GitHubAccountStore accounts,int card,int text,int muted,int accent,int border,
            Executor background,Runnable reload,Consumer<String> openUrl,Runnable done,
            Runnable cloudCreate,Runnable cloudOpen,Runnable cloudSync,Runnable cloudDisconnect,
            Runnable googleConnect,Runnable googleSync,Runnable googleDisconnect,Runnable googleOpenFolder){
        LinearLayout panel=new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(activity,16),dp(activity,18),dp(activity,16),dp(activity,18));
        GradientDrawable shape=new GradientDrawable();
        shape.setColor(card);shape.setCornerRadius(dp(activity,18));shape.setStroke(dp(activity,1),border);
        panel.setBackground(shape);
        host.addView(panel);
        title(activity,panel,"Mirror Manager",20,text);
        info(activity,panel,"Разовая передача репозитория между любыми двумя подключёнными аккаунтами. "
                +"Отдельный контроллер и ручной ввод Private Key / Client ID не нужны.",muted);
        title(activity,panel,"1. Подключённые аккаунты",16,text);
        for(GitHubAccountStore.Account a:accounts.listAccounts()){
            info(activity,panel,"✓ "+a.displayName(),accent);
        }
        if(accounts.listAccounts().size()<2){
            info(activity,panel,"Для зеркалирования подключите ещё один GitHub-аккаунт.",muted);
        }
        title(activity,panel,"2. Разрешения GitHub App",16,text);
        info(activity,panel,"GitHubble работает через OAuth каждого выбранного пользователя. "
                +"На исходном аккаунте нужны разрешения Repository Contents:write, "
                +"Workflows:write, Secrets:write, Actions:write. "
                +"На целевом — создание репозитория, Contents:write и Workflows:write. "
                +"Установка GitHub App должна охватывать нужные репозитории.",muted);
        info(activity,panel,"GitHub может попросить одобрить новые полномочия приложения. "
                +"GitHubble никогда не запрашивает приватный PEM и не показывает токены в интерфейсе.",muted);
        action(activity,panel,"Открыть установленные GitHub Apps",accent,
                ()->openUrl.accept("https://github.com/settings/installations"));
        action(activity,panel,"Разрешения GitHub Apps",accent,
                ()->openUrl.accept("https://github.com/settings/apps"));
        title(activity,panel,"3. Перенос",16,text);
        info(activity,panel,"Откройте Репозитории → исходный репозиторий → Mirror Manager. "
                +"Выберите другой аккаунт и название. "
                +"Приложение создаст пустой репозиторий назначения и временный workflow "
                +"на исходной default branch. GitHub Actions перенесёт Git/LFS. "
                +"В фоне WorkManager проверит завершение Actions, сверит refs и удалит "
                +"временный Secret и workflow. При необходимости нажмите «Проверить» в истории операций.",muted);
        info(activity,panel,"В истории исходного репозитория останутся два технических коммита, "
                +"даже после удаления временного workflow. Исходные пользовательские файлы "
                +"не перезаписываются. Работа с защищённой default branch может потребовать "
                +"изменения правил репозитория.",muted);
        title(activity,panel,"4. Google Drive · нативная синхронизация",16,text);
        GoogleMirrorCloudStore google=manager.googleCloud();
        if(google.connected()){
            info(activity,panel,"✓ Подключён Google-аккаунт: "+google.email(),accent);
            String folder=google.folderLink();
            if(!folder.isEmpty())info(activity,panel,"Папка GitHubble создана на Google Диске.",muted);
            if(google.lastSync()>0){
                String when=new java.text.SimpleDateFormat("dd.MM.yyyy HH:mm",
                        java.util.Locale.getDefault()).format(new java.util.Date(google.lastSync()));
                info(activity,panel,"Последняя синхронизация: "+when
                        +(google.pending()?" · ожидает обновления":""),muted);
            }
            if(!google.error().isEmpty())info(activity,panel,google.error(),muted);
            action(activity,panel,"Синхронизировать Google Drive",accent,googleSync);
            action(activity,panel,"Открыть папку GitHubble",accent,googleOpenFolder);
            action(activity,panel,"Выбрать другой Google-аккаунт",accent,googleConnect);
            action(activity,panel,"Отключить Google Drive",accent,()->
                    new AlertDialog.Builder(activity).setTitle("Отключить Google Drive?")
                        .setMessage("Связи на телефоне и файлы на Google Диске останутся. "
                                +"GitHubble перестанет синхронизировать их с этим аккаунтом.")
                        .setNegativeButton("Отмена",null)
                        .setPositiveButton("Отключить",(d,w)->googleDisconnect.run()).show());
        }else{
            info(activity,panel,"Войди в Google один раз. GitHubble сам создаст "
                    +"папку «GitHubble» в «Моём диске» и файл mirror-registry.json. "
                    +"Никакого выбора файла или API-ключа не потребуется.",muted);
            action(activity,panel,"Войти через Google и подключить Drive",accent,googleConnect);
        }
        info(activity,panel,"Разрешение Google: только файлы, созданные GitHubble "
                +"(drive.file). Имя Google-аккаунта не связано с текущим GitHub-аккаунтом. "
                +"Google-токены и пароли не записываются в облачный реестр.",muted);

        title(activity,panel,"5. Другие облака · через системный выбор файла",16,text);
        MirrorCloudStore cloud=manager.cloud();
        if(cloud.connected()){
            info(activity,panel,"Дополнительный JSON подключён: "+cloud.location(),accent);
            if(cloud.lastSync()>0){
                String time=new java.text.SimpleDateFormat("dd.MM.yyyy HH:mm",
                        java.util.Locale.getDefault()).format(new java.util.Date(cloud.lastSync()));
                info(activity,panel,"Последняя синхронизация: "+time,muted);
            }
            if(!cloud.lastError().isEmpty())info(activity,panel,cloud.lastError(),muted);
        }else{
            info(activity,panel,"OneDrive или Яндекс Диск можно использовать через "
                    +"системный проводник Android, если приложение облака предоставляет "
                    +"чтение и запись документов. Это дополнительный вариант.",muted);
        }
        action(activity,panel,"Создать JSON в другом облаке",accent,cloudCreate);
        action(activity,panel,"Выбрать существующий JSON",accent,cloudOpen);
        if(cloud.connected()){
            action(activity,panel,"Синхронизировать другое облако",accent,cloudSync);
            action(activity,panel,"Отключить файл другого облака",accent,cloudDisconnect);
        }
        action(activity,panel,"Закрыть мастер",accent,done);
    }
    private static void title(Activity a,LinearLayout parent,String value,int size,int color){
        TextView v=new TextView(a);v.setText(value);v.setTextSize(size);v.setTextColor(color);
        v.setTypeface(null,Typeface.BOLD);
        v.setPadding(0,dp(a,5),0,dp(a,9));parent.addView(v);
    }
    private static void info(Activity a,LinearLayout parent,String value,int color){
        TextView v=new TextView(a);v.setText(value);v.setTextColor(color);v.setTextSize(13);
        v.setPadding(0,dp(a,3),0,dp(a,15));parent.addView(v);
    }
    private static void action(Activity a,LinearLayout parent,String name,int color,Runnable action){
        Button b=new Button(a);b.setAllCaps(false);b.setText(name);b.setTextColor(color);
        b.setOnClickListener(v->action.run());parent.addView(b,new LinearLayout.LayoutParams(-1,dp(a,54)));
    }
    private static int dp(Activity a,int v){
        return (int)(v*a.getResources().getDisplayMetrics().density+.5f);
    }
}
