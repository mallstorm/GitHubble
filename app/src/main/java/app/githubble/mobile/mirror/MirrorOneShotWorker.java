package app.githubble.mobile.mirror;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import app.githubble.mobile.auth.GitHubAccountStore;
import app.githubble.mobile.security.SecureStore;

/**
 * Persistent WorkManager reconciliation after app/process exit.
 * Does not store a token in its input Data; only an opaque local job ID.
 */
public final class MirrorOneShotWorker extends Worker {
    public static final String JOB_ID="oneshot_job_id";
    public MirrorOneShotWorker(@NonNull Context context,@NonNull WorkerParameters params){
        super(context,params);
    }
    @NonNull @Override public Result doWork(){
        String id=getInputData().getString(JOB_ID);
        if(id==null||!id.matches("[0-9a-f]{32}"))return Result.failure();
        Context app=getApplicationContext();
        MirrorManager mgr=new MirrorManager(app,
                new GitHubAccountStore(app,new SecureStore(app)));
        MirrorManager.Job job=mgr.find(id);
        if(job==null)return Result.failure();
        if((MirrorManager.VERIFIED.equals(job.state)
                ||MirrorManager.FAILED.equals(job.state)
                ||MirrorManager.DIVERGED.equals(job.state))
                &&!job.cleanupPending)return Result.success();
        try{
            MirrorManager.Job updated=mgr.refresh(id);
            if(updated.cleanupPending)return Result.retry();
            if(MirrorManager.VERIFIED.equals(updated.state)
                    ||MirrorManager.FAILED.equals(updated.state)
                    ||MirrorManager.DIVERGED.equals(updated.state))return Result.success();
            return Result.retry();
        }catch(Exception error){
            // No token material or API responses are written to WorkManager logs.
            return Result.retry();
        }
    }
}
