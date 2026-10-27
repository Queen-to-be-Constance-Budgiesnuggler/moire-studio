package dk.cbavf.studio.palette

/** Picks the room a typed word stands for, for the notification's "Go to a room" field. */
object PaletteSearch {
    /**
     * The first of [names] that is exactly [query], else the first that starts with it, else the first that contains it (all
     * ignoring case and surrounding spaces). A blank query matches nothing.
     */
    fun match(query: String, names: List<String>): String? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return null
        return names.firstOrNull { it.lowercase() == q }
            ?: names.firstOrNull { it.lowercase().startsWith(q) }
            ?: names.firstOrNull { it.lowercase().contains(q) }
    }
}
