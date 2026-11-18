package com.nobi.nobistream.ui.login

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.nobi.nobistream.NobiStreamApplication
import com.nobi.nobistream.core.AuthState
import com.nobi.nobistream.ui.home.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * v8 login-flash fix (spec §7): the launcher activity.
 *
 * ROOT CAUSE: the old launcher was LoginActivity, which called
 * setContentView() FIRST and only then ran the session check on a
 * background thread — every cold start rendered the login form for
 * the whole TDLib init + auth-state resolution, and a logged-in
 * user saw the login screen flash before MainActivity opened.
 *
 * FIX: this activity renders NO login UI at all. Its theme paints
 * the splash (navy + centred icon) as the window background, so
 * the first frame is the splash, and the session check runs before
 * any content view exists:
 * - credentials missing → LoginActivity
 * - auth state resolves to READY → MainActivity
 * - anything else (needs phone/code/password) → LoginActivity,
 *   whose own collector routes to Main the moment READY arrives.
 *
 * v8.1 — the v8 timeout bug (Sam, 2026-11-18): v8 waited only
 * 10s for the auth state and then fell through to Login. On the
 * first start after an update the TDLib init + MTProto handshake
 * can take far longer than 10s (Sam's network was 9 KB/s), so a
 * LOGGED-IN user was shown the login form even though the session
 * was intact — the data layer (Room, EncryptedSharedPreferences,
 * TDLib db path) is byte-identical to v7, nothing was wiped. The
 * fix: route on the DEFINITIVE auth state, not on a timeout —
 * wait on the splash until auth resolves (60s ceiling so a
 * genuinely stuck client still falls through to Login, whose
 * collector re-routes to Main when READY arrives). A failed
 * ensureStarted() alone no longer routes anywhere: the client
 * may still be initializing, and any Throwable from the start
 * (incl. Error from Client.create) must never crash the launcher.
 *
 * It never stays in the back stack (finish() on every path +
 * noHistory in the manifest), so Back from Main exits the app.
 */
class LauncherActivity : AppCompatActivity() {

    private val locator get() = (application as NobiStreamApplication).serviceLocator
    private val repository get() = locator.repository

    /**
     * v9.1 #33: Android 12 animated splash (core-splashscreen). The
     * splash stays on screen until the route is decided — the flag
     * flips only in goMain()/goLogin(), so a slow TDLib init still
     * shows the animated icon, never a login flash or a blank frame.
     */
    private var routeDecided = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition { !routeDecided }
        // No setContentView — the splash theme IS the first frame.
        if (!locator.credentialsStore.hasCredentials()) {
            goLogin()
            return
        }
        lifecycleScope.launch {
            // v8.1: kick the client off. A failure here does NOT mean
            // the session is gone — the client may still be
            // initializing — so never crash and never route to Login
            // on this alone; the state wait below decides.
            try {
                withContext(Dispatchers.IO) { repository.ensureStarted() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Client failed to start (incl. native-load Error) —
                // fall through to the state wait; Login's collector
                // will still pick up READY if the client recovers.
            }
            // ensureStarted() only starts the client — the auth state
            // resolves asynchronously after it. Wait for the first
            // real state on the splash; on timeout fall through to
            // Login, which re-routes to Main when READY arrives.
            val state = withTimeoutOrNull(60_000L) {
                repository.authState.first { it != AuthState.UNKNOWN }
            }
            if (state == AuthState.READY) goMain() else goLogin()
        }
    }

    private fun goMain() {
        routeDecided = true
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun goLogin() {
        routeDecided = true
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }
}
