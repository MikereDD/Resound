/*
 * file:    ResoundApp.kt
 * author:  Typezer∅
 * version: 0.1.0
 * desc:    Application entry; owns the manual DI container.
 */
package com.typezero.resound

import android.app.Application
import com.typezero.resound.BuildConfig
import com.typezero.resound.di.AppContainer

class ResoundApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Startup contract: single process, nothing native has run yet. Must precede the container.
        com.typezero.resound.core.io.ScratchSweeper.sweepAtStartup(cacheDir)
        // A staged update from an earlier process is abandoned or already installed.
        com.typezero.resound.core.update.UpdateStaging.clear(com.typezero.resound.core.update.UpdateStaging.dir(cacheDir))
        if (BuildConfig.IS_DEVTEST) {
            com.typezero.resound.core.DevTrace.sink = { android.util.Log.i("ResoundTrace", "${System.nanoTime() / 1_000_000} ${Thread.currentThread().name} $it") }
            android.os.StrictMode.setThreadPolicy(
                android.os.StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build()
            )
        }
        container = AppContainer(applicationContext)
    }
}
