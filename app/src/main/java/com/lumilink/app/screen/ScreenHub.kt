package com.lumilink.app.screen

import java.util.concurrent.ConcurrentLinkedQueue

// Ponte service→tela: frames chegam pelo router e a Activity consome.
object ScreenHub {
    data class Frame(val seq: Int, val data: ByteArray, val key: Boolean)

    @Volatile var active = false
    @Volatile var fromPeer = ""
    @Volatile var fromName = ""
    @Volatile var width = 0
    @Volatile var height = 0
    val frames = ConcurrentLinkedQueue<Frame>()
    @Volatile var rxParts = 0
    @Volatile var rxFrames = 0
    private val parts = mutableMapOf<Int, MutableList<ByteArray>>()
    private var csd0: ByteArray? = null
    private var csd1: ByteArray? = null

    @Synchronized
    fun pushCsd(which: Int, data: ByteArray) {
        if (!active) return
        if (which == -1) csd0 = data else csd1 = data
    }

    @Synchronized
    fun takeCsd(): Pair<ByteArray, ByteArray>? {
        val a = csd0
        val b = csd1
        return if (a != null && b != null) Pair(a, b) else null
    }

    @Synchronized
    fun pushPart(seq: Int, part: Int, partsTotal: Int, key: Boolean, data: ByteArray) {
        if (!active) return
        if (seq < 0 || partsTotal <= 1) {
            // csd ou frame único: direto p/ fila
            frames.offer(Frame(seq, data, key))
            while (frames.size > 90) frames.poll()
            return
        }
        val list = parts.getOrPut(seq) { mutableListOf() }
        // garante índice
        while (list.size <= part) list.add(ByteArray(0))
        list[part] = data
        if (list.size == partsTotal && list.all { it.isNotEmpty() }) {
            val total = list.sumOf { it.size }
            val out = ByteArray(total)
            var off = 0
            for (b in list) {
                b.copyInto(out, off)
                off += b.size
            }
            parts.remove(seq)
            frames.offer(Frame(seq, out, key))
            while (frames.size > 90) frames.poll()
        }
        if (parts.size > 4) {
            val oldest = parts.keys.minOrNull()
            if (oldest != null) parts.remove(oldest)
        }
    }

    @Synchronized
    fun reset(peerId: String, peerName: String, w: Int, h: Int) {
        active = true
        fromPeer = peerId
        fromName = peerName
        width = w
        height = h
        frames.clear()
        parts.clear()
        csd0 = null
        csd1 = null
    }

    @Synchronized
    fun stop() {
        active = false
        frames.clear()
        parts.clear()
    }
}
