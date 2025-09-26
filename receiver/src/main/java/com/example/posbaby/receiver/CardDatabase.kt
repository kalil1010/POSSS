package com.example.posbaby.receiver

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

data class SmartCardEntry(
    val atr: String,
    val description: String,
    val aids: List<String>
)

object CardDatabase {
    private const val TAG = "CardDatabase"
    private val entries = mutableListOf<SmartCardEntry>()
    private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        try {
            context.assets.open("smart-card-list.txt").use { stream ->
                BufferedReader(InputStreamReader(stream)).useLines { lines ->
                    lines.filter { it.isNotBlank() && !it.startsWith("#") }
                        .map { it.split("\\s+".toRegex(), 2) }
                        .forEach { parts ->
                            if (parts.size == 2) {
                                val atr = parts[0]
                                val desc = parts[1]
                                val aids = when {
                                    desc.contains("VISA", ignoreCase = true) ->
                                        listOf("A0000000031010")
                                    desc.contains("MASTER", ignoreCase = true) ->
                                        listOf("A0000000041010")
                                    desc.contains("AMEX", ignoreCase = true) ->
                                        listOf("A000000025010901")
                                    else ->
                                        listOf("A0000000000000")
                                }
                                entries += SmartCardEntry(atr, desc, aids)
                            }
                        }
                }
            }
            initialized = true
            Log.d(TAG, "Loaded ${entries.size} ATR entries")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load ATR database", e)
        }
    }

    fun findByAtr(atr: String): SmartCardEntry? =
        entries.find { it.atr.equals(atr, ignoreCase = true) }
}
