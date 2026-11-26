package com.wuse123mann.einkaufszettel

import android.content.Context
import com.google.android.gms.net.CronetProviderInstaller
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.chromium.net.CronetEngine
import org.chromium.net.CronetProvider
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Namensauflösung, die nicht allein vom DNS des Handys abhängt:
 * 1. normales System-DNS
 * 2. zuletzt funktionierende Adresse (Zwischenspeicher)
 * 3. verschlüsseltes DNS über Cloudflare bzw. Google (DNS-over-HTTPS, direkt per IP erreichbar)
 */
object RobustDns : Dns {
    private val cache = ConcurrentHashMap<String, List<InetAddress>>()

    private val bootstrapClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private val dohAnbieter: List<DnsOverHttps> by lazy {
        listOf(
            DnsOverHttps.Builder().client(bootstrapClient)
                .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
                .bootstrapDnsHosts(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("1.0.0.1"))
                .build(),
            DnsOverHttps.Builder().client(bootstrapClient)
                .url("https://dns.google/dns-query".toHttpUrl())
                .bootstrapDnsHosts(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("8.8.4.4"))
                .build(),
        )
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val fehler: UnknownHostException = try {
            val r = Dns.SYSTEM.lookup(hostname)
            if (r.isNotEmpty()) {
                cache[hostname] = r
                return r
            }
            UnknownHostException("Keine Adresse für $hostname")
        } catch (e: UnknownHostException) {
            e
        }
        cache[hostname]?.let { return it }
        for (doh in dohAnbieter) {
            try {
                val r = doh.lookup(hostname)
                if (r.isNotEmpty()) {
                    cache[hostname] = r
                    return r
                }
            } catch (_: Exception) {
                // nächsten Anbieter probieren
            }
        }
        throw UnknownHostException("${fehler.message} (auch verschlüsseltes Ausweich-DNS fehlgeschlagen)")
    }
}

/**
 * HTTP-Zugriff: bevorzugt Cronet (der Netzwerk-Stack von Chrome, über die Google-Play-Dienste,
 * mit QUIC/HTTP3 und parallelen Verbindungsversuchen). Fällt auf OkHttp zurück, wenn Cronet
 * nicht verfügbar ist oder die Anfrage scheitert.
 */
object Http {
    const val HOST = "generativelanguage.googleapis.com"

    @Volatile private var engine: CronetEngine? = null
    private val bereit = CountDownLatch(1)
    private val gestartet = AtomicBoolean(false)

    /** Welcher Weg zuletzt erfolgreich war (für die Anzeige im Schlüssel-Test). */
    @Volatile var letzterWeg: String = "-"
        private set

    private val okhttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(RobustDns)
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()
    }
    private val JSON_TYP = "application/json; charset=utf-8".toMediaType()

    fun init(context: Context) {
        if (!gestartet.compareAndSet(false, true)) return
        val app = context.applicationContext
        try {
            CronetProviderInstaller.installProvider(app).addOnCompleteListener {
                try {
                    val provider = CronetProvider.getAllProviders(app).firstOrNull {
                        it.isEnabled && it.name != CronetProvider.PROVIDER_NAME_FALLBACK
                    }
                    engine = provider?.createBuilder()
                        ?.enableQuic(true)
                        ?.enableHttp2(true)
                        ?.addQuicHint(HOST, 443, 443)
                        ?.build()
                } catch (_: Throwable) {
                    engine = null
                }
                bereit.countDown()
            }
        } catch (_: Throwable) {
            bereit.countDown()
        }
    }

    fun get(url: String, kopf: Pair<String, String>): Pair<Int, String> = anfrage(url, mapOf(kopf), null)

    /** Eine Webseite so abrufen, wie Chrome am Handy es täte (für die Hofer-Preise). */
    fun seite(url: String): Pair<Int, String> = anfrage(url, BROWSER, null)

    private val BROWSER = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "de-AT,de;q=0.9,en;q=0.7",
    )
    fun post(url: String, kopf: Pair<String, String>, body: String, hochgeladen: ((Float) -> Unit)? = null): Pair<Int, String> =
        anfrage(url, mapOf(kopf), body, hochgeladen)

    private fun anfrage(url: String, koepfe: Map<String, String>, body: String?, hochgeladen: ((Float) -> Unit)? = null): Pair<Int, String> {
        bereit.await(4, TimeUnit.SECONDS)
        val e = engine
        if (e != null) {
            try {
                val r = cronet(e, url, koepfe, body, hochgeladen)
                letzterWeg = "Chrome-Netz"
                return r
            } catch (cronetFehler: IOException) {
                try {
                    val r = ok(url, koepfe, body, hochgeladen)
                    letzterWeg = "OkHttp"
                    return r
                } catch (okFehler: IOException) {
                    cronetFehler.addSuppressed(okFehler)
                    throw cronetFehler
                }
            }
        }
        val r = ok(url, koepfe, body, hochgeladen)
        letzterWeg = "OkHttp"
        return r
    }

    private const val STUECK = 16 * 1024

    private fun cronet(e: CronetEngine, url: String, koepfe: Map<String, String>, body: String?, hochgeladen: ((Float) -> Unit)?): Pair<Int, String> {
        val con = e.openConnection(URL(url)) as HttpURLConnection
        try {
            con.connectTimeout = 20_000
            con.readTimeout = 90_000
            koepfe.forEach { (k, v) -> con.setRequestProperty(k, v) }
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                con.requestMethod = "POST"
                con.doOutput = true
                con.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                con.setFixedLengthStreamingMode(bytes.size)
                con.outputStream.use { out ->
                    var i = 0
                    while (i < bytes.size) {
                        val n = minOf(STUECK, bytes.size - i)
                        out.write(bytes, i, n)
                        i += n
                        hochgeladen?.invoke(i.toFloat() / bytes.size * 0.99f)
                    }
                }
                hochgeladen?.invoke(1f)
            }
            val code = con.responseCode
            val stream = if (code < 400) con.inputStream else con.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return code to text
        } finally {
            con.disconnect()
        }
    }

    private fun ok(url: String, koepfe: Map<String, String>, body: String?, hochgeladen: ((Float) -> Unit)?): Pair<Int, String> {
        val b = Request.Builder().url(url)
        koepfe.forEach { (k, v) -> b.header(k, v) }
        if (body != null) b.post(FortschrittBody(body.toByteArray(Charsets.UTF_8), hochgeladen)) else b.get()
        return okhttp.newCall(b.build()).execute().use { it.code to (it.body?.string() ?: "") }
    }

    private class FortschrittBody(private val bytes: ByteArray, private val cb: ((Float) -> Unit)?) : RequestBody() {
        override fun contentType() = JSON_TYP
        override fun contentLength() = bytes.size.toLong()
        override fun writeTo(sink: BufferedSink) {
            var i = 0
            while (i < bytes.size) {
                val n = minOf(STUECK, bytes.size - i)
                sink.write(bytes, i, n)
                i += n
                cb?.invoke(i.toFloat() / bytes.size * 0.99f)
            }
            sink.flush()
            cb?.invoke(1f)
        }
    }
}
