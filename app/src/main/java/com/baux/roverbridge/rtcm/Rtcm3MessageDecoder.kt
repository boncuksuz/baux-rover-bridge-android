package com.baux.roverbridge.rtcm

/** Incremental RTCM3 framer. It stores at most one maximum-size message. */
internal class Rtcm3MessageDecoder {
    data class Counters(
        val inputBytes: Long,
        val validMessages: Long,
        val crcFailures: Long,
        val malformedHeaders: Long,
        val discardedBytes: Long
    )

    private val frameBuffer = ByteArray(MAX_FRAME_BYTES)
    private var buffered = 0
    private var inputBytes = 0L
    private var validMessages = 0L
    private var crcFailures = 0L
    private var malformedHeaders = 0L
    private var discardedBytes = 0L

    fun accept(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset,
               onMessage: (ByteArray) -> Unit) {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
        for (index in offset until offset + length) {
            inputBytes++
            check(buffered < frameBuffer.size) { "RTCM decoder buffer limit exceeded." }
            frameBuffer[buffered++] = bytes[index]
            drain(onMessage)
        }
    }

    fun counters() = Counters(inputBytes, validMessages, crcFailures, malformedHeaders, discardedBytes)

    fun pendingBytes(): Int = buffered

    private fun drain(onMessage: (ByteArray) -> Unit) {
        while (buffered > 0) {
            if ((frameBuffer[0].toInt() and 0xFF) != PREAMBLE) {
                discardPrefix(1)
                continue
            }
            if (buffered < 2) return
            if ((frameBuffer[1].toInt() and 0xFC) != 0) {
                malformedHeaders++
                discardPrefix(1)
                continue
            }
            if (buffered < HEADER_BYTES) return

            val payloadLength = ((frameBuffer[1].toInt() and 0x03) shl 8) or
                (frameBuffer[2].toInt() and 0xFF)
            val totalLength = HEADER_BYTES + payloadLength + CRC_BYTES
            if (buffered < totalLength) return

            val crcOffset = totalLength - CRC_BYTES
            val expectedCrc = ((frameBuffer[crcOffset].toLong() and 0xFF) shl 16) or
                ((frameBuffer[crcOffset + 1].toLong() and 0xFF) shl 8) or
                (frameBuffer[crcOffset + 2].toLong() and 0xFF)
            val actualCrc = crc24q(frameBuffer, 0, crcOffset)
            if (actualCrc == expectedCrc) {
                val message = frameBuffer.copyOf(totalLength)
                validMessages++
                removePrefix(totalLength, false)
                onMessage(message)
            } else {
                crcFailures++
                discardPrefix(1)
            }
        }
    }

    private fun discardPrefix(count: Int) {
        removePrefix(count, true)
    }

    private fun removePrefix(count: Int, discarded: Boolean) {
        if (count <= 0) return
        val remaining = buffered - count
        if (remaining > 0) System.arraycopy(frameBuffer, count, frameBuffer, 0, remaining)
        buffered = remaining
        if (discarded) discardedBytes += count
    }

    companion object {
        private const val PREAMBLE = 0xD3
        private const val HEADER_BYTES = 3
        private const val CRC_BYTES = 3
        private const val MAX_PAYLOAD_BYTES = 1023
        private const val MAX_FRAME_BYTES = HEADER_BYTES + MAX_PAYLOAD_BYTES + CRC_BYTES
        private const val CRC24Q_POLYNOMIAL = 0x1864CFB
        private const val CRC24Q_MASK = 0xFFFFFF

        fun crc24q(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Long {
            require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
            var crc = 0
            for (index in offset until offset + length) {
                crc = crc xor ((bytes[index].toInt() and 0xFF) shl 16)
                repeat(8) {
                    crc = if ((crc and 0x800000) != 0) {
                        (crc shl 1) xor CRC24Q_POLYNOMIAL
                    } else {
                        crc shl 1
                    }
                    crc = crc and CRC24Q_MASK
                }
            }
            return crc.toLong()
        }
    }
}
