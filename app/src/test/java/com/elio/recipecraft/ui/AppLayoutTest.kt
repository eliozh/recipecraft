package com.elio.recipecraft.ui

import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.computeWindowSizeClass
import org.junit.Assert.assertEquals
import org.junit.Test

class AppLayoutTest {

    private fun layoutFor(widthDp: Float, heightDp: Float) =
        appLayoutFor(WindowSizeClass.BREAKPOINTS_V1.computeWindowSizeClass(widthDp, heightDp))

    @Test
    fun aPhoneInPortraitGetsTheBottomBar() {
        assertEquals(AppLayout(NavigationType.BOTTOM_BAR, listDetail = false, compactHeight = false), layoutFor(412f, 915f))
        assertEquals(AppLayout.Compact, layoutFor(599f, 900f))
    }

    @Test
    fun aMediumWidthGetsTheRailWithoutListDetail() {
        assertEquals(AppLayout(NavigationType.RAIL, listDetail = false, compactHeight = false), layoutFor(600f, 900f))
        assertEquals(AppLayout(NavigationType.RAIL, listDetail = false, compactHeight = false), layoutFor(839f, 1280f))
    }

    @Test
    fun anExpandedWidthGetsTheRailAndListDetail() {
        assertEquals(AppLayout(NavigationType.RAIL, listDetail = true, compactHeight = false), layoutFor(840f, 600f))
        assertEquals(AppLayout(NavigationType.RAIL, listDetail = true, compactHeight = false), layoutFor(1280f, 800f))
    }

    @Test
    fun aPhoneInLandscapeHasACompactHeight() {
        assertEquals(AppLayout(NavigationType.RAIL, listDetail = true, compactHeight = true), layoutFor(952f, 427f))
        assertEquals(AppLayout(NavigationType.RAIL, listDetail = false, compactHeight = true), layoutFor(700f, 400f))
    }

    @Test
    fun aSmallSquareWindowIsTheCoverLayout() {
        val cover = AppLayout(NavigationType.BOTTOM_BAR, listDetail = false, compactHeight = true, cover = true)
        // The Galaxy Z Flip cover screen: 948 × 1048 px at 480 dpi.
        assertEquals(cover, layoutFor(316f, 349f))
        assertEquals(cover, layoutFor(360f, 380f))
        // The largest window that still counts: just under medium width and medium height.
        assertEquals(cover, layoutFor(599f, 479f))
    }

    @Test
    fun onlyCompactWidthAndCompactHeightTogetherAreTheCoverLayout() {
        assertEquals(false, layoutFor(412f, 915f).cover) // phone in portrait
        assertEquals(false, layoutFor(599f, 480f).cover) // medium height
        assertEquals(false, layoutFor(600f, 479f).cover) // medium width: a phone in landscape
        assertEquals(false, layoutFor(915f, 412f).cover)
        assertEquals(false, AppLayout.Compact.cover)
    }
}
