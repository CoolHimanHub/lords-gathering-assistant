package com.coolhimanhub.lordsgatheringassistant

/**
 * Instance-compatible bridge for GatheringAccessibilityService.
 *
 * GameCoordinateMapper.map(...) is currently defined on the companion object,
 * while the service keeps a GameCoordinateMapper instance. This extension
 * preserves the existing mapper implementation and makes instance calls
 * compile without changing coordinate math.
 */
fun GameCoordinateMapper.map(
    screenX: Int,
    screenY: Int,
    viewportX: Int,
    viewportY: Int,
    screenWidth: Int,
    screenHeight: Int
): GameCoordinateMapper.GameLocation =
    GameCoordinateMapper.map(
        screenX,
        screenY,
        viewportX,
        viewportY,
        screenWidth,
        screenHeight
    )
