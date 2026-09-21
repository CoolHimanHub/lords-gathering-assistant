package com.coolhiman.lordsassistant.target

/**
 * Defines the final scanner-to-scheduler eligibility boundary.
 *
 * A live candidate may be visually detected and still remain diagnostic-only.
 * Automatic scheduling requires current-frame camera continuity, safe validation,
 * and a finite planner position/score.
 */
enum class LiveActionCandidateRejectionReason {
    CAMERA_CONTINUITY_INVALID,
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
