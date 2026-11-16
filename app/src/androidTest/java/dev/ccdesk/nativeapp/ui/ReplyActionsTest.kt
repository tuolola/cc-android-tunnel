package dev.ccdesk.nativeapp.ui

import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReplyActionsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun copyUsesTheCompleteReplyWithoutSendingACommand() {
        val row = ChatRow("run/final", "run", "reply", "Final block")
        val completeReply = "First block\n\nFinal block"
        var commands = 0
        lateinit var clipboard: ClipboardManager
        compose.setContent {
            clipboard = LocalClipboardManager.current
            DeskTheme {
                ChatTranscriptRow(row, false, false, false, showActions = true, actionText = completeReply) { _, _ -> commands++ }
            }
        }
        compose.onNodeWithContentDescription("复制回复").performClick()
        compose.runOnIdle {
            assertEquals(completeReply, clipboard.getText()?.text)
            assertEquals(0, commands)
        }
    }

    @Test fun choosingTextOpensSelectionWithoutExecutingTheSession() {
        val row = ChatRow("run/final", "run", "reply", "A reply available for native selection")
        var commands = 0
        compose.setContent {
            DeskTheme {
                OverlayHost {
                    ChatTranscriptRow(row, false, false, false, showActions = true) { _, _ -> commands++ }
                }
            }
        }
        compose.onNodeWithContentDescription("更多回复操作").performClick()
        compose.onNodeWithText("选择文本").performClick()
        compose.onNodeWithContentDescription("关闭文本选择").assertExists()
        compose.runOnIdle { assertEquals(0, commands) }
    }

    @Test fun popupCopiesTheCompleteReplyAndDismisses() {
        val row = ChatRow("run/final", "run", "reply", "Final block")
        val complete = "Earlier reply block\n\nFinal block"
        lateinit var clipboard: ClipboardManager
        var commands = 0
        compose.setContent {
            clipboard = LocalClipboardManager.current
            DeskTheme { ChatTranscriptRow(row, false, false, false, showActions = true, actionText = complete) { _, _ -> commands++ } }
        }
        compose.onNodeWithContentDescription("更多回复操作").performClick()
        compose.onNodeWithTag("reply-actions-popup").assertIsDisplayed()
        compose.onAllNodesWithTag(OverlayScrimTag).assertCountEquals(0)
        compose.onNodeWithText("复制回复").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("reply-actions-popup").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(complete, clipboard.getText()?.text)
            assertEquals(0, commands)
        }
    }

    @Test fun backAndOutsideTapDismissTheAnchoredMenuWithoutSending() {
        val row = ChatRow("run/final", "run", "reply", "Reply actions fixture")
        lateinit var root: View
        var commands = 0
        compose.setContent {
            root = LocalView.current
            DeskTheme {
                Box(Modifier.fillMaxSize()) {
                    ChatTranscriptRow(row, false, false, false, showActions = true) { _, _ -> commands++ }
                }
            }
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        compose.onNodeWithContentDescription("更多回复操作").performClick()
        compose.onNodeWithTag("reply-actions-popup").assertIsDisplayed()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNodeWithTag("reply-actions-popup").assertDoesNotExist()

        compose.onNodeWithContentDescription("更多回复操作").performClick()
        compose.onNodeWithTag("reply-actions-popup").assertIsDisplayed()
        val outside = FloatArray(2)
        compose.runOnIdle {
            val origin = IntArray(2)
            root.getLocationOnScreen(origin)
            outside[0] = origin[0] + root.width - 8 * root.resources.displayMetrics.density
            outside[1] = origin[1] + root.height / 2f
        }
        val time = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, outside[0], outside[1], 0)
        val up = MotionEvent.obtain(time, time + 50, MotionEvent.ACTION_UP, outside[0], outside[1], 0)
        try {
            assertTrue(instrumentation.uiAutomation.injectInputEvent(down, true))
            assertTrue(instrumentation.uiAutomation.injectInputEvent(up, true))
        } finally { down.recycle(); up.recycle() }
        compose.waitForIdle()
        compose.onNodeWithTag("reply-actions-popup").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, commands) }
    }

    @Test fun transparentShadowPaddingDismissesWithoutChangingTheVisibleMenuBounds() {
        val row = ChatRow("run/final", "run", "reply", "Reply actions fixture")
        var commands = 0
        compose.setContent {
            DeskTheme {
                Box(Modifier.fillMaxSize()) {
                    ChatTranscriptRow(row, false, false, false, showActions = true) { _, _ -> commands++ }
                }
            }
        }
        compose.onNodeWithContentDescription("更多回复操作").performClick()
        val panel = compose.onNodeWithTag("reply-actions-popup")
        panel.assertIsDisplayed().assertWidthIsEqualTo(224.dp)
        val bounds = panel.fetchSemanticsNode().boundsInRoot
        assertTrue("The visible panel tag excludes the shadow padding", bounds.left > 0f && bounds.top > 0f)

        // This point is outside the panel but still inside its independent Popup
        // window, so Android's dismissOnClickOutside alone cannot handle it.
        panel.performTouchInput { click(Offset(width + 12.dp.toPx(), height / 2f)) }
        compose.waitForIdle()
        compose.onNodeWithTag("reply-actions-popup").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, commands) }
    }

    @Test fun closingDuringTheFirstEnterKeepsTheWindowForExitAndCanReverseOpen() {
        val row = ChatRow("run/final", "run", "reply", "Reply actions fixture")
        var commands = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            DeskTheme { ChatTranscriptRow(row, false, false, false, showActions = true) { _, _ -> commands++ } }
        }
        compose.onNodeWithContentDescription("更多回复操作").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(48)
        val popupId = compose.onNode(isPopup()).fetchSemanticsNode().id

        // Close before the 180 ms enter fade completes: currentState is still
        // false, but the window must remain mounted for the reverse animation.
        compose.onNodeWithText("复制回复").performClick()
        compose.mainClock.advanceTimeByFrame()
        assertEquals("An interrupted first enter must animate out in the same window",
            popupId, compose.onNode(isPopup()).fetchSemanticsNode().id)

        compose.onNodeWithContentDescription("更多回复操作").performClick()
        compose.mainClock.advanceTimeByFrame()
        assertEquals("Reopening reverses the existing window instead of replacing it",
            popupId, compose.onNode(isPopup()).fetchSemanticsNode().id)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onAllNodes(isPopup()).assertCountEquals(1)
        compose.onNodeWithTag("reply-actions-popup").assertIsDisplayed().assertWidthIsEqualTo(224.dp)

        compose.onNodeWithText("复制回复").performClick()
        compose.waitForIdle()
        compose.onNode(isPopup()).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, commands) }
    }

    @Test fun shadowWindowPaddingPreservesPanelPositionAndTransformBounds() {
        val window = IntSize(1080, 2400)
        val menu = IntSize(648, 534)
        val inset = 144
        val paddedMenu = IntSize(menu.width + inset * 2, menu.height + inset * 2)
        val unpaddedProvider = ReplyMenuPositionProvider(0, 96, 0, 900, 36, 24)
        var positionedPanel: IntRect? = null
        val paddedProvider = ReplyMenuPositionProvider(0, 96, 0, 900, 36, 24, inset) { _, panel -> positionedPanel = panel }
        for (anchor in listOf(IntRect(0, 180, 108, 288), IntRect(930, 1300, 1038, 1408))) {
            val original = unpaddedProvider.calculatePosition(anchor, window, LayoutDirection.Ltr, menu)
            val padded = paddedProvider.calculatePosition(anchor, window, LayoutDirection.Ltr, paddedMenu)
            assertEquals("Only the window origin changes", original, padded + IntOffset(inset, inset))
            assertEquals("Transform calculations use visible panel bounds", IntRect(original, menu), positionedPanel)
        }
        assertTrue("The shadow window may extend offscreen while the menu keeps its margin",
            paddedProvider.calculatePosition(IntRect(0, 180, 108, 288), window, LayoutDirection.Ltr, paddedMenu).x < 0)
    }

    @Test fun anchorPositionChoosesAvailableSpaceAndAvoidsKeyboardAndSystemBars() {
        val window = IntSize(1080, 2400)
        val menu = IntSize(648, 534)
        val provider = ReplyMenuPositionProvider(0, 96, 0, 900, 36, 24)
        val upperAnchor = IntRect(930, 180, 1038, 288)
        val lowerAnchor = IntRect(930, 1300, 1038, 1408)
        val upper = provider.calculatePosition(upperAnchor, window, LayoutDirection.Ltr, menu)
        val lower = provider.calculatePosition(lowerAnchor, window, LayoutDirection.Ltr, menu)
        assertTrue("An upper anchor opens below the invoking button", upper.y >= upperAnchor.bottom)
        assertTrue("A lower anchor opens above the invoking button", lower.y + menu.height <= lowerAnchor.top)
        for (position in listOf(upper, lower)) {
            assertTrue(position.x >= 36)
            assertTrue(position.x + menu.width <= window.width - 36)
            assertTrue(position.y >= 96 + 36)
            assertTrue(position.y + menu.height <= window.height - 900 - 36)
        }
        val leftAnchor = IntRect(0, 180, 108, 288)
        assertEquals(36, provider.calculatePosition(leftAnchor, window, LayoutDirection.Rtl, menu).x)
    }
}
