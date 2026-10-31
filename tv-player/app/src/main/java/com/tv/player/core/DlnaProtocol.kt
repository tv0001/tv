package com.tv.player.core

import java.util.Locale

/**
 * DLNA 接收端（MediaRenderer）用到的纯文本处理：SOAP 解析和应答、时间格式、SSDP 报文、设备和服务描述。
 * 不碰网络，网络部分见 DlnaRenderer。
 */
object DlnaProtocol {
    const val DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1"
    val SERVICES = listOf("AVTransport", "RenderingControl", "ConnectionManager")

    fun serviceType(name: String) = "urn:schemas-upnp-org:service:$name:1"

    // ---- SOAP ----------------------------------------------------------------------------------------------------

    /** 请求的动作名：先看 SOAPACTION 头（"urn:...:AVTransport:1#Play"），没有就取正文 Body 里的第一个元素名。 */
    fun action(soapActionHeader: String?, body: String): String? {
        soapActionHeader?.trim()?.trim('"')?.substringAfterLast('#', "")?.takeIf { it.isNotEmpty() }?.let { return it }
        return Regex("""<(?:[\w-]+:)?Body[^>]*>\s*<(?:[\w-]+:)?(\w+)""").find(body)?.groupValues?.get(1)
    }

    /** 参数的值（反转义一次）；没有这个参数或是空元素返回 null。CurrentURIMetaData 反转义后就是 DIDL-Lite 原文。 */
    fun arg(body: String, name: String): String? {
        val m = Regex("""<(?:[\w-]+:)?$name(?:\s[^>]*)?>(.*?)</(?:[\w-]+:)?$name>""", RegexOption.DOT_MATCHES_ALL).find(body) ?: return null
        val raw = m.groupValues[1].trim()
        val text = if (raw.startsWith("<![CDATA[") && raw.endsWith("]]>")) raw.removePrefix("<![CDATA[").removeSuffix("]]>") else unescape(raw)
        return text.takeIf { it.isNotEmpty() }
    }

    /** DIDL-Lite 元数据里的片名。 */
    fun title(metadata: String?): String? =
        metadata?.let { Regex("""<dc:title[^>]*>(.*?)</dc:title>""", RegexOption.DOT_MATCHES_ALL).find(it)?.groupValues?.get(1) }
            ?.let(::unescape)?.trim()?.takeIf { it.isNotEmpty() }

    fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
        .replace("&#39;", "'").replace("&amp;", "&")

    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** 动作的成功应答。 */
    fun response(service: String, action: String, values: List<Pair<String, String>> = emptyList()): String =
        envelope("<u:${action}Response xmlns:u=\"${serviceType(service)}\">" + values.joinToString("") { (k, v) -> "<$k>${esc(v)}</$k>" } + "</u:${action}Response>")

    /** 出错时的应答（HTTP 500）：401 不认识的动作，402 参数不对，701 现在不能做（比如还没投地址就播放）。 */
    fun fault(code: Int, description: String): String = envelope(
        "<s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>" +
            "<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>$code</errorCode><errorDescription>${esc(description)}</errorDescription></UPnPError>" +
            "</detail></s:Fault>",
    )

    private fun envelope(body: String) = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
        "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
        "<s:Body>$body</s:Body></s:Envelope>"

    // ---- 事件（GENA）：订阅了的手机 App 靠它知道播放状态变了 ------------------------------------------------------

    fun lastChangeAvt(state: String, uri: String, durationMs: Long): String =
        "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/AVT/\"><InstanceID val=\"0\">" +
            "<TransportState val=\"$state\"/><CurrentTrackURI val=\"${esc(uri)}\"/><CurrentTrackDuration val=\"${formatTime(durationMs)}\"/>" +
            "</InstanceID></Event>"

    fun lastChangeRcs(volume: Int, mute: Boolean): String =
        "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/RCS/\"><InstanceID val=\"0\">" +
            "<Volume channel=\"Master\" val=\"$volume\"/><Mute channel=\"Master\" val=\"${if (mute) 1 else 0}\"/>" +
            "</InstanceID></Event>"

    /** 事件通知的正文：LastChange 里是转义过的 Event XML。 */
    fun propertySet(lastChange: String): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?><e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">" +
            "<e:property><LastChange>${esc(lastChange)}</LastChange></e:property></e:propertyset>"

    /** SUBSCRIBE 的 CALLBACK 头「<http://…>」里的第一个地址。 */
    fun callback(header: String?): String? = header?.let { Regex("<(https?://[^>]+)>").find(it)?.groupValues?.get(1) }

    // ---- 时间 ----------------------------------------------------------------------------------------------------

    /** "1:02:03"、"01:02:03.500" → 毫秒；认不出返回 null。 */
    fun parseTime(s: String): Long? {
        val m = Regex("""^(\d+):(\d{1,2}):(\d{1,2})(?:\.(\d+))?$""").matchEntire(s.trim()) ?: return null
        val (h, mi, sec, frac) = m.destructured
        val ms = if (frac.isEmpty()) 0L else (frac + "00").take(3).toLong()
        return ((h.toLong() * 60 + mi.toLong()) * 60 + sec.toLong()) * 1000 + ms
    }

    fun formatTime(ms: Long): String {
        val s = maxOf(0L, ms) / 1000
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    }

    // ---- SSDP ----------------------------------------------------------------------------------------------------

    /** 对一个 M-SEARCH 的 ST，要回哪几条（ssdp:all 要把设备和每个服务都报一遍）。 */
    fun searchTargets(st: String, uuid: String): List<String> {
        val all = listOf("upnp:rootdevice", "uuid:$uuid", DEVICE_TYPE) + SERVICES.map(::serviceType)
        return when (val t = st.trim()) {
            "ssdp:all" -> all
            in all -> listOf(t)
            else -> emptyList()
        }
    }

    fun usn(target: String, uuid: String) = if (target == "uuid:$uuid") target else "uuid:$uuid::$target"

    fun searchResponse(target: String, uuid: String, location: String): String =
        "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nEXT:\r\nLOCATION: $location\r\nSERVER: Android UPnP/1.0 TVPlayer/1.0\r\n" +
            "ST: $target\r\nUSN: ${usn(target, uuid)}\r\n\r\n"

    /** 上线（ssdp:alive）或下线（ssdp:byebye）通告。 */
    fun notify(nts: String, target: String, uuid: String, location: String): String =
        "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nCACHE-CONTROL: max-age=1800\r\nLOCATION: $location\r\n" +
            "NT: $target\r\nNTS: $nts\r\nSERVER: Android UPnP/1.0 TVPlayer/1.0\r\nUSN: ${usn(target, uuid)}\r\n\r\n"

    // ---- 描述文件 ------------------------------------------------------------------------------------------------

    fun description(uuid: String, name: String): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?><root xmlns=\"urn:schemas-upnp-org:device-1-0\" xmlns:dlna=\"urn:schemas-dlna-org:device-1-0\">" +
            "<specVersion><major>1</major><minor>0</minor></specVersion><device>" +
            "<deviceType>$DEVICE_TYPE</deviceType><friendlyName>${esc(name)}</friendlyName>" +
            "<manufacturer>TV Player</manufacturer><modelName>TV Player</modelName><modelNumber>1</modelNumber>" +
            "<UDN>uuid:$uuid</UDN><dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC><serviceList>" +
            SERVICES.joinToString("") { s ->
                "<service><serviceType>${serviceType(s)}</serviceType><serviceId>urn:upnp-org:serviceId:$s</serviceId>" +
                    "<SCPDURL>/dmr/$s.xml</SCPDURL><controlURL>/dmr/$s/control</controlURL><eventSubURL>/dmr/$s/event</eventSubURL></service>"
            } +
            "</serviceList></device></root>"

    /** 每个动作的参数：名字、方向（in / out）。状态变量统一叫 A_ARG_TYPE_参数名。 */
    private val actions: Map<String, Map<String, List<Pair<String, String>>>> = mapOf(
        "AVTransport" to mapOf(
            "SetAVTransportURI" to ins("InstanceID", "CurrentURI", "CurrentURIMetaData"),
            "Play" to ins("InstanceID", "Speed"),
            "Pause" to ins("InstanceID"),
            "Stop" to ins("InstanceID"),
            "Seek" to ins("InstanceID", "Unit", "Target"),
            "GetTransportInfo" to ins("InstanceID") + outs("CurrentTransportState", "CurrentTransportStatus", "CurrentSpeed"),
            "GetPositionInfo" to ins("InstanceID") + outs("Track", "TrackDuration", "TrackMetaData", "TrackURI", "RelTime", "AbsTime", "RelCount", "AbsCount"),
            "GetMediaInfo" to ins("InstanceID") + outs("NrTracks", "MediaDuration", "CurrentURI", "CurrentURIMetaData", "NextURI", "NextURIMetaData", "PlayMedium", "RecordMedium", "WriteStatus"),
            "GetTransportSettings" to ins("InstanceID") + outs("PlayMode", "RecQualityMode"),
            "GetCurrentTransportActions" to ins("InstanceID") + outs("Actions"),
            "GetDeviceCapabilities" to ins("InstanceID") + outs("PlayMedia", "RecMedia", "RecQualityModes"),
        ),
        "RenderingControl" to mapOf(
            "GetVolume" to ins("InstanceID", "Channel") + outs("CurrentVolume"),
            "SetVolume" to ins("InstanceID", "Channel", "DesiredVolume"),
            "GetMute" to ins("InstanceID", "Channel") + outs("CurrentMute"),
            "SetMute" to ins("InstanceID", "Channel", "DesiredMute"),
        ),
        "ConnectionManager" to mapOf(
            "GetProtocolInfo" to outs("Source", "Sink"),
            "GetCurrentConnectionIDs" to outs("ConnectionIDs"),
            "GetCurrentConnectionInfo" to ins("ConnectionID") + outs("RcsID", "AVTransportID", "ProtocolInfo", "PeerConnectionManager", "PeerConnectionID", "Direction", "Status"),
        ),
    )

    private fun ins(vararg names: String) = names.map { it to "in" }
    private fun outs(vararg names: String) = names.map { it to "out" }

    fun knows(service: String, action: String) = actions[service]?.containsKey(action) == true

    fun scpd(service: String): String? {
        val list = actions[service] ?: return null
        val vars = list.values.flatten().map { it.first }.distinct()
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?><scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">" +
            "<specVersion><major>1</major><minor>0</minor></specVersion><actionList>" +
            list.entries.joinToString("") { (action, args) ->
                "<action><name>$action</name><argumentList>" + args.joinToString("") { (n, dir) ->
                    "<argument><name>$n</name><direction>$dir</direction><relatedStateVariable>A_ARG_TYPE_$n</relatedStateVariable></argument>"
                } + "</argumentList></action>"
            } + "</actionList><serviceStateTable>" +
            vars.joinToString("") { n -> "<stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_$n</name><dataType>${dataType(n)}</dataType></stateVariable>" } +
            (if (service == "AVTransport" || service == "RenderingControl") "<stateVariable sendEvents=\"yes\"><name>LastChange</name><dataType>string</dataType></stateVariable>" else "") +
            "</serviceStateTable></scpd>"
    }

    private fun dataType(arg: String) = when (arg) {
        "InstanceID", "Track", "NrTracks" -> "ui4"
        "CurrentVolume", "DesiredVolume" -> "ui2"
        "CurrentMute", "DesiredMute" -> "boolean"
        "RelCount", "AbsCount", "ConnectionID", "RcsID", "AVTransportID", "PeerConnectionID" -> "i4"
        else -> "string"
    }
}
