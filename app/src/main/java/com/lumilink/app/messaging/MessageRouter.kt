package com.lumilink.app.messaging

import org.json.JSONObject
import java.util.UUID

// { "type": "...", "id": "...", "payload": { } }
data class Message(val type: String, val id: String, val payload: JSONObject) {
    fun toJson(): String = JSONObject()
        .put("type", type).put("id", id).put("payload", payload).toString()

    companion object {
        fun create(type: String, payload: JSONObject = JSONObject()): Message =
            Message(type, UUID.randomUUID().toString().replace("-", ""), payload)
        fun fromJson(raw: String): Message? = try {
            val o = JSONObject(raw)
            Message(o.getString("type"), o.optString("id", ""), o.optJSONObject("payload") ?: JSONObject())
        } catch (e: Exception) { null }
    }
}

// Roteador por `type` — registre funções novas sem mexer na base.
class MessageRouter(private val log: (String) -> Unit) {
    private val handlers = mutableMapOf<String, suspend (Ctx, Message) -> Unit>()

    data class Ctx(
        var deviceId: String? = null,
        var authenticated: Boolean = false,
        val send: suspend (Message) -> Unit,
    )

    fun register(type: String, h: suspend (Ctx, Message) -> Unit) { handlers[type] = h }

    suspend fun route(ctx: Ctx, msg: Message) {
        val h = handlers[msg.type]
        if (h == null) { log("[router] tipo '${msg.type}' ignorado."); return }
        try { h(ctx, msg) } catch (e: Exception) { log("[router] handler '${msg.type}' falhou: ${e.message}") }
    }
}

/** EXEMPLO COMENTADO — registrar "echo":
 *  router.register("echo_response") { _, msg ->
 *      log("[echo] eco: " + msg.payload.optString("text"))
 *  }
 *  // enviar: send(Message.create("echo", JSONObject().put("text", "oi")))
 */
fun MessageRouter.registerEchoExample(log: (String) -> Unit) {
    register("echo_response") { _, msg -> log("[echo] eco: ${msg.payload.optString("text")}") }
}
