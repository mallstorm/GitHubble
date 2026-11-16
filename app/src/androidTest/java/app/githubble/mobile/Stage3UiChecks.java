package app.githubble.mobile;

import android.app.*;
import app.githubble.mobile.agent.*;
import app.githubble.mobile.ui.TaskWorkPanel;
import org.json.*;
import java.io.*;

/** Real process-recovery metadata, work-panel navigation and cryptographic APK verification. */
final class Stage3UiChecks {
    static void run(Instrumentation host,Activity activity)throws Exception {
        String account="stage3-"+java.util.UUID.randomUUID();AgentProjectStore store=new AgentProjectStore(activity);
        store.activeConversation(account);String project=store.registry.create(account,"Stage III","","main").getString("projectId");
        String task=store.newTask(account,project,"Восстановление CI","Проверить состояние после остановки");
        store.tasks.update(account,task,t->{try{t.put("state","BUILDING").put("ci",new JSONObject().put("state","DISPATCH_PENDING").put("workflowId","7").put("sha","a".repeat(40)).put("retry",new JSONObject().put("buildRuns",2)));}catch(Exception e){throw new AssertionError(e);}});
        store.tasks.recoverInterrupted(account,"");
        AgentProjectStore restored=new AgentProjectStore(activity);restored.activeConversation(account);
        if(!"INTERRUPTED".equals(restored.tasks.get(account,task).getString("state")))throw new AssertionError("CI task was not recovered");
        if(!"UNKNOWN".equals(restored.tasks.get(account,task).getJSONObject("ci").getString("state")))throw new AssertionError("Unknown dispatch was replayable");
        host.runOnMainSync(()->new TaskWorkPanel(activity,restored,account,task,()->!activity.isDestroyed(),()->{},id->{}).show());
        ChangeReviewUiChecks.click(host,"CI и проверка результата");
        ChangeReviewUiChecks.click(host,"Назад");ChangeReviewUiChecks.click(host,"Закрыть");
        File installed=new File(activity.getApplicationInfo().sourceDir);
        if(!ApkSignatureVerifier.verify(installed).getBoolean("signature_verified"))throw new AssertionError("Installed signed APK failed verification");
        File broken=File.createTempFile("tampered-",".apk",activity.getCacheDir());
        try{
            java.nio.file.Files.copy(installed.toPath(),broken.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            try(RandomAccessFile f=new RandomAccessFile(broken,"rw")){long pos=f.length()/3;f.seek(pos);int b=f.read();f.seek(pos);f.write(b^1);}
            boolean verified;try{verified=ApkSignatureVerifier.verify(broken).getBoolean("signature_verified");}catch(Exception invalid){verified=false;}
            if(verified)throw new AssertionError("Tampered APK was accepted");
        }finally{broken.delete();}
        System.out.println("PASS: stage III recovery, work panel and cryptographic APK signature");
    }
}
