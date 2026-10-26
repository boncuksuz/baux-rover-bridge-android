package com.baux.roverbridge.tls

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.Properties
import java.util.concurrent.TimeUnit

internal data class NtripConfiguration(
    val host: String,
    val port: Int,
    val mountpoint: String,
    private val authorizationBase64: String,
    val allowIpCnFallback: Boolean
) {
    fun requestBytes(): ByteArray = (
        "GET $mountpoint HTTP/1.0\r\n" +
            "User-Agent: NTRIP ESP32 Rover\r\n" +
            "Authorization: Basic $authorizationBase64\r\n" +
            "Connection: close\r\n\r\n"
        ).toByteArray(StandardCharsets.US_ASCII)

    companion object {
        fun read(stream: InputStream): NtripConfiguration {
            val values = Properties().apply { load(stream) }
            fun required(name: String): String = values.getProperty(name)?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: throw IOException("NTRIP yapılandırmasında $name eksik.")

            val host = required("host")
            val port = required("port").toIntOrNull()
                ?.takeIf { it in 1..65535 }
                ?: throw IOException("NTRIP portu geçersiz.")
            val mountpoint = required("mountpoint")
            val authorization = required("authorizationBase64")
            val fallback = values.getProperty("allowIpCnFallback", "false").trim()
            if (!host.matches(Regex("^[A-Za-z0-9.:-]+$")) || host.startsWith("-") ||
                !mountpoint.matches(Regex("^/[^\\s]*$")) ||
                !authorization.matches(Regex("^[A-Za-z0-9+/]+={0,2}$")) ||
                authorization.length % 4 != 0 || fallback !in setOf("true", "false")
            ) {
                throw IOException("NTRIP yapılandırma alanı geçersiz.")
            }
            return NtripConfiguration(host, port, mountpoint, authorization, fallback == "true")
        }
    }
}

internal data class NtripResult(
    val safeStatus: String,
    val success: Boolean,
    val bodyBytes: Long,
    val measurementDurationMs: Long
)

internal data class NtripHeaderStatus(val safeStatus: String, val success: Boolean)

/** Reads only through CRLF-CRLF, leaving all subsequent binary body bytes untouched. */
internal object NtripHeaderReader {
    private const val MAX_HEADER_BYTES = 16 * 1024

    fun read(input: InputStream, timeoutSeconds: Long = 10): NtripHeaderStatus {
        require(timeoutSeconds > 0)
        val headerDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        val header = ByteArrayOutputStream()
        var previous = -1
        var beforePrevious = -1
        var beforeBeforePrevious = -1
        var complete = false
        while (header.size() < MAX_HEADER_BYTES && System.nanoTime() < headerDeadline) {
            val value = try { input.read() } catch (_: SocketTimeoutException) { continue }
            if (value < 0) throw IOException("NTRIP başlığı tamamlanmadan bağlantı kapandı.")
            header.write(value)
            if (beforeBeforePrevious == '\r'.code && beforePrevious == '\n'.code &&
                previous == '\r'.code && value == '\n'.code
            ) {
                complete = true
                break
            }
            beforeBeforePrevious = beforePrevious
            beforePrevious = previous
            previous = value
        }
        if (!complete) throw IOException("NTRIP başlığı eksik, çok uzun veya zaman aşımına uğradı.")

        val statusLine = String(header.toByteArray(), StandardCharsets.ISO_8859_1)
            .substringBefore("\r\n")
        val code = Regex("^(?:ICY|HTTP/1\\.[01]) ([0-9]{3})(?:[ \\t].*)?$")
            .matchEntire(statusLine)?.groupValues?.get(1)
        val safeStatus = when {
            statusLine.startsWith("ICY ") && code != null -> "ICY $code"
            statusLine.startsWith("HTTP/") && code != null -> "HTTP $code"
            else -> "tanınmayan yanıt"
        }
        return NtripHeaderStatus(safeStatus, code == "200")
    }
}

/** Cleanup errors are secondary; each resource still gets a close attempt. */
internal object ProbeResourceCleanup {
    fun closeAll(vararg closeActions: () -> Unit) {
        closeActions.forEach { close ->
            try {
                close()
            } catch (_: Exception) {
                // A peer may already have closed the TCP socket during TLS shutdown.
            }
        }
    }
}

internal object NtripResponseReader {
    private const val BUFFER_SIZE = 4096

    fun read(input: InputStream, windowSeconds: Long = 10): NtripResult {
        require(windowSeconds > 0)
        val header = NtripHeaderReader.read(input)
        if (!header.success) return NtripResult(header.safeStatus, false, 0, 0)

        val buffer = ByteArray(BUFFER_SIZE)
        var count = 0L
        val measurementStart = System.nanoTime()
        val bodyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(windowSeconds)
        while (System.nanoTime() < bodyDeadline) {
            val read = try { input.read(buffer) } catch (_: SocketTimeoutException) { continue }
            if (read < 0) throw IOException("NTRIP akışı 10 saniye dolmadan kapandı.")
            count = Math.addExact(count, read.toLong())
        }
        val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - measurementStart)
        return NtripResult(header.safeStatus, true, count, durationMs)
    }
}
