package com.tv.player.core

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL

data class DlnaDevice(val name: String, val controlUrl: String, val serviceType: String)

/** Minimal DLNA/UPnP control point: SSDP discovery plus AVTransport SOAP calls. No third-party library. */
object Dlna {
    private const val SEARCH_TARGET = "urn:schemas-upnp-org:service:AVTransport:1"

    suspend fun discover(context: Context, windowMs: Long = 3_500): List<DlnaDevice> = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("tvplayer-dlna").apply {
            setReferenceCounted(false)
            acquire()
        }
        try {
            val locations = LinkedHashSet<String>()
            DatagramSocket().use { socket ->
                socket.soTimeout = 400
                val msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\n" +
                    "ST: $SEARCH_TARGET\r\n\r\n").toByteArray()
                val group = InetAddress.getByName("239.255.255.250")
                repeat(2) { socket.send(DatagramPacket(msg, msg.size, group, 1900)) }
                val end = SystemClock.elapsedRealtime() + windowMs
                val buf = ByteArray(4096)
                while (SystemClock.elapsedRealtime() < end) {
                    currentCoroutineContext().ensureActive()
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        socket.receive(packet)
                        val text = String(packet.data, 0, packet.length)
                        Regex("(?im)^location:\\s*(\\S+)").find(text)?.let { locations += it.groupValues[1] }
                    } catch (_: SocketTimeoutException) {
                    }
                }
            }
            coroutineScope { locations.map { async { describe(it) } }.awaitAll().filterNotNull() }
        } finally {
            lock.release()
        }
    }

    suspend fun describe(location: String): DlnaDevice? {
        val r = Http.get(location, timeoutMs = 4_000, limitBytes = 500_000)
        if (r.status != 200) return null
        var friendly: String? = null
        var urlBase: String? = null
        var serviceType: String? = null
        var control: String? = null
        var found: Pair<String, String>? = null
        try {
            val p = Xml.newPullParser()
            p.setInput(StringReader(r.text))
            var tag = ""
            var event = p.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        tag = p.name
                        if (tag == "service") {
                            serviceType = null
                            control = null
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val t = p.text.trim()
                        if (t.isNotEmpty()) when (tag) {
                            "friendlyName" -> if (friendly == null) friendly = t
                            "URLBase" -> urlBase = t
                            "serviceType" -> serviceType = t
                            "controlURL" -> control = t
                        }
                    }
                    XmlPullParser.END_TAG ->
                        if (p.name == "service" && found == null && serviceType?.contains("AVTransport") == true && control != null) {
                            found = serviceType!! to control!!
                        }
                }
                event = p.next()
            }
        } catch (e: Exception) {
            return null
        }
        val (type, path) = found ?: return null
        val controlUrl = runCatching { URL(URL(urlBase ?: location), path).toString() }.getOrNull() ?: return null
        return DlnaDevice(friendly ?: URL(location).host, controlUrl, type)
    }

    /** Returns null on success, otherwise a message for the user. */
    suspend fun cast(device: DlnaDevice, url: String, title: String): String? = withContext(Dispatchers.IO) {
        try {
            soap(device, "Stop", "<InstanceID>0</InstanceID>", ignoreError = true)
            val mime = if (url.contains(".m3u8", ignoreCase = true)) "application/vnd.apple.mpegurl" else "video/mp4"
            val didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
                "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
                "<item id=\"0\" parentID=\"-1\" restricted=\"false\"><dc:title>${esc(title)}</dc:title>" +
                "<upnp:class>object.item.videoItem</upnp:class>" +
                "<res protocolInfo=\"http-get:*:$mime:*\">${esc(url)}</res></item></DIDL-Lite>"
            soap(
                device,
                "SetAVTransportURI",
                "<InstanceID>0</InstanceID><CurrentURI>${esc(url)}</CurrentURI><CurrentURIMetaData>${esc(didl)}</CurrentURIMetaData>",
            )
            soap(device, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
            null
        } catch (e: Exception) {
            e.message ?: "投屏失败"
        }
    }

    suspend fun stop(device: DlnaDevice) = withContext(Dispatchers.IO) {
        runCatching { soap(device, "Stop", "<InstanceID>0</InstanceID>", ignoreError = true) }
    }

    private fun soap(device: DlnaDevice, action: String, inner: String, ignoreError: Boolean = false) {
        val envelope = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:$action xmlns:u=\"${device.serviceType}\">$inner</u:$action></s:Body></s:Envelope>"
        val request = Request.Builder()
            .url(device.controlUrl)
            .header("SOAPACTION", "\"${device.serviceType}#$action\"")
            .post(envelope.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
            .build()
        Http.client.newCall(request).execute().use {
            if (!it.isSuccessful && !ignoreError) throw IOException("$action 失败（HTTP ${it.code}）")
        }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}
