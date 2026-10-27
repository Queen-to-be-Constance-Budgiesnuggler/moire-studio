package dk.cbavf.studio.library

/**
 * Which assets can be deleted together, and in what order. An asset can only be deleted when nothing is made from it, so
 * children go before their parents. [blocked] are the assets that have a child outside what is being deleted (and the assets in the
 * selection that lie above one of those), which stay.
 */
class DeletePlan(val order: List<AssetRecord>, val blocked: List<AssetRecord>) {
    companion object {
        /** The plan for deleting [ids], and everything made from them too when [withDescendants] is true. */
        fun of(all: List<AssetRecord>, ids: Set<String>, withDescendants: Boolean): DeletePlan {
            val byId = all.associateBy { it.id }
            val children = HashMap<String, MutableList<String>>()
            for (a in all) for (p in a.parents) children.getOrPut(p) { ArrayList() } += a.id
            val chosen = LinkedHashSet<String>()
            val todo = ArrayDeque(ids.filter { it in byId })
            while (todo.isNotEmpty()) {
                val id = todo.removeFirst()
                if (!chosen.add(id)) continue
                if (withDescendants) children[id]?.let { todo.addAll(it) }
            }
            val order = ArrayList<AssetRecord>()
            val removed = HashSet<String>()
            var progress = true
            while (progress) {
                progress = false
                for (id in chosen) {
                    if (id in removed) continue
                    // free to go once every child is already gone
                    if ((children[id].orEmpty()).all { it in removed }) {
                        order += byId.getValue(id)
                        removed += id
                        progress = true
                    }
                }
            }
            val blocked = chosen.filter { it !in removed }.map { byId.getValue(it) }
            return DeletePlan(order, blocked)
        }
    }
}
