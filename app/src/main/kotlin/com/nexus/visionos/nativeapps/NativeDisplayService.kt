package com.nexus.visionos.nativeapps

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.ActivityManager
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.input.InputManager
import android.os.Binder
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import androidx.annotation.Keep
import com.nexus.xr.NativeApps
import kotlin.system.exitProcess

private const val OWN_PACKAGE = "com.nexus.visionos"
private const val TAG = "NexusNative"
private const val MAX_DISPLAYS = 2

/**
 * Runs in a separate process that Shizuku starts with the shell identity. It only answers this
 * app, only touches displays it created, and never types into system or security screens.
 */
@Keep
@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "BlockedPrivateApi", "SoonBlockedPrivateApi", "WrongConstant")
class NativeDisplayService @Keep constructor(private val context: Context) : INativeDisplayService.Stub() {
    private val displays = HashMap<Int, VirtualDisplay>()
    private val ownerUid = context.packageManager.getApplicationInfo(OWN_PACKAGE, 0).uid

    private fun checkCaller() {
        if (Binder.getCallingUid() != ownerUid) throw SecurityException("Only Nexus may use this service")
    }

    private fun owned(displayId: Int) = displays[displayId] ?: throw SecurityException("Not a Nexus display")

    /** Runs [work] with this process's own identity, not the caller's. */
    private fun <T> asShell(work: () -> T): T {
        val token = Binder.clearCallingIdentity()
        try { return work() } finally { Binder.restoreCallingIdentity(token) }
    }

    @TargetApi(31)
    private class ShellContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName() = "com.android.shell"
        override fun getOpPackageName() = "com.android.shell"
        override fun getAttributionSource(): AttributionSource =
            AttributionSource.Builder(Process.myUid()).setPackageName("com.android.shell").build()
    }

    private val manager: DisplayManager by lazy {
        DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(ShellContext(context))
    }

    private fun checkSize(width: Int, height: Int, dpi: Int) =
        require(width in 200..2400 && height in 200..2400 && dpi in 120..480) { "Unsupported display size" }

    @Synchronized
    override fun create(surface: Surface, width: Int, height: Int, dpi: Int): Int {
        checkCaller()
        checkSize(width, height, dpi)
        check(displays.size < MAX_DISPLAYS) { "Too many native windows" }
        return asShell {
            // Hidden DisplayManager flags, with the values AOSP gives them.
            val basic = 1 or (1 shl 1) or (1 shl 3) or (1 shl 6) or (1 shl 8)
            var trusted = basic or (1 shl 10) or (1 shl 11) or (1 shl 12)
            if (Build.VERSION.SDK_INT >= 34) trusted = trusted or (1 shl 14)
            fun make(flags: Int) = manager.createVirtualDisplay("nexus-native", width, height, dpi, surface, flags)
            val display = checkNotNull(
                // Android 12 refuses a trusted display to the shell; the plain one works with limits.
                try { make(trusted) } catch (e: SecurityException) { Log.w(TAG, "No trusted display: ${e.message}"); make(basic) }
            ) { "Android did not create the display" }
            val id = display.display.displayId
            displays[id] = display
            Log.i(TAG, "display $id created ${width}x$height")
            id
        }
    }

    @Synchronized
    override fun resize(displayId: Int, width: Int, height: Int, dpi: Int, surface: Surface) {
        checkCaller()
        checkSize(width, height, dpi)
        val display = owned(displayId)
        asShell {
            display.resize(width, height, dpi)
            display.surface = surface
        }
    }

    override fun launch(displayId: Int, packageName: String): String {
        checkCaller()
        synchronized(this) { owned(displayId) }
        if (NativeApps.isProtected(packageName, OWN_PACKAGE)) return "protected"
        return asShell {
            // The screen to open comes from Android's package manager, never from the caller.
            val component = context.packageManager.getLaunchIntentForPackage(packageName)?.component
            if (component == null) "no launcher screen" else {
                val process = ProcessBuilder(
                    "/system/bin/am", "start", "-W", "--display", displayId.toString(),
                    "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER",
                    "-n", component.flattenToString()
                ).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                process.waitFor()
                Log.i(TAG, "launch on $displayId finished: ${output.contains("Status: ok")}")
                if (output.contains("Status: ok")) "" else "Android refused to open it"
            }
        }
    }

    /** The app in front on a display; null when it cannot be told, which blocks input. */
    @Suppress("DEPRECATION")
    private fun frontPackage(displayId: Int): String? = runCatching {
        val tasks = context.getSystemService(ActivityManager::class.java).getRunningTasks(40)
        tasks.firstOrNull { task ->
            task.javaClass.getField("displayId").getInt(task) == displayId
        }?.topActivity?.packageName
    }.onFailure { Log.w(TAG, "Cannot read the front app", it) }.getOrNull()

    private fun mayType(displayId: Int): Boolean {
        val front = frontPackage(displayId) ?: return false
        return !NativeApps.isProtected(front, OWN_PACKAGE)
    }

    private val inject by lazy {
        InputManager::class.java.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
    }

    private fun send(event: InputEvent, displayId: Int): Boolean {
        event.javaClass.getMethod("setDisplayId", Int::class.javaPrimitiveType).invoke(event, displayId)
        // Mode 0 does not wait for the app to handle the event.
        return inject.invoke(context.getSystemService(InputManager::class.java), event, 0) as Boolean
    }

    /** Displays with a touch in progress, so its end always arrives. */
    private val touching = HashSet<Int>()

    @Synchronized
    override fun touch(displayId: Int, action: Int, x: Float, y: Float, downTime: Long): Boolean {
        checkCaller()
        owned(displayId)
        require(x.isFinite() && y.isFinite())
        return asShell {
            when (action) {
                MotionEvent.ACTION_DOWN -> if (!mayType(displayId)) return@asShell false else touching.add(displayId)
                MotionEvent.ACTION_MOVE -> if (displayId !in touching) return@asShell false
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (!touching.remove(displayId)) return@asShell false
                else -> return@asShell false
            }
            val finger = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
            val point = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f }
            val event = MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), action, 1, arrayOf(finger), arrayOf(point),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
            )
            try { send(event, displayId) } finally { event.recycle() }
        }
    }

    @Synchronized
    override fun key(displayId: Int, action: Int, keyCode: Int, metaState: Int): Boolean {
        checkCaller()
        owned(displayId)
        // Keys that act on the whole phone are never sent.
        if (keyCode == KeyEvent.KEYCODE_HOME || keyCode == KeyEvent.KEYCODE_POWER || keyCode == KeyEvent.KEYCODE_APP_SWITCH) return false
        return asShell {
            if (!mayType(displayId)) return@asShell false
            val now = SystemClock.uptimeMillis()
            send(
                KeyEvent(now, now, action, keyCode, 0, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD),
                displayId
            )
        }
    }

    @Synchronized
    override fun release(displayId: Int) {
        checkCaller()
        touching.remove(displayId)
        asShell { displays.remove(displayId)?.release() }
    }

    override fun destroy() {
        synchronized(this) { displays.values.forEach { it.release() }; displays.clear() }
        exitProcess(0)
    }
}
