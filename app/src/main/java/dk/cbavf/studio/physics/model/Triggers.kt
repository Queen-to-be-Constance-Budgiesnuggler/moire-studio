package dk.cbavf.studio.physics.model

/** Two bodies touching, by layer id (walls have negative ids), smaller id first so a pair has one name. */
data class ContactPair private constructor(val a: Int, val b: Int) {
    val touchesWall: Boolean get() = a < 0 || b < 0

    companion object {
        fun of(x: Int, y: Int) = if (x <= y) ContactPair(x, y) else ContactPair(y, x)
    }
}

/**
 * Turns "which bodies touch now" into "which pairs have just met". A resting body touches, lets go and touches again from step
 * to step; a pair only ends after it has been apart for [releaseSteps] steps in a row, so it is one hit and not a buzz.
 */
class ContactEdges(private val releaseSteps: Int = 3) {
    /** Pairs counted as touching, and for how many steps in a row each has been apart. */
    private val apart = LinkedHashMap<ContactPair, Int>()

    val touching: Set<ContactPair> get() = apart.keys

    /** The pairs touching after a step; gives back the pairs that have just met. */
    fun step(now: Set<ContactPair>): List<ContactPair> {
        val began = now.filter { it !in apart }
        for (p in apart.keys.toList()) {
            if (p in now) continue
            val n = (apart[p] ?: 0) + 1
            if (n >= releaseSteps.coerceAtLeast(1)) apart.remove(p) else apart[p] = n
        }
        for (p in now) apart[p] = 0
        return began
    }

    fun reset() = apart.clear()
}

/** One hit: the pair that met and how hard, 0 to 1. */
data class Hit(val pair: ContactPair, val strength: Float)

/** What the hits send out: a trigger for each step with a hit, how many so far, and how hard the last one was. */
class TriggerOut {
    var count = 0
        private set
    var lastStrength = 0f
        private set

    /** Takes one step's hits; gives the trigger level for this step (1 with a hit, else 0). */
    fun step(hits: List<Hit>, wallsCount: Boolean): Float {
        val counted = if (wallsCount) hits else hits.filterNot { it.pair.touchesWall }
        if (counted.isEmpty()) return 0f
        count += counted.size
        lastStrength = counted.maxOf { it.strength }
        return 1f
    }

    fun reset() {
        count = 0
        lastStrength = 0f
    }

    companion object {
        /** A meeting at this speed (m/s) or faster is a full-strength hit. */
        const val FULL_SPEED = 8f

        fun strength(relativeSpeed: Float): Float = (relativeSpeed / FULL_SPEED).coerceIn(0f, 1f)
    }
}
