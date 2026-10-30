package com.baux.cameratimestamptest

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import org.json.JSONArray
import org.json.JSONObject

internal data class CameraDetails(
    val id: String, val source: String, val sourceRaw: Int?, val json: JSONObject
) {
    val isRealtime: Boolean get() = source == "REALTIME"

    companion object {
        @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
        fun read(info: CameraInfo, manager: CameraManager): CameraDetails {
            // This is the CameraX-bound camera, not an assumed ID such as "0".
            val id = Camera2CameraInfo.from(info).cameraId
            val result = JSONObject().put("camera_id", id)
            var sourceRaw: Int? = null
            var source = "OKUNAMIYOR"
            try {
                val c = manager.getCameraCharacteristics(id)
                sourceRaw = c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
                source = when (sourceRaw) {
                    CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME -> "REALTIME"
                    CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN -> "UNKNOWN"
                    else -> "OKUNAMIYOR"
                }
                result.put("lens_facing_raw", c.get(CameraCharacteristics.LENS_FACING) ?: JSONObject.NULL)
                result.put("hardware_level_raw", c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: JSONObject.NULL)
                result.put("physical_camera_ids", if (Build.VERSION.SDK_INT >= 28) JSONArray(c.physicalCameraIds.sorted()) else JSONObject.NULL)
                result.put("physical_sensor_selected", "NOT_FORCED_OR_INDEPENDENTLY_IDENTIFIED")
                result.put("query_error", if (source == "OKUNAMIYOR") "Timestamp source missing or unrecognized" else JSONObject.NULL)
            } catch (e: Exception) {
                result.put("query_error", "${e.javaClass.simpleName}: ${e.message}")
            }
            result.put("timestamp_source", source).put("timestamp_source_raw", sourceRaw ?: JSONObject.NULL)
            return CameraDetails(id, source, sourceRaw, result)
        }
    }
}
