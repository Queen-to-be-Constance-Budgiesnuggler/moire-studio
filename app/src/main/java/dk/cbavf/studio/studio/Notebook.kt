package dk.cbavf.studio.studio

import dk.cbavf.studio.library.AssetRecord
import dk.cbavf.studio.library.Recipe

/** The kinds of experiment the notebook can run again on its own, from the recipe alone. */
enum class RerunKind(val operation: String) {
    Draw("draw.render"),
    Compose("compose.render"),
    Develop("develop.render"),
    Glitch("glitch.render"),
}

/**
 * What a rerun does: which renderer, the recipe it gets, the inputs it is filed under and the size it is made at. The new entry
 * is a sibling of the first run (same parents), marked with [origin] so the two can be compared.
 */
data class RerunPlan(
    val kind: RerunKind,
    val recipe: Recipe,
    val parents: List<String>,
    /** The first run of this experiment: a rerun of a rerun still points at the first. */
    val origin: String,
    /** Pixels per canvas pixel, for a Draw canvas saved at a high-res size; 1 elsewhere. */
    val scale: Float = 1f,
    /** Longest side to make it at (Glitch works on a downscaled copy of its source). */
    val maxPx: Int = 0,
)

/**
 * The lab notebook's rules, with no Android in them: notes on entries, and what "Rerun" uses. Other kinds (Animate, Sequence,
 * Node Lab, FFT, captures) are not rerun here: open them in their room and save again.
 */
object Notebook {
    /** Longest note kept; a notebook line, not a document. */
    const val NOTE_MAX = 2000

    /** A note as it is stored: trimmed, capped, and null when empty (no note). */
    fun cleanNote(text: String?): String? = text?.trim()?.take(NOTE_MAX)?.trimEnd()?.ifEmpty { null }

    /** True when [text] would change the stored note of [record]. */
    fun noteChanged(record: AssetRecord, text: String): Boolean = cleanNote(text) != cleanNote(record.note)

    /** The first run of the experiment [record] belongs to (itself, unless it is a rerun). */
    fun originOf(record: AssetRecord): String = record.rerunOf ?: record.id

    fun kindOf(record: AssetRecord): RerunKind? = RerunKind.entries.firstOrNull { it.operation == record.operation }

    /** Whether the Rerun button shows: a kind the notebook can rerun, with a recipe to rerun it from. */
    fun canRerun(record: AssetRecord): Boolean = record.hasRecipe && kindOf(record) != null

    /** The rerun of [record] from its [recipe], or null when the recipe does not hold what this kind needs. */
    fun plan(record: AssetRecord, recipe: Recipe?): RerunPlan? {
        val kind = kindOf(record) ?: return null
        recipe ?: return null
        val origin = originOf(record)
        return when (kind) {
            RerunKind.Draw -> recipe.canvas?.let { c ->
                if (c.widthPx <= 0 || c.heightPx <= 0) return null
                RerunPlan(kind, Recipe(canvas = c), record.parents, origin, scale = drawScale(record.widthPx, c.widthPx))
            }
            RerunKind.Compose -> recipe.compose?.let { RerunPlan(kind, Recipe(compose = it), record.parents, origin) }
            RerunKind.Develop -> recipe.develop?.takeIf { it.sourceAssetId != null }?.let { RerunPlan(kind, Recipe(develop = it), record.parents, origin) }
            RerunKind.Glitch -> recipe.glitch?.takeIf { it.sourceAssetId != null }?.let {
                RerunPlan(kind, Recipe(glitch = it), record.parents, origin, maxPx = maxOf(record.widthPx, record.heightPx))
            }
        }
    }

    /** The scale a Draw canvas was saved at: the saved width over the canvas width; 1 when either is unknown. */
    fun drawScale(savedWidthPx: Int, canvasWidthPx: Int): Float =
        if (savedWidthPx > 0 && canvasWidthPx > 0) savedWidthPx.toFloat() / canvasWidthPx else 1f

    /** Every run of the experiment [record] belongs to, first run first. */
    fun runsOf(entries: List<AssetRecord>, record: AssetRecord): List<AssetRecord> {
        val origin = originOf(record)
        return entries.filter { it.id == origin || it.rerunOf == origin }.sortedBy { it.createdAt }
    }

    /**
     * The line under a rerun in the notebook, e.g. "Run 2 · first run 12 Oct 14:02"; null for an entry that was never rerun.
     * [format] turns a time into text (the screen's date format).
     */
    fun runLine(entries: List<AssetRecord>, record: AssetRecord, format: (Long) -> String): String? {
        val runs = runsOf(entries, record)
        if (runs.size < 2 && record.rerunOf == null) return null
        val n = runs.indexOfFirst { it.id == record.id } + 1
        val first = entries.firstOrNull { it.id == originOf(record) }
        return when {
            record.rerunOf == null -> "First run · ${runs.size - 1} rerun${if (runs.size == 2) "" else "s"}"
            first == null -> "Rerun · the first run is gone"
            else -> "Run $n · first run ${format(first.createdAt)}"
        }
    }
}
