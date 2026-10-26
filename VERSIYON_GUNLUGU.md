# BAU-X Rover Android Bridge — Versiyon Günlüğü

Bu günlük Android Bridge projesinin başarılı Faz 2 adımlarını kronolojik olarak kaydeder. Yeni bir başarıyı yazmak için Git commit'i gerekmez. Tüm saatler Berlin yerel saatiyle (`UTC+01:00`) verilmiştir. Faz 2, 2A ve 2B için aşağıdaki zamanlar Git commit kayıtlarından; Faz 2C'nin yerel doğrulama zamanı test sonucu dosyalarından alınmıştır. Gerçek donanım testlerinin kesin saati ayrıca kaydedilmediği için commit saatleri test saati olarak yorumlanmamalıdır. Masaüstü prototipi ve ESP32 firmware'i bu projenin sürümleri değildir.

## Faz 2 — GitHub başlangıç sürümü

**Git sürümü:** `a243115` — `Faz 2 Baseline: Android Bridge build verified`
**Başarı kayıt zamanı:** 26 Ekim 2026, 17:51:47 (`UTC+01:00`, commit zamanı)

1. Native Android/Kotlin ve Gradle proje yapısı oluşturuldu; Bluetooth, kimlik doğrulama, GGA ve RTCM servislerinin başlangıç sınıfları depoya alındı.
2. `.gitignore` ile Gradle/Android derleme çıktıları, yerel ayarlar, IDE dosyaları ve imza anahtarları Git dışında tutuldu.
3. Android Bridge derlemesi doğrulandı ve sonraki çalışmalar için başlangıç noktası GitHub'a kaydedildi.

**Sınır:** Bu sürüm, gerçek ESP32 ile çalışan SPP veya mTLS bağlantısı kanıtı değildi.

## Faz 2A — Bluetooth Classic SPP / PING–PONG

**Git sürümü:** `0ac23f1` — `Faz 2A: Android Bluetooth SPP PING PONG verified`
**Uygulama etiketi:** `BRIDGE-0.2.0-SPP-TEST`
**Başarı kayıt zamanı:** 26 Ekim 2026, 18:34:40 (`UTC+01:00`, commit zamanı; telefon testinin kesin saati bilinmiyor)

1. Önceden eşleştirilmiş `BAUX-ROVER` cihazı Android BluetoothAdapter üzerinden bulundu.
2. Standart SPP UUID ile güvenli RFCOMM soketi açıldı; Android 12+ `BLUETOOTH_CONNECT` izni ve eski sürüm uyumluluğu ele alındı.
3. Bağlanma, okuma ve yazma işlemleri UI iş parçacığı dışına taşındı; zaman aşımı, kopma, yeniden bağlanma ve kaynak temizliği eklendi.
4. Ekrana Bağlan ve PING Testi kontrolleri eklendi. ESP32'ye `PING` gönderilip `PONG` yanıtı gösterildi.
5. Kullanıcının bildirdiği gerçek telefon testinde Android → Bluetooth Classic SPP → ESP32 → PONG akışı başarılı oldu; sürüm GitHub'a kaydedildi.

**Sınır:** PING/PONG sonucu Bluetooth bağlantı şifrelemesinin bağımsız kanıtı değildir.

## Faz 2B — ESP32 RSA-PSS RemoteSigner

**Git sürümü:** `c3ac1e1` — `Faz 2B: Android ESP32 RSA-PSS signature verified`
**Uygulama etiketi:** `BRIDGE-0.2.1-RSA-TEST`
**Başarı kayıt zamanı:** 26 Ekim 2026, 18:57:06 (`UTC+01:00`, commit zamanı; cihaz testinin kesin saati bilinmiyor)

1. Faz 2A'daki tek Bluetooth SPP bağlantısı korunarak RSA-PSS Testi kontrolü eklendi.
2. Sabit test mesajının SHA-256 özeti, ESP32 protokolüne uygun `@RSIG:PSS256:<64 HEX>` isteğiyle gönderildi.
3. `RSIG_METRICS`, tanılama ve hata satırları imza yanıtından ayrıldı; eksik veya geçersiz yanıt başarısız sayıldı.
4. Gelen imza, uygulamadaki **açık** istemci sertifikası anahtarıyla RSA-PSS-SHA256, MGF1-SHA256 ve 32 bayt salt kullanılarak doğrulandı. Özel anahtar Android'e eklenmedi.
5. PING ve RSA isteklerinin aynı bağlantıda çakışması engellendi; sonuç ve imza süresi ekranda gösterildi.
6. Kullanıcının bildirdiği gerçek cihaz RSA-PSS doğrulaması başarılı oldu; sürüm GitHub'a kaydedildi.

**Sınır:** Bu test TLS/mTLS bağlantısı kurmuyordu.

## Faz 2C — Azure TLS 1.2 mTLS / NTRIP ölçümü

**GitHub sürümü:** `0fd4683` — `Faz 2C: Android Azure mTLS NTRIP verified`; `origin/main` ve GitHub `main` ile eşleştiği 26 Ekim 2026 tarihinde doğrulandı.
**Uygulama etiketi:** `BRIDGE-0.2.2-MTLS-TEST`
**Başlangıç sürümü:** Faz 2B, `c3ac1e1`
**Yerel doğrulama kayıt zamanı:** 26 Ekim 2026, 19:19:55 (`UTC+01:00`, test sonuç dosyalarının son yazılma zamanı)

1. Masaüstü Faz 1C Bouncy Castle TLS yaklaşımı incelendi. O prototipte ESP32 üzerinden CertificateVerify imzası, Azure TLS 1.2 mTLS, `ICY 200` ve 10 saniyede 14.478 RTCM gövde baytı kullanıcı tarafından raporlanmıştı. Bu sonuçlar Android testi değildir.
2. Android'e Bouncy Castle TLS bağımlılığı eklendi; sistem kriptografi sağlayıcısı değiştirilmedi. TLS 1.2 ve `RSA_PSS_RSAE_SHA256` seçildi.
3. Mevcut Bluetooth bağlantısı TLS imzalayıcısına verildi. CertificateVerify sırasında yalnızca imzalanacak 32 bayt SHA-256 özeti ESP32'ye gönderiliyor; dönen imza TLS akışında kullanılıyor. Yerel özel anahtar veya yerel imzalayıcıya geri dönüş yok.
4. Açık istemci sertifikası ve güvenilir CA sertifikası kullanıldı. Sunucu sertifikası PKIX zinciri ve ana makine kimliğiyle denetleniyor. Eski sunucu için IP-CN istisnası yalnızca yerel ayarda açıkça etkinse ve IP SAN yoksa uygulanıyor.
5. NTRIP GET yanıtı denetleniyor ve başarılı yanıtın RTCM gövde baytları 10 saniye sayılıyor. RTCM içeriği henüz ESP32/UM980'e iletilmiyor.
6. Ekrana TLS, NTRIP, bayt sayısı, handshake süresi ve ESP32 imza süresi eklendi. PING ve RSA-PSS Testi korundu; işlemler UI iş parçacığı dışında yürütülüyor.
7. Gerçek NTRIP ayarları Git dışında tutulan `ntrip-local.properties` dosyasından, Android belge seçicisiyle test anında okunuyor. Örnek dosya `ntrip-local.properties.example`; gerçek kimlik bilgisi APK'ye eklenmedi.
8. `testDebugUnitTest assembleDebug` başarılı oldu: 10 yerel test, 0 hata. Yerel mTLS testi geçici test anahtarları ve yerel TLS sunucusu kullandı. Debug APK üretildi.
9. Kullanıcı daha sonra gerçek Android telefon testinin başarılı olduğunu bildirdi: ESP32 RemoteSigner ile Azure TLS 1.2 mTLS, NTRIP `ICY 200` ve 10 saniyede 13.471 RTCM gövde baytı alındı. Bu Android sonucu, masaüstü prototipinde bildirilen 14.478 bayttan ayrı kayıttır.

### Faz 2C çökme düzeltmesi — yerel doğrulama

**Başarı kayıt zamanı:** 26 Ekim 2026, 20:03:54 (`UTC+01:00`, Gradle görevlerinin tamamlanma zamanı)

1. `AzureNtripProbe` artık TLS protokolünü ve TCP soketini açıkça kapatıyor. Kapanma sırasındaki ikincil `SocketException`, tamamlanmış ölçüm sonucunu değiştirmiyor; her kaynak için kapatma deneniyor.
2. NTRIP ölçüm penceresi tamamlanmadan bağlantı koparsa test başarısız kalıyor ve hata kullanıcıya iletiliyor. TLS handshake bilgisi, ICY/HTTP durumu, gerçek ölçüm süresi ve bayt sayısı sonuçta korunuyor.
3. Arka plan ve UI sonuç iletiminde beklenen çalışma zamanı hataları yakalanıyor; test tamamlanınca düğmelerin yeniden kullanılabilmesi için çalışma durumu sıfırlanıyor.
4. Soket kapanma hatasının ölçüm sonucunu ezmediğini sınayan birim testi eklendi.
5. `testDebugUnitTest assembleDebug` başarılı: 11 test, 0 hata; debug APK üretildi. Gerçek telefon testi henüz yapılmadı.

**Gerçek telefon doğrulaması:** Kullanıcı PING/PONG, RSA-PSS VALID, Azure TLS 1.2 mTLS, `ICY 200` ve 13.471 RTCM bayt/10 saniye sonuçlarını başarılı bildirdi. Bu Faz 2D-B çalışmasında yeniden test edilmedi.

**Açık güvenlik ve uyumluluk notları:** Bluetooth bağlantı şifrelemesi bağımsız doğrulanmadı. Etkin IP-CN istisnası IP SAN denetiminden daha zayıftır. Bu denemede sertifika iptal denetimi ve SPKI pin yoktur. `authorizationBase64` gizli bilgidir; yerel ayar dosyası Git'e veya paylaşılan depolamaya gönderilmemelidir.

## Sonraki kayıtlar

Her yeni başarılı aşama bu dosyaya tarih ve saat, saat dilimi, doğrulama türü (yerel derleme/test veya gerçek cihaz testi), somut sonuç ve varsa açık sınırlarıyla eklenir. Git commit'i kayıt için ön koşul değildir; kesin test saati bulunmuyorsa tahmin edilmez.

## Faz 2D-B — Android canlı RTCM aktarımı

**Durum:** Yerel kod, test ve APK hazır; gerçek telefon testi yapılmadı.
**Uygulama etiketi:** `BRIDGE-0.2.3-RTCM-STREAM`
**Yerel doğrulama zamanı:** 26 Ekim 2026, 21:24:55 (`UTC+01:00`)

1. Android Faz 2C ön koşulu doğrulandı: `0fd4683` hem `HEAD` hem `origin/main`; salt okunur `git ls-remote origin refs/heads/main` sorgusu da aynı commit'i döndürdü. Çalışma kopyasındaki önceden var olan `VERSIYON_GUNLUGU.md` dışında kod değişikliğinden önce temizdi.
2. Mevcut 10 saniyelik Azure mTLS/NTRIP ölçüm yolu korundu ve ayrı mod olarak kaldı. Canlı mod NTRIP başlığını gövdeden ayırıyor; gövde baytlarını sabit bellekli RTCM3 doğrulayıcıya geçiriyor.
3. RTCM3 D3 preamble, ayrılmış bitler, 10 bit uzunluk ve CRC24Q denetleniyor. Tam ve geçerli RTCM mesajı doğrulanmadan Bluetooth'a gönderilmiyor; TCP parçalı okumaları birleştiriliyor. 256 bayttan büyük mesajlar sıralı en fazla 256 baytlık BXR1 bloklarına bölünüyor.
4. ESP32'nin `BXR1` çerçevesi, little-endian sıra/uzunluk, IEEE reflected CRC32 (poly `0xEDB88320`, init/xorout `0xFFFFFFFF`) ve gerçek ACK/NACK biçimi uygulandı. Her bloktan sonra sequence, length ve CRC32 ACK doğrulanıyor. Zaman aşımı ve CRC32 NACK için en fazla üç deneme; UART_TIMEOUT veya diğer NACK'lerde aktarım sonlandırılıyor. Aynı sequence/payload tekrar yollanarak ESP32 duplicate ACK özelliği kullanılabiliyor.
5. TLS CertificateVerify sırasında tek Bluetooth okuyucusu RemoteSigner; handshake bitince aynı SPP servisinin tek okuyucusu RTCM ACK alıcısı oluyor. Canlı aktarım sırasında PING/RSA düğmeleri kapalı. Bluetooth stop sinyali ACK beklemesini iptal ediyor; TLS soketi kapatılıyor ve temizleme hataları sonucu ezmiyor. Mümkün olduğunda SPP bağlantısı açık bırakılıyor.
6. Arayüzde `RTCM Başlat`/`RTCM Durdur`, TLS/NTRIP, alınan/doğrulanan RTCM sayıları, CRC hatası, ACK'li BXR1 baytları, ACK/NACK/retry, B/s ve durum gösteriliyor. Ham RTCM, NTRIP kimlik bilgisi veya özel anahtar loglanmıyor; Android'e özel anahtar eklenmedi.
7. `testDebugUnitTest assembleDebug` başarılı: 20 test, 0 hata; `app/build/outputs/apk/debug/app-debug.apk` üretildi. Testler IEEE CRC32 vektörü ve BXR1 alanlarını, 256 bayt sınırını, NUL/newline payload'ını, ACK/NACK/duplicate/retry politikasını, CRC24Q vektörünü, parçalı RTCM birleştirmeyi, CRC hatası sonrası toparlanmayı, büyük mesajın blok sırasını ve stop/cleanup davranışını kapsıyor.

**Gerçek telefon testinde:** ESP32 `CORE-0.1.7-BT-RTCM-TEST` çalışırken önce SPP bağlanma, PING/PONG, RSA-PSS VALID ve 10 saniyelik ölçümü doğrulayın; sonra `RTCM Başlat` ile NTRIP ayar dosyasını seçin. Ekran sayaçlarını COM3 `rx_bytes`, `uart_queued_bytes`, `ack`, `nack`, `duplicate`, `errors` sayaçlarının test öncesi/sonrası farklarıyla karşılaştırın. Ardından `RTCM Durdur` ile durdurmayı, uygulamanın açık kaldığını ve SPP'nin yeniden bağlanmadan korunup korunmadığını kontrol edin.

**Sınırlar:** ACK yalnızca ESP32 Serial2 TX kuyruğuna yazma başarısını gösterir; UM980'in fiziksel alımını veya RTCM çözümünü kanıtlamaz. Bir RTCM mesajı birden fazla BXR1 bloğuna bölünür; aktarım ortasında hata olursa UM980'e kısmi mesaj gitmiş olabilir ve sonraki RTCM CRC bunu reddetmelidir. Bluetooth şifrelemesi bağımsız doğrulanmadı. Firmware yüklenmedi ve gerçek telefon testi yapılmadı.
