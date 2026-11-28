package com.nexus.xr

import kotlin.math.atan
import kotlin.math.tan

/**
 * Vertical field of view of one eye picture: the 4:3 camera image after it is centre-cropped to
 * [aspect]. The camera view and the virtual places use the same value, so windows keep the same
 * size on screen when the background changes.
 */
fun croppedFov(cameraFov: Float, aspect: Float): Float {
    val crop = if (aspect > 4f / 3f) (4f / 3f) / aspect else 1f
    return Math.toDegrees(2.0 * atan(tan(Math.toRadians(cameraFov / 2.0)) * crop)).toFloat()
}
