package com.nobi.nobistream

import android.app.Application
import com.nobi.nobistream.core.CrashLogger
import com.nobi.nobistream.data.ServiceLocator

/**
 * Application entry point. Holds the single [ServiceLocator] used by all
 * screens. Do not add business logic here.
 */
class NobiStreamApplication : Application() {

    lateinit var serviceLocator: ServiceLocator
        private set

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
    }

    companion object {
        lateinit var instance: NobiStreamApplication
            private set
    }
}
