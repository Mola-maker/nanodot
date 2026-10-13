package io.github.nanomuse.dot.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DotGestureReducerTest {
    private val limits = GestureLimits(300L, 500L, 8f, 100f)
    private val origin = PointPx(10f, 20f)

    @Test fun holdingOrbEmitsOneMenuIntentWithoutTapOrDragOnRelease() {
        val reducer = DotGestureReducer(limits)
        reducer.accept(GestureEvent.Down(origin, 0L, 0))
        assertEquals(emptyList<DotIntent>(), reducer.accept(GestureEvent.Tick(499L)).intents)
        val held = reducer.accept(GestureEvent.Tick(500L))
        // This assertion runs against the old sealed interface too: before implementation it
        // fails because holding the orb suppresses every action rather than opening a menu.
        assertEquals("A stationary hold must expose exactly one menu action", 1, held.intents.size)
        assertEquals("LongPress", held.intents.single().javaClass.simpleName)
        assertNull(held.deadlineMs)
        assertEquals(emptyList<DotIntent>(), reducer.accept(GestureEvent.Tick(900L)).intents)
        assertEquals(emptyList<DotIntent>(), reducer.accept(GestureEvent.Move(PointPx(50f, 20f), 910L, 0)).intents)
        assertEquals(emptyList<DotIntent>(), reducer.accept(GestureEvent.Up(origin, 920L, 0)).intents)
        assertEquals(emptyList<DotIntent>(), reducer.accept(GestureEvent.Tick(1500L)).intents)
    }

    private fun tap(reducer: DotGestureReducer, downMs: Long, upMs: Long): GestureResult {
        assertEquals(emptyList<DotIntent>(), reducer.accept(GestureEvent.Down(origin, downMs, 0)).intents)
        return reducer.accept(GestureEvent.Up(origin, upMs, 0))
    }

    @Test fun exclusiveTaps() {
        val single = DotGestureReducer(limits)
        assertEquals(GestureResult(emptyList(), 420L), tap(single, 0L, 120L))
        assertEquals(GestureResult(listOf(DotIntent.Single), null), single.accept(GestureEvent.Tick(420L)))
        assertEquals(emptyList<DotIntent>(), single.accept(GestureEvent.Tick(900L)).intents)

        val double = DotGestureReducer(limits)
        tap(double, 0L, 120L)
        assertEquals(GestureResult(emptyList(), 650L), tap(double, 230L, 350L))
        assertEquals(GestureResult(listOf(DotIntent.Double), null), double.accept(GestureEvent.Tick(650L)))

        val triple = DotGestureReducer(limits)
        tap(triple, 0L, 120L)
        tap(triple, 230L, 350L)
        assertEquals(listOf(DotIntent.Triple), tap(triple, 460L, 580L).intents)
        assertEquals(emptyList<DotIntent>(), triple.accept(GestureEvent.Tick(1000L)).intents)
    }

    @Test fun secondDownSuspendsDeadline() {
        val reducer = DotGestureReducer(limits)
        tap(reducer, 0L, 120L)
        assertEquals(GestureResult(emptyList(), 900L), reducer.accept(GestureEvent.Down(origin, 400L, 0)))
        assertEquals(emptyList<DotIntent>(), reducer.accept(GestureEvent.Tick(420L)).intents)
        assertEquals(GestureResult(emptyList(), 950L), reducer.accept(GestureEvent.Up(origin, 650L, 0)))
        assertEquals(listOf(DotIntent.Double), reducer.accept(GestureEvent.Tick(950L)).intents)
    }

    @Test fun dragAndCancelClearCandidates() {
        val reducer = DotGestureReducer(limits)
        tap(reducer, 0L, 120L)
        reducer.accept(GestureEvent.Down(origin, 200L, 0))
        val moved = PointPx(19f, 20f)
        assertEquals(emptyList<DotIntent>(), reducer.accept(GestureEvent.Move(moved, 230L, 0)).intents)
        assertEquals(GestureResult(listOf(DotIntent.Drag(listOf(origin, moved))), null),
            reducer.accept(GestureEvent.Up(moved, 250L, 0)))
        assertEquals(emptyList<DotIntent>(), reducer.accept(GestureEvent.Tick(500L)).intents)

        tap(reducer, 600L, 650L)
        assertEquals(GestureResult(emptyList(), null), reducer.accept(GestureEvent.Cancel))
        assertEquals(emptyList<DotIntent>(), reducer.accept(GestureEvent.Tick(1000L)).intents)

        reducer.accept(GestureEvent.Down(origin, 1100L, 0))
        reducer.accept(GestureEvent.Down(origin, 1110L, 1))
        assertEquals(emptyList<DotIntent>(), reducer.accept(GestureEvent.Up(origin, 1150L, 0)).intents)
        assertNull(reducer.accept(GestureEvent.Tick(2000L)).deadlineMs)
    }

    @Test fun thirdDownJustInsideThresholdCombines() {
        val reducer = DotGestureReducer(limits)
        tap(reducer, 0L, 120L)
        tap(reducer, 230L, 350L)
        assertEquals(listOf(DotIntent.Triple), tap(reducer, 649L, 700L).intents)
        assertEquals(emptyList<DotIntent>(), reducer.accept(GestureEvent.Tick(1000L)).intents)
    }

    @Test fun thirdDownAtThresholdStartsNewSequenceRegardlessOfTickOrdering() {
        for (tickFirst in listOf(false, true)) {
            val reducer = DotGestureReducer(limits)
            tap(reducer, 0L, 120L)
            tap(reducer, 230L, 350L)
            val atBoundary = mutableListOf<DotIntent>()
            if (tickFirst) atBoundary += reducer.accept(GestureEvent.Tick(650L)).intents
            atBoundary += reducer.accept(GestureEvent.Down(origin, 650L, 0)).intents
            if (!tickFirst) atBoundary += reducer.accept(GestureEvent.Tick(650L)).intents
            assertEquals(listOf(DotIntent.Double), atBoundary)
            assertEquals(GestureResult(emptyList(), 1000L), reducer.accept(GestureEvent.Up(origin, 700L, 0)))
            assertEquals(listOf(DotIntent.Single), reducer.accept(GestureEvent.Tick(1000L)).intents)
        }
    }

    @Test fun expiredCandidateDoesNotJoinLaterTap() {
        val reducer = DotGestureReducer(limits)
        tap(reducer, 0L, 120L)
        assertEquals(listOf(DotIntent.Single), reducer.accept(GestureEvent.Tick(421L)).intents)
        tap(reducer, 500L, 620L)
        assertEquals(GestureResult(listOf(DotIntent.Single), null), reducer.accept(GestureEvent.Tick(920L)))
    }

    @Test fun longPressClearsSuspendedCandidateAtThreshold() {
        val reducer = DotGestureReducer(limits)
        tap(reducer, 0L, 120L)
        reducer.accept(GestureEvent.Down(origin, 200L, 0))
        assertEquals(GestureResult(emptyList(), 700L), reducer.accept(GestureEvent.Tick(699L)))
        assertEquals(GestureResult(listOf(DotIntent.LongPress), null), reducer.accept(GestureEvent.Tick(700L)))
        assertEquals(emptyList<DotIntent>(), reducer.accept(GestureEvent.Move(PointPx(50f, 20f), 710L, 0)).intents)
        assertEquals(emptyList<DotIntent>(), reducer.accept(GestureEvent.Up(origin, 750L, 0)).intents)
        assertEquals(emptyList<DotIntent>(), reducer.accept(GestureEvent.Tick(1100L)).intents)
    }

    @Test fun longPressIsDetectedByUpWhenTimerWasDelayed() {
        val reducer = DotGestureReducer(limits)
        assertEquals(GestureResult(emptyList(), 500L), reducer.accept(GestureEvent.Down(origin, 0L, 0)))
        assertEquals(GestureResult(listOf(DotIntent.LongPress), null), reducer.accept(GestureEvent.Up(origin, 500L, 0)))
        assertEquals(emptyList<DotIntent>(), reducer.accept(GestureEvent.Tick(900L)).intents)
    }

    @Test fun freshMovementAtOrAfterDeadlineWinsBeforeLongPressWasCommitted() {
        for (time in listOf(500L, 501L)) {
            val reducer = DotGestureReducer(limits)
            val moved = PointPx(19f, 20f)
            val end = PointPx(35f, 31f)
            reducer.accept(GestureEvent.Down(origin, 0L, 0))
            assertEquals("Observed movement must win over a delayed timer at $time",
                GestureResult(emptyList(), null), reducer.accept(GestureEvent.Move(moved, time, 0)))
            assertEquals(emptyList<DotIntent>(), reducer.accept(GestureEvent.Tick(time + 1)).intents)
            assertEquals(listOf(DotIntent.Drag(listOf(origin, moved, end))),
                reducer.accept(GestureEvent.Up(end, time + 2, 0)).intents)
            assertEquals(emptyList<DotIntent>(), reducer.accept(GestureEvent.Tick(time + 500)).intents)
        }
    }

    @Test fun firstMovementOnUpAtOrAfterDeadlineRemainsACompleteDrag() {
        for (time in listOf(500L, 501L)) {
            val reducer = DotGestureReducer(limits)
            val end = PointPx(19f, 20f)
            reducer.accept(GestureEvent.Down(origin, 0L, 0))
            assertEquals("UP must include its fresh position before overdue hold arbitration",
                GestureResult(listOf(DotIntent.Drag(listOf(origin, end))), null),
                reducer.accept(GestureEvent.Up(end, time, 0)))
            assertEquals(emptyList<DotIntent>(), reducer.accept(GestureEvent.Tick(time + 500)).intents)
        }
    }

    @Test fun movementCannotReopenDragAfterLongPressWasCommittedAtDeadline() {
        for (time in listOf(500L, 501L)) {
            val reducer = DotGestureReducer(limits)
            val end = PointPx(19f, 20f)
            reducer.accept(GestureEvent.Down(origin, 0L, 0))
            assertEquals(listOf(DotIntent.LongPress), reducer.accept(GestureEvent.Tick(500L)).intents)
            assertEquals(GestureResult(emptyList(), null), reducer.accept(GestureEvent.Move(end, time, 0)))
            assertEquals(GestureResult(emptyList(), null), reducer.accept(GestureEvent.Up(end, time + 1, 0)))
            assertEquals(emptyList<DotIntent>(), reducer.accept(GestureEvent.Tick(time + 500)).intents)
        }
    }

    @Test fun holdingThirdPressOpensMenuWithoutLeakingTripleOrEarlierDouble() {
        val reducer = DotGestureReducer(limits)
        tap(reducer, 0L, 100L)
        tap(reducer, 150L, 200L)
        reducer.accept(GestureEvent.Down(origin, 250L, 0))
        assertEquals(listOf(DotIntent.LongPress), reducer.accept(GestureEvent.Tick(750L)).intents)
        assertEquals(emptyList<DotIntent>(), reducer.accept(GestureEvent.Up(origin, 900L, 0)).intents)
        assertEquals(emptyList<DotIntent>(), reducer.accept(GestureEvent.Tick(1500L)).intents)
    }

    @Test fun cancellationAndSecondPointerWinAtLongPressDeadline() {
        for (cancel in listOf(true, false)) {
            val reducer = DotGestureReducer(limits)
            reducer.accept(GestureEvent.Down(origin, 0L, 0))
            val interruption = if (cancel) GestureEvent.Cancel else GestureEvent.Down(origin, 500L, 1)
            assertEquals(emptyList<DotIntent>(), reducer.accept(interruption).intents)
            assertEquals(emptyList<DotIntent>(), reducer.accept(GestureEvent.Tick(500L)).intents)
            assertEquals(emptyList<DotIntent>(), reducer.accept(GestureEvent.Up(origin, 510L, 0)).intents)
        }
    }

    @Test fun longDragRetainsTrajectoryPastLongPressThreshold() {
        val reducer = DotGestureReducer(limits)
        val middle = PointPx(19f, 20f)
        val end = PointPx(40f, 25f)
        reducer.accept(GestureEvent.Down(origin, 0L, 0))
        reducer.accept(GestureEvent.Move(middle, 100L, 0))
        assertEquals(GestureResult(emptyList(), null), reducer.accept(GestureEvent.Tick(500L)))
        reducer.accept(GestureEvent.Move(end, 2000L, 0))
        assertEquals(listOf(DotIntent.Drag(listOf(origin, middle, end))),
            reducer.accept(GestureEvent.Up(end, 2100L, 0)).intents)
    }

    @Test fun touchSlopIsEuclideanAndStrict() {
        val reducer = DotGestureReducer(limits)
        reducer.accept(GestureEvent.Down(origin, 0L, 0))
        val edge = PointPx(18f, 20f)
        reducer.accept(GestureEvent.Move(edge, 50L, 0))
        assertEquals(emptyList<DotIntent>(), reducer.accept(GestureEvent.Up(edge, 100L, 0)).intents)
        assertEquals(listOf(DotIntent.Single), reducer.accept(GestureEvent.Tick(400L)).intents)

        val diagonal = PointPx(16f, 26f)
        reducer.accept(GestureEvent.Down(origin, 500L, 0))
        assertEquals(listOf(DotIntent.Drag(listOf(origin, diagonal))),
            reducer.accept(GestureEvent.Up(diagonal, 600L, 0)).intents)
    }

    @Test fun secondPointerLiftedFirstCannotLeakTapOrResumeDrag() {
        val reducer = DotGestureReducer(limits)
        tap(reducer, 0L, 120L)
        reducer.accept(GestureEvent.Down(origin, 200L, 0))
        reducer.accept(GestureEvent.Move(PointPx(19f, 20f), 220L, 0))
        assertEquals(GestureResult(emptyList(), null), reducer.accept(GestureEvent.Down(origin, 230L, 1)))
        reducer.accept(GestureEvent.Up(origin, 240L, 1))
        assertEquals(GestureResult(emptyList(), null), reducer.accept(GestureEvent.Move(PointPx(40f, 20f), 250L, 0)))
        assertEquals(GestureResult(emptyList(), null), reducer.accept(GestureEvent.Up(origin, 260L, 0)))
        assertEquals(emptyList<DotIntent>(), reducer.accept(GestureEvent.Tick(600L)).intents)
        tap(reducer, 700L, 800L)
        assertEquals(listOf(DotIntent.Single), reducer.accept(GestureEvent.Tick(1100L)).intents)
    }

    @Test fun cancelResetsRotationAndAllowsFreshPointerSequence() {
        val reducer = DotGestureReducer(limits)
        reducer.accept(GestureEvent.Down(origin, 0L, 0))
        reducer.accept(GestureEvent.Down(origin, 10L, 1))
        assertEquals(GestureResult(emptyList(), null), reducer.accept(GestureEvent.Cancel))
        tap(reducer, 20L, 100L)
        assertEquals(listOf(DotIntent.Single), reducer.accept(GestureEvent.Tick(400L)).intents)
        assertEquals(emptyList<DotIntent>(), reducer.accept(GestureEvent.Tick(500L)).intents)
    }

    @Test fun distantTapRetainsEarlierDeadlineWithoutMerging() {
        val reducer = DotGestureReducer(limits)
        val far = PointPx(111f, 20f)
        tap(reducer, 0L, 120L)
        assertEquals(GestureResult(emptyList(), 420L), reducer.accept(GestureEvent.Down(far, 200L, 0)))
        assertEquals(GestureResult(emptyList(), 420L), reducer.accept(GestureEvent.Up(far, 250L, 0)))
        assertEquals(GestureResult(listOf(DotIntent.Single), 550L), reducer.accept(GestureEvent.Tick(420L)))
        assertEquals(GestureResult(listOf(DotIntent.Single), null), reducer.accept(GestureEvent.Tick(550L)))
    }

    @Test fun heldDistantPressDoesNotSuspendEarlierTapDeadline() {
        val reducer = DotGestureReducer(limits)
        val far = PointPx(111f, 20f)
        tap(reducer, 0L, 120L)
        assertEquals(GestureResult(emptyList(), 420L), reducer.accept(GestureEvent.Down(far, 200L, 0)))
        assertEquals(GestureResult(listOf(DotIntent.Single), 700L), reducer.accept(GestureEvent.Tick(420L)))
        assertEquals(GestureResult(listOf(DotIntent.LongPress), null), reducer.accept(GestureEvent.Tick(700L)))
        assertEquals(GestureResult(emptyList(), null), reducer.accept(GestureEvent.Up(far, 800L, 0)))
    }

    @Test fun cancelClearsEveryIndependentPendingTap() {
        val reducer = DotGestureReducer(limits)
        tap(reducer, 0L, 120L)
        val far = PointPx(111f, 20f)
        reducer.accept(GestureEvent.Down(far, 200L, 0))
        assertEquals(GestureResult(emptyList(), 420L), reducer.accept(GestureEvent.Up(far, 250L, 0)))
        assertEquals(GestureResult(emptyList(), null), reducer.accept(GestureEvent.Cancel))
        assertEquals(GestureResult(emptyList(), null), reducer.accept(GestureEvent.Tick(1000L)))
    }

    @Test fun secondDownAtThresholdStartsFreshSingle() {
        val reducer = DotGestureReducer(limits)
        tap(reducer, 0L, 120L)
        assertEquals(GestureResult(listOf(DotIntent.Single), 920L), reducer.accept(GestureEvent.Down(origin, 420L, 0)))
        assertEquals(GestureResult(emptyList(), 800L), reducer.accept(GestureEvent.Up(origin, 500L, 0)))
        assertEquals(GestureResult(listOf(DotIntent.Single), null), reducer.accept(GestureEvent.Tick(800L)))
    }

    @Test fun spatialMergeComparesDownPositionsDespiteSmallTapMotion() {
        val reducer = DotGestureReducer(limits)
        val firstUp = PointPx(3f, 20f)
        val nextDown = PointPx(110f, 20f)
        reducer.accept(GestureEvent.Down(origin, 0L, 0))
        reducer.accept(GestureEvent.Up(firstUp, 120L, 0))
        assertEquals(GestureResult(emptyList(), 700L), reducer.accept(GestureEvent.Down(nextDown, 200L, 0)))
        reducer.accept(GestureEvent.Up(nextDown, 250L, 0))
        assertEquals(listOf(DotIntent.Double), reducer.accept(GestureEvent.Tick(550L)).intents)
    }

    @Test fun thirdDownOutsideThresholdProducesDoubleThenSingle() {
        val reducer = DotGestureReducer(limits)
        tap(reducer, 0L, 120L)
        tap(reducer, 230L, 350L)
        assertEquals(GestureResult(listOf(DotIntent.Double), 1151L), reducer.accept(GestureEvent.Down(origin, 651L, 0)))
        assertEquals(GestureResult(emptyList(), 1000L), reducer.accept(GestureEvent.Up(origin, 700L, 0)))
        assertEquals(GestureResult(listOf(DotIntent.Single), null), reducer.accept(GestureEvent.Tick(1000L)))
    }

    @Test fun foreignPointerMoveCancelsBeforeStationaryPressCanComplete() {
        val reducer = DotGestureReducer(limits)
        tap(reducer, 0L, 120L)
        reducer.accept(GestureEvent.Down(origin, 200L, 0))
        assertEquals(GestureResult(emptyList(), null), reducer.accept(GestureEvent.Move(origin, 210L, 1)))
        reducer.accept(GestureEvent.Up(origin, 220L, 1))
        assertEquals(GestureResult(emptyList(), null), reducer.accept(GestureEvent.Up(origin, 230L, 0)))
        assertEquals(GestureResult(emptyList(), null), reducer.accept(GestureEvent.Tick(600L)))
    }

    @Test fun foreignPointerUpCancelsBeforeActiveDragCanComplete() {
        val reducer = DotGestureReducer(limits)
        reducer.accept(GestureEvent.Down(origin, 0L, 0))
        reducer.accept(GestureEvent.Move(PointPx(19f, 20f), 100L, 0))
        reducer.accept(GestureEvent.Up(origin, 110L, 1))
        assertEquals(GestureResult(emptyList(), null), reducer.accept(GestureEvent.Up(origin, 120L, 0)))
    }

    @Test fun firstPointerLiftedFirstKeepsSequenceBlockedUntilAllPointersLift() {
        val reducer = DotGestureReducer(limits)
        assertEquals(GestureResult(emptyList(), 500L), reducer.accept(GestureEvent.Down(origin, 0L, 0)))
        reducer.accept(GestureEvent.Down(origin, 10L, 1))
        reducer.accept(GestureEvent.Up(origin, 20L, 0))
        assertEquals(GestureResult(emptyList(), null), reducer.accept(GestureEvent.Down(origin, 30L, 2)))
        reducer.accept(GestureEvent.Up(origin, 40L, 1))
        assertEquals(GestureResult(emptyList(), null), reducer.accept(GestureEvent.Up(origin, 50L, 2)))
        tap(reducer, 60L, 100L)
        assertEquals(listOf(DotIntent.Single), reducer.accept(GestureEvent.Tick(400L)).intents)
    }

    @Test fun dragStartingJustBeforeLongPressRemainsValidAtThreshold() {
        val reducer = DotGestureReducer(limits)
        val moved = PointPx(19f, 20f)
        reducer.accept(GestureEvent.Down(origin, 0L, 0))
        reducer.accept(GestureEvent.Move(moved, 499L, 0))
        assertEquals(listOf(DotIntent.Drag(listOf(origin, moved))), reducer.accept(GestureEvent.Up(moved, 500L, 0)).intents)
    }
}
