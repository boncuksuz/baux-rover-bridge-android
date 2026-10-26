package com.baux.roverbridge.bluetooth

import java.io.IOException
import java.util.zip.CRC32

internal object Bxr1Protocol {
    const val MAX_PAYLOAD_BYTES = 256
    const val HEADER_BYTES = 14
    const val FRAME_OVERHEAD_BYTES = 18
    const val UINT32_MAX = 0xFFFF_FFFFL
    const val MAX_ATTEMPTS = 3

    data class EncodedFrame(val bytes: ByteArray, val payloadCrc32: Long)

    sealed class Response {
        data class Ack(val sequence: Long, val length: Int, val payloadCrc32: Long) : Response()
        data class Nack(val sequence: Long, val reason: String) : Response()
    }

    fun crc32(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): Long {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
        return CRC32().run {
            update(bytes, offset, length)
            value
        }
    }

    fun encode(sequence: Long, payload: ByteArray): EncodedFrame {
        require(sequence in 0..UINT32_MAX) { "BXR1 sequence must fit uint32." }
        require(payload.size in 1..MAX_PAYLOAD_BYTES) { "BXR1 payload must be 1..256 bytes." }

        val frame = ByteArray(FRAME_OVERHEAD_BYTES + payload.size)
        frame[0] = 'B'.code.toByte()
        frame[1] = 'X'.code.toByte()
        frame[2] = 'R'.code.toByte()
        frame[3] = '1'.code.toByte()
        putU32Le(frame, 4, sequence)
        putU16Le(frame, 8, payload.size)
        putU32Le(frame, 10, crc32(frame, 0, 10))
        payload.copyInto(frame, HEADER_BYTES)
        val payloadCrc = crc32(payload)
        putU32Le(frame, HEADER_BYTES + payload.size, payloadCrc)
        return EncodedFrame(frame, payloadCrc)
    }

    @Throws(IOException::class)
    fun parseResponse(line: String): Response {
        val ack = ACK.matchEntire(line)
        if (ack != null) {
            val sequence = parseU32(ack.groupValues[1])
            val length = ack.groupValues[2].toIntOrNull()
                ?: throw IOException("Invalid BXR1 ACK length.")
            val crc = ack.groupValues[3].toLongOrNull(16)
                ?: throw IOException("Invalid BXR1 ACK CRC32.")
            if (length !in 1..MAX_PAYLOAD_BYTES) throw IOException("Invalid BXR1 ACK length.")
            return Response.Ack(sequence, length, crc)
        }

        val nack = NACK.matchEntire(line)
        if (nack != null) return Response.Nack(parseU32(nack.groupValues[1]), nack.groupValues[2])
        throw IOException("Unrecognized BXR1 response.")
    }

    fun retryTimeout(attempt: Int): Boolean = attempt < MAX_ATTEMPTS

    fun retryNack(reason: String, attempt: Int): Boolean =
        reason == "CRC32" && attempt < MAX_ATTEMPTS

    private fun parseU32(text: String): Long = text.toLongOrNull()
        ?.takeIf { it in 0..UINT32_MAX }
        ?: throw IOException("Invalid BXR1 sequence.")

    private fun putU16Le(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
    }

    private fun putU32Le(target: ByteArray, offset: Int, value: Long) {
        repeat(4) { index -> target[offset + index] = (value ushr (8 * index)).toByte() }
    }

    private val ACK = Regex("^@RTCM:ACK:(\\d+):(\\d+):([0-9a-fA-F]{8})$")
    private val NACK = Regex("^@RTCM:NACK:(\\d+):([A-Z0-9_]+)$")
}
