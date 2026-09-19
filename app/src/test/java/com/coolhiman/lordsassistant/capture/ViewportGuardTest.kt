package com.coolhiman.lordsassistant.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportGuardTest {
    @Test
    fun acceptsStableDimensions() {
        val guard = ViewportGuard()
        assertTrue(guard.accept(2756, 1268))
        assertTrue(guard.accept(2756, 1268))
    }

    @Test
    fun rejectsDimensionChange() {
        val guard = ViewportGuard()
        assertTrue(guard.accept(2756, 1268))
        assertFalse(guard.accept(1920, 1080))
    }

    @Test
    fun rejectsInvalidInitialDimensions() {
        val guard = ViewportGuard()
        assertFalse(guard.accept(0, 1268))
        assertFalse(guard.accept(2756, 0))
    }
}
