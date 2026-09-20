package com.coolhiman.lordsassistant.target

import com.coolhiman.lordsassistant.model.MapObservation
import com.coolhiman.lordsassistant.model.ObservationEvidence
import com.coolhiman.lordsassistant.vision.MarchSignal
import com.coolhiman.lordsassistant.vision.PopupState

class ActionOrchestrator(
    initialRecoveryEpoch: Long = 0L,
    private val lifecycle: ActionLifecycleController = ActionLifecycleController(),
    private val marchTracker: ActionMarchAssociationTracker = ActionMarchAssociationTracker(),
    private val attemptIdAllocator: ((Long) -> Long?)? = null
) {
    data class Session(
        val attemptId: Long,
        val recoveryEpoch: Long,
        val selected: ActionTargetSnapshot,
        val beforeObservation: MapObservation?,
        val popupBefore: PopupState?,
        val marchSession: ActionMarchAssociationTracker.Session,
        val ownMarchConfirmed: Boolean = false
    )
    data class Result(val lifecycle: ActionLifecycleSnapshot, val session: Session?)

    var session: Session? = null
        private set
    val lifecycleSnapshot: ActionLifecycleSnapshot get() = lifecycle.snapshot
    var lastPostActionEvidence: PostActionEvidenceRecord? = null
        private set

    private var nextAttemptId = 0L
    private var recoveryEpoch = initialRecoveryEpoch

    val currentRecoveryEpoch: Long get() = recoveryEpoch
    private var completedTarget: ActionTargetSnapshot? = null
    private var postActionStartedAtMs: Long? = null
    private var postEvidenceSignature: Set<PostActionEvidence>? = null
    private var postEvidenceFrames = 0

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
        if (lifecycle.snapshot.failure == ActionLifecycleFailure.RECOVERY_EPOCH_EXHAUSTED) {
            session = null
            return Result(lifecycle.snapshot, null)
        }

        // Keep the recovery invariant at the orchestration boundary itself.
        // The capture service also checks this policy, but callers must not be
        // able to bypass it by invoking request() directly after UNKNOWN or
        // while another action is still in flight.
        if (!ActionRecoveryPolicy.mayStartAutomaticAttempt(lifecycle.snapshot)) {
            session = null
            return Result(lifecycle.snapshot, null)
        }

        if (lifecycle.snapshot.state == ActionLifecycleState.SUCCEEDED &&
            selected != null && selected == completedTarget) return Result(lifecycle.snapshot, session)

        lastPostActionEvidence = null
        val attemptId = attemptIdAllocator?.invoke(nextAttemptId) ?: (nextAttemptId + 1L)
        if (attemptId == null) {
            session = null
            return Result(lifecycle.attemptIdPersistenceFailed(selected), null)
        }
        nextAttemptId = maxOf(nextAttemptId, attemptId)
        session = selected?.let {
            Session(
                attemptId = attemptId,
                recoveryEpoch = recoveryEpoch,
                selected = it,
                beforeObservation = beforeObservation,
                popupBefore = popupBefore,
                marchSession = marchTracker.begin(it.point, baselineMarchSignals)
            )
        }
        return Result(lifecycle.request(automaticActionsEnabled, selected, validation, nowMs), session)
    }

    fun revalidate(latestObservation: MapObservation?, latestValidation: TargetValidationResult, latestAction: ActionButton?): Result {
        val current = session
        val selected = lifecycle.snapshot.selected
        if (current == null || selected == null || selected != current.selected) {
            return Result(lifecycle.revalidated(TargetValidationResult(false, TargetValidationStage.DETECTED, setOf(TargetBlockReason.TARGET_CHANGED))), current)
        }
        return Result(lifecycle.revalidated(PreActionRevalidator.revalidate(selected, latestObservation, latestValidation, latestAction)), current)
    }

    fun dispatch(nowMs: Long, dispatch: () -> Boolean): Result {
        val current = session
        val selected = lifecycle.snapshot.selected
        if (current == null || selected == null || selected != current.selected) return Result(lifecycle.dispatched(nowMs, false), current)
        val next = lifecycle.dispatched(nowMs, dispatch())
        postActionStartedAtMs = if (next.state == ActionLifecycleState.WAITING_FOR_RESULT) nowMs else null
        return Result(next, current)
    }

    fun observeMarch(signals: List<MarchSignal>, nowMs: Long): Result {
        val current = session ?: return Result(lifecycle.snapshot, null)
        val selected = lifecycle.snapshot.selected
        if (selected == null || selected != current.selected || lifecycle.snapshot.state != ActionLifecycleState.WAITING_FOR_RESULT) return Result(lifecycle.snapshot, current)
        if (current.ownMarchConfirmed) return Result(lifecycle.snapshot, current)
        val update = marchTracker.update(current.marchSession, signals, nowMs)
        session = current.copy(marchSession = update.session, ownMarchConfirmed = update.ownMarchConfirmed)
        return Result(lifecycle.snapshot, session)
    }

    fun verifyPostAction(afterObservation: MapObservation?, popupAfter: PopupState?, nowMs: Long = System.currentTimeMillis()): Result {
        val current = session
        val selected = lifecycle.snapshot.selected
        if (current == null || selected == null || selected != current.selected || lifecycle.snapshot.state != ActionLifecycleState.WAITING_FOR_RESULT) return Result(lifecycle.snapshot, current)

        val evidence = PostActionStateVerifier.collectEvidence(selected, current.beforeObservation, afterObservation, current.popupBefore, popupAfter).toMutableSet()
        val cameraStable = afterObservation?.evidence?.contains(ObservationEvidence.CAMERA_UNSTABLE) != true
        val sources = linkedSetOf<PostActionEvidenceSource>()
        if (PostActionEvidence.POPUP_DISAPPEARED in evidence) sources += PostActionEvidenceSource.POPUP_STATE
        if (PostActionEvidence.TARGET_REMOVED in evidence || PostActionEvidence.TARGET_OCCUPIED in evidence) sources += PostActionEvidenceSource.TARGET_STATE
        if (current.ownMarchConfirmed && cameraStable && PostActionStateVerifier.isSameTarget(afterObservation, selected)) {
            evidence += PostActionEvidence.OWN_MARCH_CONFIRMED
            sources += PostActionEvidenceSource.MARCH_ASSOCIATION
        }

        val predicted = ActionPostVerifier.verify(evidence)
        if (predicted == ActionLifecycleState.UNKNOWN) {
            postEvidenceSignature = null
            postEvidenceFrames = 0
            lastPostActionEvidence = null
            val startedAt = postActionStartedAtMs
            if (startedAt != null && nowMs - startedAt >= POST_ACTION_TIMEOUT_MS) {
                postActionStartedAtMs = null
                return Result(lifecycle.timeout(), session)
            }
            return Result(lifecycle.snapshot, session)
        }

        postEvidenceFrames = if (postEvidenceSignature == evidence) postEvidenceFrames + 1 else 1
        postEvidenceSignature = evidence.toSet()
        lastPostActionEvidence = PostActionEvidenceRecord(
            attemptId = current.attemptId,
            recoveryEpoch = current.recoveryEpoch,
            evidence = evidence.toSet(),
            sources = sources.toSet(),
            selected = selected,
            observedCoordinate = afterObservation?.coordinate,
            observedKind = afterObservation?.kind,
            observedLevel = afterObservation?.level,
            cameraStable = cameraStable,
            confirmingFrames = postEvidenceFrames,
            timestampMs = nowMs,
            marchTrajectory = current.marchSession.trajectory,
            marchTrajectoryEvidence = MarchTrajectoryEvidence.from(
                actionPoint = selected.point,
                trajectory = current.marchSession.trajectory,
                confirmingFrames = current.marchSession.confirmedFrames,
                cameraStable = cameraStable
            )
        )

        if (postEvidenceFrames < POST_ACTION_CONFIRMATION_FRAMES) return Result(lifecycle.snapshot, session)

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
        return Result(verified, current)
    }

    fun restoreUnknown(attemptId: Long): Result {
        nextAttemptId = maxOf(nextAttemptId, attemptId)
        if (!advanceRecoveryEpoch()) {
            session = null
            lastPostActionEvidence = null
            return Result(lifecycle.recoveryEpochExhausted(), null)
        }
        session = null
        completedTarget = null
        postActionStartedAtMs = null
        postEvidenceSignature = null
        postEvidenceFrames = 0
        lastPostActionEvidence = null
        return Result(lifecycle.restoreUnknown(), null)
    }

    fun timeout(): Result = Result(lifecycle.timeout(), session)

    fun reset(): Result {
        if (!advanceRecoveryEpoch()) {
            session = null
            lastPostActionEvidence = null
            return Result(lifecycle.recoveryEpochExhausted(), null)
        }
        lifecycle.reset()
        session = null
        completedTarget = null
        postActionStartedAtMs = null
        postEvidenceSignature = null
        postEvidenceFrames = 0
        lastPostActionEvidence = null
        return Result(lifecycle.snapshot, null)
    }

    /**
     * Advances the recovery boundary without allowing Long overflow to wrap
     * the epoch and accidentally reuse an old provenance identity.
     */
    private fun advanceRecoveryEpoch(): Boolean {
        if (recoveryEpoch == Long.MAX_VALUE) return false
        recoveryEpoch += 1L
        return true
    }
}
