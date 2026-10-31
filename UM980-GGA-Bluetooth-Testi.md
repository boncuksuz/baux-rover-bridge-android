# UM980 COM2 GGA → Android Bluetooth testi

Tarih: 31 Ekim 2026. Durum: APK ve firmware derlendi; kullanıcı gerçek donanım testini başarılı olarak doğruladı.

## 1. Başlangıç ve çalışan sürümün korunması

| Depo | Başlangıç HEAD |
|---|---|
| ESP32_Rover_Core | `2edc5e1d1f2e6567f4393e6f33c412b6399f95c1` |
| bau-x-rover-bridge-android | `8e9c5fec62ba5d8ac27c639d061d2e2af43b51b2` |

Her iki çalışma ağacı başlangıçta değişiklik içeriyordu. Firmware'de `platformio.ini`, Android'de VERSIONA çalışmasına ait kaynaklar, yapılandırma ve belgeler zaten değişmişti. Başlangıçtaki dosyalar ve mevcut derlenmiş APK/firmware, değişikliklerden önce yedeklendi:

`C:\Users\merti\AppData\Local\Temp\BAUX-GGA-baseline-20261030T174411Z`

`firmware.zip`, `android.zip` ve `manifest.json` içerir. Arşivlerdeki 33 firmware ve 38 Android dosyasının SHA256 değerleri doğrulandı. Sırlar ve özel anahtarlar yedeğe alınmadı; yerlerindeki dosyalar değiştirilmedi. Geçici dizindeki bu yedek kalıcı bir Git kaydı değildir.

Reset, clean, checkout, stash, commit veya push yapılmadı.

## 2. Mevcut kaynaklarda bulunan durum

- `ESP32_Rover_Core/src/GnssUart.cpp`: COM2 üzerinden alınan karakterler mevcut PPS/GGA ve UM980 yanıt okuyucularına veriliyordu; Bluetooth GGA aktarımı yoktu.
- `ESP32_Rover_Core/src/PpsTime.cpp`: GGA/PPS tanılaması mevcuttu; Android'e gönderim yapmıyordu.
- `app/src/main/java/com/baux/roverbridge/gga/GgaForwardService.kt`: `GGA|$...` zarfını ayıran yardımcı vardı; aktif Bluetooth okuma yoluna bağlanmamıştı.
- Android Bluetooth servisi işlem yanıtlarını okuyor, boşta sürekli GGA okumuyordu.

Bu nedenle aktarım eklendi. UM980'e yeni komut gönderilmedi ve GGA çıkış yapılandırması değiştirilmedi; test mevcut COM2 GGA çıkışını gözlemler.

## 3. Sürümler ve çıktılar

| Bileşen | Test sürümü |
|---|---|
| Android | `BRIDGE-0.2.5-GGA-TEST` |
| ESP32 | `CORE-0.2.2-BT-GGA-COM1-TEST` |
| PlatformIO ortamı | `esp32dev_bt_rtcm_com1_gga_test` |

APK:

`C:\Users\merti\Desktop\Bau-X\bau-x-rover-bridge-android\app\build\outputs\apk\debug\app-debug.apk`

APK SHA256: `3B59A81406187C77B9BB543A4905BB8DEFDF4759A81856EC2CF7C8276047E5DC`

Firmware:

`C:\Users\merti\Desktop\Bau-X\bau-x\development\ESP32_Rover_Core\.pio\build\esp32dev_bt_rtcm_com1_gga_test\firmware.bin`

Firmware SHA256: `12DD2DBB2DA1AE3747E85CC256569FE6810BAF33DF1488101F21EAA269A95298`

Bu firmware.bin tek başına tam flash görüntüsü değildir; yükleme için doğru ortamla PlatformIO kullanılmalıdır. Yeni firmware sürümü USB açılış logunda; APK sürümü ekranda ve `BauxGga` Logcat kayıtlarında görünür.

## 4. Eklenen aktarım

Bluetooth satırı:

```text
GGA|$GNGGA,...*HH\n
```

`GP` ve diğer iki büyük harfli NMEA talker kimlikleri de kabul edilir. `HH` gerçek NMEA checksum değeridir; yukarıdaki satır yalnızca biçim gösterimidir.

- COM2 karakterleri ek bir sınırlı boyutlu okuyucu tarafından gözlemlenir. Checksum geçerli GGA'nın son örneği saklanır.
- Bluetooth'a en fazla saniyede bir GGA gönderilir. Yoğunlukta veya daha yüksek kaynak hızında ara örnekler birleştirilebilir; bütün GGA'ların kayıpsız iletimi amaçlanmaz.
- UART görevinden Bluetooth'a yazılmaz. Gönderim mevcut ana döngüde, bekleyen RTCM UART çerçevesi ve tamponlanmış Bluetooth girdisi öncelikli olacak şekilde yapılır.
- SPP yoğunluğu ve yazma ilerlemesi gözlemlenerek GGA yazılarının birikmesi sınırlandırılır. SPP yazma olayı, Android'in mesajı işlediğinin doğrulaması değildir.
- Android boşta ve işlem sırasında aynı seri iş kuyruğunu ve aynı satır ayırıcısını kullanır. Ayrı bir eşzamanlı soket okuyucusu eklenmedi.
- GGA satırları ACK/NACK okuyucularından ayrılır. Parçalı satırlar korunur; geçersiz GGA `GGA_REJECTED` olarak loglanır.

Android ekranı ve `BauxGga` logu şunları gösterir:

- `gga_utc_time_of_day`: GGA içindeki UTC saat; tarih içermez, `utc_date=UNAVAILABLE`.
- `latitude_deg`, `longitude_deg`: işaretli ondalık derece.
- `altitude_msl_m`: GGA'nın deniz seviyesine göre yüksekliği; elipsoidal yükseklik değildir.
- `fix_quality`, `satellites`: ham GGA alanları. Eksik alanlar `UNAVAILABLE`; gerçek sıfırlar korunur.
- `android_line_complete_elapsed_ns`: Android satır okumasının tamamlandığı monotonic zaman; GNSS ölçüm epoch'u veya tam RF varış zamanı değildir.
- `arrival_is_measurement_epoch=false` ve ham NMEA satırı.

GGA alanlarının tanımı: [Unicore N4 komut referansı, GGA bölümü](https://en.unicorecomm.com/uploads/file/Unicore%20Reference%20Commands%20Manual%20For%20N4%20High%20Precision%20Products_V2_EN_R1.13.pdf).

## 5. Değişiklik kapsamı

Firmware'de değiştirilenler: `platformio.ini`, `src/GnssUart.cpp`, `src/BluetoothLink.cpp`, `src/main.cpp`.

Firmware'de eklenenler: `include/GgaSentence.h`, `include/GgaForward.h`, `src/GgaForward.cpp`, `test/test_gga_sentence/test_main.cpp`.

Android'de değiştirilenler: `app/build.gradle.kts`, `MainActivity.kt`, `BluetoothSppService.kt`, `gga/GgaForwardService.kt`, `res/layout/activity_main.xml`.

Android'de eklenenler: `bluetooth/SppLineRouter.kt`, `bluetooth/SppLineRouterTest.kt`, `gga/GgaForwardServiceTest.kt` ve bu rapor.

Başlangıç yedeğinin hash karşılaştırmasında kaynak değişiklikleri bu kapsamla sınırlıdır. PPS, UART baud/pin ayarları, RTCM ayrıştırıcıları, RTCM COM1 yazma yolu, imzalama kaynakları ve mevcut VERSIONA tanılama kaynakları değiştirilmedi. FieldCapture ve bağımsız kamera/GNSS test projeleri değiştirilmedi.

Yeni firmware ortamı mevcut COM1 RTCM ortamını ve güvenlik bayraklarını miras alır. VERSIONA'nın `esp32dev_um980_bt_version_test` ortamı ayrı bir tanılama firmware'idir. Yeni GGA/COM1 firmware'i VERSIONA protokolünü eklemez; Android'de mevcut VERSIONA düğmeleri bu test için kullanılmamalıdır.

## 6. Bilgisayarda doğrulananlar

- `platformio run -e esp32dev_bt_rtcm_com1_gga_test`: başarılı.
- `gradlew.bat :app:assembleDebug :app:testDebugUnitTest`: başarılı.
- Android: 33 test, 0 hata, 0 başarısızlık. GGA checksum/alan ayrıştırma ve GGA ile ACK/NACK/diğer kontrol satırlarının ayrılması için 7 yeni test dahil.
- APK paket bilgisi yeni sürümü doğruladı; `zipalign -c -v 4` başarılı.
- Derlenen firmware içinde yeni sürüm kimliği doğrulandı.
- PlatformIO native testleri GCC/G++ bulunmadığı için derlenemedi; çalıştırılmış veya geçmiş sayılmamalıdır.

Kullanıcının 31 Ekim 2026 tarihli saha testi doğrulaması: `CORE-0.2.2-BT-GGA-COM1-TEST` ve `BRIDGE-0.2.5-GGA-TEST` kullanıldı; Android → SPP → ESP32 → UM980 COM1 RTCM aktarımı RTK FIX sağladı. UM980 COM2 → ESP32 → SPP → Android GGA akışı `BauxGga` Logcat'te görüldü; `fix_quality=4`, UTC saati ve koordinatlar doğru ayrıştırıldı. Bu rapor kullanıcı tarafından aktarılan sonuçları kaydeder; ham cihaz logları bu çalışma alanında incelenmedi.

## 7. Gerçek cihaz testi adımları

1. ESP32'yi USB veri kablosuyla bağlayın. Mevcut UM980 COM1/COM2 ve PPS bağlantılarını koruyun. Kontrol sırasında yalnız COM8/COM9 Bluetooth sanal portları görünüyordu; bunlar USB firmware yükleme portu olarak kullanılmamalıdır.
2. ESP32'nin gerçek USB COM portunu belirleyin. `ESP32_Rover_Core` klasöründe PlatformIO'nun `esp32dev_bt_rtcm_com1_gga_test` ortamından yükleme yapın. USB monitörü 115200 ile açın. `FW_VERSION=CORE-0.2.2-BT-GGA-COM1-TEST`, COM1 RTCM bilgisi ve `[BT-GGA] Enabled` satırını doğrulayın.
3. Yukarıdaki APK'yı A15'e kurun veya Android Studio'da Bridge projesinin `app` modülünü çalıştırın. Ekranda `BRIDGE-0.2.5-GGA-TEST` görünmelidir. Android 12+ için uygulamanın istediği Bluetooth / Yakındaki cihazlar bağlantı iznini verin; bu test telefonun kendi GNSS verisini kullanmaz.
4. Telefon Bluetooth ayarlarından `BAUX-ROVER` eşleştirmesini kullanın. Bilgisayar veya başka telefon aynı anda SPP bağlantısı açmasın. Bridge'de **Bağlan** düğmesine basın.
5. İlk aşamada yalnız GGA'yı gözleyin. Ayrı bir başlatma düğmesi gerekmez. Android Studio Logcat filtresi: `package:com.baux.roverbridge tag:BauxGga`. `GGA #...`, altı istenen alan ve ham NMEA görünmelidir.
6. En az 30 saniye kayıt alın. UTC saatinin ilerlediğini, koordinat/yükseklik alanlarını ve kalite/uydu sayısını kontrol edin. `fix_quality=4` RTK integer, `5` RTK float bilgisidir; `0` geçersiz konumdur. Kaliteyi bu test uygulaması üretmez, UM980'in GGA alanından aktarır.
7. GGA görüldükten sonra **PING Testi** ile yanıtı doğrulayın. Ardından mevcut çalışan yöntemle **RTCM Başlat** testini yapın. GGA akarken ACK/NACK, timeout, CRC ve bağlantı durumunu; USB'de `[RTCM]` sayaçlarını ve mevcut RTK durumunu inceleyin. ACK yalnız UART kuyruğuna yazmayı bildirir; UM980'in RTCM'yi kabul ettiğinin veya RTK FIX'in kanıtı değildir. FIX ayrıca mevcut GNSS durumundan doğrulanmalıdır.
8. Android `BauxGga` kayıtlarını, USB sürüm/GGA/RTCM/RTK kayıtlarını ve görülen hataları arşivleyin. Kullanıcı saha testi sonucunu doğruladı: GGA aktarımı ve alan ayrıştırması gözlendi; aynı testte COM1 RTCM aktarımı RTK FIX sağladı.

Bu çalışma oturumunda APK yüklemesi veya ESP32 flash işlemi yapılmadı; yukarıdaki donanım sonucu kullanıcı tarafından bildirildi. Fotoğraf eşleştirmesi ve sonraki entegrasyon aşaması eklenmedi.
