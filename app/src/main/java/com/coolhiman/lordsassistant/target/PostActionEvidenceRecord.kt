package com.coolhiman.lordsassistant.target

import com.coolhiman.lordsassistant.model.TargetKind
import com.coolhiman.lordsassistant.model.WorldCoordinate

/**
 * Structured provenance for post-action verification.
 *
 * The lifecycle still consumes the small PostActionEvidence enum, while this
 * record preserves the context that produced the evidence for diagnostics
 * and future UI/debugging.
 */
enum class PostActionEvidenceSource {
    POPUP_STATE,
    TARGET_STATE,
    MARCH_ASSOCIATION
}

data class PostActionEvidenceRecord(
    val evidence: Set<PostActionEvidence>,
    val sources: Set<PostActionEvidenceSource>,
    val selected: ActionTargetSnapshot,
    val observedCoordinate: WorldCoordinate?,
    val observedKind: TargetKind?,
    val observedLevel: Int?,
    val cameraStable: Boolean,
    val confirmingFrames: Int,
    val timestampMs: Long,
    val marchTrajectory: List<MarchSignal> = emptyList(),
    val marchTrajectoryEvidence: MarchTrajectoryEvidence? = null
)
