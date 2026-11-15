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
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** OneShot help page in Settings: no controller, no PEM, no permanent credentials. */
public final class MirrorSetupWizardScreen {
    private MirrorSetupWizardScreen(){}

    public static void render(Activity activity,LinearLayout host,MirrorManager manager,
            GitHubAccountStore accounts,int card,int text,int muted,int accent,int border,
            Executor background,Runnable reload,Consumer<String> openUrl,Runnable done){
        LinearLayout panel=new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(activity,16),dp(activity,18),dp(activity,16),dp(activity,18));
        GradientDrawable shape=new GradientDrawable();
        shape.setColor(card);shape.setCornerRadius(dp(activity,18));shape.setStroke(dp(activity,1),border);
        panel.setBackground(shape);
        host.addView(panel);
        title(activity,panel,"Mirror Manager OneShot",20,text);
        info(activity,panel,"Разовая передача репозитория между любыми двумя подключёнными аккаунтами. "
                +"Отдельный контроллер и ручной ввод Private Key / Client ID не нужны.",muted);
        title(activity,panel,"1. Подключённые аккаунты",16,text);
        for(GitHubAccountStore.Account a:accounts.listAccounts()){
            info(activity,panel,"✓ "+a.displayName(),accent);
        }
        if(accounts.listAccounts().size()<2){
            info(activity,panel,"Для OneShot подключите ещё один GitHub-аккаунт.",muted);
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
        title(activity,panel,"3. OneShot перенос",16,text);
        info(activity,panel,"Откройте Репозитории → исходный репозиторий → Mirror Manager. "
                +"Выберите другой аккаунт и название. "
                +"Приложение создаст пустой репозиторий назначения и временный workflow "
                +"на исходной default branch. GitHub Actions перенесёт Git/LFS. "
                +"По завершении нажмите «Проверить», чтобы сверить refs и удалить "
                +"временный Secret и workflow.",muted);
        info(activity,panel,"В истории исходного репозитория останутся два технических коммита, "
                +"даже после удаления временного workflow. Исходные пользовательские файлы "
                +"не перезаписываются. Работа с защищённой default branch может потребовать "
                +"изменения правил репозитория.",muted);
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
