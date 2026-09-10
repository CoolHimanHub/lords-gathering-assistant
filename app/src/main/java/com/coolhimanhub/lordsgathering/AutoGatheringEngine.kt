package com.coolhimanhub.lordsgatheringassistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper

/**
 * AutoGatheringEngine handles the automated troop sending and gathering logic.
 * It manages the cycle of:
 * 1. Finding targets
 * 2. Sending troops
 * 3. Waiting for return
 * 4. Repeating
 */
class AutoGatheringEngine(
    private val service: AccessibilityService,
    private val handler: Handler = Handler(Looper.getMainLooper())
) {

    // ==========================================
    // STATE
    // ==========================================

    private var isGathering = false
    private var currentTarget: RssTarget? = null
    
    private val gatheringCycleRunnable = object : Runnable {
        override fun run() {
            if (!isGathering) return
            
            executeSingleGatherCycle()
            
            // Schedule next cycle with delay for troops to return
            handler.postDelayed(this, GATHER_CYCLE_INTERVAL)
        }
    }

    // ==========================================
    // CONFIG
    // ==========================================

    companion object {
        private const val GATHER_CYCLE_INTERVAL = 8000L      // 8 seconds between cycles
        private const val GESTURE_DURATION = 100L              // 100ms tap duration
        private const val TROOP_SEND_DELAY = 500L              // Delay before sending troops
        private const val CONFIRM_DIALOG_DELAY = 300L          // Delay for confirmation dialog
    }

    // ==========================================
    // TARGET DATA CLASS
    // ==========================================

    data class RssTarget(
        val type: String,
        val level: Int,
        val x: Int,
        val y: Int,
        val confidence: Int,
        val occupied: Boolean,
        val flagScore: Int,
        val targetScore: Int
    ) : Comparable<RssTarget> {
        
        override fun compareTo(other: RssTarget): Int {
            // Priority: unoccupied > high level > high confidence
            if (this.occupied != other.occupied) {
                return if (this.occupied) 1 else -1
            }
            if (this.level != other.level) {
                return other.level.compareTo(this.level)
            }
            return other.confidence.compareTo(this.confidence)
        }
    }

    // ==========================================
    // PUBLIC API
    // ==========================================

    fun startGathering() {
        if (isGathering) return
        
        isGathering = true
        handler.removeCallbacks(gatheringCycleRunnable)
        handler.post(gatheringCycleRunnable)
    }

    fun stopGathering() {
        isGathering = false
        handler.removeCallbacks(gatheringCycleRunnable)
        currentTarget = null
    }

    fun processDetectedTargets(targets: List<RssTarget>): RssTarget? {
        if (targets.isEmpty()) return null
        
        // Sort by priority and return best target
        val bestTarget = targets.sorted().firstOrNull()
        currentTarget = bestTarget
        return bestTarget
    }

    // ==========================================
    // GATHERING CYCLE
    // ==========================================

    private fun executeSingleGatherCycle() {
        // This should be called by the main service after screen analysis
        // Example flow:
        // 1. takeScreenshot()
        // 2. analyzeScreen() -> get RssTarget list
        // 3. processDetectedTargets(targets)
        // 4. performGatheringAction()
    }

    fun performGatheringAction(target: RssTarget) {
        currentTarget = target
        
        // Step 1: Tap the RSS tile
        tapLocation(target.x, target.y)
        
        // Step 2: Wait for tile details to appear, then send troops
        handler.postDelayed({
            sendTroops()
        }, TROOP_SEND_DELAY)
    }

    // ==========================================
    // GESTURE EXECUTION
    // ==========================================

    private fun tapLocation(x: Int, y: Int) {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, GESTURE_DURATION))
            .build()
        
        service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                // Gesture completed successfully
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                // Gesture was cancelled
            }
        }, handler)
    }

    private fun sendTroops() {
        // Tap the "Send" or "Gather" button
        // Coordinates should be based on your game's UI layout
        // Example: button typically at bottom center of tile details
        tapLocation(540, 1100) // Adjust based on your game UI
        
        // Wait for confirmation dialog
        handler.postDelayed({
            confirmSendTroops()
        }, CONFIRM_DIALOG_DELAY)
    }

    private fun confirmSendTroops() {
        // Tap the "Confirm" button on the dialog
        // Typically at bottom right of dialog
        tapLocation(720, 1200) // Adjust based on your game UI
    }

    // ==========================================
    // HELPER FUNCTIONS
    // ==========================================

    fun selectTroopType(troopType: Int = 1) {
        // Select which troop type to use (1=T1, 2=T2, etc)
        // This assumes there's a UI to select troop type
        when (troopType) {
            1 -> tapLocation(200, 900)  // T1 troop button
            2 -> tapLocation(350, 900)  // T2 troop button
            3 -> tapLocation(500, 900)  // T3 troop button
            4 -> tapLocation(650, 900)  // T4 troop button
            5 -> tapLocation(800, 900)  // T5 troop button
        }
    }

    fun getTroopReturnTime(resourceLevel: Int): Long {
        // Return time based on resource level
        return when (resourceLevel) {
            1 -> 30000L  // 30 seconds
            2 -> 45000L  // 45 seconds
            3 -> 60000L  // 60 seconds
            4 -> 90000L  // 90 seconds
            5 -> 120000L // 120 seconds
            else -> 30000L
        }
    }

    fun isGatheringActive(): Boolean = isGathering

    fun getCurrentTarget(): RssTarget? = currentTarget
}
