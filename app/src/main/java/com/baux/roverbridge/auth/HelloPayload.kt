package com.baux.roverbridge.auth

/**
 * Rover tarafindan gelen HELLO challenge yukunu temsil eder.
 *
 * Kopru trafiginin guvenilir kabul edilmesinden once bu nesne backend
 * dogrulama uc noktasina gonderilir.
 */
data class HelloPayload(
    /** Benzersiz rover kimligi. */
    val roverId: String,
    /** Challenge zaman damgasi (epoch milisaniye). */
    val ts: Long,
    /** Mesaj icin izin verilen yasam suresi (milisaniye). */
    val ttlMs: Long,
    /** Tekrar saldirilarini onlemek icin kullanilan rastgele nonce. */
    val nonce: String,
    /** Challenge alanlari uzerinde rover imzasi. */
    val sig: String
)
