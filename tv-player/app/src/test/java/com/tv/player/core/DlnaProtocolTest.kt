package com.tv.player.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DlnaProtocolTest {
    // 手机 App 投屏时发来的样子：元数据是转义过的 DIDL-Lite，片名里还有一层转义
    private val setUri = """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
<u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"><InstanceID>0</InstanceID>
<CurrentURI>http://cdn.example.com/v.mp4?a=1&amp;b=2</CurrentURI>
<CurrentURIMetaData>&lt;DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/"&gt;&lt;item&gt;&lt;dc:title&gt;猫和老鼠 &amp;amp; 朋友&lt;/dc:title&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;</CurrentURIMetaData>
</u:SetAVTransportURI></s:Body></s:Envelope>"""

    @Test
    fun `the action comes from the header or else the body`() {
        assertEquals("Play", DlnaProtocol.action("\"urn:schemas-upnp-org:service:AVTransport:1#Play\"", ""))
        assertEquals("SetAVTransportURI", DlnaProtocol.action(null, setUri))
        assertNull(DlnaProtocol.action(null, "<x/>"))
    }

    @Test
    fun `address and title are unescaped`() {
        assertEquals("http://cdn.example.com/v.mp4?a=1&b=2", DlnaProtocol.arg(setUri, "CurrentURI"))
        assertEquals("猫和老鼠 & 朋友", DlnaProtocol.title(DlnaProtocol.arg(setUri, "CurrentURIMetaData")))
        assertNull(DlnaProtocol.arg("<CurrentURIMetaData></CurrentURIMetaData>", "CurrentURIMetaData"))
        assertNull(DlnaProtocol.title(null))
    }

    @Test
    fun `times go both ways`() {
        assertEquals(3_723_000L, DlnaProtocol.parseTime("1:02:03"))
        assertEquals(3_723_500L, DlnaProtocol.parseTime("01:02:03.5"))
        assertNull(DlnaProtocol.parseTime("abc"))
        assertEquals("01:02:03", DlnaProtocol.formatTime(3_723_999))
        assertEquals("00:00:00", DlnaProtocol.formatTime(-5))
    }

    @Test
    fun `a search is answered for the targets this device has`() {
        val all = DlnaProtocol.searchTargets("ssdp:all", "u1")
        assertTrue("urn:schemas-upnp-org:service:AVTransport:1" in all && "uuid:u1" in all && all.size == 6)
        assertEquals(listOf(DlnaProtocol.DEVICE_TYPE), DlnaProtocol.searchTargets(DlnaProtocol.DEVICE_TYPE, "u1"))
        assertEquals(emptyList<String>(), DlnaProtocol.searchTargets("urn:schemas-upnp-org:device:MediaServer:1", "u1"))
        val reply = DlnaProtocol.searchResponse("upnp:rootdevice", "u1", "http://192.168.1.5:4000/dmr/desc.xml")
        assertTrue(reply.startsWith("HTTP/1.1 200 OK\r\n") && reply.contains("USN: uuid:u1::upnp:rootdevice\r\n") && reply.endsWith("\r\n\r\n"))
    }

    @Test
    fun `answers escape their values and faults carry the code`() {
        val r = DlnaProtocol.response("AVTransport", "GetPositionInfo", listOf("TrackURI" to "http://a/b?x=1&y=2"))
        assertTrue(r.contains("<u:GetPositionInfoResponse xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"><TrackURI>http://a/b?x=1&amp;y=2</TrackURI>"))
        assertTrue(DlnaProtocol.fault(701, "Transition not available").contains("<errorCode>701</errorCode>"))
    }

    @Test
    fun `descriptions list every service and action`() {
        val d = DlnaProtocol.description("u1", "TV Player（盒子）")
        assertTrue(d.contains("<UDN>uuid:u1</UDN>") && d.contains("/dmr/AVTransport/control") && d.contains("TV Player（盒子）"))
        val scpd = DlnaProtocol.scpd("AVTransport")!!
        assertTrue(scpd.contains("<name>SetAVTransportURI</name>") && scpd.contains("<name>A_ARG_TYPE_InstanceID</name><dataType>ui4</dataType>"))
        assertNull(DlnaProtocol.scpd("Nope"))
        assertTrue(DlnaProtocol.knows("RenderingControl", "SetVolume"))
    }

    @Test
    fun `events carry the escaped state and the callback is read from its header`() {
        val body = DlnaProtocol.propertySet(DlnaProtocol.lastChangeAvt("PLAYING", "http://a/b?x=1&y=2", 61_000))
        assertTrue(body.contains("<LastChange>&lt;Event xmlns=&quot;urn:schemas-upnp-org:metadata-1-0/AVT/&quot;&gt;"))
        assertTrue(body.contains("TransportState val=&quot;PLAYING&quot;") && body.contains("http://a/b?x=1&amp;amp;y=2") && body.contains("00:01:01"))
        assertEquals("http://192.168.1.3:9000/evt", DlnaProtocol.callback("<http://192.168.1.3:9000/evt><http://other/>"))
        assertNull(DlnaProtocol.callback(null))
    }
}
