/*
 * Spektrafilm for Android — native CameraX zoom math tests. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Pure JVM: the device range clamp and the +/- step are the only places that decide what ratio is
 * requested, so they are tested without CameraX or a device.
 */
package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraZoomTest {
    @Test
    fun aRequestedRatioIsClampedToTheDeviceRange() {
        assertEquals(1f, clampZoomRatio(0.25f, 1f, 8f))
        assertEquals(8f, clampZoomRatio(99f, 1f, 8f))
        assertEquals(2f, clampZoomRatio(2f, 0.5f, 10f))
        assertEquals(0.5f, clampZoomRatio(0.5f, 0.5f, 10f))
    }

    @Test
    fun aDeviceWithNoWideOrTeleIsLeftAtOneX() {
        assertEquals(1f, clampZoomRatio(2f, 1f, 1f))
        assertEquals(1f, clampZoomRatio(0.5f, 1f, 1f))
        assertEquals(1f, zoomPresetRatios(1f, 1f).single())
    }

    @Test
    fun invalidRangesAndValuesFallBackSafely() {
        assertEquals(1f, clampZoomRatio(Float.NaN, 1f, 4f))
        assertEquals(1f, clampZoomRatio(2f, Float.NaN, Float.NaN))
        assertEquals(2f, clampZoomRatio(Float.POSITIVE_INFINITY, 2f, 4f))
        assertEquals(1f, clampZoomRatio(1f, 0f, 4f))
        assertEquals(4f, clampZoomRatio(1f, 4f, 2f))
    }

    @Test
    fun plusAndMinusMoveByHalfStopAndStopAtTheEdges() {
        assertEquals(1.5f, stepZoomRatio(1f, 0.5f, 8f, 1))
        assertEquals(0.5f, stepZoomRatio(1f, 0.5f, 8f, -1))
        assertEquals(8f, stepZoomRatio(8f, 0.5f, 8f, 1))
        assertEquals(0.5f, stepZoomRatio(0.5f, 0.5f, 8f, -1))
        assertEquals(2f, stepZoomRatio(1f, 2f, 2f, 1))
    }

    @Test
    fun quickRatiosAreOnlyOfferedWhenTheRangeContainsThem() {
        assertEquals(listOf(1f, 2f), zoomPresetRatios(1f, 4f))
        assertEquals(listOf(0.5f, 1f), zoomPresetRatios(0.5f, 1.5f))
        assertEquals(listOf(0.5f, 1f, 2f), zoomPresetRatios(0.5f, 10f))
        assertTrue(zoomPresetRatios(2f, 4f).none { it < 2f })
    }

    @Test
    fun theDisplayAlwaysShowsTheActualOneDecimalValue() {
        assertEquals("1.0×", formatZoomRatio(1f))
        assertEquals("2.0×", formatZoomRatio(2f))
        assertEquals("0.5×", formatZoomRatio(0.5f))
        assertEquals("3.7×", formatZoomRatio(3.74f))
        assertEquals("1.0×", formatZoomRatio(Float.NaN))
    }
}
