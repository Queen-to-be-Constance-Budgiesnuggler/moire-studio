package dk.cbavf.studio.feedback

import android.view.View
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.InternalComposeUiApi

/**
 * Watches taps on the whole studio without taking them: on a finger lift it finds the button, chip or switch under the finger in the
 * accessibility tree and tells [Feedback] its text. [Feedback] keeps the tap only if the use-case catalog names it, so this is
 * how "the person pressed Group in Draw" is noticed without every room reporting it itself. Does nothing while feedback is off.
 */
fun Modifier.tapProbe(room: () -> String): Modifier = composed {
    if (!Feedback.availableInBuild) return@composed this
    val view = LocalView.current
    val slop = LocalViewConfiguration.current.touchSlop
    val currentRoom = rememberUpdatedState(room)
    var origin by remember { mutableStateOf(Offset.Zero) }
    this
        .onGloballyPositioned { origin = it.positionInWindow() }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val up = waitForUpOrCancellation(PointerEventPass.Initial) ?: return@awaitEachGesture
                if ((up.position - down.position).getDistance() > slop) return@awaitEachGesture
                if (Feedback.level == FeedbackLevel.Off) return@awaitEachGesture
                TapProbe.labelAt(view, origin + up.position)?.let { Feedback.tap(currentRoom.value(), it) }
            }
        }
}

object TapProbe {
    /** The text of the clickable control under [p] (window coordinates), or null if none or it has no text. */
    @OptIn(InternalComposeUiApi::class)
    fun labelAt(view: View, p: Offset): String? {
        val root = (view as? RootForTest)?.semanticsOwner?.rootSemanticsNode ?: return null
        return find(root, p)
    }

    private fun find(n: SemanticsNode, p: Offset): String? {
        for (c in n.children.asReversed()) {
            if (c.boundsInWindow.contains(p)) find(c, p)?.let { return it }
        }
        val cfg = n.config
        if (!cfg.contains(SemanticsActions.OnClick)) return null
        if (cfg.contains(SemanticsProperties.EditableText)) return null // never read what a person is typing
        val text = cfg.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
            ?: cfg.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
        return text?.takeIf { it.isNotBlank() }
    }
}
