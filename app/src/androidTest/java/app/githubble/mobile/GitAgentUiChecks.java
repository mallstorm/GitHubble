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
        connections.disconnect(account,"openai");
        if(connections.hasKey(account,"openai"))throw new AssertionError("Disconnected key retained");
        org.json.JSONObject oauth=new org.json.JSONObject().put("access_token","local-test-access")
            .put("refresh_token","local-test-refresh").put("expires_at",System.currentTimeMillis()+3600000);
        for(String subscription:new String[]{app.githubble.mobile.agent.OAuthSubscriptions.KIMI,
                app.githubble.mobile.agent.OAuthSubscriptions.GROK}){
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
        AgentProjectStore restored=new AgentProjectStore(context);
        if(!first.equals(restored.activeConversation(account)))throw new AssertionError("Active thread not restored");
        Intent intent=new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity activity=host.startActivitySync(intent);
        File directory=new File(context.getExternalFilesDir(null),"ui-screenshots");directory.mkdirs();
        try {
            for(boolean dark:new boolean[]{false,true}){
                onMain(host,()->{
                    LinearLayout root=new LinearLayout(activity);root.setOrientation(1);activity.setContentView(root);
                    AgentChatScreen.Actions actions=(AgentChatScreen.Actions)java.lang.reflect.Proxy.newProxyInstance(
                        AgentChatScreen.Actions.class.getClassLoader(),new Class[]{AgentChatScreen.Actions.class},
                        (proxy,method,args)->null);
                    AgentChatScreen screen=new AgentChatScreen(activity,root,dark?0xFF343941:0xFFFFFFFF,
                        0xFFFFFFFF,0xFFF2F4F6,0xFF222B31,0xFF52616B,0xFF435B69,0xFFD7DFE5,actions);
                    screen.render("test",projects.project(account),new JSONArray(),0,false,false,"","",false,
                        "Новый диалог",false,"Сохранённый черновик");
                    if(find(root,"Git Agent")==null||find(root,"Спросить Git Agent")==null)
                        throw new AssertionError("Missing Git Agent branding/composer");
                    if(find(root,"OmniChat")!=null)throw new AssertionError("Old branding visible");
                    EditText input=(EditText)find(root,"Спросить Git Agent");
                    if(!"Сохранённый черновик".equals(input.getText().toString()))throw new AssertionError("Draft missing");
                    app.githubble.mobile.ui.GitAgentUi design=new app.githubble.mobile.ui.GitAgentUi(activity,dark?0xFF343941:0xFFFFFFFF);
                    LinearLayout controls=design.row();
                    for(String title:new String[]{"Выкл","Авто","Один агент","Оркестрация"})
                        design.modeChip(controls,title,title.equals("Один агент"),!title.equals("Авто")&&!title.equals("Оркестрация"),()->{});
                    controls.measure(View.MeasureSpec.makeMeasureSpec(design.dp(300),View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(design.dp(80),View.MeasureSpec.AT_MOST));
                    controls.layout(0,0,controls.getMeasuredWidth(),controls.getMeasuredHeight());
                    for(int i=0;i<controls.getChildCount();i++){
                        TextView button=(TextView)controls.getChildAt(i);
                        if(button.getTop()!=controls.getChildAt(0).getTop()||button.getBottom()!=controls.getChildAt(0).getBottom())
                            throw new AssertionError("Execution controls are not aligned in one row");
                        if(button.getPaint().measureText(button.getText().toString())>button.getWidth()-button.getPaddingLeft()-button.getPaddingRight()+1)
                            throw new AssertionError("Execution mode label is clipped: "+button.getText());
                    }
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
            // Exercise the integrated app surface and the actual modal windows too.
            onMain(host,()->{
                try {
                    context.getSharedPreferences("githubble",Context.MODE_PRIVATE).edit().putString("theme","light").commit();
                    java.lang.reflect.Method theme=MainActivity.class.getDeclaredMethod("applyTheme");theme.setAccessible(true);theme.invoke(activity);
                    java.lang.reflect.Method chat=MainActivity.class.getDeclaredMethod("showAgentChat");chat.setAccessible(true);chat.invoke(activity);
                }catch(Exception error){throw new RuntimeException(error);}
            });
            host.waitForIdleSync();Thread.sleep(300);screenshot(host,directory,"integrated-chat.png");
            for(String methodName:new String[]{"agentChooseComposerModel","agentShowAttachments","agentShowAgentSettings","agentShowConversations"}){
                onMain(host,()->{
                    try{
                        java.lang.reflect.Method method=MainActivity.class.getDeclaredMethod(methodName,String.class);
                        method.setAccessible(true);method.invoke(activity,"guest");
                    }catch(Exception error){throw new RuntimeException(error);}
                });
                host.waitForIdleSync();Thread.sleep(300);screenshot(host,directory,methodName+".png");
                host.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);host.waitForIdleSync();
            }
            onMain(host,()->{
                try{java.lang.reflect.Method m=MainActivity.class.getDeclaredMethod("agentShowSubscriptions",String.class,boolean.class);m.setAccessible(true);m.invoke(activity,"guest",false);}
                catch(Exception error){throw new RuntimeException(error);}
            });
            host.waitForIdleSync();Thread.sleep(250);screenshot(host,directory,"subscriptions.png");
            host.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);host.waitForIdleSync();
            final float[] touch=new float[2];
            onMain(host,()->{
                EditText input=(EditText)find(activity.getWindow().getDecorView(),"Спросить Git Agent");
                if(input==null)throw new AssertionError("Integrated composer missing");
                input.requestFocus();input.setText("Черновик перед перезапуском экрана");
                int[] location=new int[2];input.getLocationOnScreen(location);
                touch[0]=location[0]+input.getWidth()/2f;touch[1]=location[1]+input.getHeight()/2f;
            });
            host.waitForIdleSync();
            long down=android.os.SystemClock.uptimeMillis();
            android.view.MotionEvent press=android.view.MotionEvent.obtain(down,down,android.view.MotionEvent.ACTION_DOWN,touch[0],touch[1],0);
            android.view.MotionEvent release=android.view.MotionEvent.obtain(down,down+60,android.view.MotionEvent.ACTION_UP,touch[0],touch[1],0);
            host.sendPointerSync(press);host.sendPointerSync(release);press.recycle();release.recycle();
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
                View editor=find(decor,"Спросить Git Agent");android.graphics.Rect visible=new android.graphics.Rect();
                if(!editor.getGlobalVisibleRect(visible)||visible.height()<editor.getHeight()/2)
                    throw new AssertionError("Composer covered by keyboard");
            });
            screenshot(host,directory,"keyboard.png");
            host.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
            onMain(host,()->{
                try{java.lang.reflect.Method chat=MainActivity.class.getDeclaredMethod("showAgentChat");chat.setAccessible(true);chat.invoke(activity);}
                catch(Exception error){throw new RuntimeException(error);}
                EditText input=(EditText)find(activity.getWindow().getDecorView(),"Спросить Git Agent");
                if(!"Черновик перед перезапуском экрана".equals(input.getText().toString()))
                    throw new AssertionError("Integrated draft did not survive screen recreation");
            });
        }finally{onMain(host,activity::finish);}
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

