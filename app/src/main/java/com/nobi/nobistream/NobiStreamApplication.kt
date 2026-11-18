package com.nobi.nobistream

import android.app.Application
import com.nobi.nobistream.core.CrashLogger
import com.nobi.nobistream.data.ServiceLocator
import com.nobi.nobistream.ui.compose.theme.applyNightModeFromTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Application entry point. Holds the single [ServiceLocator] used by all
 * screens. Do not add business logic here.
 */
class NobiStreamApplication : Application() {

    lateinit var serviceLocator: ServiceLocator
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        instance = this
        // v8.1: crash logger FIRST — before any other init, so a crash
        // anywhere (even in ServiceLocator init) leaves a trace in
        // Downloads/NobiStream-crash.txt, and the startup marker
        // (Downloads/NobiStream-start.txt) proves the write channel
        // works on this phone.
        CrashLogger.install(this)
        CrashLogger.writeStartupMarker(this)
        serviceLocator = ServiceLocator(this)
        // v2.6.1 (#3): the Views screens (Login, Continue All) follow
        // themeMode through AppCompat's night mode + the split
        // values / values-night palettes. Apply synchronously once
        // at start (DataStore first read is a fast local disk read),
        // then re-apply whenever themeMode changes (a switch in
        // Control recreates activities into the new mode, as
        // standard Android theme switching does). Compose screens
        // resolve themeMode themselves and do not need the recreate;
        // the player forces dark in its own onCreate (cinema).
        runBlocking {
            runCatching {
                applyNightModeFromTheme(serviceLocator.settingsStore.themeMode.first())
            }
        }
        appScope.launch {
            serviceLocator.settingsStore.themeMode.collect { mode ->
                applyNightModeFromTheme(mode)
            }
        }
    }

    companion object {
        lateinit var instance: NobiStreamApplication
            private set
    }
}
