package com.baux.roverbridge.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.baux.roverbridge.R
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.PublicKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Locale
import java.security.SecureRandom

/** Eşleştirilmiş BAUX-ROVER cihazına güvenli RFCOMM SPP bağlantısı. */
class BluetoothSppService(
    private val context: Context,
    private val onStateChanged: (State, String) -> Unit
) {
    enum class State { DISCONNECTED, CONNECTING, CONNECTED, TESTING, RTCM_STREAMING }

    companion object {
        private const val ROVER_NAME = "BAUX-ROVER"
        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val CONNECT_TIMEOUT_SECONDS = 12L
        private const val PING_TIMEOUT_SECONDS = 5L
        private const val RSA_TIMEOUT_SECONDS = 20L
        private const val BXR1_ACK_TIMEOUT_MS = 8_000L
        private const val BXR1_MAX_ATTEMPTS = Bxr1Protocol.MAX_ATTEMPTS
    }

    // Mevcut akış arayüzü RTCM ve sonraki köprü adımları için korunur.
    @Volatile var input: InputStream? = null
    @Volatile var output: OutputStream? = null

    private val lock = Any()
    private var socket: BluetoothSocket? = null
    private var generation = 0
    private var stopped = false
    private var testBusy = false
    private val worker = Executors.newSingleThreadExecutor()
    private val timeoutWorker = Executors.newSingleThreadScheduledExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun isConnected(): Boolean = input != null && output != null

    /** Mevcut SPP çıkış akışı üzerinden rover'a bayt yazar. Çağıran iş parçacığında çalışır. */
    fun write(bytes: ByteArray) {
        output?.write(bytes)
        output?.flush()
    }

    fun connect() {
        val (request, previous) = synchronized(lock) {
            if (stopped) return
            generation += 1
            testBusy = false
            generation to socket
        }
        report(State.CONNECTING, "$ROVER_NAME aranıyor ve bağlanılıyor…")
        if (previous != null) timeoutWorker.execute { closeSocket(previous) }
        worker.execute { connectOnWorker(request) }
    }

    @SuppressLint("MissingPermission")
    private fun connectOnWorker(request: Int) {
        if (!isCurrent(request)) return
        if (!hasConnectPermission()) {
            reportIfCurrent(request, State.DISCONNECTED, "Bluetooth bağlantı izni gerekli.")
            return
        }

        var candidate: BluetoothSocket? = null
        var timeout: ScheduledFuture<*>? = null
        val timedOut = AtomicBoolean(false)
        try {
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                ?: throw IOException("Bu telefonda Bluetooth bulunamadı.")
            if (!adapter.isEnabled) throw IOException("Bluetooth kapalı.")
            val rover = adapter.bondedDevices.firstOrNull { it.name == ROVER_NAME }
                ?: throw IOException("$ROVER_NAME eşleştirilmemiş. Önce telefonun Bluetooth ayarlarından eşleştirin.")

            candidate = rover.createRfcommSocketToServiceRecord(SPP_UUID)
            val active = synchronized(lock) {
                if (isCurrentLocked(request)) {
                    socket = candidate
                    true
                } else false
            }
            if (!active) return

            val connectingSocket = candidate
            timeout = timeoutWorker.schedule({
                timedOut.set(true)
                closeSocket(connectingSocket)
            }, CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            connectingSocket.connect()
            if (timedOut.get()) throw SocketTimeoutException("Bağlantı zaman aşımı.")

            val newInput = connectingSocket.inputStream
            val newOutput = connectingSocket.outputStream
            synchronized(lock) {
                if (!isCurrentLocked(request) || socket !== connectingSocket) return
                input = newInput
                output = newOutput
            }
            reportIfCurrent(request, State.CONNECTED, "$ROVER_NAME bağlandı. PING testi hazır.")
        } catch (error: Exception) {
            candidate?.let { closeSocket(it) }
            val message = if (timedOut.get()) "Bağlantı zaman aşımı. Yeniden bağlanabilirsiniz."
                else "Bağlantı kurulamadı: ${error.message ?: error.javaClass.simpleName}"
            reportIfCurrent(request, State.DISCONNECTED, message)
        } finally {
            timeout?.cancel(false)
            if (!isCurrent(request)) candidate?.let { closeSocket(it) }
        }
    }

    fun ping() {
        val request = synchronized(lock) {
            if (stopped || testBusy || socket == null || input == null || output == null) return
            testBusy = true
            generation
        }
        report(State.TESTING, "PING gönderiliyor; PONG bekleniyor…")
        worker.execute { pingOnWorker(request) }
    }

    private fun pingOnWorker(request: Int) {
        val (activeSocket, activeInput, activeOutput) = synchronized(lock) {
            if (!isCurrentLocked(request)) return
            Triple(socket ?: return, input ?: return, output ?: return)
        }
        val timedOut = AtomicBoolean(false)
        val timeout = timeoutWorker.schedule({
            timedOut.set(true)
            closeSocket(activeSocket)
        }, PING_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        try {
            activeOutput.write("PING\n".toByteArray(StandardCharsets.US_ASCII))
            activeOutput.flush()
            if (!readPong(activeInput)) throw EOFException("Bağlantı kapandı.")
            if (timedOut.get()) throw SocketTimeoutException("PING zaman aşımı.")
            reportIfCurrent(request, State.CONNECTED, "PONG alındı — SPP testi başarılı.")
        } catch (error: Exception) {
            closeSocket(activeSocket)
            val message = if (timedOut.get()) "PONG zaman aşımı. Yeniden bağlanabilirsiniz."
                else "Bağlantı koptu: ${error.message ?: error.javaClass.simpleName}. Yeniden bağlanabilirsiniz."
            reportIfCurrent(request, State.DISCONNECTED, message)
        } finally {
            timeout.cancel(false)
            synchronized(lock) { if (isCurrentLocked(request)) testBusy = false }
        }
    }

    /** Aynı SPP socket üzerinde tek bir RSA imza isteği yürütür. */
    fun rsaPssTest() {
        val request = synchronized(lock) {
            if (stopped || testBusy || socket == null || input == null || output == null) return
            testBusy = true
            generation
        }
        report(State.TESTING, "RSA-PSS imza isteği gönderiliyor…")
        worker.execute { rsaPssTestOnWorker(request) }
    }

    private fun rsaPssTestOnWorker(request: Int) {
        val (activeSocket, activeInput, activeOutput) = synchronized(lock) {
            if (!isCurrentLocked(request)) return
            Triple(socket ?: return, input ?: return, output ?: return)
        }
        val timedOut = AtomicBoolean(false)
        val timeout = timeoutWorker.schedule({
            timedOut.set(true)
            closeSocket(activeSocket)
        }, RSA_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        try {
            val publicKey = loadClientPublicKey()
            val started = SystemClock.elapsedRealtimeNanos()
            activeOutput.write(RsaPssProtocol.requestBytes())
            activeOutput.flush()

            var metrics: RsaPssProtocol.Response.Metrics? = null
            var signature: ByteArray? = null
            while (signature == null) {
                val line = readResponseLine(activeInput)
                when (val response = RsaPssProtocol.parseResponse(line)) {
                    is RsaPssProtocol.Response.Metrics -> metrics = response
                    is RsaPssProtocol.Response.SignatureBytes -> signature = response.bytes
                    is RsaPssProtocol.Response.DeviceError ->
                        throw IOException("ESP32 imza hatası: ${response.code}")
                    RsaPssProtocol.Response.Diagnostic -> Unit
                }
            }
            if (timedOut.get()) throw SocketTimeoutException("RSA yanıtı zaman aşımı.")
            val roundTripMs = TimeUnit.NANOSECONDS.toMillis(SystemClock.elapsedRealtimeNanos() - started)
            val valid = try {
                RsaPssProtocol.verify(publicKey, signature)
            } finally {
                signature.fill(0)
            }
            if (!valid) throw IOException("İmza doğrulanamadı.")
            val metricsText = metrics?.let {
                "\nESP32 imza: ${String.format(Locale.US, "%.1f", it.elapsedUs / 1000.0)} ms" +
                    "; heap ${it.freeBefore}→${it.freeAfter} B" +
                    "; min ${it.minBefore}→${it.minAfter} B" +
                    "; en büyük blok ${it.largestBefore}→${it.largestAfter} B"
            } ?: "\nESP32 metrikleri alınmadı."
            reportIfCurrent(request, State.CONNECTED,
                "RSA-PSS VALID\nİstek/yanıt: $roundTripMs ms$metricsText")
        } catch (error: Exception) {
            closeSocket(activeSocket)
            val reason = if (timedOut.get()) "İmza yanıtı zaman aşımı."
                else error.message ?: "İmza testi başarısız."
            reportIfCurrent(request, State.DISCONNECTED,
                "RSA-PSS TEST FAILED: $reason Yeniden bağlanabilirsiniz.")
        } finally {
            timeout.cancel(false)
            synchronized(lock) { if (isCurrentLocked(request)) testBusy = false }
        }
    }

    /** TLS oturumu boyunca PING ve bağımsız RSA testini dışlar. */
    @Throws(IOException::class)
    fun beginTlsSignerSession(): TlsSignerSession {
        val request = synchronized(lock) {
            if (stopped || testBusy || socket == null || input == null || output == null) {
                throw IOException("Bluetooth bağlantısı TLS imzası için hazır değil.")
            }
            testBusy = true
            generation
        }
        report(State.TESTING, "TLS uzak imzalayıcı etkin.")
        return TlsSignerSession(request)
    }

    inner class TlsSignerSession internal constructor(private val request: Int) : AutoCloseable {
        @Volatile var lastSignatureElapsedUs: Long = -1
            private set
        @Volatile private var cancelled = false

        fun cancel() {
            cancelled = true
        }

        /** TLS ağ iş parçacığından çağrılır; mevcut Bluetooth worker'ında tek imza yürütür. */
        @Throws(IOException::class)
        fun sign(sha256Hash: ByteArray): ByteArray {
            if (cancelled) throw IOException("TLS uzak imza oturumu iptal edildi.")
            if (sha256Hash.size != 32) throw IOException("TLS imzası 32 bayt SHA-256 özeti gerektirir.")
            val future = worker.submit<RemoteSignature> {
                signHashOnWorker(request, sha256Hash.copyOf()) { cancelled }
            }
            val result = try {
                future.get(RSA_TIMEOUT_SECONDS + 5, TimeUnit.SECONDS)
            } catch (error: ExecutionException) {
                throw (error.cause as? IOException ?: IOException("Bluetooth imzası başarısız.", error.cause))
            } catch (error: TimeoutException) {
                future.cancel(true)
                throw IOException("Bluetooth imzası zaman aşımı.", error)
            } catch (error: InterruptedException) {
                future.cancel(true)
                Thread.currentThread().interrupt()
                throw IOException("Bluetooth imzası iptal edildi.", error)
            }
            lastSignatureElapsedUs = result.elapsedUs
            return result.bytes
        }

        override fun close() {
            val released = synchronized(lock) {
                if (isCurrentLocked(request)) {
                    testBusy = false
                    true
                } else false
            }
            if (released) reportIfCurrent(request, State.CONNECTED, "TLS imzalayıcı serbest.")
        }
    }

    private data class RemoteSignature(val bytes: ByteArray, val elapsedUs: Long)

    private fun signHashOnWorker(request: Int, hash: ByteArray, isCancelled: () -> Boolean): RemoteSignature {
        val (activeSocket, activeInput, activeOutput) = synchronized(lock) {
            if (!isCurrentLocked(request) || !testBusy) {
                throw IOException("Bluetooth bağlantısı imza sırasında değişti.")
            }
            Triple(socket ?: throw IOException("Bluetooth bağlantısı kapandı."),
                input ?: throw IOException("Bluetooth girişi kapandı."),
                output ?: throw IOException("Bluetooth çıkışı kapandı."))
        }
        try {
            activeOutput.write(RsaPssProtocol.requestBytes(hash))
            activeOutput.flush()
            var elapsedUs = -1L
            var unrelatedLines = 0
            while (true) {
                when (val response = RsaPssProtocol.parseResponse(
                    readResponseLinePolling(activeInput, request, RSA_TIMEOUT_SECONDS * 1000, isCancelled)
                )) {
                    is RsaPssProtocol.Response.Metrics -> elapsedUs = response.elapsedUs
                    is RsaPssProtocol.Response.SignatureBytes -> {
                        if (elapsedUs < 0) throw IOException("ESP32 imzası metrik satırı olmadan geldi.")
                        return RemoteSignature(response.bytes, elapsedUs)
                    }
                    is RsaPssProtocol.Response.DeviceError ->
                        throw IOException("ESP32 imza hatası: ${response.code}")
                    RsaPssProtocol.Response.Diagnostic -> {
                        if (++unrelatedLines > 256) throw IOException("Çok fazla tanılama satırı geldi.")
                    }
                }
            }
        } catch (error: Exception) {
            if (!isCancelled()) {
                closeSocket(activeSocket)
                reportIfCurrent(request, State.DISCONNECTED,
                    "Bluetooth imzası başarısız; bağlantı kapandı. Yeniden bağlanabilirsiniz.")
            }
            throw if (error is SocketTimeoutException || error is InterruptedException)
                SocketTimeoutException("Bluetooth imzası zaman aşımı.")
            else IOException("Bluetooth imzası başarısız: ${error.message ?: error.javaClass.simpleName}", error)
        } finally {
            hash.fill(0)
        }
    }

    /** Acquires the existing SPP reader/writer for serialized BXR1 traffic. */
    @Throws(IOException::class)
    fun beginRtcmTransferSession(): RtcmTransferSession {
        val request = synchronized(lock) {
            if (stopped || testBusy || socket == null || input == null || output == null) {
                throw IOException("Bluetooth bağlantısı RTCM aktarımı için hazır değil.")
            }
            testBusy = true
            generation
        }
        report(State.RTCM_STREAMING, "Bluetooth BXR1 RTCM aktarımı etkin.")
        val initialSequence = SecureRandom().nextInt().toLong() and Bxr1Protocol.UINT32_MAX
        return RtcmTransferSession(request, initialSequence)
    }

    data class Bxr1SendResult(val sequence: Long, val payloadBytes: Int, val retries: Int, val nacks: Int)

    inner class RtcmTransferSession internal constructor(
        private val request: Int,
        initialSequence: Long
    ) : AutoCloseable {
        private val cancelled = AtomicBoolean(false)
        private var nextSequence = initialSequence

        @Synchronized
        @Throws(IOException::class)
        fun sendBlock(payload: ByteArray): Bxr1SendResult {
            if (cancelled.get()) throw IOException("RTCM aktarımı durduruldu.")
            val sequence = nextSequence
            val encoded = Bxr1Protocol.encode(sequence, payload.copyOf())
            val future = worker.submit<Bxr1SendResult> {
                sendBxr1BlockOnWorker(request, sequence, encoded, cancelled)
            }
            val result = try {
                future.get(BXR1_ACK_TIMEOUT_MS * BXR1_MAX_ATTEMPTS + 2_000, TimeUnit.MILLISECONDS)
            } catch (error: ExecutionException) {
                throw (error.cause as? IOException ?: IOException("BXR1 aktarımı başarısız.", error.cause))
            } catch (error: TimeoutException) {
                cancelled.set(true)
                future.cancel(true)
                val failedSocket = synchronized(lock) {
                    if (isCurrentLocked(request)) socket else null
                }
                if (failedSocket != null) {
                    closeSocket(failedSocket)
                    reportIfCurrent(request, State.DISCONNECTED,
                        "BXR1 ACK bekleme üst sınırı aşıldı; Bluetooth bağlantısı kapatıldı.")
                }
                throw IOException("BXR1 ACK bekleme üst sınırı aşıldı.", error)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                cancelled.set(true)
                future.cancel(true)
                throw IOException("BXR1 ACK bekleme işlemi iptal edildi.", error)
            }
            nextSequence = (sequence + 1) and Bxr1Protocol.UINT32_MAX
            return result
        }

        fun cancel() {
            cancelled.set(true)
        }

        override fun close() {
            cancelled.set(true)
            val released = synchronized(lock) {
                if (isCurrentLocked(request)) {
                    testBusy = false
                    true
                } else false
            }
            if (released && isConnected()) {
                reportIfCurrent(request, State.CONNECTED, "Bluetooth bağlantısı RTCM aktarımı için serbest.")
            }
        }
    }

    private fun sendBxr1BlockOnWorker(
        request: Int,
        sequence: Long,
        frame: Bxr1Protocol.EncodedFrame,
        cancelled: AtomicBoolean
    ): Bxr1SendResult {
        val (activeSocket, activeInput, activeOutput) = synchronized(lock) {
            if (!isCurrentLocked(request) || !testBusy) throw IOException("Bluetooth RTCM oturumu artık etkin değil.")
            Triple(socket ?: throw IOException("Bluetooth bağlantısı kapandı."),
                input ?: throw IOException("Bluetooth girişi kapandı."),
                output ?: throw IOException("Bluetooth çıkışı kapandı."))
        }
        var retries = 0
        var nacks = 0
        try {
            var attempt = 1
            while (attempt <= BXR1_MAX_ATTEMPTS) {
                if (cancelled.get()) throw IOException("RTCM aktarımı durduruldu.")
                activeOutput.write(frame.bytes)
                activeOutput.flush()
                val response = try {
                    Bxr1Protocol.parseResponse(
                        readResponseLinePolling(activeInput, request, BXR1_ACK_TIMEOUT_MS, cancelled::get)
                    )
                } catch (error: SocketTimeoutException) {
                    if (Bxr1Protocol.retryTimeout(attempt)) {
                        retries++
                        attempt++
                        continue
                    }
                    throw IOException("BXR1 ACK zaman aşımı (seq=$sequence).", error)
                }

                when (response) {
                    is Bxr1Protocol.Response.Ack -> {
                        if (response.sequence != sequence || response.length != frame.bytes.size - Bxr1Protocol.FRAME_OVERHEAD_BYTES ||
                            response.payloadCrc32 != frame.payloadCrc32
                        ) {
                            throw IOException("BXR1 ACK sıra, uzunluk veya CRC32 bilgisi eşleşmedi.")
                        }
                        return Bxr1SendResult(sequence, response.length, retries, nacks)
                    }
                    is Bxr1Protocol.Response.Nack -> {
                        if (response.sequence != sequence) throw IOException("BXR1 NACK sıra numarası eşleşmedi.")
                        nacks++
                        if (Bxr1Protocol.retryNack(response.reason, attempt)) {
                            retries++
                            attempt++
                            continue
                        }
                        throw IOException("BXR1 NACK ${response.reason} (seq=$sequence).")
                    }
                }
            }
            throw IOException("BXR1 aktarım denemeleri tükendi (seq=$sequence).")
        } catch (error: Exception) {
            if (!cancelled.get()) {
                closeSocket(activeSocket)
                reportIfCurrent(request, State.DISCONNECTED,
                    "BXR1 RTCM aktarımı başarısız; Bluetooth bağlantısı kapandı. Yeniden bağlanın.")
            }
            throw if (error is IOException) error else IOException("BXR1 aktarımı başarısız.", error)
        }
    }

    private fun readResponseLinePolling(
        stream: InputStream,
        request: Int,
        timeoutMs: Long,
        isCancelled: () -> Boolean
    ): String {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        val line = StringBuilder()
        while (SystemClock.elapsedRealtime() < deadline) {
            if (isCancelled() || !isCurrent(request)) throw IOException("Bluetooth işlemi iptal edildi.")
            val available = stream.available()
            if (available <= 0) {
                try {
                    Thread.sleep(5)
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Bluetooth okuma iptal edildi.", error)
                }
                continue
            }
            repeat(minOf(available, 256)) {
                val value = stream.read()
                if (value < 0) throw EOFException("Bluetooth bağlantısı yanıt sırasında kapandı.")
                if (value == '\n'.code) {
                    val response = line.toString().trimEnd('\r')
                    if (response.startsWith("@RTCM:") || response.startsWith("@RSIG:") ||
                        response.startsWith("RSIG_METRICS ")) return response
                    line.clear()
                } else if (value != '\r'.code) {
                    if (line.length >= 640) throw IOException("Bluetooth yanıt satırı çok uzun.")
                    line.append(value.toChar())
                }
            }
        }
        throw SocketTimeoutException("Bluetooth yanıt zaman aşımı.")
    }

    private fun loadClientPublicKey(): PublicKey =
        context.resources.openRawResource(R.raw.rover_client_certificate).use { stream ->
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(stream) as X509Certificate
            require(certificate.publicKey.algorithm == "RSA") { "İstemci sertifikası RSA değil." }
            certificate.publicKey
        }

    private fun readResponseLine(stream: InputStream): String {
        val line = StringBuilder()
        while (true) {
            val value = stream.read()
            if (value < 0) throw EOFException("İmza yanıtı eksik kaldı.")
            if (value == '\n'.code) return line.toString().trimEnd('\r')
            if (value > 0x7f) throw IOException("RSA yanıtında ASCII dışı bayt var.")
            if (line.length >= 640) throw IOException("İmza yanıtı çok uzun.")
            line.append(value.toChar())
        }
    }

    private fun readPong(stream: InputStream): Boolean {
        val line = StringBuilder()
        while (true) {
            val value = stream.read()
            if (value < 0) return false
            if (value == '\n'.code) {
                if (line.toString() == "PONG") return true
                line.clear()
            } else if (value != '\r'.code) {
                if (line.length >= 256) throw IOException("Bluetooth yanıtı çok uzun.")
                line.append(value.toChar())
            }
        }
    }

    fun disconnect() {
        val (request, current) = synchronized(lock) {
            if (stopped) return
            generation += 1
            testBusy = false
            generation to socket
        }
        timeoutWorker.execute {
            current?.let { closeSocket(it) }
            reportIfCurrent(request, State.DISCONNECTED, "Bağlantı kapatıldı.")
        }
    }

    fun shutdown() {
        val current = synchronized(lock) {
            if (stopped) return
            stopped = true
            generation += 1
            testBusy = false
            socket
        }
        timeoutWorker.execute {
            current?.let { closeSocket(it) }
            timeoutWorker.shutdownNow()
        }
        worker.shutdownNow()
    }

    private fun closeSocket(target: BluetoothSocket) {
        synchronized(lock) {
            if (socket === target) {
                socket = null
                input = null
                output = null
            }
        }
        try {
            target.close()
        } catch (_: IOException) {
            // Socket zaten kapalı olabilir.
        }
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun isCurrent(request: Int): Boolean = synchronized(lock) { isCurrentLocked(request) }

    private fun isCurrentLocked(request: Int): Boolean = !stopped && generation == request

    private fun reportIfCurrent(request: Int, state: State, message: String) {
        mainHandler.post {
            if (isCurrent(request)) onStateChanged(state, message)
        }
    }

    private fun report(state: State, message: String) {
        mainHandler.post {
            if (synchronized(lock) { !stopped }) onStateChanged(state, message)
        }
    }
}
