package com.baux.cameratimestamptest

import android.os.Build
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal fun JSONObject.ns(key: String, value: Long?): JSONObject =
    put(key, Timing.exact(value) ?: JSONObject.NULL)

internal fun AnalysisFrame.json(): JSONObject = JSONObject()
    .put("type", "analysis_frame").put("frame_index", index)
    .put("binding_id", bindingId).put("camera_id", cameraId)
    .ns("image_info_timestamp_ns", timestampNs).ns("callback_entry_elapsed_realtime_ns", callbackNs)
    .put("width", width).put("height", height).put("rotation_degrees", rotation).put("format", format)

internal class SessionStore(private val root: File) {
    val sessionId: String = UUID.randomUUID().toString()
    val directory: File = File(root, sessionId)
    @Volatile var analysisWriteError: String? = null
        private set

    init {
        check(directory.mkdirs()) { "Kayıt klasörü oluşturulamadı: $directory" }
        writeJson("session.json", JSONObject()
            .put("schema_version", 1).put("session_id", sessionId)
            .put("build_id", BuildConfig.BUILD_ID).put("camerax_version", BuildConfig.CAMERAX_VERSION)
            .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
            .put("android_release", Build.VERSION.RELEASE).put("android_sdk_int", Build.VERSION.SDK_INT)
            .put("created_system_wall_clock_utc", Instant.now().toString())
            .ns("created_elapsed_realtime_ns", SystemClock.elapsedRealtimeNanos())
            .put("ns_encoding", "Decimal strings; null means unavailable. Never parse absolute ns as floating point.")
            .put("utc_reference_measured", false))
    }

    fun writeJson(name: String, value: JSONObject) {
        val temp = File(directory, "$name.partial")
        FileOutputStream(temp).use { out ->
            out.write(value.toString(2).toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        check(temp.renameTo(File(directory, name))) { "JSON dosyası tamamlanamadı: $name" }
    }

    /** Time is taken after flush + fsync + close + final filename rename, not used as sensor time. */
    fun writeJpeg(id: String, bytes: ByteArray): Long {
        val temp = File(directory, "$id.jpg.partial")
        FileOutputStream(temp).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        check(temp.renameTo(File(directory, "$id.jpg"))) { "JPEG dosyası tamamlanamadı" }
        return SystemClock.elapsedRealtimeNanos()
    }

    @Synchronized fun appendAnalysis(frame: AnalysisFrame) {
        try {
            File(directory, "analysis.jsonl").appendText(frame.json().toString() + "\n", Charsets.UTF_8)
        } catch (e: Exception) {
            analysisWriteError = "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /** Capture is disabled while exporting. Lock yields a complete-line snapshot of analysis.jsonl. */
    @Synchronized fun exportAll(output: OutputStream) {
        ZipOutputStream(output).use { zip ->
            root.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { file ->
                zip.putNextEntry(ZipEntry(file.relativeTo(root).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("export.json"))
            zip.write(JSONObject().put("build_id", BuildConfig.BUILD_ID)
                .put("export_system_wall_clock_utc", Instant.now().toString())
                .put("note", "Snapshot of all local sessions. .partial files indicate incomplete writes; do not treat them as successful captures.")
                .toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }
}
