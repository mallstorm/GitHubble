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
    public static final int IMPORT_XRAY=2750,IMPORT_ZIP=2751,EXPORT_ZIP=2752,IMPORT_MEMORY=2753,EXPORT_MEMORY=2754;
    private final Activity a;private final String owner,chat;private final Supplier<String> active;private final AgentProjectStore projects;
    private final MemoryHub memory;private final WorkspaceHub workspaces;private final ExecutorService io;private final GitAgentUi ui;private final Runnable refresh;
    private String pendingWorkspace="";
    public AgentFeatureScreens(Activity a,String owner,Supplier<String> active,AgentProjectStore projects,MemoryHub memory,ExecutorService io,int bg,Runnable refresh){
        this.a=a;this.owner=owner;this.active=active;this.projects=projects;this.memory=memory;this.io=io;this.ui=new GitAgentUi(a,bg);this.refresh=refresh;
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
        ui.note(body,"В модель передаются до 8 подходящих воспоминаний. Пароли и токены исключаются. Память разделена по аккаунтам и проектам.");
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
    public void workspace(){run(()->{
        LinearLayout body=ui.body("Workspace",true);final Dialog[] d={null};AgentProjectStore.Project selected=projects.project(owner);
        ui.note(body,"Локальный проект работает без GitHub-репозитория. Каждый диалог выбирает Workspace отдельно.");
        ui.action(body,"Новый Workspace","Папка проекта, файлы и собственная память",0xFF14846C,()->form("Новый проект",new String[]{"Название"},new String[]{""},v->{JSONObject j=workspaces.create(owner,v[0]);projects.save(new AgentProjectStore.Project(owner,j.getString("name"),"","main",j.getString("id")));d[0].dismiss();refresh.run();workspace();}));
        ui.action(body,"Обычный чат — без проекта","Открепить Workspace и репозиторий от этого диалога",0xFF435B69,()->run(()->{projects.save(new AgentProjectStore.Project(owner,"","","main",""));d[0].dismiss();refresh.run();}));
        JSONArray list=workspaces.list(owner);for(int i=0;i<list.length();i++){JSONObject j=list.getJSONObject(i);String id=j.getString("id");
            ui.action(body,(id.equals(selected.workspaceId)?"✓ ":"")+j.getString("name"),j.optString("state"),0xFF435B69,()->run(()->{projects.save(new AgentProjectStore.Project(owner,j.getString("name"),"","main",id));d[0].dismiss();refresh.run();files();}));}
        if(!selected.workspaceId.isEmpty())ui.action(body,"Файлы и настройки текущего проекта",selected.name,0xFF2863B8,()->{d[0].dismiss();files();});
        if(!selected.repository.isEmpty())ui.action(body,"Открепить репозиторий",selected.repository,0xFF713CB3,()->run(()->{projects.save(new AgentProjectStore.Project(owner,selected.name,"",selected.branch,selected.workspaceId));d[0].dismiss();refresh.run();workspace();}));
        d[0]=ui.present(body,true);
    });}
    public void files(){run(()->{
        String id=wid();if(id.isEmpty()){workspace();return;}JSONObject meta=workspaces.info(owner,id);LinearLayout body=ui.body(meta.optString("name"),true);final Dialog[] d={null};
        ui.action(body,"Контекст и архитектура",meta.optString("state"),0xFF435B69,()->form("Проект",new String[]{"Название","CREATED / PLANNING / ARCHITECTURE_SELECTED / DEVELOPMENT / READY","Инструкции проекта"},new String[]{meta.optString("name"),meta.optString("state"),meta.optString("instructions")},v->{workspaces.configure(owner,id,v[0],v[1],v[2]);AgentProjectStore.Project p=projects.project(owner);projects.save(new AgentProjectStore.Project(owner,v[0],p.repository,p.branch,id));d[0].dismiss();refresh.run();files();}));
        ui.action(body,"Создать текстовый файл","Путь относительно Workspace",0xFF14846C,()->form("Файл",new String[]{"Путь","Содержимое"},new String[]{"",""},v->{workspaces.saveFile(owner,id,v[0],v[1]);d[0].dismiss();files();}));
        ui.action(body,"Импорт ZIP","Заменяет одноимённые файлы · до 64 МБ после распаковки",0xFF2863B8,()->{pendingWorkspace=id;pick(IMPORT_ZIP,"application/zip",false,"");});
        ui.action(body,"Экспорт ZIP","Файлы текущего Workspace",0xFF2863B8,()->{pendingWorkspace=id;pick(EXPORT_ZIP,"application/zip",true,"Workspace.zip");});
        JSONArray list=workspaces.files(owner,id);for(int i=0;i<list.length();i++){JSONObject f=list.getJSONObject(i);String path=f.getString("path");ui.action(body,path,f.optLong("size")+" байт",0xFF435B69,()->run(()->form(path,new String[]{"Содержимое"},new String[]{workspaces.readFile(owner,id,path)},v->{workspaces.saveFile(owner,id,path,v[0]);d[0].dismiss();files();})));}
        d[0]=ui.present(body,true);
    });}
    private void pick(int request,String type,boolean create,String name){if(!current())return;Intent i=new Intent(create?Intent.ACTION_CREATE_DOCUMENT:Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(type);if(create)i.putExtra(Intent.EXTRA_TITLE,name);a.startActivityForResult(i,request);}
    public boolean result(int request,int result,Intent data){
        if(request<IMPORT_XRAY||request>EXPORT_MEMORY)return false;if(result!=Activity.RESULT_OK||data==null||data.getData()==null||!current())return true;
        Uri uri=data.getData();String workspace=pendingWorkspace;
        async("Импорт / экспорт",()->{
            if(request==IMPORT_XRAY){String raw=read(uri,512*1024);AiNetwork.importProfile("Загруженный профиль",raw);}
            else if(request==IMPORT_ZIP){try(InputStream in=a.getContentResolver().openInputStream(uri)){if(in==null)throw new IOException("Файл недоступен");workspaces.importZip(owner,workspace,in);}}
            else if(request==EXPORT_ZIP){try(OutputStream out=a.getContentResolver().openOutputStream(uri)){if(out==null)throw new IOException("Файл недоступен");workspaces.exportZip(owner,workspace,out);}}
            else if(request==EXPORT_MEMORY){try(OutputStream out=a.getContentResolver().openOutputStream(uri)){if(out==null)throw new IOException("Файл недоступен");out.write(memory.list(owner,wid()).toString(2).getBytes(StandardCharsets.UTF_8));}}
            else {JSONArray items=new JSONArray(read(uri,2*1024*1024));for(int i=0;i<Math.min(1000,items.length());i++){JSONObject j=items.getJSONObject(i);memory.add(owner,"",chat,j.getString("content"),j.optString("kind","fact"),j.optString("key"),j.optBoolean("pinned"),j.optInt("importance",70));}}
        },()->{Toast.makeText(a,"Готово",Toast.LENGTH_SHORT).show();refresh.run();});return true;
    }
    private String read(Uri uri,int max)throws Exception{try(InputStream in=a.getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){if(in==null)throw new IOException("Файл недоступен");byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){if(out.size()+n>max)throw new IOException("Файл слишком большой");out.write(b,0,n);}return out.toString("UTF-8");}}
}
