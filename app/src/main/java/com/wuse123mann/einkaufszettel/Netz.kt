package com.wuse123mann.einkaufszettel

import okhttp3.Dns
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
