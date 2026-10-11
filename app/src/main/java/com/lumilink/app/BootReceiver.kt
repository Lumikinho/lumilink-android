package com.lumilink.app

import android.content.*
import kotlinx.coroutines.*

// Reinicia o Foreground Service após boot, se o usuário ativou o toggle.
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        CoroutineScope(Dispatchers.IO).launch {
            val store = com.lumilink.app.storage.PairStore(ctx)
            if (store.startOnBoot()) {
                ctx.startForegroundService(Intent(ctx, ConnectionService::class.java))
            }
        }
    }
}
