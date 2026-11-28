package com.nexus.visionos.nativeapps

import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.nexus.visionos.R
import com.nexus.visionos.button
import com.nexus.visionos.column
import com.nexus.visionos.label
import com.nexus.visionos.xr.XrActivity
import com.nexus.xr.InstallOutcome
import com.nexus.xr.NativeState
import com.nexus.xr.installOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File
import java.security.MessageDigest

private const val ACTION_INSTALL = "com.nexus.visionos.INSTALL_STATUS"
private const val MAX_APK_BYTES = 1_500_000_000L

/** Native Apps setup, the app list and APK installation, on the ordinary phone screen. */
class NativeAppsActivity : ComponentActivity() {
    private lateinit var checklist: TextView
    private lateinit var next: TextView
    private lateinit var actions: LinearLayout
    private lateinit var appList: LinearLayout
    private lateinit var installNote: TextView
    private var shown: NativeState? = null
    private var query = ""
    private var apps: List<InstalledApp> = emptyList()

    private val received = Shizuku.OnBinderReceivedListener { refresh() }
    private val dead = Shizuku.OnBinderDeadListener { refresh() }
    private val permission = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::review) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_native_apps)
        setContentView(column {
            label(getString(R.string.app_native_apps), 24f)
            label(getString(R.string.native_intro))
            checklist = label(size = 16f)
            next = label(size = 17f)
            actions = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            addView(actions)
            button(getString(R.string.native_install_apk)) {
                picker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
            }
            installNote = label()
            val search = EditText(context).apply { hint = getString(R.string.native_search); isSingleLine = true }
            search.doAfterTextChanged { query = it.toString(); fillApps() }
            addView(search)
            appList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            addView(appList)
            label(getString(R.string.native_help_title), 19f)
            label(getString(R.string.native_help))
        })
        Shizuku.addBinderReceivedListenerSticky(received)
        Shizuku.addBinderDeadListener(dead)
        Shizuku.addRequestPermissionResultListener(permission)
        handleInstall(intent)
    }

    override fun onResume() {
        super.onResume()
        shown = null
        refresh()
        loadApps()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(received)
        Shizuku.removeBinderDeadListener(dead)
        Shizuku.removeRequestPermissionResultListener(permission)
        super.onDestroy()
    }

    private fun loadApps() {
        lifecycleScope.launch {
            apps = withContext(Dispatchers.IO) { installedApps(this@NativeAppsActivity) }
            fillApps()
        }
    }

    private fun refresh(): Unit = runOnUiThread {
        val status = ShizukuLink.status(this)
        val state = com.nexus.xr.NativeApps.state(status)
        Log.i("NexusNative", "setup state $state")
        fun line(done: Boolean, text: Int) = (if (done) "✔ " else "○ ") + getString(text)
        checklist.text = listOf(
            line(status.installed || status.running, R.string.native_check_installed),
            line(status.running, R.string.native_check_running),
            line(status.granted, R.string.native_check_granted),
            line(state == NativeState.READY, R.string.native_check_ready)
        ).joinToString("\n")
        if (state == shown) return@runOnUiThread
        shown = state
        next.setText(
            when (state) {
                NativeState.OLD_ANDROID -> R.string.native_state_old
                NativeState.INSTALL_SHIZUKU -> R.string.native_state_install
                NativeState.START_SHIZUKU -> R.string.native_state_start
                NativeState.UPDATE_SHIZUKU -> R.string.native_state_update
                NativeState.CONNECT -> R.string.native_state_connect
                NativeState.DENIED -> R.string.native_state_denied
                NativeState.WRONG_IDENTITY -> R.string.native_state_identity
                NativeState.READY -> R.string.native_state_ready
            }
        )
        actions.removeAllViews()
        when (state) {
            NativeState.INSTALL_SHIZUKU, NativeState.UPDATE_SHIZUKU -> {
                actions.button(getString(R.string.native_download)) { web("https://shizuku.rikka.app/download/") }
                actions.button(getString(R.string.native_download_github)) { web("https://github.com/RikkaApps/Shizuku/releases") }
            }
            NativeState.START_SHIZUKU -> {
                actions.button(getString(R.string.native_open_shizuku)) { openShizuku() }
                actions.button(getString(R.string.native_developer_settings)) {
                    start(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                }
            }
            NativeState.CONNECT -> actions.button(getString(R.string.native_connect)) { ShizukuLink.requestPermission() }
            NativeState.DENIED -> actions.button(getString(R.string.native_open_shizuku)) { openShizuku() }
            else -> {}
        }
        actions.button(getString(R.string.native_guide)) { web("https://shizuku.rikka.app/guide/setup/") }
        actions.button(getString(R.string.native_check_again)) { shown = null; refresh() }
        fillApps()
    }

    private fun fillApps() {
        appList.removeAllViews()
        if (shown != NativeState.READY) return
        apps.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }.take(150).forEach { app ->
            appList.button(getString(R.string.native_open_in_nexus, app.label)) {
                startActivity(Intent(this, XrActivity::class.java).putExtra(XrActivity.EXTRA_NATIVE, app.packageName))
            }
        }
    }

    private fun start(intent: Intent) {
        runCatching { startActivity(intent) }.onFailure { installNote.setText(R.string.native_cannot_open) }
    }

    private fun web(url: String) = start(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    private fun openShizuku() {
        val intent = packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        if (intent != null) start(intent) else installNote.setText(R.string.native_cannot_open)
    }

    // ---- Installing an APK, always through Android's own confirmation ----

    private class Candidate(val file: File, val info: PackageInfo, val label: String, val signer: String)

    private fun review(uri: Uri) {
        installNote.setText(R.string.native_apk_reading)
        lifecycleScope.launch {
            val candidate = withContext(Dispatchers.IO) { runCatching { read(uri) }.getOrNull() }
            if (candidate == null) return@launch installNote.setText(R.string.install_invalid)
            installNote.text = ""
            val info = candidate.info
            AlertDialog.Builder(this@NativeAppsActivity)
                .setTitle(candidate.label)
                .setMessage(
                    getString(
                        R.string.native_apk_details, info.packageName, info.versionName.orEmpty(),
                        android.text.format.Formatter.formatFileSize(this@NativeAppsActivity, candidate.file.length()),
                        candidate.signer, uri.authority.orEmpty()
                    )
                )
                .setPositiveButton(R.string.native_apk_install) { _, _ -> install(candidate.file) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> candidate.file.delete() }
                .setOnCancelListener { candidate.file.delete() }
                .show()
        }
    }

    /** Copies the file first, so what is described is what gets installed. */
    private fun read(uri: Uri): Candidate? {
        val file = File(cacheDir, "install.apk")
        contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output ->
                val buffer = ByteArray(1 shl 16)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    check(total <= MAX_APK_BYTES) { "File too large" }
                    output.write(buffer, 0, count)
                }
            }
        } ?: return null
        @Suppress("DEPRECATION")
        val info = packageManager.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: return null
        val application = info.applicationInfo ?: return null
        application.sourceDir = file.path
        application.publicSourceDir = file.path
        val signer = info.signingInfo?.apkContentsSigners?.firstOrNull()?.let { certificate ->
            MessageDigest.getInstance("SHA-256").digest(certificate.toByteArray()).joinToString("") { "%02x".format(it) }
        } ?: getString(R.string.native_apk_no_signer)
        return Candidate(file, info, packageManager.getApplicationLabel(application).toString(), signer)
    }

    private fun install(file: File) {
        if (!packageManager.canRequestPackageInstalls()) {
            installNote.setText(R.string.native_apk_allow)
            return start(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        }
        installNote.setText(R.string.native_apk_preparing)
        lifecycleScope.launch {
            val failed = withContext(Dispatchers.IO) {
                runCatching {
                    val installer = packageManager.packageInstaller
                    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    if (Build.VERSION.SDK_INT >= 31) {
                        params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                    }
                    val id = installer.createSession(params)
                    try {
                        installer.openSession(id).use { session ->
                            session.openWrite("base.apk", 0, file.length()).use { output ->
                                file.inputStream().use { it.copyTo(output) }
                                session.fsync(output)
                            }
                            val back = Intent(this@NativeAppsActivity, NativeAppsActivity::class.java).setAction(ACTION_INSTALL)
                            // Android fills in the result, so this explicit intent has to be mutable.
                            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                            session.commit(PendingIntent.getActivity(this@NativeAppsActivity, id, back, flags).intentSender)
                        }
                    } catch (e: Exception) {
                        installer.abandonSession(id)
                        throw e
                    }
                }.also { file.delete() }.isFailure
            }
            if (failed) installNote.setText(R.string.install_failed)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleInstall(intent)
    }

    private fun handleInstall(intent: Intent) {
        if (intent.action != ACTION_INSTALL) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (installOutcome(status)) {
            InstallOutcome.NEEDS_CONFIRMATION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                // Only the system's own confirmation screen is opened.
                val handler = confirm?.resolveActivity(packageManager)?.packageName
                val system = handler != null && runCatching {
                    packageManager.getApplicationInfo(handler, 0).flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
                }.getOrDefault(false)
                if (confirm != null && (system || handler == null)) start(confirm) else installNote.setText(R.string.install_failed)
            }
            InstallOutcome.INSTALLED -> { installNote.setText(R.string.install_done); loadApps() }
            InstallOutcome.CANCELLED -> installNote.setText(R.string.install_cancelled)
            InstallOutcome.BLOCKED -> installNote.setText(R.string.install_blocked)
            InstallOutcome.CONFLICT -> installNote.setText(R.string.install_conflict)
            InstallOutcome.INCOMPATIBLE -> installNote.setText(R.string.install_incompatible)
            InstallOutcome.INVALID -> installNote.setText(R.string.install_invalid)
            InstallOutcome.NO_SPACE -> installNote.setText(R.string.install_space)
            InstallOutcome.FAILED -> installNote.setText(R.string.install_failed)
        }
    }
}
