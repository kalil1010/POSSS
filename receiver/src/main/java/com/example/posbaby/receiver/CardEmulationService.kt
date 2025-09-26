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

        // PSE Directory (Critical Fix)
        if (aid.equals(PSE_AID, ignoreCase = true)) {
            Log.d(TAG, "🏦 PSE Directory Request")
            // Build proper FCI template for PSE
            val dfName = "315041592E5359532E4444463031" // "1PAY.SYS.DDF01" hex
            val a5Template = buildPseA5Template()
            val totalLength = (dfName.length + a5Template.length) / 2
            val fci = "6F${"%02X".format(totalLength)}84${"%02X".format(dfName.length / 2)}${dfName}${a5Template}"
            return hexStringToByteArray(fci) + SUCCESS
        }

        // Application AID Selection
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
            buildApplicationFci(aid, cardType) + SUCCESS
        } else {
            Log.w(TAG, "❌ AID Not Supported: $aid for $cardType")
            FILE_NOT_FOUND
        }
    }

    private fun buildPseA5Template(): String {
        // Directory Entry for supported AIDs
        val visaAid = "A0000000031010"
        val masterAid = "A0000000041010"

        val entry1 = "61${"%02X".format((visaAid.length + 6) / 2)}4F${"%02X".format(visaAid.length / 2)}${visaAid}87010101"
        val entry2 = "61${"%02X".format((masterAid.length + 6) / 2)}4F${"%02X".format(masterAid.length / 2)}${masterAid}87010102"

        val bf0cData = entry1 + entry2
        return "A5${"%02X".format((bf0cData.length + 4) / 2)}BF0C${"%02X".format(bf0cData.length / 2)}${bf0cData}"
    }

    private fun buildApplicationFci(aid: String, cardType: String): ByteArray {
        val aidTag = "84${"%02X".format(aid.length / 2)}${aid}"
        val labelHex = cardType.toByteArray().joinToString("") { "%02X".format(it) }
        val labelTag = "50${"%02X".format(labelHex.length / 2)}${labelHex}"
        val priorityTag = "870101" // Application priority indicator

        val a5Data = labelTag + priorityTag
        val a5Template = "A5${"%02X".format(a5Data.length / 2)}${a5Data}"
        val fciData = aidTag + a5Template
        val fci = "6F${"%02X".format(fciData.length / 2)}${fciData}"

        return hexStringToByteArray(fci)
    }

    private fun handleGpo(): ByteArray {
        Log.d(TAG, "💳 GPO Request")
        val aip = "5800" // Offline data auth, SDA
        val afl = "08010100" // Application File Locator
        val data = "82${"%02X".format(aip.length / 2)}${aip}94${"%02X".format(afl.length / 2)}${afl}"
        val resp = "77${"%02X".format(data.length / 2)}${data}"
        return hexStringToByteArray(resp) + SUCCESS
    }

    private fun handleReadRecord(apdu: String): ByteArray {
        Log.d(TAG, "📄 READ RECORD")
        val card = currentCardData ?: return FILE_NOT_FOUND

        val pan = card.pan
        val exp = formatExpiry(card.expiry)
        val nameBytes = card.holder_name.toByteArray()
        val nameHex = nameBytes.joinToString("") { "%02X".format(it) }

        val panTag = "5A${"%02X".format(pan.length / 2)}${pan}"
        val expTag = "5F2403${exp}"
        val nameTag = "5F20${"%02X".format(nameBytes.size)}${nameHex}"
        val serviceCode = "5F300201" // Service code

        val recordData = panTag + expTag + nameTag + serviceCode
        val record = "70${"%02X".format(recordData.length / 2)}${recordData}"

        return hexStringToByteArray(record) + SUCCESS
    }

    private fun handleGetData(apdu: String): ByteArray {
        val tag = apdu.substring(6, 10)
        Log.d(TAG, "📊 GET DATA: $tag")

        val data = when (tag.uppercase()) {
            "9F36" -> "9F36020001" // ATC
            "9F13" -> "9F13020001" // Last Online ATC
            "9F17" -> "9F170103"   // PIN Try Counter
            else -> return FILE_NOT_FOUND
        }

        return hexStringToByteArray(data) + SUCCESS
    }

    private fun formatExpiry(exp: String): String = try {
        val parts = exp.split("-")
        val yy = parts[0].substring(2)
        val mm = parts[1]
        yy + mm
    } catch (e: Exception) {
        "2501" // Default expiry
    }

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
