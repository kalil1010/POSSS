package com.example.posbaby.receiver

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import com.example.posbaby.receiver.CardDatabaseParser.CardInfo
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue

class CardEmulationService : HostApduService() {

    companion object {
        private const val TAG = "CardEmulationService"
        var currentCardData: CardRead? = null

        // Queues for monitoring
        val commandQueue: BlockingQueue<String> = LinkedBlockingQueue()
        val responseQueue: BlockingQueue<String> = LinkedBlockingQueue()

        // EMV SW
        private val SUCCESS = byteArrayOf(0x90.toByte(), 0x00.toByte())
        private val NOT_FOUND = byteArrayOf(0x6A.toByte(), 0x82.toByte())
    }

    private var cardDbParser: CardDatabaseParser? = null

    override fun onCreate() {
        super.onCreate()
        // Initialize parser with asset file
        cardDbParser = CardDatabaseParser(this)
        Log.d(TAG, "Smart card database loaded: ${cardDbParser!!.loadSmartCardDatabase().size} entries")
    }

    override fun processCommandApdu(command: ByteArray?, extras: Bundle?): ByteArray {
        val apduHex = command?.joinToString("") { "%02X".format(it) } ?: return NOT_FOUND
        Log.e(TAG, "Incoming APDU: $apduHex")
        commandQueue.offer(apduHex)

        // Example: identify by ATR when SELECT AID command
        if (apduHex.startsWith("00A40400")) {
            // attempt to identify card by ATR from CardDatabaseParser
            val atrBytes = extras?.getByteArray(NfcAdapter.EXTRA_TAG)?.let { cardDbParser!!.findCardByAtr(it) }
            atrBytes?.let { info ->
                Log.e(TAG, "Identified card: ${info.description} (type=${info.cardType}, issuer=${info.issuer})")
            }
        }

        // Always respond SUCCESS for demonstration
        responseQueue.offer("9000")
        return SUCCESS
    }

    override fun onDeactivated(reason: Int) {
        Log.e(TAG, "HCE deactivated: $reason")
    }
}