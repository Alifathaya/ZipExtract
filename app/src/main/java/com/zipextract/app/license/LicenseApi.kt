package com.zipextract.app.license

import com.zipextract.app.BuildConfig
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

data class LicenseServerResponse(
    val status: String,
    val expiresAtEpochMs: Long,
    val serverTimeEpochMs: Long,
    val daysAdded: Int? = null,
    val unlimited: Boolean = false,
    val error: String? = null,
    val update: AppUpdateInfo? = null,
)

class LicenseApi(
    private val baseUrl: String = BuildConfig.LICENSE_API_BASE_URL,
) {
    fun isConfigured(): Boolean {
        val url = baseUrl.trim()
        return url.isNotBlank() &&
            !url.contains("license.example.com", ignoreCase = true) &&
            (url.startsWith("http://") || url.startsWith("https://"))
    }

    fun register(deviceId: String, appVersion: String): LicenseServerResponse {
        return post(
            "/v1/license/register",
            JSONObject()
                .put("deviceId", deviceId)
                .put("appVersion", appVersion)
                .put("appId", APP_ID),
        )
    }

    fun check(deviceId: String, appVersion: String = BuildConfig.VERSION_NAME): LicenseServerResponse {
        return post(
            "/v1/license/check",
            JSONObject()
                .put("deviceId", deviceId)
                .put("appVersion", appVersion)
                .put("appId", APP_ID),
        )
    }

    fun activate(deviceId: String, key: String): LicenseServerResponse {
        return post(
            "/v1/license/activate",
            JSONObject()
                .put("deviceId", deviceId)
                .put("key", key)
                .put("appId", APP_ID),
        )
    }

    private fun post(path: String, body: JSONObject): LicenseServerResponse {
        val url = URL(baseUrl.trimEnd('/') + path)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            // Keep short — checks run in the background and must not stall UX.
            connectTimeout = 5_000
            readTimeout = 8_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }
        try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { it.readText() }
            }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (code !in 200..299) {
                return parseResponse(
                    json,
                    httpError = json.optString("error").ifBlank { "http_$code" },
                )
            }
            return parseResponse(json)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** Product id on the shared license server (separate from BRI Link). */
        const val APP_ID = "filenest"

        fun parseResponse(json: JSONObject, httpError: String? = null): LicenseServerResponse {
            return LicenseServerResponse(
                status = json.optString("status", "active"),
                expiresAtEpochMs = parseIso(json.optString("expiresAt")),
                serverTimeEpochMs = parseIso(json.optString("serverTime")).takeIf { it > 0 }
                    ?: System.currentTimeMillis(),
                daysAdded = json.optInt("daysAdded", -1).takeIf { it >= 0 },
                unlimited = json.optBoolean("unlimited", false) ||
                    isUnlimitedEpoch(parseIso(json.optString("expiresAt"))),
                error = httpError ?: json.optString("error").ifBlank { null },
                update = parseUpdate(json.optJSONObject("update")),
            )
        }

        fun parseUpdate(json: JSONObject?): AppUpdateInfo? {
            if (json == null) return null
            val version = json.optString("version").trim()
            val message = json.optString("message").trim()
            val url = json.optString("url").trim()
            if (version.isBlank() || message.isBlank() || url.isBlank()) return null
            if (!url.startsWith("http://") && !url.startsWith("https://")) return null
            return AppUpdateInfo(
                version = version,
                message = message,
                url = url,
                createdAt = json.optString("createdAt").trim(),
            )
        }

        fun parseUpdateMessage(json: JSONObject): AppUpdateInfo? {
            // Dedicated WS frame: { type: "update", version, message, url, createdAt }
            if (json.optString("type") == "update") {
                return parseUpdate(json)
            }
            return parseUpdate(json.optJSONObject("update"))
        }

        fun parseIso(value: String): Long {
            if (value.isBlank()) return 0L
            return runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0L)
        }

        /** Year 9000+ (server uses 9999-12-31) means lifetime license. */
        fun isUnlimitedEpoch(epochMs: Long): Boolean {
            if (epochMs <= 0L) return false
            val year = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
                timeInMillis = epochMs
            }.get(java.util.Calendar.YEAR)
            return year >= 9000
        }

        /** True when [remote] is a newer semver-like version than [local]. */
        fun isNewerVersion(remote: String, local: String): Boolean {
            fun parts(s: String): List<Int> {
                return s.trim()
                    .removePrefix("v")
                    .removePrefix("V")
                    .split('.', '-', '_')
                    .map { token ->
                        token.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
                    }
            }
            val a = parts(remote)
            val b = parts(local)
            val n = maxOf(a.size, b.size)
            for (i in 0 until n) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
