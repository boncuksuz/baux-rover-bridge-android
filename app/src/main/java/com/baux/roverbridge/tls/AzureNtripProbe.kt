package com.baux.roverbridge.tls

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.baux.roverbridge.R
import com.baux.roverbridge.bluetooth.BluetoothSppService
import org.bouncycastle.tls.AlertDescription
import org.bouncycastle.tls.TlsClientProtocol
import org.bouncycastle.tls.TlsFatalAlertReceived
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.Locale

/** Yerel dosyadan tek kullanımlık ayarla TLS mTLS/NTRIP ölçümü; payload aktarmaz. */
class AzureNtripProbe(
    private val context: Context,
    private val bluetooth: BluetoothSppService,
    private val onUpdate: (String, Boolean) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val closer = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private var running = false
    private var stopped = false
    @Volatile private var cancelled = false
    @Volatile private var activeSocket: Socket? = null

    fun run(configurationUri: Uri) {
        synchronized(lock) {
            if (stopped || running) return
            running = true
            cancelled = false
        }
        post("Yerel NTRIP ayarı okunuyor…", false)
        try {
            worker.execute {
                val result = try {
                    runProbe(configurationUri)
                } catch (error: Exception) {
                    "Azure testi BAŞARISIZ (uygulama içi hata): ${error.javaClass.simpleName}"
                } finally {
                    synchronized(lock) { running = false }
                }
                post(result, true)
            }
        } catch (_: RejectedExecutionException) {
            synchronized(lock) { running = false }
            post("Azure testi başlatılamadı; ekran kapatılmış olabilir.", true)
        }
    }

    private fun runProbe(uri: Uri): String {
        var stage = "yapılandırma"
        var handshakeSummary: String? = null
        var ntripSummary: String? = null
        var signer: BluetoothSppService.TlsSignerSession? = null
        var protocol: TlsClientProtocol? = null
        try {
            val config = context.contentResolver.openInputStream(uri)?.use(NtripConfiguration::read)
                ?: throw IOException("NTRIP yapılandırma dosyası açılamadı.")
            val factory = CertificateFactory.getInstance("X.509")
            val client = context.resources.openRawResource(R.raw.rover_client_certificate).use {
                factory.generateCertificate(it) as X509Certificate
            }
            val rootCa = context.resources.openRawResource(R.raw.azure_root_ca).use {
                factory.generateCertificate(it) as X509Certificate
            }
            val validator = ServerCertificateValidator(rootCa, config.host, config.allowIpCnFallback)
            if (cancelled) throw IOException("Test iptal edildi.")
            val activeSigner = bluetooth.beginTlsSignerSession()
            signer = activeSigner

            stage = "Azure TLS bağlantısı"
            post("Azure TLS 1.2 mTLS bağlantısı kuruluyor…", false)
            val socket = Socket()
            activeSocket = socket
            socket.connect(InetSocketAddress(config.host, config.port), 10_000)
            socket.soTimeout = 10_000
            if (cancelled) throw IOException("Test iptal edildi.")

            stage = "TLS el sıkışması"
            val handshakeStart = SystemClock.elapsedRealtimeNanos()
            val tlsProtocol = RemoteKeyTlsClient().connect(
                socket, client, RemoteKeyTlsClient.RemoteSigner { hash -> activeSigner.sign(hash) },
                validator
            )
            protocol = tlsProtocol
            val handshakeMs = TimeUnit.NANOSECONDS.toMillis(
                SystemClock.elapsedRealtimeNanos() - handshakeStart
            )
            val signatureUs = activeSigner.lastSignatureElapsedUs
            if (signatureUs < 0 || validator.identityMethod() == "not verified") {
                throw IOException("TLS el sıkışmasında ESP32 imzası veya sunucu doğrulaması eksik.")
            }
            handshakeSummary = "TLS 1.2 mTLS: BAŞARILI (" + validator.identityMethod() + ")\n" +
                "Handshake: " + handshakeMs + " ms; ESP32 imza: " +
                String.format(Locale.US, "%.1f", signatureUs / 1000.0) + " ms"
            post(handshakeSummary + "\nNTRIP ölçülüyor…", false)

            stage = "NTRIP yanıtı ve 10 saniyelik RTCM ölçümü"
            socket.soTimeout = 1_000
            tlsProtocol.outputStream.write(config.requestBytes())
            tlsProtocol.outputStream.flush()
            val response = NtripResponseReader.read(tlsProtocol.inputStream, 10)
            ntripSummary = "NTRIP: " + response.safeStatus + "\n" +
                "RTCM gövdesi (${response.measurementDurationMs} ms): " +
                response.bodyBytes + " bayt"
            if (!response.success) throw IOException("NTRIP yanıtı: ${response.safeStatus}")
            if (response.bodyBytes <= 0) throw IOException("NTRIP gövdesinde RTCM baytı görülmedi.")

            return handshakeSummary + "\n" + ntripSummary
        } catch (error: Exception) {
            val detail = when (error) {
                is TlsFatalAlertReceived -> "TLS alert: " +
                    AlertDescription.getName(error.alertDescription)
                is IOException -> error.message?.takeIf {
                    it.startsWith("NTRIP ") || it.startsWith("TLS el sıkışmasında") ||
                        it == "Test iptal edildi." || it.startsWith("Bluetooth bağlantısı TLS")
                } ?: error.javaClass.simpleName
                else -> error.javaClass.simpleName
            }
            val observed = listOfNotNull(handshakeSummary, ntripSummary).joinToString("\n")
            return "Azure testi BAŞARISIZ (" + stage + "): " + detail +
                (if (observed.isEmpty()) "" else "\n" + observed)
        } finally {
            ProbeResourceCleanup.closeAll(
                { protocol?.close() },
                { activeSocket?.close() },
                { signer?.close() }
            )
            activeSocket = null
        }
    }

    fun cancel() {
        cancelled = true
        val socket = activeSocket
        if (socket != null) {
            try {
                closer.execute { runCatching { socket.close() } }
            } catch (_: RejectedExecutionException) {
                runCatching { socket.close() }
            }
        }
    }

    fun shutdown() {
        synchronized(lock) { stopped = true }
        cancel()
        worker.shutdownNow()
        closer.shutdown()
    }

    private fun post(message: String, complete: Boolean) {
        try {
            mainHandler.post {
                try {
                    if (synchronized(lock) { !stopped }) onUpdate(message, complete)
                } catch (_: Exception) {
                    // Activity view lifecycle may end while a worker result is queued.
                }
            }
        } catch (_: Exception) {
            // Do not let a rejected UI update terminate the probe worker.
        }
    }
}
