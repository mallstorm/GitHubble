package app.githubble.mobile.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.githubble.mobile.core.markdown.MarkdownBlock
import app.githubble.mobile.core.markdown.MarkdownDocument
import app.githubble.mobile.core.model.RepoContent
import app.githubble.mobile.core.model.Repository

@Composable
fun RepositoryCard(repository: Repository, onOpen: () -> Unit, onFavorite: () -> Unit, favorite: Boolean) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(repository.fullName, style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onFavorite) { Text(if (favorite) "★" else "☆") }
            }
            Text(repository.description ?: "Без описания")
            Text("★ ${repository.stars} · Forks ${repository.forks} · ${repository.language ?: "—"}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun ContentRow(item: RepoContent, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        leadingContent = {
            Icon(if (item.type == "dir") Icons.Default.Folder else Icons.Default.InsertDriveFile, null)
        },
        headlineContent = { Text(item.name) },
        supportingContent = { Text(if (item.type == "dir") "Каталог" else "${item.size} байт") }
    )
}

@Composable
fun MarkdownView(document: MarkdownDocument, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        document.blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading -> Text(block.text, style = when (block.level) {
                    1 -> MaterialTheme.typography.headlineMedium
                    2 -> MaterialTheme.typography.headlineSmall
                    else -> MaterialTheme.typography.titleMedium
                })
                is MarkdownBlock.Paragraph -> Text(block.text)
                is MarkdownBlock.Bullet -> Row { Text("•  "); Text(block.text) }
                is MarkdownBlock.Code -> Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.small) {
                    Row(Modifier.padding(12.dp)) {
                        Icon(Icons.Default.Code, null, Modifier.padding(end = 8.dp))
                        Text(block.code, fontFamily = FontFamily.Monospace)
                    }
                }
                MarkdownBlock.Divider -> HorizontalDivider()
            }
        }
    }
}
