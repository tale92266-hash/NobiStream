package com.nobi.nobistream.ui.settings

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.lifecycleScope
import com.nobi.nobistream.NobiStreamApplication
import com.nobi.nobistream.core.AuthState
import com.nobi.nobistream.ui.compose.screens.ControlScreen
import com.nobi.nobistream.ui.compose.theme.NobiThemeHost
import com.nobi.nobistream.ui.login.LoginActivity
import kotlinx.coroutines.launch

/**
 * v2.6.0: SettingsActivity is now a thin Compose host for
 * [ControlScreen] (ui/compose) — the Control tab content. The v9.9
 * Settings HUB (account card + General / Video / Audio sub-screen
 * rows) is gone; its contents are merged into the Control screen
 * as sections, per spec §5.5. The three sub-screen activities are
 * deleted (their files + layouts + manifest entries are removed
 * in this version).
 *
 * Kept exactly: EXTRA_STANDALONE (standalone opening shows the
 * back button; as a tab it does not), and the logout flow — after
 * [ControlScreen] logs out it calls [onLogoutDone], which routes
 * to Login. (Inside MainActivity's root the same screen passes
 * an empty onLogoutDone and the root's auth-state effect routes
 * to Login instead.)
 *
 * Note: in 2.6.0 Control is normally the 4th tab inside
 * MainActivity's Compose root — this standalone activity only
 * remains for any explicit intent that targets it.
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        /**
         * v8: when true, Settings was opened standalone from Login
         * (not as a bottom-nav tab) — the back button stays visible.
         * Dormant in v9.9 (Login no longer opens Settings), but the
         * mode stays for any standalone opening that remains.
         */
        const val EXTRA_STANDALONE = "standalone"
    }

    private val locator get() = (application as NobiStreamApplication).serviceLocator
    private val repository get() = locator.repository
    private val settingsStore get() = locator.settingsStore
    private val downloadManager get() = locator.downloadManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val showBack = intent.getBooleanExtra(EXTRA_STANDALONE, false)
        setContent {
            // v2.6.1: NobiThemeHost (see ChannelActivity) — the
            // 2.6.0 standalone host had no theme wrapper at all, so
            // Control stayed on the default LIGHT theme in every mode.
            // v2.6.2: statusBarsPadding at the HOST level — the
            // Control list started at y=0 under the status bar when
            // opened standalone. (Inside MainActivity's Scaffold the
            // tab version already gets the inset from innerPadding,
            // so the screen itself stays untouched and is never
            // double-padded.)
            NobiThemeHost(settingsStore = settingsStore) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .statusBarsPadding()
                ) {
                ControlScreen(
                    repository = repository,
                    downloadManager = downloadManager,
                    settingsStore = settingsStore,
                    showBack = showBack,
                    onBack = { finish() },
                    onLogoutDone = {
                        startActivity(Intent(this, LoginActivity::class.java))
                        finish()
                    },
                    onShowMessage = { message ->
                        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                    }
                )
                }
            }
        }
        // Downloads resume (same as MainActivity; guarded — the
        // pump needs TDLib READY, which only holds after login).
        if (locator.gateway.authState.value == AuthState.READY) {
            lifecycleScope.launch {
                downloadManager.resumePending()
                downloadManager.start()
            }
        }
    }
}
