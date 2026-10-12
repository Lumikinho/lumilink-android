package com.lumilink.app.notification

import android.app.Notification
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Bundle
import org.json.JSONObject

// HyperIsland (HyperOS): a MESMA notificação + extras miui.focus.param.
// Em aparelho sem suporte cai para notificação normal sozinha (sem erro).
object FocusIsland {
    fun supported(): Boolean =
        android.os.Build.MANUFACTURER.contains("xiaomi", ignoreCase = true)

    // Consulta opcional (IPC; não chamar na main thread).
    fun hasFocusPermission(ctx: Context): Boolean {
        return try {
            val extras = Bundle().apply { putString("package", ctx.packageName) }
            val out = ctx.contentResolver.call(
                android.net.Uri.parse("content://miui.statusbar.notification.public"),
                "canShowFocus", null, extras)
            out?.getBoolean("canShowFocus", false) == true
        } catch (e: Exception) { false }
    }

    fun attach(ctx: Context, n: Notification, title: String, text: String) {
        if (!supported()) return
        try {
            n.extras.putString("miui.focus.param", paramJson(title.take(32), text.take(96), false))
            val pics = Bundle()
            pics.putParcelable("miui.focus.pic_lumi",
                Icon.createWithResource(ctx, android.R.drawable.stat_sys_data_bluetooth))
            n.extras.putBundle("miui.focus.pics", pics)
        } catch (e: Exception) { }
    }

    // Template de mídia (ilha grande + pequena), espelhando o exemplo da doc oficial.
    fun attachMedia(ctx: Context, n: Notification, title: String, text: String) {
        if (!supported()) return
        try {
            n.extras.putString("miui.focus.param", paramJson(title.take(32), text.take(96), true))
            val pics = Bundle()
            pics.putParcelable("miui.focus.pic_lumi",
                Icon.createWithResource(ctx, android.R.drawable.stat_sys_data_bluetooth))
            n.extras.putBundle("miui.focus.pics", pics)
        } catch (e: Exception) { }
    }

    private fun paramJson(title: String, text: String, media: Boolean): String {
        val small = JSONObject().put("picInfo",
            JSONObject().put("type", 1).put("pic", "miui.focus.pic_lumi"))
        val island = JSONObject().put("smallIslandArea", small)
        if (media) {
            val left = JSONObject()
                .put("type", 1)
                .put("picInfo", JSONObject().put("type", 1).put("pic", "miui.focus.pic_lumi"))
                .put("miui.focus.paramtextInfo", JSONObject()
                    .put("frontTitle", "LumiLink")
                    .put("title", title)
                    .put("content", text)
                    .put("useHighLight", false))
            island.put("islandProperty", 1)
            island.put("bigIslandArea", JSONObject().put("imageTextInfoLeft", left))
            island.put("shareData", JSONObject().put("title", title))
        }
        val base = JSONObject().put("title", title).put("content", text).put("type", 2)
        val v2 = JSONObject()
            .put("protocol", 1)
            .put("business", "lumilink")
            .put("enableFloat", false)
            .put("updatable", true)
            .put("ticker", title)
            .put("aodTitle", title)
            .put("baseInfo", base)
            .put("param_island", island)
        return JSONObject().put("param_v2", v2).toString()
    }
}
