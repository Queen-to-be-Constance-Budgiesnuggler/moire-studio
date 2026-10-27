package dk.cbavf.studio.uieditor

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * What a control's port carries on the back: a slider a number, a button a trigger, a toggle a gate, and an envelope takes a
 * gate. The rules for which cables fit are those of the rack's jacks.
 */
fun ControlKind.portType(): dk.cbavf.studio.routing.model.JackType = when (this) {
    ControlKind.Slider -> dk.cbavf.studio.routing.model.JackType.Value
    ControlKind.Button -> dk.cbavf.studio.routing.model.JackType.Trigger
    ControlKind.Toggle, ControlKind.Envelope -> dk.cbavf.studio.routing.model.JackType.Gate
}

/** What a control is, for how it is drawn on the front and which port it gets on the back. */
@Serializable
enum class ControlKind { Slider, Toggle, Button, Envelope }

/**
 * One control of the studio, as the UI Editor sees it: where it sits on the big canvas ([x], [y], [w], [h]
 * in canvas units) and which module it belongs to. [id] is `module.label`, stable across sessions, and is
 * also the id of the control's port on the back.
 */
@Serializable
data class ControlDesc(
    val id: String,
    val module: String,
    val label: String,
    val kind: ControlKind,
    val x: Float,
    val y: Float,
    val w: Float = CONTROL_W,
    val h: Float = CONTROL_H,
)

/** A module's panel on the canvas: a header and the controls laid out inside it. */
@Serializable
data class ModulePanel(val id: String, val title: String, val x: Float, val y: Float, val w: Float, val h: Float)

/** A jack cable on the back, from one control's port to another's. */
@Serializable
data class UiCable(val from: String, val to: String)

/**
 * The whole UI on one canvas: front layout (panels and controls) and back routing (cables). [panels] and [controls] are the
 * portrait front; [landscape] is the front used in landscape, null until it is first edited (see [UiOrientation]).
 */
@Serializable
data class UiLayout(
    val panels: List<ModulePanel> = emptyList(),
    val controls: List<ControlDesc> = emptyList(),
    val cables: List<UiCable> = emptyList(),
    val landscape: UiFront? = null,
)

/** One orientation's front: where the panels and controls sit. */
@Serializable
data class UiFront(val panels: List<ModulePanel> = emptyList(), val controls: List<ControlDesc> = emptyList())

const val CONTROL_W = 190f
const val CONTROL_H = 44f
const val PANEL_W = 214f
const val PANEL_HEADER = 34f
const val PANEL_GAP = 24f
const val PANEL_PAD = 12f
const val SNAP = 4f

/** A control as a module offers it, before it has a place. */
class ControlSeed(val label: String, val kind: ControlKind)

/** A module as it offers its controls. */
class ModuleSeed(val id: String, val title: String, val controls: List<ControlSeed>)

object UiModel {
    /** Panels in rows of [columns], each with its controls stacked under the header. */
    fun seed(modules: List<ModuleSeed>, columns: Int = 4): UiLayout {
        val panels = ArrayList<ModulePanel>()
        val controls = ArrayList<ControlDesc>()
        var y = 0f
        var rowHeight = 0f
        modules.forEachIndexed { i, m ->
            val col = i % columns
            if (col == 0 && i > 0) { y += rowHeight + PANEL_GAP; rowHeight = 0f }
            val x = col * (PANEL_W + PANEL_GAP)
            val h = PANEL_HEADER + max(1, m.controls.size) * (CONTROL_H + 8f) + PANEL_PAD
            panels += ModulePanel(m.id, m.title, x, y, PANEL_W, h)
            m.controls.forEachIndexed { j, c ->
                controls += ControlDesc(
                    id = controlId(m.id, c.label), module = m.id, label = c.label, kind = c.kind,
                    x = x + (PANEL_W - CONTROL_W) / 2, y = y + PANEL_HEADER + j * (CONTROL_H + 8f),
                )
            }
            rowHeight = max(rowHeight, h)
        }
        return UiLayout(panels, controls)
    }

    fun controlId(module: String, label: String) = "$module.$label"

    /**
     * Brings a saved layout up to date with what the modules offer now. Everything saved is kept, because a
     * module offers some controls only in some states (Draw's knobs depend on the selected layer), so a control
     * that is missing right now is not gone. New controls are placed in their module's panel, below the others,
     * and the panel grows to hold them. Cables always keep both of their ports.
     */
    fun merge(saved: UiLayout, seeded: UiLayout): UiLayout {
        val panels = ArrayList(saved.panels)
        for (p in seeded.panels) if (panels.none { it.id == p.id }) panels += p
        val controls = ArrayList(saved.controls.map { c ->
            seeded.controls.firstOrNull { it.id == c.id }?.let { s -> c.copy(kind = s.kind, label = s.label) } ?: c
        })
        for (s in seeded.controls) {
            if (controls.any { it.id == s.id }) continue
            // A control new to a module whose panel was saved goes in that panel, below the others.
            val panel = panels.firstOrNull { it.id == s.module }?.takeIf { p -> saved.panels.any { it.id == p.id } }
            val n = controls.count { it.module == s.module }
            controls += if (panel != null) s.copy(x = panel.x + (panel.w - s.w) / 2, y = panel.y + PANEL_HEADER + n * (CONTROL_H + 8f)) else s
        }
        val grown = panels.map { p ->
            val bottom = controls.filter { it.module == p.id }.maxOfOrNull { it.y + it.h }
            if (bottom == null) p else p.copy(h = max(p.h, bottom + PANEL_PAD - p.y))
        }
        val ids = controls.map { it.id }.toSet()
        return UiLayout(grown, controls, saved.cables.filter { it.from in ids && it.to in ids })
    }

    /** The width of the canvas: the back is the front mirrored across this. */
    fun worldWidth(layout: UiLayout): Float = max(layout.panels.maxOfOrNull { it.x + it.w } ?: 0f, layout.controls.maxOfOrNull { it.x + it.w } ?: 0f)

    /** The x of a box on the back: the front's x mirrored across the canvas width. */
    fun mirrorX(x: Float, w: Float, worldW: Float): Float = worldW - x - w

    fun snap(v: Float): Float = (v / SNAP).roundToInt() * SNAP

    /** Moves a control, snapped to the grid. */
    fun moveControl(l: UiLayout, id: String, dx: Float, dy: Float): UiLayout =
        l.copy(controls = l.controls.map { if (it.id == id) it.copy(x = snap(it.x + dx), y = snap(it.y + dy)) else it })

    /** Resizes a control by its bottom right corner, never smaller than a small minimum. */
    fun resizeControl(l: UiLayout, id: String, dw: Float, dh: Float): UiLayout =
        l.copy(controls = l.controls.map { if (it.id == id) it.copy(w = snap(max(40f, it.w + dw)), h = snap(max(28f, it.h + dh))) else it })

    /** Moves a panel (snapped to the grid) and every control that belongs to it by exactly as far. */
    fun movePanel(l: UiLayout, id: String, dx: Float, dy: Float): UiLayout {
        val p = l.panels.firstOrNull { it.id == id } ?: return l
        val mx = snap(p.x + dx) - p.x
        val my = snap(p.y + dy) - p.y
        return l.copy(
            panels = l.panels.map { if (it.id == id) it.copy(x = it.x + mx, y = it.y + my) else it },
            controls = l.controls.map { if (it.module == id) it.copy(x = it.x + mx, y = it.y + my) else it },
        )
    }

    /**
     * A cable between two controls' ports. What a port carries follows its control (see [portType]); a cable is made only if
     * the jack types fit, and it is stored from the source to the target, whichever port the drag began on. A port cannot be
     * joined to itself, to a port that is not there, or twice over.
     */
    fun connect(l: UiLayout, from: String, to: String): UiLayout? {
        val a = l.controls.firstOrNull { it.id == from } ?: return null
        val b = l.controls.firstOrNull { it.id == to } ?: return null
        if (from == to) return null
        if (l.cables.any { (it.from == from && it.to == to) || (it.from == to && it.to == from) }) return null
        val ta = a.kind.portType()
        val tb = b.kind.portType()
        return when {
            tb.accepts(ta) -> l.copy(cables = l.cables + UiCable(from, to))
            ta.accepts(tb) -> l.copy(cables = l.cables + UiCable(to, from))
            else -> null
        }
    }

    fun disconnectPort(l: UiLayout, id: String): UiLayout = l.copy(cables = l.cables.filterNot { it.from == id || it.to == id })

    fun disconnect(l: UiLayout, cable: UiCable): UiLayout = l.copy(cables = l.cables - cable)

    fun cablesOf(l: UiLayout, id: String): List<UiCable> = l.cables.filter { it.from == id || it.to == id }
}
