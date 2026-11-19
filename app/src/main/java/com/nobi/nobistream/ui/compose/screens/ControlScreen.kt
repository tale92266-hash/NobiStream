package com.nobi.nobistream.ui.compose.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nobi.nobistream.core.FormatUtils
import com.nobi.nobistream.data.DownloadManager
import com.nobi.nobistream.data.NobiRepository
import com.nobi.nobistream.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Subtitle option lists — the same lists as the v9.9 SettingsVideo
// screen (SettingsStore documents them: size Small / Medium /
// Large, colour White / Yellow / Cyan, background Black 50% /
// Black 80% / Transparent). The v9.9 sub-screen Activities do not
// exist in this working copy, so the lists are taken from
// SettingsStore.kt itself, which is their source of truth.
private val SUBTITLE_SIZES = listOf("Small", "Medium", "Large")
private val SUBTITLE_COLORS = listOf("White", "Yellow", "Cyan")
private val SUBTITLE_BACKGROUNDS = listOf("Black 50%", "Black 80%", "Transparent")
private val PREFERRED_QUALITIES =
    listOf("Highest available", "4K", "1080p", "720p", "480p")
private val DEFAULT_FILTERS = listOf("All", "480p", "720p", "1080p", "4K")

/**
 * Control tab — the mock's ProfileSettingsScreen layout, every
 * control real. The v9.9 Settings hub contents are merged in here
 * as sections; the hub as a separate structure is gone.
 *
 * Dropped on purpose, with the reason in the spec: the audio-track
 * preference row (a file's audio language is unknowable before
 * playback) and the paid-tier claims (there is no paid tier).
 * No API fields anywhere. No third stat — two real stats only.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ControlScreen(
    repository: NobiRepository,
    downloadManager: DownloadManager,
    settingsStore: SettingsStore,
    showBack: Boolean,
    onBack: () -> Unit,
    onLogoutDone: () -> Unit,
    onShowMessage: (String) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val user by repository.currentUser.collectAsStateWithLifecycle()
    val bookmarkCount by repository.observeBookmarkCount()
        .collectAsStateWithLifecycle(initialValue = 0)
    val watchedCount by repository.observeWatchedCount()
        .collectAsStateWithLifecycle(initialValue = 0)
    val themeMode by settingsStore.themeMode
        .collectAsStateWithLifecycle(initialValue = "System")
    val amoled by settingsStore.amoled
        .collectAsStateWithLifecycle(initialValue = false)
    val preferredQuality by settingsStore.preferredQuality
        .collectAsStateWithLifecycle(initialValue = "Highest available")
    val autoplay by settingsStore.autoplay
        .collectAsStateWithLifecycle(initialValue = true)
    val smartFilter by settingsStore.smartFilter
        .collectAsStateWithLifecycle(initialValue = true)
    val defaultFilter by settingsStore.defaultFilter
        .collectAsStateWithLifecycle(initialValue = "All")
    val subtitleAlwaysShow by settingsStore.subtitleAlwaysShow
        .collectAsStateWithLifecycle(initialValue = false)
    val subtitleSize by settingsStore.subtitleSize
        .collectAsStateWithLifecycle(initialValue = "Medium")
    val subtitleColor by settingsStore.subtitleColor
        .collectAsStateWithLifecycle(initialValue = "White")
    val subtitleBackground by settingsStore.subtitleBackground
        .collectAsStateWithLifecycle(initialValue = "Black 50%")
    val downloads by downloadManager.downloads
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val completedBytes by downloadManager.completedBytes
        .collectAsStateWithLifecycle(initialValue = 0L)

    // Effective dark: Dark -> true, Light -> false, System -> system.
    val effectiveDark = when (themeMode) {
        "Dark" -> true
        "Light" -> false
        else -> isSystemInDarkTheme()
    }
    val completedCount = downloads.count { it.status == "COMPLETED" }

    var confirmLogout by remember { mutableStateOf(false) }
    var confirmDeleteDownloads by remember { mutableStateOf(false) }
    var infoDialog by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            // v2.6.1: root background in theme colours.
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (showBack) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Text(
                        text = "Control",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }

        // 1. Account card (real user, real stats — two, not three).
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                )
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(60.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = user?.displayName?.firstOrNull()?.uppercase().orEmpty(),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = user?.displayName ?: "Not signed in",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                text = listOfNotNull(
                                    user?.phoneNumber?.takeIf { it.isNotBlank() },
                                    user?.username?.takeIf { it.isNotBlank() }?.let { "@$it" }
                                ).joinToString(" • ").ifEmpty { "Signed out" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        StatBadge(label = "Watchlist", value = "$bookmarkCount")
                        StatBadge(label = "Watched", value = "$watchedCount")
                    }
                }
            }
        }

        // 2. Appearance.
        item {
            SectionLabel("Appearance")
            Spacer(modifier = Modifier.height(6.dp))
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // v2.6.1 (#3): a real 3-way theme selector. The
                    // old Dark Theme switch was Dark <-> System — OFF
                    // wrote "System", which follows the phone, and
                    // Sam's phone is dark, so Light was unreachable.
                    // System / Dark / Light all write themeMode
                    // directly. The top-bar toggle still flips
                    // Dark <-> Light.
                    Text(
                        text = "Theme",
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "System follows this phone's dark setting",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("System", "Dark", "Light").forEach { option ->
                            androidx.compose.material3.FilterChip(
                                selected = themeMode == option,
                                onClick = {
                                    scope.launch {
                                        settingsStore.setThemeMode(option)
                                    }
                                },
                                label = { Text(option) }
                            )
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    SwitchRow(
                        title = "Pitch-Black AMOLED Mode",
                        desc = "True black background for OLED power saving",
                        checked = amoled,
                        enabled = effectiveDark,
                        onCheckedChange = { checked ->
                            scope.launch { settingsStore.setAmoled(checked) }
                        }
                    )
                }
            }
        }

        // 3. Default Resolution (all five options).
        item {
            SectionLabel("Default Resolution")
            Spacer(modifier = Modifier.height(6.dp))
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Caps the copy Smart Filter picks. A direct copy pick is never blocked.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PREFERRED_QUALITIES.forEach { option ->
                            androidx.compose.material3.FilterChip(
                                selected = preferredQuality == option,
                                onClick = {
                                    scope.launch {
                                        settingsStore.setPreferredQuality(option)
                                    }
                                },
                                label = { Text(option) }
                            )
                        }
                    }
                }
            }
        }

        // 4. Playback.
        item {
            SectionLabel("Playback")
            Spacer(modifier = Modifier.height(6.dp))
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SwitchRow(
                        title = "Autoplay",
                        desc = "Play the next video automatically",
                        checked = autoplay,
                        onCheckedChange = { checked ->
                            scope.launch { settingsStore.setAutoplay(checked) }
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    SwitchRow(
                        title = "Smart Filter",
                        desc = "Show each episode once, in its best copy",
                        checked = smartFilter,
                        onCheckedChange = { checked ->
                            scope.launch { settingsStore.setSmartFilter(checked) }
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    DropdownRow(
                        title = "Default quality filter",
                        desc = "Quality shown first in lists",
                        value = defaultFilter,
                        options = DEFAULT_FILTERS,
                        onSelect = { option ->
                            scope.launch { settingsStore.setDefaultFilter(option) }
                        }
                    )
                }
            }
        }

        // 5. Subtitles.
        item {
            SectionLabel("Subtitles")
            Spacer(modifier = Modifier.height(6.dp))
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SwitchRow(
                        title = "Always show subtitles",
                        desc = "Show subtitles automatically when a video has them",
                        checked = subtitleAlwaysShow,
                        onCheckedChange = { checked ->
                            scope.launch { settingsStore.setSubtitleAlwaysShow(checked) }
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    DropdownRow(
                        title = "Subtitle size",
                        desc = "How big subtitles appear on the video",
                        value = subtitleSize,
                        options = SUBTITLE_SIZES,
                        onSelect = { option ->
                            scope.launch { settingsStore.setSubtitleSize(option) }
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    DropdownRow(
                        title = "Subtitle colour",
                        desc = "Text colour for subtitles",
                        value = subtitleColor,
                        options = SUBTITLE_COLORS,
                        onSelect = { option ->
                            scope.launch { settingsStore.setSubtitleColor(option) }
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    DropdownRow(
                        title = "Subtitle background",
                        desc = "Background behind subtitle text",
                        value = subtitleBackground,
                        options = SUBTITLE_BACKGROUNDS,
                        onSelect = { option ->
                            scope.launch { settingsStore.setSubtitleBackground(option) }
                        }
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Subtitles appear only for videos with a subtitle file (.srt or .vtt) attached in Telegram. Text burned into the video itself can't be turned off.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 6. Storage: purge cache (measured) + downloads use.
        item {
            SectionLabel("Storage")
            Spacer(modifier = Modifier.height(6.dp))
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Clear cache", fontWeight = FontWeight.Bold)
                            Text(
                                "Delete player and thumbnail caches. Downloads are not touched.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        FilledTonalButton(onClick = {
                            scope.launch {
                                val before = withContext(Dispatchers.IO) {
                                    repository.getCacheUsedBytes()
                                }
                                repository.clearCache()
                                val after = withContext(Dispatchers.IO) {
                                    repository.getCacheUsedBytes()
                                }
                                val freed = (before - after).coerceAtLeast(0L)
                                onShowMessage(
                                    if (freed > 0) {
                                        "Freed ${FormatUtils.formatSize(freed)}"
                                    } else {
                                        "Cache already empty"
                                    }
                                )
                            }
                        }) {
                            Icon(
                                Icons.Filled.CleaningServices,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Clean")
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Downloads use ${FormatUtils.formatSize(completedBytes)}", fontWeight = FontWeight.Bold)
                            Text(
                                text = if (completedCount == 0) {
                                    "No completed downloads"
                                } else {
                                    "$completedCount file${if (completedCount == 1) "" else "s"} saved on this phone"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(
                            onClick = { confirmDeleteDownloads = true },
                            enabled = downloads.isNotEmpty()
                        ) {
                            Text("Delete all downloads")
                        }
                    }
                }
            }
        }

        // 7. Application: version, About, Privacy, licences, log out.
        item {
            SectionLabel("Application")
            Spacer(modifier = Modifier.height(6.dp))
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Version", fontWeight = FontWeight.Bold)
                        Text(
                            text = "2.6.2 (24)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    TextButton(
                        onClick = {
                            infoDialog = "About NobiStream 2.6.1. Videos stream from your own Telegram account with TDLib (MTProto). No videos are uploaded anywhere."
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("About", modifier = Modifier.fillMaxWidth())
                    }
                    TextButton(
                        onClick = {
                            infoDialog = "NobiStream sends nothing anywhere except to Telegram, using your own account. Your groups and watch positions stay on this phone."
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Privacy", modifier = Modifier.fillMaxWidth())
                    }
                    TextButton(
                        onClick = {
                            infoDialog = "NobiStream is built with open-source libraries:\n\n• TDLib (Telegram Database Library) — Boost Software License 1.0\n• ExoPlayer / Media3 — Apache License 2.0\n• Material Components for Android — Apache License 2.0\n• Room, DataStore, Lifecycle — Apache License 2.0\n• Coil — Apache License 2.0\n• EncryptedSharedPreferences (Security Crypto) — Apache License 2.0\n• NextLib (FFmpeg extension for Media3) — GNU General Public License v3.0"
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Open-source licences", modifier = Modifier.fillMaxWidth())
                    }
                    if (repository.credentialsStore.hasSaved &&
                        repository.credentialsStore.hasBuiltIn
                    ) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                        TextButton(
                            onClick = {
                                repository.credentialsStore.clearSaved()
                                onShowMessage("Saved credentials cleared")
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Reset saved credentials", modifier = Modifier.fillMaxWidth())
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    OutlinedButton(
                        onClick = { confirmLogout = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ExitToApp,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Log out")
                    }
                }
            }
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Log out?") },
            text = {
                Text("Your downloaded videos will be removed, and you will need to sign in again.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    scope.launch {
                        repository.logout()
                        downloadManager.clearAllOnLogout()
                        onLogoutDone()
                    }
                }) {
                    Text("Log out")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (confirmDeleteDownloads) {
        AlertDialog(
            onDismissRequest = { confirmDeleteDownloads = false },
            title = { Text("Delete all downloads?") },
            text = { Text("Every saved video will be removed from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteDownloads = false
                    scope.launch {
                        runCatching { downloadManager.deleteAll() }
                        onShowMessage("All downloads deleted")
                    }
                }) {
                    Text("Delete all")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteDownloads = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    val info = infoDialog
    if (info != null) {
        AlertDialog(
            onDismissRequest = { infoDialog = null },
            title = { Text("NobiStream") },
            text = { Text(info) },
            confirmButton = {
                TextButton(onClick = { infoDialog = null }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun StatBadge(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

@Composable
private fun DropdownRow(
    title: String,
    desc: String,
    value: String,
    options: List<String>,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box {
            Button(
                onClick = { expanded = true },
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(value)
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            expanded = false
                            onSelect(option)
                        }
                    )
                }
            }
        }
    }
}
