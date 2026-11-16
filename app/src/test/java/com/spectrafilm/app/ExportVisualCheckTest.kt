/*
 * Spektrafilm for Android — visual export correlation tests. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportVisualCheckTest {
    @Test
    fun identicalSamplesHavePerfectCorrelation() {
        val samples = samples(256)

        assertEquals(1.0, ExportVisualCheck.correlation(samples, samples.copyOf())!!, 1e-9)
    }

    @Test
    fun exposureAndContrastChangesDoNotChangeImageIdentity() {
        val first = samples(256)
        val second = DoubleArray(first.size) { index -> first[index] * 0.65 + 18.0 }

        assertEquals(1.0, ExportVisualCheck.correlation(first, second)!!, 1e-9)
    }

    @Test
    fun independentNoiseIsRejectedByThreshold() {
        val first = samples(256)
        val random = Random(91)
        val second = DoubleArray(first.size) { random.nextDouble() * 255.0 }

        val correlation = ExportVisualCheck.correlation(first, second)!!

        assertTrue(correlation < ExportVisualCheck.MIN_CORRELATION)
    }

    @Test
    fun reversedImageIsRejectedByThreshold() {
        val first = DoubleArray(256) { index -> (index % 16) * 12.0 + (index / 16) * 3.0 }
        val second = first.reversedArray()

        val correlation = ExportVisualCheck.correlation(first, second)!!

        assertTrue(correlation < ExportVisualCheck.MIN_CORRELATION)
    }

    @Test
    fun solidImagesAreIndeterminateRatherThanFailures() {
        assertNull(ExportVisualCheck.correlation(DoubleArray(64) { 40.0 }, DoubleArray(64) { 90.0 }))
    }

    @Test
    fun oneSolidImageIsAlsoIndeterminate() {
        assertNull(ExportVisualCheck.correlation(samples(64), DoubleArray(64)))
    }

    @Test
    fun invalidGridShapesAreIndeterminate() {
        assertNull(ExportVisualCheck.correlation(DoubleArray(0), DoubleArray(0)))
        assertNull(ExportVisualCheck.correlation(DoubleArray(4), DoubleArray(8)))
    }

    @Test
    fun firstVisualFailureEncodesOneReplacementBeforePublication() {
        var encodeCalls = 0
        var retryCalls = 0
        var publishCalls = 0

        val result = ExportVisualCheck.withOneRetry(
            encode = { ++encodeCalls },
            verify = { candidate ->
                if (candidate == 1) throw ExportVisualCheckException("corrupt")
            },
            onRetry = { retryCalls++ },
        )
        if (result == 2) publishCalls++

        assertEquals(2, result)
        assertEquals(2, encodeCalls)
        assertEquals(1, retryCalls)
        assertEquals(1, publishCalls)
    }

    @Test
    fun secondVisualFailureStopsBeforePublication() {
        var encodeCalls = 0
        var publishCalls = 0

        val failure = runCatching {
            ExportVisualCheck.withOneRetry(
                encode = { ++encodeCalls },
                verify = { throw ExportVisualCheckException("still corrupt") },
            )
        }.exceptionOrNull()

        if (failure == null) publishCalls++
        assertTrue(failure is ExportVisualCheckException)
        assertEquals(2, encodeCalls)
        assertEquals(0, publishCalls)
    }

    @Test
    fun cancellationIsNotRetriedOrPublished() {
        var encodeCalls = 0
        val failure = runCatching {
            ExportVisualCheck.withOneRetry(
                encode = { ++encodeCalls },
                verify = { throw java.util.concurrent.CancellationException("cancelled") },
            )
        }.exceptionOrNull()

        assertTrue(failure is java.util.concurrent.CancellationException)
        assertEquals(1, encodeCalls)
    }

    private fun samples(size: Int, seed: Int = 37): DoubleArray {
        val random = Random(seed)
        return DoubleArray(size) { index ->
            ((index * 37) % 251).toDouble() + random.nextDouble() * 8.0
        }
    }
}
