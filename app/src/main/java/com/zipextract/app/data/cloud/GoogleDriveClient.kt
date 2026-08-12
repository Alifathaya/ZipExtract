package com.zipextract.app.data.cloud

import android.accounts.Account
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.zipextract.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class GoogleDriveClient(private val context: Context) {

    companion object {
        const val SCOPE_DRIVE = "https://www.googleapis.com/auth/drive"
        private const val FILES_URL = "https://www.googleapis.com/drive/v3/files"
        private const val UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart"

        fun webClientIdOrNull(): String? {
            return BuildConfig.GOOGLE_WEB_CLIENT_ID.trim().takeIf { it.isNotEmpty() }
        }

        fun isConfigured(): Boolean = !webClientIdOrNull().isNullOrBlank()
    }

    fun authState(): CloudAuthState {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        return if (account != null && !account.email.isNullOrBlank()) {
            CloudAuthState.SignedIn(email = account.email!!, displayName = account.displayName)
        } else {
            // Login does not require GOOGLE_WEB_CLIENT_ID — that is only for optional ID tokens.
            CloudAuthState.SignedOut
        }
    }

    /** Optional hint shown under the login button when web client ID is missing. */
    fun setupHintOrNull(): String? {
        if (isConfigured()) return null
        return "Opsional: set GOOGLE_WEB_CLIENT_ID untuk ID token. Login Drive tetap bisa tanpa itu " +
            "(butuh OAuth Android client di Google Cloud: package + SHA-1)."
    }

    fun signInClient(): GoogleSignInClient {
        val webClientId = webClientIdOrNull().orEmpty()
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestProfile()
            .requestScopes(Scope(SCOPE_DRIVE))
            .apply {
                // Web client ID is optional; used only when present.
                if (webClientId.isNotBlank()) {
                    requestIdToken(webClientId)
                }
            }
            .build()
        return GoogleSignIn.getClient(context, options)
    }

    fun signInIntent(): Intent = signInClient().signInIntent

    fun signOut(onDone: () -> Unit = {}) {
        signInClient().signOut().addOnCompleteListener { onDone() }
    }

    fun describeSignInError(statusCode: Int): String {
        return when (statusCode) {
            10 -> "Konfigurasi OAuth salah (kode 10). Tambahkan SHA-1 debug/release + package " +
                "com.zipextract.app di Google Cloud Console → Credentials → Android client."
            7 -> "Jaringan bermasalah (kode 7). Cek koneksi internet."
            8 -> "Internal error Play Services (kode 8). Coba lagi."
            12500 -> "Login gagal (12500). Update Google Play Services."
            12501 -> "Login dibatalkan."
            12502 -> "Login sedang diproses. Tunggu sebentar."
            else -> "Login gagal: kode $statusCode"
        }
    }

    fun lastAccount(): GoogleSignInAccount? = GoogleSignIn.getLastSignedInAccount(context)

    suspend fun listFolder(folderId: String = "root"): Result<List<CloudFileItem>> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val token = accessToken()
                val q = URLEncoder.encode("'$folderId' in parents and trashed=false", "UTF-8")
                val fields = URLEncoder.encode(
                    "files(id,name,mimeType,size,modifiedTime,webViewLink)",
                    "UTF-8",
                )
                val url = "$FILES_URL?q=$q&fields=$fields&pageSize=100&orderBy=folder,name"
                val json = httpGet(url, token)
                val files = json.getJSONArray("files")
                buildList {
                    for (i in 0 until files.length()) {
                        val obj = files.getJSONObject(i)
                        val mime = obj.optString("mimeType")
                        add(
                            CloudFileItem(
                                id = obj.getString("id"),
                                name = obj.getString("name"),
                                mimeType = mime,
                                sizeBytes = obj.optString("size").toLongOrNull(),
                                isFolder = mime == "application/vnd.google-apps.folder",
                                modifiedTime = obj.optString("modifiedTime").ifBlank { null },
                                webViewLink = obj.optString("webViewLink").ifBlank { null },
                            ),
                        )
                    }
                }.sortedWith(compareByDescending<CloudFileItem> { it.isFolder }.thenBy { it.name.lowercase() })
            }
        }
    }

    suspend fun downloadFile(fileId: String, fileName: String, mimeType: String): Result<File> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val token = accessToken()
                val isGoogleDoc = mimeType.startsWith("application/vnd.google-apps.")
                val url = if (isGoogleDoc) {
                    val exportMime = when {
                        mimeType.contains("document") -> "application/pdf"
                        mimeType.contains("spreadsheet") ->
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                        mimeType.contains("presentation") ->
                            "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                        else -> "application/pdf"
                    }
                    "$FILES_URL/$fileId/export?mimeType=${URLEncoder.encode(exportMime, "UTF-8")}"
                } else {
                    "$FILES_URL/$fileId?alt=media"
                }
                val dir = File(context.getExternalFilesDir(null), "DriveDownloads").apply { mkdirs() }
                val safe = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                val targetName = if (isGoogleDoc && !safe.contains('.')) "$safe.pdf" else safe
                val target = File(dir, targetName)
                httpDownload(url, token, target)
                target
            }
        }
    }

    suspend fun uploadFile(local: File, parentFolderId: String = "root"): Result<CloudFileItem> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val token = accessToken()
                val boundary = "filenest_${System.currentTimeMillis()}"
                val metadata = JSONObject()
                    .put("name", local.name)
                    .put("parents", org.json.JSONArray().put(parentFolderId))
                    .toString()

                val connection = (URL(UPLOAD_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
                }

                connection.outputStream.use { out ->
                    val writer = OutputStreamWriter(out, StandardCharsets.UTF_8)
                    writer.append("--$boundary\r\n")
                    writer.append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
                    writer.append(metadata).append("\r\n")
                    writer.append("--$boundary\r\n")
                    writer.append("Content-Type: application/octet-stream\r\n\r\n")
                    writer.flush()
                    local.inputStream().use { input -> input.copyTo(out) }
                    writer.append("\r\n--$boundary--\r\n")
                    writer.flush()
                }

                val code = connection.responseCode
                val body = readBody(connection)
                if (code !in 200..299) error("Upload gagal ($code): $body")
                val obj = JSONObject(body)
                CloudFileItem(
                    id = obj.getString("id"),
                    name = obj.optString("name", local.name),
                    mimeType = obj.optString("mimeType", "application/octet-stream"),
                    sizeBytes = local.length(),
                    isFolder = false,
                )
            }
        }
    }

    private fun accessToken(): String {
        val account = lastAccount() ?: error("Belum login Google Drive")
        val acc = account.account ?: Account(account.email!!, "com.google")
        return try {
            GoogleAuthUtil.getToken(context, acc, "oauth2:$SCOPE_DRIVE")
        } catch (recoverable: UserRecoverableAuthException) {
            throw recoverable
        }
    }

    private fun httpGet(url: String, token: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
        }
        val code = connection.responseCode
        val body = readBody(connection)
        if (code !in 200..299) error("Drive error ($code): $body")
        return JSONObject(body)
    }

    private fun httpDownload(url: String, token: String, target: File) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
        }
        val code = connection.responseCode
        if (code !in 200..299) {
            error("Download gagal ($code): ${readBody(connection)}")
        }
        connection.inputStream.use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
    }

    private fun readBody(connection: HttpURLConnection): String {
        val stream = if (connection.responseCode >= 400) {
            connection.errorStream
        } else {
            connection.inputStream
        } ?: return ""
        return BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
    }
}
