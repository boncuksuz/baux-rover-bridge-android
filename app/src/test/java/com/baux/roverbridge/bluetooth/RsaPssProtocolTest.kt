package com.baux.roverbridge.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec

class RsaPssProtocolTest {
    @Test
    fun requestHasSha256DigestAndLineEnding() {
        val message = "BAUX-RSA-PSS test".toByteArray(StandardCharsets.UTF_8)
        val expected = MessageDigest.getInstance("SHA-256").digest(message)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val request = String(RsaPssProtocol.requestBytes(), StandardCharsets.US_ASCII)
        assertEquals("@RSIG:PSS256:$expected\n", request)
        assertEquals(64, request.substringAfterLast(':').trimEnd('\n').length)
    }

    @Test
    fun separatesMetricsErrorsDiagnosticsAndCompleteSignature() {
        val metrics = RsaPssProtocol.parseResponse(
            "RSIG_METRICS free_before=100 min_before=90 largest_before=80 " +
                "free_after=70 min_after=60 largest_after=50 elapsed_us=243828"
        ) as RsaPssProtocol.Response.Metrics
        assertEquals(243828L, metrics.elapsedUs)
        assertEquals(100L, metrics.freeBefore)

        val signature = RsaPssProtocol.parseResponse("@RSIG:SIG:" + "ab".repeat(256))
            as RsaPssProtocol.Response.SignatureBytes
        assertEquals(256, signature.bytes.size)
        assertEquals(0xab.toByte(), signature.bytes[0])

        val error = RsaPssProtocol.parseResponse("@RSIG:ERROR:SIGN")
            as RsaPssProtocol.Response.DeviceError
        assertEquals("SIGN", error.code)
        assertTrue(RsaPssProtocol.parseResponse("PPS | status=OK") is RsaPssProtocol.Response.Diagnostic)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTruncatedSignature() {
        RsaPssProtocol.parseResponse("@RSIG:SIG:" + "ab".repeat(255))
    }

    @Test
    fun verifiesPssSha256WithThirtyTwoByteSalt() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val signer = Signature.getInstance("RSASSA-PSS")
        signer.initSign(pair.private)
        signer.setParameter(PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1))
        signer.update("BAUX-RSA-PSS test".toByteArray(StandardCharsets.UTF_8))
        val signature = signer.sign()

        assertTrue(RsaPssProtocol.verify(pair.public, signature))
        signature[0] = (signature[0].toInt() xor 1).toByte()
        assertFalse(RsaPssProtocol.verify(pair.public, signature))
    }
}
