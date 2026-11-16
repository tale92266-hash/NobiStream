package com.nobi.nobistream

import android.app.Application
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
        serviceLocator = ServiceLocator(this)
    }

    companion object {
        lateinit var instance: NobiStreamApplication
            private set
    }
}
