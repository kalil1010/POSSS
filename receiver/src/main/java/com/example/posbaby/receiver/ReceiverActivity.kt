package com.example.posbaby.receiver

import android.content.Intent
import android.nfc.NfcAdapter
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import retrofit2.*
import retrofit2.converter.gson.GsonConverterFactory
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ReceiverActivity : AppCompatActivity() {
    private lateinit var tvStatus: TextView
    private lateinit var btnReceive: Button
    private lateinit var btnNfcSettings: Button
    private lateinit var btnMonitor: Button
    private lateinit var scrollMonitor: ScrollView
    private lateinit var tvMonitorLog: TextView
    private lateinit var etReceiverName: EditText
    private lateinit var etReceiverPan: EditText
    private lateinit var etReceiverExpiry: EditText
    private lateinit var etReceiverCvv: EditText
    private lateinit var etReceiverTrack: EditText
    private lateinit var etReceiverAmount: EditText
    private lateinit var tvCardInfo: TextView

    private lateinit var cardApi: CardApi
    private var nfcAdapter: NfcAdapter? = null
    private var currentCard: CardRead? = null
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private var monitoringActive = false

    companion object {
        private const val TAG = "ReceiverActivity"
        private const val BACKEND_URL = "https://posbaby-production.up.railway.app/"
        private const val SERVICE_CODE = "201"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_receiver)

        // Initialize CardDatabase with ATR lookup
        CardDatabase.initialize(this)
        Log.d(TAG, "✅ CardDatabase initialized")

        initViews()
        initNFC()
        initAPI()
        setupClicks()
        Log.d(TAG, "🚀 ReceiverActivity initialized")
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tvStatus)
        btnReceive = findViewById(R.id.btnReceive)
        btnNfcSettings = findViewById(R.id.btnNfcSettings)
        btnMonitor = findViewById(R.id.btnMonitor)
        scrollMonitor = findViewById(R.id.scrollMonitor)
        tvMonitorLog = findViewById(R.id.tvMonitorLog)
        etReceiverName = findViewById(R.id.etReceiverName)
        etReceiverPan = findViewById(R.id.etReceiverPan)
        etReceiverExpiry = findViewById(R.id.etReceiverExpiry)
        etReceiverCvv = findViewById(R.id.etReceiverCvv)
        etReceiverTrack = findViewById(R.id.etReceiverTrack)
        etReceiverAmount = findViewById(R.id.etReceiverAmount)
        tvCardInfo = findViewById(R.id.tvCardInfo)

        btnMonitor.visibility = View.GONE
        appendLog("🔧 UI initialized")
    }

    private fun initNFC() {
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        val status = when {
            nfcAdapter == null -> {
                "❌ NFC unavailable on this device"
            }
            !nfcAdapter!!.isEnabled -> {
                "⚠️ NFC disabled - Please enable in settings"
            }
            !packageManager.hasSystemFeature("android.hardware.nfc.hce") -> {
                "❌ HCE (Host Card Emulation) not supported"
            }
            else -> {
                "✅ NFC ready for card emulation"
            }
        }

        tvStatus.text = status
        Log.d(TAG, status)
    }

    private fun initAPI() {
        val retrofit = Retrofit.Builder()
            .baseUrl(BACKEND_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        cardApi = retrofit.create(CardApi::class.java)
        Log.d(TAG, "🌐 API client initialized: $BACKEND_URL")
    }

    private fun setupClicks() {
        btnReceive.setOnClickListener {
            appendLog("🔄 Loading card from backend...")
            fetchCard()
        }

        btnNfcSettings.setOnClickListener {
            startActivity(Intent(android.provider.Settings.ACTION_NFC_SETTINGS))
            appendLog("⚙️ Opening NFC settings")
        }

        btnMonitor.setOnClickListener {
            toggleMonitor()
        }
    }

    private fun fetchCard() {
        tvStatus.text = "🔄 Loading card data from server..."

        cardApi.getCards().enqueue(object : Callback<List<CardRead>> {
            override fun onResponse(
                call: Call<List<CardRead>>,
                response: Response<List<CardRead>>
            ) {
                val cardList = response.body().orEmpty()
                if (cardList.isEmpty()) {
                    toast("⚠️ No cards found on server")
                    appendLog("❌ No cards available")
                    tvStatus.text = "❌ No cards available"
                    return
                }

                // Get the latest card
                val card = cardList.last()
                currentCard = card

                Log.d(TAG, "✅ Card loaded: ${card.holder_name} - ${card.getCardType()}")
                appendLog("✅ Card loaded: ${card.holder_name} (${card.getCardType()})")

                displayCard(card)
                enableCardEmulation(card)
            }

            override fun onFailure(call: Call<List<CardRead>>, t: Throwable) {
                val errorMsg = "Network error: ${t.message}"
                toast(errorMsg)
                appendLog("❌ $errorMsg")
                tvStatus.text = "❌ Connection failed"
                Log.e(TAG, "Failed to fetch cards", t)
            }
        })
    }

    private fun displayCard(card: CardRead) {
        // Display card information in UI
        etReceiverName.setText(card.holder_name)
        etReceiverPan.setText(formatPan(card.pan))
        etReceiverExpiry.setText(formatExpiry(card.expiry))
        etReceiverCvv.setText("***") // Don't show CVV
        etReceiverTrack.setText(card.track.ifEmpty { buildTrack2(card) })
        etReceiverAmount.setText("%.2f".format(card.amount ?: 0.0f))

        // Display card info
        val cardType = card.getCardType()
        tvCardInfo.text = "💳 ID: ${card.id} | Type: $cardType | Ready for tap"

        appendLog("📱 Card details displayed: $cardType")
    }

    private fun enableCardEmulation(card: CardRead) {
        // Set the card data for the emulation service
        CardEmulationService.currentCardData = card

        val cardType = card.getCardType()
        tvStatus.text = "🎯 ${cardType} card ready for POS tap"
        btnMonitor.visibility = View.VISIBLE

        appendLog("🎯 HCE enabled for $cardType card")
        appendLog("📡 Ready to receive POS commands...")

        Log.d(TAG, "Card emulation enabled: $cardType")
    }

    private fun toggleMonitor() {
        monitoringActive = !monitoringActive

        if (monitoringActive) {
            btnMonitor.text = "⏹️ Stop Monitor"
            appendLog("🔍 APDU monitoring started")

            // Start polling APDU queues
            executor.scheduleAtFixedRate({
                pollApduQueues()
            }, 0, 100, TimeUnit.MILLISECONDS)

        } else {
            btnMonitor.text = "📊 Monitor APDU"
            appendLog("⏸️ APDU monitoring stopped")

            // Stop the executor
            executor.shutdownNow()
        }
    }

    private fun pollApduQueues() {
        // Poll command queue
        while (CardEmulationService.commandQueue.isNotEmpty()) {
            val command = CardEmulationService.commandQueue.poll()
            runOnUiThread {
                appendLog("🔵 CMD: $command")
            }
        }

        // Poll response queue
        while (CardEmulationService.responseQueue.isNotEmpty()) {
            val response = CardEmulationService.responseQueue.poll()
            runOnUiThread {
                appendLog("🟢 RSP: $response")
            }
        }
    }

    private fun appendLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val logEntry = "[$timestamp] $message\n"

        tvMonitorLog.append(logEntry)

        // Auto-scroll to bottom
        scrollMonitor.post {
            scrollMonitor.fullScroll(View.FOCUS_DOWN)
        }

        Log.d(TAG, message)
    }

    private fun formatPan(pan: String): String {
        return if (pan.length >= 16) {
            "${pan.take(4)} **** **** ${pan.takeLast(4)}"
        } else {
            pan
        }
    }

    private fun formatExpiry(expiry: String): String {
        return try {
            val inputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val outputFormat = SimpleDateFormat("MM/yy", Locale.US)
            val date = inputFormat.parse(expiry)
            outputFormat.format(date!!)
        } catch (e: Exception) {
            expiry // Return as-is if parsing fails
        }
    }

    private fun buildTrack2(card: CardRead): String {
        return try {
            val parts = card.expiry.split("-")
            val yy = parts[0].substring(2)  // Get last 2 digits of year
            val mm = parts[1]               // Get month
            "${card.pan}D$yy$mm$SERVICE_CODE"
        } catch (e: Exception) {
            Log.e(TAG, "Error building Track 2", e)
            "${card.pan}D2501$SERVICE_CODE" // Fallback
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!executor.isShutdown) {
            executor.shutdown()
        }
        Log.d(TAG, "🔴 ReceiverActivity destroyed")
    }

    override fun onResume() {
        super.onResume()
        // Check NFC status when returning to app
        initNFC()
    }
}
