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
        private const val TEST_VERSION = "BRIDGE-0.2.1-RSA-TEST"
    }

    private lateinit var bluetooth: BluetoothSppService
    private lateinit var statusText: TextView
    private lateinit var connectButton: Button
    private lateinit var pingButton: Button
    private lateinit var rsaButton: Button
    private lateinit var disconnectButton: Button

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
        disconnectButton = findViewById(R.id.disconnectButton)
        findViewById<TextView>(R.id.versionText).text = TEST_VERSION

        bluetooth = BluetoothSppService(applicationContext, ::showState)
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
        disconnectButton.setOnClickListener {
            disconnectButton.isEnabled = false
            bluetooth.disconnect()
        }
        Log.i(TAG, "$TEST_VERSION başlatıldı")
        showState(BluetoothSppService.State.DISCONNECTED, "BAUX-ROVER için Bağlan'a dokunun.")
    }

    private fun showState(state: BluetoothSppService.State, message: String) {
        statusText.text = message
        connectButton.isEnabled = state == BluetoothSppService.State.DISCONNECTED ||
            state == BluetoothSppService.State.CONNECTED
        connectButton.setText(if (state == BluetoothSppService.State.CONNECTED)
            R.string.reconnect else R.string.connect)
        pingButton.isEnabled = state == BluetoothSppService.State.CONNECTED
        rsaButton.isEnabled = state == BluetoothSppService.State.CONNECTED
        disconnectButton.isEnabled = state != BluetoothSppService.State.DISCONNECTED
        Log.i(TAG, "$TEST_VERSION | $state | $message")
    }

    override fun onDestroy() {
        bluetooth.shutdown()
        super.onDestroy()
    }
}
