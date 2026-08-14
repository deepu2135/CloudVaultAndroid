package com.cloudvault.app

import android.app.Application
import android.util.Log

class CloudVaultApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.d("CloudVaultApp", "Application starting...")

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("CloudVaultApp", "Uncaught exception on thread ${thread.name}", throwable)
        }
    }
}
