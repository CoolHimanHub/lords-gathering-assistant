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
        val cameraUnstable = after?.evidence?.contains(
            com.coolhiman.lordsassistant.model.ObservationEvidence.CAMERA_UNSTABLE
        ) == true

        if (cameraUnstable) return evidence

        val popupBeforeMatchesTarget = popupBefore?.isPopup == true &&
            popupBefore.coordinate == selected.coordinate &&
            popupBefore.kind == selected.kind &&
            popupBefore.level == selected.level
        val popupAfterIsVisible = popupAfter?.isPopup == true
        if (popupBeforeMatchesTarget && !popupAfterIsVisible) {
            evidence += PostActionEvidence.POPUP_DISAPPEARED
        }

        val beforeMatchesTarget = before?.coordinate == selected.coordinate &&
            before.kind == selected.kind &&
            before.level == selected.level
        val sameTarget = after?.coordinate == selected.coordinate &&
            after.kind == selected.kind &&
            after.level == selected.level

        if (sameTarget) {
            if (after.occupied == true || after.incomingTroops == true) {
                evidence += PostActionEvidence.TARGET_OCCUPIED
            }
        } else if (beforeMatchesTarget) {
            evidence += PostActionEvidence.TARGET_REMOVED
        }

        return evidence
    }

    fun isSameTarget(observation: MapObservation?, coordinate: WorldCoordinate): Boolean =
        observation?.coordinate == coordinate

    fun isSameTarget(observation: MapObservation?, selected: ActionTargetSnapshot): Boolean =
        observation?.coordinate == selected.coordinate &&
            observation.kind == selected.kind &&
            observation.level == selected.level
}
