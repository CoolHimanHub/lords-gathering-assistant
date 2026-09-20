package com.coolhiman.lordsassistant.target

import com.coolhiman.lordsassistant.map.CameraState
import com.coolhiman.lordsassistant.map.LiveMapScanResult
import com.coolhiman.lordsassistant.vision.MarchAssociationDiagnostics

data class ActionDiagnosticsSnapshot(
    val timestampMs: Long,
    val detectedTiles: Int,
    val processingMs: Long,
    val cameraState: CameraState,
    val cameraSharedTargets: Int,
    val cameraScaleChangePercent: Float,
    val validationStage: TargetValidationStage,
    val validationReasons: Set<TargetBlockReason>,
    val validationSafe: Boolean,
    val selected: ActionTargetSnapshot?,
    val actionKind: ActionKind?,
    val marchAssociation: MarchAssociationDiagnostics?,
    val targetStability: TargetStability,
    val lifecycle: ActionLifecycleSnapshot,
    val actionAttemptId: Long?,
    val recoveryEpoch: Long = 0L,
    val recoveryEpochPersistenceHealthy: Boolean = true,
    val journalAttemptId: Long? = null,
    val journalRecoveryEpoch: Long? = null,
    val journalRecoveryEpochPersisted: Boolean = false,
    val reconciledInitialEpoch: Long = 0L,
    val restartQuarantine: Boolean = false,
    val evidence: PostActionEvidenceRecord?
) {
    companion object {
        fun fromScan(
            scan: LiveMapScanResult,
            lifecycle: ActionLifecycleSnapshot,
            evidence: PostActionEvidenceRecord?,
            actionAttemptId: Long? = null,
            recoveryEpoch: Long = 0L,
            recoveryEpochPersistenceHealthy: Boolean = true,
            journalAttemptId: Long? = null,
            journalRecoveryEpoch: Long? = null,
            journalRecoveryEpochPersisted: Boolean = false,
            reconciledInitialEpoch: Long = 0L,
            restartQuarantine: Boolean = false,
            timestampMs: Long = System.currentTimeMillis()
        ) = ActionDiagnosticsSnapshot(
            timestampMs, scan.detectedTiles, scan.processingMs, scan.cameraState,
            scan.cameraSharedTargets, scan.cameraScaleChangePercent, scan.validation.stage, scan.validation.reasons,
            scan.validation.safe, scan.selectedActionTarget, scan.actionButton?.kind,
            scan.selectedMarchAssociation, scan.targetStability, lifecycle, actionAttemptId, recoveryEpoch,
            recoveryEpochPersistenceHealthy, journalAttemptId, journalRecoveryEpoch, journalRecoveryEpochPersisted,
            reconciledInitialEpoch, restartQuarantine, evidence
        )
    }
}

object ActionDiagnosticsFormatter {
    fun format(snapshot: ActionDiagnosticsSnapshot?): String {
        if (snapshot == null) return "No live scan yet. Start the screen scanner to populate diagnostics."
        val selected = snapshot.selected
        val evidence = snapshot.evidence
        return buildString {
            appendLine("LIVE EVIDENCE / SAFETY DIAGNOSTICS")
            appendLine("Frame: ${snapshot.timestampMs}")
            appendLine("Tiles: ${snapshot.detectedTiles}  •  ${snapshot.processingMs} ms")
            appendLine("Camera: ${snapshot.cameraState.name}  •  shared=${snapshot.cameraSharedTargets}  •  scale=${"%.1f".format(snapshot.cameraScaleChangePercent)}%")
            appendLine("Validation: ${snapshot.validationStage.name}  •  safe=${snapshot.validationSafe}")
            if (snapshot.validationReasons.isNotEmpty()) appendLine("Blocked: ${snapshot.validationReasons.joinToString(", ") { it.name.replace('_', ' ') }}")
            appendLine("Selected: ${selected?.let { "${it.coordinate.kingdom}:${it.coordinate.x},${it.coordinate.y} ${it.kind.name} L${it.level}" } ?: "none"}")
            appendLine("Action: ${snapshot.actionKind?.name ?: "none"}")
            snapshot.marchAssociation?.let { association ->
                appendLine("March association: ${association.status.name}")
                association.nearestDistancePx?.let { appendLine("Nearest march: ${"%.1f".format(it)} px") }
                association.secondNearestDistancePx?.let { appendLine("2nd nearest: ${"%.1f".format(it)} px") }
                association.marginRatio?.let { appendLine("Association ratio: ${"%.2f".format(it)}") }
            }
            appendLine("Target stability: ${snapshot.targetStability.consecutiveFrames} frames  •  stable=${snapshot.targetStability.stable}")
            appendLine("Lifecycle: ${snapshot.lifecycle.state.name}")
            appendLine("Recovery epoch: ${snapshot.recoveryEpoch}")
            appendLine("Recovery epoch persistence: ${if (snapshot.recoveryEpochPersistenceHealthy) "healthy" else "FAILED — automatic execution blocked"}")
            appendLine("Action attempt: ${snapshot.actionAttemptId?.toString() ?: "none"}")
            appendLine("Journal attempt: ${snapshot.journalAttemptId?.toString() ?: "none"}")
            appendLine("Journal epoch: ${snapshot.journalRecoveryEpoch?.toString() ?: "none"}")
            appendLine("Journal epoch provenance: ${if (snapshot.journalRecoveryEpochPersisted) "present" else "LEGACY / missing"}")
            appendLine("Reconciled startup epoch: ${snapshot.reconciledInitialEpoch}")
            appendLine("Restart quarantine: ${if (snapshot.restartQuarantine) "ACTIVE" else "clear"}")
            if (snapshot.restartQuarantine) {
                appendLine("Quarantine reason: durable in-flight action requires deliberate recovery")
            }
            appendLine(
                "Automatic recovery: " +
                    if (ActionRecoveryPolicy.mayStartAutomaticAttempt(snapshot.lifecycle)) "allowed" else "blocked"
            )
            snapshot.lifecycle.failure?.let { appendLine("Failure: ${it.name}") }
            if (evidence == null) {
                appendLine("Post-action evidence: none")
            } else {
                appendLine("Evidence epoch: ${evidence.recoveryEpoch}")
                appendLine("Evidence attempt: ${evidence.attemptId}")
                appendLine("Evidence matches current attempt: ${snapshot.actionAttemptId == evidence.attemptId}")
                appendLine("Post-action evidence: ${evidence.evidence.joinToString(", ") { it.name }}")
                appendLine("Sources: ${evidence.sources.joinToString(", ") { it.name }}")
                appendLine("Confirming frames: ${evidence.confirmingFrames}")
                appendLine("Camera stable at verification: ${evidence.cameraStable}")
                evidence.marchTrajectoryEvidence?.let {
                    appendLine("Trajectory: ${it.start} -> ${it.end}")
                    appendLine("Displacement: ${"%.1f".format(it.displacementPx)} px")
                    appendLine("Direction: (${"%.2f".format(it.directionX)}, ${"%.2f".format(it.directionY)})")
                }
            }
            appendLine()
            appendLine("Safety rule: diagnostics never bypass validation or the interaction gate.")
        }
    }
}
