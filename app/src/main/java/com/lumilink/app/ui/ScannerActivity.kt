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
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.lumilink.app.ConnectionService
import com.lumilink.app.pairing.QrPayload

// Leitura do QR via Google Code Scanner (Play Services): sem permissão de
// câmera, sem PreviewView — o Google cuida da câmera, foco e do modelo.
class ScannerActivity : ComponentActivity() {

    private var status by mutableStateOf("Abrindo leitor…")
    private var detail by mutableStateOf<String?>(null)
    private var canRetry by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Text("Parear com o PC", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(status)
                    detail?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                    if (canRetry) {
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { startScan() }) { Text("TENTAR DE NOVO") }
                    }
                }
            }
        }
        startScan()
    }

    private fun startScan() {
        status = "Abrindo leitor…"
        detail = null
        canRetry = false
        try {
            val options = GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .enableAutoZoom()
                .build()
            GmsBarcodeScanning.getClient(this, options)
                .startScan()
                .addOnSuccessListener { barcode ->
                    val raw = barcode.rawValue
                    if (raw == null) {
                        status = "Nada lido."
                        detail = "O leitor não retornou conteúdo. Tente de novo."
                        canRetry = true
                        return@addOnSuccessListener
                    }
                    val qr = QrPayload.parse(raw)
                    if (qr == null) {
                        status = "QR lido, mas não é do LumiLink."
                        detail = "Aponte para o QR gerado pelo botão “Parear novo celular” no PC."
                        canRetry = true
                        return@addOnSuccessListener
                    }
                    status = "QR lido! Conectando…"
                    startForegroundService(
                        Intent(this, ConnectionService::class.java)
                            .putExtra("qr_ip", qr.ip)
                            .putExtra("qr_port", qr.port)
                            .putExtra("qr_token", qr.token)
                            .putExtra("qr_fp", qr.fingerprint)
                            .putExtra("qr_deviceId", qr.deviceId),
                    )
                    finish()
                }
                .addOnCanceledListener {
                    status = "Leitura cancelada."
                    detail = "Toque em TENTAR DE NOVO e aponte para o QR do PC."
                    canRetry = true
                }
                .addOnFailureListener { e ->
                    status = "Falha ao abrir o leitor."
                    detail = "${e.message}\n" +
                        "(Exige Google Play Services atualizado e internet na 1ª vez, " +
                        "para baixar o módulo do leitor.)"
                    canRetry = true
                }
        } catch (e: Exception) {
            status = "Falha ao abrir o leitor."
            detail = "${e.javaClass.simpleName}: ${e.message}"
            canRetry = true
        }
    }
}
