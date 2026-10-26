package com.baux.roverbridge.tls

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.baux.roverbridge.R
import com.baux.roverbridge.bluetooth.BluetoothSppService
import com.baux.roverbridge.rtcm.RtcmBlockSendResult
import com.baux.roverbridge.rtcm.RtcmBridgeService
import com.baux.roverbridge.rtcm.RtcmCancellation
import com.baux.roverbridge.rtcm.RtcmLiveMetrics
import org.bouncycastle.tls.AlertDescription
import org.bouncycastle.tls.TlsClientProtocol
import org.bouncycastle.tls.TlsFatalAlertReceived
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/** Azure mTLS/NTRIP test plus a separately selected, bounded live RTCM forwarding mode. */
class AzureNtripProbe(
    private val context: Context,
    private val bluetooth: BluetoothSppService,
    private val onUpdate: (String, Boolean) -> Unit,
    private val onLiveUpdate: (LiveUpdate) -> Unit
) {
    data class LiveUpdate(
        val tlsStatus: String,
        val ntripStatus: String,
        val metrics: RtcmLiveMetrics,
        val detail: String
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val closer = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private var running = false
    private var stopped = false
    @Volatile private var cancellation = RtcmCancellation()
    private val cancelled: Boolean get() = cancellation.isStopRequested()
    @Volatile private var activeSocket: Socket? = null
    @Volatile private var activeSigner: BluetoothSppService.TlsSignerSession? = null
    @Volatile private var activeTransfer: BluetoothSppService.RtcmTransferSession? = null

    fun run(configurationUri: Uri) = start(configurationUri, false)

    fun startLiveStream(configurationUri: Uri) = start(configurationUri, true)

    private fun start(configurationUri: Uri, stream: Boolean) {
        synchronized(lock) {
            if (stopped || running) return
            running = true
            cancellation = RtcmCancellation()
        }
        if (stream) {
            post("Canlı RTCM mTLS bağlantısı başlatılıyor…", false)
            publishLive("Bağlanıyor", "Bekliyor", RtcmLiveMetrics(), "Yapılandırma okunuyor")
        } else {
            post("Yerel NTRIP ayarı okunuyor…", false)
        }
        try {
            worker.execute {
                val result = try {
                    runProbe(configurationUri, stream)
                } catch (error: Exception) {
                    if (cancelled) "RTCM aktarımı durduruldu; kaynaklar kapatıldı."
                    else "Azure testi BAŞARISIZ (uygulama içi hata): ${error.javaClass.simpleName}"
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

    @Volatile private var lastMetrics = RtcmLiveMetrics()

    private fun runProbe(uri: Uri, stream: Boolean): String {
        var stage = "yapılandırma"
        var handshakeSummary: String? = null
        var ntripSummary: String? = null
        var signer: BluetoothSppService.TlsSignerSession? = null
        var transfer: BluetoothSppService.RtcmTransferSession? = null
        var protocol: TlsClientProtocol? = null
        var tlsStatus = "Bağlanıyor"
        var ntripStatus = "Bekliyor"
        lastMetrics = RtcmLiveMetrics()
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
            val activeSignerSession = bluetooth.beginTlsSignerSession()
            signer = activeSignerSession
            activeSigner = activeSignerSession

            stage = "Azure TLS bağlantısı"
            tlsStatus = "Bağlanıyor"
            if (stream) publishLive(tlsStatus, ntripStatus, lastMetrics, "Azure TLS bağlantısı kuruluyor")
            else post("Azure TLS 1.2 mTLS bağlantısı kuruluyor…", false)
            val socket = Socket()
            activeSocket = socket
            socket.connect(InetSocketAddress(config.host, config.port), 10_000)
            socket.soTimeout = 10_000
            if (cancelled) throw IOException("Test iptal edildi.")

            stage = "TLS el sıkışması"
            val handshakeStart = SystemClock.elapsedRealtimeNanos()
            val tlsProtocol = RemoteKeyTlsClient().connect(
                socket, client, RemoteKeyTlsClient.RemoteSigner { hash -> activeSignerSession.sign(hash) },
                validator
            )
            protocol = tlsProtocol
            val handshakeMs = TimeUnit.NANOSECONDS.toMillis(
                SystemClock.elapsedRealtimeNanos() - handshakeStart
            )
            val signatureUs = activeSignerSession.lastSignatureElapsedUs
            if (signatureUs < 0 || validator.identityMethod() == "not verified") {
                throw IOException("TLS el sıkışmasında ESP32 imzası veya sunucu doğrulaması eksik.")
            }
            handshakeSummary = "TLS 1.2 mTLS: BAŞARILI (" + validator.identityMethod() + ")\n" +
                "Handshake: " + handshakeMs + " ms; ESP32 imza: " +
                String.format(Locale.US, "%.1f", signatureUs / 1000.0) + " ms"
            tlsStatus = "TLS 1.2 mTLS: BAŞARILI"
            if (stream) publishLive(tlsStatus, ntripStatus, lastMetrics, handshakeSummary)
            else post(handshakeSummary + "\nNTRIP ölçülüyor…", false)

            stage = "NTRIP yanıtı"
            socket.soTimeout = 1_000
            tlsProtocol.outputStream.write(config.requestBytes())
            tlsProtocol.outputStream.flush()

            if (!stream) {
                stage = "NTRIP yanıtı ve 10 saniyelik RTCM ölçümü"
                val response = NtripResponseReader.read(tlsProtocol.inputStream, 10)
                ntripSummary = "NTRIP: " + response.safeStatus + "\n" +
                    "RTCM gövdesi (${response.measurementDurationMs} ms): " +
                    response.bodyBytes + " bayt"
                if (!response.success) throw IOException("NTRIP yanıtı: ${response.safeStatus}")
                if (response.bodyBytes <= 0) throw IOException("NTRIP gövdesinde RTCM baytı görülmedi.")
                return handshakeSummary + "\n" + ntripSummary
            }

            // TLS CertificateVerify is complete. Release SPP for the exclusive BXR1 reader.
            activeSignerSession.close()
            signer = null
            activeSigner = null
            val header = NtripHeaderReader.read(tlsProtocol.inputStream)
            ntripStatus = header.safeStatus
            if (!header.success) throw IOException("NTRIP yanıtı: ${header.safeStatus}")
            publishLive(tlsStatus, "${header.safeStatus} — RTCM akıyor", lastMetrics, "BXR1 aktarımı başlatılıyor")

            transfer = bluetooth.beginRtcmTransferSession()
            activeTransfer = transfer
            val activeTransfer: BluetoothSppService.RtcmTransferSession = transfer
            val bridge = RtcmBridgeService { payload ->
                val sent = activeTransfer.sendBlock(payload)
                RtcmBlockSendResult(sent.payloadBytes, sent.nacks, sent.retries)
            }
            stage = "RTCM doğrulama ve BXR1 aktarımı"
            val finalMetrics = bridge.forwardRtcmStream(
                tlsProtocol.inputStream,
                isCancelled = { cancellation.isStopRequested() },
                onProgress = { metrics ->
                    lastMetrics = metrics
                    publishLive(tlsStatus, "${header.safeStatus} — RTCM akıyor", metrics, "Aktarım sürüyor")
                }
            )
            lastMetrics = finalMetrics
            ntripSummary = "NTRIP: ${header.safeStatus}\nRTCM akışı sona erdi."
            return handshakeSummary + "\n" + ntripSummary
        } catch (error: Exception) {
            if (cancelled) {
                val stoppedText = if (stream) "RTCM aktarımı durduruldu." else "Azure testi iptal edildi."
                if (stream) publishLive("Durduruldu", "Durduruldu", lastMetrics, stoppedText)
                return stoppedText + "\n" + formatMetrics(lastMetrics)
            }
            val detail = when (error) {
                is TlsFatalAlertReceived -> "TLS alert: " +
                    AlertDescription.getName(error.alertDescription)
                is IOException -> error.message?.takeIf {
                    it.startsWith("NTRIP ") || it.startsWith("TLS el sıkışmasında") ||
                        it.startsWith("BXR1 ") || it.startsWith("Bluetooth ") ||
                        it.startsWith("RTCM ") || it.startsWith("Test iptal edildi.")
                } ?: error.javaClass.simpleName
                else -> error.javaClass.simpleName
            }
            val observed = listOfNotNull(handshakeSummary, ntripSummary).joinToString("\n")
            val result = "Azure testi BAŞARISIZ ($stage): $detail" +
                (if (observed.isEmpty()) "" else "\n" + observed)
            if (stream) publishLive(tlsStatus, if (ntripStatus == "Bekliyor") "Hata" else ntripStatus,
                lastMetrics, detail)
            return result
        } finally {
            activeTransfer = null
            transfer?.close()
            activeSigner = null
            val socketToClose = activeSocket
            activeSocket = null
            ProbeResourceCleanup.closeAll(
                { protocol?.close() },
                { socketToClose?.close() },
                { signer?.close() }
            )
        }
    }

    fun cancel() {
        cancellation.requestStop()
        activeSigner?.cancel()
        activeTransfer?.cancel()
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

    private fun formatMetrics(metrics: RtcmLiveMetrics): String =
        "RTCM alındı=${metrics.receivedBytes} B; doğrulanan=${metrics.validatedMessages}; " +
            "CRC hatası=${metrics.crcFailures}; BXR1 ACK baytı=${metrics.acknowledgedBytes} B; " +
            "ACK=${metrics.acknowledgements}, NACK=${metrics.negativeAcknowledgements}, " +
            "retry=${metrics.retries}; hız=${metrics.rateBytesPerSecond} B/s"

    private fun publishLive(tls: String, ntrip: String, metrics: RtcmLiveMetrics, detail: String) {
        try {
            mainHandler.post {
                try {
                    if (synchronized(lock) { !stopped }) onLiveUpdate(LiveUpdate(tls, ntrip, metrics, detail))
                } catch (_: Exception) {
                    // Activity can be destroyed while a network callback is queued.
                }
            }
        } catch (_: Exception) {
            // UI lifecycle callbacks must not terminate network workers.
        }
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
