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
        for (c in controlLayout(w)) assertEquals(c.region, at(c.x, c.y))
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
        val button = controlLayout(w).first { it.region == Region.CLOSE }
        val close = Ray(Vec3(0f, 0f, 0f), w.point(button.x, button.y).normalized())
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

    @Test fun windowsOpenWhereTheUserLooks() {
        for (pitch in listOf(-0.6f, 0f, 0.5f)) {
            val look = Quat.axisAngle(Vec3(0f, 1f, 0f), -1.4f) * Quat.axisAngle(Vec3(1f, 0f, 0f), pitch)
            val m = WindowManager()
            val w = m.open(AppType.HOME, yawOf(look), pitchOf(look))
            val centre = viewportRay(look, 0.5f, 0.5f, 50f, 2f)
            assertEquals(w.id, m.pick(centre)?.windowId)
            m.arrange(yawOf(look), pitchOf(look))
            assertEquals(Region.CONTENT, m.pick(centre)?.region)
        }
    }

    @Test fun handIdsFollowPositions() {
        val ids = HandIdentity()
        fun at(x: Float) = List(21) { Point(x, 0.5f) }
        val first = ids.assign(listOf(at(0.2f), at(0.8f))).map { it.id }
        assertEquals(2, first.toSet().size)
        assertEquals(first.reversed(), ids.assign(listOf(at(0.78f), at(0.22f))).map { it.id })
        assertEquals(listOf(first[0]), ids.assign(listOf(at(0.24f))).map { it.id })
    }

    private fun kinds(events: List<PressEvent>) = events.map { it.action }

    @Test fun releaseBeforeTwoSecondsIsATap() {
        val g = PressGate(slop = 20f)
        assertTrue(g.down(100f, 100f, 0).isEmpty())
        for (t in 100L..1900L step 100) assertTrue(g.move(103f, 101f, t).isEmpty())
        assertEquals(listOf(PressAction.DOWN, PressAction.UP), kinds(g.up(104f, 101f)))
    }

    @Test fun holdingTwoSecondsIsOneLongPressAndNoClick() {
        val g = PressGate(slop = 20f)
        g.down(100f, 100f, 0)
        val all = (100L..3000L step 100).flatMap { g.move(100f, 100f, it) }
        assertEquals(listOf(PressAction.DOWN, PressAction.LONG_PRESS), kinds(all))
        assertEquals(listOf(PressAction.CANCEL), kinds(g.up(100f, 100f)))
    }

    @Test fun lagSpikeCannotMakeALongPress() {
        val g = PressGate(slop = 20f)
        g.down(100f, 100f, 0)
        assertTrue(g.move(100f, 100f, 5000).isEmpty())
        assertEquals(listOf(PressAction.DOWN, PressAction.UP), kinds(g.up(100f, 100f)))
        g.down(0f, 0f, 10_000) // even a release that arrives very late is a tap
        assertEquals(listOf(PressAction.DOWN, PressAction.UP), kinds(g.up(0f, 0f)))
    }

    @Test fun movingStartsADragAndLostHandNeverClicks() {
        val g = PressGate(slop = 20f)
        g.down(0f, 0f, 0)
        assertEquals(listOf(PressAction.DOWN, PressAction.MOVE), kinds(g.move(60f, 0f, 50)))
        assertEquals(listOf(PressAction.CANCEL), kinds(g.cancel()))
        g.down(0f, 0f, 100)
        assertTrue(g.cancel().isEmpty())
        assertTrue(g.up(0f, 0f).isEmpty())
    }

    @Test fun governorDropsBelow15FpsAndRecoversSlowly() {
        val g = PerformanceGovernor()
        var t = 0L
        fun run(fps: Int, seconds: Int): Int { repeat(seconds * 10) { t += 100; g.update(fps, t) }; return g.level }
        assertEquals(0, run(30, 5))
        assertEquals(0, run(12, 2))
        assertEquals(0, run(30, 1)) // a short dip changes nothing
        assertEquals(1, run(12, 4))
        assertEquals(2, run(10, 8))
        assertEquals(2, run(20, 60)) // between the limits it stays put
        assertEquals(1, run(40, 14))
        assertEquals(0, run(40, 14))
        assertEquals(0, PerformanceGovernor().update(0, 100_000))
    }

    @Test fun animatorFadesInOutAndCanBeInstant() {
        val a = WindowAnimator()
        val w = SpatialWindow(1, AppType.NOTES, 0f, 0f, 2f, 1f, 0.6f)
        assertTrue(a.update(listOf(w), 0.016f, 0).single().alpha < 0.5f)
        var shown = a.update(listOf(w), 0.016f, 0).single()
        repeat(60) { shown = a.update(listOf(w), 0.016f, 0).single() }
        assertEquals(1f, shown.alpha, 0.01f); assertEquals(1f, shown.window.scale, 0.01f)
        assertEquals(1, a.update(emptyList(), 0.016f, 0).size)
        repeat(60) { a.update(emptyList(), 0.016f, 0) }
        assertFalse(a.isVisible(1))
        assertEquals(1f, a.update(listOf(w), 0.016f, 2).single().alpha, 0f)
        assertTrue(a.update(emptyList(), 0.016f, 2).isEmpty())
        val spring = Spring(0f); spring.step(1f, 5f, 16f)
        assertEquals(1f, spring.value, 0.01f)
    }

    @Test fun controlsAreSeparatedAtEverySize() {
        for (app in AppType.entries) for (scale in listOf(0.5f, 1f, 2.2f)) {
            val w = WindowManager().open(app, 0f).copy(scale = scale)
            val row = controlLayout(w).sortedBy { it.x }
            for ((a, b) in row.zipWithNext())
                assertTrue("$app $scale ${a.region} ${b.region}", b.x - b.halfWidth - (a.x + a.halfWidth) >= 0.025f)
        }
    }

    @Test fun curvedWindowHitsMatchItsSurface() {
        val w = SpatialWindow(1, AppType.MEDIA, 0.4f, 0.2f, 2f, 2f, 1f, curved = true)
        for ((x, y) in listOf(0f to 0f, 0.8f to 0.3f, -0.9f to -0.4f)) {
            val hit = hitTest(Ray(Vec3(0f, 0f, 0f), w.point(x, y).normalized()), w)!!
            assertEquals(x / 2f + 0.5f, hit.u, 1e-3f); assertEquals(0.5f - y, hit.v, 1e-3f)
        }
    }

    @Test fun layoutRoundTripsRelativeToTheHead() {
        val m = WindowManager()
        val a = m.open(AppType.NOTES, 1f)
        m.replace(a.copy(yaw = 1.3f, scale = 1.4f, curved = true))
        m.open(AppType.KEYBOARD, 1f)
        val saved = LayoutCodec.encode(m.windows, 1f, 0f)
        val next = WindowManager()
        next.restore(LayoutCodec.decode(saved, -2f, 0f))
        val back = next.find(AppType.NOTES)!!
        assertEquals(-1.7f, back.yaw, 1e-3f); assertEquals(1.4f, back.scale, 1e-3f); assertTrue(back.curved)
        assertNull(next.find(AppType.KEYBOARD))
        assertTrue(LayoutCodec.decode("junk;NOTES,NaN,0,1,1,false", 0f, 0f).isEmpty())
    }

    @Test fun hoverReportsEnterAndLeave() {
        val events = mutableListOf<String>()
        val m = WindowManager(); val w = m.open(AppType.NOTES, 0f)
        val c = InteractionController(m, object : InteractionListener {
            override fun onContentTouch(windowId: Int, action: TouchAction, u: Float, v: Float) {}
            override fun onClosed(windowId: Int) {}
            override fun onContentHover(windowId: Int, u: Float, v: Float) { events += "in" }
            override fun onHoverEnd(windowId: Int) { events += "out" }
        })
        c.handle(1, PointerPhase.HOVER, ray(0f, 0f)); assertEquals(w.id, c.hovered().single().windowId)
        c.handle(1, PointerPhase.HOVER, ray(2f, 0f)); assertTrue(c.hovered().isEmpty())
        c.handle(1, PointerPhase.HOVER, ray(0f, 0f)); c.retainHovers(emptySet())
        assertEquals(listOf("in", "out", "in", "out"), events)
    }

    @Test fun palmDirectionNeedsKnownSide() {
        // hand(open) has the index knuckle left of the little-finger knuckle.
        val base = hand(0f, open = true)
        assertFalse(palmFacesUser(base))
        assertTrue(palmFacesUser(base.copy(side = Side.LEFT)))
        assertFalse(palmFacesUser(base.copy(side = Side.RIGHT)))
    }

    @Test fun handChoiceAndCalibration() {
        val e = GestureEngine()
        e.configure(GestureConfig(hands = HandChoice.LEFT))
        assertTrue(e.update(listOf(hand(0.02f).copy(side = Side.RIGHT)), 1f, 1).isEmpty())
        assertEquals(1, e.update(listOf(hand(0.02f).copy(side = Side.LEFT)), 1f, 40).size)
        val tuned = calibratedConfig(GestureConfig(), 1.2f, 0.2f)!!
        assertTrue(tuned.pressRatio > 0.2f && tuned.releaseRatio > tuned.pressRatio)
        assertNull(calibratedConfig(GestureConfig(), 0.4f, 0.3f))
    }

    @Test fun pokeClicksWhenTheHandPushesForward() {
        val e = GestureEngine().apply { configure(GestureConfig(style = ClickStyle.POKE)) }
        fun scaled(k: Float): Hand {
            val h = hand(0.2f, open = true)
            return h.copy(points = h.points.map { Point(0.5f + (it.x - 0.5f) * k, 0.6f + (it.y - 0.6f) * k) })
        }
        var t = 0L
        fun step(k: Float) = e.update(listOf(scaled(k)), 1f, 33L.let { t += it; t }).single().phase
        repeat(5) { assertEquals(PointerPhase.HOVER, step(1f)) }
        step(1.15f); assertEquals(PointerPhase.DOWN, step(1.15f))
        step(1f); assertEquals(PointerPhase.UP, step(1f))
    }

    @Test fun gazeAimsWithHeadAndDragsWithHand() {
        val g = GazeAim()
        fun at(phase: PointerPhase, x: Float) = g.position(PointerState(0, phase, Point(x, 0.5f), false))
        assertEquals(0.5f, at(PointerPhase.HOVER, 0.9f).x, 0f)
        assertEquals(0.5f, at(PointerPhase.DOWN, 0.9f).x, 0f)
        assertEquals(0.4f, at(PointerPhase.MOVE, 0.8f).x, 1e-4f)
        assertEquals(0.4f, at(PointerPhase.UP, 0.8f).x, 1e-4f)
        assertEquals(0.5f, at(PointerPhase.HOVER, 0.1f).x, 0f)
    }
}
