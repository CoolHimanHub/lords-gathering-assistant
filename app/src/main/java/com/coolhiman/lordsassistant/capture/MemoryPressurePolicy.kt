package com.coolhiman.lordsassistant.capture

enum class MemoryPressureLevel {
    NORMAL,
    WARNING,
    CRITICAL
}

data class MemoryPressureSnapshot(
    val usedBytes: Long,
    val maxBytes: Long,
    val usedRatio: Double,
    val level: MemoryPressureLevel
)

class MemoryPressurePolicy(
    private val warningRatio: Double = 0.80,
    private val criticalRatio: Double = 0.90
) {
    init {
        require(warningRatio in 0.0..1.0)
        require(criticalRatio in 0.0..1.0)
        require(warningRatio < criticalRatio)
    }

    fun evaluate(usedBytes: Long, maxBytes: Long): MemoryPressureSnapshot {
        require(maxBytes > 0L)
        val safeUsed = usedBytes.coerceAtLeast(0L)
        val ratio = (safeUsed.toDouble() / maxBytes.toDouble()).coerceAtMost(1.0)
        val level = when {
            ratio >= criticalRatio -> MemoryPressureLevel.CRITICAL
            ratio >= warningRatio -> MemoryPressureLevel.WARNING
            else -> MemoryPressureLevel.NORMAL
        }
        return MemoryPressureSnapshot(safeUsed, maxBytes, ratio, level)
    }
}
