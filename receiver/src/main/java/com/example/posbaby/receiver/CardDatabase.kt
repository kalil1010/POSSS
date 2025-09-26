package com.example.posbaby.receiver

import android.content.Context
import android.util.Log

data class CardEntry(
    val atr: String,
    val descriptions: List<String>,
    val aids: List<String>
)

object CardDatabase {
    private const val TAG = "CardDatabase"
    private val database = mutableMapOf<String, CardEntry>()
    private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        try {
            context.assets.open("smart-card-list.txt").bufferedReader().use { reader ->
                parseSmartCardList(reader.lineSequence())
            }
            initialized = true
            Log.d(TAG, "Loaded ${database.size} ATR entries")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load ATR database", e)
        }
    }

    private fun parseSmartCardList(lines: Sequence<String>) {
        var currentAtr: String? = null
        val descriptions = mutableListOf<String>()

        lines.forEach { line ->
            when {
                line.startsWith("3B") -> {
                    // Save previous entry
                    currentAtr?.let { atr ->
                        database[atr] = CardEntry(
                            atr = atr,
                            descriptions = descriptions.toList(),
                            aids = extractAidsFromDescriptions(descriptions)
                        )
                    }
                    // Start new entry
                    currentAtr = line.trim()
                    descriptions.clear()
                }
                line.startsWith("\t") && currentAtr != null -> {
                    descriptions.add(line.trim())
                }
            }
        }
    }

    private fun extractAidsFromDescriptions(descriptions: List<String>): List<String> {
        val aids = mutableListOf<String>()
        descriptions.forEach { desc ->
            when {
                desc.contains("VISA", ignoreCase = true) -> aids.add("A0000000031010")
                desc.contains("MasterCard", ignoreCase = true) -> aids.add("A0000000041010")
                desc.contains("AMEX", ignoreCase = true) -> aids.add("A000000025010901")
                desc.contains("Discover", ignoreCase = true) -> aids.add("A0000001524010")
            }
        }
        return aids.ifEmpty { listOf("A0000000031010") } // Default to VISA
    }

    fun findByAtr(atr: String): CardEntry? {
        return database[atr] ?: database.entries.find { entry ->
            atr.contains(entry.key, ignoreCase = true)
        }?.value
    }
}
