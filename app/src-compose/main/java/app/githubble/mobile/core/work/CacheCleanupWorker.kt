package app.githubble.mobile.core.work
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.githubble.mobile.core.database.AppDatabase
class CacheCleanupWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params){ override suspend fun doWork():Result=runCatching{ AppDatabase.get(applicationContext).cacheDao().purgeApi(System.currentTimeMillis()); Result.success() }.getOrElse{Result.retry()} }
