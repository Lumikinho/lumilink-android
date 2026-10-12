package com.lumilink.app.ui

import android.content.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.lumilink.app.ConnectionService
import com.lumilink.app.storage.AppSettings
import kotlinx.coroutines.*
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class UiPeer(
    val deviceId: String, val name: String, val state: String,
    val lastSeen: Long, val lastIp: String, val bmp: Bitmap?,
)

// Principal: lista de pareados (foto, nome, estado) + parear/manual/atualizar.
class MainActivity : ComponentActivity() {
    private var peers by mutableStateOf(listOf<UiPeer>())
    private var logs by mutableStateOf(listOf<String>())
    private var trackLine by mutableStateOf("")
    private var listenerGranted by mutableStateOf(false)
    private var islandState by mutableStateOf("Ilha: verificando…")
    private var startOnBoot by mutableStateOf(false)
    private val receivers = mutableListOf<BroadcastReceiver>()
    private lateinit var settings: AppSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(
                android.Manifest.permission.POST_NOTIFICATIONS)
        }
        startForegroundService(Intent(this, ConnectionService::class.java))

        fun reg(action: String, fn: (Intent) -> Unit) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) = fn(i)
            }
            registerReceiver(r, IntentFilter(action), RECEIVER_NOT_EXPORTED); receivers += r
        }
        reg("LUMILINK_CHANGED") { refreshPeers() }
        reg("LUMILINK_LOG") { logs = (logs + "> ${it.getStringExtra("line")}".takeLast(200)).takeLast(60) }
        reg("LUMILINK_MEDIA") {
            val t = it.getStringExtra("title")
            trackLine = if (t == null) "" else {
                val mark = when (it.getStringExtra("state")) {
                    "playing" -> "▶ "
                    "paused" -> "⏸ "
                    else -> "⏹ "
                }
                "$mark$t — ${it.getStringExtra("artist")}"
            }
        }
        refreshPeers()
        refreshListenerState()
        CoroutineScope(Dispatchers.IO).launch { startOnBoot = settings.startOnBoot() }

        setContent {
            MaterialTheme {
                Column(Modifier.padding(16.dp)) {
                    val up = peers.count { it.state == "Conectado" }
                    Text("LumiLink (nó P2P)", style = MaterialTheme.typography.headlineSmall)
                    Text(if (up == 0) "Desconectado" else "Conectado a $up dispositivo(s)")
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Button(onClick = {
                            startService(Intent(this@MainActivity, ConnectionService::class.java)
                                .putExtra("action", "new_pairing"))
                            startActivity(Intent(this@MainActivity, PairActivity::class.java))
                        }) { Text("Parear novo") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { startActivity(Intent(this@MainActivity, ManualActivity::class.java)) }) {
                            Text("Manual")
                        }
                    }
                    Row {
                        Button(onClick = { startActivity(Intent(this@MainActivity, ScannerActivity::class.java)) }) {
                            Text("Escanear QR")
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                            refreshPeers()
                            startService(Intent(this@MainActivity, ConnectionService::class.java)
                                .putExtra("action", "ping_now"))
                        }) { Text("Atualizar") }
                    }
                    Button(onClick = { startActivity(Intent(this@MainActivity, ProfileActivity::class.java)) }) {
                        Text("Meu perfil")
                    }
                    Button(onClick = { startActivity(Intent(this@MainActivity, ClipActivity::class.java)) }) {
                        Text("Área de transferência")
                    }
                    if (trackLine.isNotEmpty()) Text("Tocando: $trackLine")
                    Text(islandState, style = MaterialTheme.typography.bodySmall)
                    Row {
                        if (!listenerGranted) {
                            Button(onClick = {
                                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            }) { Text("Acesso a notificações") }
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { testIsland() }) { Text("Testar ilha") }
                    }
                    Text("Pareados:", style = MaterialTheme.typography.titleSmall)
                    LazyColumn(Modifier.height(220.dp)) {
                        items(peers, key = { it.deviceId }) { p ->
                            Row(Modifier.fillMaxWidth().clickable {
                                startActivity(Intent(this@MainActivity, PeerDetailActivity::class.java)
                                    .putExtra("peerId", p.deviceId))
                            }.padding(4.dp)) {
                                p.bmp?.let { Image(it.asImageBitmap(), null, Modifier.size(48.dp)) }
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(p.name, style = MaterialTheme.typography.titleSmall)
                                    Text("${p.state} · ${seenText(p.lastSeen)}",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                Button(onClick = {
                                    startService(Intent(this@MainActivity, ConnectionService::class.java)
                                        .putExtra("action", "ping_peer").putExtra("peerId", p.deviceId))
                                }) { Text("Ping") }
                            }
                        }
                    }
                    Row {
                        Checkbox(startOnBoot, {
                            startOnBoot = it
                            CoroutineScope(Dispatchers.IO).launch { settings.setStartOnBoot(it) }
                        }); Text("Iniciar com o sistema")
                    }
                    Button(onClick = { requestBatteryExemption() }) { Text("Desativar otimização de bateria") }
                    Text("Log:", style = MaterialTheme.typography.titleSmall)
                    LazyColumn(Modifier.height(140.dp)) {
                        items(logs) { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }

    private fun seenText(ts: Long): String {
        if (ts <= 0) return "nunca"
        return SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(ts))
    }

    private fun refreshPeers() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val raw = getSharedPreferences("peers_ui", MODE_PRIVATE)
                    .getString("peers_json", "[]") ?: "[]"
                val arr = JSONArray(raw)
                val list = mutableListOf<UiPeer>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val id = o.getString("deviceId")
                    val f = File(filesDir, "peer_$id.png")
                    list += UiPeer(id, o.optString("name", id), o.optString("state", "?"),
                        o.optLong("lastSeen", 0), o.optString("lastIp", ""),
                        if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null)
                }
                peers = list
            } catch (e: Exception) { }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPeers()
        refreshListenerState()
        refreshIslandState()
        sendBroadcast(Intent("LUMILINK_MEDIA_REQ"))
    }

    private fun refreshIslandState() {
        CoroutineScope(Dispatchers.IO).launch {
            val ok = try {
                com.lumilink.app.notification.FocusIsland.hasFocusPermission(this@MainActivity)
            } catch (e: Exception) { false }
            islandState = if (ok) "Ilha: pronta" else "Ilha: sem permissão de foco"
        }
    }

    private fun testIsland() {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.createNotificationChannel(android.app.NotificationChannel("lumilink_ping",
                "LumiLink ping", android.app.NotificationManager.IMPORTANCE_DEFAULT))
            val n = androidx.core.app.NotificationCompat.Builder(this, "lumilink_ping")
                .setContentTitle("Teste LumiLink")
                .setContentText("Se a ilha acender, está funcionando.")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setAutoCancel(true).setTimeoutAfter(10000).build()
            com.lumilink.app.notification.FocusIsland.attachMedia(
                this, n, "Teste LumiLink", "Se a ilha acender, está funcionando.")
            nm.notify(2002, n)
        } catch (e: Exception) { }
    }

    private fun refreshListenerState() {
        listenerGranted = androidx.core.app.NotificationManagerCompat
            .getEnabledListenerPackages(this).contains(packageName)
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:$packageName")))
        }
    }

    override fun onDestroy() { receivers.forEach { unregisterReceiver(it) }; super.onDestroy() }
}
