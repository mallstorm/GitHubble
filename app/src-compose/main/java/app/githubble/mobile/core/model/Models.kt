package app.githubble.mobile.core.model

import kotlinx.serialization.Serializable

@Serializable data class Repository(val id:Long,val owner:String,val name:String,val description:String?,val language:String?,val stars:Int,val isPrivate:Boolean,val defaultBranch:String="main",val forks:Int=0,val openIssues:Int=0,val htmlUrl:String=""){ val fullName get()="$owner/$name" }
@Serializable data class RepoContent(val name:String,val path:String,val type:String,val size:Long=0,val downloadUrl:String?=null,val htmlUrl:String?=null,val sha:String="")
@Serializable data class Branch(val name:String,val sha:String)
@Serializable data class UserProfile(val login:String,val name:String?,val avatarUrl:String?,val bio:String?,val publicRepos:Int,val followers:Int)
@Serializable data class FavoriteEntry(val fullName:String,val savedAt:Long)
@Serializable data class HistoryEntry(val route:String,val title:String,val openedAt:Long)
@Serializable data class IssueItem(val number:Int,val title:String,val state:String,val author:String,val comments:Int,val body:String?,val htmlUrl:String,val pullRequest:Boolean=false)
@Serializable data class PullRequestItem(val number:Int,val title:String,val state:String,val author:String,val draft:Boolean,val body:String?,val htmlUrl:String)
@Serializable data class DiscussionItem(val number:Int,val title:String,val author:String,val body:String?,val htmlUrl:String)
@Serializable data class ReleaseItem(val id:Long,val tagName:String,val name:String?,val body:String?,val prerelease:Boolean,val publishedAt:String?,val htmlUrl:String,val assets:List<ReleaseAsset>)
@Serializable data class ReleaseAsset(val id:Long,val name:String,val size:Long,val downloadUrl:String)
@Serializable data class NotificationItem(val id:String,val reason:String,val unread:Boolean,val updatedAt:String,val title:String,val type:String,val apiUrl:String?)
@Serializable data class RateLimitInfo(val remaining:Int,val limit:Int,val resetEpochSeconds:Long)
sealed interface UiState<out T>{ data object Idle:UiState<Nothing>; data object Loading:UiState<Nothing>; data class Data<T>(val value:T):UiState<T>; data class Error(val message:String):UiState<Nothing> }
