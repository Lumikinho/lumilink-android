package com.lumilink.app.ui

import android.graphics.Bitmap
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
import com.lumilink.app.pairing.QrPayload
import kotlinx.coroutines.*
import org.json.JSONObject

// Mostra QR + código 6 dígitos + IP:porta (vale 2 min). Qualquer nó pode parear aqui.
class PairActivity : ComponentActivity() {
    private var qrBmp by mutableStateOf<Bitmap?>(null)
    private var code by mutableStateOf("")
    private var addr by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Column(Modifier.padding(16.dp)) {
                    Text("Parear novo dispositivo", style = MaterialTheme.typography.headlineSmall)
                    Text("Escaneie ou digite IP:porta + código (2 min).")
                    qrBmp?.let { Image(it.asImageBitmap(), null, Modifier.size(260.dp)) }
                    if (code.isNotEmpty()) Text("Código: ${code.take(3)} ${code.drop(3)}",
                        style = MaterialTheme.typography.headlineMedium)
                    if (addr.isNotEmpty()) Text(addr)
                }
            }
        }
        CoroutineScope(Dispatchers.IO).launch {
            repeat(15) {
                val raw = getSharedPreferences("pairing_ui", MODE_PRIVATE).getString("json", null)
                if (raw != null) {
                    try {
                        val o = JSONObject(raw)
                        code = o.optString("code", "")
                        addr = "${o.optString("ip", "")}:${o.optInt("port", 0)}"
                        qrBmp = QrPayload.toBitmap(o.optString("qr", ""))
                    } catch (e: Exception) { }
                    return@launch
                }
                delay(200)
            }
        }
    }
}
