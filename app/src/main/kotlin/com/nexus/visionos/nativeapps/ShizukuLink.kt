package com.nexus.visionos.nativeapps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.nexus.xr.NativeApps
import com.nexus.xr.NativeState
import com.nexus.xr.ShizukuStatus
import rikka.shizuku.Shizuku

const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

/** An installed app that has a launcher screen. The list never leaves the phone. */
class InstalledApp(val packageName: String, val label: String, val icon: () -> Drawable)

fun installedApps(context: Context): List<InstalledApp> {
    val manager = context.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return manager.queryIntentActivities(launcher, 0)
        .distinctBy { it.activityInfo.packageName }
        .filterNot { NativeApps.isProtected(it.activityInfo.packageName, context.packageName) }
        .map { info -> InstalledApp(info.activityInfo.packageName, info.loadLabel(manager).toString()) { info.loadIcon(manager) } }
        .sortedBy { it.label.lowercase() }
}

fun appLabel(context: Context, packageName: String): String = runCatching {
    val manager = context.packageManager
    manager.getApplicationLabel(manager.getApplicationInfo(packageName, 0)).toString()
}.getOrDefault(packageName)

/** The one connection to Shizuku and to Nexus's own privileged service. Main thread only. */
object ShizukuLink {
    private const val TAG = "NexusNative"
    private val main = Handler(Looper.getMainLooper())
    private var service: INativeDisplayService? = null
    private val waiting = mutableListOf<(INativeDisplayService?) -> Unit>()
    private var args: Shizuku.UserServiceArgs? = null

    /** Told when the service goes away: Shizuku stopped, permission was removed, or it crashed. */
    val lostListeners = mutableSetOf<() -> Unit>()

    fun status(context: Context): ShizukuStatus {
        val installed = runCatching { context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0) }.isSuccess
        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!running) return ShizukuStatus(Build.VERSION.SDK_INT, installed, false)
        // Each call can fail if the service stops in between.
        return runCatching {
            val version = Shizuku.getVersion()
            val granted = !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            val denied = !granted && !Shizuku.isPreV11() && Shizuku.shouldShowRequestPermissionRationale()
            ShizukuStatus(Build.VERSION.SDK_INT, installed, true, version, granted, denied, if (granted) Shizuku.getUid() else -1)
        }.getOrElse { ShizukuStatus(Build.VERSION.SDK_INT, installed, false) }
    }

    fun state(context: Context) = NativeApps.state(status(context))

    /** Shows Shizuku's own permission dialog. */
    fun requestPermission() {
        runCatching { Shizuku.requestPermission(1) }.onFailure { Log.w(TAG, "Permission request failed", it) }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = if (binder.pingBinder()) INativeDisplayService.Stub.asInterface(binder) else null
            Log.i(TAG, "service connected")
            finishWaiting()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Log.w(TAG, "service disconnected")
            service = null
            finishWaiting()
            lostListeners.toList().forEach { it() }
        }
    }

    private fun finishWaiting() {
        val ready = waiting.toList()
        waiting.clear()
        ready.forEach { it(service) }
    }

    /** Hands over the service, starting it first if needed; null when it cannot be had. */
    fun connect(context: Context, done: (INativeDisplayService?) -> Unit) {
        if (state(context) != NativeState.READY) return done(null)
        service?.takeIf { it.asBinder().pingBinder() }?.let { return done(it) }
        waiting += done
        if (waiting.size > 1) return
        val version = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
        val request = Shizuku.UserServiceArgs(ComponentName(context.packageName, NativeDisplayService::class.java.name))
            // The service ends with the app, which releases its displays.
            .daemon(false).processNameSuffix("nativedisplay").version(version)
        args = request
        runCatching { Shizuku.bindUserService(request, connection) }.onFailure {
            Log.e(TAG, "Could not start the service", it)
            finishWaiting()
        }
        main.postDelayed({ if (service == null) finishWaiting() }, 15_000)
    }
}
