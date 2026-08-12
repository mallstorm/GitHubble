package app.githubble.mobile.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.githubble.mobile.core.model.RepoContent
import app.githubble.mobile.ui.MainViewModel
import app.githubble.mobile.ui.components.ContentRow
import app.githubble.mobile.ui.components.MarkdownView
import app.githubble.mobile.ui.components.RepositoryCard

@Composable
fun HomeScreen(vm: MainViewModel, onOpen: (String, String) -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Единый центр GitHub на русском", style = MaterialTheme.typography.headlineSmall)
            Text("Репозитории, исходный код, документация и интеллектуальный перевод без изменения команд и путей.")
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::loadPopular) { Text("Популярные") }
                OutlinedButton(onClick = { vm.search("language:kotlin stars:>10000") }) { Text("Kotlin") }
            }
        }
        if (vm.isLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        items(vm.repositories, key = { it.id }) { repository ->
            RepositoryCard(
                repository = repository,
                onOpen = { onOpen(repository.owner, repository.name) },
                onFavorite = { vm.toggleFavorite(repository.fullName) },
                favorite = vm.favorites.any { it.fullName == repository.fullName }
            )
        }
        if (vm.repositories.isEmpty() && !vm.isLoading) item { Text("Репозитории пока не загружены.") }
    }
}

@Composable
fun SearchScreen(vm: MainViewModel, onOpen: (String, String) -> Unit) {
    var query by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Репозитории") },
            singleLine = true,
            trailingIcon = { IconButton(onClick = { vm.search(query) }) { Icon(Icons.Default.Search, "Искать") } }
        )
        Spacer(Modifier.height(12.dp))
        if (vm.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(vm.repositories, key = { it.id }) { repository ->
                RepositoryCard(
                    repository,
                    onOpen = { onOpen(repository.owner, repository.name) },
                    onFavorite = { vm.toggleFavorite(repository.fullName) },
                    favorite = vm.favorites.any { it.fullName == repository.fullName }
                )
            }
        }
    }
}

@Composable
fun NotificationsScreen(vm: MainViewModel) {
    LaunchedEffect(Unit) { vm.loadNotifications() }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
        item { Text("Уведомления", style = MaterialTheme.typography.headlineSmall) }
        if (vm.isLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        items(vm.notifications, key = { it.id }) { n ->
            ListItem(headlineContent = { Text(n.title) }, supportingContent = { Text("${n.type} · ${n.reason}") }, leadingContent = { Icon(Icons.Default.Notifications, null) })
            HorizontalDivider()
        }
        if (!vm.isLoading && vm.notifications.isEmpty()) item { Text("Для уведомлений требуется вход через GitHub.") }
    }
}

@Composable
fun FavoritesScreen(vm: MainViewModel, onOpen: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Избранное", style = MaterialTheme.typography.headlineSmall) }
        if (vm.favorites.isEmpty()) item { Text("Добавьте репозиторий в избранное кнопкой ☆.") }
        items(vm.favorites, key = { it.fullName }) { favorite ->
            ListItem(
                headlineContent = { Text(favorite.fullName) },
                leadingContent = { Icon(Icons.Default.Star, null) },
                trailingContent = { IconButton(onClick = { vm.toggleFavorite(favorite.fullName) }) { Icon(Icons.Default.Delete, "Удалить") } },
                modifier = Modifier.fillMaxWidth()
            )
            HorizontalDivider()
        }
        item {
            Spacer(Modifier.height(12.dp))
            Text("Недавние страницы", style = MaterialTheme.typography.titleMedium)
        }
        items(vm.history, key = { it.route }) { entry ->
            ListItem(
                headlineContent = { Text(entry.title) },
                supportingContent = { Text(entry.route) },
                leadingContent = { Icon(Icons.Default.History, null) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (vm.history.isNotEmpty()) item { TextButton(onClick = vm::clearHistory) { Text("Очистить историю") } }
    }
}

@Composable
fun ProfileScreen(vm: MainViewModel) {
    val profile = vm.profile
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(Icons.Default.AccountCircle, null, Modifier.size(72.dp))
        if (profile == null) {
            Text("Гостевой режим", style = MaterialTheme.typography.headlineSmall)
            Text("Публичные репозитории доступны без входа. Для персональных данных и приватных проектов требуется безопасная авторизация GitHub.")
            Button(onClick = vm::beginLogin) { Text("Войти через GitHub") }
            vm.loginMessage?.let { AssistChip(onClick = {}, label = { Text(it) }) }
            Text("Client secret запрещено хранить в APK. Обмен кода должен выполняться через минимальный backend GitHubble.", style = MaterialTheme.typography.bodySmall)
        } else {
            Text(profile.name ?: profile.login, style = MaterialTheme.typography.headlineSmall)
            Text("@${profile.login}")
            profile.bio?.let { Text(it) }
            Text("Репозитории: ${profile.publicRepos} · Подписчики: ${profile.followers}")
            OutlinedButton(onClick = vm::signOut) { Text("Выйти") }
        }
    }
}

@Composable
fun RepositoryScreen(
    vm: MainViewModel,
    owner: String,
    repo: String,
    onBack: () -> Unit,
    onFiles: (String, String, String) -> Unit,
    onIssues: () -> Unit, onPulls: () -> Unit, onDiscussions: () -> Unit, onReleases: () -> Unit
) {
    LaunchedEffect(owner, repo) { vm.loadRepository(owner, repo) }
    val repository = vm.selectedRepository
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Назад") }
                IconButton(onClick = { vm.toggleFavorite("$owner/$repo") }) {
                    Icon(if (vm.favorites.any { it.fullName == "$owner/$repo" }) Icons.Default.Star else Icons.Default.StarBorder, "Избранное")
                }
            }
        }
        if (vm.isLoading && repository == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        repository?.let { value ->
            item { Text(value.fullName, style = MaterialTheme.typography.headlineSmall) }
            item { Text(value.description ?: "Без описания") }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = {}, label = { Text("★ ${value.stars}") })
                    AssistChip(onClick = {}, label = { Text("Forks ${value.forks}") })
                    AssistChip(onClick = {}, label = { Text("Issues ${value.openIssues}") })
                    AssistChip(onClick = {}, label = { Text(value.language ?: "Язык не указан") })
                }
            }
            item {
                Button(onClick = { onFiles(owner, repo, value.defaultBranch) }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Folder, null); Spacer(Modifier.width(8.dp)); Text("Файлы и README")
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onIssues, Modifier.fillMaxWidth()) { Text("Issues") }
                    OutlinedButton(onClick = onPulls, Modifier.fillMaxWidth()) { Text("Pull Requests") }
                    OutlinedButton(onClick = onDiscussions, Modifier.fillMaxWidth()) { Text("Discussions") }
                    OutlinedButton(onClick = onReleases, Modifier.fillMaxWidth()) { Text("Releases") }
                }
            }
            item { Text("Ветки", style = MaterialTheme.typography.titleMedium) }
            items(vm.branches.take(12), key = { it.name }) { branch ->
                ListItem(
                    headlineContent = { Text(branch.name) },
                    supportingContent = { Text(branch.sha.take(12), fontFamily = FontFamily.Monospace) },
                    leadingContent = { Icon(Icons.Default.AccountTree, null) }
                )
            }
        }
    }
}

@Composable
fun FilesScreen(
    vm: MainViewModel,
    owner: String,
    repo: String,
    path: String,
    ref: String,
    onBack: () -> Unit,
    onDirectory: (String) -> Unit,
    onFile: (RepoContent) -> Unit
) {
    LaunchedEffect(owner, repo, path, ref) { vm.loadContents(owner, repo, path, ref) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Назад") }
            Column { Text("$owner/$repo", style = MaterialTheme.typography.titleMedium); Text(path.ifBlank { "/" }, style = MaterialTheme.typography.bodySmall) }
            Icon(Icons.Default.AccountTree, null)
        }
        if (vm.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn {
            items(vm.contents, key = { it.path }) { item ->
                ContentRow(item) {
                    if (item.type == "dir") onDirectory(item.path) else onFile(item)
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun CodeScreen(vm: MainViewModel, path: String, url: String, onBack: () -> Unit) {
    val markdownFile = path.substringAfterLast('.').lowercase() in setOf("md", "markdown")
    LaunchedEffect(url) { if (url.isNotBlank()) vm.loadText(url, markdownFile) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Назад") }
            Text(path.substringAfterLast('/'), style = MaterialTheme.typography.titleMedium)
            if (markdownFile) IconButton(onClick = vm::translateMarkdown) { Icon(Icons.Default.Translate, "Перевести") }
            else Icon(Icons.Default.Code, null)
        }
        if (vm.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        vm.markdown?.let { document ->
            LazyColumn(Modifier.fillMaxSize().padding(16.dp)) { item { MarkdownView(document) } }
        }
        vm.rawCode?.let { code ->
            LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
                item { Text(code, fontFamily = FontFamily.Monospace) }
            }
        }
    }
}

@Composable
fun IssuesScreen(vm:MainViewModel,owner:String,repo:String,onBack:()->Unit){LaunchedEffect(owner,repo){vm.loadIssues(owner,repo)};EntityListScreen("Issues",onBack,vm.isLoading,vm.issues.map{"#${it.number} ${it.title}" to "${it.state} · ${it.author} · комментариев ${it.comments}"})}
@Composable
fun PullsScreen(vm:MainViewModel,owner:String,repo:String,onBack:()->Unit){LaunchedEffect(owner,repo){vm.loadPulls(owner,repo)};EntityListScreen("Pull Requests",onBack,vm.isLoading,vm.pulls.map{"#${it.number} ${it.title}" to "${it.state} · ${it.author}${if(it.draft)" · черновик" else ""}"})}
@Composable
fun DiscussionsScreen(vm:MainViewModel,owner:String,repo:String,onBack:()->Unit){LaunchedEffect(owner,repo){vm.loadDiscussions(owner,repo)};EntityListScreen("Discussions",onBack,vm.isLoading,vm.discussions.map{"#${it.number} ${it.title}" to it.author})}
@Composable
fun ReleasesScreen(vm:MainViewModel,owner:String,repo:String,onBack:()->Unit){LaunchedEffect(owner,repo){vm.loadReleases(owner,repo)};LazyColumn(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){item{Row{IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,"Назад")};Text("Релизы",style=MaterialTheme.typography.headlineSmall)}};if(vm.isLoading)item{LinearProgressIndicator(Modifier.fillMaxWidth())};items(vm.releases,key={it.id}){r->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){Text(r.name?:r.tagName,style=MaterialTheme.typography.titleMedium);Text(r.tagName);r.body?.takeIf(String::isNotBlank)?.let{Text(it.take(500))};r.assets.forEach{a->OutlinedButton(onClick={vm.download(a)}){Icon(Icons.Default.Download,null);Spacer(Modifier.width(8.dp));Text("${a.name} · ${a.size/1024} КБ")}}}}}}}
@Composable
fun SettingsScreen(vm:MainViewModel,onBack:()->Unit){var backend by remember{mutableStateOf(vm.oauthBackend())};val cfg=remember{vm.translationConfig()};var endpoint by remember{mutableStateOf(cfg[0])};var key by remember{mutableStateOf(cfg[1])};var model by remember{mutableStateOf(cfg[2])};var auto by remember{mutableStateOf(cfg[3].toBoolean())};Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){Row{IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,"Назад")};Text("Настройки",style=MaterialTheme.typography.headlineSmall)};Text("Авторизация",style=MaterialTheme.typography.titleMedium);OutlinedTextField(backend,{backend=it},label={Text("OAuth backend URL")},modifier=Modifier.fillMaxWidth());Text("Перевод",style=MaterialTheme.typography.titleMedium);OutlinedTextField(endpoint,{endpoint=it},label={Text("OpenAI-compatible endpoint")},modifier=Modifier.fillMaxWidth());OutlinedTextField(key,{key=it},label={Text("API-ключ")},modifier=Modifier.fillMaxWidth());OutlinedTextField(model,{model=it},label={Text("Модель")},modifier=Modifier.fillMaxWidth());Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Switch(auto,{auto=it});Spacer(Modifier.width(8.dp));Text("Автоперевод")};Button(onClick={vm.setOAuthBackend(backend);vm.setTranslation(endpoint,key,model,auto)}){Text("Сохранить")};Text("Код, команды, URL и пути защищаются до отправки переводчику.",style=MaterialTheme.typography.bodySmall)}}
@Composable private fun EntityListScreen(title:String,onBack:()->Unit,loading:Boolean,rows:List<Pair<String,String>>){LazyColumn(Modifier.fillMaxSize().padding(16.dp)){item{Row{IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,"Назад")};Text(title,style=MaterialTheme.typography.headlineSmall)}};if(loading)item{LinearProgressIndicator(Modifier.fillMaxWidth())};items(rows){(h,s)->ListItem(headlineContent={Text(h)},supportingContent={Text(s)});HorizontalDivider()};if(!loading&&rows.isEmpty())item{Text("Данных пока нет или API не поддерживает публичный доступ к этому разделу.")}}}
