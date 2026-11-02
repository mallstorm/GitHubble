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
        String first=projects.activeConversation(account);
        projects.appendToConversation(account,first,"user","Первая переписка");
        String second=projects.newConversation(account);
        projects.appendToConversation(account,second,"user","Вторая переписка");
        projects.switchConversation(account,first);
        if(projects.messages(account).length()!=1 || !"Первая переписка".equals(projects.messages(account).getJSONObject(0).getString("body")))
            throw new AssertionError("Conversation history was replaced");
        if(projects.messages(other).length()!=0)throw new AssertionError("Cross-account messages");
        AgentProjectStore restored=new AgentProjectStore(context);
        if(!first.equals(restored.activeConversation(account)))throw new AssertionError("Active thread not restored");
        Intent intent=new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity activity=host.startActivitySync(intent);
        File directory=new File(context.getExternalFilesDir(null),"ui-screenshots");directory.mkdirs();
        try {
            for(boolean dark:new boolean[]{false,true}){
                host.runOnMainSync(()->{
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
                });
                host.waitForIdleSync();
                host.runOnMainSync(()->{
                    View root=activity.getWindow().getDecorView();
                    Bitmap image=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);
                    root.draw(new android.graphics.Canvas(image));
                    try(FileOutputStream output=new FileOutputStream(new File(directory,dark?"chat-dark.png":"chat-light.png"))){
                        image.compress(Bitmap.CompressFormat.PNG,100,output);
                    }catch(Exception error){throw new RuntimeException(error);}finally{image.recycle();}
                });
            }
        }finally{host.runOnMainSync(activity::finish);}
    }
    private static View find(View view,String value){
        if(view instanceof TextView){TextView text=(TextView)view;
            if(value.contentEquals(text.getText()) || text.getHint()!=null&&value.contentEquals(text.getHint()))return view;}
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++){View found=find(group.getChildAt(i),value);if(found!=null)return found;}}
        return null;
    }
}
