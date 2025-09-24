package com.example.posbaby.receiver

import android.util.Log

object NfcUtils {

    private const val TAG = "NfcUtils"

    fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02X".format(it) }
    }

    fun hexToBytes(hex: String): ByteArray {
        val cleanHex = hex.replace(" ", "").uppercase()
        return ByteArray(cleanHex.length / 2) { i ->
            cleanHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    fun parseApduCommand(command: ByteArray): String {
        return when {
            isSelectCommand(command) -> "SELECT"
            isGetProcessingOptions(command) -> "GET_PROCESSING_OPTIONS"
            isReadRecord(command) -> "READ_RECORD"
            isGetData(command) -> "GET_DATA"
            else -> "UNKNOWN"
        }
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

    private fun isGetData(command: ByteArray): Boolean {
        return command.size >= 4 &&
                command == 0x80.toByte() &&
                command == 0xCA.toByte()
    }

    fun logApdu(tag: String, direction: String, data: ByteArray) {
        val hex = bytesToHex(data)
        val command = parseApduCommand(data)
        Log.d(tag, "$direction: $hex ($command)")
    }
}