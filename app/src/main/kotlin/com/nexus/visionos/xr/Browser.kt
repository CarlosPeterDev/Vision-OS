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
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.nexus.visionos.R
import com.nexus.xr.Act
import org.json.JSONArray
import org.json.JSONObject

private const val MAX_TABS = 4
private const val MAX_HISTORY = 200

private val SEARCH_ENGINES = listOf(
    "Google" to "https://www.google.com/search?q=",
    "DuckDuckGo" to "https://duckduckgo.com/?q=",
    "Bing" to "https://www.bing.com/search?q=",
    "Brave" to "https://search.brave.com/search?q="
)
private val ENGINE_HOMES = listOf("https://www.google.com", "https://duckduckgo.com", "https://www.bing.com", "https://search.brave.com")

fun searchUrl(index: Int, query: String) = SEARCH_ENGINES[index.coerceIn(SEARCH_ENGINES.indices)].second + Uri.encode(query)

fun searchEngineName(index: Int) = SEARCH_ENGINES[index.coerceIn(SEARCH_ENGINES.indices)].first

/** What the browser keeps on this phone between launches, apart from the web engine's own storage. */
object BrowserData {
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("browser", Context.MODE_PRIVATE)

    internal fun pairs(context: Context, key: String): List<Pair<String, String>> = runCatching {
        val list = JSONArray(prefs(context).getString(key, "[]"))
        (0 until list.length()).map { list.getJSONObject(it).let { item -> item.getString("title") to item.getString("url") } }
    }.getOrDefault(emptyList())

    private fun save(context: Context, key: String, items: List<Pair<String, String>>) {
        val array = JSONArray()
        items.forEach { array.put(JSONObject().put("title", it.first).put("url", it.second)) }
        prefs(context).edit().putString(key, array.toString()).apply()
    }

    internal fun visited(context: Context, title: String, url: String) =
        save(context, "history", (listOf(title to url) + pairs(context, "history").filter { it.second != url }).take(MAX_HISTORY))

    internal fun openTabs(context: Context) = pairs(context, "tabs").map { it.second }
    internal fun saveTabs(context: Context, urls: List<String>) = save(context, "tabs", urls.map { "" to it })

    fun clearHistory(context: Context) = prefs(context).edit().remove("history").remove("tabs").apply()

    /** Signs out of every website and forgets what they stored. Bookmarks are kept. */
    fun clearAll(context: Context) {
        clearHistory(context)
        CookieManager.getInstance().apply { removeAllCookies(null); flush() }
        WebStorage.getInstance().deleteAllData()
        WebView(context.applicationContext).apply { clearCache(true); clearFormData(); destroy() }
    }
}

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

    // A page's full screen video (HTML fullscreen) covers the browser inside its own window.
    lateinit var chrome: View
    val stage = FrameLayout(context).apply { setBackgroundColor(android.graphics.Color.BLACK); visibility = View.GONE }
    var fullscreen: WebChromeClient.CustomViewCallback? = null

    /** Safe to call at any time. [tellPage] is false when the page itself asked to leave. */
    fun leaveFullscreen(tellPage: Boolean = true): Boolean {
        val callback = fullscreen ?: return false
        // Cleared first: telling the page may call back into onHideCustomView.
        fullscreen = null
        stage.removeAllViews()
        stage.visibility = View.GONE
        chrome.visibility = View.VISIBLE
        if (tellPage) callback.onCustomViewHidden()
        return true
    }

    fun enterFullscreen(video: View, callback: WebChromeClient.CustomViewCallback) {
        if (fullscreen != null) return callback.onCustomViewHidden()
        fullscreen = callback
        stage.addView(video, FrameLayout.LayoutParams(-1, -1, android.view.Gravity.CENTER))
        val exit = android.widget.Button(context).apply {
            text = context.getString(R.string.exit_fullscreen)
            isAllCaps = false
            setTextColor(-1)
            background = glassStates(0xFFFFFF, radius = context.px(18).toFloat())
            setOnClickListener { leaveFullscreen() }
        }
        stage.addView(exit, FrameLayout.LayoutParams(-2, context.px(40), android.view.Gravity.TOP or android.view.Gravity.END))
        // Kept attached while hidden, so the page and its playback carry on.
        chrome.visibility = View.INVISIBLE
        stage.visibility = View.VISIBLE
    }

    fun refreshStar() {
        val saved = bookmarks.contains(web().url.orEmpty())
        starButton.alpha = if (saved) 1f else 0.55f
        starButton.isSelected = saved
    }

    fun go(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        leaveFullscreen()
        val url = when {
            text.startsWith("https://") -> text
            text.startsWith("http://") -> "https://" + text.removePrefix("http://")
            '.' in text && ' ' !in text -> "https://$text"
            else -> SEARCH_ENGINES[shell.settings.searchEngine].second + Uri.encode(text)
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

    fun home() = shell.settings.homePage.ifBlank { ENGINE_HOMES[shell.settings.searchEngine] }

    /** Cookies keep the user signed in; they are written to storage right away. */
    fun persist() {
        CookieManager.getInstance().flush()
        if (shell.settings.restoreTabs) {
            BrowserData.saveTabs(context, tabs.mapNotNull { it.url?.takeIf { url -> url.startsWith("https://") } })
        }
    }

    fun newWeb() = WebView(context).also { view ->
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, shell.settings.thirdPartyCookies)
        view.settings.apply {
            textZoom = shell.settings.webTextZoom
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            if (shell.settings.desktopSites) {
                // The same engine name without the mobile markers, which asks sites for their desktop pages.
                userAgentString = userAgentString.replace(Regex("\\(Linux; Android[^)]*\\)"), "(X11; Linux x86_64)").replace(" Mobile ", " ")
            }
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
                if (shell.settings.saveHistory && url.startsWith("https://")) BrowserData.visited(context, v.title.orEmpty(), url)
                persist()
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) = request.deny()
            override fun onShowCustomView(video: View, callback: CustomViewCallback) {
                // A tab in the background may not take over the window.
                if (view === web()) enterFullscreen(video, callback) else callback.onCustomViewHidden()
            }
            override fun onHideCustomView() { leaveFullscreen(tellPage = false) }
        }
        view.setDownloadListener { url, agent, disposition, mime, _ -> download(url, agent, disposition, mime) }
    }

    fun show(index: Int) {
        leaveFullscreen()
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
            go(home())
        }
        if (tabs.size > 1) strip.iconKey(R.drawable.ic_ui_close) {
            leaveFullscreen()
            val old = tabs.removeAt(current)
            pages.removeView(old)
            old.destroy()
            show(current.coerceAtMost(tabs.lastIndex))
            persist()
        }
    }

    fun showList(saved: List<Pair<String, String>>, empty: Int) {
        if (saved.isEmpty()) return ask(context.getString(empty), 0, null)
        fun restore() { pages.removeAllViews(); pages.addView(web(), FrameLayout.LayoutParams(-1, -1)) }
        val list = context.scrolling {
            key(context.getString(R.string.dismiss), tint = 0x5AB0FF) { restore() }
            saved.forEach { (title, url) -> key(title.ifBlank { url }) { restore(); go(url) } }
        }
        list.background = LiquidGlass(0x101520, 0xE8, 0x66, context.px(18).toFloat())
        pages.addView(list, FrameLayout.LayoutParams(-1, -1))
    }
    fun showBookmarks() = showList(bookmarks.all(), R.string.no_bookmarks)

    fun spoken(act: Act): Boolean {
        // "Back" first closes a full screen video; any other browser command closes it and carries on.
        if (leaveFullscreen() && act == Act.BACK) return true
        when (act) {
            Act.BACK -> { if (!web().canGoBack()) return false; web().goBack() }
            Act.FORWARD -> { if (!web().canGoForward()) return false; web().goForward() }
            Act.RELOAD -> web().reload()
            Act.STOP_LOADING -> web().stopLoading()
            Act.NEW_TAB -> {
                if (tabs.size >= MAX_TABS) return false
                tabs += newWeb(); show(tabs.lastIndex); go(home())
            }
            Act.CLOSE_TAB -> {
                if (tabs.size < 2) return false
                val old = tabs.removeAt(current)
                pages.removeView(old); old.destroy(); show(current.coerceAtMost(tabs.lastIndex)); persist()
            }
            Act.NEXT_TAB -> show((current + 1) % tabs.size)
            Act.PREVIOUS_TAB -> show((current + tabs.size - 1) % tabs.size)
            Act.BOOKMARK -> {
                val url = web().url ?: return false
                if (!bookmarks.contains(url)) bookmarks.toggle(web().title.orEmpty(), url)
                refreshStar()
            }
            Act.SHOW_BOOKMARKS -> showBookmarks()
            Act.SHOW_HISTORY -> showList(BrowserData.pairs(context, "history"), R.string.no_history)
            Act.ZOOM_IN -> web().zoomIn()
            Act.ZOOM_OUT -> web().zoomOut()
            Act.HOME_PAGE -> go(home())
            else -> return false
        }
        return true
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
            iconKey(R.drawable.ic_ui_clock) { spoken(Act.SHOW_HISTORY) }
            iconKey(R.drawable.ic_ui_keyboard) { shell.open(com.nexus.xr.AppType.KEYBOARD) }
        }
        row { notice = this; visibility = View.GONE }
        addView(pages, LinearLayout.LayoutParams(-1, 0, 1f))
    }
    val wanted = shell.pendingUrl()
    val saved = if (shell.settings.restoreTabs) BrowserData.openTabs(context).take(MAX_TABS) else emptyList()
    if (saved.isEmpty()) {
        tabs += newWeb()
        show(0)
        go(wanted ?: home())
    } else {
        saved.forEach { url -> tabs += newWeb().also { it.loadUrl(url) } }
        if (wanted != null && tabs.size < MAX_TABS) tabs += newWeb()
        show(if (wanted != null) tabs.lastIndex else 0)
        if (wanted != null) go(wanted)
    }
    chrome = view
    val root = FrameLayout(context).apply {
        addView(view, FrameLayout.LayoutParams(-1, -1))
        addView(stage, FrameLayout.LayoutParams(-1, -1))
    }
    // The shell reaches an already open browser through the root view's tag.
    root.tag = { url: String -> go(url) }
    return SpatialApp(root) {
        leaveFullscreen()
        persist()
        tabs.forEach { it.stopLoading(); it.destroy() }
        tabs.clear()
    }.also {
        it.command = ::spoken
        it.flush = ::persist
        it.back = { leaveFullscreen() }
        it.beforeReshape = { leaveFullscreen() }
    }
}
