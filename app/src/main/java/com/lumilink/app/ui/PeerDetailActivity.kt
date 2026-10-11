package com.lumilink.app.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.lumilink.app.ConnectionService
import kotlinx.coroutines.*
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Detalhe do par: perfil, último IP, última conexão, Reconectar agora, Esquecer.
class PeerDetailActivity : ComponentActivity() {
    private var name by mutableStateOf("")
    private var state by mutableStateOf("")
    private var addr by mutableStateOf("")
    private var seen by mutableStateOf("")
    private var id by mutableStateOf("")
    private var bmp by mutableStateOf<Bitmap?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        id = intent.getStringExtra("peerId") ?: ""
        setContent {
            MaterialTheme {
                Column(Modifier.padding(16.dp)) {
                    bmp?.let { Image(it.asImageBitmap(), null, Modifier.size(72.dp)) }
                    Text(name, style = MaterialTheme.typography.headlineSmall)
                    Text("Estado: $state")
                    Text("id: $id", style = MaterialTheme.typography.bodySmall)
                    Text("Último IP: $addr")
                    Text("Última conexão: $seen")
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        startService(Intent(this@PeerDetailActivity, ConnectionService::class.java)
                            .putExtra("action", "reconnect_now").putExtra("peerId", id))
                    }) { Text("Reconectar agora") }
                    Button(onClick = {
                        startService(Intent(this@PeerDetailActivity, ConnectionService::class.java)
                            .putExtra("action", "forget").putExtra("peerId", id))
                        finish()
                    }) { Text("Esquecer") }
                }
            }
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val raw = getSharedPreferences("peers_ui", MODE_PRIVATE)
                    .getString("peers_json", "[]") ?: "[]"
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (o.getString("deviceId") == id) {
                        name = o.optString("name", id)
                        state = o.optString("state", "?")
                        addr = o.optString("lastIp", "").ifEmpty { "(descoberta pendente)" }
                        val ts = o.optLong("lastSeen", 0)
                        seen = if (ts <= 0) "nunca"
                        else SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(ts))
                    }
                }
                val f = File(filesDir, "peer_$id.png")
                if (f.exists()) bmp = BitmapFactory.decodeFile(f.absolutePath)
            } catch (e: Exception) { }
        }
    }
}
