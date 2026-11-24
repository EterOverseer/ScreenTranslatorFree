package com.example.app

import android.app.Application

class EterScreenApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Note: App Check is intentionally NOT initialized here; it is wired in MainActivity.onCreate()
        // with the launch Intent so debug tokens are preserved.
        container = AppContainer(this)
    }
}
