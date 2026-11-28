package com.nexus.xr

/** Where Native Apps setup stands. Each state has one next step. */
enum class NativeState { OLD_ANDROID, INSTALL_SHIZUKU, START_SHIZUKU, UPDATE_SHIZUKU, CONNECT, DENIED, WRONG_IDENTITY, READY }

/** What the phone and Shizuku report. */
data class ShizukuStatus(
    val sdk: Int, val installed: Boolean, val running: Boolean, val version: Int = 0,
    val granted: Boolean = false, val deniedForGood: Boolean = false, val uid: Int = -1
)

object NativeApps {
    /** The platform checks behind this feature were only run from Android 12. */
    const val MIN_SDK = 31
    private const val SHELL_UID = 2000
    private const val MIN_SHIZUKU = 13

    fun state(s: ShizukuStatus): NativeState = when {
        s.sdk < MIN_SDK -> NativeState.OLD_ANDROID
        !s.running -> if (s.installed) NativeState.START_SHIZUKU else NativeState.INSTALL_SHIZUKU
        s.version < MIN_SHIZUKU -> NativeState.UPDATE_SHIZUKU
        !s.granted -> if (s.deniedForGood) NativeState.DENIED else NativeState.CONNECT
        // Root is neither needed nor supported.
        s.uid != SHELL_UID -> NativeState.WRONG_IDENTITY
        else -> NativeState.READY
    }

    private val PROTECTED = setOf(
        "android", "com.android.settings", "com.android.systemui", "moe.shizuku.privileged.api",
        "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.android.packageinstaller", "com.google.android.packageinstaller", "com.samsung.android.packageinstaller",
        "com.miui.packageinstaller", "com.miui.securitycenter", "com.android.keychain", "com.android.certinstaller",
        "com.android.vpndialogs", "com.android.credentialmanager"
    )

    /** System and security screens: never opened in a window and never sent input. */
    fun isProtected(packageName: String, ownPackage: String) = packageName == ownPackage || packageName in PROTECTED

    /**
     * Whether a spoken or AI-chosen "click" may press this spot. Anything that would send input to another
     * app, or open one, needs the person's own hand, finger, mouse or controller.
     */
    fun voiceMayPress(app: AppType, region: Region, typingIntoNative: Boolean): Boolean = when (app) {
        AppType.NATIVE -> region != Region.CONTENT && region != Region.BACK
        AppType.NATIVE_APPS -> region != Region.CONTENT
        AppType.KEYBOARD -> region != Region.CONTENT || !typingIntoNative
        else -> true
    }

    /** The window-local position as a pixel on the app's display. */
    fun pixel(u: Float, size: Int): Float = (u * size).coerceIn(0f, (size - 1).toFloat())

    /** A density that makes the window's short side about as wide as a phone screen, in dp. */
    fun density(width: Int, height: Int): Int = (minOf(width, height) * 160 / 360).coerceIn(120, 320)
}

enum class InstallOutcome { NEEDS_CONFIRMATION, INSTALLED, CANCELLED, BLOCKED, CONFLICT, INCOMPATIBLE, INVALID, NO_SPACE, FAILED }

/** Android's PackageInstaller status codes, in plain terms. */
fun installOutcome(status: Int): InstallOutcome = when (status) {
    -1 -> InstallOutcome.NEEDS_CONFIRMATION
    0 -> InstallOutcome.INSTALLED
    2 -> InstallOutcome.BLOCKED
    3 -> InstallOutcome.CANCELLED
    4 -> InstallOutcome.INVALID
    5 -> InstallOutcome.CONFLICT
    6 -> InstallOutcome.NO_SPACE
    7 -> InstallOutcome.INCOMPATIBLE
    else -> InstallOutcome.FAILED
}
