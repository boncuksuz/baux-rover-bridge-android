package com.baux.cameratimestamptest

import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal const val LOG_TAG = "CameraTimestampTest"

internal class CaptureJob(
    val camera: CameraDetails, val bindingId: String, val analysisEnabled: Boolean,
    val analysisNote: String, val targetRotation: Int, val sessionId: String
) {
    val id: String = UUID.randomUUID().toString()
    val wallClockLabel: String = Instant.now().toString()
    var requestNs: Long? = null
    var startedCallbackNs: Long? = null
    var imageTimestampNs: Long? = null
    var successCallbackNs: Long? = null
    var writeCompleteNs: Long? = null
    val imageMetadata = JSONObject()

    fun json(): JSONObject = JSONObject()
        .put("schema_version", 1).put("type", "jpeg_capture")
        .put("capture_id", id).put("session_id", sessionId).put("binding_id", bindingId)
        .put("build_id", BuildConfig.BUILD_ID).put("camerax_version", BuildConfig.CAMERAX_VERSION)
        .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
        .put("android_release", Build.VERSION.RELEASE).put("android_sdk_int", Build.VERSION.SDK_INT)
        .put("request_system_wall_clock_label_utc", wallClockLabel)
        .put("wall_clock_label_is_sensor_time", false)
        .put("camera", camera.json).put("image_proxy", imageMetadata)
        .put("settings", JSONObject()
            .put("capture_mode", "MINIMIZE_LATENCY").put("output_format_requested", "JPEG")
            .put("jpeg_quality_requested", 100).put("flash", "OFF")
            .put("preview_dynamic_range", "SDR").put("extensions_enabled", false)
            .put("zsl_capture_mode_enabled", false).put("camera2_enable_zsl_requested", false)
            .put("camera2_scene_mode_requested", "DISABLED")
            .put("resolution_strategy", "HIGHEST_AVAILABLE; high-resolution sizes allowed, 4:3 preferred")
            .put("target_rotation", targetRotation)
            .put("vendor_internal_multiframe_disabled_verified", false))
        .put("timing", JSONObject()
            .ns("request_before_take_picture_elapsed_realtime_ns", requestNs)
            .ns("on_capture_started_callback_elapsed_realtime_ns", startedCallbackNs)
            .ns("image_info_timestamp_ns", imageTimestampNs)
            .ns("on_capture_success_callback_elapsed_realtime_ns", successCallbackNs)
            .ns("jpeg_write_complete_elapsed_realtime_ns", writeCompleteNs)
            .put("callback_times_are_exposure_times", false)
            .put("write_complete_definition", "After JPEG flush, fsync, close and rename; not physical exposure time")
            .put("on_capture_started_status", if (startedCallbackNs == null) "NOT_OBSERVED" else "CALLBACK_ENTRY_OBSERVED"))
        .put("deltas", JSONObject()
            .put("comparable_to_elapsed_realtime", camera.isRealtime)
            .ns("sensor_minus_request_ns", Timing.delta(imageTimestampNs, requestNs, camera.isRealtime))
            .ns("success_callback_minus_sensor_ns", Timing.delta(successCallbackNs, imageTimestampNs, camera.isRealtime))
            .ns("jpeg_write_complete_minus_sensor_ns", Timing.delta(writeCompleteNs, imageTimestampNs, camera.isRealtime))
            .put("note", "Signed integer differences; UNKNOWN/unreadable source produces null. Negative deltas are retained for review."))
        .put("camera2_capture_result_comparison", JSONObject()
            .put("status", "NOT_PERFORMED_NO_INDEPENDENT_REQUEST_ID_MATCH")
            .put("sensor_timestamp_ns", JSONObject.NULL)
            .put("note", "This build has no independent mapping from the CameraX capture ID to a Camera2 request/result. Timestamp equality or callback proximity alone is not used as proof. Separate follow-up test required."))
        .put("absolute_utc_error_measured", false)
        .put("association_basis", "JPEG bytes and metadata read from the same OnImageCapturedCallback ImageProxy; no separate analysis frame substituted")
        .put("ns_encoding", "Decimal strings; parse with BigInteger/Long, never floating point")
}

internal class CaptureRecorder(
    private val store: SessionStore,
    private val history: AnalysisHistory,
    private val io: ScheduledThreadPoolExecutor,
    private val complete: (CaptureJob, Boolean, String) -> Unit
) {
    // IO executor owns this map. Compare only within a single camera binding/session.
    private val previous = mutableMapOf<String, Pair<String, Long>>()

    fun accept(job: CaptureJob, image: ImageProxy, callbackNs: Long) {
        job.successCallbackNs = callbackNs
        var bytes: ByteArray? = null
        var error: String? = null
        try {
            job.imageTimestampNs = image.imageInfo.timestamp
            job.imageMetadata.ns("timestamp_ns", image.imageInfo.timestamp)
                .put("rotation_degrees", image.imageInfo.rotationDegrees)
                .put("format", image.format)
                .put("format_name", when (image.format) { ImageFormat.JPEG -> "JPEG"; 4101 -> "JPEG_R"; else -> "UNEXPECTED" })
                .put("width", image.width).put("height", image.height)
                .put("crop_rect", image.cropRect.toShortString())
                .put("plane_count", image.planes.size)
            require(image.format == ImageFormat.JPEG) {
                "Format ${image.format} desteklenmiyor (JPEG_R dahil); JPEG dosyası üretilmedi."
            }
            require(image.planes.size == 1) { "JPEG plane sayısı beklenmiyor: ${image.planes.size}" }
            val buffer = image.planes[0].buffer.duplicate()
            buffer.rewind()
            bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
        } catch (e: Exception) {
            error = "${e.javaClass.simpleName}: ${e.message}"
        } finally {
            image.close()
        }
        val capturedBytes = bytes
        val capturedError = error
        io.execute {
            if (capturedError != null || capturedBytes == null) {
                saveFailure(job, capturedError ?: "JPEG buffer alınamadı")
                return@execute
            }
            try {
                require(capturedBytes.size >= 4 && capturedBytes[0] == 0xff.toByte() && capturedBytes[1] == 0xd8.toByte()) {
                    "JPEG başlangıç işareti geçersiz"
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(capturedBytes, 0, capturedBytes.size, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outMimeType == "image/jpeg") {
                    "JPEG boyut/format bilgisi çözülemedi"
                }
                // No rotation, crop, recompression, EXIF edit or timestamp injection into JPEG bytes.
                job.writeCompleteNs = store.writeJpeg(job.id, capturedBytes)
                val digest = MessageDigest.getInstance("SHA-256").digest(capturedBytes)
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                val record = job.json().put("status", "JPEG_AND_METADATA_SAVED")
                    .put("jpeg", JSONObject().put("filename", "${job.id}.jpg")
                        .put("byte_count", capturedBytes.size).put("sha256", digest)
                        .put("encoded_width", bounds.outWidth).put("encoded_height", bounds.outHeight)
                        .put("bytes_from_same_image_proxy", true).put("bytes_transformed_by_app", false))
                // Allow delivered analysis frames after the JPEG timestamp to reach the history.
                // Missing neighbours after the bounded window remain explicitly missing.
                io.schedule({ finalizeRecord(job, record) }, if (job.analysisEnabled) 1500 else 0, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                saveFailure(job, "${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    fun fail(job: CaptureJob, message: String) { io.execute { saveFailure(job, message) } }

    private fun finalizeRecord(job: CaptureJob, record: JSONObject) {
        try {
            val timestamp = job.imageTimestampNs
            val neighbours = if (timestamp != null && timestamp > 0 && job.analysisEnabled && job.camera.isRealtime)
                history.neighbours(timestamp, job.bindingId) else FrameNeighbours(null, null, null)
            fun neighbour(frame: AnalysisFrame?): Any = frame?.json()
                ?.ns("analysis_minus_jpeg_timestamp_ns", Timing.delta(frame.timestampNs, timestamp, job.camera.isRealtime)) ?: JSONObject.NULL
            record.put("analysis", JSONObject()
                .put("enabled", job.analysisEnabled).put("configuration_note", job.analysisNote)
                .put("log_filename", if (job.analysisEnabled) "analysis.jsonl" else JSONObject.NULL)
                .put("log_write_error", store.analysisWriteError ?: JSONObject.NULL)
                .put("bracketing_status", when {
                    !job.analysisEnabled -> "DISABLED"
                    !job.camera.isRealtime -> "NOT_COMPARED_SOURCE_NOT_REALTIME"
                    timestamp == null || timestamp <= 0 -> "INVALID_JPEG_TIMESTAMP"
                    neighbours.before != null && neighbours.after != null -> "OBSERVED_BEFORE_AND_AFTER"
                    else -> "INCOMPLETE_OBSERVED_WINDOW"
                })
                .put("nearest_observed_before", neighbour(neighbours.before))
                .put("equal_timestamp_observed", neighbour(neighbours.equal))
                .put("nearest_observed_after", neighbour(neighbours.after))
                .ns("snapshot_elapsed_realtime_ns", SystemClock.elapsedRealtimeNanos())
                .put("history_capacity_frames", 1800).put("post_write_wait_ms", if (job.analysisEnabled) 1500 else 0)
                .put("same_physical_frame_claimed", false)
                .put("note", "Nearest among delivered frames in bounded history; KEEP_ONLY_LATEST may skip frames. Equal timestamps do not independently prove physical-frame identity."))
            val prior = previous[job.bindingId]
            val delta = Timing.delta(timestamp, prior?.second, true)
            record.put("continuity", JSONObject()
                .put("scope", "Current process session and camera binding only; no reboot comparison")
                .put("previous_capture_id", prior?.first ?: JSONObject.NULL)
                .ns("previous_jpeg_timestamp_ns", prior?.second)
                .ns("current_minus_previous_jpeg_timestamp_ns", delta)
                .put("status", when {
                    timestamp == null || timestamp <= 0 -> "INVALID_TIMESTAMP"
                    prior == null -> "FIRST_CAPTURE_IN_BINDING"
                    delta == null -> "UNAVAILABLE"
                    delta <= 0 -> "NON_INCREASING_REVIEW_REQUIRED"
                    else -> "INCREASING"
                })
                .put("note", "Increasing timestamps alone do not rule out forward clock jumps or prove absolute accuracy."))
            record.put("timestamp_status", if (timestamp != null && timestamp > 0) "PRESENT_POSITIVE" else "INVALID_NON_POSITIVE")
            store.writeJson("${job.id}.json", record)
            if (timestamp != null && timestamp > 0) previous[job.bindingId] = job.id to timestamp
            Log.i(LOG_TAG, "capture=${job.id} JPEG+JSON saved sensor_ns=$timestamp source=${job.camera.source}")
            complete(job, true, "JPEG + JSON kaydedildi\n${job.id}\nAnaliz: ${record.getJSONObject("analysis").getString("bracketing_status")}\nSüreklilik: ${record.getJSONObject("continuity").getString("status")}")
        } catch (e: Exception) {
            saveFailure(job, "JSON tamamlanamadı: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun saveFailure(job: CaptureJob, reason: String) {
        var message = reason
        try {
            store.writeJson("${job.id}.error.json", job.json().put("status", "FAILED")
                .put("error", reason).put("jpeg_write_completed", job.writeCompleteNs != null))
        } catch (e: Exception) { message += "; hata raporu da yazılamadı: ${e.message}" }
        Log.e(LOG_TAG, "capture=${job.id} $message")
        complete(job, false, "HATA: $message\nCapture ID: ${job.id}")
    }
}
