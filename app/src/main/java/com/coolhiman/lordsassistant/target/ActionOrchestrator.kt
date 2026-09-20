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

    private var completedTarget: ActionTargetSnapshot? = null

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
        return Result(
            lifecycle = lifecycle.dispatched(nowMs, accepted),
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
        popupAfter: PopupState?
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

        if (current.ownMarchConfirmed) {
            evidence += PostActionEvidence.OWN_MARCH_CONFIRMED
        }

        val verified = lifecycle.verify(evidence)
        if (verified.state == ActionLifecycleState.SUCCEEDED) {
            completedTarget = selected
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
        return Result(lifecycle.snapshot, null)
    }
}
