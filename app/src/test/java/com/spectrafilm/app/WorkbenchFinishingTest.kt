/*
 * Spektrafilm for Android — workbench finishing geometry tests. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Pure JVM geometry only: no Bitmap, no Canvas, no device. It pins the print contract that the
 * border/date are laid out OUTSIDE the photo rectangle, so the stamp can never cover the image,
 * and that an invalid capture time falls back deterministically.
 */
package com.spectrafilm.app

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkbenchFinishingTest {

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    private fun layout(
        width: Int,
        height: Int,
        config: WorkbenchFinishingConfig,
    ) = WorkbenchFinishingGeometry.layout(width, height, config, utc)

    @Test
    fun noneAndOffIsTheIdentityAndCreatesNoCopyContract() {
        val config = WorkbenchFinishingConfig()
        assertTrue(config.isIdentity)
        assertEquals("", config.cacheKey())

        val result = layout(4000, 3000, config)
        assertEquals(4000, result.outputWidth)
        assertEquals(3000, result.outputHeight)
        assertEquals(0, result.imageLeft)
        assertEquals(0, result.imageTop)
        assertFalse(result.hasFrame)
        assertNull(result.dateText)
    }

    @Test
    fun portraitAndLandscapeBordersScaleWithTheShortEdge() {
        val config = WorkbenchFinishingConfig(border = WorkbenchBorder.WHITE)
        val portrait = layout(3000, 4000, config)
        val landscape = layout(4000, 3000, config)

        assertEquals(3000, portrait.outputWidth - portrait.imageLeft * 2)
        assertEquals(4000, portrait.outputHeight - portrait.imageTop * 2)
        assertEquals(landscape.imageLeft, portrait.imageLeft)
        assertEquals(landscape.imageTop, portrait.imageTop)
        assertTrue("a white border must exist on all four sides", portrait.imageLeft > 0)
        assertTrue(portrait.imageTop > 0)
        assertEquals(WorkbenchFinishingGeometry.WHITE, portrait.frameColor)
    }

    @Test
    fun whiteAndBlackFramesUseTheirOwnPrintColour() {
        val white = layout(1200, 900, WorkbenchFinishingConfig(border = WorkbenchBorder.WHITE))
        val black = layout(1200, 900, WorkbenchFinishingConfig(border = WorkbenchBorder.BLACK))

        assertEquals(WorkbenchFinishingGeometry.WHITE, white.background)
        assertEquals(WorkbenchFinishingGeometry.BLACK, black.background)
        assertTrue(white.dateTextColor != black.dateTextColor)
        assertEquals(white.outputWidth, black.outputWidth)
        assertEquals(white.outputHeight, black.outputHeight)
    }

    @Test
    fun allThreeDateOptionsAreDistinctAndTheStampSitsBelowThePhoto() {
        val off = layout(1600, 1200, WorkbenchFinishingConfig())
        val date = layout(
            1600,
            1200,
            WorkbenchFinishingConfig(
                date = WorkbenchDate.DATE,
                captureTimestampMs = 1_788_357_780_000L,
            ),
        )
        val dateTime = layout(
            1600,
            1200,
            WorkbenchFinishingConfig(
                date = WorkbenchDate.DATE_TIME,
                captureTimestampMs = 1_788_357_780_000L,
            ),
        )

        assertNull(off.dateText)
        assertEquals("2026.09.02", date.dateText)
        assertEquals("2026.09.02  14:03", dateTime.dateText)

        // No crop of the photo: the extra height is exactly the date strip below it.
        assertTrue(date.outputHeight > 1200)
        assertTrue(
            "the date baseline must be below the photo bottom edge",
            date.dateBaseline > date.imageTop + 1200,
        )
        assertEquals(date.outputHeight, dateTime.outputHeight)
    }

    @Test
    fun borderlessDateAddsACompactBlackBottomStrip() {
        val result = layout(1000, 750, WorkbenchFinishingConfig(date = WorkbenchDate.DATE))

        assertFalse(result.hasFrame)
        assertEquals(1000, result.outputWidth)
        assertTrue(result.outputHeight > 750)
        assertEquals(WorkbenchFinishingGeometry.BLACK, result.background)
        assertTrue(result.dateRight <= 1000f)
        assertNotEquals("", WorkbenchFinishingConfig(date = WorkbenchDate.DATE).cacheKey())
    }

    @Test
    fun anInvalidCaptureTimeFallsBackDeterministically() {
        val negative = layout(
            800,
            600,
            WorkbenchFinishingConfig(date = WorkbenchDate.DATE, captureTimestampMs = -1L),
        )
        val zero = layout(
            800,
            600,
            WorkbenchFinishingConfig(date = WorkbenchDate.DATE, captureTimestampMs = 0L),
        )
        val tooLarge = layout(
            800,
            600,
            WorkbenchFinishingConfig(
                date = WorkbenchDate.DATE,
                captureTimestampMs = Long.MAX_VALUE,
            ),
        )

        assertEquals("1970.01.01", negative.dateText)
        assertEquals(zero.dateText, negative.dateText)
        assertEquals("9999.12.31", tooLarge.dateText)
        assertEquals(
            0L,
            WorkbenchFinishingConfig(captureTimestampMs = -99L).normalizedCaptureTimestampMs(),
        )
    }

    @Test
    fun cacheKeySeparatesEveryDecorationAndTheFrozenTime() {
        val none = WorkbenchFinishingConfig()
        val white = WorkbenchFinishingConfig(border = WorkbenchBorder.WHITE)
        val black = WorkbenchFinishingConfig(border = WorkbenchBorder.BLACK)
        val date = WorkbenchFinishingConfig(date = WorkbenchDate.DATE)
        val dateTime = WorkbenchFinishingConfig(date = WorkbenchDate.DATE_TIME)
        val early = WorkbenchFinishingConfig(date = WorkbenchDate.DATE, captureTimestampMs = 1000L)
        val late = WorkbenchFinishingConfig(date = WorkbenchDate.DATE, captureTimestampMs = 2000L)

        assertEquals("", none.cacheKey())
        assertEquals(7, setOf(
            none, white, black, date, dateTime, early, late,
        ).map { it.cacheKey() }.size)
    }
}
