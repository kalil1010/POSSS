package com.example.posbaby.receiver

import android.content.Context
import android.util.Log
import java.io.IOException
import java.io.InputStream

class CardDatabaseParser(private val context: Context) {

    companion object {
        private const val TAG = "CardDatabaseParser"
        private const val SMART_CARD_FILE = "smart-card-list.txt"
    }

    data class CardInfo(
        val atr: String,
        val description: String,
        val cardType: String?,
        val issuer: String?,
        val country: String?
    )

    private var cardDatabase: Map<String, CardInfo>? = null

    /**
     * Load and parse the smart card list from assets
     */
    fun loadSmartCardDatabase(): Map<String, CardInfo> {
        if (cardDatabase != null) {
            return cardDatabase!!
        }

        return try {
            val content = readAssetFile(SMART_CARD_FILE)
            val patterns = parseSmartCardList(content)
            cardDatabase = patterns
            Log.d(TAG, "Loaded ${patterns.size} card patterns from database")
            patterns
        } catch (e: IOException) {
            Log.e(TAG, "Failed to load smart card database", e)
            emptyMap()
        }
    }

    /**
     * Parse the smart card list content
     */
    private fun parseSmartCardList(content: String): Map<String, CardInfo> {
        val patterns = mutableMapOf<String, CardInfo>()
        val lines = content.split("\n")

        for (line in lines) {
            if (line.trim().isEmpty() || line.startsWith("#")) continue

            try {
                val cardInfo = parseLine(line)
                cardInfo?.let {
                    patterns[it.atr] = it
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse line: $line", e)
            }
        }

        return patterns
    }

    /**
     * Parse individual line from smart card list
     */
    private fun parseLine(line: String): CardInfo? {
        // Look for ATR pattern (starts with 3B)
        val atrPattern = Regex("3B[\\s\\w]+")
        val atrMatch = atrPattern.find(line)

        if (atrMatch != null) {
            val atr = atrMatch.value.replace(" ", "").uppercase()
            val description = line.substring(atrMatch.range.last + 1).trim()

            // Extract additional information
            val cardType = extractCardType(description)
            val issuer = extractIssuer(description)
            val country = extractCountry(description)

            return CardInfo(atr, description, cardType, issuer, country)
        }

        return null
    }

    /**
     * Extract card type from description
     */
    private fun extractCardType(description: String): String? {
        val lowerDesc = description.lowercase()
        return when {
            lowerDesc.contains("visa") -> "VISA"
            lowerDesc.contains("mastercard") || lowerDesc.contains("master card") -> "MASTERCARD"
            lowerDesc.contains("maestro") -> "MAESTRO"
            lowerDesc.contains("american express") || lowerDesc.contains("amex") -> "AMEX"
            lowerDesc.contains("discover") -> "DISCOVER"
            lowerDesc.contains("diners") -> "DINERS"
            lowerDesc.contains("jcb") -> "JCB"
            lowerDesc.contains("unionpay") -> "UNIONPAY"
            else -> null
        }
    }

    /**
     * Extract issuer from description
     */
    private fun extractIssuer(description: String): String? {
        val lowerDesc = description.lowercase()

        // Common bank patterns
        val bankPatterns = listOf(
            "bank", "credit union", "financial", "savings", "trust",
            "chase", "wells fargo", "bank of america", "citibank",
            "hsbc", "barclays", "santander", "deutsche bank"
        )

        for (pattern in bankPatterns) {
            if (lowerDesc.contains(pattern)) {
                // Extract surrounding context
                val words = description.split(" ")
                val index = words.indexOfFirst { it.lowercase().contains(pattern) }
                if (index >= 0) {
                    val start = maxOf(0, index - 1)
                    val end = minOf(words.size, index + 2)
                    return words.subList(start, end).joinToString(" ")
                }
            }
        }

        return null
    }

    /**
     * Extract country from description
     */
    private fun extractCountry(description: String): String? {
        val countryPatterns = mapOf(
            "usa" to "United States",
            "uk" to "United Kingdom",
            "germany" to "Germany",
            "france" to "France",
            "canada" to "Canada",
            "australia" to "Australia",
            "japan" to "Japan",
            "sweden" to "Sweden",
            "norway" to "Norway",
            "belgium" to "Belgium",
            "netherlands" to "Netherlands"
        )

        val lowerDesc = description.lowercase()
        for ((pattern, country) in countryPatterns) {
            if (lowerDesc.contains(pattern)) {
                return country
            }
        }

        return null
    }

    /**
     * Read file from assets
     */
    private fun readAssetFile(fileName: String): String {
        val inputStream: InputStream = context.assets.open(fileName)
        return inputStream.bufferedReader().use { it.readText() }
    }

    /**
     * Find card info by ATR
     */
    fun findCardByAtr(atr: String): CardInfo? {
        val database = loadSmartCardDatabase()
        val normalizedAtr = atr.replace(" ", "").uppercase()

        // First try exact match
        database[normalizedAtr]?.let { return it }

        // Try partial match (first 10 characters)
        if (normalizedAtr.length >= 10) {
            val prefix = normalizedAtr.substring(0, 10)
            database.values.find { it.atr.startsWith(prefix) }?.let { return it }
        }

        return null
    }

    /**
     * Find cards by type
     */
    fun findCardsByType(cardType: String): List<CardInfo> {
        val database = loadSmartCardDatabase()
        return database.values.filter {
            it.cardType?.equals(cardType, ignoreCase = true) == true
        }
    }

    /**
     * Find cards by issuer
     */
    fun findCardsByIssuer(issuer: String): List<CardInfo> {
        val database = loadSmartCardDatabase()
        return database.values.filter {
            it.issuer?.contains(issuer, ignoreCase = true) == true
        }
    }

    /**
     * Get statistics about loaded database
     */
    fun getDatabaseStats(): Map<String, Int> {
        val database = loadSmartCardDatabase()
        val stats = mutableMapOf<String, Int>()

        stats["total_cards"] = database.size
        stats["visa_cards"] = database.values.count { it.cardType == "VISA" }
        stats["mastercard_cards"] = database.values.count { it.cardType == "MASTERCARD" }
        stats["amex_cards"] = database.values.count { it.cardType == "AMEX" }
        stats["other_cards"] = database.values.count { it.cardType == null }

        return stats
    }
}