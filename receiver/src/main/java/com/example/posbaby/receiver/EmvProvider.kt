package com.example.posbaby.receiver

import com.github.devnied.emvnfccard.model.enums.CommandEnum
import com.github.devnied.emvnfccard.parser.IProvider
import com.github.devnied.emvnfccard.exception.CommunicationException

class EmvProvider : IProvider {

    private var currentCardData: CardRead? = null

    fun setCardData(cardData: CardRead?) {
        this.currentCardData = cardData
    }

    override fun transceive(command: ByteArray): ByteArray {
        // This simulates EMV card responses based on your card data
        return when {
            isSelectCommand(command) -> handleSelectCommand(command)
            isGetProcessingOptions(command) -> handleGetProcessingOptions()
            isReadRecord(command) -> handleReadRecord(command)
            else -> byteArrayOf(0x6D.toByte(), 0x00.toByte()) // Command not supported
        }
    }

    override fun getAt(): ByteArray {
        // Return ATR (Answer To Reset) - you can use patterns from smart-card-list.txt
        return byteArrayOf(
            0x3B, 0x67, 0x00, 0x00, 0xB2.toByte(), 0x40, 0x40, 0x20, 0x0B, 0x90.toByte(), 0x00
        )
    }

    private fun isSelectCommand(command: ByteArray): Boolean {
        return command.size >= 4 &&
                command == 0x00.toByte() &&
                command == 0xA4.toByte() &&
                command == 0x04.toByte()
    }

    private fun isGetProcessingOptions(command: ByteArray): Boolean {
        return command.size >= 4 &&
                command == 0x80.toByte() &&
                command == 0xA8.toByte()
    }

    private fun isReadRecord(command: ByteArray): Boolean {
        return command.size >= 4 &&
                command == 0x00.toByte() &&
                command == 0xB2.toByte()
    }

    private fun handleSelectCommand(command: ByteArray): ByteArray {
        // Return File Control Information (FCI) based on card type
        return currentCardData?.let {
            when (it.getCardType()) {
                "VISA" -> hexToByteArray("6F1C840E315041592E5359532E4444463031A50A9F38009F40090000123456789000")
                "MASTERCARD" -> hexToByteArray("6F1A840E325041592E5359532E4444463031A508BF0C0561034E039000")
                else -> hexToByteArray("6A82") // File not found
            }
        } ?: hexToByteArray("6985") // Conditions not satisfied
    }

    private fun handleGetProcessingOptions(): ByteArray {
        return currentCardData?.let {
            hexToByteArray("771482021800940408010100") + byteArrayOf(0x90.toByte(), 0x00.toByte())
        } ?: hexToByteArray("6985")
    }

    private fun handleReadRecord(command: ByteArray): ByteArray {
        return currentCardData?.let { card ->
            val pan = card.pan
            val expiry = formatExpiryForEmv(card.expiry)
            val name = card.holder_name

            // Build EMV record
            buildEmvRecord(pan, expiry, name) + byteArrayOf(0x90.toByte(), 0x00.toByte())
        } ?: hexToByteArray("6A83") // Record not found
    }

    private fun buildEmvRecord(pan: String, expiry: String, name: String): ByteArray {
        val panBytes = "5A" + String.format("%02X", pan.length / 2) + pan
        val expiryBytes = "5F2403$expiry"
        val nameBytes = "5F20" + String.format("%02X", name.length) + stringToHex(name)

        val recordData = panBytes + expiryBytes + nameBytes
        val record = "70" + String.format("%02X", recordData.length / 2) + recordData

        return hexToByteArray(record)
    }

    private fun formatExpiryForEmv(expiry: String): String {
        return try {
            val parts = expiry.split("-")
            if (parts.size == 3) {
                parts.substring(2) + parts + parts
            } else "250101"
        } catch (e: Exception) {
            "250101"
        }
    }

    private fun stringToHex(str: String): String {
        return str.toByteArray().joinToString("") { "%02X".format(it) }
    }

    private fun hexToByteArray(hex: String): ByteArray {
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}