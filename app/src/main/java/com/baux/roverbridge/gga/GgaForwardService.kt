package com.baux.roverbridge.gga

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
}
