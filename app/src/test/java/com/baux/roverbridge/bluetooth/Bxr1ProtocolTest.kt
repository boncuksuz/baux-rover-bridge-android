package com.baux.roverbridge.bluetooth

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class Bxr1ProtocolTest {
    @Test
    fun usesIeeeReflectedCrc32AndLittleEndianHeader() {
        val check = "123456789".toByteArray(StandardCharsets.US_ASCII)
        assertEquals(0xCBF43926L, Bxr1Protocol.crc32(check))

        val payload = byteArrayOf(0x00, 0x0A, 0xFF.toByte(), 'P'.code.toByte(), 'I'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        val frame = Bxr1Protocol.encode(0x78563412L, payload)
        assertEquals(18 + payload.size, frame.bytes.size)
        assertArrayEquals(byteArrayOf('B'.code.toByte(), 'X'.code.toByte(), 'R'.code.toByte(), '1'.code.toByte()), frame.bytes.copyOfRange(0, 4))
        assertArrayEquals(byteArrayOf(0x12, 0x34, 0x56, 0x78), frame.bytes.copyOfRange(4, 8))
        assertArrayEquals(byteArrayOf(0x07, 0x00), frame.bytes.copyOfRange(8, 10))
        assertEquals(Bxr1Protocol.crc32(frame.bytes, 0, 10), readU32Le(frame.bytes, 10))
        assertArrayEquals(payload, frame.bytes.copyOfRange(14, 14 + payload.size))
        assertEquals(frame.payloadCrc32, readU32Le(frame.bytes, 14 + payload.size))
    }

    @Test
    fun supportsThe256ByteLimitAndRejectsLargerPayloads() {
        val payload = ByteArray(256) { it.toByte() }
        assertEquals(274, Bxr1Protocol.encode(7, payload).bytes.size)
        try {
            Bxr1Protocol.encode(7, ByteArray(257))
            throw AssertionError("257-byte payload should be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun parsesAckNackAndDuplicateAckWithUnsignedSequence() {
        val ack = Bxr1Protocol.parseResponse("@RTCM:ACK:4294967295:256:cbf43926")
        assertEquals(Bxr1Protocol.Response.Ack(0xFFFF_FFFFL, 256, 0xCBF43926L), ack)
        assertEquals(ack, Bxr1Protocol.parseResponse("@RTCM:ACK:4294967295:256:cbf43926"))
        val payload = byteArrayOf(0, 10, 0xFF.toByte())
        assertArrayEquals(
            Bxr1Protocol.encode(44, payload).bytes,
            Bxr1Protocol.encode(44, payload).bytes
        )
        assertEquals(Bxr1Protocol.Response.Nack(12, "CRC32"),
            Bxr1Protocol.parseResponse("@RTCM:NACK:12:CRC32"))
    }

    @Test
    fun retriesTimeoutAndOnlyRetryableCrcNacksWithinLimit() {
        assertTrue(Bxr1Protocol.retryTimeout(1))
        assertTrue(Bxr1Protocol.retryTimeout(2))
        assertFalse(Bxr1Protocol.retryTimeout(3))
        assertTrue(Bxr1Protocol.retryNack("CRC32", 1))
        assertFalse(Bxr1Protocol.retryNack("UART_TIMEOUT", 1))
        assertFalse(Bxr1Protocol.retryNack("CRC32", 3))
    }

    private fun readU32Le(bytes: ByteArray, offset: Int): Long {
        // Build the value from the encoded little-endian bytes (without relying on host byte order).
        return (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)
    }
}
