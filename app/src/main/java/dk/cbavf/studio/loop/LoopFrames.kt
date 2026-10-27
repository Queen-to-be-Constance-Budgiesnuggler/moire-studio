package dk.cbavf.studio.loop

import dk.cbavf.studio.sequence.model.AssetSource
import dk.cbavf.studio.sequence.model.SeqSource

/**
 * Which frame the Loop projector shows. A picture patched into the Loop unit's Projector input
 * ([routed], filed in the Library as an asset id) comes first and takes the Sequence's place.
 */
object LoopFrames {
    /** The first frame: the rack's picture, the Library pick, the Sequence's first item, or the newest still. */
    fun first(routed: String?, picked: String?, sequenceFirst: SeqSource?, newest: String?): SeqSource? =
        routed?.let(::AssetSource) ?: picked?.let(::AssetSource) ?: sequenceFirst ?: newest?.let(::AssetSource)

    /** True when the next frame is the capture itself (feedback), so the rack's picture is not needed. */
    fun showsCapture(source: LoopSource, capturedId: String?) = source == LoopSource.Feedback && capturedId != null

    /**
     * The frame after a capture: the capture (feedback), else the rack's latest picture, else the
     * Sequence item for [step], else whatever was there before.
     */
    fun next(source: LoopSource, capturedId: String?, routed: String?, sequence: List<SeqSource>, step: Int, shownId: String?): SeqSource {
        if (showsCapture(source, capturedId)) return AssetSource(capturedId!!)
        routed?.let { return AssetSource(it) }
        return sequence.getOrNull(step % sequence.size.coerceAtLeast(1)) ?: AssetSource(capturedId ?: shownId ?: "")
    }
}
