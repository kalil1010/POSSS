package com.example.posbaby.receiver

import android.util.Log
import com.google.gson.Gson
import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit

class WebSocketClient(private val serverUrl: String) {

    companion object {
        private const val TAG = "WebSocketClient"
    }

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    var isConnected = false
        private set

    fun connect() {
        try {
            val request = Request.Builder()
                .url("ws://$serverUrl/ws/apdu")
                .build()

            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    super.onOpen(webSocket, response)
                    isConnected = true
                    Log.d(TAG, "WebSocket connected to $serverUrl")

                    // Send initialization message
                    val initMessage = mapOf(
                        "type" to "init",
                        "device_id" to "android_hce_device",
                        "timestamp" to System.currentTimeMillis()
                    )
                    webSocket.send(Gson().toJson(initMessage))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    super.onMessage(webSocket, text)
                    handleMessage(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    super.onMessage(webSocket, bytes)
                    Log.d(TAG, "Received bytes: ${bytes.hex()}")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    super.onClosed(webSocket, code, reason)
                    isConnected = false
                    Log.d(TAG, "WebSocket closed: $code - $reason")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    super.onFailure(webSocket, t, response)
                    isConnected = false
                    Log.e(TAG, "WebSocket failure: ${t.message}", t)

                    // Attempt reconnection after delay
                    Thread.sleep(5000)
                    reconnect()
                }
            }

            webSocket = client.newWebSocket(request, listener)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect WebSocket: ${e.message}", e)
            isConnected = false
        }
    }

    private fun reconnect() {
        Log.d(TAG, "Attempting to reconnect WebSocket...")
        try {
            disconnect()
            Thread.sleep(2000)
            connect()
        } catch (e: Exception) {
            Log.e(TAG, "Reconnection failed: ${e.message}", e)
        }
    }

    fun sendAPDU(apduHex: String) {
        if (!isConnected) {
            Log.w(TAG, "WebSocket not connected, cannot send APDU")
            return
        }

        try {
            val message = mapOf(
                "type" to "apdu_command",
                "command" to apduHex,
                "timestamp" to System.currentTimeMillis(),
                "device_id" to "android_hce_device"
            )

            val json = Gson().toJson(message)
            webSocket?.send(json)
            Log.d(TAG, "Sent APDU: $apduHex")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to send APDU: ${e.message}", e)
        }
    }

    private fun handleMessage(message: String) {
        try {
            val data = Gson().fromJson(message, Map::class.java) as Map<String, Any>

            when (data["type"] as? String) {
                "apdu_response" -> {
                    val response = data["response"] as? String
                    if (!response.isNullOrEmpty()) {
                        Log.d(TAG, "Received APDU response: $response")
                        CardEmulationService.responseQueue.offer(response)
                    }
                }

                "status" -> {
                    val status = data["message"] as? String
                    Log.d(TAG, "Status: $status")
                }

                "error" -> {
                    val error = data["message"] as? String
                    Log.e(TAG, "Server error: $error")
                }

                else -> {
                    Log.d(TAG, "Unknown message type: ${data["type"]}")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle message: ${e.message}", e)
        }
    }

    fun sendHeartbeat() {
        if (!isConnected) return

        try {
            val heartbeat = mapOf(
                "type" to "heartbeat",
                "timestamp" to System.currentTimeMillis(),
                "device_id" to "android_hce_device"
            )

            webSocket?.send(Gson().toJson(heartbeat))

        } catch (e: Exception) {
            Log.e(TAG, "Failed to send heartbeat: ${e.message}", e)
        }
    }

    fun disconnect() {
        try {
            isConnected = false
            webSocket?.close(1000, "Client disconnect")
            webSocket = null
            Log.d(TAG, "WebSocket disconnected")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to disconnect WebSocket: ${e.message}", e)
        }
    }

    // Start heartbeat mechanism
    fun startHeartbeat() {
        Thread {
            while (isConnected) {
                try {
                    sendHeartbeat()
                    Thread.sleep(30000) // 30 seconds
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Heartbeat error: ${e.message}", e)
                }
            }
        }.start()
    }
}