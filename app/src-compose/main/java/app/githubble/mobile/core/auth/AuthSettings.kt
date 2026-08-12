package app.githubble.mobile.core.auth
import android.content.Context
class AuthSettings(context:Context){ private val p=context.getSharedPreferences("auth_settings",Context.MODE_PRIVATE); var backendEndpoint:String get()=p.getString("backend","").orEmpty() set(v){p.edit().putString("backend",v).apply()} }
