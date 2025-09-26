package com.example.posbaby.receiver

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue

class CardEmulationService : HostApduService() {
    companion object {
        private const val TAG = "CardEmulationService"
        var currentCardData: CardRead? = null

        private val SUCCESS = byteArrayOf(0x90.toByte(), 0x00.toByte())
        private val FILE_NOT_FOUND = byteArrayOf(0x6A.toByte(), 0x82.toByte())
        private val CMD_NOT_SUPPORTED = byteArrayOf(0x6D.toByte(), 0x00.toByte())
        private val COND_NOT_SAT = byteArrayOf(0x69.toByte(), 0x85.toByte())

        val commandQueue: BlockingQueue<String> = LinkedBlockingQueue()
        val responseQueue: BlockingQueue<String> = LinkedBlockingQueue()

        private const val PSE_AID = "325041592E5359532E4444463031"
    }

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        val apduHex = commandApdu?.joinToString("") { "%02X".format(it) } ?: "NULL"
        Log.d(TAG, "🔵 IN: $apduHex")
        commandQueue.offer(apduHex)

        val response = try {
            when {
                apduHex.startsWith("00A40400") -> handleSelect(apduHex)
                apduHex.startsWith("80A80000") -> handleGpo()
                apduHex.startsWith("00B2") -> handleReadRecord(apduHex)
                apduHex.startsWith("80CA") -> handleGetData(apduHex)
                else -> CMD_NOT_SUPPORTED
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ APDU Error", e)
            CMD_NOT_SUPPORTED
        }

        val respHex = response.joinToString("") { "%02X".format(it) }
        Log.d(TAG, "🟢 OUT: $respHex")
        responseQueue.offer(respHex)
        return response
    }

    private fun handleSelect(apdu: String): ByteArray {
        val len = apdu.substring(10, 12).toInt(16) * 2
        val aid = if (apdu.length >= 12 + len) apdu.substring(12, 12 + len) else ""
        Log.d(TAG, "📱 SELECT AID: $aid")

        // PSE Directory handling
        if (aid.equals(PSE_AID, ignoreCase = true)) {
            Log.d(TAG, "✅ PSE Directory Request")
            val dfNameHex = "315041592E5359532E4444463031"
            // '6F0E' = TLV tag & length for 14-byte DF name
            val fciHex = "6F0E84${dfNameHex}A500"
            return hexStringToByteArray(fciHex) + SUCCESS
        }

        // Application AID handling
        val cardType = currentCardData?.getCardType()?.uppercase() ?: return COND_NOT_SAT
        val isSupported = when {
            aid.startsWith("A0000000031010") && cardType.contains("VISA") -> true
            aid.startsWith("A0000000041010") && cardType.contains("MASTER") -> true
            aid.startsWith("A000000025010901") && cardType.contains("AMEX") -> true
            aid.startsWith("A0000001524010") && cardType.contains("DISCOVER") -> true
            else -> false
        }

        return if (isSupported) {
            Log.d(TAG, "✅ AID Supported: $cardType")
            buildApplicationFci(aid) + SUCCESS
        } else {
            Log.w(TAG, "❌ AID Not Supported: $aid")
            FILE_NOT_FOUND
        }
    }

    private fun buildApplicationFci(aid: String): ByteArray {
        val aidTag = "84" + "%02X".format(aid.length / 2) + aid
        val label = currentCardData?.getCardType() ?: "CARD"
        val labelHex = label.toByteArray().joinToString("") { "%02X".format(it) }
        val labelTag = "50" + "%02X".format(labelHex.length / 2) + labelHex
        val fciDataHex = aidTag + labelTag
        val fciHex = "6F" + "%02X".format(fciDataHex.length / 2) + fciDataHex
        return hexStringToByteArray(fciHex)
    }

    private fun handleGpo(): ByteArray {
        Log.d(TAG, "💳 GPO Request")
        val aip = "5800"
        val afl = "08010100"
        val dataHex = "82" + "%02X".format(aip.length / 2) + aip +
                "94" + "%02X".format(afl.length / 2) + afl
        val respHex = "77" + "%02X".format(dataHex.length / 2) + dataHex
        return hexStringToByteArray(respHex) + SUCCESS
    }

    private fun handleReadRecord(apdu: String): ByteArray {
        val card = currentCardData ?: return FILE_NOT_FOUND
        val pan = card.pan
        val exp = formatExpiry(card.expiry)
        val nameHex = card.holder_name.toByteArray().joinToString("") { "%02X".format(it) }

        val recordDataHex = "5A" + "%02X".format(pan.length / 2) + pan +
                "5F24" + "03" + exp +
                "5F20" + "%02X".format(nameHex.length / 2) + nameHex
        val recordHex = "70" + "%02X".format(recordDataHex.length / 2) + recordDataHex
        return hexStringToByteArray(recordHex) + SUCCESS
    }

    private fun handleGetData(apdu: String): ByteArray {
        val tag = apdu.substring(6, 10).uppercase()
        val dataHex = when (tag) {
            "9F36" -> "9F36020001"
            else -> return FILE_NOT_FOUND
        }
        return hexStringToByteArray(dataHex) + SUCCESS
    }

    private fun formatExpiry(exp: String): String = try {
        val parts = exp.split("-")
        parts[0].substring(2) + parts[1]
    } catch (_: Exception) { "2501" }

    private fun hexStringToByteArray(hex: String): ByteArray {
        val clean = hex.replace(" ", "").uppercase()
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    override fun onDeactivated(reason: Int) {
        Log.d(TAG, "🔴 HCE Deactivated: $reason")
    }
}
