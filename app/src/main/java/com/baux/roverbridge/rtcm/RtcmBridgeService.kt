package com.baux.roverbridge.rtcm

import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

data class RtcmLiveMetrics(
    val receivedBytes: Long = 0,
    val validatedMessages: Long = 0,
    val crcFailures: Long = 0,
    val malformedHeaders: Long = 0,
    val acknowledgedBytes: Long = 0,
    val acknowledgements: Long = 0,
    val negativeAcknowledgements: Long = 0,
    val retries: Long = 0,
    val rateBytesPerSecond: Long = 0
)

internal data class RtcmBlockSendResult(val payloadBytes: Int, val nacks: Int, val retries: Int)

internal class RtcmCancellation {
    private val stopRequested = AtomicBoolean(false)
    fun requestStop() { stopRequested.set(true) }
    fun isStopRequested(): Boolean = stopRequested.get()
}

/** Validates complete RTCM3 messages, then forwards acknowledged BXR1 blocks with backpressure. */
class RtcmBridgeService internal constructor(
    private val sendBlock: (ByteArray) -> RtcmBlockSendResult
) {
    @Throws(IOException::class)
    fun forwardRtcmStream(
        rtcmInput: InputStream,
        isCancelled: () -> Boolean,
        onProgress: (RtcmLiveMetrics) -> Unit
    ): RtcmLiveMetrics {
        val decoder = Rtcm3MessageDecoder()
        val inputBuffer = ByteArray(4096)
        val startedAt = System.nanoTime()
        var acknowledgedBytes = 0L
        var acknowledgements = 0L
        var negativeAcknowledgements = 0L
        var retries = 0L

        fun snapshot(): RtcmLiveMetrics {
            val decoded = decoder.counters()
            val elapsedNanos = (System.nanoTime() - startedAt).coerceAtLeast(1)
            return RtcmLiveMetrics(
                receivedBytes = decoded.inputBytes,
                validatedMessages = decoded.validMessages,
                crcFailures = decoded.crcFailures,
                malformedHeaders = decoded.malformedHeaders,
                acknowledgedBytes = acknowledgedBytes,
                acknowledgements = acknowledgements,
                negativeAcknowledgements = negativeAcknowledgements,
                retries = retries,
                rateBytesPerSecond =
                    (acknowledgedBytes.toDouble() * 1_000_000_000.0 / elapsedNanos).toLong()
            )
        }

        while (!isCancelled()) {
            val count = try {
                rtcmInput.read(inputBuffer)
            } catch (_: SocketTimeoutException) {
                if (isCancelled()) break else continue
            }
            if (count < 0) {
                if (isCancelled()) break
                throw IOException("NTRIP RTCM akışı beklenmedik şekilde kapandı.")
            }
            if (count == 0) continue

            try {
                decoder.accept(inputBuffer, 0, count) { message ->
                    var offset = 0
                    while (offset < message.size) {
                        if (isCancelled()) throw IOException("RTCM aktarımı durduruldu.")
                        val end = minOf(offset + BXR1_BLOCK_BYTES, message.size)
                        val payload = message.copyOfRange(offset, end)
                        val result = sendBlock(payload)
                        acknowledgedBytes += result.payloadBytes
                        acknowledgements++
                        negativeAcknowledgements += result.nacks
                        retries += result.retries
                        offset = end
                        onProgress(snapshot())
                    }
                }
            } catch (error: IOException) {
                if (isCancelled()) break
                throw error
            }
            onProgress(snapshot())
        }
        return snapshot()
    }

    companion object {
        const val BXR1_BLOCK_BYTES = 256
    }
}
