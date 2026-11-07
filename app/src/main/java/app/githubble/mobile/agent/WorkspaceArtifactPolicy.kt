package app.githubble.mobile.agent

/**
 * Canonical presentation rules for artifacts emitted by workspace agents.
 *
 * Agent tools may emit [[workspace-file:...]] markers while the controller can later rebuild the
 * final archive from the finished workspace. The controller output is authoritative: stale tool
 * markers are removed before the final list is appended, and the UI presents at most the last ZIP
 * marker as the primary archive while keeping regular files in a secondary group.
 */
object WorkspaceArtifactPolicy {
    private val markerRegex = Regex(
        pattern = """(?m)^[\t ]*\[\[workspace-file:([^\r\n]+?)]]\s*$"""
    )

    data class Presentation(
        val markdown: String,
        val primaryArchive: String?,
        val otherFiles: List<String>
    )

    fun paths(content: String): List<String> = markerRegex.findAll(content)
        .map { it.groupValues[1].trim().replace('\\', '/') }
        .filter { it.isNotBlank() }
        .toList()

    fun stripMarkers(content: String): String = markerRegex.replace(content, "")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trimEnd()

    fun canonicalAgentOutput(content: String, createdFiles: List<String>): String {
        // Never keep raw tool-emitted artifact markers. A tool can create a transient archive that
        // the controller immediately deletes before rebuilding the final ZIP; exposing that stale
        // path is what previously produced a zero-byte Android download destination.
        val cleaned = stripMarkers(content)
        val canonical = createdFiles
            .map { it.trim().replace('\\', '/') }
            .filter(String::isNotBlank)
            .distinct()
        if (canonical.isEmpty()) return cleaned
        return buildString {
            append(cleaned)
            if (isNotBlank()) append("\n\n")
            append("Файлы действительно созданы и готовы к скачиванию:")
            canonical.forEach { append("\n[[workspace-file:").append(it).append("]]" ) }
        }
    }

    fun presentation(content: String): Presentation {
        val all = paths(content)
        if (all.isEmpty()) return Presentation(content, null, emptyList())

        // Keep the newest marker for the same display name. This also repairs old chat messages
        // that contain both a transient ZIP marker and the controller's final ZIP marker.
        val newestByName = linkedMapOf<String, String>()
        all.forEach { path ->
            newestByName.remove(displayName(path).lowercase())
            newestByName[displayName(path).lowercase()] = path
        }
        val deduped = newestByName.values.toList()
        val primaryArchive = deduped.filter { it.endsWith(".zip", ignoreCase = true) }.lastOrNull()
        val other = deduped.filterNot { it.endsWith(".zip", ignoreCase = true) }
        return Presentation(stripMarkers(content), primaryArchive, other)
    }

    fun displayName(path: String): String = path.substringAfterLast('/').ifBlank { path }
}

