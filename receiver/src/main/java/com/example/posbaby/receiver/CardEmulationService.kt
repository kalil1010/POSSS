package com.example.posbaby.receiver

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class CardEmulationService : HostApduService() {

    companion object {
        private const val TAG = "CardEmulationService"
        var currentCardData: CardRead? = null

        // EMV Standard Response Codes
        private val SUCCESS_RESPONSE = byteArrayOf(0x90.toByte(), 0x00.toByte())
        private val FILE_NOT_FOUND = byteArrayOf(0x6A.toByte(), 0x82.toByte())
        private val WRONG_LENGTH = byteArrayOf(0x67.toByte(), 0x00.toByte())
        private val COMMAND_NOT_SUPPORTED = byteArrayOf(0x6D.toByte(), 0x00.toByte())
        private val CONDITIONS_NOT_SATISFIED = byteArrayOf(0x69.toByte(), 0x85.toByte())

        // Common EMV Commands
        private const val SELECT_COMMAND = "00A40400"
        private const val GET_PROCESSING_OPTIONS = "80A80000"
        private const val READ_RECORD = "00B2"
        private const val GET_DATA = "80CA"

        // Common AIDs
        private val VISA_AID = "A0000000031010"
        private val MASTERCARD_AID = "A0000000041010"
        private val AMEX_AID = "A000000025010901"
        private val DISCOVER_AID = "A0000001524010"

        // Command and response queues for real-time processing
        val commandQueue: BlockingQueue<String> = LinkedBlockingQueue()
        val responseQueue: BlockingQueue<String> = LinkedBlockingQueue()
    }

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        val apduHex = commandApdu?.joinToString("") { "%02X".format(it) } ?: "NULL"
        Log.e(TAG, "🔥 INCOMING APDU: $apduHex")

        // Add command to queue for monitoring
        commandQueue.offer(apduHex)

        // Log current card data
        val cardInfo = currentCardData?.let {
            "Card: ${it.holder_name} (${it.issuer_id}) PAN: ${it.pan.take(6)}****${it.pan.takeLast(4)}"
        } ?: "No card data loaded"
        Log.e(TAG, "🔥 $cardInfo")

        return try {
            when {
                isSelectCommand(apduHex) -> handleSelectCommand(apduHex)
                isGetProcessingOptions(apduHex) -> handleGetProcessingOptions(apduHex)
                isReadRecord(apduHex) -> handleReadRecord(apduHex)
                isGetData(apduHex) -> handleGetData(apduHex)
                else -> {
                    Log.w(TAG, "🔥 Unknown command: $apduHex")
                    COMMAND_NOT_SUPPORTED
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "🔥 Error processing APDU: ${e.message}")
            COMMAND_NOT_SUPPORTED
        }.also { response ->
            val responseHex = response.joinToString("") { "%02X".format(it) }
            Log.e(TAG, "🔥 RESPONSE: $responseHex")
            responseQueue.offer(responseHex)
        }
    }

    private fun isSelectCommand(apdu: String): Boolean {
        return apdu.startsWith(SELECT_COMMAND)
    }

    private fun isGetProcessingOptions(apdu: String): Boolean {
        return apdu.startsWith(GET_PROCESSING_OPTIONS)
    }

    private fun isReadRecord(apdu: String): Boolean {
        return apdu.startsWith(READ_RECORD)
    }

    private fun isGetData(apdu: String): Boolean {
        return apdu.startsWith(GET_DATA)
    }

    private fun handleSelectCommand(apdu: String): ByteArray {
        Log.d(TAG, "🔥 Processing SELECT command")

        return if (currentCardData != null) {
            // Extract AID from SELECT command
            val aidLength = apdu.substring(10, 12).toInt(16) * 2
            val aid = if (apdu.length >= 12 + aidLength) {
                apdu.substring(12, 12 + aidLength)
            } else ""

            Log.d(TAG, "🔥 Selecting AID: $aid")

            when {
                aid.contains(VISA_AID) -> {
                    Log.d(TAG, "🔥 VISA card selected")
                    buildSelectResponse("VISA")
                }
                aid.contains(MASTERCARD_AID) -> {
                    Log.d(TAG, "🔥 MasterCard selected")
                    buildSelectResponse("MASTERCARD")
                }
                aid.contains(AMEX_AID) -> {
                    Log.d(TAG, "🔥 American Express selected")
                    buildSelectResponse("AMEX")
                }
                aid.contains(DISCOVER_AID) -> {
                    Log.d(TAG, "🔥 Discover selected")
                    buildSelectResponse("DISCOVER")
                }
                else -> {
                    Log.w(TAG, "🔥 Unknown AID requested: $aid")
                    FILE_NOT_FOUND
                }
            }
        } else {
            Log.w(TAG, "🔥 No card data available for SELECT")
            CONDITIONS_NOT_SATISFIED
        }
    }

    private fun buildSelectResponse(cardType: String): ByteArray {
        // Build FCI (File Control Information) template
        val fciTemplate = when (cardType) {
            "VISA" -> "6F1C840E315041592E5359532E4444463031A50A9F38009F4009000012345678"
            "MASTERCARD" -> "6F1A840E325041592E5359532E4444463031A508BF0C0561034E03"
            "AMEX" -> "6F19840E325041592E5359532E4444463031A507BF0C048201"
            "DISCOVER" -> "6F1B840E325041592E5359532E4444463031A509BF0C06810200"
            else -> "6F0C8407A0000000000000A501"
        }

        return hexStringToByteArray(fciTemplate) + SUCCESS_RESPONSE
    }

    private fun handleGetProcessingOptions(apdu: String): ByteArray {
        Log.d(TAG, "🔥 Processing GET PROCESSING OPTIONS")

        return if (currentCardData != null) {
            // Build Application Interchange Profile (AIP) and Application File Locator (AFL)
            val aip = "1800" // Terminal verification supported, SDA supported
            val afl = "08010100" // Record 1 in file 1
            val response = "77" + String.format("%02X", (aip.length + afl.length) / 2 + 4) +
                    "82" + String.format("%02X", aip.length / 2) + aip +
                    "94" + String.format("%02X", afl.length / 2) + afl

            hexStringToByteArray(response) + SUCCESS_RESPONSE
        } else {
            CONDITIONS_NOT_SATISFIED
        }
    }

    private fun handleReadRecord(apdu: String): ByteArray {
        Log.d(TAG, "🔥 Processing READ RECORD")

        return if (currentCardData != null) {
            val card = currentCardData!!

            // Build EMV record with card data
            val pan = card.pan
            val expiryDate = formatExpiryForEmv(card.expiry)
            val cardholderName = card.holder_name.padEnd(26).take(26)

            // EMV Record Template (simplified)
            val record = buildEmvRecord(pan, expiryDate, cardholderName)

            record + SUCCESS_RESPONSE
        } else {
            FILE_NOT_FOUND
        }
    }

    private fun handleGetData(apdu: String): ByteArray {
        Log.d(TAG, "🔥 Processing GET DATA")

        return if (currentCardData != null) {
            // Extract data object tag from command
            val tag = apdu.substring(6, 10)

            when (tag.uppercase()) {
                "9F13" -> { // Last Online ATC Register
                    val atc = "0001"
                    hexStringToByteArray("9F1302$atc") + SUCCESS_RESPONSE
                }
                "9F17" -> { // PIN Try Counter
                    val ptc = "03"
                    hexStringToByteArray("9F170103") + SUCCESS_RESPONSE
                }
                "9F36" -> { // Application Transaction Counter
                    val counter = "0001"
                    hexStringToByteArray("9F3602$counter") + SUCCESS_RESPONSE
                }
                else -> {
                    Log.w(TAG, "🔥 Unknown GET DATA tag: $tag")
                    FILE_NOT_FOUND
                }
            }
        } else {
            CONDITIONS_NOT_SATISFIED
        }
    }

    private fun buildEmvRecord(pan: String, expiry: String, holderName: String): ByteArray {
        // Build EMV record with TLV encoding
        val panTag = "5A" + String.format("%02X", pan.length / 2) + pan
        val expiryTag = "5F24" + "03" + expiry
        val nameTag = "5F20" + String.format("%02X", holderName.length) + stringToHex(holderName)

        val recordData = panTag + expiryTag + nameTag
        val recordTemplate = "70" + String.format("%02X", recordData.length / 2) + recordData

        return hexStringToByteArray(recordTemplate)
    }

    private fun formatExpiryForEmv(expiryString: String): String {
        return try {
            // Convert YYYY-MM-DD to YYMMDD
            val parts = expiryString.split("-")
            if (parts.size == 3) {
                val year = parts[0].substring(2)
                val month = parts[1]
                val day = parts[2]
                year + month + day
            } else {
                "250101" // Default fallback
            }
        } catch (e: Exception) {
            "250101"
        }
    }

    private fun stringToHex(str: String): String {
        return str.toByteArray().joinToString("") { "%02X".format(it) }
    }

    private fun hexStringToByteArray(hex: String): ByteArray {
        val cleanHex = hex.replace(" ", "")
        return ByteArray(cleanHex.length / 2) { i ->
            cleanHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    override fun onDeactivated(reason: Int) {
        val reasonText = when (reason) {
            DEACTIVATION_LINK_LOSS -> "Link Loss"
            DEACTIVATION_DESELECTED -> "Deselected"
            else -> "Unknown ($reason)"
        }
        Log.e(TAG, "🔥 Card emulation deactivated. Reason: $reasonText")
    }
}