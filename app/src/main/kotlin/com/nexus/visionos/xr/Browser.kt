package com.nexus.visionos.xr

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.view.KeyEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.nexus.visionos.R
import org.json.JSONArray
import org.json.JSONObject

private const val MAX_TABS = 4

private class Bookmarks(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("bookmarks", Context.MODE_PRIVATE)
    fun all(): List<Pair<String, String>> = runCatching {
        val list = JSONArray(prefs.getString("list", "[]"))
        (0 until list.length()).map { list.getJSONObject(it).let { item -> item.getString("title") to item.getString("url") } }
    }.getOrDefault(emptyList())

    fun contains(url: String) = all().any { it.second == url }

    fun toggle(title: String, url: String) {
        val next = if (contains(url)) all().filter { it.second != url } else (listOf(title to url) + all()).take(100)
        val array = JSONArray()
        next.forEach { array.put(JSONObject().put("title", it.first).put("url", it.second)) }
        prefs.edit().putString("list", array.toString()).apply()
    }
}

/** A small tabbed browser. Web content gets no device permissions and no access to files. */
@SuppressLint("SetJavaScriptEnabled")
internal fun browser(context: Context, shell: Shell): SpatialApp {
    val bookmarks = Bookmarks(context)
    val tabs = mutableListOf<WebView>()
    var current = 0
    val address = editor(context).apply { isSingleLine = true; hint = context.getString(R.string.address_hint) }
    val pages = FrameLayout(context)
    lateinit var strip: LinearLayout
    lateinit var notice: LinearLayout
    lateinit var starButton: View
    val web = { tabs[current] }

    fun refreshStar() {
        val saved = bookmarks.contains(web().url.orEmpty())
        starButton.alpha = if (saved) 1f else 0.55f
        starButton.isSelected = saved
    }

    fun go(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        val url = when {
            text.startsWith("https://") -> text
            text.startsWith("http://") -> "https://" + text.removePrefix("http://")
            '.' in text && ' ' !in text -> "https://$text"
            else -> "https://www.google.com/search?q=" + Uri.encode(text)
        }
        notice.visibility = View.GONE
        web().loadUrl(url)
        web().requestFocus()
    }

    /** A bar inside the window that asks before anything is saved to the phone. */
    fun ask(message: String, confirm: Int, action: (() -> Unit)?) {
        notice.removeAllViews()
        notice.text(message, 13f).apply { maxLines = 2; layoutParams = LinearLayout.LayoutParams(0, -2, 3f) }
        if (action != null) notice.key(context.getString(confirm), 1.2f, 0x5AB0FF) { notice.visibility = View.GONE; action() }
        notice.key(context.getString(R.string.dismiss), 1f) { notice.visibility = View.GONE }
        notice.visibility = View.VISIBLE
    }

    fun download(url: String, agent: String, disposition: String?, mime: String?) {
        val uri = Uri.parse(url)
        if (uri.scheme != "https") return ask(context.getString(R.string.download_unsupported), 0, null)
        val name = URLUtil.guessFileName(url, disposition, mime).replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120)
        ask(context.getString(R.string.download_question, name, uri.host.orEmpty()), R.string.download) {
            val queued = runCatching {
                val request = DownloadManager.Request(uri)
                    .setTitle(name)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                    .addRequestHeader("User-Agent", agent)
                CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
                context.applicationContext.getSystemService(DownloadManager::class.java).enqueue(request)
            }.isSuccess
            ask(context.getString(if (queued) R.string.download_started else R.string.download_failed), 0, null)
        }
    }

    fun newWeb() = WebView(context).also { view ->
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
        }
        view.webViewClient = object : WebViewClient() {
            // Only web pages stay inside the browser; app links and other schemes are ignored.
            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest) = request.url.scheme != "https"
            override fun onPageFinished(v: WebView, url: String) {
                if (v === web()) { if (!address.hasFocus()) address.setText(url); refreshStar() }
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) = request.deny()
        }
        view.setDownloadListener { url, agent, disposition, mime, _ -> download(url, agent, disposition, mime) }
    }

    fun show(index: Int) {
        current = index
        pages.removeAllViews()
        pages.addView(web(), FrameLayout.LayoutParams(-1, -1))
        address.setText(web().url.orEmpty())
        refreshStar()
        strip.removeAllViews()
        tabs.indices.forEach { i ->
            strip.key(context.getString(R.string.tab_number, i + 1), 1.3f, if (i == current) 0x7FC4FF else 0xFFFFFF) { show(i) }
        }
        if (tabs.size < MAX_TABS) strip.iconKey(R.drawable.ic_ui_nearer) {
            tabs += newWeb()
            show(tabs.lastIndex)
            go("https://www.google.com")
        }
        if (tabs.size > 1) strip.iconKey(R.drawable.ic_ui_close) {
            val old = tabs.removeAt(current)
            pages.removeView(old)
            old.destroy()
            show(current.coerceAtMost(tabs.lastIndex))
        }
    }

    fun showBookmarks() {
        val saved = bookmarks.all()
        if (saved.isEmpty()) return ask(context.getString(R.string.no_bookmarks), 0, null)
        val list = context.scrolling {
            saved.forEach { (title, url) ->
                key(title.ifBlank { url }) { pages.removeAllViews(); pages.addView(web(), FrameLayout.LayoutParams(-1, -1)); go(url) }
            }
            key(context.getString(R.string.dismiss), tint = 0x5AB0FF) {
                pages.removeAllViews(); pages.addView(web(), FrameLayout.LayoutParams(-1, -1))
            }
        }
        list.background = LiquidGlass(0x101520, 0xE8, 0x66, context.px(18).toFloat())
        pages.addView(list, FrameLayout.LayoutParams(-1, -1))
    }

    address.setOnKeyListener { _, code, event ->
        if (code == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP) go(address.text.toString())
        code == KeyEvent.KEYCODE_ENTER
    }
    val view = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        row(height = context.px(48)) { strip = this; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        row(height = context.px(50)) {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            iconKey(R.drawable.ic_ui_back) { if (web().canGoBack()) web().goBack() }
            iconKey(R.drawable.ic_ui_forward) { if (web().canGoForward()) web().goForward() }
            iconKey(R.drawable.ic_ui_reload) { web().reload() }
            addView(address, LinearLayout.LayoutParams(0, -1, 5f))
            iconKey(R.drawable.ic_ui_go) { go(address.text.toString()) }
            starButton = iconKey(R.drawable.ic_ui_star) {
                val url = web().url ?: return@iconKey
                bookmarks.toggle(web().title.orEmpty(), url)
                refreshStar()
            }
            iconKey(R.drawable.ic_ui_files) { showBookmarks() }
            iconKey(R.drawable.ic_ui_keyboard) { shell.open(com.nexus.xr.AppType.KEYBOARD) }
        }
        row { notice = this; visibility = View.GONE }
        addView(pages, LinearLayout.LayoutParams(-1, 0, 1f))
    }
    tabs += newWeb()
    show(0)
    go(shell.pendingUrl() ?: "https://www.google.com")
    // The shell reaches an already open browser through the root view's tag.
    view.tag = { url: String -> go(url) }
    return SpatialApp(view) { tabs.forEach { it.stopLoading(); it.destroy() }; tabs.clear() }
}
