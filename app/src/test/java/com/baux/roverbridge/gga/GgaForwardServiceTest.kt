package com.baux.roverbridge.gga

import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class GgaForwardServiceTest {
    private val parser = GgaForwardService()
    private fun sentence(body: String): String {
        val checksum = body.fold(0) { a, c -> a xor c.code }
        return "GGA|\$$body*${"%02X".format(checksum)}"
    }
    @Test fun canonicalNmeaFieldsAndArrivalRemainSeparate() {
        val observation = parser.parse("GGA|\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47", 9_007_199_254_740_993L)
        assertEquals("12:35:19", observation.utcTimeOfDay)
        assertEquals(BigDecimal("48.117300000"), observation.latitude)
        assertEquals(BigDecimal("11.516666667"), observation.longitude)
        assertEquals(BigDecimal("545.4"), observation.altitudeMslMeters)
        assertEquals(1, observation.fixQuality)
        assertEquals(8, observation.satellites)
        assertEquals(9_007_199_254_740_993L, observation.androidLineCompleteElapsedNs)
        assertTrue(observation.summary().contains("utc_date=UNAVAILABLE"))
    }
    @Test fun southWestNegativeAltitudeAndFractionalUtc() {
        val observation = parser.parse(sentence("GNGGA,235960.125,4807.038,S,01131.000,W,4,25,0.8,-12.3,M,46.9,M,,"), 10)
        assertEquals("23:59:60.125", observation.utcTimeOfDay)
        assertEquals(BigDecimal("-48.117300000"), observation.latitude)
        assertEquals(BigDecimal("-11.516666667"), observation.longitude)
        assertEquals(BigDecimal("-12.3"), observation.altitudeMslMeters)
        assertEquals(4, observation.fixQuality)
    }
    @Test fun noFixMissingCoordinatesAndUtcAreNotZeroFilled() {
        val observation = parser.parse(sentence("GNGGA,,,,,,0,00,99.9,,M,,M,,"), 20)
        assertNull(observation.utcTimeOfDay)
        assertNull(observation.latitude)
        assertNull(observation.longitude)
        assertNull(observation.altitudeMslMeters)
        assertEquals(0, observation.fixQuality)
        assertEquals(0, observation.satellites)
    }
    @Test fun rejectsCorruptChecksumImpossibleCoordinateAndWrongUnit() {
        val valid = "GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,"
        for (bad in listOf(sentence(valid).dropLast(2) + "00", sentence(valid.replace("4807.038", "4860.000")),
            sentence(valid.replace("01131.000", "19000.000")), sentence(valid.replace("545.4,M", "545.4,F")),
            sentence(valid.replace("123519", "253519")))) {
            assertThrows(IllegalArgumentException::class.java) { parser.parse(bad, 1) }
        }
    }
}
