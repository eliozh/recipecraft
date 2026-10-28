package com.elio.recipecraft.ui

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ui-test only offers exact touch-size assertions; these check the minimum touch target instead.

/** Asserts the node's touch target, including any minimum-size padding, is at least [min] high. */
fun SemanticsNodeInteraction.assertTouchHeightIsAtLeast(min: Dp = 48.dp): SemanticsNodeInteraction = assertTouchSize(min) { it.height }

/** Asserts the node's touch target, including any minimum-size padding, is at least [min] wide. */
fun SemanticsNodeInteraction.assertTouchWidthIsAtLeast(min: Dp = 48.dp): SemanticsNodeInteraction = assertTouchSize(min) { it.width }

private fun SemanticsNodeInteraction.assertTouchSize(min: Dp, size: (androidx.compose.ui.geometry.Rect) -> Float): SemanticsNodeInteraction {
    val node = fetchSemanticsNode()
    val actual = with(node.layoutInfo.density) { size(node.touchBoundsInRoot).toDp() }
    if (actual < min - 0.5.dp) throw AssertionError("Touch target is $actual, expected at least $min")
    return this
}
