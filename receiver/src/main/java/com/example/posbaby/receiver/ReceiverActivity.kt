package com.example.posbaby.receiver

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.posbaby.receiver.databinding.ActivityReceiverBinding

class ReceiverActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReceiverBinding
    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    private var filters: Array<IntentFilter>? = null
    private var techLists: Array<Array<String>>? = null

    private lateinit var cardDbParser: CardDatabaseParser

    companion object {
        private const val TAG = "ReceiverActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReceiverBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize smart card database parser
        cardDbParser = CardDatabaseParser(this)
        val stats = cardDbParser.getDatabaseStats()
        Log.d(TAG, "Smart card DB stats: $stats")

        setupNfc()
        binding.btnReceive.setOnClickListener { showToast("Tap POS terminal to emulate card") }
    }

    private fun setupNfc() {
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            binding.tvStatus.text = "NFC not supported"
            return
        }
        if (!nfcAdapter!!.isEnabled) {
            binding.tvStatus.text = "Enable NFC"
            return
        }
        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_MUTABLE)
        filters = arrayOf(IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED))
        techLists = arrayOf(arrayOf(android.nfc.tech.IsoDep::class.java.name))
        binding.tvStatus.text = "Ready for NFC"
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, filters, techLists)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Toast.makeText(this, "NFC tag detected", Toast.LENGTH_SHORT).show()
        binding.tvStatus.text = "Processing NFC..."
        // At this point, CardEmulationService handles APDU
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        Log.d(TAG, msg)
    }
}