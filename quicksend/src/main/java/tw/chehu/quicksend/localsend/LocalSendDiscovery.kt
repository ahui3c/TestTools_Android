package tw.chehu.quicksend.localsend

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tw.chehu.quicksend.model.LocalSendDevice
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID

class LocalSendDiscovery(private val context: Context) {
    private val multicastAddress = InetAddress.getByName("224.0.0.167")
    private val port = 53317

    suspend fun discover(durationMillis: Long = 3_500): List<LocalSendDevice> = withContext(Dispatchers.IO) {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifiManager.createMulticastLock("QuickSend.discovery").apply {
            setReferenceCounted(false)
            acquire()
        }
        val devices = linkedMapOf<String, LocalSendDevice>()
        val socket = MulticastSocket(null)
        var server: ServerSocket? = null
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(port))
            socket.soTimeout = 180
            socket.joinGroup(multicastAddress)
            server = runCatching {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                    soTimeout = 80
                }
            }.getOrNull()
            val announcement = JSONObject()
                .put("alias", "QuickSend")
                .put("version", "2.0")
                .put("deviceModel", android.os.Build.MODEL)
                .put("deviceType", "mobile")
                .put("fingerprint", UUID.randomUUID().toString())
                .put("port", port)
                .put("protocol", "http")
                .put("download", false)
                .put("announce", true)
                .toString()
                .toByteArray(Charsets.UTF_8)
            socket.send(DatagramPacket(announcement, announcement.size, multicastAddress, port))

            val deadline = System.currentTimeMillis() + durationMillis
            val buffer = ByteArray(65_507)
            while (System.currentTimeMillis() < deadline) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val json = JSONObject(String(packet.data, packet.offset, packet.length, Charsets.UTF_8))
                    if (json.optBoolean("announce", false)) continue
                    val alias = json.optString("alias").takeIf { it.isNotBlank() } ?: continue
                    val device = LocalSendDevice(
                        alias = alias,
                        ip = packet.address.hostAddress ?: continue,
                        port = json.optInt("port", port),
                        protocol = json.optString("protocol", "https"),
                        fingerprint = json.optString("fingerprint", ""),
                    )
                    devices[device.key] = device
                } catch (_: SocketTimeoutException) {
                    delay(10)
                } catch (_: Exception) {
                    // Ignore malformed announcements from unrelated multicast users.
                }
                try {
                    server?.accept()?.use { client ->
                        readRegisterRequest(client)?.let { device ->
                            devices[device.key] = device
                        }
                    }
                } catch (_: SocketTimeoutException) {
                    // No HTTP registration in this polling interval.
                } catch (_: Exception) {
                    // Keep discovery alive if one peer sends an invalid HTTP request.
                }
            }
        } finally {
            runCatching { server?.close() }
            runCatching { socket.leaveGroup(multicastAddress) }
            socket.close()
            if (lock.isHeld) lock.release()
        }
        devices.values.sortedBy { it.alias.lowercase() }
    }

    private fun readRegisterRequest(client: Socket): LocalSendDevice? {
        client.soTimeout = 1_000
        val input = client.getInputStream().buffered()
        val headerBytes = ByteArrayOutputStream()
        var matched = 0
        val delimiter = byteArrayOf(13, 10, 13, 10)
        while (headerBytes.size() < 16 * 1024 && matched < delimiter.size) {
            val next = input.read()
            if (next < 0) return null
            headerBytes.write(next)
            matched = if (next.toByte() == delimiter[matched]) matched + 1
            else if (next.toByte() == delimiter[0]) 1 else 0
        }
        val headerLines = headerBytes.toString(Charsets.US_ASCII.name()).split("\r\n")
        val requestLine = headerLines.firstOrNull() ?: return null
        if (!requestLine.startsWith("POST /api/localsend/v2/register")) return null
        val contentLength = headerLines.firstOrNull {
            it.startsWith("Content-Length:", ignoreCase = true)
        }?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        val body = ByteArray(contentLength)
        var offset = 0
        while (offset < contentLength) {
            val count = input.read(body, offset, contentLength - offset)
            if (count < 0) break
            offset += count
        }
        val json = JSONObject(String(body, 0, offset, Charsets.UTF_8))
        val responseBody = JSONObject()
            .put("alias", "QuickSend")
            .put("version", "2.0")
            .put("deviceModel", android.os.Build.MODEL)
            .put("deviceType", "mobile")
            .put("fingerprint", "quicksend")
            .put("download", false)
            .toString()
            .toByteArray(Charsets.UTF_8)
        client.getOutputStream().buffered().use { output ->
            output.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                    "Content-Length: ${responseBody.size}\r\nConnection: close\r\n\r\n")
                    .toByteArray(Charsets.US_ASCII)
            )
            output.write(responseBody)
            output.flush()
        }
        val alias = json.optString("alias").takeIf { it.isNotBlank() } ?: return null
        return LocalSendDevice(
            alias = alias,
            ip = client.inetAddress.hostAddress ?: return null,
            port = json.optInt("port", port),
            protocol = json.optString("protocol", "https"),
            fingerprint = json.optString("fingerprint", ""),
        )
    }

    fun manualDevice(address: String): LocalSendDevice {
        val trimmed = address.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .trimEnd('/')
        val host = trimmed.substringBefore(':')
        val customPort = trimmed.substringAfter(':', port.toString()).toIntOrNull() ?: port
        return LocalSendDevice(
            alias = host,
            ip = host,
            port = customPort,
            protocol = if (address.trim().startsWith("http://")) "http" else "https",
            fingerprint = "",
        )
    }
}
