package dev.ccdesk.nativeapp.ui

import org.junit.Assert.*
import org.junit.Test

/** Pure release, resistance and scrim policies shared by every gesture-driven surface. */
class MotionPolicyTest {
    private val distance = 300f
    private val velocity = 2400f

    @Test fun downwardFlingClosesEvenAfterAShortDrag() {
        assertTrue(DeskMotion.shouldDismissSheet(offset = 20f, height = 1200f, velocity = 2500f, distanceThreshold = distance, velocityThreshold = velocity))
    }

    @Test fun upwardFlingKeepsTheSheetOpenEvenPastTheDistanceThreshold() {
        assertFalse(DeskMotion.shouldDismissSheet(offset = 700f, height = 1200f, velocity = -2500f, distanceThreshold = distance, velocityThreshold = velocity))
    }

    @Test fun slowReleaseUsesTheSmallerOfDistanceAndHeightFraction() {
        // A short sheet (600px) closes after 30% of its height (180px), before the 300px distance.
        assertTrue(DeskMotion.shouldDismissSheet(181f, 600f, 0f, distance, velocity))
        assertFalse(DeskMotion.shouldDismissSheet(179f, 600f, 0f, distance, velocity))
        // A tall sheet uses the absolute distance.
        assertFalse(DeskMotion.shouldDismissSheet(299f, 2000f, 0f, distance, velocity))
        assertTrue(DeskMotion.shouldDismissSheet(300f, 2000f, 0f, distance, velocity))
    }

    @Test fun aSheetDraggedAboveItsRestingPositionNeverDismisses() {
        assertFalse(DeskMotion.shouldDismissSheet(-40f, 1200f, 5000f, distance, velocity))
        assertFalse(DeskMotion.shouldDismissSheet(0f, 1200f, 5000f, distance, velocity))
    }

    @Test fun rubberBandIsMonotonicBoundedAndLinearNearZero() {
        assertEquals(0f, DeskMotion.rubberBand(0f, 1000f), 0f)
        assertEquals(0f, DeskMotion.rubberBand(-10f, 1000f), 0f)
        var previous = 0f
        for (step in 1..200) {
            val value = DeskMotion.rubberBand(step * 25f, 1000f)
            assertTrue("rubber band must keep increasing", value > previous)
            assertTrue("rubber band must stay below the dimension", value < 1000f)
            previous = value
        }
        val small = DeskMotion.rubberBand(1f, 1000f)
        assertEquals(DeskMotion.RubberBandCoefficient, small, .001f)
    }

    @Test fun scrimFollowsDragProgressAndClamps() {
        assertEquals(1f, DeskMotion.scrimForOffset(0f, 800f), 0f)
        assertEquals(.5f, DeskMotion.scrimForOffset(400f, 800f), .0001f)
        assertEquals(0f, DeskMotion.scrimForOffset(1200f, 800f), 0f)
        assertEquals("Rubber-banding above rest must not over-darken", 1f, DeskMotion.scrimForOffset(-50f, 800f), 0f)
        assertEquals("Unmeasured panel keeps a full scrim", 1f, DeskMotion.scrimForOffset(10f, 0f), 0f)
    }

    @Test fun drawerReleasePrefersVelocityThenDistanceThenCurrentState() {
        assertTrue(DeskMotion.drawerTarget(open = false, dx = 5f, velocity = 900f, distanceThreshold = 60f, velocityThreshold = 500f))
        assertFalse(DeskMotion.drawerTarget(open = true, dx = 200f, velocity = -900f, distanceThreshold = 60f, velocityThreshold = 500f))
        assertTrue(DeskMotion.drawerTarget(open = false, dx = 61f, velocity = 10f, distanceThreshold = 60f, velocityThreshold = 500f))
        assertFalse(DeskMotion.drawerTarget(open = true, dx = -61f, velocity = 10f, distanceThreshold = 60f, velocityThreshold = 500f))
        assertTrue(DeskMotion.drawerTarget(open = true, dx = -20f, velocity = 10f, distanceThreshold = 60f, velocityThreshold = 500f))
    }

    @Test fun motionTokensStayWithinSaneBounds() {
        listOf(DeskMotion.ButtonPressScale, DeskMotion.ChipPressScale, DeskMotion.IconPressScale, DeskMotion.CardPressScale, DeskMotion.PanelEnterScale).forEach {
            assertTrue("press/enter scales are subtle reductions: $it", it in .8f..<1f)
        }
        listOf(DeskMotion.SheetScrim, DeskMotion.MenuScrim, DeskMotion.PanelScrim, DeskMotion.DrawerScrim).forEach { assertTrue(it in .2f..<.7f) }
        assertTrue("A cancelled press must not linger longer than its release", DeskMotion.MinimumPressVisibleMs < DeskMotion.PressHighlightOut)
    }
}
