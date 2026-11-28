package com.nexus.visionos

import android.content.Context
import android.os.Build
import com.nexus.xr.CrashText
import java.io.File

/**
 * Crash reports, off until the user turns them on. A report is written to this app's private
 * storage and goes nowhere unless the user shares it. Crashes inside native libraries are not seen.
 */
object Crashes {
    private var installed = false

    private fun file(context: Context) = File(context.applicationContext.filesDir, "crash-report.txt")
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("crashes", Context.MODE_PRIVATE)

    fun isOn(context: Context) = prefs(context).getBoolean("on", false)
    fun setOn(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("on", on).apply()
        if (!on) delete(context)
    }

    @Synchronized fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { if (isOn(app)) file(app).writeText(report(app, error)) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun report(context: Context, error: Throwable): String {
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        return "Nexus Vision OS $version\n" +
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "Time ${java.time.Instant.now()}\n\n" + CrashText.of(error)
    }

    fun read(context: Context): String? = runCatching { file(context).takeIf { it.exists() }?.readText() }.getOrNull()
    fun delete(context: Context) { runCatching { file(context).delete() } }
}
