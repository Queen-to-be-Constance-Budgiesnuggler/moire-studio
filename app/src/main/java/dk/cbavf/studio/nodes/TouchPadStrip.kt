package dk.cbavf.studio.nodes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dk.cbavf.studio.math.TouchPad
import dk.cbavf.studio.nodes.model.Node
import dk.cbavf.studio.nodes.model.Prop
import dk.cbavf.studio.ui.LocalCanvasColors
import dk.cbavf.studio.ui.fmt

/**
 * The pad of a selected Touch Pad node, under the toolbar: drag on it to set the node's X and Y
 * (one undo step per drag). While a finger is down the node's Down gate is on.
 */
@Composable
fun TouchPadStrip(state: NodesState, node: Node) {
    val x = (node.props["X"] as? Prop.Num)?.v ?: 0.5f
    val y = (node.props["Y"] as? Prop.Num)?.v ?: 0.5f
    var gesture by remember { mutableIntStateOf(0) }
    val dot = MaterialTheme.colorScheme.primary
    val cc = LocalCanvasColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(140.dp)
            .background(Color(cc.pad), RoundedCornerShape(6.dp))
            .pointerInput(node.id) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    gesture++
                    fun set(p: Offset) {
                        val (px, py) = TouchPad.position(p.x, p.y, size.width.toFloat(), size.height.toFloat())
                        state.setProps(node.id, mapOf("X" to Prop.Num(px), "Y" to Prop.Num(py)), "pad${node.id}.$gesture")
                    }
                    state.touching = state.touching + node.id
                    try {
                        set(down.position)
                        down.consume()
                        do {
                            val e = awaitPointerEvent()
                            e.changes.firstOrNull()?.let { set(it.position); it.consume() }
                        } while (e.changes.any { it.pressed })
                    } finally {
                        state.touching = state.touching - node.id
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(x * size.width, y * size.height)
            drawLine(Color(cc.padLine), Offset(c.x, 0f), Offset(c.x, size.height))
            drawLine(Color(cc.padLine), Offset(0f, c.y), Offset(size.width, c.y))
            drawCircle(dot, radius = 10.dp.toPx(), center = c)
        }
        Text(
            "Touch pad · X ${fmt(x)}, Y ${fmt(y)}",
            Modifier.align(Alignment.TopStart).padding(6.dp),
            color = Color(cc.nodeText),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
