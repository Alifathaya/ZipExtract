package com.zipextract.app.license

import android.util.Log
import com.zipextract.app.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Receives Block/Unblock commands from the license server over WebSocket.
 * Idle most of the time — no HTTP polling loop.
 */
class LicensePushClient(
    private val deviceId: String,
    private val onLicense: (LicenseServerResponse) -> Unit,
    private val baseUrl: String = BuildConfig.LICENSE_API_BASE_URL,
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val wanted = AtomicBoolean(false)
    private var socket: WebSocket? = null
    private var reconnectAttempt = 0

    fun start() {
        if (!wanted.compareAndSet(false, true)) {
            // already started
            return
        }
        connect()
    }

    fun stop() {
        wanted.set(false)
        socket?.close(1000, "bye")
        socket = null
    }

    private fun wsUrl(): String? {
        val http = baseUrl.trim().trimEnd('/')
        if (http.isBlank()) return null
        val ws = when {
            http.startsWith("https://") -> "wss://" + http.removePrefix("https://")
            http.startsWith("http://") -> "ws://" + http.removePrefix("http://")
            else -> return null
        }
        return "$ws/v1/license/ws?deviceId=${java.net.URLEncoder.encode(deviceId, Charsets.UTF_8.name())}"
    }

    private fun connect() {
        if (!wanted.get()) return
        val url = wsUrl() ?: return
        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    reconnectAttempt = 0
                    Log.i(TAG, "license push connected")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                    val type = json.optString("type")
                    if (type == "pong") return
                    if (type != "license" && !json.has("status")) return
                    onLicense(LicenseApi.parseResponse(json))
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    scheduleReconnect()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "license push failed: ${t.message}")
                    scheduleReconnect()
                }
            },
        )
    }

    private fun scheduleReconnect() {
        if (!wanted.get()) return
        reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(6)
        val delayMs = (1_000L shl (reconnectAttempt - 1)).coerceAtMost(60_000L)
        client.dispatcher.executorService.execute {
            try {
                Thread.sleep(delayMs)
            } catch (_: InterruptedException) {
                return@execute
            }
            if (wanted.get()) connect()
        }
    }

    companion object {
        private const val TAG = "LicensePush"
    }
}
