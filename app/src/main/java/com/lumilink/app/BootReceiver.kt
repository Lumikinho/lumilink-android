package com.lumilink.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lumilink.app.storage.AppSettings
import kotlinx.coroutines.*

// Reinicia o Service após boot, se o toggle estiver ativo.
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (AppSettings(ctx).startOnBoot())
                    ctx.startForegroundService(Intent(ctx, ConnectionService::class.java))
            } catch (e: Exception) { }
        }
    }
}
