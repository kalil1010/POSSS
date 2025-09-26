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
        initViews()
        initNFC()
        initAPI()
        setupClicks()
        Log.d(TAG, "Initialized")
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
    }

    private fun initNFC() {
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        tvStatus.text = when {
            nfcAdapter == null -> "❌ NFC unavailable"
            !nfcAdapter!!.isEnabled -> "⚠️ NFC disabled"
            !packageManager.hasSystemFeature("android.hardware.nfc.hce") -> "❌ HCE unsupported"
            else -> "✅ NFC ready"
        }
    }

    private fun initAPI() {
        val retrofit = Retrofit.Builder()
            .baseUrl(BACKEND_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        cardApi = retrofit.create(CardApi::class.java)
    }

    private fun setupClicks() {
        btnReceive.setOnClickListener { fetchCard() }
        btnNfcSettings.setOnClickListener {
            startActivity(Intent(android.provider.Settings.ACTION_NFC_SETTINGS))
        }
        btnMonitor.setOnClickListener { toggleMonitor() }
    }

    private fun fetchCard() {
        tvStatus.text = "🔄 Loading..."
        cardApi.getCards().enqueue(object : Callback<List<CardRead>> {
            override fun onResponse(
                call: Call<List<CardRead>>,
                response: Response<List<CardRead>>
            ) {
                val list = response.body().orEmpty()
                if (list.isEmpty()) {
                    toast("No cards")
                    return
                }
                val card = list.last()
                currentCard = card
                display(card)
                enableEmu(card)
            }

            override fun onFailure(call: Call<List<CardRead>>, t: Throwable) {
                toast("Error: ${t.message}")
            }
        })
    }

    private fun display(card: CardRead) {
        etReceiverName.setText(card.holder_name)
        etReceiverPan.setText(formatPan(card.pan))
        etReceiverExpiry.setText(formatExp(card.expiry))
        etReceiverCvv.setText("***")
        etReceiverTrack.setText(card.track.ifEmpty { buildTrack2(card) })
        etReceiverAmount.setText("%.2f".format(card.amount))
        tvCardInfo.text = "ID:${card.id} | ${card.getCardType()}"
    }

    private fun enableEmu(card: CardRead) {
        CardEmulationService.currentCardData = card
        tvStatus.text = "🎯 ${card.getCardType()} ready"
        btnMonitor.visibility = View.VISIBLE
    }

    private fun toggleMonitor() {
        monitoringActive = !monitoringActive
        if (monitoringActive) {
            executor.scheduleAtFixedRate({ pollQueues() }, 0, 100, TimeUnit.MILLISECONDS)
            btnMonitor.text = "Stop Monitor"
        } else {
            executor.shutdownNow()
            btnMonitor.text = "📊 Monitor APDU"
        }
    }

    private fun pollQueues() {
        while (CardEmulationService.commandQueue.isNotEmpty()) {
            val cmd = CardEmulationService.commandQueue.poll()
            runOnUiThread { appendLog("CMD: $cmd") }
        }
        while (CardEmulationService.responseQueue.isNotEmpty()) {
            val rsp = CardEmulationService.responseQueue.poll()
            runOnUiThread { appendLog("RSP: $rsp") }
        }
    }

    private fun appendLog(line: String) {
        tvMonitorLog.append("$line\n")
        scrollMonitor.post { scrollMonitor.fullScroll(View.FOCUS_DOWN) }
    }

    private fun formatPan(p: String) =
        if (p.length >= 16) "${p.take(4)} **** **** ${p.takeLast(4)}" else p

    private fun formatExp(e: String) = try {
        SimpleDateFormat("MM/yy", Locale.US)
            .format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(e)!!)
    } catch (_: Exception) {
        e
    }

    private fun buildTrack2(card: CardRead): String {
        val parts = card.expiry.split("-")
        val yy = parts[0].substring(2)
        val mm = parts[1]
        return "${card.pan}D${yy}${mm}$SERVICE_CODE"
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }
}
