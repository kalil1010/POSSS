package com.example.posbaby.receiver

import retrofit2.Call
import retrofit2.http.GET

// Data class for cards
data class CardRead(
    val id: Int,
    val holder_name: String,
    val pan: String,
    val expiry: String,
    val cvv: Int,
    val issuer_id: String,
    val track: String,
    val amount: Float? = 0.0f
) {
    fun getCardType(): String = when {
        pan.startsWith("4") -> "VISA"
        pan.startsWith("5") || pan.startsWith("2") -> "MASTERCARD"
        pan.startsWith("34") || pan.startsWith("37") -> "AMEX"
        pan.startsWith("6") -> "DISCOVER"
        pan.startsWith("35") -> "JCB"
        pan.startsWith("62") -> "UNIONPAY"
        pan.startsWith("30") || pan.startsWith("38") -> "DINERS"
        else -> "UNKNOWN"
    }
}

// Retrofit API interface
interface CardApi {
    @GET("cards/")
    fun getCards(): Call<List<CardRead>>
}
