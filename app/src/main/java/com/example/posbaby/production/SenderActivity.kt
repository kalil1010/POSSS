package com.example.posbaby.production

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import com.example.posbaby.production.databinding.ActivitySenderBinding
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

data class CardCreate(
    val holder_name: String,
    val pan: String,
    val expiry: String,        // Backend expects date, we'll format it properly
    val cvv: Int,
    val issuer_id: String,     // Required by backend - auto-detected from PAN
    val track: String,         // Required by backend
    val amount: Float? = 0.00f, // Optional with default
    val id: Int? = null        // For response
)

interface CardApi {
    @POST("cards/")
    fun createCard(@Body card: CardCreate): Call<CardCreate>
}

class SenderActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySenderBinding
    private lateinit var cardApi: CardApi

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = DataBindingUtil.setContentView(this, R.layout.activity_sender) as ActivitySenderBinding

        val retrofit = Retrofit.Builder()
            .baseUrl("https://posbaby-production.up.railway.app/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        cardApi = retrofit.create(CardApi::class.java)

        binding.btnSend.setOnClickListener {
            sendCardToAPI()
        }

        // Auto-format expiry MM/YY
        binding.etExpiry.addTextChangedListener(object : TextWatcher {
            private var isEditing = false

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { }

            override fun afterTextChanged(s: Editable?) {
                if (isEditing) return
                isEditing = true

                val input = s.toString().replace("[^\\d]".toRegex(), "")
                val formatted = StringBuilder()

                for (i in input.indices) {
                    formatted.append(input[i])
                    if (i == 1 && input.length > 2) {
                        formatted.append('/')
                    }
                }

                binding.etExpiry.setText(formatted)
                binding.etExpiry.setSelection(formatted.length.coerceAtMost(binding.etExpiry.text?.length ?: 0))
                isEditing = false
            }
        })
    }

    // Function to auto-detect card issuer from PAN (card number)
    private fun getIssuerFromPAN(pan: String): String {
        return when {
            pan.startsWith("4") -> "VISA"           // Visa cards start with 4
            pan.startsWith("5") -> "MC"             // Mastercard starts with 5
            pan.startsWith("34") || pan.startsWith("37") -> "AMEX" // Amex starts with 34/37
            pan.startsWith("6") -> "DISC"           // Discover starts with 6
            pan.startsWith("30") || pan.startsWith("38") -> "DINER" // Diners Club
            else -> "UNKNWN"                        // Unknown issuer (6 chars max)
        }
    }

    private fun sendCardToAPI() {
        val holderName = binding.etHolderName.text.toString().trim()
        val pan = binding.etPan.text.toString().trim()
        val expiry = binding.etExpiry.text.toString().trim()
        val cvvText = binding.etCvv.text.toString().trim()

        // Validation
        if (holderName.isEmpty()) {
            showToast("Please enter holder name")
            return
        }

        if (pan.isEmpty() || pan.length != 16) {
            showToast("Please enter valid 16-digit PAN")
            return
        }

        if (expiry.isEmpty() || !expiry.matches(Regex("\\d{2}/\\d{2}"))) {
            showToast("Please enter expiry in MM/YY format")
            return
        }

        if (cvvText.isEmpty()) {
            showToast("Please enter CVV")
            return
        }

        val cvv = try {
            cvvText.toInt()
        } catch (e: NumberFormatException) {
            showToast("CVV must be a number")
            return
        }

        // Convert MM/YY to date format that backend can parse
        val expiryParts = expiry.split("/")
        val month = expiryParts[0]
        val year = "20${expiryParts[1]}"
        val expiryDate = "$year-$month-01"  // Format as YYYY-MM-DD

        // Auto-detect issuer from card number
        val issuerId = getIssuerFromPAN(pan)

        val card = CardCreate(
            holder_name = holderName,
            pan = pan,
            expiry = expiryDate,
            cvv = cvv,
            issuer_id = issuerId,        // ✅ Auto-detected real issuer
            track = "",                  // Empty track
            amount = 0.00f              // Default amount
        )

        cardApi.createCard(card).enqueue(object : Callback<CardCreate> {
            override fun onResponse(call: Call<CardCreate>, response: Response<CardCreate>) {
                if (response.isSuccessful) {
                    val cardId = response.body()?.id ?: "Unknown"
                    showToast("Card sent successfully! ID: $cardId, Issuer: $issuerId")
                    clearInputs()
                } else {
                    showToast("Failed to send card: ${response.code()}")
                }
            }

            override fun onFailure(call: Call<CardCreate>, t: Throwable) {
                showToast("Network error: ${t.message}")
                t.printStackTrace()
            }
        })
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun clearInputs() {
        binding.etHolderName.text?.clear()
        binding.etPan.text?.clear()
        binding.etExpiry.text?.clear()
        binding.etCvv.text?.clear()
    }
}
