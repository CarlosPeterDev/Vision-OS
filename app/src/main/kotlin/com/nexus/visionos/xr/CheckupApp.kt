package com.nexus.visionos.xr

import android.content.Context
import android.widget.Button
import android.widget.TextView
import com.nexus.visionos.Crashes
import com.nexus.visionos.R

/** What the shell has measured about speed and battery. [drainPerHour] is null until known. */
class Measurements(
    val fps: Int, val averageFps: Int, val lowestFps: Int, val seconds: Int,
    val trackingMs: Long?, val gpu: Boolean,
    val battery: Int, val charging: Boolean, val drainPerHour: Int?, val measuredMinutes: Int,
    val memoryMb: Int, val freeMemoryMb: Int
)

/** Live speed and battery figures, and the crash report the user may choose to share. */
internal fun checkupApp(context: Context, shell: Shell): SpatialApp {
    lateinit var figures: TextView
    lateinit var report: TextView
    lateinit var toggle: Button
    var showing = false
    fun reportStatus(): String {
        val saved = Crashes.read(context)
        return when {
            saved == null -> context.getString(R.string.crash_none)
            showing -> saved.take(4000)
            else -> context.getString(R.string.crash_saved)
        }
    }
    fun toggleLabel() = context.getString(R.string.crash_reports) + ": " +
        context.getString(if (Crashes.isOn(context)) R.string.on else R.string.off)
    val test = PhoneTest(context, shell)
    val view = context.scrolling {
        test.build(this)
        figures = text(size = 14f)
        text(context.getString(R.string.crash_note), 11f).alpha = 0.75f
        row(height = context.px(48)) {
            toggle = key(toggleLabel()) {
                Crashes.setOn(context, !Crashes.isOn(context))
                toggle.text = toggleLabel()
                report.text = reportStatus()
            }
        }
        row(height = context.px(48)) {
            key(context.getString(R.string.crash_view)) { showing = !showing; report.text = reportStatus() }
            key(context.getString(R.string.crash_share)) {
                Crashes.read(context)?.let { shell.shareText(it) }
            }
            key(context.getString(R.string.crash_delete), tint = 0xFF9A9A) {
                Crashes.delete(context)
                showing = false
                report.text = reportStatus()
            }
        }
        report = text(reportStatus(), 11f)
        report.textDirection = android.view.View.TEXT_DIRECTION_FIRST_STRONG
    }
    val stop = ticker(1000) {
        test.refresh()
        val m = shell.measurements()
        val battery = when {
            m.charging -> context.getString(R.string.check_charging)
            m.drainPerHour == null -> context.getString(R.string.check_measuring, m.measuredMinutes)
            else -> context.getString(R.string.check_drain, m.drainPerHour, m.measuredMinutes)
        }
        figures.text = listOf(
            context.getString(R.string.check_fps, m.fps),
            context.getString(R.string.check_fps_history, m.averageFps, m.lowestFps, m.seconds),
            m.trackingMs?.let { context.getString(R.string.check_tracking, it, if (m.gpu) "GPU" else "CPU") }
                ?: context.getString(R.string.check_tracking_none),
            context.getString(R.string.check_battery, m.battery),
            battery,
            context.getString(R.string.check_memory, m.memoryMb, m.freeMemoryMb)
        ).joinToString("\n")
    }
    return SpatialApp(view, stop)
}
