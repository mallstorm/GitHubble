package app.githubble.mobile
import android.app.Application
import androidx.work.*
import app.githubble.mobile.core.work.CacheCleanupWorker
import java.util.concurrent.TimeUnit
class GitHubbleApp:Application(){override fun onCreate(){super.onCreate();val request=PeriodicWorkRequestBuilder<CacheCleanupWorker>(24,TimeUnit.HOURS).setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build()).build();WorkManager.getInstance(this).enqueueUniquePeriodicWork("cache-cleanup",ExistingPeriodicWorkPolicy.KEEP,request)}}
