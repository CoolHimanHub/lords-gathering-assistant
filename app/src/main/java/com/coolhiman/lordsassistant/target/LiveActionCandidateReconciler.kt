package com.coolhiman.lordsassistant.target

import com.coolhiman.lordsassistant.map.LiveActionCandidate

/**
 * V0.10 live scanner -> scheduler reconciliation boundary.
 *
 * Converts only the latest-frame candidates that satisfy the final live
 * eligibility policy. This component is pure and has no gesture or lifecycle
 * authority. The scheduler remains responsible for queue reconciliation,
 * cooldown, in-flight state, and completed-target suppression.
 */
data class LiveActionCandidateReconciliation(
    val eligible: List<LiveActionCandidate>,
    val rejected: Map<LiveActionCandidate, LiveActionCandidateRejectionReason>
) {
    val rejectedCount: Int
        get() = rejected.size
}

object LiveActionCandidateReconciler {
    fun reconcile(candidates: Collection<LiveActionCandidate>): LiveActionCandidateReconciliation {
        val eligible = mutableListOf<LiveActionCandidate>()
        val rejected = linkedMapOf<LiveActionCandidate, LiveActionCandidateRejectionReason>()

        candidates.forEach { candidate ->
            val reason = LiveActionCandidatePolicy.rejectionReason(candidate)
            if (reason == null) {
                eligible += candidate
            } else {
                rejected[candidate] = reason
            }
        }

        return LiveActionCandidateReconciliation(
            eligible = eligible,
            rejected = rejected
        )
    }
}
