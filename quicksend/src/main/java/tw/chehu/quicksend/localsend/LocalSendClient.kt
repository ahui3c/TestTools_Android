package tw.chehu.quicksend.localsend

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tw.chehu.quicksend.model.LocalSendDevice
import tw.chehu.quicksend.model.TransferFile
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.coroutineContext

class LocalSendClient(private val resolver: ContentResolver) {
    private val senderFingerprint = UUID.randomUUID().toString()
    @Volatile private var activeConnection: HttpURLConnection? = null

    fun abort() {
        activeConnection?.disconnect()
    }

    suspend fun send(
        device: LocalSendDevice,
        files: List<TransferFile>,
        pin: String?,
        onFileStarted: (Int, TransferFile) -> Unit,
        onBytesSent: (Int, Long) -> Unit,
        onFileCompleted: (TransferFile) -> Unit,
    ) = withContext(Dispatchers.IO) {
        require(files.isNotEmpty()) { "沒有符合條件的檔案" }
        val ids = files.associateWith { UUID.randomUUID().toString() }
        val payload = JSONObject()
            .put("info", senderInfo(device.protocol))
            .put("files", JSONObject().apply {
                files.forEach { file ->
                    val id = ids.getValue(file)
                    put(id, JSONObject()
                        .put("id", id)
                        .put("fileName", file.destinationPath)
                        .put("size", file.size)
                        .put("fileType", file.mimeType)
                        .put("sha256", JSONObject.NULL)
                        .put("preview", JSONObject.NULL)
                        .put("metadata", JSONObject().put(
                            "modified",
                            file.lastModified.takeIf { it > 0 }
                                ?.let { Instant.ofEpochMilli(it).toString() }
                                ?: JSONObject.NULL
                        ))
                    )
                }
            })

        val pinQuery = pin?.takeIf { it.isNotBlank() }?.let {
            "?pin=${urlEncode(it)}"
        } ?: ""
        val prepare = openConnection(
            device,
            "${device.key}/api/localsend/v2/prepare-upload$pinQuery"
        ).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 15_000
            readTimeout = 120_000
        }
        val prepareJson = try {
            activeConnection = prepare
            val payloadBytes = payload.toString().toByteArray(Charsets.UTF_8)
            prepare.setFixedLengthStreamingMode(payloadBytes.size)
            prepare.outputStream.use { it.write(payloadBytes) }
            val prepareCode = prepare.responseCode
            if (prepareCode == 204) {
                files.forEach(onFileCompleted)
                return@withContext
            }
            if (prepareCode !in 200..299) {
                throw LocalSendException.fromStatus(prepareCode)
            }
            prepare.inputStream.bufferedReader().use {
                JSONObject(it.readText())
            }
        } finally {
            activeConnection = null
            prepare.disconnect()
        }
        val sessionId = prepareJson.getString("sessionId")
        val tokens = prepareJson.getJSONObject("files")

        files.forEachIndexed { index, file ->
            coroutineContext.ensureActive()
            val id = ids.getValue(file)
            if (!tokens.has(id)) return@forEachIndexed
            onFileStarted(index, file)
            val uploadUrl = "${device.key}/api/localsend/v2/upload" +
                "?sessionId=${urlEncode(sessionId)}&fileId=${urlEncode(id)}" +
                "&token=${urlEncode(tokens.getString(id))}"
            val upload = openConnection(device, uploadUrl).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 10 * 60_000
                setRequestProperty("Content-Type", file.mimeType)
                setFixedLengthStreamingMode(file.size)
            }
            try {
                activeConnection = upload
                resolver.openInputStream(file.uri)?.use { input ->
                    BufferedOutputStream(upload.outputStream, 128 * 1024).use { output ->
                        val buffer = ByteArray(128 * 1024)
                        var sent = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            sent += read
                            onBytesSent(index, sent)
                        }
                    }
                } ?: throw LocalSendException("無法開啟 ${file.destinationPath}")
                val code = upload.responseCode
                if (code !in 200..299) throw LocalSendException.fromStatus(code)
            } finally {
                activeConnection = null
                upload.disconnect()
            }
            onFileCompleted(file)
        }
    }

    private fun senderInfo(protocol: String): JSONObject = JSONObject()
        .put("alias", "QuickSend")
        .put("version", "2.0")
        .put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
        .put("deviceType", "mobile")
        .put("fingerprint", senderFingerprint)
        .put("port", 53317)
        .put("protocol", protocol)
        .put("download", false)

    // LocalSend deliberately uses self-signed certificates. The discovery fingerprint is
    // the SHA-256 certificate pin, so the custom trust manager verifies that exact pin.
    @SuppressLint("CustomX509TrustManager")
    private fun openConnection(device: LocalSendDevice, url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        if (connection is HttpsURLConnection) {
            val expected = normalizeFingerprint(device.fingerprint)
            val trustManager = object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    if (expected.isBlank()) {
                        throw CertificateException(
                            "手動 HTTPS 連線缺少接收端憑證指紋；請用自動搜尋或輸入 http://"
                        )
                    }
                    val certificate = chain.firstOrNull()
                        ?: throw CertificateException("接收端沒有憑證")
                    val actual = MessageDigest.getInstance("SHA-256")
                        .digest(certificate.encoded)
                        .joinToString("") { "%02x".format(it) }
                    if (actual != expected) {
                        throw CertificateException("LocalSend 接收端憑證指紋不符")
                    }
                }
            }
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
            }
            connection.sslSocketFactory = sslContext.socketFactory
            connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
        }
        return connection
    }

    private fun normalizeFingerprint(value: String): String =
        value.lowercase().replace(":", "").replace(Regex("[^0-9a-f]"), "")

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())
}

class LocalSendException(message: String) : Exception(message) {
    companion object {
        fun fromStatus(code: Int): LocalSendException = LocalSendException(
            when (code) {
                401 -> "接收端需要 PIN，或 PIN 不正確"
                403 -> "接收端拒絕傳送"
                409 -> "接收端正忙於其他傳輸"
                429 -> "接收端要求過多，請稍後再試"
                else -> "LocalSend 傳輸失敗（HTTP $code）"
            }
        )
    }
}
