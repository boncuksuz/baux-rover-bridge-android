package com.baux.roverbridge.bluetooth

import java.io.IOException
import java.util.ArrayDeque

/** Owned only by BluetoothSppService's existing worker, including its idle poll. */
internal class SppLineRouter(private val onGga: (String, Long) -> Unit) {
    private val partial = StringBuilder()
    private val controls = ArrayDeque<String>()

    fun reset() { partial.clear(); controls.clear() }

    fun feed(value: Int, lineCompleteElapsedNs: Long): String? {
        if (value !in 0..127) throw IOException("Bluetooth yanıtında ASCII dışı bayt var.")
        if (value == '\r'.code) return null
        if (value == '\n'.code) {
            val line = partial.toString()
            partial.clear()
            if (line.startsWith("GGA|")) {
                onGga(line, lineCompleteElapsedNs)
                return null
            }
            return line
        }
        if (partial.length >= 640) throw IOException("Bluetooth yanıt satırı çok uzun.")
        partial.append(value.toChar())
        return null
    }

    fun rememberControl(line: String) {
        if (line.isEmpty()) return
        if (controls.size >= 32) throw IOException("Beklenmeyen Bluetooth kontrol satırları birikti.")
        controls.addLast(line)
    }
    fun pollControl(): String? = controls.pollFirst()
}
