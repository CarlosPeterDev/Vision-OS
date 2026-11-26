package com.nexus.visionos.xr

import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

/** An OpenGL ES 3 config that can also feed a video encoder, with a plain one as fallback. */
class RecordableConfigChooser : GLSurfaceView.EGLConfigChooser {
    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        for (recordable in listOf(true, false)) {
            val attributes = mutableListOf(
                EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8,
                EGL10.EGL_RENDERABLE_TYPE, ES3_BIT
            )
            if (recordable) attributes += listOf(RECORDABLE, 1)
            attributes += EGL10.EGL_NONE
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            egl.eglChooseConfig(display, attributes.toIntArray(), configs, 1, count)
            if (count[0] > 0) return configs[0]!!
        }
        error("No OpenGL ES 3 configuration")
    }

    private companion object {
        const val ES3_BIT = 0x40
        const val RECORDABLE = 0x3142
    }
}
