package com.nexus.visionos

import android.Manifest
import android.app.ActivityManager
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat

/** Handheld camera preview plus a basic capability report. No spatial tracking yet. */
class PassthroughActivity : ComponentActivity() {
    private lateinit var preview: PreviewView
    private lateinit var info: TextView

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startCamera() else warn(getString(R.string.camera_denied))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preview = PreviewView(this)
        info = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x99000000.toInt())
            setPadding(dp(16), dp(48), dp(16), dp(16))
            text = getString(R.string.safety) + "\n" + capabilities()
        }
        setContentView(FrameLayout(this).apply {
            addView(preview)
            addView(info, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        })
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            runCatching {
                val provider = future.get()
                val useCase = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                provider.unbindAll()
                val camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, useCase)
                camera.cameraInfo.cameraState.observe(this) { state: CameraState ->
                    state.error?.let { warn(getString(R.string.camera_failed, it.code.toString())) }
                }
            }.onFailure { warn(getString(R.string.camera_failed, it.javaClass.simpleName)) }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun warn(message: String) {
        preview.visibility = android.view.View.INVISIBLE // never leave a frozen frame on screen
        info.text = message
    }

    private fun capabilities(): String {
        val sensors = getSystemService(SensorManager::class.java)
        fun has(type: Int) = if (sensors.getDefaultSensor(type) != null) "✓" else "✗"
        val activity = getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also { activity.getMemoryInfo(it) }
        return "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.SUPPORTED_ABIS.firstOrNull()}\n" +
            "OpenGL ES ${activity.deviceConfigurationInfo.glEsVersion} · RAM %.1f GB\n".format(memory.totalMem / 1e9) +
            "Gyroscope ${has(Sensor.TYPE_GYROSCOPE)} · Accelerometer ${has(Sensor.TYPE_ACCELEROMETER)}\n" +
            "Thermal status: ${getSystemService(PowerManager::class.java).currentThermalStatus}"
    }
}
