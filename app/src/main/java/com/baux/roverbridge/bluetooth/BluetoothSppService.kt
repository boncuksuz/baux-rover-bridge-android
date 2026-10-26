package com.baux.roverbridge.bluetooth

import java.io.InputStream
import java.io.OutputStream

/**
 * Minimal Bluetooth SPP akis tasiyicisi.
 *
 * Baglanti kurulumunun disarida yonetilmesi beklenir; bu sinif okuma/yazma
 * akislarini ve rover'a yazma yardimci metodunu sunmaya odaklanir.
 */
class BluetoothSppService {
    var input: InputStream? = null
    var output: OutputStream? = null

    /** Hem okuma hem yazma akisi hazirsa true doner. */
    fun isConnected(): Boolean = input != null && output != null

    /** Aktif SPP cikis akisi uzerinden rover'a bayt yazar. */
    fun write(bytes: ByteArray) {
        output?.write(bytes)
        output?.flush()
    }
}
