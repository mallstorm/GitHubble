package app.githubble.mobile.ui;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.widget.*;
import app.githubble.mobile.agent.*;
import app.githubble.mobile.memory.MemoryHub;
import app.githubble.mobile.network.AiNetwork;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

/** Native controls over the migrated engines; each project edit retains its account/chat binding. */
public final class AgentFeatureScreens {
    public static final int IMPORT_XRAY=2750,IMPORT_ZIP=2751,EXPORT_ZIP=2752,IMPORT_MEMORY=2753,EXPORT_MEMORY=2754,LINK_FOLDER=2755,EXPORT_HISTORY=2756;
    private final Activity a;private final String owner,chat;private final Supplier<String> active;private final AgentProjectStore projects;
    private final MemoryHub memory;private final WorkspaceHub workspaces;private final ExecutorService io;private final GitAgentUi ui;private final Runnable refresh;
    private String pendingWorkspace="";
    public AgentFeatureScreens(Activity a,String owner,Supplier<String> active,AgentProjectStore projects,MemoryHub memory,ExecutorService io,int bg,Runnable refresh){
        this.a=a;this.owner=owner;this.active=active;this.projects=projects;this.memory=memory;this.io=io;this.ui=new GitAgentUi(a,bg);this.refresh=refresh;
        chat=projects.activeConversation(owner);workspaces=new WorkspaceHub(a);
    }
    public AgentFeatureScreens(Activity a,String owner,Supplier<String> active,AgentProjectStore projects,MemoryHub memory,ExecutorService io,int bg,int surface,int text,int muted,int accent,int border,Runnable refresh){
        this.a=a;this.owner=owner;this.active=active;this.projects=projects;this.memory=memory;this.io=io;
        this.ui=new GitAgentUi(a,bg,surface,text,muted,accent,border);this.refresh=refresh;
        chat=projects.activeConversation(owner);workspaces=new WorkspaceHub(a);
    }
    private boolean current(){return owner.equals(active.get())&&chat.equals(projects.activeConversation(owner))&&!a.isDestroyed();}
    private String wid(){return projects.project(owner).workspaceId;}
    private interface Action {void run()throws Exception;}
    private interface Form {void run(String[] values)throws Exception;}
    private void error(Exception e){new AlertDialog.Builder(a).setTitle("Не удалось выполнить").setMessage(e.getMessage()).setPositiveButton("ОК",null).show();}
    private void run(Action action){if(!current())return;try{action.run();}catch(Exception e){error(e);}}
    private void async(String title,Action action,Runnable done){if(!current())return;ProgressDialog p=ProgressDialog.show(a,title,"Выполняется…",true,false);
        io.execute(()->{Exception failure=null;try{if(current())action.run();}catch(Exception e){failure=e;}final Exception problem=failure;
            a.runOnUiThread(()->{p.dismiss();if(!current())return;if(problem!=null)error(problem);else done.run();});});}
    private void form(String title,String[] labels,String[] defaults,Form action){
        LinearLayout fields=ui.body(title,false);EditText[] inputs=new EditText[labels.length];
        for(int i=0;i<labels.length;i++){inputs[i]=new EditText(a);inputs[i].setHint(labels[i]);inputs[i].setText(defaults[i]);inputs[i].setTextColor(ui.text);fields.addView(inputs[i]);}
        ScrollView scroll=new ScrollView(a);scroll.addView(fields);
        AlertDialog d=new AlertDialog.Builder(a).setView(scroll).setPositiveButton("Сохранить",null).setNegativeButton("Отмена",null).create();
        d.setOnShowListener(x->d.getButton(-1).setOnClickListener(v->{if(!current()){d.dismiss();return;}try{String[] values=new String[inputs.length];for(int i=0;i<values.length;i++)values[i]=inputs[i].getText().toString();action.run(values);d.dismiss();}catch(Exception e){error(e);}}));d.show();
    }
    public void network(){run(()->{
        LinearLayout body=ui.body("Маршрутизация и DNS",true);JSONObject config=new JSONObject(AiNetwork.settings());
        ui.note(body,"API и подписки используют общий DNS и маршрут Xray. Настройки действуют внутри GitHubble.");
        final Dialog[] d={null};
        ui.action(body,"Режим DNS",config.optString("mode")+" · "+config.optString("endpoint"),0xFF2863B8,()->{
            new AlertDialog.Builder(a).setTitle("Режим DNS").setItems(new String[]{"Системный","Smart — автоматический маршрут","DNS over HTTPS","DNS over TLS"},(x,n)->{
                String mode=new String[]{"system","smart","doh","dot"}[n];
                if(n<2)run(()->{AiNetwork.save(mode,"");d[0].dismiss();network();});
                else form("Свой DNS",new String[]{"Endpoint"},new String[]{config.optString("endpoint")},v->{AiNetwork.save(mode,v[0]);d[0].dismiss();network();});
            }).show();});
        ui.action(body,"Проверить DNS/TLS/HTTP","Доступность провайдеров и качество маршрутов",0xFF14846C,()->async("Проверка маршрутов",AiNetwork::sweep,()->{d[0].dismiss();network();}));
        ui.action(body,"Проверить домен","Например auth.openai.com или api.cline.bot",0xFF14846C,()->form("DNS probe",new String[]{"Домен"},new String[]{"api.cline.bot"},v->async("DNS",()->{
            String result=AiNetwork.probe(v[0]);a.runOnUiThread(()->{if(current())new AlertDialog.Builder(a).setTitle("Результат").setMessage(result).setPositiveButton("ОК",null).show();});},()->{})));
        JSONObject registry=new JSONObject(AiNetwork.registry());
        ui.action(body,"Удалённый DNS-реестр",registry.optString("url"),0xFF435B69,()->form("Реестр HTTPS JSON",new String[]{"URL (пусто — отключить)"},new String[]{registry.optString("url")},v->async("Обновление реестра",()->AiNetwork.refreshRegistry(v[0]),()->{d[0].dismiss();network();})));
        ui.action(body,"Добавить DNS-профиль","DoH/DoT · домены · сервисы",0xFF2863B8,()->form("DNS-профиль",new String[]{"Название","doh или dot","Endpoint","Домены через запятую","Сервисы: openai,gemini,grok,cline…"},new String[]{"Мой DNS","doh","","",""},v->{AiNetwork.addDns(v[0],v[1],v[2],v[3],v[4]);d[0].dismiss();network();}));
        ui.action(body,"Xray / VLESS","Импорт ссылок и JSON-профилей",0xFF713CB3,()->{d[0].dismiss();xray();});
        ui.section(body,"DNS-профили");JSONArray profiles=registry.optJSONArray("profiles");
        if(profiles!=null)for(int i=0;i<profiles.length();i++){JSONObject p=profiles.getJSONObject(i);
            ui.action(body,p.optString("name"),p.optString("mode")+" · "+p.optString("endpoint")+"\n"+p.optString("capabilities")+" · "+p.optString("health"),0xFF435B69,()->{
                if("local".equals(p.optString("source")))new AlertDialog.Builder(a).setTitle(p.optString("name")).setMessage(p.toString())
                    .setPositiveButton("Удалить профиль",(x,n)->run(()->{AiNetwork.removeDns(p.optString("id"));d[0].dismiss();network();})).setNegativeButton("Закрыть",null).show();
                else new AlertDialog.Builder(a).setMessage(p.toString()).setPositiveButton("ОК",null).show();});}
        ui.note(body,registry.optString("error")+"\n"+registry.optString("routes"));d[0]=ui.present(body,true);
    });}
    public void xray(){run(()->{
        LinearLayout body=ui.body("Xray / VLESS",true);JSONObject state=new JSONObject(AiNetwork.profiles());final Dialog[] d={null};
        ui.note(body,"Профили зашифрованы. Импорт: vless:// ссылки или Xray JSON. Маршрут применяется только к этому приложению.");
        ui.action(body,"Добавить профиль","Вставить ссылку или JSON",0xFF2863B8,()->form("Xray-профиль",new String[]{"Название","vless://… или JSON"},new String[]{"Мой профиль",""},v->{AiNetwork.importProfile(v[0],v[1]);d[0].dismiss();xray();}));
        ui.action(body,"Загрузить файл профиля","JSON / TXT · до 512 КБ",0xFF2863B8,()->pick(IMPORT_XRAY,"*/*",false,""));
        if(state.optBoolean("enabled"))ui.action(body,"Отключить Xray","Вернуться к обычному маршруту",0xFF713CB3,()->run(()->{AiNetwork.disable();d[0].dismiss();xray();}));
        JSONArray list=state.getJSONArray("profiles");for(int i=0;i<list.length();i++){JSONObject p=list.getJSONObject(i);
            ui.action(body,(p.optBoolean("active")?"✓ ":"")+p.optString("name"),p.optBoolean("active")?"Активен":"Выбрать профиль",0xFF14846C,()->new AlertDialog.Builder(a).setTitle(p.optString("name"))
                .setItems(new String[]{"Подключить","Удалить"},(x,n)->{if(n==0)async("Запуск Xray",()->AiNetwork.enable(p.getString("id")),()->{d[0].dismiss();xray();});
                    else run(()->{AiNetwork.removeProfile(p.getString("id"));d[0].dismiss();xray();});}).show());}
        ui.note(body,state.optString("error"));d[0]=ui.present(body,true);
    });}
    public void memory(){run(()->{
        LinearLayout body=ui.body("Память",true);final Dialog[] d={null};
        String[] keys={"enabled","auto_learn","cross_chat","episodes","project_scope"};String[] labels={"Память включена","Автообучение — дополнительный запрос к модели","Вспоминать из других чатов","Эпизодическая память","Память проектов"};
        for(int i=0;i<keys.length;i++){final String key=keys[i];Switch toggle=new Switch(a);toggle.setText(labels[i]);toggle.setTextColor(ui.text);toggle.setChecked(memory.enabled(owner,key));toggle.setOnCheckedChangeListener((b,on)->{if(current())memory.set(owner,key,on);});body.addView(toggle);}
        ui.note(body,"В модель передаются до "+memory.maxRecall(owner)+" подходящих воспоминаний. Пароли и токены исключаются. Память разделена по аккаунтам и проектам.");
        ui.action(body,"Эпизоды из прежних чатов","Локальная обработка истории без отправки модели",0xFF14846C,()->async("Восстановление памяти",()->{
            JSONArray chats=projects.listConversations(owner);
            for(int i=0;i<chats.length();i++){String source=chats.getJSONObject(i).getString("id");String workspace=projects.projectForChat(owner,source).workspaceId;
                JSONArray messages=projects.messages(owner,source);String user="";
                for(int k=0;k<messages.length();k++){JSONObject e=messages.getJSONObject(k);if("user".equals(e.optString("role")))user=e.optString("body");else if("agent".equals(e.optString("role"))&&!user.isEmpty()){memory.recordEpisode(owner,workspace,source,user,e.optString("body"));user="";}}
            }
        },()->{d[0].dismiss();memory();}));
        ui.action(body,"Объём вспоминания",memory.maxRecall(owner)+" записей · лимит контекста 7000 символов",0xFF435B69,()->form("Память",new String[]{"Число записей 4–16"},new String[]{String.valueOf(memory.maxRecall(owner))},v->{memory.setMaxRecall(owner,Integer.parseInt(v[0]));d[0].dismiss();memory();}));
        ui.action(body,"Очистить память аккаунта","Переписки и файлы Workspace сохранятся",0xFF713CB3,()->new AlertDialog.Builder(a).setTitle("Удалить все воспоминания аккаунта?").setPositiveButton("Очистить",(x,n)->run(()->{memory.clear(owner);d[0].dismiss();memory();})).setNegativeButton("Отмена",null).show());
        ui.action(body,"Добавить воспоминание","Глобально или для текущего Workspace",0xFF14846C,()->form("Запомнить",new String[]{"Содержание","Область: global или project"},new String[]{"",wid().isEmpty()?"global":"project"},v->{memory.add(owner,"project".equals(v[1])?wid():"",chat,v[0],"fact","",false,70);d[0].dismiss();memory();}));
        ui.action(body,"Экспорт памяти","JSON в выбранный файл",0xFF435B69,()->pick(EXPORT_MEMORY,"application/json",true,"GitAgent-memory.json"));
        ui.action(body,"Импорт памяти","JSON · ключи и секреты будут отклонены",0xFF435B69,()->pick(IMPORT_MEMORY,"application/json",false,""));
        EditText search=new EditText(a);search.setHint("Поиск в памяти");search.setTextColor(ui.text);body.addView(search);LinearLayout rows=ui.column();body.addView(rows);
        Runnable render=()->run(()->{rows.removeAllViews();JSONArray list=memory.list(owner,wid());String q=search.getText().toString().toLowerCase(java.util.Locale.ROOT);
            for(int i=0;i<list.length();i++){JSONObject item=list.getJSONObject(i);if(!item.getString("content").toLowerCase(java.util.Locale.ROOT).contains(q))continue;
                ui.action(rows,(item.optBoolean("pinned")?"★ ":"")+item.getString("content"),item.optString("workspace").isEmpty()?"Глобальная память":"Память Workspace",0xFF435B69,()->new AlertDialog.Builder(a).setTitle("Воспоминание")
                    .setItems(new String[]{"Закрепить / открепить","Редактировать","Удалить"},(x,n)->run(()->{
                        if(n==0)memory.togglePin(owner,item.getLong("id"));
                        if(n==2)memory.remove(owner,item.getLong("id"));
                        if(n==1){form("Редактировать память",new String[]{"Содержание"},new String[]{item.getString("content")},v->{memory.add(owner,item.optString("workspace"),chat,v[0],item.optString("kind"),item.optString("key"),item.optBoolean("pinned"),item.optInt("importance",70));d[0].dismiss();memory();});return;}
                        d[0].dismiss();memory();})).show());}});
        search.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int af){}public void afterTextChanged(android.text.Editable e){}public void onTextChanged(CharSequence s,int st,int before,int count){render.run();}});render.run();d[0]=ui.present(body,true);
    });}

    /** Durable conversation archive and export. Search is account-isolated and local-only. */
    public void history(){run(()->{
        LinearLayout body=ui.body("История и память",true);final Dialog[] d={null};
        JSONArray threads=projects.listConversations(owner);
        ui.note(body,"Диалогов: "+threads.length()+" · архивных сообщений: "+projects.archivedCount(owner)+
            ". Старые сообщения сохраняются в локальном SQLite, но автоматически не отправляются модели.");
        ui.action(body,"Экспортировать переписки","Полные сообщения, включая SQLite-архив · JSON",
            0xFF2863B8,()->pick(EXPORT_HISTORY,"application/json",true,"GitAgent-history.json"));
        ui.action(body,"Найти в переписках","Поиск по прошлым и текущим сообщениям",
            0xFF435B69,()->form("Поиск истории",new String[]{"Слово или фраза"},
                new String[]{""},v->run(()->{
                    JSONArray matches=projects.searchHistory(owner,v[0],35);
                    StringBuilder result=new StringBuilder();
                    for(int i=0;i<matches.length();i++){
                        JSONObject item=matches.getJSONObject(i);
                        String snippet=item.optString("body");
                        if(snippet.length()>220)snippet=snippet.substring(0,220)+"…";
                        result.append(item.optString("role")).append(" · ")
                            .append(item.optString("thread")).append("\n")
                            .append(snippet).append("\n\n");
                    }
                    new AlertDialog.Builder(a).setTitle("Найдено: "+matches.length())
                        .setMessage(result.length()==0?"Совпадений нет":result.toString())
                        .setPositiveButton("Закрыть",null).show();
                })));
        ui.section(body,"История чатов");
        for(int i=0;i<threads.length();i++){
            JSONObject chat=threads.getJSONObject(i);
            ui.action(body,chat.optString("title","Диалог"),
                "Полная история · "+chat.optString("id"),0xFF435B69,()->run(()->{
                    JSONArray messages=projects.fullHistory(owner,chat.getString("id"),300);
                    StringBuilder preview=new StringBuilder();
                    for(int n=0;n<messages.length();n++){
                        JSONObject msg=messages.getJSONObject(n);
                        String bodyText=msg.optString("body");
                        if(bodyText.length()>500)bodyText=bodyText.substring(0,500)+"…";
                        preview.append(msg.optString("role")).append(": ")
                            .append(bodyText).append("\n\n");
                    }
                    new AlertDialog.Builder(a).setTitle(chat.optString("title"))
                        .setMessage(preview.length()==0?"Сообщений нет":preview.toString())
                        .setPositiveButton("Закрыть",null).show();
                }));
        }
        d[0]=ui.present(body,true);
    });}

    public void workspace(){run(()->{
        LinearLayout body=ui.body("Workspace",true);final Dialog[] d={null};AgentProjectStore.Project selected=projects.project(owner);
        ui.note(body,"Локальный проект работает без GitHub-репозитория. Каждый диалог выбирает Workspace отдельно.");
        ui.action(body,"Новый Workspace","Папка проекта, файлы и собственная память",0xFF14846C,()->form("Новый проект",new String[]{"Название"},new String[]{""},v->{JSONObject j=workspaces.create(owner,v[0]);projects.save(new AgentProjectStore.Project(owner,j.getString("name"),"","main",j.getString("id")));d[0].dismiss();refresh.run();workspace();}));
        ui.action(body,"Обычный чат — без проекта","Открыть новый диалог без Workspace и репозитория",0xFF435B69,()->run(()->{projects.newConversation(owner);d[0].dismiss();refresh.run();}));
        ui.action(body,"Управление Workspace","Отвязать · Удалить локально · Удалить полностью",0xFF713CB3,()->{d[0].dismiss();manageWorkspace();});
        JSONArray list=workspaces.list(owner);for(int i=0;i<list.length();i++){JSONObject j=list.getJSONObject(i);String id=j.getString("id");
            ui.action(body,(id.equals(selected.workspaceId)?"✓ ":"")+j.getString("name"),j.optString("state"),0xFF435B69,()->run(()->{projects.save(new AgentProjectStore.Project(owner,j.getString("name"),"","main",id));d[0].dismiss();refresh.run();files();}));}
        if(!selected.workspaceId.isEmpty())ui.action(body,"Файлы и настройки текущего проекта",selected.name,0xFF2863B8,()->{d[0].dismiss();files();});
        if(!selected.repository.isEmpty())ui.action(body,"Открепить репозиторий",selected.repository,0xFF713CB3,()->run(()->{projects.save(new AgentProjectStore.Project(owner,selected.name,"",selected.branch,selected.workspaceId));d[0].dismiss();refresh.run();workspace();}));
        d[0]=ui.present(body,true);
    });}
    /** Account-scoped lifecycle controls; never delete a remote GitHub repository. */
    private void manageWorkspace(){run(()->{
        JSONArray list=workspaces.list(owner);
        if(list.length()==0){Toast.makeText(a,"Локальных Workspace нет",Toast.LENGTH_SHORT).show();return;}
        String activeId=wid();
        String[] labels=new String[list.length()];
        for(int i=0;i<list.length();i++){
            JSONObject j=list.getJSONObject(i);
            labels[i]=(activeId.equals(j.optString("id"))?"✓ ":"")+j.optString("name","Workspace");
        }
        new AlertDialog.Builder(a).setTitle("Выберите Workspace").setItems(labels,(d,n)->{
            JSONObject item=list.optJSONObject(n);
            if(item==null)return;
            String id=item.optString("id"),name=item.optString("name","Workspace");
            new AlertDialog.Builder(a).setTitle(name).setItems(new String[]{
                "Отвязать от текущего диалога",
                "Удалить локально · файлы и контрольные точки",
                "Удалить полностью · включая память Workspace"
            },(dialog,choice)->{
                if(!current())return;
                if(choice==0){
                    run(()->{
                        if(!id.equals(wid())){
                            Toast.makeText(a,"В этом диалоге выбран другой Workspace",Toast.LENGTH_LONG).show();
                            return;
                        }
                        projects.save(new AgentProjectStore.Project(owner,"","","main",""));
                        refresh.run();
                        Toast.makeText(a,"Workspace отвязан от диалога",Toast.LENGTH_SHORT).show();
                    });
                    return;
                }
                boolean full=choice==2;
                String explanation="Проект: "+name+"\n"
                    +"Локальные файлы и контрольные точки будут удалены без возможности восстановления.\n"
                    +"Привязки к этому Workspace во всех диалогах будут сброшены.\n"
                    +(full?"Память именно этого Workspace также будет удалена.\n":
                          "Память Workspace и история переписок сохранятся.\n")
                    +"GitHub-репозиторий и внешняя папка SAF не удаляются.";
                new AlertDialog.Builder(a)
                    .setTitle(full?"Полностью удалить Workspace?":"Удалить локальный Workspace?")
                    .setMessage(explanation)
                    .setPositiveButton("Удалить",(confirm,ignored)->async("Удаление Workspace",()->{
                        app.githubble.mobile.agent.AgentRun active=
                            app.githubble.mobile.agent.AgentRunRegistry.current(owner);
                        if(active!=null&&active.active())
                            throw new IllegalStateException("Дождитесь завершения работы агента перед удалением Workspace");
                        // First remove references, so interrupted disk deletion cannot
                        // leave a conversation pinned to a now-partially-deleted folder.
                        projects.detachWorkspaceEverywhere(owner,id);
                        workspaces.deleteLocal(owner,id);
                        if(full)memory.clearWorkspace(owner,id);
                    },()->{
                        refresh.run();
                        workspace();
                    }))
                    .setNegativeButton("Отмена",null).show();
            }).show();
        }).setNegativeButton("Отмена",null).show();
    });}
    public void files(){run(()->{
        String id=wid();if(id.isEmpty()){workspace();return;}JSONObject meta=workspaces.info(owner,id);LinearLayout body=ui.body(meta.optString("name"),true);final Dialog[] d={null};
        ui.action(body,"Контекст и архитектура",meta.optString("state"),0xFF435B69,()->form("Проект",new String[]{"Название","CREATED / PLANNING / ARCHITECTURE_SELECTED / DEVELOPMENT / READY","Инструкции проекта"},new String[]{meta.optString("name"),meta.optString("state"),meta.optString("instructions")},v->{workspaces.configure(owner,id,v[0],v[1],v[2]);AgentProjectStore.Project p=projects.project(owner);projects.save(new AgentProjectStore.Project(owner,v[0],p.repository,p.branch,id));d[0].dismiss();refresh.run();files();}));
        ui.action(body,"Поиск по проекту","Текст во всех файлах Workspace",0xFF435B69,()->form("Поиск",new String[]{"Текст"},new String[]{""},v->async("Поиск",()->{String result=WorkspaceEngine.search(a,workspaces.root(owner,id),id,v[0]);a.runOnUiThread(()->{if(current())new AlertDialog.Builder(a).setTitle("Результаты").setMessage(result).setPositiveButton("ОК",null).show();});},()->{})));
        ui.action(body,"Карта и индекс символов","Структура проекта, функции и определения",0xFF435B69,()->form("Символы проекта",new String[]{"Имя / часть имени"},new String[]{""},v->async("Индекс",()->{String result=WorkspaceEngine.index(a,workspaces.root(owner,id),id,v[0]);a.runOnUiThread(()->{if(current())new AlertDialog.Builder(a).setTitle("Индекс проекта").setMessage(result).setPositiveButton("ОК",null).show();});},()->{})));
        ui.action(body,"Контрольные точки","Снимки проекта и восстановление",0xFF14846C,()->run(()->{
            JSONArray points=WorkspaceEngine.checkpoints(a,workspaces.root(owner,id),id);String[] labels=new String[points.length()+1];labels[0]="Создать снимок";for(int i=0;i<points.length();i++)labels[i+1]=points.getJSONObject(i).optString("label");
            new AlertDialog.Builder(a).setTitle("Контрольные точки").setItems(labels,(x,n)->{
                if(n==0)async("Снимок",()->WorkspaceEngine.checkpoint(a,workspaces.root(owner,id),id,"Снимок "+System.currentTimeMillis()),()->Toast.makeText(a,"Снимок создан",Toast.LENGTH_SHORT).show());
                else new AlertDialog.Builder(a).setTitle("Восстановить снимок?").setMessage("Текущие файлы проекта будут заменены.").setPositiveButton("Восстановить",(y,z)->async("Восстановление",()->WorkspaceEngine.restore(a,workspaces.root(owner,id),id,points.getJSONObject(n-1).getString("id")),()->{d[0].dismiss();files();})).setNegativeButton("Отмена",null).show();
            }).show();
        }));
        ui.action(body,"Операции с файлами","Создать каталог · переместить · удалить · переименовать символ",0xFF435B69,()->form("Операция Workspace",new String[]{"mkdir / move / delete / rename_symbol","Путь или имя символа","Новое имя / путь (для move/rename_symbol)"},new String[]{"mkdir","",""},v->new AlertDialog.Builder(a).setTitle("Выполнить операцию?").setMessage(v[0]+": "+v[1]+" → "+v[2]).setPositiveButton("Выполнить",(x,n)->async("Workspace",()->{
            if(v[1].endsWith(".workspace.json")||v[2].endsWith(".workspace.json"))throw new IOException("Метаданные Workspace защищены");
            WorkspaceEngine.operation(a,workspaces.root(owner,id),id,v[0],v[1],v[2]);
        },()->{d[0].dismiss();files();})).setNegativeButton("Отмена",null).show()));
        ui.action(body,"Связать папку устройства (SAF)",meta.optString("storage_uri").isEmpty()?"Android выберет папку с доступом к файлам":"Связана живая папка",0xFF2863B8,()->{pendingWorkspace=id;pick(LINK_FOLDER,"",false,"");});
        ui.action(body,"Создать текстовый файл","Путь относительно Workspace",0xFF14846C,()->form("Файл",new String[]{"Путь","Содержимое"},new String[]{"",""},v->{workspaces.saveFile(owner,id,v[0],v[1]);d[0].dismiss();files();}));
        ui.action(body,"Импорт ZIP","Заменяет одноимённые файлы · до 64 МБ после распаковки",0xFF2863B8,()->{pendingWorkspace=id;pick(IMPORT_ZIP,"application/zip",false,"");});
        ui.action(body,"Экспорт ZIP","Файлы текущего Workspace",0xFF2863B8,()->{pendingWorkspace=id;pick(EXPORT_ZIP,"application/zip",true,"Workspace.zip");});
        JSONArray list=workspaces.files(owner,id);for(int i=0;i<list.length();i++){JSONObject f=list.getJSONObject(i);String path=f.getString("path");ui.action(body,path,f.optLong("size")+" байт",0xFF435B69,()->run(()->form(path,new String[]{"Содержимое"},new String[]{workspaces.readFile(owner,id,path)},v->{workspaces.saveFile(owner,id,path,v[0]);d[0].dismiss();files();})));}
        d[0]=ui.present(body,true);
    });}
    private void pick(int request,String type,boolean create,String name){if(!current())return;Intent i=request==LINK_FOLDER?new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE):new Intent(create?Intent.ACTION_CREATE_DOCUMENT:Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(type);if(create)i.putExtra(Intent.EXTRA_TITLE,name);a.startActivityForResult(i,request);}
    public boolean result(int request,int result,Intent data){
        if(request<IMPORT_XRAY||request>EXPORT_HISTORY)return false;if(result!=Activity.RESULT_OK||data==null||data.getData()==null||!current())return true;
        Uri uri=data.getData();String workspace=pendingWorkspace;
        async("Импорт / экспорт",()->{
            if(request==IMPORT_XRAY){String raw=read(uri,512*1024);AiNetwork.importProfile("Загруженный профиль",raw);}
            else if(request==IMPORT_ZIP){WorkspaceEngine.importUri(a,workspaces.root(owner,workspace),workspace,uri);}
            else if(request==EXPORT_ZIP){try(OutputStream out=a.getContentResolver().openOutputStream(uri)){if(out==null)throw new IOException("Файл недоступен");workspaces.exportZip(owner,workspace,out);}}
            else if(request==EXPORT_MEMORY){try(OutputStream out=a.getContentResolver().openOutputStream(uri)){if(out==null)throw new IOException("Файл недоступен");out.write(memory.list(owner,wid()).toString(2).getBytes(StandardCharsets.UTF_8));}}
            else if(request==EXPORT_HISTORY){try(OutputStream out=a.getContentResolver().openOutputStream(uri)){if(out==null)throw new IOException("Файл недоступен");out.write(projects.exportHistory(owner).toString(2).getBytes(StandardCharsets.UTF_8));}}
            else if(request==LINK_FOLDER){
                a.getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                File metaFile=new File(workspaces.root(owner,workspace),".workspace.json");JSONObject meta=workspaces.info(owner,workspace);meta.put("storage_uri",uri.toString());
                try(OutputStream out=new FileOutputStream(metaFile)){out.write(meta.toString().getBytes(StandardCharsets.UTF_8));}
            }
            else {JSONArray items=new JSONArray(read(uri,2*1024*1024));for(int i=0;i<Math.min(1000,items.length());i++){JSONObject j=items.getJSONObject(i);String target=j.optString("workspace").isEmpty()?"":wid();if(!j.optString("workspace").isEmpty()&&target.isEmpty())throw new IOException("Для импорта проектной памяти выберите Workspace");memory.add(owner,target,chat,j.getString("content"),j.optString("kind","fact"),j.optString("key"),j.optBoolean("pinned"),j.optInt("importance",70));}}
        },()->{Toast.makeText(a,"Готово",Toast.LENGTH_SHORT).show();refresh.run();});return true;
    }
    private String read(Uri uri,int max)throws Exception{try(InputStream in=a.getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){if(in==null)throw new IOException("Файл недоступен");byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){if(out.size()+n>max)throw new IOException("Файл слишком большой");out.write(b,0,n);}return out.toString("UTF-8");}}
}

