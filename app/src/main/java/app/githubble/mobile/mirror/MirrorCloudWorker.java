package app.githubble.mobile.mirror;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/** Persistent periodic/one-time reconciliation of a user-selected SAF cloud file. */
public final class MirrorCloudWorker extends Worker {
    public MirrorCloudWorker(@NonNull Context context,@NonNull WorkerParameters params){
        super(context,params);
    }
    @NonNull @Override public Result doWork(){
        MirrorCloudStore store=new MirrorCloudStore(getApplicationContext());
        if(!store.connected())return Result.success();
        try{
            store.sync();
            return Result.success();
        }catch(IllegalArgumentException | SecurityException bad){
            // Never overwrite a malformed registry or retry with revoked document access.
            return Result.failure();
        }catch(Exception problem){
            return Result.retry();
        }
    }
}
