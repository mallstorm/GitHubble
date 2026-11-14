package app.githubble.mobile;

import android.app.*;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import app.githubble.mobile.agent.*;
import app.githubble.mobile.ui.ChangeReviewScreen;
import org.json.*;
import java.io.File;

/** Real review controls and partial acceptance, without network or account credentials. */
final class ChangeReviewUiChecks {
    static void run(Instrumentation host,Activity activity)throws Exception {
        String owner="review-"+java.util.UUID.randomUUID();AgentProjectStore projects=new AgentProjectStore(activity);
        projects.activeConversation(owner);String project=projects.registry.create(owner,"Review","","main").getString("projectId");
        String task=projects.newTask(owner,project,"Review changes","");WorkspaceHub hub=new WorkspaceHub(activity);String workspace=hub.create(owner,"Review").getString("id");
        hub.saveFile(owner,workspace,"file.txt","one\nkeep\nthree\n");projects.save(new AgentProjectStore.Project(owner,"Review","","main",workspace));
        TaskGitService service=new TaskGitService(new File(activity.getFilesDir(),"task-changes"),projects,hub,owner,task,null,true,()->{});
        WorkspaceToolset tools=service.tools();tools.write(new JSONArray().put(new JSONObject().put("path","file.txt").put("expected_sha",GitObjectId.blob("one\nkeep\nthree\n")).put("content","ONE\nkeep\nTHREE\n")),"UI change");
        java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
        try{
            new ChangeReviewScreen(activity,()->service,worker,()->!activity.isDestroyed(),()->{}).show();
            click(host,"UI change · PROPOSED");click(host,"file.txt · PROPOSED");click(host,"Блоки");
            click(host,"-one");click(host,"Принять выбранные");
            for(int i=0;i<100&&!"ONE\nkeep\nthree\n".equals(service.tools().snapshot("file.txt").text);i++)SystemClock.sleep(50);
            if(!"ONE\nkeep\nthree\n".equals(service.tools().snapshot("file.txt").text))throw new AssertionError("Hunk review applied the wrong lines");
            if(!"one\nkeep\nthree\n".equals(hub.readFile(owner,workspace,"file.txt")))throw new AssertionError("Review modified the shared base");
            click(host,"Закрыть");System.out.println("PASS: task diff and per-hunk review controls");
        }finally{worker.shutdownNow();}
    }
    private static void click(Instrumentation host,String text)throws Exception {
        for(int i=0;i<100;i++){
            AccessibilityNodeInfo node=find(host.getUiAutomation().getRootInActiveWindow(),text);
            if(node!=null){
                android.graphics.Rect bounds=new android.graphics.Rect();node.getBoundsInScreen(bounds);
                if(!bounds.isEmpty()){
                    long now=SystemClock.uptimeMillis();
                    android.view.MotionEvent down=android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_DOWN,bounds.centerX(),bounds.centerY(),0);
                    android.view.MotionEvent up=android.view.MotionEvent.obtain(now,now+60,android.view.MotionEvent.ACTION_UP,bounds.centerX(),bounds.centerY(),0);
                    try{host.getUiAutomation().injectInputEvent(down,true);host.getUiAutomation().injectInputEvent(up,true);}finally{down.recycle();up.recycle();}
                    host.waitForIdleSync();SystemClock.sleep(200);return;
                }
            }
            SystemClock.sleep(50);
        }throw new AssertionError("Review control unavailable: "+text);
    }
    private static AccessibilityNodeInfo find(AccessibilityNodeInfo node,String text){
        if(node==null)return null;CharSequence value=node.getText();if(value!=null&&value.toString().contains(text))return node;
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo found=find(node.getChild(i),text);if(found!=null)return found;}return null;
    }
}
