package app.githubble.mobile.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import app.githubble.mobile.auth.GitHubAccountStore;
import app.githubble.mobile.mirror.MirrorManager;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Guided one-time setup inside Settings, separate from the repository transfer UI. */
public final class MirrorSetupWizardScreen {
    private static final String PREFS="githubble_mirror_setup_wizard";
    private static final String KEY_STEP="step";
    private static final String APP_URL="https://github.com/settings/apps";
    private static final String INSTALLATIONS_URL="https://github.com/settings/installations";
    private static final String SECRETS_URL=MirrorManager.CONTROLLER_URL+"/settings/secrets/actions";
    private static final String VARIABLES_URL=MirrorManager.CONTROLLER_URL+"/settings/variables/actions";
    private static final String SECRET_NAME="GITHUBBLE_MIRROR_APP_PRIVATE_KEY";
    private static final String VARIABLE_NAME="GITHUBBLE_MIRROR_APP_CLIENT_ID";
    private static final int STEPS=6;
    private MirrorSetupWizardScreen() {}

    public static void render(Activity activity,LinearLayout host,MirrorManager manager,
            GitHubAccountStore accounts,int card,int foreground,int muted,int accent,int border,
            Executor executor,Runnable reload,Consumer<String> openUrl,Runnable done) {
        SharedPreferences prefs=activity.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        int step=Math.max(0,Math.min(STEPS-1,prefs.getInt(KEY_STEP,0)));
        LinearLayout header=vertical(activity);
        header.setPadding(dp(activity,16),dp(activity,13),dp(activity,16),dp(activity,13));
        title(activity,header,"Мастер настройки зеркалирования",20,foreground);
        description(activity,header,"Один раз подготовим доступ. Приватные ключи остаются на GitHub — в GitHubble их вводить не нужно.",muted);
        TextView counter=label(activity,"Шаг "+(step+1)+" из "+STEPS,12,true,accent);
        header.addView(counter);
        LinearLayout progress=new LinearLayout(activity);
        progress.setOrientation(LinearLayout.HORIZONTAL);
        for(int i=0;i<STEPS;i++){
            TextView mark=label(activity,i<=step?"●":"○",18,true,i<=step?accent:muted);
            mark.setGravity(Gravity.CENTER);
            progress.addView(mark,new LinearLayout.LayoutParams(0,dp(activity,35),1));
        }
        header.addView(progress);
        frame(activity,host,header,card,border);

        LinearLayout content=vertical(activity);
        content.setPadding(dp(activity,16),dp(activity,17),dp(activity,16),dp(activity,17));

        switch(step){
            case 0:
                title(activity,content,"1. Подключённые аккаунты",17,foreground);
                description(activity,content,"Для управления нужен @mallstorm и минимум один другой аккаунт, например @Digger401. Они должны быть авторизованы внутри GitHubble.",muted);
                List<GitHubAccountStore.Account> all=accounts.listAccounts();
                boolean hasManager=false;
                for(GitHubAccountStore.Account a:all){
                    if("mallstorm".equalsIgnoreCase(a.login))hasManager=true;
                    status(activity,content,"●  "+a.displayName(),"Подключён к GitHubble",foreground,muted);
                }
                if(!hasManager)status(activity,content,"Требуется @mallstorm","Добавьте или восстановите его сеанс в настройках аккаунтов.",foreground,muted);
                if(all.size()<2)status(activity,content,"Нужен второй аккаунт","Выполните обычный вход GitHub ещё на одном аккаунте.",foreground,muted);
                description(activity,content,"Подключение аккаунтов в приложении и установка GitHub App — разные действия. Доступ App проверим на следующем шаге.",muted);
                break;
            case 1:
                title(activity,content,"2. Создать приватный контроллер",17,foreground);
                description(activity,content,"Это технический приватный репозиторий @mallstorm. В нём будет работать GitHub Actions. Исходники зеркалируемых проектов в него не загружаются.",muted);
                status(activity,content,"Имя контроллера","mallstorm/GitHubble-Mirror-Control",foreground,muted);
                button(activity,content,"Создать или обновить контроллер",accent,()->new AlertDialog.Builder(activity)
                        .setTitle("Подготовить приватный контроллер?")
                        .setMessage("GitHubble создаст или обновит приватный технический репозиторий GitHubble-Mirror-Control в аккаунте mallstorm. Если он уже существует, изменения затронут только управляющий workflow.")
                        .setNegativeButton("Отмена",null)
                        .setPositiveButton("Создать",(d,w)->execute(activity,executor,"Создаём контроллер…",
                                ()->manager.setupController(),
                                url->new AlertDialog.Builder(activity)
                                        .setTitle("Контроллер готов")
                                        .setMessage("Технический репозиторий доступен. Теперь настройте доступ GitHub App.")
                                        .setPositiveButton("Продолжить",(a,b)->advance(prefs,step,reload))
                                        .show())).show());
                button(activity,content,"Открыть приватный репозиторий",accent,
                        ()->openUrl.accept(MirrorManager.CONTROLLER_URL));
                description(activity,content,"Контроллер создаётся один раз. При обновлении приложения его не нужно создавать заново.",muted);
                break;
            case 2:
                title(activity,content,"3. GitHub App и разрешения",17,foreground);
                description(activity,content,"Откройте GitHub → Settings → Developer settings → GitHub Apps. Выберите уже созданное приложение GiHubble. Создавать второе приложение не нужно.",muted);
                status(activity,content,"Permissions & events",
                        "Repository permissions: Contents — Read and write; Workflows — Read and write. Для запуска управляющего workflow нужен Actions: write, для создания назначения — права на создание репозиториев.",foreground,muted);
                description(activity,content,"Нажмите Save changes. Затем проверьте Installations: приложение должно быть установлено на mallstorm и Digger401 с доступом к нужным репозиториям. Новые разрешения могут потребовать одобрения в GitHub.",muted);
                button(activity,content,"Открыть настройки GitHub Apps",accent,()->openUrl.accept(APP_URL));
                button(activity,content,"Проверить установки GitHub App",accent,()->openUrl.accept(INSTALLATIONS_URL));
                description(activity,content,"Если GitHub App не установлено на целевом аккаунте, копирование не будет разрешено. Пароли и токены вводить в мастер не нужно.",muted);
                break;
            case 3:
                title(activity,content,"4. Client ID → Repository Variable",17,foreground);
                description(activity,content,"Client ID — публичный идентификатор уже существующего GitHub App GiHubble. В настройках приложения откройте раздел General и найдите поле Client ID (обычно начинается с Iv).",muted);
                status(activity,content,"Название переменной",VARIABLE_NAME,foreground,muted);
                button(activity,content,"Скопировать название переменной",accent,()->copy(activity,VARIABLE_NAME));
                description(activity,content,"Теперь в приватном GitHubble-Mirror-Control откройте Settings → Secrets and variables → Actions → Variables → New repository variable. В поле Name вставьте название выше, а в поле Value — Client ID из GitHub App. Сохраните.",muted);
                button(activity,content,"1. Открыть GitHub App (взять Client ID)",accent,()->openUrl.accept(APP_URL));
                button(activity,content,"2. Открыть Repository Variables",accent,()->openUrl.accept(VARIABLES_URL));
                description(activity,content,"Client ID не является приватным ключом. Здесь его можно копировать и хранить в переменной, а не в Secret.",muted);
                break;
            case 4:
                title(activity,content,"5. Private Key → Repository Secret",17,foreground);
                description(activity,content,"В настройках GitHub App GiHubble откройте General → Private keys → Generate a private key. GitHub скачает файл формата .pem. Это и есть приватный ключ, а не токен из GitHubble.",muted);
                status(activity,content,"Название секрета",SECRET_NAME,foreground,muted);
                button(activity,content,"Скопировать название секрета",accent,()->copy(activity,SECRET_NAME));
                description(activity,content,"В приватном контроллере откройте Settings → Secrets and variables → Actions → Secrets → New repository secret. В поле Name укажите имя выше. В поле Secret вставьте полный текст .pem, включая строки BEGIN ... PRIVATE KEY и END ... PRIVATE KEY (точно как в .pem), затем Save secret.",muted);
                button(activity,content,"1. Открыть GitHub App (создать .pem)",accent,()->openUrl.accept(APP_URL));
                button(activity,content,"2. Открыть Repository Secrets",accent,()->openUrl.accept(SECRETS_URL));
                description(activity,content,"ВАЖНО: не присылайте .pem в чат, не загружайте в GitHubble и не записывайте в исходники. GitHub скрывает значение сохранённого секрета. Если ключ раскрыт, удалите его и сгенерируйте новый.",muted);
                break;
            default:
                title(activity,content,"6. Проверка готовности",17,foreground);
                description(activity,content,"Проверим приватный контроллер, активность workflow и метаданные секретов. Содержимое приватного ключа приложение не читает.",muted);
                TextView result=label(activity,"Нажмите «Проверить настройки».",13,false,muted);
                content.addView(result);
                button(activity,content,"Проверить настройки",accent,()->{
                    result.setText("Проверяем GitHub...");
                    executor.execute(()->{
                        MirrorManager.SetupStatus state=manager.setupStatus();
                        activity.runOnUiThread(()->{
                            if(activity.isFinishing())return;
                            result.setText(statusText(state));
                            result.setTextColor(state.verifiedReady()?accent:foreground);
                        });
                    });
                });
                description(activity,content,"Готовность по метаданным ещё не подтверждает успешную передачу Git/LFS. После настройки выполните контрольное зеркалирование небольшого тестового репозитория.",muted);
                button(activity,content,"Открыть контроллер Actions",accent,
                        ()->openUrl.accept(MirrorManager.CONTROLLER_URL+"/actions"));
                break;
        }
        frame(activity,host,content,card,border);

        LinearLayout nav=new LinearLayout(activity);
        nav.setPadding(dp(activity,8),dp(activity,6),dp(activity,8),dp(activity,8));
        if(step>0){
            Button prev=navigation(activity,"Назад",accent);
            prev.setOnClickListener(v->{prefs.edit().putInt(KEY_STEP,step-1).apply();reload.run();});
            nav.addView(prev,new LinearLayout.LayoutParams(0,dp(activity,50),1));
        }
        Button next=navigation(activity,step==STEPS-1?"Вернуться в настройки":"Продолжить",accent);
        next.setOnClickListener(v->{
            if(step==STEPS-1){prefs.edit().putInt(KEY_STEP,0).apply();done.run();}
            else advance(prefs,step,reload);
        });
        nav.addView(next,new LinearLayout.LayoutParams(0,dp(activity,50),1));
        host.addView(nav,new LinearLayout.LayoutParams(-1,-2));
    }

    private static String statusText(MirrorManager.SetupStatus s){
        StringBuilder b=new StringBuilder();
        b.append(s.controllerAccountConnected?"✓ @mallstorm подключён":"✕ @mallstorm не подключён");
        b.append("\n").append(s.connectedAccounts>=2?"✓ Несколько аккаунтов подключены":"✕ Требуются минимум два аккаунта");
        b.append("\n").append(marker(s.controller)).append(" Контроллер: ").append(s.controllerMessage);
        b.append("\n").append(marker(s.workflow)).append(" Workflow: ").append(s.workflowMessage);
        b.append("\n").append(marker(s.variable)).append(" Client ID: ").append(s.variableMessage);
        b.append("\n").append(marker(s.secret)).append(" Private Key: ").append(s.secretMessage);
        b.append("\n\n");
        b.append(s.verifiedReady()
                ?"✓ Метаданные настройки подтверждены. Дальше проверьте реальный тестовый перенос в Mirror Manager."
                :"Проверка не завершена. Если доступ к метаданным ограничен, наличие секрета и переменной нужно проверить вручную на GitHub.");
        return b.toString();
    }
    private static String marker(int value){
        if(value==MirrorManager.SetupStatus.PRESENT)return "✓";
        if(value==MirrorManager.SetupStatus.MISSING)return "✕";
        return "?";
    }
    private static void advance(SharedPreferences prefs,int step,Runnable reload){
        prefs.edit().putInt(KEY_STEP,Math.min(STEPS-1,step+1)).apply();
        reload.run();
    }
    private interface Task{String perform()throws Exception;}
    private static void execute(Activity a,Executor io,String message,Task task,Consumer<String> success){
        ProgressDialog progress=ProgressDialog.show(a,"Mirror Manager",message,true,false);
        io.execute(()->{
            try{
                String answer=task.perform();
                a.runOnUiThread(()->{
                    if(a.isFinishing())return;
                    progress.dismiss();success.accept(answer);
                });
            }catch(Exception e){
                a.runOnUiThread(()->{
                    if(a.isFinishing())return;
                    progress.dismiss();
                    new AlertDialog.Builder(a).setTitle("Ошибка настройки")
                            .setMessage(e.getMessage()==null?"Не удалось выполнить запрос GitHub":e.getMessage())
                            .setPositiveButton("OK",null).show();
                });
            }
        });
    }
    private static void copy(Activity a,String value){
        ClipboardManager clipboard=(ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE);
        if(clipboard!=null){
            clipboard.setPrimaryClip(ClipData.newPlainText("GitHub Actions name",value));
            Toast.makeText(a,"Название скопировано",Toast.LENGTH_SHORT).show();
        }
    }
    private static LinearLayout vertical(Activity a){
        LinearLayout box=new LinearLayout(a);box.setOrientation(LinearLayout.VERTICAL);return box;
    }
    private static void frame(Activity a,LinearLayout parent,LinearLayout body,int bg,int border){
        GradientDrawable shape=new GradientDrawable();shape.setColor(bg);
        shape.setCornerRadius(dp(a,15));shape.setStroke(dp(a,1),border);
        body.setBackground(shape);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
        lp.setMargins(0,dp(a,5),0,dp(a,8));
        parent.addView(body,lp);
    }
    private static void title(Activity a,LinearLayout host,String value,int sp,int color){
        host.addView(label(a,value,sp,true,color));
    }
    private static void description(Activity a,LinearLayout host,String value,int color){
        TextView text=label(a,value,13,false,color);
        text.setPadding(0,dp(a,8),0,dp(a,12));host.addView(text);
    }
    private static void status(Activity a,LinearLayout host,String name,String detail,int text,int muted){
        TextView t=label(a,name,14,true,text);
        t.setPadding(0,dp(a,7),0,0);host.addView(t);
        description(a,host,detail,muted);
    }
    private static TextView label(Activity a,String value,int sp,boolean bold,int color){
        TextView t=new TextView(a);
        t.setText(value);t.setTextSize(sp);t.setTextColor(color);
        if(bold)t.setTypeface(null,Typeface.BOLD);
        return t;
    }
    private static void button(Activity a,LinearLayout host,String label,int tint,Runnable action){
        Button b=navigation(a,label,tint);
        b.setOnClickListener(v->action.run());
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(a,52));
        lp.setMargins(0,dp(a,4),0,dp(a,5));host.addView(b,lp);
    }
    private static Button navigation(Activity a,String name,int color){
        Button b=new Button(a);b.setText(name);b.setAllCaps(false);
        b.setTextColor(color);b.setTextSize(13);return b;
    }
    private static int dp(Activity a,int size){
        return (int)(size*a.getResources().getDisplayMetrics().density+.5f);
    }
}
