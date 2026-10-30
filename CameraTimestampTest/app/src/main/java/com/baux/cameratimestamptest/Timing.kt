package com.baux.cameratimestamptest

/** Subtract integer nanoseconds first; never convert an absolute timestamp to Double. */
internal object Timing {
    fun delta(later: Long?, earlier: Long?, comparable: Boolean): Long? {
        if (!comparable || later == null || earlier == null || later <= 0 || earlier <= 0) return null
        return try { Math.subtractExact(later, earlier) } catch (_: ArithmeticException) { null }
    }
    fun exact(value: Long?): String? = value?.toString()
}

internal data class AnalysisFrame(
    val index: Long, val bindingId: String, val cameraId: String,
    val timestampNs: Long, val callbackNs: Long,
    val width: Int, val height: Int, val rotation: Int, val format: Int
)

internal data class FrameNeighbours(
    val before: AnalysisFrame?, val equal: AnalysisFrame?, val after: AnalysisFrame?
)

internal class AnalysisHistory(private val capacity: Int = 1800) {
    private val frames = ArrayDeque<AnalysisFrame>()
    @Synchronized fun add(frame: AnalysisFrame) {
        frames.addLast(frame)
        while (frames.size > capacity) frames.removeFirst()
    }
    @Synchronized fun neighbours(timestamp: Long, bindingId: String): FrameNeighbours {
        val candidates = frames.filter { it.bindingId == bindingId && it.timestampNs > 0 }
        return FrameNeighbours(
            candidates.filter { it.timestampNs < timestamp }.maxByOrNull { it.timestampNs },
            candidates.firstOrNull { it.timestampNs == timestamp },
            candidates.filter { it.timestampNs > timestamp }.minByOrNull { it.timestampNs }
        )
    }
}
