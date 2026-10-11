package com.lumilink.app.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.lumilink.app.ConnectionService
import com.lumilink.app.profile.ProfileDefaults
import com.lumilink.app.profile.ProfileStore
import kotlinx.coroutines.*

// "Meu perfil": nome (32), foto via Photo Picker (sem permissões), preview, Restaurar, Salvar -> profile_update.
class ProfileActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val deviceId = getSharedPreferences("dev", MODE_PRIVATE).getString("deviceId", "android")!!
        val store = ProfileStore(this, deviceId)
        var name by mutableStateOf(ProfileDefaults.defaultName(this))
        var bmp by mutableStateOf<android.graphics.Bitmap?>(null)
        var resetToDefault by mutableStateOf(false)
        val systemDefault = ProfileDefaults.defaultName(this)

        val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) {
                contentResolver.openInputStream(uri)?.use {
                    bmp = BitmapFactory.decodeStream(it)
                    resetToDefault = false
                }
            }
        }

        CoroutineScope(Dispatchers.IO).launch {
            // Se foto padrão ainda não existe, gera avatar com iniciais.
            if (!store.ownPhotoFile().exists())
                store.initialsAvatar(ProfileDefaults.defaultName(this@ProfileActivity), store.ownPhotoFile())
        }

        setContent {
            MaterialTheme {
                Column(Modifier.padding(16.dp)) {
                    Text("Meu perfil", style = MaterialTheme.typography.headlineSmall)
                    Text("Como o PC vai ver:")
                    Text(name)
                    bmp?.let { Image(it.asImageBitmap(), null, Modifier.size(64.dp)) }
                    OutlinedTextField(name, { if (it.length <= 32) name = it }, label = { Text("Nome (máx 32)") })
                    Row {
                        Button(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                            Text("Alterar foto")
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                            name = ProfileDefaults.defaultName(this@ProfileActivity)
                            resetToDefault = true
                            bmp = null
                            CoroutineScope(Dispatchers.IO).launch {
                                store.initialsAvatar(name, store.ownPhotoFile())
                            }
                        }) { Text("Restaurar padrão") }
                    }
                    Button(onClick = {
                        val clean = store.sanitize(name)
                        if (clean.isEmpty()) return@Button
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                val picked = bmp
                                if (resetToDefault && picked == null) {
                                    store.saveOwn(clean, nameCustom = clean != systemDefault, photoCustom = false)
                                } else {
                                    if (picked != null) store.prepareAvatar(picked, store.ownPhotoFile())
                                    store.saveOwn(clean, nameCustom = true,
                                        photoCustom = store.isPhotoCustom() || picked != null)
                                }
                                // Avisa o parceiro na hora, se conectado.
                                this@ProfileActivity.startService(
                                    Intent(this@ProfileActivity, ConnectionService::class.java)
                                        .putExtra("action", "profile_update"))
                            } catch (e: Exception) { /* erro vai pro logcat */ }
                        }
                        finish()
                    }) { Text("Salvar") }
                }
            }
        }
    }
}
