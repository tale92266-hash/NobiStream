package com.nobi.nobistream.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "nobi_settings"
)

/** App settings kept in DataStore (no secrets here, only preferences). */
class SettingsStore(context: Context) {

    private val dataStore = context.applicationContext.settingsDataStore

    val autoplay: Flow<Boolean> = dataStore.data.map { it[KEY_AUTOPLAY] ?: true }
    val defaultFilter: Flow<String> = dataStore.data.map { it[KEY_DEFAULT_FILTER] ?: "All" }
    val cacheSizeMb: Flow<Int> = dataStore.data.map { it[KEY_CACHE_SIZE_MB] ?: 500 }
    val lastChatId: Flow<Long> = dataStore.data.map { it[KEY_LAST_CHAT_ID] ?: 0L }
    val lastFilter: Flow<String> = dataStore.data.map { it[KEY_LAST_FILTER] ?: "All" }
    /**
     * v4: default OFF — Home must open first; the last episode opens
     * only from the Home hero card, or when the user explicitly turns
     * this switch ON in Settings. (v2/v3 defaulted ON, which yanked
     * the user straight into the Player on every cold start.)
     * DataStore key absent = OFF, so existing installs that never
     * touched the switch also get OFF without any migration.
     */
    val openLastEpisode: Flow<Boolean> = dataStore.data.map { it[KEY_OPEN_LAST_EPISODE] ?: false }

    /**
     * v5 Smart Filter: default ON — each episode shows once, in its
     * best quality. Key absent = ON, so existing installs get it
     * without any migration. OFF restores the old behaviour (every
     * copy listed).
     */
    val smartFilter: Flow<Boolean> = dataStore.data.map { it[KEY_SMART_FILTER] ?: true }

    /**
     * v6 subtitles: default OFF — subtitles appear only when the
     * user wants them. When ON and a video has a sidecar track,
     * subtitles are enabled as soon as the video (or its discovered
     * tracks) load. Style settings below apply to every video.
     */
    val subtitleAlwaysShow: Flow<Boolean> =
        dataStore.data.map { it[KEY_SUBTITLE_ALWAYS_SHOW] ?: false }
    /** "Small" / "Medium" / "Large" — fractional text size on the video. */
    val subtitleSize: Flow<String> =
        dataStore.data.map { it[KEY_SUBTITLE_SIZE] ?: "Medium" }
    /**
     * v9.9 Preferred quality: "Highest available" (default — today's
     * behaviour) / "4K" / "1080p" / "720p" / "480p". Caps the copy
     * Smart Filter picks (see SmartFilter.maxRankFor); never blocks
     * a video — direct copy picks ignore it. Key absent = default,
     * so existing installs need no migration.
     */
    val preferredQuality: Flow<String> =
        dataStore.data.map { it[KEY_PREFERRED_QUALITY] ?: "Highest available" }

    /** "White" / "Yellow" / "Cyan". */
    val subtitleColor: Flow<String> =
        dataStore.data.map { it[KEY_SUBTITLE_COLOR] ?: "White" }
    /** v9.1 #26: "Black 50%" / "Black 80%" / "Transparent" (opacity inside the background dropdown). Legacy "Semi-black" / "Black" values are migrated on first run (see SettingsActivity). */
    val subtitleBackground: Flow<String> =
        dataStore.data.map { it[KEY_SUBTITLE_BACKGROUND] ?: "Black 50%" }

    /**
     * v4: recent search queries, newest first, capped at 10,
     * de-duplicated (case-insensitive). Query text only — nothing
     * else is stored. Kept as one newline-joined string so the
     * order survives (a stringSet would lose it).
     */
    val recentSearches: Flow<List<String>> = dataStore.data.map { prefs ->
        prefs[KEY_RECENT_SEARCHES].orEmpty()
            .split('\n').map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_RECENT_SEARCHES)
    }

    suspend fun addRecentSearch(query: String) {
        val clean = query.trim().replace('\n', ' ').take(100)
        if (clean.isEmpty()) return
        dataStore.edit { prefs ->
            val current = prefs[KEY_RECENT_SEARCHES].orEmpty()
                .split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            val updated = (listOf(clean) + current.filter { !it.equals(clean, ignoreCase = true) })
                .take(MAX_RECENT_SEARCHES)
            prefs[KEY_RECENT_SEARCHES] = updated.joinToString("\n")
        }
    }

    suspend fun clearRecentSearches() {
        dataStore.edit { it.remove(KEY_RECENT_SEARCHES) }
    }

    /** v8: delete ONE recent search (per-row X); the rest keep their order. */
    suspend fun removeRecentSearch(query: String) {
        val clean = query.trim()
        if (clean.isEmpty()) return
        dataStore.edit { prefs ->
            val current = prefs[KEY_RECENT_SEARCHES].orEmpty()
                .split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            val updated = current.filter { !it.equals(clean, ignoreCase = true) }
            if (updated.isEmpty()) {
                prefs.remove(KEY_RECENT_SEARCHES)
            } else {
                prefs[KEY_RECENT_SEARCHES] = updated.joinToString("\n")
            }
        }
    }

    /**
     * v9.1 #19: last-seen video count per group ("chatId=count,...").
     * A group shows its NEW dot only when a last-seen value EXISTS
     * and the count has grown since — first install shows no dots
     * (nothing is "new" yet). Updated when a channel is opened.
     */
    val lastSeenVideoCounts: Flow<Map<Long, Int>> = dataStore.data.map { prefs ->
        prefs[KEY_LAST_SEEN_VIDEO_COUNTS].orEmpty()
            .split(',').mapNotNull { part ->
                val kv = part.split('=', limit = 2)
                val chatId = kv.getOrNull(0)?.toLongOrNull()
                val count = kv.getOrNull(1)?.toIntOrNull()
                if (chatId != null && count != null) chatId to count else null
            }.toMap()
    }

    suspend fun setLastSeenVideoCount(chatId: Long, count: Int) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_LAST_SEEN_VIDEO_COUNTS].orEmpty()
                .split(',').mapNotNull { part ->
                    val kv = part.split('=', limit = 2)
                    val c = kv.getOrNull(0)?.toLongOrNull()
                    val n = kv.getOrNull(1)?.toIntOrNull()
                    if (c != null && n != null) c to n else null
                }.toMap().toMutableMap()
            current[chatId] = count
            prefs[KEY_LAST_SEEN_VIDEO_COUNTS] =
                current.entries.joinToString(",") { "${it.key}=${it.value}" }
        }
    }

    suspend fun setSmartFilter(value: Boolean) {
        dataStore.edit { it[KEY_SMART_FILTER] = value }
    }

    suspend fun setSubtitleAlwaysShow(value: Boolean) {
        dataStore.edit { it[KEY_SUBTITLE_ALWAYS_SHOW] = value }
    }

    suspend fun setSubtitleSize(value: String) {
        dataStore.edit { it[KEY_SUBTITLE_SIZE] = value }
    }

    suspend fun setSubtitleColor(value: String) {
        dataStore.edit { it[KEY_SUBTITLE_COLOR] = value }
    }

    suspend fun setSubtitleBackground(value: String) {
        dataStore.edit { it[KEY_SUBTITLE_BACKGROUND] = value }
    }

    suspend fun setAutoplay(value: Boolean) {
        dataStore.edit { it[KEY_AUTOPLAY] = value }
    }

    suspend fun setOpenLastEpisode(value: Boolean) {
        dataStore.edit { it[KEY_OPEN_LAST_EPISODE] = value }
    }

    suspend fun setDefaultFilter(value: String) {
        dataStore.edit { it[KEY_DEFAULT_FILTER] = value }
    }

    suspend fun setPreferredQuality(value: String) {
        dataStore.edit { it[KEY_PREFERRED_QUALITY] = value }
    }

    suspend fun setCacheSizeMb(value: Int) {
        dataStore.edit { it[KEY_CACHE_SIZE_MB] = value }
    }

    suspend fun setLastChat(chatId: Long, filter: String) {
        dataStore.edit {
            it[KEY_LAST_CHAT_ID] = chatId
            it[KEY_LAST_FILTER] = filter
        }
    }

    suspend fun setLastFilter(filter: String) {
        dataStore.edit { it[KEY_LAST_FILTER] = filter }
    }

    companion object {
        val KEY_AUTOPLAY = booleanPreferencesKey("autoplay")
        val KEY_DEFAULT_FILTER = stringPreferencesKey("default_filter")
        val KEY_PREFERRED_QUALITY = stringPreferencesKey("preferred_quality")
        val KEY_CACHE_SIZE_MB = intPreferencesKey("cache_size_mb")
        val KEY_LAST_CHAT_ID = longPreferencesKey("last_chat_id")
        val KEY_LAST_FILTER = stringPreferencesKey("last_filter")
        val KEY_OPEN_LAST_EPISODE = booleanPreferencesKey("open_last_episode")
        val KEY_SMART_FILTER = booleanPreferencesKey("smart_filter")
        val KEY_SUBTITLE_ALWAYS_SHOW = booleanPreferencesKey("subtitle_always_show")
        val KEY_SUBTITLE_SIZE = stringPreferencesKey("subtitle_size")
        val KEY_SUBTITLE_COLOR = stringPreferencesKey("subtitle_color")
        val KEY_SUBTITLE_BACKGROUND = stringPreferencesKey("subtitle_background")
        val KEY_RECENT_SEARCHES = stringPreferencesKey("recent_searches")
        val KEY_LAST_SEEN_VIDEO_COUNTS = stringPreferencesKey("last_seen_video_counts")
        const val MAX_RECENT_SEARCHES = 10
    }
}
