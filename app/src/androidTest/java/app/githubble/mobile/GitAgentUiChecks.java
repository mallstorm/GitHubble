package app.githubble.mobile;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.githubble.mobile.agent.AgentProjectStore;
import app.githubble.mobile.ui.AgentChatScreen;
import org.json.JSONArray;
import java.io.File;
import java.io.FileOutputStream;

/** Real API-33 layout/render checks; no private account or provider credentials. */
final class GitAgentUiChecks {
    static void run(Instrumentation host) throws Exception {
        Context context=host.getTargetContext();
        UnifiedAgentUiChecks.run(context);
        AgentProjectStore projects=new AgentProjectStore(context);
        String account="ui-regression-"+System.nanoTime(), other=account+"-other";
        if(projects.listConversations(account).length()!=0)throw new AssertionError("Fresh account contains a phantom conversation");
        String first=projects.activeConversation(account);
        if(!first.equals(projects.newConversation(account))||projects.listConversations(account).length()!=0)
            throw new AssertionError("Repeated new-chat taps created empty history");
        projects.appendToConversation(account,first,"user","Первая переписка");
        String second=projects.newConversation(account);
        projects.appendToConversation(account,second,"user","Вторая переписка");
        projects.switchConversation(account,first);
        if(projects.listConversations(account).length()!=2)throw new AssertionError("A started conversation was not saved");
        if(projects.messages(account).length()!=1 || !"Первая переписка".equals(projects.messages(account).getJSONObject(0).getString("body")))
            throw new AssertionError("Conversation history was replaced");
        if(projects.messages(other).length()!=0)throw new AssertionError("Cross-account messages");
        String draftAccount=account+"-drafts";
        String saved=projects.activeConversation(draftAccount);
        projects.appendToConversation(draftAccount,saved,"user","Saved message");
        String pending=projects.newConversation(draftAccount);
        projects.switchConversation(draftAccount,saved);
        if(!pending.equals(projects.newConversation(draftAccount)))throw new AssertionError("Unsent draft was replaced on returning to new chat");
        projects.deleteConversation(draftAccount,saved);
        if(!pending.equals(projects.activeConversation(draftAccount))||projects.listConversations(draftAccount).length()!=0)
            throw new AssertionError("Deleting the last saved thread lost the current draft");
        app.githubble.mobile.security.SecureStore vault=new app.githubble.mobile.security.SecureStore(context);
        app.githubble.mobile.agent.OmniChatConnectionStore connections=new app.githubble.mobile.agent.OmniChatConnectionStore(context,vault);
        connections.save(account,"openai","test-model-a","test-credential-never-sent");
        connections.cacheModels(account,"openai",java.util.Arrays.asList("test-model-a","test-model-b"));
        connections=new app.githubble.mobile.agent.OmniChatConnectionStore(context,vault);
        if(!"openai".equals(connections.selectedProvider(account)) || !connections.isConfigured(account,"openai")
                || connections.models(account,"openai").size()!=2 || connections.isConfigured(other,"openai"))
            throw new AssertionError("Connection/catalog persistence or account isolation failed");
        if(!app.githubble.mobile.network.NativeProviderCatalog.keyless("lmstudio"))throw new AssertionError("Local keyless API requires a secret");
        if(app.githubble.mobile.network.NativeProviderCatalog.names().size()<40)throw new AssertionError("Full provider registry missing");
        connections.disconnect(account,"openai");
        if(connections.hasKey(account,"openai"))throw new AssertionError("Disconnected key retained");
        // Regression: choosing an AI model when GitHub is not signed in.
        connections.save("guest","openai","offline-test-model","test-key-never-sent");
        if(!"openai".equals(connections.selectedProvider("guest"))
            ||!"offline-test-model".equals(connections.model("guest","openai"))
            ||!connections.isConfigured("guest","openai")
            ||connections.isConfigured(account,"openai"))
            throw new AssertionError("Guest AI selection failed or crossed account scopes");
        connections=new app.githubble.mobile.agent.OmniChatConnectionStore(context,vault);
        if(!"openai".equals(connections.selectedProvider("guest")))
            throw new AssertionError("Guest model selection not persisted");
        connections.disconnect("guest","openai");
        org.json.JSONObject oauth=new org.json.JSONObject().put("access_token","local-test-access")
            .put("refresh_token","local-test-refresh").put("expires_at",System.currentTimeMillis()+3600000);
        for(String subscription:new String[]{app.githubble.mobile.agent.OAuthSubscriptions.KIMI,
                app.githubble.mobile.agent.OAuthSubscriptions.GROK,"chatgpt-plan","proxy-chatgpt","proxy-gemini","proxy-claude","proxy-grok","openrouter-account","kilo-account"}){
        connections.saveSubscription(account,subscription,oauth,java.util.Arrays.asList("verified-test-model"));
        connections=new app.githubble.mobile.agent.OmniChatConnectionStore(context,vault);
        String originalSession=connections.credential(account,subscription);
        if(!account.equals(new org.json.JSONObject(originalSession).getString("owner")) || connections.isConfigured(other,subscription))
            throw new AssertionError("OAuth account/session isolation failed");
        org.json.JSONObject rotated=new org.json.JSONObject(originalSession).put("refresh_token","rotated-local-test");
        connections.replaceSubscription(account,subscription,originalSession,rotated);
        String refreshed=connections.credential(account,subscription);
        connections.disconnect(account,subscription);
        try{connections.replaceSubscription(account,subscription,refreshed,rotated);throw new AssertionError("Disconnected OAuth session was resurrected");}
        catch(IllegalStateException expected){}
        }
        String scoped=account+"-project-scope";
        String scopedChat=projects.activeConversation(scoped);
        projects.appendToConversation(scoped,scopedChat,"user","Project A");
        projects.save(new AgentProjectStore.Project(scoped,"Project A","owner/repository","main",""));
        String independent=projects.newConversation(scoped);
        if(!projects.project(scoped).repository.isEmpty()||!projects.project(scoped).workspaceId.isEmpty())
            throw new AssertionError("New chat inherited a repository/workspace");
        projects.switchConversation(scoped,scopedChat);
        if(!"owner/repository".equals(projects.project(scoped).repository))throw new AssertionError("Explicit repository binding was lost");
        app.githubble.mobile.memory.MemoryHub memory=new app.githubble.mobile.memory.MemoryHub(context);
        memory.add(account,"","chat-A","Предпочитаю компактный интерфейс","preference","density",true,90);
        memory.add(account,"workspace-A","chat-A","Проект использует Kotlin","project","language",false,80);
        if(memory.list(other,"workspace-A").length()!=0||memory.list(account,"workspace-B").length()!=1)
            throw new AssertionError("Memory leaked across accounts or workspaces");
        if(!memory.recall(account,"workspace-A","chat-B","интерфейс").contains("компактный"))throw new AssertionError("Memory recall not wired");
        try{memory.add(account,"","chat-A","api_key = sk-1234567890abcdefghijklmnop","fact","secret",false,70);throw new AssertionError("Secret entered memory");}catch(IllegalArgumentException expected){}
        memory.clear(account);memory.close();
        // Preserve old turns in the SQLite archive instead of silently truncating them.
        String archiveOwner=account+"-archive";
        String archiveThread=projects.activeConversation(archiveOwner);
        for(int i=0;i<86;i++){
            projects.appendToConversation(archiveOwner,archiveThread,"user",
                i==12?"Archive unique search marker":"Archive turn "+i);
        }
        if(projects.messages(archiveOwner).length()!=80
                || projects.fullHistory(archiveOwner,archiveThread,10000).length()!=86
                || projects.archivedCount(archiveOwner)!=6)
            throw new AssertionError("History overflow archive lost messages");
        if(projects.searchHistory(archiveOwner,"unique search marker",20).length()!=1)
            throw new AssertionError("Archived chat search lost a historical message");
        if(!app.githubble.mobile.agent.OmniChatChatClient.supportsVision("gemini")
                ||app.githubble.mobile.agent.OmniChatChatClient.supportsVision("deepseek"))
            throw new AssertionError("Vision capability gate is incorrect");
        AgentProjectStore restored=new AgentProjectStore(context);
        if(!first.equals(restored.activeConversation(account)))throw new AssertionError("Active thread not restored");
        Intent intent=new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity activity=host.startActivitySync(intent);
        File directory=new File(context.getExternalFilesDir(null),"ui-screenshots");directory.mkdirs();
        try {
            // Execute the actual local tool broker, not a mocked filename renderer.
            String toolOwner=account+"-tools",toolThread=projects.activeConversation(toolOwner);
            app.githubble.mobile.agent.WorkspaceHub toolWorkspace=new app.githubble.mobile.agent.WorkspaceHub(context);
            java.util.concurrent.atomic.AtomicInteger permission=new java.util.concurrent.atomic.AtomicInteger(2);
            app.githubble.mobile.agent.NativeAgentToolHost localTools=new app.githubble.mobile.agent.NativeAgentToolHost(
                activity,null,null,toolWorkspace,projects,toolOwner,toolThread,projects.project(toolOwner),
                false,true,false,permission::get,()->{},()->{});
            localTools.execute("create_workspace",new org.json.JSONObject().put("name","Agent tool regression"));
            String toolId=projects.project(toolOwner).workspaceId;
            if(toolId.isEmpty())throw new AssertionError("Model tool did not create a Workspace");
            localTools.execute("write_files",new org.json.JSONObject().put("summary","Create source")
                .put("files",new JSONArray().put(new org.json.JSONObject().put("path","src/file.js").put("content","one\ntwo\n"))));
            localTools.execute("write_files",new org.json.JSONObject().put("summary","Edit source")
                .put("files",new JSONArray().put(new org.json.JSONObject().put("path","src/file.js").put("expected_sha",new org.json.JSONObject(localTools.execute("read_file",new org.json.JSONObject().put("path","src/file.js"))).getString("sha")).put("content","one\nthree\nfour\n"))));
            if(!"one\nthree\nfour\n".equals(new app.githubble.mobile.agent.TaskGitService(new File(activity.getFilesDir(),"task-changes"),projects,toolWorkspace,toolOwner,toolThread,null,true,()->{}).tools().snapshot("src/file.js").text))
                throw new AssertionError("Native write tool did not apply task overlay");
            if(toolWorkspace.file(toolOwner,toolId,"src/file.js").exists())throw new AssertionError("Task edit modified shared Workspace");
            JSONArray toolEvents=projects.messages(toolOwner,toolThread);
            org.json.JSONObject changed=null;
            for(int i=0;i<toolEvents.length();i++){
                JSONArray ops=toolEvents.getJSONObject(i).optJSONArray("operations");
                if(ops!=null&&ops.length()>0)changed=ops.getJSONObject(0);
            }
            if(changed==null||changed.getInt("added")!=2||changed.getInt("removed")!=1)
                throw new AssertionError("Host file counts differ from actual diff");
            permission.set(0);
            if(localTools.handles("write_files"))throw new AssertionError("Read-only permission still offers write tools");
            app.githubble.mobile.agent.NativeAgentToolHost planTools=new app.githubble.mobile.agent.NativeAgentToolHost(
                activity,null,null,toolWorkspace,projects,toolOwner,toolThread,projects.project(toolOwner),
                true,true,true,()->3,()->{},()->{});
            if(planTools.handles("write_files")||planTools.handles("create_repository")||!planTools.handles("read_file"))
                throw new AssertionError("Planning mode does not enforce read-only capabilities");
            JSONArray media=new JSONArray().put(new org.json.JSONObject().put("name","fixture.png").put("mime","image/png").put("path","private-fixture").put("origin","assistant_generated").put("replayToModel",false));
            projects.appendAttachments(toolOwner,toolThread,"agent","Generated image fixture",media);
            // Move structured operations to SQLite and verify they survive archival.
            for(int i=0;i<85;i++)projects.appendToConversation(toolOwner,toolThread,"user","Archive tool event "+i);
            JSONArray archivedTools=projects.fullHistory(toolOwner,toolThread,10000);
            boolean restoredOperation=false;
            for(int i=0;i<archivedTools.length();i++){
                JSONArray ops=archivedTools.getJSONObject(i).optJSONArray("operations");
                if(ops!=null&&ops.length()>0&&ops.getJSONObject(0).optInt("added")==2)restoredOperation=true;
            }
            if(!restoredOperation)throw new AssertionError("Archive lost structured file operations");
            boolean restoredMedia=false;for(int i=0;i<archivedTools.length();i++)if("Generated image fixture".equals(archivedTools.getJSONObject(i).optString("body")))restoredMedia=archivedTools.getJSONObject(i).getJSONArray("attachments").length()==1;
            if(!restoredMedia)throw new AssertionError("Archive lost generated media metadata");
            app.githubble.mobile.agent.NativePluginRegistry plugins=new app.githubble.mobile.agent.NativePluginRegistry(context,toolOwner);
            try{plugins.save("","Unsafe","http://127.0.0.1/tool","POST","","2025-11-25");throw new AssertionError("Unsafe plugin URL accepted");}catch(IllegalArgumentException expected){}
            plugins.save("","Fixture","https://example.org/tool","POST","fixture-plugin-secret","2025-11-25");
            String pluginId=plugins.list().getJSONObject(0).getString("id");
            if(new app.githubble.mobile.agent.NativePluginRegistry(context,other).list().length()!=0)throw new AssertionError("Plugins cross accounts");
            if(plugins.catalog().contains("fixture-plugin-secret"))throw new AssertionError("Plugin secret leaked into model catalog");
            plugins.toggle(pluginId);if(plugins.operation("ext_"+pluginId)!=null)throw new AssertionError("Disabled plugin remains callable");plugins.remove(pluginId);
            app.githubble.mobile.agent.WorkspaceHub workspaceHub=new app.githubble.mobile.agent.WorkspaceHub(context);
            String smokeId=workspaceHub.create(account,"Local Workspace smoke").getString("id");
            workspaceHub.saveFile(account,smokeId,"src/Smoke.kt","class Smoke { fun original() = 1 }\n");
            java.util.concurrent.atomic.AtomicInteger toolCalls=new java.util.concurrent.atomic.AtomicInteger();
            String toolAnswer=app.githubble.mobile.agent.AgentExecutionEngine.analyze(query->{
                if(toolCalls.incrementAndGet()==1)
                    return "{\"tools\":[{\"name\":\"list_files\",\"args\":{}}]}";
                return "{\"answer\":\"Workspace files inspected\"}";
            },workspaceHub,account,smokeId,"Check workspace files","Local project context");
            if(!"Workspace files inspected".equals(toolAnswer)||toolCalls.get()!=2)
                throw new AssertionError("Bounded agent tool loop failed");
            if(!app.githubble.mobile.agent.WorkspaceEngine.search(context,workspaceHub.root(account,smokeId),smokeId,"original").contains("src/Smoke.kt"))throw new AssertionError("Workspace content search failed");
            String checkpoint=app.githubble.mobile.agent.WorkspaceEngine.checkpoint(context,workspaceHub.root(account,smokeId),smokeId,"Before edit");
            workspaceHub.saveFile(account,smokeId,"src/Smoke.kt","class Smoke { fun changed() = 2 }\n");
            app.githubble.mobile.agent.WorkspaceEngine.restore(context,workspaceHub.root(account,smokeId),smokeId,checkpoint);
            if(!workspaceHub.readFile(account,smokeId,"src/Smoke.kt").contains("original"))throw new AssertionError("Workspace checkpoint restoration failed");
            app.githubble.mobile.network.AiNetwork.importProfile("Self-test runtime","{\"outbounds\":[{\"protocol\":\"freedom\"}]}");
            org.json.JSONArray xrayProfiles=new org.json.JSONObject(app.githubble.mobile.network.AiNetwork.profiles()).getJSONArray("profiles");
            String testProfile="";for(int i=0;i<xrayProfiles.length();i++)if("Self-test runtime".equals(xrayProfiles.getJSONObject(i).optString("name")))testProfile=xrayProfiles.getJSONObject(i).getString("id");
            try{app.githubble.mobile.network.AiNetwork.enable(testProfile);
                if(!new org.json.JSONObject(app.githubble.mobile.network.AiNetwork.profiles()).getBoolean("enabled"))throw new AssertionError("Native Xray did not start");
            }finally{app.githubble.mobile.network.AiNetwork.disable();app.githubble.mobile.network.AiNetwork.removeProfile(testProfile);}
            org.commonmark.node.Node document=app.githubble.mobile.ui.AgentMarkdown.parse("# Заголовок\n\n**приватный**\n\n```bash\nprintf ok\n```");
            if(!(document.getFirstChild() instanceof org.commonmark.node.Heading))throw new AssertionError("Markdown heading missing");
            for(boolean dark:new boolean[]{false,true}){
                onMain(host,()->{
                    LinearLayout root=new LinearLayout(activity);root.setOrientation(1);activity.setContentView(root);
                    AgentChatScreen.Actions actions=(AgentChatScreen.Actions)java.lang.reflect.Proxy.newProxyInstance(
                        AgentChatScreen.Actions.class.getClassLoader(),new Class[]{AgentChatScreen.Actions.class},
                        (proxy,method,args)->null);
                    AgentChatScreen screen=new AgentChatScreen(activity,root,dark?0xFF182226:0xFFF9FAF7,
                        dark?0xFF223135:0xFFFFFFFF,dark?0xFF263B33:0xFFEFF7E9,
                        dark?0xFFF2F7F4:0xFF1C221F,dark?0xFFAEBEB7:0xFF626F67,
                        dark?0xFF8CD74F:0xFF4EA839,dark?0xFF3A4C48:0xFFE0E7DE,actions);
                    screen.render("test",projects.project(account),new JSONArray(),0,false,"","",false,
                        "Новый диалог",false,"Сохранённый черновик");
                    if(find(root,"Hubble Agent")==null||find(root,"Спросить Hubble Agent")==null)
                        throw new AssertionError("Missing Hubble Agent branding/composer");
                    if(find(root,"OmniChat")!=null)throw new AssertionError("Old branding visible");
                    if(find(root,"План")!=null)throw new AssertionError("Removed Plan control is visible");
                    EditText input=(EditText)find(root,"Спросить Hubble Agent");
                    if(!"Сохранённый черновик".equals(input.getText().toString()))throw new AssertionError("Draft missing");

                });
                host.waitForIdleSync();
                onMain(host,()->{
                    View root=activity.getWindow().getDecorView();
                    Bitmap image=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);
                    root.draw(new android.graphics.Canvas(image));
                    try(FileOutputStream output=new FileOutputStream(new File(directory,dark?"chat-dark.png":"chat-light.png"))){
                        image.compress(Bitmap.CompressFormat.PNG,100,output);
                    }catch(Exception error){throw new RuntimeException(error);}finally{image.recycle();}
                });
            }
            onMain(host,()->{
                try{
                    LinearLayout root=new LinearLayout(activity);activity.setContentView(root);
                    AgentChatScreen.Actions actions=(AgentChatScreen.Actions)java.lang.reflect.Proxy.newProxyInstance(
                        AgentChatScreen.Actions.class.getClassLoader(),new Class[]{AgentChatScreen.Actions.class},(proxy,method,args)->null);
                    JSONArray ops=new JSONArray().put(new org.json.JSONObject().put("path","src/file.js").put("kind","edit").put("added",130).put("removed",80))
                        .put(new org.json.JSONObject().put("path","src/new.js").put("kind","create").put("added",160).put("removed",0));
                    JSONArray messages=new JSONArray().put(new org.json.JSONObject().put("role","system").put("body","Файлы записаны").put("operations",ops));
                    new AgentChatScreen(activity,root,0xFF182226,0xFF223135,0xFF263B33,0xFFF2F7F4,0xFFAEBEB7,0xFF8CD74F,0xFF3A4C48,actions)
                        .render("test",projects.project(account),messages,0,true,"Test","Test model",true,"Проект",false,"");
                    if(find(root,"file.js")==null||find(root," +130")==null||find(root," −80")==null)
                        throw new AssertionError("Structured file diff row missing");
                }catch(Exception e){throw new RuntimeException(e);}
            });
            host.waitForIdleSync();screenshot(host,directory,"file-change-events.png");
            // Exercise the integrated app surface and the actual modal windows too.
            onMain(host,()->{
                try {
                    context.getSharedPreferences("githubble",Context.MODE_PRIVATE).edit().putString("theme","light").commit();
                    java.lang.reflect.Method theme=MainActivity.class.getDeclaredMethod("applyTheme");theme.setAccessible(true);theme.invoke(activity);
                    java.lang.reflect.Method chat=MainActivity.class.getDeclaredMethod("showAgentChat");chat.setAccessible(true);chat.invoke(activity);
                }catch(Exception error){throw new RuntimeException(error);}
            });
            host.waitForIdleSync();Thread.sleep(300);screenshot(host,directory,"integrated-chat.png");
            // Settings must expose all AI controls in one native category, directly
            // below Data and security and above About.
            onMain(host,()->{
                try{
                    java.lang.reflect.Method settings=MainActivity.class.getDeclaredMethod("showSettings");
                    settings.setAccessible(true);settings.invoke(activity);
                    View root=activity.getWindow().getDecorView();
                    View data=find(root,"Данные и безопасность");
                    View agent=find(root,"Hubble Agent и Workspace");
                    View security=find(root,"О приложении");
                    if(data==null||agent==null||security==null)
                        throw new AssertionError("Central AI settings category missing");
                    // Check actual child order instead of screen coordinates:
                    // the first layout pass may not have run inside runOnMainSync.
                    ViewGroup dataRow=(ViewGroup)((View)data.getParent()).getParent();
                    ViewGroup agentRow=(ViewGroup)((View)agent.getParent()).getParent();
                    ViewGroup securityRow=(ViewGroup)((View)security.getParent()).getParent();
                    if(dataRow.getParent()!=agentRow.getParent()
                            || dataRow.getParent()!=securityRow.getParent())
                        throw new AssertionError("Settings cards are not in the same list");
                    ViewGroup list=(ViewGroup)dataRow.getParent();
                    if(!(list.indexOfChild(dataRow)<list.indexOfChild(agentRow)
                            && list.indexOfChild(agentRow)<list.indexOfChild(securityRow)))
                        throw new AssertionError("Hubble Agent settings category has wrong dashboard order");
                    java.lang.reflect.Method open=MainActivity.class.getDeclaredMethod("showSettingsGitAgentWorkspace");
                    open.setAccessible(true);open.invoke(activity);
                    root=activity.getWindow().getDecorView();
                    for(String label:new String[]{"Режим и исполнитель","Подключения API",
                            "Аккаунты и подписки","Рабочие пространства","Память Hubble Agent",
                            "DNS и Smart Routing","Xray / VLESS","Диктовка"}){
                        if(find(root,label)==null)throw new AssertionError("Missing AI settings row: "+label);
                    }
                }catch(Exception error){throw new RuntimeException(error);}
            });
            host.waitForIdleSync();screenshot(host,directory,"git-agent-workspace-settings.png");
            onMain(host,()->{
                try{
                    java.lang.reflect.Method chat=MainActivity.class.getDeclaredMethod("showAgentChat");
                    chat.setAccessible(true);chat.invoke(activity);
                }catch(Exception error){throw new RuntimeException(error);}
            });
            host.waitForIdleSync();

            for(String methodName:new String[]{"agentChooseComposerModel","agentShowAttachments","agentShowAgentSettings","agentShowConversations"}){
                onMain(host,()->{
                    try{
                        java.lang.reflect.Method method=MainActivity.class.getDeclaredMethod(methodName,String.class);
                        method.setAccessible(true);method.invoke(activity,"guest");
                    }catch(Exception error){throw new RuntimeException(error);}
                });
                host.waitForIdleSync();Thread.sleep(300);screenshot(host,directory,methodName+".png");
                dismissActiveDialog(host,activity);
            }
            onMain(host,()->{
                try{java.lang.reflect.Method m=MainActivity.class.getDeclaredMethod("agentShowSubscriptions",String.class,boolean.class);m.setAccessible(true);m.invoke(activity,"guest",false);}
                catch(Exception error){throw new RuntimeException(error);}
            });
            host.waitForIdleSync();Thread.sleep(250);screenshot(host,directory,"subscriptions.png");
            dismissActiveDialog(host,activity);
            for(String section:new String[]{"network","memory","workspace","xray"}){
                onMain(host,()->{try{
                    java.lang.reflect.Method method=MainActivity.class.getDeclaredMethod("agentFeatures",String.class);method.setAccessible(true);
                    Object screens=method.invoke(activity,"guest");screens.getClass().getMethod(section).invoke(screens);
                }catch(Exception e){throw new RuntimeException(e);}});
                host.waitForIdleSync();Thread.sleep(250);screenshot(host,directory,section+".png");dismissActiveDialog(host,activity);
            }
            // Back must not send the host Activity to QuickstepLauncher when a
            // sheet was not actually displayed. Re-enter the real chat before IME.
            onMain(host,()->{
                try{
                    java.lang.reflect.Method chat=MainActivity.class.getDeclaredMethod("showAgentChat");
                    chat.setAccessible(true);chat.invoke(activity);
                }catch(Exception error){throw new RuntimeException(error);}
            });
            host.waitForIdleSync();
            // Opening the IME via the app-owned view avoids injecting pointer events
            // into an unrelated overlay window (Android 13 may target another UID).
            onMain(host,()->{
                EditText input=(EditText)find(activity.getWindow().getDecorView(),"Спросить Hubble Agent");
                if(input==null)throw new AssertionError("Integrated composer missing");
                input.requestFocus();
                input.setText("Черновик перед перезапуском экрана");
                input.performClick();
                android.view.inputmethod.InputMethodManager keyboard=
                    (android.view.inputmethod.InputMethodManager)activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                if(keyboard==null)throw new AssertionError("InputMethodManager missing");
                keyboard.showSoftInput(input,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            });
            host.waitForIdleSync();
            final boolean[] imeReady={false};
            long imeDeadline=android.os.SystemClock.uptimeMillis()+10000;
            while(!imeReady[0]&&android.os.SystemClock.uptimeMillis()<imeDeadline){
                onMain(host,()->{
                    android.view.WindowInsets insets=activity.getWindow().getDecorView().getRootWindowInsets();
                    imeReady[0]=insets!=null&&insets.isVisible(android.view.WindowInsets.Type.ime());
                });
                if(!imeReady[0])Thread.sleep(100);
            }
            onMain(host,()->{
                View decor=activity.getWindow().getDecorView();
                android.view.WindowInsets insets=decor.getRootWindowInsets();
                if(insets==null||!insets.isVisible(android.view.WindowInsets.Type.ime()))
                    throw new AssertionError("Soft keyboard did not open after tapping composer");
                View editor=find(decor,"Спросить Hubble Agent");android.graphics.Rect visible=new android.graphics.Rect();
                if(!editor.getGlobalVisibleRect(visible)||visible.height()<editor.getHeight()/2)
                    throw new AssertionError("Composer covered by keyboard");
            });
            screenshot(host,directory,"keyboard.png");
            onMain(host,()->{
                EditText editor=(EditText)find(activity.getWindow().getDecorView(),"Спросить Hubble Agent");
                android.view.inputmethod.InputMethodManager keyboard=
                    (android.view.inputmethod.InputMethodManager)activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                if(editor!=null&&keyboard!=null)keyboard.hideSoftInputFromWindow(editor.getWindowToken(),0);
            });
            onMain(host,()->{
                try{java.lang.reflect.Method chat=MainActivity.class.getDeclaredMethod("showAgentChat");chat.setAccessible(true);chat.invoke(activity);}
                catch(Exception error){throw new RuntimeException(error);}
                EditText input=(EditText)find(activity.getWindow().getDecorView(),"Спросить Hubble Agent");
                if(!"Черновик перед перезапуском экрана".equals(input.getText().toString()))
                    throw new AssertionError("Integrated draft did not survive screen recreation");
            });
        }finally{onMain(host,activity::finish);}
    }
    private static void dismissActiveDialog(Instrumentation host,Activity activity){
        host.waitForIdleSync();
        android.view.accessibility.AccessibilityNodeInfo active=
            host.getUiAutomation().getRootInActiveWindow();
        CharSequence foreground=active==null?null:active.getPackageName();
        if(foreground==null||!activity.getPackageName().contentEquals(foreground))
            throw new AssertionError("Hubble Agent lost foreground before closing a sheet: "+foreground);
        final boolean[] activityFocused={false};
        onMain(host,()->activityFocused[0]=activity.getWindow().getDecorView().hasWindowFocus());
        // Dialogs own the window focus; a native screen with no sheet must not
        // receive BACK, or the test would accidentally exit to the launcher.
        if(!activityFocused[0]){
            host.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
            host.waitForIdleSync();
        }
    }
    private static void onMain(Instrumentation host,Runnable action){
        java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
        host.runOnMainSync(()->{try{action.run();}catch(Throwable error){failure.set(error);}});
        if(failure.get()!=null)throw new AssertionError("UI: "+failure.get(),failure.get());
    }
    private static void screenshot(Instrumentation host,File directory,String name)throws Exception{
        Bitmap image=host.getUiAutomation().takeScreenshot();if(image==null)throw new AssertionError("Screenshot unavailable");
        try(FileOutputStream out=new FileOutputStream(new File(directory,name))){image.compress(Bitmap.CompressFormat.PNG,100,out);}
        finally{image.recycle();}
    }
    private static View find(View view,String value){
        if(view instanceof TextView){TextView text=(TextView)view;
            if(value.contentEquals(text.getText()) || text.getHint()!=null&&value.contentEquals(text.getHint()))return view;}
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++){View found=find(group.getChildAt(i),value);if(found!=null)return found;}}
        return null;
    }
}


