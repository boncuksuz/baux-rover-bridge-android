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
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Locale

/** Eşleştirilmiş BAUX-ROVER cihazına güvenli RFCOMM SPP bağlantısı. */
class BluetoothSppService(
    private val context: Context,
    private val onStateChanged: (State, String) -> Unit
) {
    enum class State { DISCONNECTED, CONNECTING, CONNECTED, TESTING }

    companion object {
        private const val ROVER_NAME = "BAUX-ROVER"
        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val CONNECT_TIMEOUT_SECONDS = 12L
        private const val PING_TIMEOUT_SECONDS = 5L
        private const val RSA_TIMEOUT_SECONDS = 20L
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
