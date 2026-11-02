package com.securenet.optimizer

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URL

/** Put your own HTTPS endpoint here. JSON: {"versionCode":2,"versionName":"1.1.0","url":"https://...apk","notes":"..."} */
const val UPDATE_URL = "https://example.com/securenet/version.json"

data class NetReport(
    val avg: Int,
    val min: Int,
    val jitter: Int,
    val rssi: Int?,
    val mbps: Int?,
    val mhz: Int?
)

suspend fun measureNetwork(ctx: Context): NetReport = withContext(Dispatchers.IO) {
    val t = mutableListOf<Int>()
    repeat(6) {
        try {
            val s = System.nanoTime()
            Socket().use { it.connect(InetSocketAddress("1.1.1.1", 443), 2000) }
            t += ((System.nanoTime() - s) / 1_000_000).toInt()
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    val wifi = (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo
    val ok = wifi != null && wifi.networkId != -1

    NetReport(
        avg = if (t.isEmpty()) -1 else t.average().toInt(),
        min = t.minOrNull() ?: -1,
        jitter = if (t.size < 2) 0 else (t.max() - t.min()),
        rssi = if (ok) wifi.rssi else null,
        mbps = if (ok) wifi.linkSpeed else null,
        mhz = if (ok && android.os.Build.VERSION.SDK_INT >= 21) wifi.frequency else null
    )
}

fun analyzeUrl(raw: String): Pair<Int, List<String>> {
    val input = raw.trim().let { if (it.contains("://")) it else "http://$it" }
    val uri = try {
        URI(input)
    } catch (_: Exception) {
        return 60 to listOf("r_bad")
    }

    val host = (uri.host ?: return 60 to listOf("r_bad")).lowercase()
    var score = 0
    val why = mutableListOf<String>()

    fun add(p: Int, k: String) {
        score += p
        why += k
    }

    if (uri.scheme != "https") add(15, "r_http")
    if (Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(host)) add(35, "r_ip")
    if (uri.rawUserInfo != null || input.contains("@")) add(25, "r_at")
    if (host.split('.').size > 4) add(15, "r_sub")
    if (host.contains("xn--")) add(25, "r_punny")
    if (host.substringAfterLast('.') in setOf("zip", "tk", "ml", "ga", "cf", "gq", "top", "xyz", "click")) add(15, "r_tld")
    if (Regex("login|verify|secure|account|update|bank|wallet|paypal|apple|google").containsMatchIn(host)) add(20, "r_kw")
    if (input.length > 100) add(10, "r_long")

    return score.coerceAtMost(100) to why
}

suspend fun checkUpdate(): JSONObject? = withContext(Dispatchers.IO) {
    try {
        val c = URL(UPDATE_URL).openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 5000
        JSONObject(c.inputStream.bufferedReader().readText())
    } catch (_: Exception) {
        null
    }
}
