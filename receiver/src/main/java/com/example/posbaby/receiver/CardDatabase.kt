package com.example.posbaby.receiver

import android.content.Context
import android.util.Log

object CardDatabase {

    private const val TAG = "CardDatabase"
    private var parser: CardDatabaseParser? = null
    private var isInitialized = false

    fun initialize(context: Context) {
        if (!isInitialized) {
            parser = CardDatabaseParser(context)
            isInitialized = true
            Log.d(TAG, "CardDatabase initialized")
        }
    }

    fun identifyCardByAtr(atr: ByteArray): CardDatabaseParser.CardInfo? {
        val atrHex = atr.joinToString("") { "%02X".format(it) }
        return parser?.findCardByAtr(atrHex)
    }

    fun identifyCardByAtr(atrHex: String): CardDatabaseParser.CardInfo? {
        return parser?.findCardByAtr(atrHex)
    }

    fun getCardsByType(cardType: String): List<CardDatabaseParser.CardInfo> {
        return parser?.findCardsByType(cardType) ?: emptyList()
    }

    fun getDatabaseStats(): Map<String, Int> {
        return parser?.getDatabaseStats() ?: emptyMap()
    }

    // Legacy support for existing AID mappings
    private val aidMappings = mapOf(
        "A0000000031010" to "Visa",
        "A0000000041010" to "MasterCard",
        "A0000000042203" to "MasterCard Maestro",
        "A000000025010901" to "American Express",
        "A0000001524010" to "Discover"
    )

    fun getCardTypeByAid(aid: String): String? {
        return aidMappings[aid.uppercase()]
    }

    fun getSupportedAids(): List<String> {
        return aidMappings.keys.toList()
    }
}