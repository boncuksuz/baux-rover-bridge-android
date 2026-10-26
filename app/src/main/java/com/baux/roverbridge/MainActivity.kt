package com.baux.roverbridge

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Bridge uygulamasinin giris aktivitesi.
 *
 * Mevcut ekran bilincli olarak minimal tutulmustur ve Bluetooth, kimlik
 * dogrulama, RTCM ve GGA akisi parcalari birbirine baglanirken durum
 * gostergesi olarak kullanilir.
 */
class MainActivity : AppCompatActivity() {
    /**
     * Temel arayuzu baslatir ve mevcut entegrasyon durumunu gosterir.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<TextView>(R.id.statusText).text =
            "Hazir: SPP + Azure dogrulama + RTCM kopru iskeleti"
    }
}
