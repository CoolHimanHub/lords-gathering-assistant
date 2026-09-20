package com.coolhiman.lordsassistant.target

import com.coolhiman.lordsassistant.map.CameraState
import com.coolhiman.lordsassistant.map.LiveMapScanResult

data class ActionDiagnosticsSnapshot(
    val timestampMs: Long,
    val detectedTiles: Int,
    val processingMs: Long,
    val cameraState: CameraState,
    val cameraSharedTargets: Int,
    val validationStage: TargetValidationStage,
    val validationReasons: Set<TargetBlockReason>,
    val validationSafe: Boolean,
    val selected: ActionTargetSnapshot?,
    val actionKind: ActionKind?,
    val lifecycle: ActionLifecycleSnapshot,
    val evidence: PostActionEvidenceRecord?
) {
    companion object {
        fun fromScan(
            scan: LiveMapScanResult,
            lifecycle: ActionLifecycleSnapshot,
            evidence: PostActionEvidenceRecord?,
            timestampMs: Long = System.currentTimeMillis()
        ) = ActionDiagnosticsSnapshot(
            timestampMs, scan.detectedTiles, scan.processingMs, scan.cameraState,
            scan.cameraSharedTargets, scan.validation.stage, scan.validation.reasons,
            scan.validation.safe, scan.selectedActionTarget, scan.actionButton?.kind,
            lifecycle, evidence
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
            appendLine("Camera: ${snapshot.cameraState.name}  •  shared=${snapshot.cameraSharedTargets}")
            appendLine("Validation: ${snapshot.validationStage.name}  •  safe=${snapshot.validationSafe}")
            if (snapshot.validationReasons.isNotEmpty()) appendLine("Blocked: ${snapshot.validationReasons.joinToString(", ") { it.name.replace('_', ' ') }}")
            appendLine("Selected: ${selected?.let { "${it.coordinate.kingdom}:${it.coordinate.x},${it.coordinate.y} ${it.kind.name} L${it.level}" } ?: "none"}")
            appendLine("Action: ${snapshot.actionKind?.name ?: "none"}")
            appendLine("Lifecycle: ${snapshot.lifecycle.state.name}")
            snapshot.lifecycle.failure?.let { appendLine("Failure: ${it.name}") }
            if (evidence == null) {
                appendLine("Post-action evidence: none")
            } else {
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
