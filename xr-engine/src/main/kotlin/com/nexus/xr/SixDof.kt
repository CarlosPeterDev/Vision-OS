package com.nexus.xr

/** Where the head is and which way it looks, in the app's world. */
data class Pose6(val orientation: Quat, val position: Vec3)

/**
 * Joins world tracking (position and direction from the camera) with the motion sensor. While
 * tracking works its pose is used. When it is lost, for example against a blank wall, the position
 * stays where it was and the motion sensor keeps turning the view from the last good direction.
 *
 * "Sensor" orientations are the app's ordinary 3DoF ones. The first good tracking result is lined
 * up with them, so nothing jumps when walk-around tracking starts.
 */
class PoseFusion {
    private var lastRaw: Quat? = null
    private var lastPosition = Vec3(0f, 0f, 0f)
    private var origin = Vec3(0f, 0f, 0f)
    private var yaw: Float? = null
    /** Turns a sensor orientation into a tracking one; kept from the last good result. */
    private var correction: Quat? = null

    var tracking = false
        private set

    @Synchronized fun tracked(orientation: Quat, position: Vec3, sensor: Quat) {
        if (yaw == null) {
            yaw = yawOf(orientation) - yawOf(sensor)
            origin = position
        }
        lastRaw = orientation
        lastPosition = position
        correction = orientation * sensor.conjugate()
        tracking = true
    }

    @Synchronized fun lost() { tracking = false }

    @Synchronized fun pose(sensor: Quat): Pose6 {
        val turn = yaw ?: return Pose6(sensor, Vec3(0f, 0f, 0f))
        val raw = if (tracking) lastRaw!! else correction!! * sensor
        val rotation = Quat.axisAngle(Vec3(0f, 1f, 0f), turn)
        return Pose6(rotation * raw, rotation.rotate(lastPosition - origin))
    }

    /** The current spot becomes the middle and the current direction becomes forward. */
    @Synchronized fun recenter(sensor: Quat) {
        val raw = (if (tracking) lastRaw else correction?.let { it * sensor }) ?: return
        yaw = yawOf(raw)
        origin = lastPosition
    }

    /** Distance walked from the middle, in metres. */
    @Synchronized fun distanceFromOrigin() = (lastPosition - origin).length

    @Synchronized fun reset() {
        lastRaw = null; correction = null; yaw = null; tracking = false
        lastPosition = Vec3(0f, 0f, 0f); origin = Vec3(0f, 0f, 0f)
    }
}

/** A 2D affine map, fixed by where it sends (0,0), (1,0) and (0,1). */
data class Affine(val ox: Float, val oy: Float, val ax: Float, val ay: Float, val bx: Float, val by: Float) {
    fun map(x: Float, y: Float) = Pair(ox + ax * x + bx * y, oy + ay * x + by * y)

    companion object {
        /** [p] holds the images of (0,0), (1,0) and (0,1) as x,y pairs. */
        fun fromCorners(p: FloatArray) = Affine(p[0], p[1], p[2] - p[0], p[3] - p[1], p[4] - p[0], p[5] - p[1])

        /**
         * Maps vUv of a full-screen quad to texture coordinates. [p] holds the texture coordinates
         * of the screen corners at normalised device coordinates (-1,-1), (1,-1) and (-1,1).
         * The result is a column-major 4x4 matrix for the shader.
         */
        fun textureMatrix(p: FloatArray): FloatArray {
            val a = fromCorners(p)
            return floatArrayOf(a.ax, a.ay, 0f, 0f, a.bx, a.by, 0f, 0f, 0f, 0f, 1f, 0f, a.ox, a.oy, 0f, 1f)
        }
    }
}

/**
 * A point of a picture that was turned clockwise by [degrees] (0, 90, 180 or 270) to stand upright,
 * given back in the coordinates of the picture before turning. All values run from 0 to 1.
 */
fun beforeTurn(x: Float, y: Float, degrees: Int): Pair<Float, Float> = when (((degrees % 360) + 360) % 360) {
    90 -> Pair(y, 1f - x)
    180 -> Pair(1f - x, 1f - y)
    270 -> Pair(1f - y, x)
    else -> Pair(x, y)
}

/**
 * Converts a YUV 4:2:0 camera picture to ARGB pixels. The planes keep their camera layout:
 * [yStride] and [uvStride] are bytes per row, [uvStep] bytes between neighbouring colour samples.
 */
fun yuvToArgb(
    y: ByteArray, u: ByteArray, v: ByteArray, width: Int, height: Int,
    yStride: Int, uvStride: Int, uvStep: Int, out: IntArray
) {
    var index = 0
    for (row in 0 until height) {
        val yRow = row * yStride
        val uvRow = (row shr 1) * uvStride
        for (column in 0 until width) {
            val chroma = uvRow + (column shr 1) * uvStep
            // Full-range BT.601, in fixed point.
            val luma = (y[yRow + column].toInt() and 255) shl 10
            val cb = (u[chroma].toInt() and 255) - 128
            val cr = (v[chroma].toInt() and 255) - 128
            val r = ((luma + 1436 * cr) shr 10).coerceIn(0, 255)
            val g = ((luma - 352 * cb - 731 * cr) shr 10).coerceIn(0, 255)
            val b = ((luma + 1815 * cb) shr 10).coerceIn(0, 255)
            out[index++] = -0x1000000 or (r shl 16) or (g shl 8) or b
        }
    }
}
