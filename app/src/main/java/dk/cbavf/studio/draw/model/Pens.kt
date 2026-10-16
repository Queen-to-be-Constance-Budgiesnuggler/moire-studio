package dk.cbavf.studio.draw.model

/** Starting stroke stacks for the pen. Every sub-stroke stays editable after drawing. */
enum class Pen(val label: String, val stack: List<SubStroke>) {
    Plain("Ink", listOf(SubStroke(widthPx = 6f))),

    /** Nine parallel lines: a hand-drawn grating. Two overlapping rail strokes make moiré. */
    Rails("Rails", (-4..4).map { SubStroke(widthPx = 3f, offsetPx = it * 7f, cap = Cap.Butt) }),

    Dashed(
        "Dashed",
        listOf(
            SubStroke(widthPx = 10f),
            SubStroke(widthPx = 4f, ink = Ink.White, dashPx = 10f, gapPx = 10f, cap = Cap.Butt),
        ),
    ),

    /** Two dash trains with close periods: the dashes beat along the line (d1·d2 / |d1 − d2|). */
    DashBeat(
        "Dash beat",
        listOf(
            SubStroke(widthPx = 8f, dashPx = 9f, gapPx = 9f, cap = Cap.Butt),
            SubStroke(widthPx = 8f, dashPx = 10f, gapPx = 10f, cap = Cap.Butt),
        ),
    ),
}
