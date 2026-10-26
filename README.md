# BAU-X Rover Bridge Android

Rover tarafindaki Bluetooth trafigi ile bulut tarafindaki duzeltme/kimlik dogrulama servisleri arasinda kopru kuran Android Kotlin uygulamasi.

## Proje ne yapar
- Rover'a Bluetooth Classic SPP ile baglanir.
- Rover'dan gelen HELLO challenge payload'unu alir ve backend ile dogrular.
- RTCM duzeltme baytlarini ag girisinden rover'a iletir.
- Rover GGA mesajlarini ayiklayip ust katmana iletime hazirlar.

## Yuksek seviye akis
1. Uygulama rover'a Bluetooth SPP ile baglanir.
2. Rover HELLO payload gonderir (kimlik + imza verisi).
3. Uygulama HELLO payload'unu dogrulama endpoint'ine gonderir.
4. Dogrulama basariliysa kopru trafigi guvenilir kabul edilir.
5. RTCM akisi rover'a aktarilir.
6. GGA cerceveleri ayiklanir ve backend'e gonderilebilir.

## Proje yapisi
- `app/src/main/java/com/baux/roverbridge/MainActivity.kt`
  - Minimal arayuz giris noktasidir, su an entegrasyon durum metnini gosterir.
- `app/src/main/java/com/baux/roverbridge/bluetooth/BluetoothSppService.kt`
  - SPP giris/cikis akislarini tutar ve yazma yardimci metodunu saglar.
- `app/src/main/java/com/baux/roverbridge/auth/HelloPayload.kt`
  - HELLO dogrulama payload'u icin veri modelidir.
- `app/src/main/java/com/baux/roverbridge/auth/VerifyService.kt`
  - HELLO payload JSON verisini backend dogrulama endpoint'ine gonderir.
- `app/src/main/java/com/baux/roverbridge/rtcm/RtcmBridgeService.kt`
  - RTCM giris akisini okur ve baytlari rover'a iletir.
- `app/src/main/java/com/baux/roverbridge/gga/GgaForwardService.kt`
  - Rover protokol cercevesinden ham NMEA GGA satirini ayiklar.

## Android izinleri
`app/src/main/AndroidManifest.xml` icinde tanimlidir:
- `BLUETOOTH`, `BLUETOOTH_ADMIN`
- `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`
- `ACCESS_FINE_LOCATION` (bircok Android surumunde tarama icin gerekir)
- `INTERNET`

## Derleme gereksinimleri
- Gradle 8.9 wrapper uyumlu dagitimi.
- Android Gradle Plugin 8.5.2.
- JDK 11+ gerekir (JDK 17 onerilir).

## Mevcut durum
Bu depo iskelet bir temel saglar. Cekirdek servisler mevcut olsa da uretim icin bazi gereksinimler henuz tamamlanmamistir:
- mTLS istemci sertifikasi yonetimi.
- Bluetooth baglanti yasam dongusu ve yeniden deneme mekanizmalari.
- Asenkron/arka plan calistirma ve guclu hata yonetimi.
- Gercek backend ve rover cihazi ile uctan uca entegrasyon.

## Onerilen sonraki adimlar
1. Backend base URL yapilandirmasini ve ortam ayrimini ekle.
2. Android Keystore ile guvenli anahtar/sertifika yuklemeyi uygula.
3. Ag ve akis donguleri icin coroutine tabanli is parcacigi yonetimi ekle.
4. Loglama, telemetri ve kullaniciya gorunur durum modelini ekle.
5. HELLO parse/dogrulama ve akis kopruleme davranisi icin birim/entegrasyon testleri ekle.
