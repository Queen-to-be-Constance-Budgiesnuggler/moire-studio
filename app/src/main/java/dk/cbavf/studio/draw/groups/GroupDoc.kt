package dk.cbavf.studio.draw.groups

import dk.cbavf.studio.draw.model.Layer
import dk.cbavf.studio.library.CanvasDoc

/** The layers a renderer should draw: hidden ones and those in hidden groups are left out. */
fun CanvasDoc.visibleLayers(): List<Layer> {
    if (groups.isEmpty()) return layers
    val gn = groups.map { GNode(it.id, it.parent, it.visible) }
    return layers.filter { GroupLogic.effectiveVisible(LNode(it.id, it.group, it.visible), gn) }
}

/**
 * The canvas as other rooms should see it (Animate frames, rack cables, Node Lab): group visibility
 * baked into the layers and the groups dropped, so nothing downstream has to know about groups.
 */
fun CanvasDoc.flattened(): CanvasDoc = if (groups.isEmpty()) this else copy(layers = visibleLayers(), groups = emptyList())
