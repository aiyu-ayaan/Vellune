package com.aiyu.vellune

import android.app.Application
import android.content.Context
import com.aiyu.vellune.data.CanvasRepository
import com.aiyu.vellune.data.SettingsRepository
import com.aiyu.vellune.data.TraceRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** Manual DI: one instance of each repository for the process. */
class AppContainer(context: Context) {
    /** Outlives screens, so a save started on exit still finishes. */
    val appScope = CoroutineScope(SupervisorJob())
    val settings = SettingsRepository(context)
    val canvases = CanvasRepository(File(context.filesDir, "canvases"), appScope)

    // External app storage so traces can be pulled with adb without root.
    val traces = TraceRecorder(context.getExternalFilesDir("traces") ?: File(context.filesDir, "traces"))
}

class VelluneApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

val Context.container: AppContainer get() = (applicationContext as VelluneApp).container
