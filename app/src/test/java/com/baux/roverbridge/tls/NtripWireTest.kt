package com.baux.roverbridge.tls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets

class NtripWireTest {
    @Test
    fun readsLocalConfigurationAndBuildsNtripRequest() {
        val input = ("host=127.0.0.1\nport=443\nmountpoint=/BASE\n" +
            "authorizationBase64=dGVzdDp0ZXN0\nallowIpCnFallback=true\n")
            .byteInputStream(StandardCharsets.US_ASCII)
        val config = NtripConfiguration.read(input)
        assertEquals("127.0.0.1", config.host)
        assertEquals(443, config.port)
        assertTrue(config.allowIpCnFallback)
        val request = String(config.requestBytes(), StandardCharsets.US_ASCII)
        assertTrue(request.startsWith("GET /BASE HTTP/1.0\r\n"))
        assertTrue(request.contains("Authorization: Basic dGVzdDp0ZXN0\r\n"))
        assertTrue(request.endsWith("\r\n\r\n"))
    }

    @Test(expected = java.io.IOException::class)
    fun rejectsHeaderInjectionInMountpoint() {
        NtripConfiguration.read(
            ("host=127.0.0.1\nport=443\nmountpoint=/BASE\\nInjected\n" +
                "authorizationBase64=dGVzdDp0ZXN0\n").byteInputStream()
        )
    }

    @Test
    fun returnsSafeFailureStatusWithoutCountingErrorBody() {
        val response = NtripResponseReader.read(
            ByteArrayInputStream("HTTP/1.1 401 Unauthorized\r\n\r\nprivate".toByteArray()), 1
        )
        assertEquals("HTTP 401", response.safeStatus)
        assertFalse(response.success)
        assertEquals(0L, response.bodyBytes)
    }

    @Test
    fun countsOnlyBodyBytesInReadWindow() {
        val header = "ICY 200 OK\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
        val body = byteArrayOf(0xD3.toByte(), 0, 2, 1, 2, 3)
        val source = object : InputStream() {
            var headerPosition = 0
            var sentBody = false
            override fun read(): Int = if (headerPosition < header.size)
                header[headerPosition++].toInt() and 0xff else -1

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (!sentBody) {
                    sentBody = true
                    System.arraycopy(body, 0, buffer, offset, body.size)
                    return body.size
                }
                Thread.sleep(25)
                throw SocketTimeoutException()
            }
        }
        val response = NtripResponseReader.read(source, 1)
        assertEquals("ICY 200", response.safeStatus)
        assertTrue(response.success)
        assertEquals(body.size.toLong(), response.bodyBytes)
        assertTrue(response.measurementDurationMs >= 900)
    }

    @Test
    fun closedSocketDuringCleanupDoesNotReplaceCompletedMeasurement() {
        val measuredResult = "TLS 1.2 mTLS: BAŞARILI\nNTRIP: ICY 200\nRTCM gövdesi (10000 ms): 6 bayt"
        var socketCloseAttempted = false
        val result = try {
            measuredResult
        } finally {
            ProbeResourceCleanup.closeAll(
                { throw SocketException("Socket closed") },
                { socketCloseAttempted = true }
            )
        }
        assertEquals(measuredResult, result)
        assertTrue(socketCloseAttempted)
    }
}
