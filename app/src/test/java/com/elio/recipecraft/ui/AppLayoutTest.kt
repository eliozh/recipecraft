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
    fun aSmallSquareWindowKeepsTheBottomBar() {
        assertEquals(AppLayout(NavigationType.BOTTOM_BAR, listDetail = false, compactHeight = true), layoutFor(360f, 380f))
    }
}
