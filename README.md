# BAU-X Rover Bridge Android

Faz 2 adimlarinin kronolojik ozeti: [Versiyon Gunlugu](VERSIYON_GUNLUGU.md).

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

## Faz 2C: Azure mTLS denemesi

`BRIDGE-0.2.2-MTLS-TEST` ekranda ve Logcat'te gorunur. Azure testi, mevcut
BAUX-ROVER SPP baglantisi uzerinden ESP32'ye yalnizca TLS CertificateVerify
SHA-256 ozetini imzalatir. Uygulama istemci sertifikasinin acik anahtarini ve
guvenilir CA sertifikasini tasir; ozel anahtari tasimaz. TLS 1.2, PKIX zinciri
ve sunucu kimligi denetlenir. NTRIP GET yanitinin durumu ve 10 saniyedeki
RTCM govde baytlari olculur; RTCM henuz rover'a aktarilmaz.

Gercek NTRIP ayarlari `ntrip-local.properties.example` orneginden
`ntrip-local.properties` dosyasina yazilir. Bu dosya Git tarafindan yok sayilir,
APK'ye eklenmez ve test baslatilirken Android belge secicisinden okunur.
`authorizationBase64`, `kullanici:sifre` ifadesinin Base64 kodudur; gizli
bilgi sayilir. Dosyayi telefona guvenli bicimde aktarip testten sonra kaldirin.
`allowIpCnFallback` varsayilan olarak `false` olmalidir. Yalnizca eski Azure
sunucu sertifikasinda IP SAN bulunmadigi ve CN ayarlanan IP ile tam eslestigi
biliniyorsa `true` yapin. Bu istisna PKIX dogrulamasini kapatmaz, ancak
modern IP SAN kimlik denetiminden daha zayiftir. Bu testte iptal listesi
(revocation) denetimi ve SPKI pin bulunmaz.

Telefon testi: Debug APK'yi kurun; Android ayarlarinda BAUX-ROVER ile eslestirin;
uygulamada Baglan, PING Testi ve RSA-PSS Testi'ni sirayla calistirin. Ardindan
Azure mTLS Testi'ne dokunup yerel ayar dosyasini secin. Ekranda TLS sonucu,
NTRIP durumu, handshake/imza sureleri ve 10 saniyelik bayt sayisi gorunur.
PING/PONG veya TLS basarisi, Bluetooth baglanti sifrelemesinin bagimsiz kaniti
degildir; bu dogrulama hala aciktir.
