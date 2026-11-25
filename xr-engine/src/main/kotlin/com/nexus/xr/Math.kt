package com.nexus.xr

import kotlin.math.*

/** World space: right handed, +X right, +Y up, -Z forward, metres. */
data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    val length get() = sqrt(this dot this)
    fun normalized() = length.let { if (it < 1e-6f) this else this * (1f / it) }
}

data class Quat(val w: Float, val x: Float, val y: Float, val z: Float) {
    operator fun times(o: Quat) = Quat(
        w * o.w - x * o.x - y * o.y - z * o.z,
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w
    )
    fun conjugate() = Quat(w, -x, -y, -z)
    fun rotate(v: Vec3): Vec3 {
        val r = this * Quat(0f, v.x, v.y, v.z) * conjugate()
        return Vec3(r.x, r.y, r.z)
    }

    companion object {
        val IDENTITY = Quat(1f, 0f, 0f, 0f)
        fun axisAngle(axis: Vec3, radians: Float): Quat {
            val a = axis.normalized()
            val s = sin(radians / 2)
            return Quat(cos(radians / 2), a.x * s, a.y * s, a.z * s)
        }
    }
}

/**
 * Converts an Android rotation-vector quaternion (device to a Z-up world) into the
 * orientation of a virtual camera held in landscape. [displayDegrees] is 90 or 270.
 */
fun cameraOrientation(device: Quat, displayDegrees: Int): Quat {
    val zUpToYUp = Quat.axisAngle(Vec3(1f, 0f, 0f), (-PI / 2).toFloat())
    val screen = Quat.axisAngle(Vec3(0f, 0f, 1f), Math.toRadians(-displayDegrees.toDouble()).toFloat())
    return zUpToYUp * device * screen
}

/** Heading of a camera orientation, positive to the right. */
fun yawOf(q: Quat): Float {
    val f = q.rotate(Vec3(0f, 0f, -1f))
    // Looking straight up or down, the forward vector has no heading, so use the up vector.
    if (f.x * f.x + f.z * f.z > 0.04f) return atan2(f.x, -f.z)
    val u = q.rotate(Vec3(0f, 1f, 0f)) * (if (f.y < 0) 1f else -1f)
    return atan2(u.x, -u.z)
}

/** Removes [yaw] so that the direction faced at recenter time becomes forward. */
fun recentered(q: Quat, yaw: Float): Quat = Quat.axisAngle(Vec3(0f, 1f, 0f), yaw) * q

fun direction(yaw: Float, pitch: Float) = Vec3(sin(yaw) * cos(pitch), sin(pitch), -cos(yaw) * cos(pitch))

data class Ray(val origin: Vec3, val direction: Vec3)

/** Ray through a viewport point. [nx] and [ny] are in [0,1] with the origin at the top left. */
fun viewportRay(orientation: Quat, nx: Float, ny: Float, fovYDegrees: Float, aspect: Float): Ray {
    val t = tan(Math.toRadians(fovYDegrees / 2.0)).toFloat()
    val local = Vec3((nx * 2 - 1) * t * aspect, (1 - ny * 2) * t, -1f).normalized()
    return Ray(Vec3(0f, 0f, 0f), orientation.rotate(local))
}

/** Maps a point of an image shown with centre-crop into viewport coordinates. */
fun coverMap(x: Float, y: Float, imageAspect: Float, viewAspect: Float): Pair<Float, Float> =
    if (imageAspect > viewAspect) Pair((x - 0.5f) * (imageAspect / viewAspect) + 0.5f, y)
    else Pair(x, (y - 0.5f) * (viewAspect / imageAspect) + 0.5f)
