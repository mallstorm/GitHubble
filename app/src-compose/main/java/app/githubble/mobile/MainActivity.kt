package app.githubble.mobile
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import app.githubble.mobile.ui.MainViewModel
import app.githubble.mobile.ui.navigation.Routes
import app.githubble.mobile.ui.screens.*
class MainActivity:ComponentActivity(){override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{GitHubbleUi(intent)}};override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent)}}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun GitHubbleUi(initialIntent:Intent,vm:MainViewModel=viewModel()){val nav=rememberNavController();LaunchedEffect(initialIntent.data){vm.handleOAuthCallback(initialIntent.data)};val tabs=listOf(Triple(Routes.HOME,"Главная",Icons.Default.Home),Triple(Routes.SEARCH,"Поиск",Icons.Default.Search),Triple(Routes.NOTIFICATIONS,"Уведомления",Icons.Default.Notifications),Triple(Routes.FAVORITES,"Избранное",Icons.Default.Star),Triple(Routes.PROFILE,"Профиль",Icons.Default.Person));val route=nav.currentBackStackEntryAsState().value?.destination?.route
 MaterialTheme{Scaffold(topBar={TopAppBar(title={Text("GitHubble")},actions={IconButton(onClick={nav.navigate(Routes.SETTINGS)}){Icon(Icons.Default.Settings,"Настройки")}})},bottomBar={if(tabs.any{it.first==route})NavigationBar{tabs.forEach{(d,l,i)->NavigationBarItem(route==d,{nav.navigate(d){launchSingleTop=true;restoreState=true}},{Icon(i,l)},label={Text(l)})}}}},snackbarHost={vm.error?.let{Snackbar{Text(it)}}}){pad->NavHost(nav,Routes.HOME,Modifier.padding(pad)){
  composable(Routes.HOME){HomeScreen(vm){o,r->nav.navigate(Routes.repository(o,r))}};composable(Routes.SEARCH){SearchScreen(vm){o,r->nav.navigate(Routes.repository(o,r))}};composable(Routes.NOTIFICATIONS){NotificationsScreen(vm)};composable(Routes.FAVORITES){FavoritesScreen(vm){full->full.split('/').takeIf{it.size==2}?.let{nav.navigate(Routes.repository(it[0],it[1]))}}};composable(Routes.PROFILE){ProfileScreen(vm)};composable(Routes.SETTINGS){SettingsScreen(vm,nav::popBackStack)}
  composable(Routes.REPOSITORY,arguments=listOf(navArgument("owner"){type=NavType.StringType},navArgument("repo"){type=NavType.StringType})){b->val o=b.arguments!!.getString("owner")!!;val r=b.arguments!!.getString("repo")!!;RepositoryScreen(vm,o,r,nav::popBackStack,{a,z,ref->nav.navigate(Routes.files(a,z,ref=ref))},{nav.navigate(Routes.issues(o,r))},{nav.navigate(Routes.pulls(o,r))},{nav.navigate(Routes.discussions(o,r))},{nav.navigate(Routes.releases(o,r))})}
  composable(Routes.FILES,arguments=listOf(navArgument("owner"){type=NavType.StringType},navArgument("repo"){type=NavType.StringType},navArgument("path"){defaultValue=""},navArgument("ref"){defaultValue=""})){b->val o=b.arguments!!.getString("owner")!!;val r=b.arguments!!.getString("repo")!!;val p=b.arguments?.getString("path").orEmpty();val ref=b.arguments?.getString("ref").orEmpty();FilesScreen(vm,o,r,p,ref,nav::popBackStack,{next->nav.navigate(Routes.files(o,r,next,ref))},{item->item.downloadUrl?.let{nav.navigate(Routes.code(o,r,item.path,it))}})}
  composable(Routes.CODE,arguments=listOf(navArgument("owner"){type=NavType.StringType},navArgument("repo"){type=NavType.StringType},navArgument("path"){defaultValue=""},navArgument("url"){defaultValue=""})){b->CodeScreen(vm,b.arguments?.getString("path").orEmpty(),b.arguments?.getString("url").orEmpty(),nav::popBackStack)}
  composable(Routes.ISSUES,arguments=listOf(navArgument("owner"){type=NavType.StringType},navArgument("repo"){type=NavType.StringType})){b->IssuesScreen(vm,b.arguments!!.getString("owner")!!,b.arguments!!.getString("repo")!!,nav::popBackStack)}
  composable(Routes.PULLS,arguments=listOf(navArgument("owner"){type=NavType.StringType},navArgument("repo"){type=NavType.StringType})){b->PullsScreen(vm,b.arguments!!.getString("owner")!!,b.arguments!!.getString("repo")!!,nav::popBackStack)}
  composable(Routes.DISCUSSIONS,arguments=listOf(navArgument("owner"){type=NavType.StringType},navArgument("repo"){type=NavType.StringType})){b->DiscussionsScreen(vm,b.arguments!!.getString("owner")!!,b.arguments!!.getString("repo")!!,nav::popBackStack)}
  composable(Routes.RELEASES,arguments=listOf(navArgument("owner"){type=NavType.StringType},navArgument("repo"){type=NavType.StringType})){b->ReleasesScreen(vm,b.arguments!!.getString("owner")!!,b.arguments!!.getString("repo")!!,nav::popBackStack)}
 }}}
}
