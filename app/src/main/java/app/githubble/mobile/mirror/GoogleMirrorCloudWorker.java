package app.githubble.mobile.mirror;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.tasks.Tasks;
import java.util.concurrent.TimeUnit;

/**
 * Google Play Services supplies transient, short-lived credentials.
 * No refresh tokens or OAuth secrets are stored in WorkManager Data / app preferences.
 */
public final class GoogleMirrorCloudWorker extends Worker {
    public GoogleMirrorCloudWorker(@NonNull Context app,@NonNull WorkerParameters p){
        super(app,p);
    }
    @NonNull @Override public Result doWork(){
        GoogleMirrorCloudStore cloud=new GoogleMirrorCloudStore(getApplicationContext());
        if(!cloud.connected())return Result.success();
        try{
            AuthorizationResult authorization=Tasks.await(
                    Identity.getAuthorizationClient(getApplicationContext()).authorize(
                            GoogleMirrorCloudStore.request(false,cloud.email())),
                    25,TimeUnit.SECONDS);
            if(authorization.hasResolution()||authorization.getAccessToken()==null){
                cloud.needsAuthorization();
                return Result.success(); // A background worker must not display account prompts.
            }
            cloud.sync(authorization.getAccessToken());
            return Result.success();
        }catch(SecurityException denied){
            cloud.needsAuthorization();
            return Result.success();
        }catch(Exception problem){
            return Result.retry();
        }
    }
}
