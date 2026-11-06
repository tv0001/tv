package com.tv.player.core

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import coil.Coil
import coil.ImageLoader
import com.tv.player.BuildConfig
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * 兼容包（最低 Android 5.0）在老系统上补根证书：Android 7.1.1 以下的系统证书库里没有 Let's Encrypt（ISRG Root X1/X2）、
 * Google（GTS Root）、Amazon 等这几年的新根证书，用这些证书的片源、解析接口、海报图床都会握手失败。
 * 做法是系统证书库加上包里自带的 Mozilla 根证书（src/legacy/resources/cacert.pem，取自 https://curl.se/ca/cacert.pem）
 * 一起信任。7.1.0 和 7.1.1 同是 API 25，分不开，7.x 全部补上。主版本和 Android 8.0 以上的系统不走这里，用系统默认的证书校验。
 */
object LegacyTls {
    private val needed = BuildConfig.LEGACY && Build.VERSION.SDK_INT <= 25

    private class Trust(val context: SSLContext, val manager: X509TrustManager)

    private val trust: Trust? by lazy {
        if (!needed) return@lazy null
        try {
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
            // 系统自带的（含用户自己装的证书）
            val system = KeyStore.getInstance("AndroidCAStore").apply { load(null, null) }
            for (alias in system.aliases()) system.getCertificate(alias)?.let { store.setCertificateEntry("sys:$alias", it) }
            // 包里自带的：只取 BEGIN/END 之间的内容，文件里的说明文字不管
            val pem = LegacyTls::class.java.getResourceAsStream("/cacert.pem")?.use { it.readBytes().toString(Charsets.US_ASCII) }.orEmpty()
            val factory = CertificateFactory.getInstance("X.509")
            var n = 0
            Regex("-----BEGIN CERTIFICATE-----([^-]+)-----END CERTIFICATE-----").findAll(pem).forEach { m ->
                runCatching {
                    val cert = factory.generateCertificate(Base64.decode(m.groupValues[1], Base64.DEFAULT).inputStream()) as X509Certificate
                    store.setCertificateEntry("bundled:${n++}", cert)
                }
            }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            val manager = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
            Log.d("TVP", "legacy tls: ${store.size()} roots ($n bundled)")
            Trust(SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }, manager)
        } catch (e: Exception) {
            Log.w("TVP", "legacy tls failed, using system roots: $e")
            null
        }
    }

    /** OkHttp 客户端都从这里过一遍（[Http.client] 和由它派生的）；不需要时原样返回。 */
    fun configure(builder: OkHttpClient.Builder): OkHttpClient.Builder = builder.apply {
        trust?.let { sslSocketFactory(it.context.socketFactory, it.manager) }
    }

    /**
     * 另外自己建连接的：离线下载和投屏播放（Media3 的 DefaultHttpDataSource，走 HttpsURLConnection）、海报（Coil 自己的 OkHttp）。
     * 在 App.onCreate 里调用；第一次读证书要几十毫秒，放到后台线程。
     */
    fun install(context: Context) {
        if (!needed) return
        val app = context.applicationContext
        Coil.setImageLoader {
            ImageLoader.Builder(app).okHttpClient { configure(OkHttpClient.Builder()).build() }.build()
        }
        Thread {
            trust?.let { HttpsURLConnection.setDefaultSSLSocketFactory(it.context.socketFactory) }
        }.start()
    }
}
