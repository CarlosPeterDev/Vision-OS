package com.nexus.visionos.xr

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.widget.LinearLayout
import android.widget.TextView
import com.nexus.visionos.R
import com.nexus.visionos.nativeapps.ShizukuLink
import com.nexus.visionos.nativeapps.installedApps
import com.nexus.xr.NativeState

/** Installed Android apps, to open in a window. Setup itself happens on the phone screen. */
internal fun nativeLibrary(context: Context, shell: Shell): SpatialApp {
    lateinit var status: TextView
    lateinit var list: LinearLayout
    val apps by lazy { installedApps(context) }
    var shown: NativeState? = null
    var query = ""
    val search = editor(context).apply { isSingleLine = true; hint = context.getString(R.string.native_search) }
    fun fill() {
        list.removeAllViews()
        if (shown != NativeState.READY) {
            list.key(context.getString(R.string.native_open_setup), tint = 0x5AB0FF) { shell.openNativeSetup() }
            return
        }
        apps.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }.take(80).forEach { app ->
            list.key(app.label) { shell.openNative(app.packageName) }
        }
    }
    fun refresh() {
        val state = ShizukuLink.state(context)
        if (state == shown) return
        shown = state
        status.text = context.getString(if (state == NativeState.READY) R.string.native_library_ready else R.string.native_library_setup)
        search.visibility = if (state == NativeState.READY) android.view.View.VISIBLE else android.view.View.GONE
        fill()
    }
    search.addTextChangedListener(object : TextWatcher {
        override fun afterTextChanged(s: Editable?) { query = s.toString(); fill() }
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    })
    val view = context.vertical {
        status = text(size = 13f)
        addView(search, LinearLayout.LayoutParams(-1, context.px(46)))
        row(height = context.px(50)) {
            key(context.getString(R.string.app_keyboard)) { shell.nativeKeyboard() }
            key(context.getString(R.string.native_open_setup)) { shell.openNativeSetup() }
        }
        val scroll = context.scrolling { list = this }
        addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    }
    val stop = ticker(2000) { refresh() }
    return SpatialApp(view, stop)
}
