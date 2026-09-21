package com.coolhiman.lordsassistant.target

/**
 * Defines the final scanner-to-scheduler eligibility boundary.
 *
 * A live candidate may be visually detected and still remain diagnostic-only.
 * Automatic scheduling requires both current-frame validation and a finite
 * planner position/score, so an unranked memory/detection result cannot become
 * an automatic action merely because an interaction button was detected.
 */
object LiveActionCandidatePolicy {
    fun isSchedulerEligible(candidate: com.coolhiman.lordsassistant.map.LiveActionCandidate): Boolean {
        return candidate.cameraContinuityValid &&
            candidate.validation.safe &&
            candidate.validation.stage == TargetValidationStage.SAFE_TO_INTERACT &&
            candidate.plannerRank >= 0 &&
            candidate.plannerScore.isFinite()
    }
}
