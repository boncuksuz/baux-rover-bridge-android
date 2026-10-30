package com.baux.cameratimestamptest

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.DynamicRange
import androidx.camera.core.ExperimentalImageCaptureOutputFormat
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var cameraText: TextView
    private lateinit var statusText: TextView
    private lateinit var timingText: TextView
    private lateinit var captureButton: Button
    private lateinit var exportButton: Button
    private lateinit var analysisToggle: CheckBox
    private lateinit var store: SessionStore
    private lateinit var recorder: CaptureRecorder
    private val history = AnalysisHistory()
    private val callbackExecutor = Executors.newSingleThreadExecutor()
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val io = ScheduledThreadPoolExecutor(1)
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var capture: ImageCapture? = null
    private var analysis: ImageAnalysis? = null
    private var details: CameraDetails? = null
    private var bindingId: String = ""
    private var analysisEnabled = false
    private var analysisNote = ""
    private var busy = false
    private var cameraReady = false

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else {
            statusText.text = "Kamera izni verilmedi. FOTOĞRAF ÇEK ile yeniden izin isteyebilirsiniz."
            refreshButtons()
        }
    }
    private val export = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode != RESULT_OK || uri == null) {
            busy = false
            refreshButtons()
        } else {
            statusText.text = "Kayıtlar ZIP dosyasına aktarılıyor…"
            io.execute {
                val message = try {
                    val output = contentResolver.openOutputStream(uri, "wt") ?: error("Dosya açılamadı")
                    output.use { store.exportAll(it) }
                    "ZIP kaydedildi: $uri"
                } catch (e: Exception) { "ZIP kaydedilemedi; eksik ZIP'i kullanmayın: ${e.message}" }
                runOnUiThread {
                    if (!isDestroyed) { statusText.text = message; busy = false; refreshButtons() }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        try {
            store = SessionStore(File(filesDir, "captures"))
            recorder = CaptureRecorder(store, history, io) { job, success, message ->
                runOnUiThread {
                    if (!isDestroyed) {
                        busy = false
                        statusText.text = message
                        statusText.setTextColor(if (success) Color.rgb(25, 85, 45) else Color.RED)
                        showTiming(job)
                        refreshButtons()
                    }
                }
            }
        } catch (e: Exception) {
            statusText.text = "Kayıt klasörü hazırlanamadı: ${e.message}"
            captureButton.isEnabled = false
            exportButton.isEnabled = false
            analysisToggle.isEnabled = false
            return
        }
        Log.i(LOG_TAG, "build=${BuildConfig.BUILD_ID} device=${Build.MANUFACTURER} ${Build.MODEL} Android=${Build.VERSION.RELEASE} session=${store.sessionId}")
        if (hasPermission()) startCamera() else permission.launch(Manifest.permission.CAMERA)
    }

    private fun buildUi() {
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        ViewCompat.setOnApplyWindowInsetsListener(outer) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)) }
        fun text(size: Float, value: String): TextView = TextView(this).apply {
            textSize = size; text = value; setTextColor(Color.rgb(23, 35, 50)); setPadding(0, dp(5), 0, dp(5))
            content.addView(this)
        }
        text(23f, "Camera Timestamp Test")
        text(14f, "${Build.MANUFACTURER} ${Build.MODEL} • Android ${Build.VERSION.RELEASE}\n${BuildConfig.BUILD_ID}")
        cameraText = text(16f, "Arka kamera hazırlanıyor…")
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
        }
        content.addView(previewView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(250)))
        analysisToggle = CheckBox(this).apply {
            text = "ImageAnalysis açık (ayrı kare kayıtları)"
            isChecked = true
            setOnCheckedChangeListener { _, _ -> if (!busy && provider != null) bindCamera() }
        }
        content.addView(analysisToggle)
        captureButton = Button(this).apply {
            text = "FOTOĞRAF ÇEK"
            isEnabled = false
            setOnClickListener {
                if (!hasPermission()) permission.launch(Manifest.permission.CAMERA)
                else if (capture == null) startCamera() else takePhoto()
            }
        }
        content.addView(captureButton)
        timingText = text(15f, "Son JPEG sensör timestamp: —\nİstek → sensör: —\nSensör → callback: —\nSensör → dosya: —")
        statusText = text(14f, "Henüz çekim yapılmadı.")
        exportButton = Button(this).apply {
            text = "KAYITLARI ZIP OLARAK KAYDET"
            setOnClickListener {
                busy = true
                refreshButtons()
                try {
                    export.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/zip"
                        putExtra(Intent.EXTRA_TITLE, "camera_timestamp_${Build.MODEL.replace(Regex("[^A-Za-z0-9_-]"), "_")}_${store.sessionId.take(8)}.zip")
                    })
                } catch (e: Exception) {
                    statusText.text = "Dosya seçici açılamadı: ${e.message}"
                    busy = false
                    refreshButtons()
                }
            }
        }
        content.addView(exportButton)
        text(13f, "Callback ve dosya yazım saatleri pozlama zamanı değildir. REALTIME kaynağında elapsedRealtime farkları hesaplanır. Analiz karesi ile JPEG'in aynı fiziksel kare olduğu varsayılmaz. Mutlak UTC hatası ölçülmez.")
        val scroll = ScrollView(this).apply { addView(content) }
        outer.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        setContentView(outer)
    }

    private fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        cameraReady = false
        captureButton.isEnabled = false
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (isDestroyed) return@addListener
            try { provider = future.get(); bindCamera() }
            catch (e: Exception) {
                statusText.text = "Kamera başlatılamadı: ${e.message}"
                capture = null
                refreshButtons()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class, ExperimentalImageCaptureOutputFormat::class)
    private fun bindCamera() {
        val p = provider ?: return
        cameraReady = false
        camera?.cameraInfo?.cameraState?.removeObservers(this)
        analysis?.clearAnalyzer()
        p.unbindAll()
        capture = null
        var fallback: String? = null
        fun bind(withAnalysis: Boolean) {
            val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
            val newBindingId = UUID.randomUUID().toString()
            val preview = Preview.Builder().setDynamicRange(DynamicRange.SDR).setTargetRotation(rotation).build()
            preview.setSurfaceProvider(previewView.surfaceProvider)
            val selector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                .setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE).build()
            val builder = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setOutputFormat(ImageCapture.OUTPUT_FORMAT_JPEG)
                .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                .setJpegQuality(100).setResolutionSelector(selector).setTargetRotation(rotation)
            Camera2Interop.Extender(builder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_ENABLE_ZSL, false)
                .setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED)
            val still = builder.build()
            val analysisCase = if (withAnalysis) ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build())
                .setTargetRotation(rotation).build() else null
            val bound = if (analysisCase != null)
                p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, still, analysisCase)
            else p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, still)
            val info = CameraDetails.read(bound.cameraInfo, getSystemService(CameraManager::class.java))
            camera = bound
            details = info
            bindingId = newBindingId
            capture = still
            analysis = analysisCase
            analysisEnabled = analysisCase != null
            analysisNote = fallback ?: if (withAnalysis) "ENABLED_KEEP_ONLY_LATEST_640x480_PREFERRED" else "DISABLED_BY_USER"
            var frameIndex = 0L
            analysisCase?.setAnalyzer(analysisExecutor) { image ->
                val callbackNs = SystemClock.elapsedRealtimeNanos()
                val frame = try {
                    AnalysisFrame(++frameIndex, newBindingId, info.id, image.imageInfo.timestamp,
                        callbackNs, image.width, image.height, image.imageInfo.rotationDegrees, image.format)
                } finally { image.close() }
                history.add(frame)
                store.appendAnalysis(frame)
            }
            val bindingReport = JSONObject().put("type", "camera_binding").put("binding_id", newBindingId)
                .put("camera", info.json).put("analysis_enabled", analysisEnabled).put("analysis_note", analysisNote)
                .put("build_id", BuildConfig.BUILD_ID).ns("bound_elapsed_realtime_ns", SystemClock.elapsedRealtimeNanos())
                .put("selected_image_capture_resolution", still.resolutionInfo?.resolution?.toString() ?: JSONObject.NULL)
            io.execute {
                try { store.writeJson("binding_$newBindingId.json", bindingReport) }
                catch (e: Exception) { Log.e(LOG_TAG, "Binding metadata save failed", e) }
            }
            cameraText.text = "Camera2 ID: ${info.id} • Arka kamera\nTimestamp kaynağı: ${info.source}\nJPEG: ${still.resolutionInfo?.resolution ?: "Çekimde raporlanacak"}\nAnaliz: ${if (analysisEnabled) "açık" else "kapalı"}"
            statusText.text = fallback ?: "Kamera bağlandı. Önizleme hazır olduğunda fotoğraf çekebilirsiniz."
            Log.i(LOG_TAG, bindingReport.toString())
            bound.cameraInfo.cameraState.observe(this) { state ->
                cameraReady = state.type == CameraState.Type.OPEN && state.error == null
                if (state.error != null) statusText.text = "Kamera hatası: ${state.error?.code} ${state.error?.cause?.message.orEmpty()}"
                refreshButtons()
            }
        }
        try {
            try { bind(analysisToggle.isChecked) }
            catch (e: IllegalArgumentException) {
                if (!analysisToggle.isChecked) throw e
                p.unbindAll()
                fallback = "ImageAnalysis olmadan yeniden bağlandı; üçlü akış desteklenmedi: ${e.message}"
                bind(false)
            }
        } catch (e: Exception) {
            p.unbindAll()
            capture = null
            statusText.text = "Kamera bağlanamadı: ${e.javaClass.simpleName}: ${e.message}"
            Log.e(LOG_TAG, "Camera binding failed", e)
        }
        refreshButtons()
    }

    private fun takePhoto() {
        if (busy || !cameraReady || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val still = capture ?: return
        val bound = camera ?: return
        // Re-query the currently used camera immediately before each capture.
        val current = try { CameraDetails.read(bound.cameraInfo, getSystemService(CameraManager::class.java)) }
        catch (e: Exception) { statusText.text = "Gerçek kamera ID'si okunamadı: ${e.message}"; return }
        if (current.id != details?.id) {
            statusText.text = "Kamera ID'si değişti; yeni kamera oturumu hazırlanıyor. Sonra tekrar çekin."
            bindCamera()
            return
        }
        still.targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
        val job = CaptureJob(current, bindingId, analysisEnabled, analysisNote, still.targetRotation, store.sessionId)
        busy = true
        statusText.setTextColor(Color.rgb(23, 35, 50))
        statusText.text = "Çekiliyor ve JPEG/JSON kaydediliyor…\n${job.id}"
        refreshButtons()
        val callback = object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureStarted() {
                val now = SystemClock.elapsedRealtimeNanos()
                job.startedCallbackNs = now
            }
            override fun onCaptureSuccess(image: ImageProxy) {
                val now = SystemClock.elapsedRealtimeNanos()
                recorder.accept(job, image, now)
            }
            override fun onError(exception: ImageCaptureException) {
                val now = SystemClock.elapsedRealtimeNanos()
                job.imageMetadata.ns("on_error_callback_elapsed_realtime_ns", now)
                    .put("capture_error_code", exception.imageCaptureError)
                recorder.fail(job, "CameraX ${exception.imageCaptureError}: ${exception.message}")
            }
        }
        try {
            job.requestNs = SystemClock.elapsedRealtimeNanos()
            still.takePicture(callbackExecutor, callback)
        } catch (e: Exception) { recorder.fail(job, "takePicture: ${e.message}") }
    }

    private fun showTiming(job: CaptureJob) {
        fun format(later: Long?, earlier: Long?): String {
            val delta = Timing.delta(later, earlier, job.camera.isRealtime)
            return if (delta == null) "Hesaplanamıyor (${job.camera.source} / eksik timestamp)"
            else "${String.format(Locale.US, "%.3f", delta / 1_000_000.0)} ms ($delta ns)"
        }
        timingText.text = "Son JPEG sensör timestamp: ${job.imageTimestampNs ?: "—"} ns\n" +
            "İstek → sensör: ${format(job.imageTimestampNs, job.requestNs)}\n" +
            "Sensör → callback: ${format(job.successCallbackNs, job.imageTimestampNs)}\n" +
            "Sensör → dosya: ${format(job.writeCompleteNs, job.imageTimestampNs)}\n" +
            "Boyut: ${job.imageMetadata.opt("width")} × ${job.imageMetadata.opt("height")}; format: ${job.imageMetadata.opt("format_name")}"
    }

    private fun refreshButtons() {
        if (!::captureButton.isInitialized) return
        captureButton.isEnabled = ::store.isInitialized && !busy && (cameraReady || !hasPermission() || capture == null)
        exportButton.isEnabled = ::store.isInitialized && !busy
        analysisToggle.isEnabled = ::store.isInitialized && !busy
    }

    override fun onDestroy() {
        analysis?.clearAnalyzer()
        provider?.unbindAll()
        // Drain callbacks before shutting down IO: in-flight jobs must be allowed to finish sidecars.
        callbackExecutor.shutdown()
        analysisExecutor.shutdown()
        Thread {
            callbackExecutor.awaitTermination(Long.MAX_VALUE, java.util.concurrent.TimeUnit.NANOSECONDS)
            analysisExecutor.awaitTermination(Long.MAX_VALUE, java.util.concurrent.TimeUnit.NANOSECONDS)
            // A queued JPEG write may schedule its delayed sidecar. Shut down only
            // after earlier writes have had the chance to enqueue their finalizers.
            io.execute { io.shutdown() }
        }.start()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
