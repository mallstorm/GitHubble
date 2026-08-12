package app.githubble.mobile.core.cache

import android.content.Context
import app.githubble.mobile.core.model.FavoriteEntry
import app.githubble.mobile.core.model.HistoryEntry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class LocalStores(context: Context) {
    private val prefs = context.getSharedPreferences("githubble_local", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun favorites(): List<FavoriteEntry> = decodeList("favorites")
    fun toggleFavorite(fullName: String): Boolean {
        val list = favorites().toMutableList()
        val existing = list.indexOfFirst { it.fullName == fullName }
        val added = existing < 0
        if (added) list.add(0, FavoriteEntry(fullName, System.currentTimeMillis())) else list.removeAt(existing)
        prefs.edit().putString("favorites", json.encodeToString(list)).apply()
        return added
    }

    fun history(): List<HistoryEntry> = decodeList("history")
    fun addHistory(route: String, title: String) {
        val updated = history().filterNot { it.route == route }.toMutableList()
        updated.add(0, HistoryEntry(route, title, System.currentTimeMillis()))
        prefs.edit().putString("history", json.encodeToString(updated.take(100))).apply()
    }
    fun clearHistory() = prefs.edit().remove("history").apply()

    private inline fun <reified T> decodeList(key: String): List<T> = runCatching {
        json.decodeFromString<List<T>>(prefs.getString(key, null) ?: "[]")
    }.getOrDefault(emptyList())
}
