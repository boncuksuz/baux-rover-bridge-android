package com.baux.cameratimestamptest

import org.junit.Assert.*
import org.junit.Test

class TimingTest {
    @Test fun largeNanosecondsRetainOneNanosecondDifference() {
        val sensor = 9_007_199_254_740_993L
        assertEquals(1L, Timing.delta(sensor, sensor - 1, true))
        assertEquals("9007199254740993", Timing.exact(sensor))
    }
    @Test fun unknownSourceAndMissingValuesAreNotFabricated() {
        assertNull(Timing.delta(800L, 700L, false))
        assertNull(Timing.delta(null, 700L, true))
        assertNull(Timing.delta(0, 700L, true))
        assertEquals(-10L, Timing.delta(690, 700, true))
    }
    @Test fun neighboursUseSensorOrderAndRespectBindingAndCapacity() {
        val history = AnalysisHistory(4)
        fun frame(index: Long, time: Long, binding: String = "A") = AnalysisFrame(index, binding, "0", time, index, 640, 480, 0, 35)
        history.add(frame(1, 90))
        history.add(frame(2, 101, "B"))
        history.add(frame(3, 110))
        history.add(frame(4, 100))
        history.add(frame(5, 99)) // Retained order is not assumed to be timestamp order.
        val result = history.neighbours(100, "A")
        assertEquals(99L, result.before?.timestampNs)
        assertEquals(100L, result.equal?.timestampNs)
        assertEquals(110L, result.after?.timestampNs)
        assertNull(history.neighbours(98, "A").before) // Old frame was evicted.
    }
}
