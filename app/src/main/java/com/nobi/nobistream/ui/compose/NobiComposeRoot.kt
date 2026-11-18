package com.nobi.nobistream.ui.compose

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.ManageSearch
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.ManageSearch
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nobi.nobistream.core.AuthState
import com.nobi.nobistream.core.VideoItem
import com.nobi.nobistream.data.DownloadManager
import com.nobi.nobistream.data.NobiRepository
import com.nobi.nobistream.data.SettingsStore
import com.nobi.nobistream.ui.compose.screens.ControlScreen
import com.nobi.nobistream.ui.compose.screens.HomeScreen
import com.nobi.nobistream.ui.compose.screens.LibraryScreen
import com.nobi.nobistream.ui.compose.screens.SearchScreen
import com.nobi.nobistream.ui.compose.theme.NobiStreamTheme
import kotlinx.coroutines.launch

private enum class NobiTab(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    DISCOVER("Discover", Icons.Filled.Explore, Icons.Outlined.Explore),
    CATALOG("Catalog", Icons.Filled.ManageSearch, Icons.Outlined.ManageSearch),
    VAULT("My Vault", Icons.Filled.Bookmarks, Icons.Outlined.Bookmarks),
    CONTROL("Control", Icons.Filled.Tune, Icons.Outlined.Tune)
}

/**
 * Compose root: theme resolution + Scaffold (mock top bar + 4-tab
 * bottom nav) + Crossfade between tabs. One visible tab at a time,
 * so only the visible tab owns a pull-to-refresh container — the
 * v9.3 Bug A class (a stacked, invisible, enabled refresh layout
 * eating every tap) cannot occur by construction.
 *
 * Sheet ownership (one place per screen, as briefed): the Add Group
 * sheet lives HERE (shared by Home's empty state and Search's
 * paste-link chip); the copies sheet lives inside HomeScreen and
 * ChannelScreen, which are the screens that open it.
 *
 * Screens set PlayerPlaylist themselves immediately before calling
 * [onPlayVideo]; the host lambda only builds the player intent
 * (EXTRA_CHAT_ID + EXTRA_MESSAGE_ID) and starts PlayerActivity.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NobiComposeRoot(
    repository: NobiRepository,
    downloadManager: DownloadManager,
    settingsStore: SettingsStore,
    startTab: String,
    startAddGroupLink: String?,
    onOpenChannel: (Long, String) -> Unit,
    onPlayVideo: (VideoItem) -> Unit,
    onOpenContinueAll: () -> Unit,
    onOpenLogin: () -> Unit,
    onFinish: () -> Unit
) {
    val themeMode by settingsStore.themeMode
        .collectAsStateWithLifecycle(initialValue = "System")
    val amoled by settingsStore.amoled
        .collectAsStateWithLifecycle(initialValue = false)
    val authState by repository.authState
        .collectAsStateWithLifecycle(initialValue = AuthState.UNKNOWN)

    val darkTheme = when (themeMode) {
        "Dark" -> true
        "Light" -> false
        else -> isSystemInDarkTheme()
    }

    // Logged out while the root is up (e.g. after Control's Log out
    // finishes its work) routes back to login via the host.
    LaunchedEffect(authState) {
        if (authState == AuthState.LOGGED_OUT) onOpenLogin()
    }

    NobiStreamTheme(darkTheme = darkTheme, amoledMode = amoled) {
        NobiComposeRootContent(
            repository = repository,
            downloadManager = downloadManager,
            settingsStore = settingsStore,
            startTab = startTab,
            startAddGroupLink = startAddGroupLink,
            darkTheme = darkTheme,
            onOpenChannel = onOpenChannel,
            onPlayVideo = onPlayVideo,
            onOpenContinueAll = onOpenContinueAll,
            onFinish = onFinish
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NobiComposeRootContent(
    repository: NobiRepository,
    downloadManager: DownloadManager,
    settingsStore: SettingsStore,
    startTab: String,
    startAddGroupLink: String?,
    darkTheme: Boolean,
    onOpenChannel: (Long, String) -> Unit,
    onPlayVideo: (VideoItem) -> Unit,
    onOpenContinueAll: () -> Unit,
    onFinish: () -> Unit
) {
    var currentTab by remember {
        mutableStateOf(if (startTab == "library") NobiTab.VAULT else NobiTab.DISCOVER)
    }
    var showAddSheet by remember { mutableStateOf(startAddGroupLink != null) }
    var addGroupLink by remember { mutableStateOf(startAddGroupLink.orEmpty()) }
    var addingGroup by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun showFeedback(message: String) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message = message)
        }
    }

    BackHandler {
        if (currentTab != NobiTab.DISCOVER) {
            currentTab = NobiTab.DISCOVER
        } else {
            onFinish()
        }
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    shape = RoundedCornerShape(14.dp),
                    containerColor = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface
                )
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.PlayCircleFilled,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "NobiStream",
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { currentTab = NobiTab.CATALOG }) {
                        Icon(Icons.Filled.Search, contentDescription = "Quick Search")
                    }
                    IconButton(onClick = {
                        scope.launch {
                            settingsStore.setThemeMode(if (darkTheme) "Light" else "Dark")
                        }
                    }) {
                        Icon(
                            imageVector = if (darkTheme) Icons.Filled.LightMode
                            else Icons.Filled.DarkMode,
                            contentDescription = "Toggle Theme"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                NobiTab.entries.forEach { tab ->
                    val selected = currentTab == tab
                    NavigationBarItem(
                        selected = selected,
                        onClick = { currentTab = tab },
                        icon = {
                            Icon(
                                imageVector = if (selected) tab.selectedIcon
                                else tab.unselectedIcon,
                                contentDescription = tab.label
                            )
                        },
                        label = {
                            Text(
                                text = tab.label,
                                fontWeight = if (selected) FontWeight.Bold
                                else FontWeight.Normal
                            )
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Crossfade(targetState = currentTab, label = "mainTabCrossfade") { tab ->
                when (tab) {
                    NobiTab.DISCOVER -> HomeScreen(
                        repository = repository,
                        downloadManager = downloadManager,
                        settingsStore = settingsStore,
                        onOpenChannel = onOpenChannel,
                        onPlayVideo = onPlayVideo,
                        onOpenContinueAll = onOpenContinueAll,
                        onAddGroup = { showAddSheet = true },
                        onShowMessage = ::showFeedback
                    )
                    NobiTab.CATALOG -> SearchScreen(
                        repository = repository,
                        downloadManager = downloadManager,
                        settingsStore = settingsStore,
                        onPlayVideo = onPlayVideo,
                        onOpenChannel = onOpenChannel,
                        onAddGroupLink = { link ->
                            addGroupLink = link
                            showAddSheet = true
                        },
                        onShowMessage = ::showFeedback
                    )
                    NobiTab.VAULT -> LibraryScreen(
                        repository = repository,
                        downloadManager = downloadManager,
                        settingsStore = settingsStore,
                        onPlayVideo = onPlayVideo,
                        onOpenChannel = onOpenChannel,
                        onShowMessage = ::showFeedback
                    )
                    NobiTab.CONTROL -> ControlScreen(
                        repository = repository,
                        downloadManager = downloadManager,
                        settingsStore = settingsStore,
                        showBack = false,
                        onBack = {},
                        onLogoutDone = {},
                        onShowMessage = ::showFeedback
                    )
                }
            }
        }
    }

    // Add Group sheet (port of MainActivity's sheet: link field +
    // Add button -> repository.addGroup; errors via snackbar, never
    // raw TDLib text).
    if (showAddSheet) {
        ModalBottomSheet(
            onDismissRequest = { if (!addingGroup) showAddSheet = false },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(
                    text = "Add Group",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Paste a Telegram group or channel link.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(14.dp))
                OutlinedTextField(
                    value = addGroupLink,
                    onValueChange = { addGroupLink = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://t.me/…") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    enabled = !addingGroup
                )
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
                ) {
                    TextButton(
                        onClick = { if (!addingGroup) showAddSheet = false },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            val link = addGroupLink.trim()
                            if (link.isEmpty()) {
                                showFeedback("Paste a group link first")
                                return@Button
                            }
                            addingGroup = true
                            scope.launch {
                                val ok = runCatching {
                                    repository.addGroup(link)
                                }.isSuccess
                                addingGroup = false
                                if (ok) {
                                    showAddSheet = false
                                    addGroupLink = ""
                                    showFeedback("Group added")
                                } else {
                                    showFeedback("Couldn't add that link. Check it and try again.")
                                }
                            }
                        },
                        enabled = !addingGroup,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        if (addingGroup) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Add")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(18.dp))
            }
        }
    }
}
