package com.baux.roverbridge.rtcm

import com.baux.roverbridge.bluetooth.BluetoothSppService
import java.io.InputStream

/**
 * RTCM duzeltme baytlarini ag kaynagindan rover Bluetooth baglantisina aktarir.
 */
class RtcmBridgeService(
    private val bt: BluetoothSppService
) {
    /**
     * RTCM girisinden baytlari surekli okur ve rover'a iletir.
     * Akis bittiginde veya okuma sonucu pozitif degilse dongu sonlanir.
     */
    fun forwardRtcmStream(rtcmInput: InputStream) {
        val buffer = ByteArray(4096)
        while (true) {
            val read = rtcmInput.read(buffer)
            if (read <= 0) break
            bt.write(buffer.copyOf(read))
        }
    }
}
