package com.baux.roverbridge.gga

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Rover metin protokol cercevelerinden NMEA GGA verisini ayiklar.
 */
class GgaForwardService {
    /**
     * `GGA|$GPGGA,...` bicimindeki satirlari parse eder ve ham NMEA kismini
     * doner. Girdi gecerli bir GGA cercevesi degilse null doner.
     */
    fun extractRawGga(line: String): String? {
        if (!line.startsWith("GGA|")) return null
        val idx = line.indexOf("|$")
        if (idx < 0) return null
        return line.substring(idx + 1)
    }

    data class Observation(
        val raw: String, val utcTimeOfDay: String?, val latitude: BigDecimal?,
        val longitude: BigDecimal?, val altitudeMslMeters: BigDecimal?,
        val fixQuality: Int?, val satellites: Int?, val androidLineCompleteElapsedNs: Long
    ) {
        fun summary(): String = "gga_utc_time_of_day=${utcTimeOfDay ?: "UNAVAILABLE"} utc_date=UNAVAILABLE " +
            "latitude_deg=${latitude?.toPlainString() ?: "UNAVAILABLE"} " +
            "longitude_deg=${longitude?.toPlainString() ?: "UNAVAILABLE"} " +
            "altitude_msl_m=${altitudeMslMeters?.toPlainString() ?: "UNAVAILABLE"} " +
            "fix_quality=${fixQuality ?: "UNAVAILABLE"} satellites=${satellites ?: "UNAVAILABLE"} " +
            "android_line_complete_elapsed_ns=$androidLineCompleteElapsedNs arrival_is_measurement_epoch=false"
    }

    /** NMEA UTC has time of day only. Never infer its date from Android arrival time. */
    fun parse(line: String, androidLineCompleteElapsedNs: Long): Observation {
        val raw = extractRawGga(line) ?: throw IllegalArgumentException("NOT_GGA_ENVELOPE")
        require(raw.length in 10..191 && raw.all { it.code in 32..126 }) { "INVALID_NMEA_LENGTH_OR_ASCII" }
        require(raw.take(7).matches(Regex("\\$[A-Z]{2}GGA,"))) { "NOT_GGA_SENTENCE" }
        val star = raw.indexOf('*')
        require(star == raw.length - 3) { "MISSING_OR_INVALID_CHECKSUM_SUFFIX" }
        val checksum = raw.substring(star + 1).toIntOrNull(16)
            ?: throw IllegalArgumentException("INVALID_CHECKSUM_HEX")
        var actual = 0
        raw.substring(1, star).forEach { actual = actual xor it.code }
        require(checksum == actual) { "CHECKSUM_MISMATCH" }
        val fields = raw.substring(0, star).split(',')
        require(fields.size >= 15) { "MISSING_GGA_FIELDS" }
        val utc = fields[1].takeIf { it.isNotEmpty() }?.let { value ->
            require(value.matches(Regex("[0-9]{6}(?:\\.[0-9]{1,9})?"))) { "INVALID_UTC_FORMAT" }
            require(value.substring(0, 2).toInt() in 0..23 && value.substring(2, 4).toInt() in 0..59 &&
                value.substring(4, 6).toInt() in 0..60) { "INVALID_UTC_RANGE" }
            "${value.take(2)}:${value.substring(2, 4)}:${value.substring(4)}"
        }
        fun unsigned(field: Int): Int? = fields[field].takeIf { it.isNotEmpty() }?.let {
            require(it.all(Char::isDigit)) { "INVALID_UNSIGNED_FIELD_$field" }
            it.toIntOrNull()?.takeIf { n -> n in 0..255 }
                ?: throw IllegalArgumentException("OUT_OF_RANGE_FIELD_$field")
        }
        val altitude = fields[9].takeIf { it.isNotEmpty() }?.let {
            require(fields[10] == "M") { "ALTITUDE_UNIT_NOT_METERS" }
            require(it.matches(Regex("-?[0-9]+(?:\\.[0-9]+)?"))) { "INVALID_ALTITUDE" }
            it.toBigDecimal()
        }
        return Observation(raw, utc, coordinate(fields[2], fields[3], true),
            coordinate(fields[4], fields[5], false), altitude, unsigned(6), unsigned(7), androidLineCompleteElapsedNs)
    }

    private fun coordinate(value: String, direction: String, latitude: Boolean): BigDecimal? {
        if (value.isEmpty() && direction.isEmpty()) return null
        val degreesDigits = if (latitude) 2 else 3
        require(value.matches(Regex("[0-9]{${degreesDigits + 2}}(?:\\.[0-9]+)?"))) { "INVALID_COORDINATE_FORMAT" }
        require(direction in if (latitude) listOf("N", "S") else listOf("E", "W")) { "INVALID_HEMISPHERE" }
        val degrees = value.take(degreesDigits).toBigDecimal()
        val minutes = value.substring(degreesDigits).toBigDecimal()
        val limit = BigDecimal(if (latitude) 90 else 180)
        require(minutes >= BigDecimal.ZERO && minutes < BigDecimal(60) &&
            degrees <= limit && (degrees < limit || minutes.signum() == 0)) { "COORDINATE_OUT_OF_RANGE" }
        val decimal = degrees.add(minutes.divide(BigDecimal(60), 12, RoundingMode.HALF_UP))
            .setScale(9, RoundingMode.HALF_UP)
        return if (direction == "S" || direction == "W") decimal.negate() else decimal
    }
}
