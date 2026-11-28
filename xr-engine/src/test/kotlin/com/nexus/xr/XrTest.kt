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

    @Test fun voiceCommandsInEnglishAndArabic() {
        val c = VoiceCommands()
        assertEquals(VoiceCommand.Open(AppType.BROWSER), c.parse("Open the browser, please."))
        assertEquals(VoiceCommand.Open(AppType.CALCULATOR), c.parse("افتح الآلة الحاسبة"))
        assertEquals(VoiceCommand.Open(AppType.SETTINGS), c.parse("لو سمحت افتح الإعدادات"))
        assertEquals(VoiceCommand.Close(AppType.NOTES), c.parse("close notes"))
        assertEquals(VoiceCommand.Open(AppType.CENTER), c.parse("show control center"))
        assertNull(c.parse("how do I open the browser on my laptop"))
        assertNull(c.parse("open the door"))
    }

    @Test fun destructiveCommandsNeedConfirmation() {
        val c = VoiceCommands()
        assertEquals(CommandResult.AskToConfirm(VoiceCommand.CloseAll), c.handle("close all", 0))
        assertEquals(CommandResult.Run(VoiceCommand.CloseAll), c.handle("Confirm.", 5_000))
        assertNull(c.handle("confirm", 6_000))
        c.handle("اقفل الكل", 10_000)
        assertNull(c.handle("تأكيد", 100_000)) // too late
        c.handle("exit", 0); assertEquals(CommandResult.Cancelled, c.handle("cancel", 1))
        c.handle("exit", 0); assertEquals(CommandResult.Run(VoiceCommand.Recenter), c.handle("recenter", 1))
        assertNull(c.handle("confirm", 2))
    }

    @Test fun suggestionsLearnAndPersist() {
        val s = WordSuggester()
        assertEquals("hello", s.suggest("hel").first())
        assertEquals("Hello", s.suggest("Hel").first())
        assertTrue(s.suggest("صبا").contains("صباح"))
        repeat(3) { s.learn("helicopter") }
        assertEquals("helicopter", WordSuggester(s.state()).suggest("hel").first())
        s.learn("a1b2c3"); s.learn("ab")
        assertFalse(s.state().contains("a1b2"))
        assertEquals("wor", s.currentWord("hello wor"))
        assertEquals("", s.currentWord("hello "))
        assertTrue(s.suggest("").isEmpty())
        val small = WordSuggester(limit = 2)
        listOf("alpha", "bravo", "charlie").forEach(small::learn)
        assertFalse(small.state().contains("alpha"))
        assertTrue(WordSuggester("garbage\nx\ty").state().isEmpty())
    }

    @Test fun thereAreOverFiftyOfflineCommandsAndEveryPhraseWorks() {
        val c = VoiceCommands()
        assertTrue(Act.entries.size > 50)
        for (act in Act.entries) for (phrase in act.phrases) assertEquals(phrase, VoiceCommand.Do(act), c.parse(phrase))
    }

    @Test fun spokenTextKeepsTheUsersSpelling() {
        val c = VoiceCommands()
        assertEquals(VoiceCommand.Type("hello"), c.parse("type hello"))
        assertEquals(VoiceCommand.Type("Hello World"), c.parse("Please, type Hello World."))
        assertEquals(VoiceCommand.Type("أهلاً يا صاحبي"), c.parse("اكتب أهلاً يا صاحبي"))
        assertEquals(VoiceCommand.Search("pizza near me"), c.parse("search for pizza near me"))
        assertEquals(VoiceCommand.Search("مطاعم في المعادي"), c.parse("ابحث عن مطاعم في المعادي"))
        assertEquals(VoiceCommand.GoTo("wikipedia dot org"), c.parse("go to wikipedia dot org"))
        assertEquals(VoiceCommand.Do(Act.PAGE_TOP), c.parse("go to top"))
        assertEquals(VoiceCommand.ClickLabel("Sign in"), c.parse("click on Sign in"))
        assertEquals(VoiceCommand.Timer(5), c.parse("set a timer for 5 minutes"))
        assertEquals(VoiceCommand.Timer(10), c.parse("مؤقت ١٠ دقايق"))
        assertEquals(VoiceCommand.AddTask("buy milk"), c.parse("add task buy milk"))
        assertEquals(VoiceCommand.Open(AppType.WEATHER), c.parse("open weather"))
        assertNull(c.parse("type"))
        assertNull(c.parse("what is the capital of France"))
    }

    @Test fun aiCanOnlyUseTheCommandList() {
        val plan = AiControl.parse("DO: open browser\nDO: search for cats\nDO: format the phone\nSAY: Searching for cats.")
        assertEquals(listOf(VoiceCommand.Open(AppType.BROWSER), VoiceCommand.Search("cats")), plan.steps)
        assertEquals(listOf("format the phone"), plan.rejected)
        assertEquals("Searching for cats.", plan.say)
        val chat = AiControl.parse("open browser")
        assertTrue(chat.steps.isEmpty())
        assertEquals("open browser", chat.say)
        assertEquals(AiControl.MAX_STEPS, AiControl.parse(List(20) { "DO: scroll down" }.joinToString("\n")).steps.size)
        assertTrue(AiControl.needsConfirmation(VoiceCommand.CloseAll))
        assertTrue(AiControl.needsConfirmation(VoiceCommand.ClickLabel("Pay now")))
        assertTrue(AiControl.needsConfirmation(VoiceCommand.ClickLabel("ادفع")))
        assertTrue(AiControl.needsConfirmation(VoiceCommand.Do(Act.CLICK)))
        assertFalse(AiControl.needsConfirmation(VoiceCommand.ClickLabel("Images")))
        assertFalse(AiControl.needsConfirmation(VoiceCommand.Search("cats")))
        assertTrue(AiControl.systemPrompt(AppType.BROWSER).contains("scroll down"))
    }

    @Test fun unitConversions() {
        fun unit(category: String, symbol: String) = Units.categories.getValue(category).first { it.symbol == symbol }
        assertEquals(1000.0, Units.convert(1.0, unit("length", "km"), unit("length", "m")), 1e-9)
        assertEquals(212.0, Units.convert(100.0, unit("temperature", "°C"), unit("temperature", "°F")), 1e-9)
        assertEquals(273.15, Units.convert(32.0, unit("temperature", "°F"), unit("temperature", "K")), 1e-9)
    }

    @Test fun game2048MergesEachTileOnce() {
        val game = Game2048(kotlin.random.Random(1))
        game.load(intArrayOf(2, 2, 2, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        assertTrue(game.move(Slide.LEFT))
        assertEquals(4, game.cells[0]); assertEquals(4, game.cells[1]); assertEquals(8, game.score)
        game.load(intArrayOf(2, 4, 2, 4, 4, 2, 4, 2, 2, 4, 2, 4, 4, 2, 4, 2))
        assertTrue(game.over()); assertFalse(game.move(Slide.UP))
    }

    @Test fun ticTacToeComputerNeverLosesAndTasksRoundTrip() {
        fun play(board: CharArray) {
            // The person tries every move; the computer answers with its best one.
            for (i in 0..8) {
                if (board[i] != ' ') continue
                val next = board.copyOf().also { it[i] = 'X' }
                assertNotEquals('X', TicTacToe.winner(next))
                val reply = TicTacToe.bestMove(next, 'O')
                if (reply < 0 || next.none { it == ' ' }) continue
                next[reply] = 'O'
                if (TicTacToe.winner(next) == null) play(next)
            }
        }
        play(CharArray(9) { ' ' })
        val decoded = TaskCodec.decode(TaskCodec.encode(listOf(Task("a\tb", true), Task("c"))))
        assertEquals(listOf("a b", "c"), decoded.map { it.text }); assertTrue(decoded[0].done)
    }

    @Test fun freeResizeChangesWidthAndHeightSeparatelyAndIsRemembered() {
        val manager = WindowManager()
        val window = manager.open(AppType.NOTES, 0f)
        val controller = InteractionController(manager, object : InteractionListener {
            override fun onContentTouch(windowId: Int, action: TouchAction, u: Float, v: Float) {}
            override fun onClosed(windowId: Int) {}
        }).apply { freeResize = true }
        val corner = controlLayout(window).first { it.region == Region.RESIZE }
        fun aim(x: Float, y: Float) = window.point(x, y).let { Ray(Vec3(0f, 0f, 0f), it * (1f / it.length)) }
        controller.handle(1, PointerPhase.DOWN, aim(corner.x, corner.y))
        controller.handle(1, PointerPhase.MOVE, aim(corner.x + 0.1f, corner.y))
        controller.handle(1, PointerPhase.UP, aim(corner.x + 0.1f, corner.y))
        val wider = manager.get(window.id)!!
        assertEquals(window.width + 0.2f, wider.width, 0.01f)
        assertEquals(window.height, wider.height, 0.01f)
        val back = LayoutCodec.decode(LayoutCodec.encode(manager.windows, 0f, 0f), 0f, 0f).single()
        assertEquals(wider.width, back.width, 1e-4f)
        val old = LayoutCodec.decode("NOTES,0.0,0.0,1.7,1.0,false", 0f, 0f).single()
        assertEquals(0f, old.width, 0f)
    }

    @Test fun headsetPicturesMoveIndependentlyAndTheGapPushesThemApart() {
        val plain = HeadsetFit()
        assertEquals(EyeRect(0, 0, 1200, 1080), plain.eye(0, 2400, 1080))
        assertEquals(EyeRect(1200, 0, 1200, 1080), plain.eye(1, 2400, 1080))
        val apart = HeadsetFit(gap = 0.1f, width = 0.8f, height = 0.8f)
        assertEquals(0, apart.eye(0, 2400, 1080).x)
        assertEquals(1200 + 120 + 120, apart.eye(1, 2400, 1080).x)
        val moved = apart.dragged(0, 0.05f, 0.1f)
        assertEquals(apart.eye(0, 2400, 1080).x + 120, moved.eye(0, 2400, 1080).x)
        assertEquals(apart.eye(0, 2400, 1080).y + 108, moved.eye(0, 2400, 1080).y)
        assertEquals(apart.eye(1, 2400, 1080), moved.eye(1, 2400, 1080))
        val tiny = apart.scaled(0.01f)
        assertEquals(HeadsetFit.MIN_SIZE, tiny.width, 1e-4f)
        assertEquals(tiny.width, tiny.height, 1e-4f)
        val wide = HeadsetFit(width = 1f, height = 0.5f).scaled(2f)
        assertEquals(0.5f, wide.height, 1e-4f)
        assertEquals(HeadsetFit.MAX_MOVE, plain.dragged(1, 5f, -5f).rightX, 1e-4f)
    }

    @Test fun headsetCommandsAreUnderstoodAndThePhoneAppListIsGone() {
        val commands = VoiceCommands()
        assertEquals(VoiceCommand.Do(Act.GAP_WIDER), (commands.handle("wider gap", 0) as CommandResult.Run).command)
        assertEquals(VoiceCommand.Do(Act.GAP_NARROWER), (commands.handle("قرب الشاشتين", 0) as CommandResult.Run).command)
        assertEquals(VoiceCommand.Open(AppType.FIT), (commands.handle("open screen fit", 0) as CommandResult.Run).command)
        assertNull(commands.handle("open my apps", 0))
        val phrases = Act.entries.flatMap { act -> act.phrases.map(VoiceCommands::normalize) }
        assertEquals(phrases.size, phrases.toSet().size)
    }

    private fun command(text: String) = VoiceCommands().parse(text)

    @Test fun routinesAcceptOnlyBuiltInStepsAndNeverReplaceACommand() {
        val book = RoutineBook.parse("""
            morning = open browser; open tasks; arrange
            صباح الخير = افتح المتصفح؛ رتب
            close all = open notes
            confirm = open notes
            bad = open notes; format the phone
            no steps =
            Morning = open clock
        """.trimIndent())
        assertEquals(listOf("morning", "صباح الخير"), book.routines.map { it.name })
        assertEquals(3, book.find("Morning!")!!.steps.size)
        assertNull(book.find("good evening"))
        assertEquals(
            listOf(RoutineBook.Kind.NAME_TAKEN, RoutineBook.Kind.NAME_TAKEN, RoutineBook.Kind.UNKNOWN_STEP, RoutineBook.Kind.FORMAT, RoutineBook.Kind.NAME_TAKEN),
            book.problems.map { it.kind }
        )
    }

    @Test fun remindersAreUnderstoodInBothLanguages() {
        assertEquals(VoiceCommand.Remind(10, "call Mom"), command("remind me in 10 minutes to call Mom"))
        assertEquals(VoiceCommand.Remind(120, "water the plants"), command("remind me to water the plants in two hours".replace("two", "2")))
        assertEquals(VoiceCommand.Remind(15, "اكلم احمد"), command("فكرني بعد 15 دقيقة اكلم احمد"))
        assertEquals(VoiceCommand.Remind(60, "أذاكر"), command("ذكرني بعد ساعة أذاكر"))
        assertNull(command("remind me to sleep"))
        assertNull(command("remind me in 10 minutes"))
        assertNull(command("remind me in 999999 hours to rest"))
        assertEquals(VoiceCommand.Workspace(Space.STUDY, false), command("study mode"))
        assertTrue(AiControl.needsConfirmation(command("save work space")!!))
    }

    @Test fun eventsSurviveStorageAndRingOnce() {
        val events = listOf(CalendarEvent(1, 5_000, "a\tb"), CalendarEvent(2, 9_000, "later"), CalendarEvent(3, 1_000, "done", true))
        val loaded = EventCodec.decode(EventCodec.encode(events))
        assertEquals("a b", loaded[0].title)
        assertEquals(listOf(1L), EventCodec.due(loaded, 6_000).map { it.id })
        val many = (1..EventCodec.MAX_EVENTS).map { CalendarEvent(it.toLong(), it.toLong(), "x", it == 7) }
        val full = EventCodec.add(many, CalendarEvent(999, 5, "new"))
        assertEquals(EventCodec.MAX_EVENTS, full.size)
        assertTrue(full.none { it.id == 7L })
    }

    @Test fun subtitlesReadSrtAndVttAndFollowTheTime() {
        val srt = Subtitles.parse("\uFEFF1\r\n00:00:01,000 --> 00:00:02,500\r\n<i>Hello</i>\r\nthere\r\n\r\n2\r\n00:01:00,000 --> 00:01:01,000\r\nمرحبا\r\n")
        assertEquals("Hello\nthere", srt.textAt(1_000))
        assertEquals("", srt.textAt(2_500))
        assertEquals("مرحبا", srt.textAt(60_500))
        assertEquals("", srt.textAt(0))
        val vtt = Subtitles.parse("WEBVTT\n\n00:03.5 --> 00:04.000 align:start\nShort form")
        assertEquals("Short form", vtt.textAt(3_600))
        assertTrue(Subtitles.parse("not subtitles").cues.isEmpty())
    }

    @Test fun soundFromTheRightIsLouderAndEarlierInTheRightEar() {
        val right = SpatialAudio.mix(1.5708f, 44_100)
        assertTrue(right.rightGain > right.leftGain)
        assertEquals(0, right.rightDelay)
        assertTrue(right.leftDelay in 20..35)
        val ahead = SpatialAudio.mix(0f, 44_100)
        assertEquals(ahead.leftGain, ahead.rightGain, 0f)
        assertEquals(0, ahead.leftDelay + ahead.rightDelay)
        val stereo = SpatialAudio.render(shortArrayOf(1000), right)
        assertEquals((right.leftDelay + 1) * 2, stereo.size)
        assertEquals(1000.toShort(), stereo[1])
    }

    @Test fun theStartingShortcutExamplesWorkInBothLanguages() {
        for (language in listOf("values", "values-ar")) {
            val strings = java.io.File("app/src/main/res/$language/strings.xml").takeIf { it.exists() }
                ?: java.io.File("../app/src/main/res/$language/strings.xml")
            val example = Regex("<string name=\"routines_example\">(.*?)</string>").find(strings.readText())!!.groupValues[1]
            val book = RoutineBook.parse(example)
            assertEquals(language, 0, book.problems.size)
            assertEquals(language, 3, book.routines.single().steps.size)
        }
    }

    /** A made-up hand: wrist at the bottom, each named finger straight (up) or folded (back). */
    private fun posedHand(vararg straight: Int, thumbOut: Boolean = false, thumbUp: Boolean = false): Hand {
        val p = MutableList(21) { Point(0.5f, 0.8f) }
        val bases = listOf(5 to 0.43f, 9 to 0.49f, 13 to 0.55f, 17 to 0.61f)
        bases.forEachIndexed { i, (base, x) ->
            val spread = (x - 0.52f)
            p[base] = Point(x, 0.6f)
            p[base + 1] = Point(x + spread * 0.3f, 0.52f)
            if (i in straight) {
                p[base + 2] = Point(x + spread * 0.6f, 0.45f); p[base + 3] = Point(x + spread * 0.9f, 0.38f)
            } else {
                p[base + 2] = Point(x, 0.58f); p[base + 3] = Point(x, 0.64f)
            }
        }
        p[1] = Point(0.43f, 0.76f); p[2] = Point(0.38f, 0.70f)
        when {
            thumbUp -> { p[3] = Point(0.36f, 0.55f); p[4] = Point(0.35f, 0.30f) }
            thumbOut -> { p[3] = Point(0.33f, 0.67f); p[4] = Point(0.26f, 0.64f) }
            else -> { p[3] = Point(0.43f, 0.66f); p[4] = Point(0.50f, 0.65f) }
        }
        return Hand(1, p)
    }

    @Test fun handPosesAreToldApartAndAPinchIsNeverOne() {
        assertEquals(HandPose.VICTORY, classifyPose(posedHand(0, 1), 1f, 0.9f))
        assertEquals(HandPose.THREE, classifyPose(posedHand(0, 1, 2), 1f, 0.9f))
        assertEquals(HandPose.HORNS, classifyPose(posedHand(0, 3), 1f, 0.9f))
        assertEquals(HandPose.CALL, classifyPose(posedHand(3, thumbOut = true), 1f, 0.9f))
        assertEquals(HandPose.THUMB_UP, classifyPose(posedHand(thumbUp = true), 1f, 0.9f))
        assertNull(classifyPose(posedHand(0, 1), 1f, 0.2f))
        assertNull(classifyPose(posedHand(), 1f, 0.9f))
        assertNull(classifyPose(posedHand(0, 1, 2, 3, thumbOut = true), 1f, 0.9f))
    }

    @Test fun aHeldPoseFiresOnceUntilLetGo() {
        val hold = PoseHold(1000)
        assertNull(hold.update(HandPose.VICTORY, 0))
        assertNull(hold.update(HandPose.VICTORY, 999))
        assertEquals(HandPose.VICTORY, hold.update(HandPose.VICTORY, 1000))
        assertNull(hold.update(HandPose.VICTORY, 5000))
        assertNull(hold.update(null, 5100))
        assertNull(hold.update(HandPose.VICTORY, 5200))
        assertEquals(HandPose.VICTORY, hold.update(HandPose.VICTORY, 6200))
    }

    @Test fun everyPoseActionIsARealCommand() {
        val parser = VoiceCommands()
        PoseAction.entries.mapNotNull { it.say }.forEach { assertNotNull(it, parser.parse(it)) }
    }

    @Test fun trackingProblemsAreNamed() {
        assertEquals(listOf(TrackingProblem.NO_HAND), TrackingAdvice.problems(TrackingSample(0, 40)))
        assertEquals(listOf(TrackingProblem.SLOW, TrackingProblem.TOO_FAR, TrackingProblem.AT_EDGE),
            TrackingAdvice.problems(TrackingSample(1, 300, 0.05f, true)))
        assertEquals(listOf(TrackingProblem.FAILED), TrackingAdvice.problems(TrackingSample(0, 0, error = "x")))
        assertTrue(TrackingAdvice.problems(TrackingSample(1, 60, 0.3f)).isEmpty())
    }

    @Test fun theMouseCursorStaysInViewAndARestingStickDoesNotDrift() {
        val cursor = AirCursor()
        cursor.stick(0.1f, -0.15f, 0.1f)
        assertEquals(0.5f, cursor.x, 0f); assertEquals(0.5f, cursor.y, 0f)
        cursor.stick(1f, 0f, 0.1f)
        assertTrue(cursor.x > 0.55f)
        cursor.move(9f, -9f)
        assertEquals(0.98f, cursor.x, 1e-5f); assertEquals(0.02f, cursor.y, 1e-5f)
    }

    @Test fun crashReportsNeverContainErrorMessages() {
        val error = IllegalStateException("password hunter2 in https://secret.example", RuntimeException("token abc"))
        val report = CrashText.of(error)
        assertTrue(report.contains("java.lang.IllegalStateException"))
        assertTrue(report.contains("Caused by: java.lang.RuntimeException"))
        assertTrue(listOf("hunter2", "secret.example", "token abc").none { it in report })
    }

    @Test fun suggestionsPredictTheNextWordAndFixSlips() {
        val words = WordSuggester()
        repeat(3) { words.learn("morning", "good") }
        words.learn("luck", "good")
        assertEquals(listOf("morning", "luck"), words.suggest("", previous = "Good"))
        assertEquals("luck", words.suggest("l", previous = "good").first())
        assertTrue("tomorrow" in words.suggest("tomorow"))
        assertTrue("weather" in words.suggest("waether"))
        assertEquals("good", words.previousWord("very good mor"))
        assertEquals("good", words.previousWord("good "))
        assertEquals("", words.previousWord("good. "))
        val again = WordSuggester(words.state())
        assertEquals(listOf("morning", "luck"), again.suggest("", previous = "good"))
        assertTrue(WordSuggester.oneSlip("helo", "hello"))
        assertFalse(WordSuggester.oneSlip("hat", "dog"))
    }

    @Test fun batteryUseIsUnknownUntilEnoughHasBeenMeasured() {
        val drain = BatteryDrain()
        drain.add(0, 80, false)
        assertNull(drain.perHour(299_000, 79))
        assertEquals(12, drain.perHour(600_000, 78))
        drain.add(700_000, 78, true)
        assertNull(drain.perHour(1_200_000, 90))
        drain.add(1_200_000, 90, false)
        assertNull(drain.perHour(1_300_000, 90))
    }

    @Test fun theFrameLogKeepsOnlyTheLastMinute() {
        val log = FrameLog(3)
        listOf(2, 30, 40, 50).forEach(log::add)
        assertEquals(40, log.average())
        assertEquals(30, log.lowest())
    }

    @Test fun cameraAndScenePicturesShareOneFieldOfView() {
        assertEquals(50f, croppedFov(50f, 4f / 3f), 0.01f)
        assertEquals(50f, croppedFov(50f, 1.1f), 0.01f)
        assertEquals(31.3f, croppedFov(50f, 2400f / 1080f), 0.1f)
    }

    @Test fun walkingKeepsWindowsInTheRoom() {
        val manager = WindowManager()
        val first = manager.open(AppType.NOTES, 0f)
        val place = first.center
        manager.viewer = Vec3(1f, 0f, -0.5f)
        assertEquals(place, manager.get(first.id)!!.center)
        val second = manager.open(AppType.CLOCK, 0f)
        assertEquals(manager.viewer, second.anchor)
        manager.shift(Vec3(0f, 0f, 1f))
        assertEquals(place + Vec3(0f, 0f, 1f), manager.get(first.id)!!.center)
    }

    @Test fun raysFromAWalkingViewerHitFlatAndCurvedWindows() {
        for (curved in listOf(false, true)) {
            val win = SpatialWindow(1, AppType.NOTES, 0f, 0f, 2f, 1f, 0.8f, curved = curved)
            val from = Vec3(0.3f, 0.1f, -0.5f)
            val target = win.point(0.2f, 0.15f)
            val (t, x, y) = planeHit(Ray(from, (target - from).normalized()), win)!!
            assertEquals((target - from).length, t, 0.01f)
            assertEquals(0.2f, x, 0.01f)
            assertEquals(0.15f, y, 0.01f)
        }
    }

    @Test fun grabbingAfterWalkingDoesNotMoveTheWindow() {
        val manager = WindowManager()
        val win = manager.open(AppType.NOTES, 0f)
        val controller = InteractionController(manager, object : InteractionListener {
            override fun onContentTouch(windowId: Int, action: TouchAction, u: Float, v: Float) {}
            override fun onClosed(windowId: Int) {}
        })
        val from = Vec3(0.8f, 0f, 0f)
        val bar = win.point(0f, win.h / 2 + SpatialWindow.BAR / 2)
        val ray = Ray(from, (bar - from).normalized())
        controller.handle(1, PointerPhase.DOWN, ray)
        controller.handle(1, PointerPhase.MOVE, ray)
        assertEquals(0f, (manager.get(win.id)!!.center - win.center).length, 0.02f)
        assertEquals(from, manager.get(win.id)!!.anchor)
    }

    @Test fun poseFusionStartsWithoutAJumpAndSurvivesLostTracking() {
        fun turn(yaw: Float) = Quat.axisAngle(Vec3(0f, 1f, 0f), -yaw)
        val fusion = PoseFusion()
        assertEquals(turn(0.4f), fusion.pose(turn(0.4f)).orientation)
        // The tracker has its own idea of forward and of where the middle is.
        fusion.tracked(turn(2.0f), Vec3(5f, 1f, 5f), turn(0.4f))
        var pose = fusion.pose(turn(0.4f))
        assertEquals(0.4f, yawOf(pose.orientation), 0.001f)
        assertEquals(0f, pose.position.length, 0.001f)
        // One step along the direction faced is one step forward in the app too.
        fusion.tracked(turn(2.0f), Vec3(5f, 1f, 5f) + direction(2.0f, 0f), turn(0.4f))
        pose = fusion.pose(turn(0.4f))
        assertEquals(0f, (pose.position - direction(0.4f, 0f)).length, 0.001f)
        // Lost: the place stays, and the motion sensor keeps turning the view.
        fusion.lost()
        val lost = fusion.pose(turn(0.9f))
        assertEquals(0.9f, yawOf(lost.orientation), 0.001f)
        assertEquals(pose.position, lost.position)
        fusion.recenter(turn(0.9f))
        assertEquals(0f, yawOf(fusion.pose(turn(0.9f)).orientation), 0.001f)
        assertEquals(0f, fusion.pose(turn(0.9f)).position.length, 0.001f)
    }

    @Test fun pictureCoordinatesTurnBack() {
        assertEquals(Pair(0.2f, 0.9f), beforeTurn(0.1f, 0.2f, 90))
        assertEquals(Pair(0.9f, 0.8f), beforeTurn(0.1f, 0.2f, 180))
        assertEquals(Pair(0.8f, 0.1f), beforeTurn(0.1f, 0.2f, 270))
        assertEquals(Pair(0.1f, 0.2f), beforeTurn(0.1f, 0.2f, 360))
        val map = Affine.fromCorners(floatArrayOf(1f, 0f, 1f, 1f, 0f, 0f))
        assertEquals(Pair(0.75f, 0.5f), map.map(0.5f, 0.25f))
        val matrix = Affine.textureMatrix(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f))
        assertEquals(-1f, matrix[5], 0f)
        assertEquals(1f, matrix[13], 0f)
    }

    @Test fun yuvBecomesTheRightColours() {
        val out = IntArray(4)
        // A 2x2 picture with padded rows and interleaved colour samples.
        yuvToArgb(byteArrayOf(0, -1, 0, 0, 76, 76, 0, 0), byteArrayOf(128.toByte(), 0), byteArrayOf(128.toByte(), 0), 2, 2, 4, 2, 2, out)
        assertEquals(0xFF000000.toInt(), out[0])
        assertEquals(0xFFFFFFFF.toInt(), out[1])
        yuvToArgb(ByteArray(4) { 76 }, byteArrayOf(85), byteArrayOf(-1), 2, 2, 2, 1, 1, out)
        assertTrue(out.all { (it shr 16 and 255) > 250 && (it shr 8 and 255) < 5 && (it and 255) < 5 })
    }

    @Test fun heatTightensAtOnceAndRelaxesSlowly() {
        val policy = ThermalPolicy(coolAfterMs = 30_000)
        assertEquals(HeatLimits.NONE, policy.update(0, Float.NaN, 0))
        assertEquals(1, policy.update(0, 0.97f, 1000).maxQuality)
        assertTrue(policy.update(4, Float.NaN, 2000).warn)
        assertTrue(policy.update(0, Float.NaN, 3000).warn)
        assertEquals(3, policy.step)
        policy.update(0, Float.NaN, 33_000)
        assertEquals(2, policy.step)
        policy.update(0, Float.NaN, 40_000)
        assertEquals(2, policy.step)
        policy.update(0, Float.NaN, 63_000)
        policy.update(0, Float.NaN, 93_000)
        assertEquals(HeatLimits.NONE, policy.update(0, Float.NaN, 94_000))
    }

    @Test fun deviceReportListsWhatPassedFailedAndWasSkipped() {
        val report = DeviceReport()
        report.observe(Check.HAND_SEEN, false, "none")
        assertEquals(Outcome.NOT_RUN, report.get(Check.HAND_SEEN).outcome)
        report.observe(Check.HAND_SEEN, true, "40 ms")
        report.observe(Check.HAND_SEEN, false, "gone")
        report.set(Check.SPEAKER, false)
        val text = report.text(listOf("Android 14"))
        assertTrue(text.contains("PASS    HAND_SEEN - 40 ms"))
        assertTrue(text.contains("FAIL    SPEAKER"))
        assertTrue(text.endsWith("Passed 1, failed 1, not run ${Check.entries.size - 2}"))
        assertEquals(-90.3f, peakDb(ShortArray(4)), 0.1f)
        assertEquals(0f, peakDb(shortArrayOf(Short.MIN_VALUE)), 0.01f)
    }

    @Test fun inputUsesTheGeometryOnScreen() {
        val m = WindowManager(); val w = m.open(AppType.NOTES, 0f)
        // The target has jumped to the right, but the frame on screen still shows it ahead.
        m.replace(w.copy(yaw = 1.2f))
        m.present(listOf(w))
        assertEquals(w.id, m.pick(forward)?.windowId)
        assertNull(m.pick(ray(1.2f, 0f)))
        m.present(listOf(m.get(w.id)!!))
        assertNull(m.pick(forward))
        assertEquals(w.id, m.pick(ray(1.2f, 0f))?.windowId)
    }

    @Test fun closingAndMinimizedWindowsTakeNoInput() {
        val m = WindowManager(); val w = m.open(AppType.NOTES, 0f); m.present(listOf(w))
        m.minimize(w.id); assertNull(m.pick(forward))
        m.close(w.id); assertNull(m.pick(forward))
    }

    @Test fun overlappingPickMatchesWhatIsPaintedOnTop() {
        val m = WindowManager()
        val first = m.open(AppType.NOTES, 0f); val second = m.open(AppType.CLOCK, 0f)
        // Same distance, angled apart: their planes cross, so ray depth disagrees with paint order.
        m.replace(first.copy(yaw = -0.15f)); m.replace(second.copy(yaw = 0.15f))
        for (aim in listOf(-0.08f, 0f, 0.08f)) assertEquals(second.id, m.pick(ray(aim, 0f))?.windowId)
        m.focus(first.id)
        for (aim in listOf(-0.08f, 0f, 0.08f)) assertEquals(first.id, m.pick(ray(aim, 0f))?.windowId)
        assertEquals(first.id, paintOrder(m.windows, forward.origin).last().id)
        // A nearer window wins whatever the focus is.
        m.replace(m.get(second.id)!!.copy(distance = 1.2f))
        assertEquals(second.id, m.pick(forward)?.windowId)
    }

    @Test fun animatorKeepsFocusOrderAndHeldWindowsDoNotLag() {
        val m = WindowManager(); val a = WindowAnimator()
        val first = m.open(AppType.NOTES, 0f); val second = m.open(AppType.CLOCK, 0f)
        a.update(m.windows, 0.016f, 0)
        m.focus(first.id)
        assertEquals(listOf(second.id, first.id), a.update(m.windows, 0.016f, 0).map { it.window.id })
        m.replace(m.get(first.id)!!.copy(yaw = 0.8f, distance = 2.4f))
        assertTrue(a.update(m.windows, 0.016f, 0).last().window.yaw < 0.7f)
        val held = a.update(m.windows, 0.016f, 0, setOf(first.id)).last().window
        assertEquals(0.8f, held.yaw, 1e-5f); assertEquals(2.4f, held.distance, 1e-5f)
    }

    @Test fun grabbingAMovingWindowDoesNotJump() {
        val m = WindowManager(); val w = m.open(AppType.NOTES, 0f)
        val c = InteractionController(m, Recorder())
        m.present(listOf(w))
        m.replace(w.copy(yaw = 0.5f))
        val grab = controlLayout(w).first { it.region == Region.GRAB }
        val point = w.point(grab.x, grab.y)
        val at = Ray(Vec3(0f, 0f, 0f), point * (1f / point.length))
        c.handle(1, PointerPhase.DOWN, at)
        c.handle(1, PointerPhase.MOVE, at)
        assertEquals(0f, m.get(w.id)!!.yaw, 1e-3f)
        assertEquals(w.pitch, m.get(w.id)!!.pitch, 1e-3f)
    }

    @Test fun skippingATutorialLessonDoesNotMasterIt() {
        val t = TutorialProgress()
        repeat(Lesson.entries.size) { t.begin(0); t.skip() }
        assertNull(t.current); assertTrue(t.practised.isEmpty()); assertEquals("", t.encode())
    }

    @Test fun tutorialCountsOnlyActionsDoneDuringTheLesson() {
        val t = TutorialProgress()
        t.begin(4); assertFalse(t.observe(4))
        assertTrue(t.observe(5)); assertEquals(Lesson.SCROLL, t.current)
        t.skip(); assertEquals(Lesson.MOVE, t.current)
        t.begin(2); assertFalse(t.observe(2)); assertTrue(t.observe(3))
        assertEquals(setOf(Lesson.TAP, Lesson.MOVE), t.practised)
        // Saved results survive a replay and unknown names are ignored.
        val again = TutorialProgress(t.encode() + ",NOPE")
        again.skip(); assertEquals(setOf(Lesson.TAP, Lesson.MOVE), again.practised)
        again.restart(); assertEquals(Lesson.TAP, again.current)
    }

    @Test fun nativeSetupAsksForOneStepAtATime() {
        val ready = ShizukuStatus(34, installed = true, running = true, version = 13, granted = true, uid = 2000)
        assertEquals(NativeState.READY, NativeApps.state(ready))
        assertEquals(NativeState.OLD_ANDROID, NativeApps.state(ready.copy(sdk = 30)))
        assertEquals(NativeState.INSTALL_SHIZUKU, NativeApps.state(ShizukuStatus(34, installed = false, running = false)))
        assertEquals(NativeState.START_SHIZUKU, NativeApps.state(ShizukuStatus(34, installed = true, running = false)))
        assertEquals(NativeState.UPDATE_SHIZUKU, NativeApps.state(ready.copy(version = 12)))
        // Revoked permission and a stopped service both leave the ready state.
        assertEquals(NativeState.CONNECT, NativeApps.state(ready.copy(granted = false)))
        assertEquals(NativeState.DENIED, NativeApps.state(ready.copy(granted = false, deniedForGood = true)))
        assertEquals(NativeState.START_SHIZUKU, NativeApps.state(ready.copy(running = false)))
        assertEquals(NativeState.WRONG_IDENTITY, NativeApps.state(ready.copy(uid = 0)))
    }

    @Test fun securityScreensAreProtectedFromInput() {
        for (name in listOf("com.android.settings", "com.google.android.permissioncontroller",
            "com.google.android.packageinstaller", "moe.shizuku.privileged.api", "com.nexus.visionos"))
            assertTrue(name, NativeApps.isProtected(name, "com.nexus.visionos"))
        assertFalse(NativeApps.isProtected("com.nexus.probetarget", "com.nexus.visionos"))
    }

    @Test fun nativeCoordinatesStayOnTheDisplay() {
        assertEquals(0f, NativeApps.pixel(-0.2f, 480), 0f)
        assertEquals(240f, NativeApps.pixel(0.5f, 480), 0f)
        assertEquals(479f, NativeApps.pixel(1f, 480), 0f)
        assertEquals(209, NativeApps.density(471, 760))
        assertEquals(120, NativeApps.density(200, 900)); assertEquals(320, NativeApps.density(1500, 1500))
    }

    @Test fun installStatusesHavePlainOutcomes() {
        assertEquals(InstallOutcome.NEEDS_CONFIRMATION, installOutcome(-1))
        assertEquals(InstallOutcome.INSTALLED, installOutcome(0))
        assertEquals(InstallOutcome.CANCELLED, installOutcome(3))
        assertEquals(InstallOutcome.CONFLICT, installOutcome(5))
        assertEquals(InstallOutcome.FAILED, installOutcome(99))
    }

    @Test fun onlyNativeWindowsHaveABackControlAndTheyAreNotRestored() {
        val m = WindowManager()
        val native = m.open(AppType.NATIVE, 0f); val notes = m.open(AppType.NOTES, 0f)
        assertTrue(controlLayout(native).any { it.region == Region.BACK })
        assertFalse(controlLayout(notes).any { it.region == Region.BACK })
        assertEquals(listOf(AppType.NOTES), LayoutCodec.decode(LayoutCodec.encode(m.windows, 0f, 0f), 0f, 0f).map { it.app })
    }
}
