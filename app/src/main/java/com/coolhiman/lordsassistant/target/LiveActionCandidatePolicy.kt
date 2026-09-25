package com.coolhiman.lordsassistant.target

/**
 * Final scanner-to-scheduler eligibility boundary.
 *
 * Visual detection and calibrated coordinates remain diagnostic-only until the
 * target's world coordinate is directly observed and all action gates pass.
 */
enum class LiveActionCandidateRejectionReason {
    CAMERA_CONTINUITY_INVALID,
    COORDINATE_NOT_ACTION_AUTHORITATIVE,
    VALIDATION_UNSAFE,
    VALIDATION_NOT_SAFE_TO_INTERACT,
    PLANNER_RANK_INVALID,
    PLANNER_SCORE_NON_FINITE
}

object LiveActionCandidatePolicy {
    fun rejectionReason(candidate: com.coolhiman.lordsassistant.map.LiveActionCandidate): LiveActionCandidateRejectionReason? =
        when {
            !candidate.cameraContinuityValid ->
                LiveActionCandidateRejectionReason.CAMERA_CONTINUITY_INVALID
            !candidate.observation.coordinateConfidence.actionAuthoritative ->
                LiveActionCandidateRejectionReason.COORDINATE_NOT_ACTION_AUTHORITATIVE
            !candidate.validation.safe ->
                LiveActionCandidateRejectionReason.VALIDATION_UNSAFE
            candidate.validation.stage != TargetValidationStage.SAFE_TO_INTERACT ->
                LiveActionCandidateRejectionReason.VALIDATION_NOT_SAFE_TO_INTERACT
            candidate.plannerRank < 0 ->
                LiveActionCandidateRejectionReason.PLANNER_RANK_INVALID
            !candidate.plannerScore.isFinite() ->
                LiveActionCandidateRejectionReason.PLANNER_SCORE_NON_FINITE
            else -> null
        }

    fun isSchedulerEligible(candidate: com.coolhiman.lordsassistant.map.LiveActionCandidate): Boolean =
        rejectionReason(candidate) == null
}
