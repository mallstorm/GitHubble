package app.githubble.mobile.core.work
import android.content.Context
import androidx.work.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
class DownloadWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params){
 override suspend fun doWork():Result{ val url=inputData.getString("url")?:return Result.failure(); val name=inputData.getString("name")?:"download.bin"; return runCatching{ val c=URL(url).openConnection() as HttpURLConnection; c.setRequestProperty("User-Agent","GitHubble-Android"); val out=File(applicationContext.getExternalFilesDir("downloads"),name.replace(Regex("[^A-Za-z0-9._-]"),"_")); out.parentFile?.mkdirs(); c.inputStream.use{input->out.outputStream().use{input.copyTo(it)}}; Result.success(workDataOf("path" to out.absolutePath)) }.getOrElse{Result.retry()} }
 companion object{ fun request(url:String,name:String)=OneTimeWorkRequestBuilder<DownloadWorker>().setInputData(workDataOf("url" to url,"name" to name)).build() }
}
