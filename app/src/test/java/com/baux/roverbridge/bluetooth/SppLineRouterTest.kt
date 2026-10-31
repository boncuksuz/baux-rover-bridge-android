package com.baux.roverbridge.bluetooth

import com.baux.roverbridge.gga.GgaForwardService
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SppLineRouterTest {
    private val gga = "GGA|\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47"
    @Test fun fragmentedIdleLineAndInterleavedProtocolLinesRemainIntact() {
        val observations = mutableListOf<GgaForwardService.Observation>()
        val router = SppLineRouter { line, t -> observations.add(GgaForwardService().parse(line, t)) }
        val controls = mutableListOf<String>()
        val expected = listOf("@RTCM:ACK:8:10:1234abcd", "@RTCM:NACK:9:CRC32", "@UV:ACK:3:COM2", "RSIG_METRICS test", "@RSIG:ERROR:BUSY", "PONG")
        // Same state survives an idle read ending in the middle of GGA and a later ACK read.
        val wire = "${gga.take(12)}" + gga.drop(12) + "\r\n" + expected.joinToString("\n$gga\n") + "\n"
        wire.forEachIndexed { index, c -> router.feed(c.code, 1_000_000L + index)?.let(controls::add) }
        assertEquals(expected, controls)
        assertEquals(6, observations.size)
        assertTrue(observations.all { it.utcTimeOfDay == "12:35:19" })
        val ack = Bxr1Protocol.parseResponse(controls[0]) as Bxr1Protocol.Response.Ack
        assertEquals(8L, ack.sequence)
        assertEquals(10, ack.length)
    }
    @Test fun malformedGgaIsRejectedWithoutConsumingFollowingAck() {
        var rejected = 0
        val router = SppLineRouter { line, t ->
            try { GgaForwardService().parse(line, t) } catch (_: IllegalArgumentException) { rejected++ }
        }
        val controls = mutableListOf<String>()
        (gga.dropLast(2) + "00\n@RTCM:ACK:7:1:00000000\n").forEach { router.feed(it.code, 100)?.let(controls::add) }
        assertEquals(1, rejected)
        assertEquals(listOf("@RTCM:ACK:7:1:00000000"), controls)
    }
    @Test fun queuedControlAndResetBoundariesAreExplicit() {
        val router = SppLineRouter { _, _ -> }
        router.rememberControl("PONG")
        assertEquals("PONG", router.pollControl())
        "GGA|partial".forEach { router.feed(it.code, 1) }
        router.reset()
        val output = mutableListOf<String>()
        "PONG\n".forEach { router.feed(it.code, 2)?.let(output::add) }
        assertEquals(listOf("PONG"), output)
        assertThrows(IOException::class.java) { repeat(641) { router.feed('x'.code, 1) } }
    }
}
