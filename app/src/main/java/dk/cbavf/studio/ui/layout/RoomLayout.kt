package dk.cbavf.studio.ui.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** How a room lays out its picture and its controls. */
enum class LayoutMode { Stacked, SideBySide }

/**
 * The sizes behind [RoomSplit], as plain functions of the window size in dp, so they can be tested.
 * Controls never stretch across a wide window: a panel is at most [MAX_PANEL] dp wide, and in a wide, low window
 * (landscape) the panel sits beside the picture instead of under it.
 */
object RoomLayoutMath {
    const val MIN_PANEL = 280f
    const val DEFAULT_PANEL = 420f
    const val MAX_PANEL = 560f

    /** The widest a stacked (portrait) controls panel gets on a big screen. */
    const val MAX_STACKED = 640f

    /** Below this width there is no room for a side panel beside a useful picture. */
    const val MIN_SIDE_BY_SIDE_WIDTH = 560f

    fun mode(widthDp: Float, heightDp: Float): LayoutMode =
        if (widthDp > heightDp && widthDp >= MIN_SIDE_BY_SIDE_WIDTH) LayoutMode.SideBySide else LayoutMode.Stacked

    /**
     * The side panel's width: what the person asked for (0 = the default), kept between the minimum and the smaller of the
     * maximum and 60 % of the window, so the picture always keeps at least 40 %.
     */
    fun panelWidth(requestedDp: Float, windowWidthDp: Float): Float {
        // The default and the widest are math parameters (Studio → Config), these constants are their defaults.
        val wanted = if (requestedDp <= 0f) dk.cbavf.studio.math.MathParams.get("layout.panelDefaultDp").toFloat() else requestedDp
        val upper = minOf(dk.cbavf.studio.math.MathParams.get("layout.panelMaxDp").toFloat(), windowWidthDp * 0.6f).coerceAtLeast(MIN_PANEL)
        return wanted.coerceIn(MIN_PANEL, upper)
    }

    /** The readable width of a page-like room (settings, lists, forms). */
    const val PAGE_MAX = 760f

    /** The rail of tabs a page puts on its left in landscape (Studio). */
    const val RAIL = 200f

    /**
     * The width a page-like room is kept to: [maxDp], plus [landscapeExtraDp] in landscape (room for a rail beside the page), or
     * null when the window is no wider than that and the page simply fills it.
     */
    fun pageWidth(windowWidthDp: Float, windowHeightDp: Float, maxDp: Float = PAGE_MAX, landscapeExtraDp: Float = 0f): Float? {
        val cap = maxDp + if (mode(windowWidthDp, windowHeightDp) == LayoutMode.SideBySide) landscapeExtraDp else 0f
        return if (windowWidthDp > cap) cap else null
    }

    /** True when a page has room for a rail of tabs beside it: the same wide, low window that puts a room's panel at the side. */
    fun pageRail(windowWidthDp: Float, windowHeightDp: Float): Boolean =
        mode(windowWidthDp, windowHeightDp) == LayoutMode.SideBySide
}

/** The remembered width of the side panel, in dp (0 = the default). Provided once by the studio. */
class PanelSizes(val widthDp: () -> Float, val setWidthDp: (Float) -> Unit)

val LocalPanelSizes = staticCompositionLocalOf<PanelSizes?> { null }

/**
 * A card that a control can ask the controls panel to show inside itself, so an exact-value box opens where the controls are
 * instead of covering the picture. Absent outside a [RoomSplit]; callers then fall back to an ordinary dialog.
 */
class PanelOverlayHost {
    var content by mutableStateOf<(@Composable () -> Unit)?>(null)
    var onDismiss by mutableStateOf<(() -> Unit)?>(null)

    fun show(onDismiss: () -> Unit, content: @Composable () -> Unit) {
        this.onDismiss = onDismiss
        this.content = content
    }

    fun clear() {
        content = null
        onDismiss = null
    }
}

val LocalPanelOverlay = staticCompositionLocalOf<PanelOverlayHost?> { null }

/**
 * A room: [canvas] fills the space, [controls] sit in a bounded panel. In portrait the panel is under the picture at its
 * natural height, never wider than [RoomLayoutMath.MAX_STACKED]. In landscape it is a scrolling panel beside the picture, whose
 * width can be dragged by its edge and is remembered.
 */
@Composable
fun RoomSplit(
    modifier: Modifier = Modifier.fillMaxSize(),
    controls: @Composable () -> Unit,
    canvas: @Composable BoxScope.() -> Unit,
) {
    BoxWithConstraints(modifier) {
        val w = maxWidth.value
        val h = maxHeight.value
        when (RoomLayoutMath.mode(w, h)) {
            LayoutMode.Stacked -> Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth(), content = canvas)
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    PanelHost(Modifier.widthIn(max = RoomLayoutMath.MAX_STACKED.dp).fillMaxWidth()) { controls() }
                }
            }
            LayoutMode.SideBySide -> SidePanelRow(w, controls, canvas)
        }
    }
}

/**
 * A page-like room with its own bars (Library, Node Lab). In portrait it keeps its own order: [bars] above the [content] when
 * [barsOnTop], else below. In landscape the bars move to the same scrolling side panel as a [RoomSplit], so the content keeps
 * the window's full height.
 */
@Composable
fun PageSplit(
    modifier: Modifier = Modifier.fillMaxSize(),
    barsOnTop: Boolean,
    bars: @Composable () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    BoxWithConstraints(modifier) {
        val w = maxWidth.value
        when (RoomLayoutMath.mode(w, maxHeight.value)) {
            LayoutMode.Stacked -> Column(Modifier.fillMaxSize()) {
                if (barsOnTop) bars()
                Box(Modifier.weight(1f).fillMaxWidth(), content = content)
                if (!barsOnTop) bars()
            }
            LayoutMode.SideBySide -> SidePanelRow(w, bars, content)
        }
    }
}

/** The landscape layout of a room: [canvas] on the left, [controls] in a scrolling panel on the right with a draggable edge. */
@Composable
private fun SidePanelRow(w: Float, controls: @Composable () -> Unit, canvas: @Composable BoxScope.() -> Unit) {
    val sizes = LocalPanelSizes.current
    var requested by remember { mutableStateOf(sizes?.widthDp?.invoke() ?: 0f) }
    val panel = RoomLayoutMath.panelWidth(requested, w)
    val density = LocalDensity.current.density
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxHeight(), content = canvas)
        // The edge: drag it sideways to scale the panel.
        Box(
            Modifier
                .width(14.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                .pointerInput(w) {
                    detectHorizontalDragGestures(
                        onDragEnd = { sizes?.setWidthDp?.invoke(requested) },
                    ) { change, dx ->
                        change.consume()
                        requested = RoomLayoutMath.panelWidth(panel - dx / density, w)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.width(3.dp).fillMaxHeight(0.12f).background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)))
        }
        PanelHost(Modifier.width(panel.dp).fillMaxHeight()) {
            CompositionLocalProvider(LocalSideBySide provides true) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { controls() }
            }
        }
    }
}

/** True while the controls are in the scrolling panel beside the picture (landscape). */
val LocalSideBySide = staticCompositionLocalOf { false }

/**
 * A scrolling stretch of controls. Under the picture it is capped at [maxHeight] so the picture keeps its space; in the
 * side panel the whole panel scrolls, so the cap and the inner scroll go away.
 */
@Composable
fun Modifier.panelScroll(maxHeight: androidx.compose.ui.unit.Dp): Modifier =
    if (LocalSideBySide.current) this else this.heightIn(max = maxHeight).verticalScroll(rememberScrollState())

/**
 * A dialog that opens inside the controls panel of a room (over the controls, not the picture) and as an ordinary dialog
 * anywhere else. [content] is the body; [confirm] holds its buttons.
 */
@Composable
fun PanelDialog(title: String, onDismiss: () -> Unit, confirm: @Composable () -> Unit, content: @Composable () -> Unit) {
    val host = LocalPanelOverlay.current
    if (host != null) {
        androidx.compose.runtime.SideEffect {
            host.show(onDismiss) {
                Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                    androidx.compose.material3.Text(title, style = MaterialTheme.typography.titleLarge)
                    content()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) { confirm() }
                }
            }
        }
        androidx.compose.runtime.DisposableEffect(host) { onDispose { host.clear() } }
    } else androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { androidx.compose.material3.Text(title) },
        text = { Box(Modifier.verticalScroll(rememberScrollState())) { content() } },
        confirmButton = confirm,
    )
}

/** A plain vertical scroll for controls that are taller than the screen; beside the picture the side panel scrolls instead. */
@Composable
fun Modifier.panelScrollOpen(): Modifier =
    if (LocalSideBySide.current) this else this.verticalScroll(rememberScrollState())

/**
 * A box of controls outside a [RoomSplit] (a module's back, say) whose exact-value boxes open over the box itself, as they do in
 * a room's controls panel. [cardDirection] lays the card out in that direction (the studio's back is mirrored, its cards are not).
 */
@Composable
fun ControlsPanel(modifier: Modifier = Modifier, cardDirection: LayoutDirection? = null, content: @Composable () -> Unit) =
    PanelHost(modifier, cardDirection, content)

/** The controls' own box, which can lay a card over just itself. */
@Composable
private fun PanelHost(modifier: Modifier, cardDirection: LayoutDirection? = null, content: @Composable () -> Unit) {
    val host = remember { PanelOverlayHost() }
    val card = host.content
    CompositionLocalProvider(LocalPanelOverlay provides host) {
        // A stacked panel is only as tall as its controls, so keep room for the card while one is open.
        Box(modifier.then(if (card != null) Modifier.heightIn(min = 360.dp) else Modifier)) {
            content()
            if (card != null) {
                Box(
                    Modifier
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .pointerInput(host) { detectTapGestures { host.onDismiss?.invoke() } },
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        tonalElevation = 6.dp,
                        modifier = Modifier.padding(12.dp).widthIn(max = 420.dp).pointerInput(Unit) { detectTapGestures { } },
                    ) {
                        Box(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
                            if (cardDirection == null) card()
                            else CompositionLocalProvider(LocalLayoutDirection provides cardDirection) { card() }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Keeps a page-like room (settings, lists, forms) at a readable width: on a window wider than [maxDp] the content is a centred
 * column of that width instead of lines that run across the whole screen.
 */
@Composable
fun BoundedWidth(maxDp: Float = RoomLayoutMath.PAGE_MAX, landscapeExtraDp: Float = 0f, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val bound = RoomLayoutMath.pageWidth(maxWidth.value, maxHeight.value, maxDp, landscapeExtraDp)
        if (bound != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = bound.dp).fillMaxHeight()) { content() }
            }
        } else content()
    }
}
