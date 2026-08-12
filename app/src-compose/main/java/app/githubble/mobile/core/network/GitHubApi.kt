package app.githubble.mobile.core.network

import app.githubble.mobile.core.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class GitHubApi(private val tokenProvider:()->String?={null}){
 private val json=Json{ignoreUnknownKeys=true}
 suspend fun searchRepositories(query:String)=io{ val q=enc(query.ifBlank{"android"}); requestJson("/search/repositories?q=$q&sort=stars&per_page=30").jsonObject["items"]?.jsonArray.orEmpty().map(::repositoryFromJson) }
 suspend fun repository(owner:String,repo:String)=io{repositoryFromJson(requestJson("/repos/$owner/$repo"))}
 suspend fun contents(owner:String,repo:String,path:String="",ref:String?=null)=io{ val p=path.split('/').filter(String::isNotBlank).joinToString("/"){enc(it)}; val suffix=ref?.let{"?ref=${enc(it)}"}.orEmpty(); val e=requestJson("/repos/$owner/$repo/contents/$p$suffix"); (if(e is JsonArray)e else JsonArray(listOf(e))).map{ o->o.jsonObject.let{RepoContent(it.s("name"),it.s("path"),it.s("type"),it.l("size"),it.ns("download_url"),it.ns("html_url"),it.s("sha"))}}.sortedWith(compareBy<RepoContent>{it.type!="dir"}.thenBy{it.name.lowercase()}) }
 suspend fun branches(owner:String,repo:String)=io{requestJson("/repos/$owner/$repo/branches?per_page=100").jsonArray.map{val o=it.jsonObject;Branch(o.s("name"),o["commit"]?.jsonObject?.s("sha").orEmpty())}}
 suspend fun issues(owner:String,repo:String)=io{requestJson("/repos/$owner/$repo/issues?state=all&per_page=50").jsonArray.map{val o=it.jsonObject;IssueItem(o.i("number"),o.s("title"),o.s("state"),o["user"]?.jsonObject?.s("login").orEmpty(),o.i("comments"),o.ns("body"),o.s("html_url"),o["pull_request"]!=null)}}
 suspend fun pulls(owner:String,repo:String)=io{requestJson("/repos/$owner/$repo/pulls?state=all&per_page=50").jsonArray.map{val o=it.jsonObject;PullRequestItem(o.i("number"),o.s("title"),o.s("state"),o["user"]?.jsonObject?.s("login").orEmpty(),o.b("draft"),o.ns("body"),o.s("html_url"))}}
 suspend fun releases(owner:String,repo:String)=io{requestJson("/repos/$owner/$repo/releases?per_page=30").jsonArray.map{val o=it.jsonObject;ReleaseItem(o.l("id"),o.s("tag_name"),o.ns("name"),o.ns("body"),o.b("prerelease"),o.ns("published_at"),o.s("html_url"),o["assets"]?.jsonArray.orEmpty().map{a->a.jsonObject.let{ReleaseAsset(it.l("id"),it.s("name"),it.l("size"),it.s("browser_download_url"))}})}}
 suspend fun discussions(owner:String,repo:String)=io{ // REST has no universal discussions endpoint; search Issues API for discussion-like results as graceful public fallback.
  runCatching{requestJson("/repos/$owner/$repo/discussions?per_page=30").jsonArray.map{val o=it.jsonObject;DiscussionItem(o.i("number"),o.s("title"),o["user"]?.jsonObject?.s("login").orEmpty(),o.ns("body"),o.s("html_url"))}}.getOrDefault(emptyList()) }
 suspend fun notifications()=io{requestJson("/notifications?all=false&participating=false&per_page=50").jsonArray.map{val o=it.jsonObject; val subject=o["subject"]?.jsonObject; NotificationItem(o.s("id"),o.s("reason"),o.b("unread"),o.s("updated_at"),subject?.s("title").orEmpty(),subject?.s("type").orEmpty(),subject?.ns("url"))}}
 suspend fun rawText(url:String)=io{request(url,"application/vnd.github.raw+json")}
 suspend fun me()=io{val o=requestJson("/user").jsonObject;UserProfile(o.s("login"),o.ns("name"),o.ns("avatar_url"),o.ns("bio"),o.i("public_repos"),o.i("followers"))}
 private fun repositoryFromJson(e:JsonElement):Repository{val o=e.jsonObject;return Repository(o.l("id"),o["owner"]?.jsonObject?.s("login").orEmpty(),o.s("name"),o.ns("description"),o.ns("language"),o.i("stargazers_count"),o.b("private"),o.ns("default_branch")?:"main",o.i("forks_count"),o.i("open_issues_count"),o.s("html_url"))}
 private suspend fun<T> io(block:()->T)=withContext(Dispatchers.IO){block()}
 private fun requestJson(path:String)=json.parseToJsonElement(request("https://api.github.com$path"))
 private fun request(url:String,accept:String="application/vnd.github+json"):String{val c=URL(url).openConnection() as HttpURLConnection;c.requestMethod="GET";c.connectTimeout=15000;c.readTimeout=25000;c.setRequestProperty("Accept",accept);c.setRequestProperty("X-GitHub-Api-Version","2022-11-28");c.setRequestProperty("User-Agent","GitHubble-Android");tokenProvider()?.takeIf(String::isNotBlank)?.let{c.setRequestProperty("Authorization","Bearer $it")};val code=c.responseCode;val body=(if(code in 200..299)c.inputStream else c.errorStream)?.bufferedReader()?.use{it.readText()}.orEmpty();if(code !in 200..299)throw GitHubApiException(code,body.take(400),c.getHeaderField("X-RateLimit-Remaining"),c.getHeaderField("X-RateLimit-Reset"));return body}
 private fun enc(v:String)=URLEncoder.encode(v,"UTF-8")
 private fun JsonObject.s(k:String)=this[k]?.jsonPrimitive?.contentOrNull.orEmpty();private fun JsonObject.ns(k:String)=this[k]?.jsonPrimitive?.contentOrNull;private fun JsonObject.i(k:String)=this[k]?.jsonPrimitive?.intOrNull?:0;private fun JsonObject.l(k:String)=this[k]?.jsonPrimitive?.longOrNull?:0;private fun JsonObject.b(k:String)=this[k]?.jsonPrimitive?.booleanOrNull?:false
}
class GitHubApiException(val statusCode:Int,message:String,val rateLimitRemaining:String?,val rateLimitReset:String?):RuntimeException("GitHub HTTP $statusCode: $message")
