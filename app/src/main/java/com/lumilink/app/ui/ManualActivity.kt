package com.lumilink.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lumilink.app.ConnectionService

// Conexão manual: IP + porta + código de 6 dígitos (obrigatório p/ PC↔PC).
class ManualActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        var ip by mutableStateOf("")
        var port by mutableStateOf("")
        var code by mutableStateOf("")
        var err by mutableStateOf<String?>(null)
        setContent {
            MaterialTheme {
                Column(Modifier.padding(16.dp)) {
                    Text("Conectar manualmente", style = MaterialTheme.typography.headlineSmall)
                    OutlinedTextField(ip, { ip = it }, label = { Text("IP do par") })
                    OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) },
                        label = { Text("Porta") })
                    OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) },
                        label = { Text("Código (6 dígitos)") })
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        val p = port.toIntOrNull()
                        if (ip.isBlank() || p == null || p !in 1..65535 || code.length != 6) {
                            err = "Confira IP, porta e os 6 dígitos."
                            return@Button
                        }
                        startService(Intent(this@ManualActivity, ConnectionService::class.java)
                            .putExtra("m_ip", ip.trim()).putExtra("m_port", p).putExtra("m_code", code))
                        finish()
                    }) { Text("Parear") }
                    err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}
