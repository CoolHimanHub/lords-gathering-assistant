package com.coolhiman.lordsassistant.target

import com.coolhiman.lordsassistant.model.MapObservation
import com.coolhiman.lordsassistant.model.WorldCoordinate
import com.coolhiman.lordsassistant.vision.PopupState

/**
 * Converts post-dispatch visual state changes into conservative evidence.
 *
 * It never claims that a march belongs to our action. That association needs
 * a stronger identity signal and remains a future step.
 */
object PostActionStateVerifier {
    fun collectEvidence(
        selected: ActionTargetSnapshot,
        before: MapObservation?,
        after: MapObservation?,
        popupBefore: PopupState?,
        popupAfter: PopupState?
    ): Set<PostActionEvidence> {
        val evidence = linkedSetOf<PostActionEvidence>()

        val popupWasVisible = popupBefore?.isPopup == true
        val popupIsVisible = popupAfter?.isPopup == true
        if (popupWasVisible && !popupIsVisible) {
            evidence += PostActionEvidence.POPUP_DISAPPEARED
        }

        val sameTarget = after?.coordinate == selected.coordinate &&
            after.kind == selected.kind &&
            after.level == selected.level

        if (sameTarget) {
            if (after.occupied == true || after.incomingTroops == true) {
                evidence += PostActionEvidence.TARGET_OCCUPIED
            }
        } else if (before?.coordinate == selected.coordinate) {
            evidence += PostActionEvidence.TARGET_REMOVED
        }

        return evidence
    }

    fun isSameTarget(observation: MapObservation?, coordinate: WorldCoordinate): Boolean =
        observation?.coordinate == coordinate
}
