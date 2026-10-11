package com.lumilink.app.ui

import android.content.*
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lumilink.app.ConnectionService
import com.lumilink.app.storage.PairStore
import kotlinx.coroutines.*

// Tela mínima: estado, Escanear QR, Esquecer PC, toggle boot, Meu perfil, isenção bateria.
class MainActivity : ComponentActivity() {
    private lateinit var pairs: PairStore
    private var state by mutableStateOf("Desconectado")
    private var logs by mutableStateOf(listOf<String>())
    private var startOnBoot by mutableStateOf(false)
    private val receivers = mutableListOf<BroadcastReceiver>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pairs = PairStore(this)
        ConnectionService.start(this)
        onSvc { startForegroundService(Intent(this, ConnectionService::class.java)) }

        fun reg(action: String, fn: (Intent) -> Unit) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) = fn(i)
            }
            registerReceiver(r, IntentFilter(action), RECEIVER_NOT_EXPORTED); receivers += r
        }
        reg("LUMILINK_STATE") { state = it.getStringExtra("state") ?: state }
        reg("LUMILINK_LOG") { logs = (logs + "> ${it.getStringExtra("line")}".takeLast(200)).takeLast(60) }
        refreshState() // lê o estado persistido (caso o service já esteja conectado)

        CoroutineScope(Dispatchers.IO).launch { startOnBoot = pairs.startOnBoot() }

        setContent {
            MaterialTheme {
                Column(Modifier.padding(16.dp)) {
                    Text("LumiLink (celular)", style = MaterialTheme.typography.headlineSmall)
                    Text("Estado: $state")
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { startActivity(Intent(this@MainActivity, ScannerActivity::class.java)) }) {
                        Text("Escanear QR")
                    }
                    Button(onClick = {
                        CoroutineScope(Dispatchers.IO).launch { pairs.forget(); }
                    }) { Text("Esquecer PC") }
                    Button(onClick = { startActivity(Intent(this@MainActivity, ProfileActivity::class.java)) }) {
                        Text("Meu perfil")
                    }
                    Row { Checkbox(startOnBoot, {
                        startOnBoot = it
                        CoroutineScope(Dispatchers.IO).launch { pairs.setStartOnBoot(it) }
                    }); Text("Iniciar com o sistema") }
                    Button(onClick = { requestBatteryExemption() }) { Text("Desativar otimização de bateria") }
                    Text("Log:", style = MaterialTheme.typography.titleSmall)
                    logs.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }

    private fun onSvc(fn: () -> Unit) = fn()

    private fun requestBatteryExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:$packageName")))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshState() // volta pra tela sempre mostra o estado atual
    }

    private fun refreshState() {
        val sp = getSharedPreferences("svc", MODE_PRIVATE)
        state = sp.getString("state", null) ?: state
    }

    override fun onDestroy() { receivers.forEach { unregisterReceiver(it) }; super.onDestroy() }
}
