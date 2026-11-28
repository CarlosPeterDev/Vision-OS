package com.nexus.visionos.xr

import android.content.Context
import android.widget.LinearLayout
import android.widget.TextView
import com.nexus.visionos.R
import com.nexus.xr.Check
import com.nexus.xr.Outcome

/**
 * A guided check of the camera, hand signals and sound on this phone. What the app can see for
 * itself passes on its own; what only a person can judge is answered with Yes or No.
 */
internal class PhoneTest(private val context: Context, private val shell: Shell) {
    private val report get() = shell.deviceReport
    private val lines = HashMap<Check, TextView>()
    private lateinit var summary: TextView
    private var open = false
    private var busy: Check? = null

    private val names = context.resources.getStringArray(R.array.test_checks)

    fun build(into: LinearLayout) = with(into) {
        lateinit var body: LinearLayout
        row(height = context.px(48)) {
            key(context.getString(R.string.test_title), tint = 0x9AD7FF) {
                open = !open
                body.visibility = if (open) android.view.View.VISIBLE else android.view.View.GONE
                refresh()
            }
        }
        body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addView(body, LinearLayout.LayoutParams(-1, -2))
        body.visibility = android.view.View.GONE
        with(body) {
            text(context.getString(R.string.test_help), 12f)
            fun line(check: Check) { lines[check] = text("", 13f) }
            fun asked(check: Check, before: () -> Unit = {}) {
                line(check)
                row(height = context.px(46)) {
                    key(context.getString(R.string.test_yes), tint = 0x9AFFB0) { before(); answer(check, true) }
                    key(context.getString(R.string.test_no), tint = 0xFF9A9A) { before(); answer(check, false) }
                }
            }
            fun run(check: Check, label: Int, start: ((Boolean?, String) -> Unit) -> Unit) {
                line(check)
                row(height = context.px(46)) {
                    key(context.getString(label)) {
                        busy = check
                        refresh()
                        start { pass, detail ->
                            busy = null
                            if (pass != null) report.set(check, pass, detail)
                            refresh()
                            if (pass == null) lines[check]?.text = detail
                        }
                    }
                }
            }
            line(Check.CAMERA)
            asked(Check.CAMERA_UPRIGHT) { shell.settings.passthrough = true; shell.settingsChanged() }
            line(Check.MOTION)
            line(Check.HAND_SEEN)
            line(Check.HAND_PINCH)
            row(height = context.px(46)) {
                key(context.getString(R.string.test_show_hands)) { shell.settings.showHands = true }
            }
            asked(Check.HAND_OUTLINE)
            run(Check.MICROPHONE, R.string.test_mic_run, shell::testMicrophone)
            row(height = context.px(46)) { key(context.getString(R.string.test_sound_play)) { shell.playTestSound() } }
            asked(Check.SPEAKER)
            run(Check.SPEECH, R.string.test_speech_run, shell::testSpeech)
            row(height = context.px(46)) {
                key(context.getString(R.string.test_open_assistant)) { shell.open(com.nexus.xr.AppType.ASSISTANT) }
            }
            asked(Check.INTERRUPT)
            line(Check.WALK)
            line(Check.HEAT)
            summary = text("", 13f)
            row(height = context.px(48)) {
                key(context.getString(R.string.test_share)) { shell.shareText(report.text(shell.reportHeader())) }
            }
        }
    }

    private fun answer(check: Check, pass: Boolean) {
        val note = if (check == Check.INTERRUPT && !shell.deviceSample().canInterrupt) "no echo canceller" else "answered by user"
        report.set(check, pass, note)
        refresh()
    }

    fun refresh() {
        val now = shell.deviceSample()
        report.observe(Check.CAMERA, now.cameraAgeMs != null && now.cameraAgeMs < 1000, "${now.fps} FPS")
        report.observe(Check.MOTION, now.motion, "sensor sends readings")
        report.observe(Check.HAND_SEEN, now.hands > 0, "delay ${now.handDelayMs} ms")
        report.observe(Check.HAND_PINCH, now.hands > 0 && now.pinching, "pinch detected")
        report.observe(Check.WALK, now.walking && now.walkedMetres >= 0.5f, "moved %.1f m".format(now.walkedMetres))
        // Passes when the phone is no worse than warm while the test is on screen.
        if (open) report.set(Check.HEAT, now.thermalStatus < 3, "Android thermal status ${now.thermalStatus}, ${now.fps} FPS")
        if (!open) return
        for ((check, line) in lines) {
            val result = report.get(check)
            val mark = when {
                busy == check -> "…"
                result.outcome == Outcome.PASS -> "✓"
                result.outcome == Outcome.FAIL -> "✗"
                else -> "○"
            }
            val hint = when {
                check == Check.WALK && !now.walkOn && result.outcome == Outcome.NOT_RUN -> " (" + context.getString(R.string.test_walk_off) + ")"
                result.detail.isNotEmpty() -> " (${result.detail})"
                else -> ""
            }
            val text = "$mark ${names[check.ordinal]}$hint"
            if (line.text.toString() != text) line.text = text
        }
        summary.text = context.getString(
            R.string.test_summary, report.count(Outcome.PASS), report.count(Outcome.FAIL), report.count(Outcome.NOT_RUN)
        )
    }
}
