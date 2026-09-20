package com.coolhiman.lordsassistant.target

import com.coolhiman.lordsassistant.model.MapObservation
import com.coolhiman.lordsassistant.model.ScreenPoint
import com.coolhiman.lordsassistant.vision.MarchSignal
import com.coolhiman.lordsassistant.vision.PopupState

/**
 * Coordinates the complete guarded action lifecycle without owning the
 * AccessibilityService. Automatic execution remains opt-in at the caller.
 *
 * Flow:
 * request -> fresh revalidation -> dispatch -> post-action observation ->
 * march/state verification -> lifecycle result.
 */
class ActionOrchestrator(
    private val lifecycle: ActionLifecycleController = ActionLifecycleController(),
    private val marchTracker: ActionMarchAssociationTracker = ActionMarchAssociationTracker()
) {
    data class Session(
        val beforeObservation: MapObservation?,
        val popupBefore: PopupState?,
        val marchSession: ActionMarchAssociationTracker.Session,
        val ownMarchConfirmed: Boolean = false
    )

    data class Result(
        val lifecycle: ActionLifecycleSnapshot,
        val session: Session?
    )

    var session: Session? = null
        private set

    val lifecycleSnapshot: ActionLifecycleSnapshot
        get() = lifecycle.snapshot

    private var completedTarget: ActionTargetSnapshot? = null
    private var postActionStartedAtMs: Long? = null
    private var postEvidenceSignature: Set<PostActionEvidence>? = null
    private var postEvidenceFrames: Int = 0

    companion object {
        const val POST_ACTION_TIMEOUT_MS = 4_000L
        const val POST_ACTION_CONFIRMATION_FRAMES = 2
    }

    fun request(
        automaticActionsEnabled: Boolean,
        selected: ActionTargetSnapshot?,
        validation: TargetValidationResult,
        beforeObservation: MapObservation?,
        popupBefore: PopupState?,
        baselineMarchSignals: List<MarchSignal>,
        nowMs: Long
    ): Result {
        if (lifecycle.snapshot.state == ActionLifecycleState.SUCCEEDED &&
            selected != null && selected == completedTarget
        ) {
            return Result(lifecycle.snapshot, session)
        }
        session = selected?.let {
            Session(
                beforeObservation = beforeObservation,
                popupBefore = popupBefore,
                marchSession = marchTracker.begin(it.point, baselineMarchSignals)
            )
        }
        return Result(
            lifecycle = lifecycle.request(
                automaticActionsEnabled = automaticActionsEnabled,
                selected = selected,
                validation = validation,
                nowMs = nowMs
            ),
            session = session
        )
    }

    fun revalidate(
        latestObservation: MapObservation?,
        latestValidation: TargetValidationResult,
        latestAction: ActionButton?
    ): Result {
        val selected = lifecycle.snapshot.selected
        val validation = PreActionRevalidator.revalidate(
            selected = selected,
            latestObservation = latestObservation,
            latestValidation = latestValidation,
            latestAction = latestAction
        )
        return Result(
            lifecycle = lifecycle.revalidated(validation),
            session = session
        )
    }

    /**
     * Dispatch is supplied by the caller so this class remains testable and
     * cannot silently acquire or invoke Accessibility gestures.
     */
    fun dispatch(nowMs: Long, dispatch: () -> Boolean): Result {
        val accepted = dispatch()
        val next = lifecycle.dispatched(nowMs, accepted)
        postActionStartedAtMs = if (next.state == ActionLifecycleState.WAITING_FOR_RESULT) nowMs else null
        return Result(
            lifecycle = next,
            session = session
        )
    }

    fun observeMarch(signals: List<MarchSignal>, nowMs: Long): Result {
        val current = session ?: return Result(lifecycle.snapshot, null)
        if (current.ownMarchConfirmed) return Result(lifecycle.snapshot, current)

        val update = marchTracker.update(
            session = current.marchSession,
            signals = signals,
            nowMs = nowMs
        )
        session = current.copy(
            marchSession = update.session,
            ownMarchConfirmed = update.ownMarchConfirmed
        )
        return Result(lifecycle.snapshot, session)
    }

    fun verifyPostAction(
        afterObservation: MapObservation?,
        popupAfter: PopupState?,
        nowMs: Long = System.currentTimeMillis()
    ): Result {
        val current = session
        val selected = lifecycle.snapshot.selected
        if (current == null || selected == null) {
            return Result(lifecycle.snapshot, current)
        }

        val evidence = PostActionStateVerifier.collectEvidence(
            selected = selected,
            before = current.beforeObservation,
            after = afterObservation,
            popupBefore = current.popupBefore,
            popupAfter = popupAfter
        ).toMutableSet()

        val cameraStable = afterObservation?.evidence?.contains(
            com.coolhiman.lordsassistant.model.ObservationEvidence.CAMERA_UNSTABLE
        ) != true
        if (current.ownMarchConfirmed &&
            cameraStable &&
            PostActionStateVerifier.isSameTarget(afterObservation, selected)
        ) {
            evidence += PostActionEvidence.OWN_MARCH_CONFIRMED
        }

        val predicted = ActionPostVerifier.verify(evidence)
        if (predicted == ActionLifecycleState.UNKNOWN) {
            postEvidenceSignature = null
            postEvidenceFrames = 0
            val startedAt = postActionStartedAtMs
            if (startedAt != null && nowMs - startedAt >= POST_ACTION_TIMEOUT_MS) {
                postActionStartedAtMs = null
                return Result(lifecycle.timeout(), session)
            }
            return Result(lifecycle.snapshot, session)
        }

        if (postEvidenceSignature == evidence) {
            postEvidenceFrames += 1
        } else {
            postEvidenceSignature = evidence.toSet()
            postEvidenceFrames = 1
        }

        if (postEvidenceFrames < POST_ACTION_CONFIRMATION_FRAMES) {
            return Result(lifecycle.snapshot, session)
        }

        val verified = lifecycle.verify(evidence)
        if (verified.state == ActionLifecycleState.SUCCEEDED) {
            completedTarget = selected
            postActionStartedAtMs = null
            postEvidenceSignature = null
            postEvidenceFrames = 0
        } else if (verified.state == ActionLifecycleState.FAILED) {
            postActionStartedAtMs = null
            postEvidenceSignature = null
            postEvidenceFrames = 0
        }
        return Result(
            lifecycle = verified,
            session = current
        )
    }

    fun timeout(): Result =
        Result(lifecycle.timeout(), session)

    fun reset(): Result {
        lifecycle.reset()
        session = null
        completedTarget = null
        postActionStartedAtMs = null
        postEvidenceSignature = null
        postEvidenceFrames = 0
        return Result(lifecycle.snapshot, null)
    }
}
