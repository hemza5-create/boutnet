package com.securenet.optimizer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    App()
                }
            }
        }
    }
}

@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("p", 0) }

    var ar by remember { mutableStateOf(prefs.getBoolean("ar", true)) }
    var fw by remember { mutableStateOf(prefs.getBoolean("fw", true)) }
    var dns by remember { mutableStateOf(prefs.getString("dns", "cloudflare") ?: "cloudflare") }
    val s = if (ar) AR else EN
    fun t(k: String) = s[k] ?: k
    val on by VpnState.running

    fun startVpn() = ctx.startService(
        Intent(ctx, SecureVpnService::class.java)
            .putExtra("dns", dns)
            .putExtra("fw", fw)
    )

    fun stopVpn() = ctx.startService(Intent(ctx, SecureVpnService::class.java).setAction("STOP"))

    val vpnPerm = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) startVpn()
    }

    fun toggle(enable: Boolean) {
        if (!enable) {
            stopVpn()
            return
        }
        val i = VpnService.prepare(ctx)
        if (i != null) vpnPerm.launch(i) else startVpn()
    }

    fun restartIfOn() {
        if (on) startVpn()
    }

    var report by remember { mutableStateOf<NetReport?>(null) }
    var busy by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf("") }
    var verdict by remember { mutableStateOf<Pair<Int, List<String>>?>(null) }
    var updMsg by remember { mutableStateOf<String?>(null) }
    var updUrl by remember { mutableStateOf<String?>(null) }

    CompositionLocalProvider(
        LocalLayoutDirection provides if (ar) LayoutDirection.Rtl else LayoutDirection.Ltr
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = t("title"),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = { ar = !ar; prefs.edit().putBoolean("ar", ar).apply() }) {
                    Text(t("lang"))
                }
            }

            Section(t("fw")) {
                Text(t("fw_d"))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${t("blocked")}: ${VpnState.blocked.intValue}", modifier = Modifier.weight(1f))
                    Switch(
                        checked = fw,
                        onCheckedChange = {
                            fw = it
                            prefs.edit().putBoolean("fw", it).apply()
                            restartIfOn()
                        }
                    )
                }
            }

            Section(t("dns")) {
                Text(t("dns_d"))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = dns == "cloudflare",
                        onClick = {
                            dns = "cloudflare"
                            prefs.edit().putString("dns", dns).apply()
                            restartIfOn()
                        },
                        label = { Text(t("cf")) }
                    )
                    FilterChip(
                        selected = dns == "google",
                        onClick = {
                            dns = "google"
                            prefs.edit().putString("dns", dns).apply()
                            restartIfOn()
                        },
                        label = { Text(t("goog")) }
                    )
                }
                Button(
                    onClick = { toggle(!on) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (on) Color(0xFF2E7D32) else Color(0xFF616161)
                    )
                ) {
                    Text(if (on) "● ${t("on")}" else "○ ${t("off")}")
                }
            }

            Section(t("booster")) {
                Button(
                    onClick = {
                        busy = true
                        scope.launch {
                            report = measureNetwork(ctx)
                            busy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (busy) "…" else t("run"))
                }

                report?.let { r ->
                    Text("${t("ping")}: ${r.avg} / ${r.min} ms")
                    Text("${t("jitter")}: ${r.jitter} ms")
                    r.rssi?.let { Text("${t("signal")}: $it dBm") }
                    r.mbps?.let { Text("${t("speed")}: $it Mbps") }
                    r.mhz?.let { Text("${t("band")}: ${if (it > 4900) "5 GHz" else "2.4 GHz"}") }

                    val tips = buildList {
                        if ((r.rssi ?: 0) in -120..-75) add("tip_sig")
                        if ((r.mhz ?: 5000) in 2400..2500) add("tip_24")
                        if (r.avg > 120 || r.avg < 0) add("tip_ping")
                    }.ifEmpty { listOf("tip_ok") }

                    tips.forEach { Text("• ${t(it)}", color = MaterialTheme.colorScheme.primary) }
                }
            }

            Section(t("phish")) {
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        verdict = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(t("hint")) },
                    singleLine = true
                )
                Button(
                    onClick = { verdict = analyzeUrl(url) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = url.isNotBlank()
                ) {
                    Text(t("scan"))
                }

                verdict?.let { (score, why) ->
                    val (label, col) = when {
                        score < 25 -> t("safe") to Color(0xFF66BB6A)
                        score < 55 -> t("warn") to Color(0xFFFFA726)
                        else -> t("danger") to Color(0xFFEF5350)
                    }
                    Text("$label  ($score/100)", color = col, fontWeight = FontWeight.Bold)
                    why.forEach { Text("• ${t(it)}") }
                }
            }

            Section(t("upd")) {
                Button(
                    onClick = {
                        scope.launch {
                            val j = checkUpdate()
                            when {
                                j == null -> {
                                    updMsg = t("fail")
                                    updUrl = null
                                }
                                j.optLong("versionCode") > BuildConfig.VERSION_CODE -> {
                                    updMsg = "${t("newv")}: ${j.optString("versionName")}\n${j.optString("notes")}"
                                    updUrl = j.optString("url").takeIf { it.startsWith("https://") }
                                }
                                else -> {
                                    updMsg = t("latest")
                                    updUrl = null
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(t("check"))
                }
                updMsg?.let { Text(it) }
                updUrl?.let { u ->
                    Button(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) }) {
                        Text(t("dl"))
                    }
                }
            }
        }
    }
}
