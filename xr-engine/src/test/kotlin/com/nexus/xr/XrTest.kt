package com.nexus.xr

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class XrTest {
    private fun near(a: Vec3, b: Vec3) = assertTrue("$a != $b", (a - b).length < 1e-3f)
    private val forward = Ray(Vec3(0f, 0f, 0f), Vec3(0f, 0f, -1f))
    private fun ray(yaw: Float, pitch: Float) = Ray(Vec3(0f, 0f, 0f), direction(yaw, pitch))

    private class Recorder : InteractionListener {
        val touches = mutableListOf<TouchAction>(); val closed = mutableListOf<Int>()
        override fun onContentTouch(windowId: Int, action: TouchAction, u: Float, v: Float) { touches += action }
        override fun onClosed(windowId: Int) { closed += windowId }
    }

    @Test fun quaternionRotation() {
        near(Vec3(0f, 0f, -1f), Quat.axisAngle(Vec3(0f, 1f, 0f), (PI / 2).toFloat()).rotate(Vec3(1f, 0f, 0f)))
    }

    @Test fun uprightLandscapePhoneLooksForward() {
        // Display 90: top of the phone to the left, screen facing the user who looks north.
        // Device axes in the Android world: x = up (+Z), y = west (-X), z = south (-Y).
        // That is the rotation matrix with columns (0,0,1), (-1,0,0), (0,-1,0).
        val device = Quat(0.5f, 0.5f, -0.5f, 0.5f)
        near(Vec3(0f, 0f, 1f), device.rotate(Vec3(1f, 0f, 0f)))
        near(Vec3(-1f, 0f, 0f), device.rotate(Vec3(0f, 1f, 0f)))
        val camera = cameraOrientation(device, 90)
        near(Vec3(0f, 0f, -1f), camera.rotate(Vec3(0f, 0f, -1f)))
        near(Vec3(0f, 1f, 0f), camera.rotate(Vec3(0f, 1f, 0f)))
        near(Vec3(1f, 0f, 0f), camera.rotate(Vec3(1f, 0f, 0f)))
    }

    @Test fun otherLandscapeAlsoUpright() {
        // Device axes: x = down, y = east, z = south.
        val device = Quat(0.5f, 0.5f, 0.5f, -0.5f)
        near(Vec3(0f, 0f, -1f), device.rotate(Vec3(1f, 0f, 0f)))
        near(Vec3(1f, 0f, 0f), device.rotate(Vec3(0f, 1f, 0f)))
        val camera = cameraOrientation(device, 270)
        near(Vec3(0f, 0f, -1f), camera.rotate(Vec3(0f, 0f, -1f)))
        near(Vec3(0f, 1f, 0f), camera.rotate(Vec3(0f, 1f, 0f)))
    }

    @Test fun recenterMakesHeadingForward() {
        val turned = Quat.axisAngle(Vec3(0f, 1f, 0f), -1.2f)
        assertEquals(1.2f, yawOf(turned), 1e-3f)
        assertEquals(0f, yawOf(recentered(turned, yawOf(turned))), 1e-3f)
    }

    @Test fun viewportRayAndCrop() {
        near(Vec3(0f, 0f, -1f), viewportRay(Quat.IDENTITY, 0.5f, 0.5f, 60f, 2f).direction)
        assertTrue(viewportRay(Quat.IDENTITY, 1f, 0f, 60f, 2f).direction.let { it.x > 0 && it.y > 0 })
        assertEquals(Pair(0.5f, 0.5f), coverMap(0.5f, 0.5f, 1.33f, 2f))
        assertTrue(coverMap(0.5f, 0f, 1.33f, 2f).second < 0f)
    }

    @Test fun hitRegions() {
        val w = SpatialWindow(1, AppType.NOTES, 0f, 0f, 2f, 1f, 0.6f)
        fun at(x: Float, y: Float) = hitTest(Ray(Vec3(0f, 0f, 0f), Vec3(x, y, -2f).normalized()), w)?.region
        assertEquals(Region.CONTENT, at(0f, 0f))
        assertEquals(Region.TITLE, at(-0.3f, 0.33f))
        assertEquals(Region.CLOSE, at(0.47f, 0.33f))
        assertEquals(Region.MINIMIZE, at(0.39f, 0.33f))
        assertEquals(Region.RESIZE, at(0.45f, -0.34f))
        assertNull(at(0.7f, 0f))
        assertNull(hitTest(Ray(Vec3(0f, 0f, 0f), Vec3(0f, 0f, 1f)), w))
        val topLeft = hitTest(Ray(Vec3(0f, 0f, 0f), Vec3(-0.49f, 0.29f, -2f).normalized()), w)!!
        assertTrue(topLeft.u < 0.05f && topLeft.v < 0.05f)
    }

    @Test fun nearestWindowWinsAndSingleInstance() {
        val m = WindowManager()
        val a = m.open(AppType.NOTES, 0f)
        val b = m.open(AppType.CLOCK, 0f)
        m.replace(b.copy(yaw = 0f, distance = 1.2f))
        m.focus(a.id)
        assertEquals(b.id, m.pick(ray(0f, 0.02f))!!.windowId)
        assertEquals(a.id, m.open(AppType.NOTES, 0f).id)
        assertEquals(2, m.windows.size)
        m.minimize(b.id)
        assertEquals(a.id, m.pick(ray(0f, 0.02f))!!.windowId)
    }

    @Test fun windowLimitEvicts() {
        val m = WindowManager(maxWindows = 2)
        val home = m.open(AppType.HOME, 0f); val notes = m.open(AppType.NOTES, 0f)
        val gone = mutableListOf<Int>()
        m.open(AppType.CLOCK, 0f) { gone += it }
        assertEquals(listOf(notes.id), gone); assertNotNull(m.get(home.id))
    }

    @Test fun contentTouchSequenceAndCancel() {
        val m = WindowManager(); val r = Recorder(); val c = InteractionController(m, r)
        m.open(AppType.NOTES, 0f)
        c.handle(1, PointerPhase.DOWN, ray(0f, 0.02f)); c.handle(1, PointerPhase.MOVE, ray(1.0f, 0f)); c.handle(1, PointerPhase.UP, ray(1f, 0f))
        c.handle(1, PointerPhase.DOWN, ray(0f, 0.02f)); c.cancelAll()
        assertEquals(listOf(TouchAction.DOWN, TouchAction.MOVE, TouchAction.UP, TouchAction.DOWN, TouchAction.CANCEL), r.touches)
    }

    @Test fun dragMovesAndTwoHandsScale() {
        val m = WindowManager(); val c = InteractionController(m, Recorder())
        val w = m.open(AppType.NOTES, 0f)
        val bar = Ray(Vec3(0f, 0f, 0f), w.point(-0.2f, w.h / 2 + 0.03f).normalized())
        c.handle(1, PointerPhase.DOWN, bar)
        c.handle(1, PointerPhase.MOVE, Ray(bar.origin, Quat.axisAngle(Vec3(0f, 1f, 0f), -0.4f).rotate(bar.direction)))
        assertEquals(0.4f, m.get(w.id)!!.yaw, 0.02f)
        c.handle(2, PointerPhase.DOWN, ray(0f, -0.1f))
        c.handle(2, PointerPhase.MOVE, ray(-0.5f, -0.3f))
        assertTrue(m.get(w.id)!!.scale > 1.1f)
        c.handle(2, PointerPhase.UP, ray(-0.5f, -0.3f))
        assertFalse(c.isCapturing(1))
    }

    @Test fun closeNeedsReleaseOnSameButton() {
        val m = WindowManager(); val r = Recorder(); val c = InteractionController(m, r)
        val w = m.open(AppType.NOTES, 0f)
        val close = Ray(Vec3(0f, 0f, 0f), w.point(w.w / 2 - 0.03f, w.h / 2 + 0.035f).normalized())
        c.handle(1, PointerPhase.DOWN, close); c.handle(1, PointerPhase.UP, ray(0f, 0f))
        assertNotNull(m.get(w.id))
        c.handle(1, PointerPhase.DOWN, close); c.handle(1, PointerPhase.UP, close)
        assertNull(m.get(w.id)); assertEquals(listOf(w.id), r.closed)
    }

    private fun hand(gap: Float, open: Boolean = false): Hand {
        val p = MutableList(21) { Point(0.5f, 0.6f) }
        p[0] = Point(0.5f, 0.8f); p[9] = Point(0.5f, 0.6f); p[5] = Point(0.45f, 0.6f); p[17] = Point(0.58f, 0.62f)
        p[4] = Point(0.45f, 0.5f); p[8] = Point(0.45f + gap, 0.5f)
        if (open) {
            p[8] = Point(0.45f, 0.35f); p[4] = Point(0.25f, 0.6f)
            for ((tip, mid) in listOf(12 to 10, 16 to 14, 20 to 18)) { p[tip] = Point(0.5f, 0.3f); p[mid] = Point(0.5f, 0.5f) }
            p[6] = Point(0.45f, 0.5f)
        }
        return Hand(0, p)
    }

    @Test fun pinchIsDebouncedWithHysteresis() {
        val e = GestureEngine(); var t = 0L
        fun step(gap: Float) = e.update(listOf(hand(gap)), 1f, 33L.let { t += it; t }).single().phase
        assertEquals(PointerPhase.HOVER, step(0.2f))
        assertEquals(PointerPhase.HOVER, step(0.02f))
        assertEquals(PointerPhase.DOWN, step(0.02f))
        assertEquals(PointerPhase.MOVE, step(0.07f)) // between press and release thresholds
        assertEquals(PointerPhase.MOVE, step(0.2f))  // a single noisy frame
        assertEquals(PointerPhase.MOVE, step(0.02f))
        step(0.2f); assertEquals(PointerPhase.UP, step(0.2f))
    }

    @Test fun lostHandCancelsInsteadOfClicking() {
        val e = GestureEngine()
        e.update(listOf(hand(0.02f)), 1f, 10); e.update(listOf(hand(0.02f)), 1f, 40)
        assertTrue(e.update(emptyList(), 1f, 100).isEmpty())
        assertEquals(PointerPhase.CANCEL, e.update(emptyList(), 1f, 400).single().phase)
    }

    @Test fun openPalmHeldOnce() {
        assertTrue(GestureEngine().update(listOf(hand(0f, open = true)), 1f, 1).single().openPalm)
        val hold = PalmHold(500)
        assertFalse(hold.update(true, 0)); assertTrue(hold.update(true, 600)); assertFalse(hold.update(true, 2000))
        hold.update(false, 2100); assertFalse(hold.update(true, 2200)); assertTrue(hold.update(true, 2800))
    }

    @Test fun filterSmoothsJitter() {
        val f = OneEuro(beta = 0f); f.filter(0.5f, 0f)
        assertTrue(abs(f.filter(0.6f, 0.033f) - 0.5f) < 0.05f)
    }

    @Test fun calculator() {
        assertEquals(14.0, Calculator.evaluate("2+3×4"), 1e-9)
        assertEquals(-4.0, Calculator.evaluate("-2^2"), 1e-9)
        assertEquals(5.0, Calculator.evaluate("sqrt(9)+sin(30)*4"), 1e-9)
        assertEquals(0.5, Calculator.evaluate("50%"), 1e-9)
        for (bad in listOf("1/0", "2+", "abc", "(".repeat(100), "-".repeat(150) + "1", ""))
            assertThrows(IllegalArgumentException::class.java) { Calculator.evaluate(bad) }
    }

    @Test fun handIdsFollowPositions() {
        val ids = HandIdentity()
        fun at(x: Float) = List(21) { Point(x, 0.5f) }
        val first = ids.assign(listOf(at(0.2f), at(0.8f))).map { it.id }
        assertEquals(2, first.toSet().size)
        assertEquals(first.reversed(), ids.assign(listOf(at(0.78f), at(0.22f))).map { it.id })
        assertEquals(listOf(first[0]), ids.assign(listOf(at(0.24f))).map { it.id })
    }
}
