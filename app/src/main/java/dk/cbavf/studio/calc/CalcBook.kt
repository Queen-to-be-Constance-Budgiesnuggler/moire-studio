package dk.cbavf.studio.calc

/** One line of the calculator's history: what was typed and what came out. */
data class CalcEntry(val input: String, val text: String, val number: Double)

/**
 * The calculator's memory: the history (newest first, at most [MAX_HISTORY] lines) and the variables, `ans` among them
 * (the last result). [submit] works out a line and gives back the book with it added; a line that fails changes nothing.
 */
data class CalcBook(val history: List<CalcEntry> = emptyList(), val vars: Map<String, Quantity> = emptyMap()) {
    fun submit(input: String): Pair<CalcBook, CalcOutcome> {
        val line = input.trim()
        val out = Calc.run(line, vars)
        if (out !is CalcOutcome.Ok) return this to out
        val r = out.result
        val v = vars + ("ans" to r.quantity) + (r.assigned?.let { mapOf(it to r.quantity) } ?: emptyMap())
        val h = (listOf(CalcEntry(line, r.text, r.number)) + history).take(MAX_HISTORY)
        return CalcBook(h, v) to out
    }

    /** What [input] would give, without keeping it (the preview under the field). */
    fun preview(input: String): CalcOutcome? = if (input.isBlank()) null else Calc.run(input.trim(), vars)

    /** The variables people set (not `ans`), by name. */
    val named: List<Pair<String, Quantity>> get() = vars.filterKeys { it != "ans" }.toList().sortedBy { it.first }

    fun clearHistory(): CalcBook = copy(history = emptyList())

    fun forget(name: String): CalcBook = copy(vars = vars - name)

    companion object {
        const val MAX_HISTORY = 100
    }
}

/** A result sent to a control: the [value] it gets and whether it had to be moved into the control's range. */
data class Fitted(val value: Float, val clamped: Boolean)

object CalcSend {
    /** [x] fitted to a knob with [range]: kept inside it, and rounded when the knob takes whole numbers. */
    fun fit(x: Double, range: ClosedFloatingPointRange<Float>, whole: Boolean): Fitted {
        val v = if (whole) Math.round(x).toDouble() else x
        val kept = v.coerceIn(range.start.toDouble(), range.endInclusive.toDouble())
        return Fitted(kept.toFloat(), kept != v)
    }
}
