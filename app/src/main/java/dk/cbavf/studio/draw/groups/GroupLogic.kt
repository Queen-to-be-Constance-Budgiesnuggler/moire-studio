package dk.cbavf.studio.draw.groups

data class GNode(val id: Int, val parent: Int?, val visible: Boolean = true)
data class LNode(val id: Int, val group: Int?, val visible: Boolean = true)
data class DuplicateResult(
    val newLayers: List<LNode>,
    val newGroups: List<GNode>,
    val layerIdMap: Map<Int, Int>,
    val groupIdMap: Map<Int, Int>
)

object GroupLogic {
    /**
     * Returns true if the layer and all its ancestor groups are visible.
     * Unknown group ids count as visible. Cycles are handled by stopping after
     * groups.size + 1 steps.
     */
    fun effectiveVisible(layer: LNode, groups: List<GNode>): Boolean {
        if (!layer.visible) return false
        
        val groupMap = groups.associateBy { it.id }
        var currentGroupId = layer.group
        var steps = groups.size + 1
        
        while (currentGroupId != null) {
            // Walked more steps than there are groups: the parents go round in a cycle.
            if (steps <= 0) return false
            val group = groupMap[currentGroupId] ?: return true
            if (!group.visible) return false
            currentGroupId = group.parent
            steps--
        }

        return true
    }

    /**
     * Returns the set of all descendant group ids of the given group.
     * Cycles are handled by tracking visited nodes.
     */
    fun descendants(groupId: Int, groups: List<GNode>): Set<Int> {
        val result = mutableSetOf<Int>()
        val visited = mutableSetOf<Int>()
        val queue = mutableListOf(groupId)
        
        while (queue.isNotEmpty()) {
            val currentId = queue.removeFirst()
            
            if (currentId in visited) continue
            visited.add(currentId)
            
            val group = groups.find { it.id == currentId } ?: continue
            
            groups.filter { it.parent == currentId }.forEach { child ->
                result.add(child.id)
                queue.add(child.id)
            }
        }
        
        return result
    }

    /**
     * Returns the list of layers that belong to the given group or any of its descendants.
     * Maintains original order from the input list.
     */
    fun layersIn(groupId: Int, layers: List<LNode>, groups: List<GNode>): List<LNode> {
        val descendantsSet = descendants(groupId, groups)
        return layers.filter { 
            it.group == groupId || it.group in descendantsSet 
        }
    }

    /**
     * Duplicates selected layers and their containing groups.
     * Fully selected groups (all layers within them are selected) are also duplicated.
     * Returns a DuplicateResult with new layer and group IDs, mappings, and lists.
     */
    fun duplicate(
        layers: List<LNode>,
        groups: List<GNode>,
        selected: Set<Int>,
        nextLayerId: Int,
        nextGroupId: Int
    ): DuplicateResult {
        val groupMap = groups.associateBy { it.id }
        val layerMap = layers.associateBy { it.id }
        
        // Identify which groups are fully selected
        val fullySelectedGroups = mutableSetOf<Int>()
        val selectedLayerIds = selected.toSet()
        
        for (group in groups) {
            val groupLayers = layersIn(group.id, layers, groups)
            if (groupLayers.isNotEmpty() && groupLayers.all { it.id in selectedLayerIds }) {
                fullySelectedGroups.add(group.id)
            }
        }
        
        // Build mappings
        val groupIdMap = mutableMapOf<Int, Int>()
        val layerIdMap = mutableMapOf<Int, Int>()
        
        var newGroupId = nextGroupId
        var newLayerId = nextLayerId
        
        for (group in groups) {
            if (group.id in fullySelectedGroups) {
                groupIdMap[group.id] = newGroupId
                newGroupId++
            }
        }
        
        for (layer in layers) {
            if (layer.id in selectedLayerIds) {
                layerIdMap[layer.id] = newLayerId
                newLayerId++
            }
        }
        
        // Build new groups list in ascending order of original IDs
        val newGroups = groups
            .filter { it.id in fullySelectedGroups }
            .sortedBy { it.id }
            .map { group ->
                // A parent that is not copied stays the parent of the copy.
                val newParent = group.parent?.let { parentId -> groupIdMap[parentId] ?: parentId }
                GNode(groupIdMap[group.id]!!, newParent, group.visible)
            }
        
        // Build new layers list
        val newLayers = layers
            .filter { it.id in selectedLayerIds }
            .map { layer ->
                val newGroupId = layer.group?.let { groupId -> groupIdMap[groupId] ?: groupId }
                LNode(layerIdMap[layer.id]!!, newGroupId, layer.visible)
            }
        
        return DuplicateResult(newLayers, newGroups, layerIdMap, groupIdMap)
    }
}
