package com.baux.roverbridge.rtcm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.baux.roverbridge.tls.ProbeResourceCleanup

class Rtcm3MessageDecoderTest {
    @Test
    fun crc24qMatchesTheStandardCheckVector() {
        assertEquals(0xCDE703L,
            Rtcm3MessageDecoder.crc24q("123456789".toByteArray(StandardCharsets.US_ASCII)))
    }

    @Test
    fun reassemblesTcpFragmentsAndSplitsLargeMessageIntoOrderedBxr1Blocks() {
        val payload = ByteArray(600) { ((it * 31) and 0xFF).toByte() }
        payload[0] = 0
        payload[1] = 10
        val message = makeRtcmMessage(payload)
        val sentBlocks = mutableListOf<ByteArray>()
        val cancellation = RtcmCancellation()
        val fragmented = FragmentedInputStream(message, 7) { cancellation.requestStop() }
        val service = RtcmBridgeService { block ->
            sentBlocks += block.copyOf()
            RtcmBlockSendResult(block.size, 0, 0)
        }

        val metrics = service.forwardRtcmStream(fragmented, cancellation::isStopRequested) { }

        assertEquals(3, sentBlocks.size)
        assertEquals(listOf(256, 256, 94), sentBlocks.map { it.size })
        assertArrayEquals(message, sentBlocks.fold(ByteArray(0)) { all, block -> all + block })
        assertEquals(message.size.toLong(), metrics.receivedBytes)
        assertEquals(1L, metrics.validatedMessages)
        assertEquals(message.size.toLong(), metrics.acknowledgedBytes)
        assertEquals(3L, metrics.acknowledgements)
    }

    @Test
    fun rejectsBadCrcMessageAndResynchronizesAtNextValidMessage() {
        val corrupt = makeRtcmMessage(ByteArray(20) { 0x11 })
        corrupt[corrupt.lastIndex] = (corrupt.last().toInt() xor 1).toByte()
        val valid = makeRtcmMessage(byteArrayOf(0, 10, 0xD3.toByte(), 0xFF.toByte()))
        val input = corrupt + valid
        val cancellation = RtcmCancellation()
        val stream = FragmentedInputStream(input, 3) { cancellation.requestStop() }
        val sent = mutableListOf<ByteArray>()
        val service = RtcmBridgeService { block ->
            sent += block.copyOf()
            RtcmBlockSendResult(block.size, 0, 0)
        }

        val metrics = service.forwardRtcmStream(stream, cancellation::isStopRequested) { }

        assertEquals(1L, metrics.crcFailures)
        assertEquals(1L, metrics.validatedMessages)
        assertEquals(valid.size.toLong(), metrics.acknowledgedBytes)
        assertArrayEquals(valid, sent.single())
    }

    @Test
    fun stopDuringBlockedReadReturnsAndRunsResourceCleanup() {
        val cancellation = RtcmCancellation()
        val readStarted = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        var inputClosed = false
        val input = object : InputStream() {
            override fun read(): Int = -1
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                readStarted.countDown()
                releaseRead.await(2, TimeUnit.SECONDS)
                return -1
            }
            override fun close() { inputClosed = true }
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<RtcmLiveMetrics> {
                try {
                    RtcmBridgeService { block -> RtcmBlockSendResult(block.size, 0, 0) }
                        .forwardRtcmStream(input, cancellation::isStopRequested) { }
                } finally {
                    ProbeResourceCleanup.closeAll({ input.close() })
                }
            }
            assertTrue(readStarted.await(1, TimeUnit.SECONDS))
            cancellation.requestStop()
            releaseRead.countDown()
            val result = future.get(2, TimeUnit.SECONDS)
            assertEquals(0L, result.receivedBytes)
            assertTrue(inputClosed)
        } finally {
            releaseRead.countDown()
            executor.shutdownNow()
        }
    }

    private fun makeRtcmMessage(payload: ByteArray): ByteArray {
        require(payload.size <= 1023)
        val message = ByteArray(payload.size + 6)
        message[0] = 0xD3.toByte()
        message[1] = ((payload.size ushr 8) and 0x03).toByte()
        message[2] = payload.size.toByte()
        payload.copyInto(message, 3)
        val crc = Rtcm3MessageDecoder.crc24q(message, 0, message.size - 3)
        message[message.size - 3] = (crc ushr 16).toByte()
        message[message.size - 2] = (crc ushr 8).toByte()
        message[message.size - 1] = crc.toByte()
        return message
    }

    private class FragmentedInputStream(
        private val bytes: ByteArray,
        private val maxChunk: Int,
        private val onDelivered: () -> Unit
    ) : ByteArrayInputStream(bytes) {
        private var notified = false

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (pos >= this.count) {
                if (!notified) {
                    notified = true
                    onDelivered()
                }
                return -1
            }
            val count = super.read(buffer, offset, minOf(length, maxChunk))
            return count
        }
    }
}
