package com.baux.roverbridge

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.baux.roverbridge.bluetooth.BluetoothSppService
import com.baux.roverbridge.tls.AzureNtripProbe

/**
 * Bridge uygulamasinin giris aktivitesi.
 *
 * Mevcut ekran bilincli olarak minimal tutulmustur ve Bluetooth, kimlik
 * dogrulama, RTCM ve GGA akisi parcalari birbirine baglanirken durum
 * gostergesi olarak kullanilir.
 */
class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "BauxRoverBridge"
        private const val TEST_VERSION = "BRIDGE-0.2.3-RTCM-STREAM"
    }

    private lateinit var bluetooth: BluetoothSppService
    private lateinit var statusText: TextView
    private lateinit var connectButton: Button
    private lateinit var pingButton: Button
    private lateinit var rsaButton: Button
    private lateinit var azureButton: Button
    private lateinit var rtcmStartButton: Button
    private lateinit var rtcmStopButton: Button
    private lateinit var azureStatusText: TextView
    private lateinit var rtcmStatusText: TextView
    private lateinit var disconnectButton: Button
    private lateinit var azureProbe: AzureNtripProbe
    private var currentState = BluetoothSppService.State.DISCONNECTED
    private var azureRunning = false
    private var pendingLiveStream = false

    private val configurationPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            azureRunning = false
            pendingLiveStream = false
            azureStatusText.text = "NTRIP ayar dosyası seçilmedi."
            updateControls()
        } else {
            if (pendingLiveStream) azureProbe.startLiveStream(uri) else azureProbe.run(uri)
        }
    }

    private val connectPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) bluetooth.connect()
        else showState(BluetoothSppService.State.DISCONNECTED, "Bluetooth bağlantı izni verilmedi.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        connectButton = findViewById(R.id.connectButton)
        pingButton = findViewById(R.id.pingButton)
        rsaButton = findViewById(R.id.rsaButton)
        azureButton = findViewById(R.id.azureButton)
        rtcmStartButton = findViewById(R.id.rtcmStartButton)
        rtcmStopButton = findViewById(R.id.rtcmStopButton)
        azureStatusText = findViewById(R.id.azureStatusText)
        rtcmStatusText = findViewById(R.id.rtcmStatusText)
        disconnectButton = findViewById(R.id.disconnectButton)
        findViewById<TextView>(R.id.versionText).text = TEST_VERSION

        bluetooth = BluetoothSppService(applicationContext, ::showState)
        azureProbe = AzureNtripProbe(
            applicationContext,
            bluetooth,
            ::showAzureStatus,
            ::showRtcmStatus
        )
        connectButton.setOnClickListener {
            connectButton.isEnabled = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                connectPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                bluetooth.connect()
            }
        }
        pingButton.setOnClickListener {
            pingButton.isEnabled = false
            bluetooth.ping()
        }
        rsaButton.setOnClickListener {
            rsaButton.isEnabled = false
            bluetooth.rsaPssTest()
        }
        azureButton.setOnClickListener {
            azureRunning = true
            pendingLiveStream = false
            updateControls()
            configurationPicker.launch(arrayOf("*/*"))
        }
        rtcmStartButton.setOnClickListener {
            if (currentState != BluetoothSppService.State.CONNECTED || azureRunning) return@setOnClickListener
            azureRunning = true
            pendingLiveStream = true
            updateControls()
            configurationPicker.launch(arrayOf("*/*"))
        }
        rtcmStopButton.setOnClickListener {
            rtcmStopButton.isEnabled = false
            rtcmStatusText.text = "RTCM aktarımı güvenli biçimde durduruluyor…"
            azureProbe.cancel()
        }
        disconnectButton.setOnClickListener {
            disconnectButton.isEnabled = false
            azureProbe.cancel()
            bluetooth.disconnect()
        }
        Log.i(TAG, "$TEST_VERSION başlatıldı")
        showState(BluetoothSppService.State.DISCONNECTED, "BAUX-ROVER için Bağlan'a dokunun.")
    }

    private fun showState(state: BluetoothSppService.State, message: String) {
        currentState = state
        statusText.text = message
        updateControls()
        Log.i(TAG, "$TEST_VERSION | $state | $message")
    }

    private fun showAzureStatus(message: String, complete: Boolean) {
        azureStatusText.text = message
        if (complete) {
            azureRunning = false
            pendingLiveStream = false
            updateControls()
        }
        Log.i(TAG, "$TEST_VERSION | AZURE | $message")
    }

    private fun showRtcmStatus(update: AzureNtripProbe.LiveUpdate) {
        val metrics = update.metrics
        rtcmStatusText.text = "TLS: ${update.tlsStatus}\n" +
            "NTRIP: ${update.ntripStatus}\n" +
            "RTCM alınan: ${metrics.receivedBytes} B; doğrulanan mesaj: ${metrics.validatedMessages}; " +
            "CRC hatası: ${metrics.crcFailures}; hatalı başlık: ${metrics.malformedHeaders}\n" +
            "BXR1 gönderilen/ACK’li: ${metrics.acknowledgedBytes} B; ACK: ${metrics.acknowledgements}; " +
            "NACK: ${metrics.negativeAcknowledgements}; retry: ${metrics.retries}\n" +
            "Aktarım hızı: ${metrics.rateBytesPerSecond} B/s\nDurum: ${update.detail}"
        Log.i(TAG, "$TEST_VERSION | RTCM | TLS=${update.tlsStatus} NTRIP=${update.ntripStatus} " +
            "rx=${metrics.receivedBytes} valid=${metrics.validatedMessages} " +
            "crc_fail=${metrics.crcFailures} ack_bytes=${metrics.acknowledgedBytes} " +
            "ack=${metrics.acknowledgements} nack=${metrics.negativeAcknowledgements} " +
            "retry=${metrics.retries} rate_Bps=${metrics.rateBytesPerSecond} state=${update.detail}")
    }

    private fun updateControls() {
        connectButton.isEnabled = !azureRunning &&
            (currentState == BluetoothSppService.State.DISCONNECTED ||
                currentState == BluetoothSppService.State.CONNECTED)
        connectButton.setText(if (currentState == BluetoothSppService.State.CONNECTED)
            R.string.reconnect else R.string.connect)
        val ready = !azureRunning && currentState == BluetoothSppService.State.CONNECTED
        pingButton.isEnabled = ready
        rsaButton.isEnabled = ready
        azureButton.isEnabled = ready
        rtcmStartButton.isEnabled = ready
        rtcmStopButton.isEnabled = azureRunning && pendingLiveStream
        disconnectButton.isEnabled = currentState != BluetoothSppService.State.DISCONNECTED
    }

    override fun onDestroy() {
        azureProbe.shutdown()
        bluetooth.shutdown()
        super.onDestroy()
    }
}
