package com.lumilink.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.core.content.FileProvider
import com.lumilink.app.storage.AppSettings
import com.lumilink.app.storage.ClipItem
import com.lumilink.app.storage.ClipStore
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Histórico da área de transferência (SQLite): copiar, apagar, limpar, toggle sync.
class ClipActivity : ComponentActivity() {
    private lateinit var store: ClipStore
    private lateinit var settings: AppSettings
    private var items by mutableStateOf(listOf<ClipItem>())
    private var thumbs by mutableStateOf(mapOf<Long, Bitmap>())
    private var syncOn by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ClipStore(this)
        settings = AppSettings(this)
        setContent {
            MaterialTheme {
                Column(Modifier.padding(16.dp)) {
                    Text("Área de transferência", style = MaterialTheme.typography.headlineSmall)
                    Row {
                        Checkbox(syncOn, {
                            syncOn = it
                            CoroutineScope(Dispatchers.IO).launch { settings.setClipSync(it) }
                        }); Text("Sincronizar com pares")
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { refresh() }) { Text("Atualizar") }
                    }
                    LazyColumn(Modifier.height(380.dp)) {
                        items(items, key = { it.id }) { item ->
                            Row(Modifier.fillMaxWidth().clickable { activate(item) }.padding(4.dp)) {
                                thumbs[item.id]?.let { Image(it.asImageBitmap(), null, Modifier.size(48.dp)) }
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(titleOf(item), style = MaterialTheme.typography.titleSmall)
                                    Text(subOf(item), style = MaterialTheme.typography.bodySmall)
                                }
                                Button(onClick = {
                                    CoroutineScope(Dispatchers.IO).launch {
                                        store.delete(item.id); refresh()
                                    }
                                }) { Text("X") }
                            }
                        }
                    }
                    Button(onClick = {
                        CoroutineScope(Dispatchers.IO).launch { store.clear(); refresh() }
                    }) { Text("Limpar tudo") }
                }
            }
        }
        refresh()
        CoroutineScope(Dispatchers.IO).launch { syncOn = settings.clipSync() }
    }

    private fun titleOf(i: ClipItem): String = when (i.kind) {
        "text" -> if (i.text.length > 60) i.text.take(60) + "…" else i.text
        "image" -> "Imagem (${i.size / 1024} KB)"
        else -> i.name
    }

    private fun subOf(i: ClipItem): String {
        val when_ = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(i.ts))
        return "$when_ · de ${i.source}"
    }

    private fun refresh() {
        CoroutineScope(Dispatchers.IO).launch {
            val list = store.list(100)
            val tm = mutableMapOf<Long, Bitmap>()
            for (i in list) {
                if (i.kind == "image") {
                    try {
                        val b = BitmapFactory.decodeFile(i.path)
                        if (b != null) tm[i.id] = b
                    } catch (e: Exception) { }
                }
            }
            items = list
            thumbs = tm
        }
    }

    private fun activate(item: ClipItem) {
        CoroutineScope(Dispatchers.IO).launch {
            val full = store.get(item.id) ?: return@launch
            when (full.kind) {
                "text" -> {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("LumiLink", full.text))
                }
                "image" -> {
                    val f = File(full.path)
                    if (f.exists()) {
                        val uri = FileProvider.getUriForFile(this@ClipActivity,
                            "com.lumilink.app.files", f)
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newUri(contentResolver, "image", uri))
                    }
                }
                else -> openFile(full.path, full.name)
            }
        }
    }

    private fun openFile(path: String, name: String) {
        try {
            val f = File(path)
            if (!f.exists()) return
            val uri: Uri = FileProvider.getUriForFile(this, "com.lumilink.app.files", f)
            val mime = contentResolver.getType(uri) ?: "*/*"
            val it = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(it, name))
        } catch (e: Exception) { }
    }

    override fun onResume() { super.onResume(); refresh() }
}
