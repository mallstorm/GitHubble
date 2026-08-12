package app.githubble.mobile.ui.navigation
object Routes{
 const val HOME="home";const val SEARCH="search";const val NOTIFICATIONS="notifications";const val FAVORITES="favorites";const val PROFILE="profile";const val SETTINGS="settings"
 const val REPOSITORY="repository/{owner}/{repo}";const val FILES="files/{owner}/{repo}?path={path}&ref={ref}";const val CODE="code/{owner}/{repo}?path={path}&url={url}"
 const val ISSUES="issues/{owner}/{repo}";const val PULLS="pulls/{owner}/{repo}";const val DISCUSSIONS="discussions/{owner}/{repo}";const val RELEASES="releases/{owner}/{repo}"
 fun repository(o:String,r:String)="repository/$o/$r";fun files(o:String,r:String,path:String="",ref:String="")="files/$o/$r?path=${android.net.Uri.encode(path)}&ref=${android.net.Uri.encode(ref)}";fun code(o:String,r:String,path:String,url:String)="code/$o/$r?path=${android.net.Uri.encode(path)}&url=${android.net.Uri.encode(url)}";fun issues(o:String,r:String)="issues/$o/$r";fun pulls(o:String,r:String)="pulls/$o/$r";fun discussions(o:String,r:String)="discussions/$o/$r";fun releases(o:String,r:String)="releases/$o/$r"
}
