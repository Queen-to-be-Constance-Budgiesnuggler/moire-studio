package dk.cbavf.studio.patch.model

import dk.cbavf.studio.routing.model.PATCH_LFO1
import dk.cbavf.studio.routing.model.PATCH_LFO2
import dk.cbavf.studio.routing.model.PATCH_ROUTES
import dk.cbavf.studio.routing.model.PATCH_TOUCH_X
import dk.cbavf.studio.routing.model.PATCH_TOUCH_Y
import dk.cbavf.studio.routing.model.RoutingDoc

/**
 * The Patch bay's signals as rack jacks (the "Patch bay" unit, docs/ARCHITECTURE.md §13). The LFOs and the touch pad need no
 * hardware, so they go out whenever a cable listens. Route 1 to 4 are the patch's first four routes, in list order: each sends
 * where its output sits in its own range (0 at min, 1 at max), after curve and smoothing, while the patch is live.
 */
object PatchSignals {
    /** The Patch bay jacks some cable listens to. */
    fun listened(doc: RoutingDoc): Set<String> = doc.patchedOutputs.filter { it.startsWith("patch.") }.toSet()

    /** The jack that carries route [routeId], or null when it is not among the first four. */
    fun routeJack(routes: List<Route>, routeId: Int): String? =
        routes.indexOfFirst { it.id == routeId }.takeIf { it in PATCH_ROUTES.indices }?.let { PATCH_ROUTES[it] }

    /** Where [v] (the route's output) sits in the route's range, 0..1; a reversed range (max below min) counts down. */
    fun routeLevel(route: Route, v: Float): Float {
        val span = route.max - route.min
        if (span == 0f) return 0f
        return ((v - route.min) / span).coerceIn(0f, 1f)
    }

    /**
     * The levels of the jacks that need no hardware (LFOs, touch pad), for those in [wanted] only, [tSec] into the bus's clock.
     * The LFOs use the same seeds as the patch's own routes, so a Random LFO holds the same values on both.
     */
    fun freeLevels(doc: PatchDoc, tSec: Double, touchX: Float, touchY: Float, wanted: Set<String>): Map<String, Float> = buildMap {
        if (PATCH_LFO1 in wanted) put(PATCH_LFO1, PatchMath.lfo(doc.lfo1, tSec, seed = 1))
        if (PATCH_LFO2 in wanted) put(PATCH_LFO2, PatchMath.lfo(doc.lfo2, tSec, seed = 2))
        if (PATCH_TOUCH_X in wanted) put(PATCH_TOUCH_X, touchX.coerceIn(0f, 1f))
        if (PATCH_TOUCH_Y in wanted) put(PATCH_TOUCH_Y, touchY.coerceIn(0f, 1f))
    }
}
