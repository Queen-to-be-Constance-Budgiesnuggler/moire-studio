package dk.cbavf.studio.uieditor

import dk.cbavf.studio.routing.KnobSpec
import dk.cbavf.studio.routing.ModuleBackSpec

/**
 * A control of a module's back as the cables see it: what kind it is, its level now (0..1 for a slider, 0 or 1 for a toggle)
 * and how to set it. A button has no level to read (a press leaves nothing behind), so only a toggle or a slider can be a source.
 */
class LiveControl(val kind: ControlKind, val read: () -> Float, val write: (Float) -> Unit)

/** The controls the modules offer right now, by their id (`room.label`, the same as in the UI Editor's layout). */
fun liveControls(specs: List<ModuleBackSpec>): Map<String, LiveControl> {
    val out = HashMap<String, LiveControl>()
    for (m in specs) {
        for (k in m.knobs) out[UiModel.controlId(m.roomId, baseLabelOf(k.label))] = sliderControl(k)
        for (b in m.buttons) {
            val id = UiModel.controlId(m.roomId, baseLabelOf(b.label))
            val on = b.on
            out[id] = if (on != null) {
                LiveControl(ControlKind.Toggle, { if (b.on == true) 1f else 0f }) { v -> if ((v >= 0.5f) != (b.on == true)) b.onClick() }
            } else {
                LiveControl(ControlKind.Button, { 0f }) { v -> if (v >= 0.5f) b.onClick() }
            }
        }
    }
    return out
}

private fun sliderControl(k: KnobSpec): LiveControl {
    val span = k.range.endInclusive - k.range.start
    return LiveControl(
        ControlKind.Slider,
        { if (span <= 0f) 0f else ((k.value - k.range.start) / span).coerceIn(0f, 1f) },
    ) { n ->
        val v = k.range.start + n.coerceIn(0f, 1f) * span
        k.onChange(if (k.whole) Math.round(v).toFloat() else v)
    }
}

/** The label a control has in the UI Editor: button labels that change ("Sort: Name ▸") reduced to their stable name. */
internal fun baseLabelOf(label: String): String = label.substringBefore(':').removeSuffix("▸").trim().let { if (it == "Connected") "Connect" else it }

/**
 * Runs the UI Editor's cables: each frame a cable carries its source's level to its target. A slider target follows the level
 * across its range, a toggle target switches on at half or more, and a button target is pressed on the frame its level rises
 * through half. [memo] keeps what was last written per target, so a control is only touched when its level changes.
 */
object UiCables {
    fun step(cables: List<UiCable>, controls: Map<String, LiveControl>, memo: MutableMap<String, Float>) {
        for (c in cables) {
            val src = controls[c.from] ?: continue
            val tgt = controls[c.to] ?: continue
            val level = src.read()
            val last = memo[c.to]
            when (tgt.kind) {
                ControlKind.Button -> {
                    if (level >= 0.5f && (last == null || last < 0.5f)) tgt.write(1f)
                    memo[c.to] = level
                }
                else -> {
                    if (last == null || kotlin.math.abs(level - last) > 1e-4f) {
                        tgt.write(level)
                        memo[c.to] = level
                    }
                }
            }
        }
    }
}
