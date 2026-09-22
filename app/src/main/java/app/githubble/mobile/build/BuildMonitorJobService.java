package app.githubble.mobile.build;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Android-native persisted periodic checks; delivery time is OS-controlled, never "instant push". */
public final class BuildMonitorJobService extends JobService {
    private static final int JOB_ID=21043;
    private static final ExecutorService WORK=Executors.newSingleThreadExecutor();
    private Future<?> running;
    @Override public boolean onStartJob(JobParameters params){
        running=WORK.submit(()->{
            boolean retry=false;
            try{
                if(GlobalBuildHub.enabled(getApplicationContext()))
                    GlobalBuildHub.refresh(getApplicationContext(),false);
                BuildMonitor.scan(getApplicationContext(),true);
            }
            catch(Exception error){retry=true;}
            finally{jobFinished(params,retry);}
        });
        return true;
    }
    @Override public boolean onStopJob(JobParameters params){
        if(running!=null)running.cancel(true);
        return true;
    }
    public static void reschedule(Context context){
        JobScheduler scheduler=context.getSystemService(JobScheduler.class);
        if(scheduler==null)return;
        scheduler.cancel(JOB_ID);
        boolean watched=BuildMonitor.prefs(context).getBoolean(BuildMonitor.PREF_ENABLED,true)
                &&!BuildMonitor.watched(context).isEmpty();
        if(!watched&&!GlobalBuildHub.enabled(context))return;
        long period=Math.max(JobInfo.getMinPeriodMillis(),TimeUnit.MINUTES.toMillis(15));
        long flex=Math.max(JobInfo.getMinFlexMillis(),TimeUnit.MINUTES.toMillis(5));
        JobInfo job=new JobInfo.Builder(JOB_ID,
                new ComponentName(context,BuildMonitorJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setPeriodic(period,Math.min(period,flex))
                .build();
        scheduler.schedule(job);
    }
    public static void checkSoon(Context context){
        boolean watched=BuildMonitor.prefs(context).getBoolean(BuildMonitor.PREF_ENABLED,true)
                &&!BuildMonitor.watched(context).isEmpty();
        if(!watched&&!GlobalBuildHub.enabled(context))return;
        long last=BuildMonitor.prefs(context).getLong(
                "build_"+BuildMonitor.account(context)+"_last_check",0L);
        if(System.currentTimeMillis()-last<2*60*1000L)return;
        WORK.submit(()->{
            try{
                if(GlobalBuildHub.enabled(context))
                    GlobalBuildHub.refresh(context.getApplicationContext(),false);
                BuildMonitor.scan(context.getApplicationContext(),true);
            }
            catch(Exception ignored){}
        });
    }
}
