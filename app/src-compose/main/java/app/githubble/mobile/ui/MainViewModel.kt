package app.githubble.mobile.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import app.githubble.mobile.BuildConfig
import app.githubble.mobile.core.auth.*
import app.githubble.mobile.core.cache.LocalStores
import app.githubble.mobile.core.database.AppDatabase
import app.githubble.mobile.core.markdown.*
import app.githubble.mobile.core.model.*
import app.githubble.mobile.core.network.GitHubApi
import app.githubble.mobile.core.security.SecureTokenStore
import app.githubble.mobile.core.translation.*
import app.githubble.mobile.core.work.DownloadWorker
import kotlinx.coroutines.launch
import java.security.MessageDigest

class MainViewModel(app:Application):AndroidViewModel(app){
 private val tokenStore=SecureTokenStore(app); private val oauthSession=OAuthSessionStore(app); private val oauth=OAuthManager(); private val authSettings=AuthSettings(app); private val oauthBackend=OAuthBackendClient(authSettings::backendEndpoint)
 private val api=GitHubApi(tokenStore::accessToken); private val local=LocalStores(app); private val parser=MarkdownParser(); private val translationSettings=TranslationSettings(app); private val translationCache=AppDatabase.get(app).cacheDao(); private val translation=TranslationEngine(provider=HttpTranslationProvider(translationSettings::endpoint,translationSettings::apiKey,translationSettings::model))
 var repositories by mutableStateOf<List<Repository>>(emptyList());private set; var selectedRepository by mutableStateOf<Repository?>(null);private set; var contents by mutableStateOf<List<RepoContent>>(emptyList());private set; var branches by mutableStateOf<List<Branch>>(emptyList());private set
 var issues by mutableStateOf<List<IssueItem>>(emptyList());private set; var pulls by mutableStateOf<List<PullRequestItem>>(emptyList());private set; var discussions by mutableStateOf<List<DiscussionItem>>(emptyList());private set; var releases by mutableStateOf<List<ReleaseItem>>(emptyList());private set; var notifications by mutableStateOf<List<NotificationItem>>(emptyList());private set
 var markdown by mutableStateOf<MarkdownDocument?>(null);private set; var originalMarkdown by mutableStateOf<MarkdownDocument?>(null);private set; var rawCode by mutableStateOf<String?>(null);private set; var profile by mutableStateOf<UserProfile?>(null);private set
 var isLoading by mutableStateOf(false);private set; var error by mutableStateOf<String?>(null);private set; var loginMessage by mutableStateOf<String?>(null);private set; var favorites by mutableStateOf(local.favorites());private set; var history by mutableStateOf(local.history());private set; var translationStatus by mutableStateOf<String?>(null);private set
 init{loadPopular(); if(tokenStore.accessToken()!=null)loadProfile()}
 fun loadPopular()=search("stars:>50000"); fun search(q:String)=request{repositories=api.searchRepositories(q)}
 fun loadRepository(owner:String,repo:String)=request{selectedRepository=api.repository(owner,repo);branches=api.branches(owner,repo);local.addHistory("repository/$owner/$repo","$owner/$repo");history=local.history()}
 fun loadContents(owner:String,repo:String,path:String,ref:String?)=request{contents=api.contents(owner,repo,path,ref?.ifBlank{null})}
 fun loadText(url:String,asMarkdown:Boolean)=request{val text=api.rawText(url);if(asMarkdown){originalMarkdown=parser.parse(text);markdown=originalMarkdown;rawCode=null}else{rawCode=text;markdown=null;originalMarkdown=null}}
 fun loadIssues(o:String,r:String)=request{issues=api.issues(o,r).filterNot{it.pullRequest}}; fun loadPulls(o:String,r:String)=request{pulls=api.pulls(o,r)}; fun loadDiscussions(o:String,r:String)=request{discussions=api.discussions(o,r)}; fun loadReleases(o:String,r:String)=request{releases=api.releases(o,r)}; fun loadNotifications()=request{notifications=api.notifications()}
 fun translateMarkdown()=viewModelScope.launch{val current=originalMarkdown?:markdown?:return@launch;isLoading=true;translationStatus="Перевод…";val source=current.toString();val hash=MessageDigest.getInstance("SHA-256").digest(source.toByteArray()).joinToString(""){"%02x".format(it)};val key="md:$hash:ru:${translationSettings.model}";runCatching{translationCache.translation(key)?.translatedText?.let{parser.parse(it)}?:translation.translate(current).also{doc->translationCache.putTranslation(app.githubble.mobile.core.database.TranslationCacheEntity(key,hash,"ru",renderPlain(doc),"http",System.currentTimeMillis()))}}.onSuccess{markdown=it;translationStatus="Переведено автоматически"}.onFailure{error=it.message;translationStatus=null};isLoading=false}
 fun showOriginal(){markdown=originalMarkdown;translationStatus="Показан оригинал"}
 fun toggleFavorite(v:String){local.toggleFavorite(v);favorites=local.favorites()};fun clearHistory(){local.clearHistory();history=emptyList()}
 fun beginLogin(){runCatching{oauth.createRequest()}.onSuccess{oauthSession.save(it.state,it.verifier);getApplication<Application>().startActivity(Intent(Intent.ACTION_VIEW,it.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}.onFailure{error=it.message}}
 fun handleOAuthCallback(uri:Uri?){if(uri==null||uri.scheme!="githubble")return;val cb=oauth.parseCallback(uri);cb.error?.let{loginMessage="Ошибка GitHub: $it";return};val verifier=oauthSession.consume(cb.state);when{cb.code.isNullOrBlank()->loginMessage="GitHub не вернул код авторизации.";verifier==null->loginMessage="Проверка OAuth state не пройдена.";else->viewModelScope.launch{isLoading=true;runCatching{oauthBackend.exchange(cb.code,verifier,BuildConfig.OAUTH_CALLBACK)}.onSuccess{tokenStore.save(it);loginMessage="Вход выполнен";loadProfile()}.onFailure{loginMessage=it.message};isLoading=false}}}
 fun loadProfile()=request{profile=api.me()};fun signOut(){tokenStore.clear();profile=null;loginMessage="Вы вышли из GitHub."};fun clearError(){error=null}
 fun setOAuthBackend(v:String){authSettings.backendEndpoint=v};fun oauthBackend()=authSettings.backendEndpoint
 fun setTranslation(endpoint:String,key:String,model:String,auto:Boolean){translationSettings.endpoint=endpoint;translationSettings.apiKey=key;translationSettings.model=model;translationSettings.autoTranslate=auto};fun translationConfig()=listOf(translationSettings.endpoint,translationSettings.apiKey,translationSettings.model,translationSettings.autoTranslate.toString())
 fun download(asset:ReleaseAsset){WorkManager.getInstance(getApplication()).enqueue(DownloadWorker.request(asset.downloadUrl,asset.name));translationStatus="Загрузка ${asset.name} поставлена в очередь"}
 private fun request(block:suspend()->Unit){viewModelScope.launch{isLoading=true;error=null;runCatching{block()}.onFailure{error=it.message?:"Неизвестная ошибка"};isLoading=false}}
 private fun renderPlain(doc:MarkdownDocument)=doc.blocks.joinToString("\n\n"){when(it){is MarkdownBlock.Heading->"${"#".repeat(it.level)} ${it.text}";is MarkdownBlock.Paragraph->it.text;is MarkdownBlock.Code->"```\n${it.code}\n```";is MarkdownBlock.Bullet->"- ${it.text}";MarkdownBlock.Divider->"---"}}
}
