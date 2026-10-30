# BAU-X — Camera Timestamp Test

Build ID: **CAMERA-TIMESTAMP-TEST-0.1.0**

Bağımsız Android/Kotlin test uygulaması. Paket: `com.baux.cameratimestamptest`.
İlk cihaz Samsung Galaxy A15 5G, ardından aynı APK ile Honor Magic5 Lite.
Bu sürüm için gerçek cihaz sonuçları henüz alınmadı.

## Kurulum ve ilk çekimler

1. APK'yı telefona yükleyin: `app/build/outputs/apk/debug/app-debug.apk`.
2. **Camera Timestamp Test** uygulamasını açın ve **Kamera** iznini verin.
3. Build ID, Camera2 ID ve timestamp kaynağını kontrol edin. `REALTIME`, `UNKNOWN` ve `OKUNAMIYOR` ayrı gösterilir. Gerçek kaynak her çekim öncesinde yeniden sorgulanır.
4. Aydınlık bir ortamda önizlemenin oturmasını bekleyin. **FOTOĞRAF ÇEK** düğmesine basın. JPEG + JSON kaydı tamamlandıktan sonra birkaç saniye arayla 3–5 çekim yapın.
5. **KAYITLARI ZIP OLARAK KAYDET** ile Downloads gibi erişilebilir bir yere dışa aktarın. JPEG, JSON ve analiz JSONL dosyaları birlikte aktarılır. ZIP, önceki uygulama açılışlarında oluşan yerel oturumları da içerir.
6. İlk inceleme için A15 ZIP dosyasını kullanın; daha sonra Honor ile aynı işlemleri yapın.

Yalnız kamera izni gerekir. Dosya dışa aktarımı Android dosya seçicisini kullanır. Konum, mikrofon, Bluetooth, ağ veya tüm dosyalara erişim izni gerekmez. APK'yı dosya yöneticisinden kuruyorsanız o dosya yöneticisi için Android'in “Bilinmeyen uygulama yükleme” izni gerekebilir.

Uygulama içindeki kayıtlar `files/captures/<session_id>/` altında tutulur. Uygulamayı kaldırmadan veya verilerini temizlemeden önce ZIP olarak kaydedin. Galeriye otomatik eklenmez. Debug sürümünde Android Studio Device Explorer ile `/data/data/com.baux.cameratimestamptest/files/captures/` üzerinden de alınabilir.

## Android Studio / derleme

Android Studio'da **bu `CameraTimestampTest` klasörünü ayrı proje olarak açın**. Gradle JDK olarak 17 seçin, SDK Platform 35'i yükleyin, Gradle senkronizasyonunu tamamlayın. `app` yapılandırmasını seçip A15 üzerinde Run kullanın.

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

`local.properties` yalnız yerel SDK yolunu içerir ve Git dışında tutulur. Bağımlılıklar CameraX 1.4.2, Kotlin 1.9.24, AGP 8.5.2 ve Gradle 8.13 olarak sabitlenmiştir. AGP 8.5.2'nin compileSdk 35 için destek aralığı uyarısı vermesi mümkündür.

Logcat etiketi: `CameraTimestampTest`. Uygulama açılışında Build ID, cihaz ve oturum; kamera bağlanınca ID/kaynak; her çekimde capture ID ve kayıt sonucu yazılır. Tam ölçümler JSON dosyalarındadır.

## JPEG ve timestamp ilişkisinin kaydı

Her `takePicture()` çağrısına UUID capture ID atanır. `OnImageCapturedCallback.onCaptureSuccess()` ile gelen **aynı ImageProxy** üzerinden timestamp, rotationDegrees, format, boyutlar ve JPEG plane baytları alınır. Görüntü `finally` içinde kapatılır. JPEG uygulama tarafından yeniden kodlanmaz, döndürülmez, kırpılmaz veya EXIF düzenlemesine uğramaz.

Dosya çifti:

```text
<session_id>/
  session.json
  binding_<binding_id>.json
  <capture_id>.jpg
  <capture_id>.json
  analysis.jsonl
```

JSON içinde aynı capture ID, JPEG dosya adı, bayt sayısı ve SHA-256 özeti bulunur. Böylece dışa aktarılan JPEG'in doğru sidecar ile ilişkisi kontrol edilebilir. `JPEG_R` veya beklenmeyen formatta `.jpg` üretilmez; format ve hata `<capture_id>.error.json` içinde kaydedilir. Başarısız yazımda `.partial` dosyası kalabilir; bu dosya tamamlanmış çekim sayılmaz. JSON kaydı başarısızsa ekranda başarı gösterilmez.

SHA-256 ve ortak UUID, dosya/metadata ilişkisinin uygulama tarafındaki izini sağlar. Kamera HAL'inin fiziksel pozlama zamanına karşı doğruluğunu bağımsız olarak ölçmez.

## Kamera ayarları ve sınırlar

- CameraX `Preview + ImageCapture`, arka kamera seçimi. ID, bağlı `Camera.cameraInfo` üzerinden Camera2 interop ile alınır; `0` varsayılmaz. Mantıksal kameranın fiziksel ID listesi kaydedilir, fiilen pozlayan fiziksel sensör ayrıca doğrulanmaz.
- Standart `OUTPUT_FORMAT_JPEG`, JPEG quality 100, flaş kapalı, preview SDR, `CAPTURE_MODE_MINIMIZE_LATENCY`.
- ZSL capture mode kullanılmaz; Camera2 `CONTROL_ENABLE_ZSL=false`, scene mode disabled istenir. HDR/Night Extensions ve Ultra HDR JPEG_R etkinleştirilmez. Üretici içindeki tüm çok kareli işlem yollarının kapalı olduğu iddia edilmez; otomatik pozlama/odak/beyaz dengesi devam eder.
- 4:3 tercihli en yüksek kullanılabilir JPEG çözünürlüğü istenir; yüksek çözünürlük listesine izin verilir. Nihai boyut CameraX'in desteklenen akış birleşimine bağlıdır. Hem ImageProxy hem kodlanmış JPEG boyutları raporlanır; üreticinin özel kamera uygulamasındaki maksimum MP modu garanti edilmez.
- İstek → sensör, sensör → callback, sensör → dosya farkları yalnız sorgu `REALTIME` dönerse hesaplanır. `UNKNOWN`/okunamayan kaynakta ham timestamp korunur, bu farklar `null` olur. Negatif farklar mutlak değere çevrilmez; incelenmek üzere korunur.

## JSON zaman alanları

**Bütün nanosecond timestamp ve farkları ondalık metin olarak yazılır.** JSON `null`, alanın elde edilemediği veya karşılaştırmanın uygun olmadığı anlamına gelir. JavaScript `Number`/Double kullanmadan BigInt, Python `int`, Java/Kotlin `Long` veya `BigInteger` ile okuyun.

| `timing` alanı | Anlamı |
| --- | --- |
| `request_before_take_picture_elapsed_realtime_ns` | `takePicture()` çağrısından hemen önce Android monotonic saat |
| `on_capture_started_callback_elapsed_realtime_ns` | `onCaptureStarted()` fonksiyonuna giriş zamanı |
| `image_info_timestamp_ns` | Aynı JPEG ImageProxy içindeki `imageInfo.timestamp` |
| `on_capture_success_callback_elapsed_realtime_ns` | `onCaptureSuccess()` giriş zamanı; bayt kopyalama/yazımdan önce |
| `jpeg_write_complete_elapsed_realtime_ns` | JPEG flush + fsync + close + son dosya adına geçiş tamamlandıktan sonra |

Callback girişleri, thread zamanlaması ve kamera işlem hattı gecikmelerini içerir; fiziksel pozlama timestamp'i olarak kullanılmaz. Dosya yazım timestamp'i JPEG'in sensör timestamp'ini değiştirmez. UTC biçimli sistem saati etiketi yalnız tanılama etiketi olarak tutulur; GNSS UTC karşılığı değildir.

`continuity`, aynı uygulama oturumu ve kamera bağlaması içindeki bir önceki JPEG ile signed timestamp farkını ve artış durumunu kaydeder. Kamera yeniden bağlandığında yeni baseline başlar. Artan timestamp, ileri sıçramaların olmadığını tek başına kanıtlamaz. Rebootlar arasında karşılaştırma yapılmaz.

## Ayrı analiz akışı

ImageAnalysis varsayılan açık; 640×480 tercihli, `KEEP_ONLY_LATEST` akışı kullanılır. Yalnız metadata kaydedilir; analiz görüntüsü JPEG yerine kullanılmaz. Her teslim edilen analiz karesi için timestamp, callback zamanı, kamera/binding ID, boyut ve rotation `analysis.jsonl` içine yazılır. Bu akış tüm sensör karelerini içerme garantisi vermez.

JPEG yazımından sonra 1,5 saniye daha analiz verisi beklenir. Son 1800 gözlenen kare içinde JPEG timestamp'inden hemen önceki, eşit timestamp'li ve hemen sonraki analiz kareleri ayrı bulunur. JSON `analysis_minus_jpeg_timestamp_ns` farkını verir. Eşit timestamp bulunsa bile fiziksel kare özdeşliği doğrulanmış sayılmaz. Önce/sonra karesi yoksa `INCOMPLETE_OBSERVED_WINDOW` yazılır; veri uydurulmaz. `REALTIME` dışındaki kaynaklarda bu karşılaştırma yapılmaz.

Üç kullanım senaryosu birlikte bağlanamazsa analiz kapalı yeniden denenir ve neden kaydedilir. Performans sorunu varsa ekrandaki ImageAnalysis kutusunu kapatıp JPEG testine devam edilebilir. Analiz günlüğü yazma hatası sidecar içinde ayrıca raporlanır.

## Camera2 CaptureResult ve FieldCapture kapsamı

Bu sürüm, `ImageProxy` ile bağımsız bir Camera2 `CaptureRequest`/`CaptureResult` arasında doğrulanmış request kimliği eşleştirmesi kurmaz. Bu yüzden `camera2_capture_result_comparison.status` açıkça `NOT_PERFORMED_NO_INDEPENDENT_REQUEST_ID_MATCH` olur. Yakın callback zamanı veya eşit timestamp bulmak, bağımsız `SENSOR_TIMESTAMP` karşılaştırması yapılmış gibi sunulmaz. Bu karşılaştırma ayrı sonraki testtir.

FieldCapture kaynak kodu bu görevde incelenmedi. Bu uygulamadaki JPEG/metadata ilişkisi FieldCapture'ın kendi fotoğraf işleme, yeniden kodlama, dosya taşıma ve metadata akışını doğrulamaz. Gerçek cihaz çıktıları gelmeden zaman doğruluğu veya FieldCapture entegrasyonu hakkında başarı iddiası yapılmaz. Test bağımsız fiziksel pozlama/UTC referansına karşı mutlak hatayı ölçmez.

## Resmî kaynaklar

- [ImageCapture.OnImageCapturedCallback — callback ve JPEG/JPEG_R sözleşmesi](https://developer.android.com/reference/androidx/camera/core/ImageCapture.OnImageCapturedCallback)
- [ImageInfo — timestamp ve rotation](https://developer.android.com/reference/androidx/camera/core/ImageInfo)
- [Camera2CameraInfo — bağlı kamera ID'si](https://developer.android.com/reference/androidx/camera/camera2/interop/Camera2CameraInfo)
- [CameraMetadata — REALTIME timestamp kaynağı](https://developer.android.com/reference/android/hardware/camera2/CameraMetadata#SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME)
- [ResolutionSelector — çözünürlük seçimi](https://developer.android.com/reference/androidx/camera/core/resolutionselector/ResolutionSelector)
- [CameraX ZSL yapılandırması](https://developer.android.com/media/camera/camerax/take-photo/zsl)

Bu proje diğer BAU-X projelerine derleme veya çalışma zamanı bağımlılığı içermez.
