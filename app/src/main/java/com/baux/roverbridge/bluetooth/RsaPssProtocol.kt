package com.baux.roverbridge.bluetooth

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.PublicKey
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec

/** ESP32 RemoteSigner test çerçevesi ve yalnızca açık anahtarla doğrulama. */
internal object RsaPssProtocol {
    private const val TEST_MESSAGE = "BAUX-RSA-PSS test"
    private const val REQUEST_PREFIX = "@RSIG:PSS256:"
    private const val SIGNATURE_PREFIX = "@RSIG:SIG:"
    private const val ERROR_PREFIX = "@RSIG:ERROR:"
    private const val SIGNATURE_BYTES = 256
    private val HEX = "0123456789abcdef".toCharArray()
    private val signatureHex = Regex("^[0-9A-Fa-f]{512}$")
    private val errorCode = Regex("^[A-Z_]+$")
    private val metricsPattern = Regex(
        "^RSIG_METRICS free_before=(\\d+) min_before=(\\d+) largest_before=(\\d+) " +
            "free_after=(\\d+) min_after=(\\d+) largest_after=(\\d+) elapsed_us=(\\d+)$"
    )

    sealed class Response {
        data class Metrics(
            val freeBefore: Long,
            val minBefore: Long,
            val largestBefore: Long,
            val freeAfter: Long,
            val minAfter: Long,
            val largestAfter: Long,
            val elapsedUs: Long
        ) : Response()

        data class SignatureBytes(val bytes: ByteArray) : Response()
        data class DeviceError(val code: String) : Response()
        object Diagnostic : Response()
    }

    fun requestBytes(): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(TEST_MESSAGE.toByteArray(StandardCharsets.UTF_8))
        check(digest.size == 32)
        val request = StringBuilder(REQUEST_PREFIX.length + 65).append(REQUEST_PREFIX)
        for (byte in digest) {
            val value = byte.toInt() and 0xff
            request.append(HEX[value ushr 4]).append(HEX[value and 0x0f])
        }
        return request.append('\n').toString().toByteArray(StandardCharsets.US_ASCII)
    }

    fun parseResponse(line: String): Response {
        if (line.startsWith(SIGNATURE_PREFIX)) {
            val hex = line.substring(SIGNATURE_PREFIX.length)
            require(signatureHex.matches(hex)) { "Eksik veya geçersiz RSA imzası." }
            val bytes = ByteArray(SIGNATURE_BYTES)
            for (index in bytes.indices) {
                val high = Character.digit(hex[index * 2], 16)
                val low = Character.digit(hex[index * 2 + 1], 16)
                bytes[index] = ((high shl 4) or low).toByte()
            }
            return Response.SignatureBytes(bytes)
        }
        if (line.startsWith(ERROR_PREFIX)) {
            val code = line.substring(ERROR_PREFIX.length)
            require(errorCode.matches(code)) { "Geçersiz ESP32 hata yanıtı." }
            return Response.DeviceError(code)
        }
        if (line.startsWith("@RSIG:")) {
            throw IllegalArgumentException("Bilinmeyen RSA yanıtı.")
        }
        val match = metricsPattern.matchEntire(line) ?: return Response.Diagnostic
        val numbers = match.groupValues.drop(1).map { it.toLongOrNull() }
        require(numbers.all { it != null }) { "Geçersiz ESP32 metrikleri." }
        return Response.Metrics(
            numbers[0]!!, numbers[1]!!, numbers[2]!!,
            numbers[3]!!, numbers[4]!!, numbers[5]!!, numbers[6]!!
        )
    }

    fun verify(publicKey: PublicKey, signatureBytes: ByteArray): Boolean {
        if (signatureBytes.size != SIGNATURE_BYTES) return false
        val verifier = try {
            Signature.getInstance("SHA256withRSA/PSS")
        } catch (_: NoSuchAlgorithmException) {
            // Masaüstü JDK'ları aynı şemayı RSASSA-PSS adıyla sunabilir.
            Signature.getInstance("RSASSA-PSS")
        }
        verifier.initVerify(publicKey)
        verifier.setParameter(
            PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1)
        )
        verifier.update(TEST_MESSAGE.toByteArray(StandardCharsets.UTF_8))
        return verifier.verify(signatureBytes)
    }
}
