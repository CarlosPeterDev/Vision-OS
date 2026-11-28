package com.nexus.visionos.xr

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.nexus.xr.AirCursor

/** What a mouse, keyboard or game controller can ask of the shell. */
interface AirActions {
    /** The cursor moved; it stays on screen for a while. */
    fun airMoved()
    fun airButton(down: Boolean)
    fun airScroll(direction: Int)
    fun airBack()
    fun airHome()
    fun airKeyboard()
    fun airRecenter()
    fun airType(text: String)
    fun airKey(code: Int)
    fun airEdit(action: Int)
}

/** Turns Bluetooth or USB mouse, keyboard and game controller events into shell actions. */
class AirInput(private val actions: AirActions) {
    val cursor = AirCursor()
    private var stickX = 0f
    private var stickY = 0f
    private var scrollStick = 0f
    private var scrolledAt = 0L

    fun reset() {
        stickX = 0f; stickY = 0f; scrollStick = 0f
    }

    /** Called every frame, because a held stick sends no new events. */
    fun step(seconds: Float, now: Long) {
        if (AirCursor.curve(stickX) != 0f || AirCursor.curve(stickY) != 0f) {
            cursor.stick(stickX, stickY, seconds)
            actions.airMoved()
        }
        if (kotlin.math.abs(scrollStick) > 0.55f && now - scrolledAt > 350) {
            scrolledAt = now
            actions.airScroll(if (scrollStick > 0) 1 else -1)
        }
    }

    /** A mouse while Android has given this app its pointer; positions are movements. */
    fun mouse(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                var dx = event.x
                var dy = event.y
                for (i in 0 until event.historySize) { dx += event.getHistoricalX(i); dy += event.getHistoricalY(i) }
                cursor.move(dx / MOUSE_WIDTH, dy / MOUSE_HEIGHT)
                actions.airMoved()
            }
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> {
                val down = event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS
                when (event.actionButton) {
                    MotionEvent.BUTTON_PRIMARY -> actions.airButton(down)
                    MotionEvent.BUTTON_SECONDARY -> if (!down) actions.airBack()
                    MotionEvent.BUTTON_TERTIARY -> if (!down) actions.airHome()
                }
            }
            MotionEvent.ACTION_SCROLL -> {
                val wheel = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                if (wheel != 0f) actions.airScroll(if (wheel > 0) -1 else 1)
            }
        }
        return true
    }

    fun joystick(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.actionMasked != MotionEvent.ACTION_MOVE) return false
        stickX = event.getAxisValue(MotionEvent.AXIS_X) + event.getAxisValue(MotionEvent.AXIS_HAT_X)
        stickY = event.getAxisValue(MotionEvent.AXIS_Y) + event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        scrollStick = event.getAxisValue(MotionEvent.AXIS_RZ)
        return true
    }

    /** Returns false for keys the phone itself must keep, such as back and volume. */
    fun key(event: KeyEvent): Boolean {
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN
        val first = down && event.repeatCount == 0
        if (KeyEvent.isGamepadButton(code)) {
            when (code) {
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_R1 -> if (first || !down) actions.airButton(down)
                KeyEvent.KEYCODE_BUTTON_B -> if (first) actions.airBack()
                KeyEvent.KEYCODE_BUTTON_X -> if (first) actions.airKeyboard()
                KeyEvent.KEYCODE_BUTTON_Y -> if (first) actions.airHome()
                KeyEvent.KEYCODE_BUTTON_START -> if (first) actions.airRecenter()
                else -> return false
            }
            return true
        }
        if (code in SYSTEM_KEYS || event.isMetaPressed || event.isAltPressed && !event.isCtrlPressed) return false
        if (event.isCtrlPressed) {
            val action = when (code) {
                KeyEvent.KEYCODE_A -> android.R.id.selectAll
                KeyEvent.KEYCODE_C -> android.R.id.copy
                KeyEvent.KEYCODE_X -> android.R.id.cut
                KeyEvent.KEYCODE_V -> android.R.id.paste
                KeyEvent.KEYCODE_Z -> android.R.id.undo
                else -> return false
            }
            if (first) actions.airEdit(action)
            return true
        }
        if (code == KeyEvent.KEYCODE_ESCAPE) {
            if (first) actions.airBack()
            return true
        }
        if (code in EDIT_KEYS) {
            if (down) actions.airKey(code)
            return true
        }
        // AltGr and dead-key combinations without a character of their own are left alone.
        val typed = event.unicodeChar
        if (typed < 32) return false
        if (down) actions.airType(String(Character.toChars(typed)))
        return true
    }

    private companion object {
        /** Mouse movement, in counts, that crosses the whole view. */
        const val MOUSE_WIDTH = 1400f
        const val MOUSE_HEIGHT = 850f
        val EDIT_KEYS = setOf(
            KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END
        )
        val SYSTEM_KEYS = setOf(
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_CAMERA, KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS
        )
    }
}
