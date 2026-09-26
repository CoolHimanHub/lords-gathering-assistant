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
        val captureSessionId: Long?,
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
    private var activeCaptureSessionId: Long? = null

    val currentRecoveryEpoch: Long get() = recoveryEpoch
    val currentCaptureSessionId: Long? get() = activeCaptureSessionId
    private var completedTargetIdentity: ActionTargetIdentity? = null
    private var postActionStartedAtMs: Long? = null
    private var postEvidenceSignature: Set<PostActionEvidence>? = null
    private var postEvidenceFrames = 0

    companion object {
        const val POST_ACTION_TIMEOUT_MS = 4_000L
        const val POST_ACTION_CONFIRMATION_FRAMES = 2
    }

    /**
     * Establishes the hard action boundary for a new MediaProjection session.
     *
     * Any target selected, completed, or awaiting verification in the previous
     * capture session is never allowed to cross this boundary. An in-flight
     * attempt becomes UNKNOWN and therefore requires deliberate recovery.
     */
    fun beginCaptureSession(captureSessionId: Long): Result {
        require(captureSessionId > 0L) { "captureSessionId must be positive" }
        val previous = activeCaptureSessionId
        if (previous == captureSessionId) {
            return Result(lifecycle.snapshot, session)
        }

        if (previous != null) {
            when (lifecycle.snapshot.state) {
                ActionLifecycleState.REQUESTED,
                ActionLifecycleState.REVALIDATED,
                ActionLifecycleState.WAITING_FOR_RESULT -> {
                    if (!advanceRecoveryEpoch()) {
                        session = null
                        return Result(lifecycle.recoveryEpochExhausted(), null)
                    }
                    lifecycle.captureSessionChanged()
                }
                ActionLifecycleState.UNKNOWN -> Unit
                ActionLifecycleState.IDLE,
                ActionLifecycleState.SUCCEEDED -> lifecycle.reset()
                ActionLifecycleState.FAILED -> {
                    // A recovery-epoch exhaustion is a fail-closed boundary.
                    // A new capture session must not silently clear it.
                    if (lifecycle.snapshot.failure != ActionLifecycleFailure.RECOVERY_EPOCH_EXHAUSTED) {
                        lifecycle.reset()
                    }
                }
            }
        }

        activeCaptureSessionId = captureSessionId
        session = null
        completedTargetIdentity = null
        postActionStartedAtMs = null
        postEvidenceSignature = null
        postEvidenceFrames = 0
        lastPostActionEvidence = null
        return Result(lifecycle.snapshot, null)
    }

    fun request(
        automaticActionsEnabled: Boolean,
        selected: ActionTargetSnapshot?,
        validation: TargetValidationResult,
        beforeObservation: MapObservation?,
        popupBefore: PopupState?,
        baselineMarchSignals: List<MarchSignal>,
        nowMs: Long,
        captureSessionId: Long? = null
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
            selected != null && selected.identity() == completedTargetIdentity) {
            session = null
            return Result(lifecycle.snapshot, null)
        }

        lastPostActionEvidence = null

        // Evaluate the lifecycle gate before creating any dispatchable session.
        val lifecycleResult = lifecycle.request(
            automaticActionsEnabled = automaticActionsEnabled,
            selected = selected,
            validation = validation,
            nowMs = nowMs
        )
        if (lifecycleResult.state != ActionLifecycleState.REQUESTED) {
            session = null
            return Result(lifecycleResult, null)
        }

        val attemptId: Long = if (attemptIdAllocator != null) {
            attemptIdAllocator.invoke(nextAttemptId)
                ?: run {
                    session = null
                    return Result(lifecycle.attemptIdPersistenceFailed(selected), null)
                }
        } else {
            nextAttemptId + 1L
        }
        nextAttemptId = maxOf(nextAttemptId, attemptId)

        session = selected?.let {
            Session(
                attemptId = attemptId,
                recoveryEpoch = recoveryEpoch,
                captureSessionId = captureSessionId,
                selected = it,
                beforeObservation = beforeObservation,
                popupBefore = popupBefore,
                marchSession = marchTracker.begin(it.point, baselineMarchSignals)
            )
        }
        return Result(lifecycleResult, session)
    }

    fun revalidate(latestObservation: MapObservation?, latestValidation: TargetValidationResult, latestAction: ActionButton?, nowMs: Long = System.currentTimeMillis(), captureSessionId: Long? = null): Result {
        val current = session
        val selected = lifecycle.snapshot.selected
        if (current == null || selected == null || selected.identity() != current.selected.identity()) {
            return Result(lifecycle.revalidated(TargetValidationResult(false, TargetValidationStage.DETECTED, setOf(TargetBlockReason.TARGET_CHANGED))), current)
        }
        if (current.captureSessionId != captureSessionId) {
            lifecycle.captureSessionChanged()
            return Result(lifecycle.snapshot, current)
        }
        return Result(lifecycle.revalidated(PreActionRevalidator.revalidate(selected, latestObservation, latestValidation, latestAction, nowMs)), current)
    }

    fun dispatch(nowMs: Long, captureSessionId: Long? = null, dispatch: () -> Boolean): Result {
        val current = session
        val selected = lifecycle.snapshot.selected
        if (current == null || selected == null || selected.identity() != current.selected.identity()) {
            return Result(lifecycle.dispatched(nowMs, false), current)
        }
        if (current.captureSessionId != captureSessionId) {
            lifecycle.captureSessionChanged()
            return Result(lifecycle.snapshot, current)
        }

        // Never invoke the real gesture callback unless the lifecycle has
        // already reached REVALIDATED. This keeps a failed/stale revalidation
        // from causing a side effect merely because a caller invoked dispatch().
        if (lifecycle.snapshot.state != ActionLifecycleState.REVALIDATED) {
            return Result(lifecycle.dispatched(nowMs, false), current)
        }

        val next = lifecycle.dispatched(nowMs, dispatch())
        postActionStartedAtMs = if (next.state == ActionLifecycleState.WAITING_FOR_RESULT) nowMs else null
        return Result(next, current)
    }

    fun observeMarch(signals: List<MarchSignal>, nowMs: Long): Result {
        val current = session ?: return Result(lifecycle.snapshot, null)
        val selected = lifecycle.snapshot.selected
        if (selected == null || selected.identity() != current.selected.identity() || lifecycle.snapshot.state != ActionLifecycleState.WAITING_FOR_RESULT) return Result(lifecycle.snapshot, current)
        if (current.ownMarchConfirmed) return Result(lifecycle.snapshot, current)
        val update = marchTracker.update(current.marchSession, signals, nowMs)
        session = current.copy(marchSession = update.session, ownMarchConfirmed = update.ownMarchConfirmed)
        return Result(lifecycle.snapshot, session)
    }

    fun verifyPostAction(afterObservation: MapObservation?, popupAfter: PopupState?, nowMs: Long = System.currentTimeMillis(), captureSessionId: Long? = null): Result {
        val current = session
        val selected = lifecycle.snapshot.selected
        if (current == null || selected == null || selected.identity() != current.selected.identity() || lifecycle.snapshot.state != ActionLifecycleState.WAITING_FOR_RESULT) return Result(lifecycle.snapshot, current)
        if (current.captureSessionId != captureSessionId) {
            lifecycle.captureSessionChanged()
            return Result(lifecycle.snapshot, current)
        }

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
            completedTargetIdentity = selected.identity()
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
        completedTargetIdentity = null
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
        completedTargetIdentity = null
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
