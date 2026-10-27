package dk.cbavf.studio.math

/** The Switch node: one of two numbers, chosen by a gate or a value. */
object SwitchMath {
    /** B when [select] is at or above [threshold], else A. A gate (0 or 1) picks with the default 0.5. */
    fun pick(a: Float, b: Float, select: Float, threshold: Float = 0.5f): Float = if (select >= threshold) b else a

    /** A blend: A at select 0, B at select 1, held at the ends. */
    fun fade(a: Float, b: Float, select: Float): Float = a + (b - a) * select.coerceIn(0f, 1f)
}
