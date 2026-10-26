package com.baux.roverbridge.auth

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Rover HELLO challenge dogrulamasi icin backend istemcisi.
 *
 * Bu iskelet yapida ag cagrisi sozlesmesi net olsun diye senkron tutulmustur.
 * Uretim kodunda ana is parcacigi disinda calistirilmalidir.
 */
class VerifyService(
    private val client: OkHttpClient,
    private val verifyUrl: String
) {
    /**
        * Payload verisini backend dogrulama uc noktasina gonderir.
     *
        * Backend basarili HTTP durum kodu dondururse true doner.
     */
    fun verify(payload: HelloPayload): Boolean {
        val json = """
            {
              \"roverId\": \"${payload.roverId}\",
              \"ts\": ${payload.ts},
              \"ttlMs\": ${payload.ttlMs},
              \"nonce\": \"${payload.nonce}\",
              \"sig\": \"${payload.sig}\"
            }
        """.trimIndent()

        val req = Request.Builder()
            .url(verifyUrl)
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).execute().use { return it.isSuccessful }
    }
}
