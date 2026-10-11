package com.lumilink.app.transport

// 5 falhas por IP em 1 min → bloqueia 5 min.
class RateLimiter(
    private val maxFails: Int = 5,
    private val windowMs: Long = 60_000,
    private val blockMs: Long = 5 * 60_000,
) {
    private val fails = mutableMapOf<String, MutableList<Long>>()
    private val blocked = mutableMapOf<String, Long>()

    @Synchronized
    fun isBlocked(ip: String): Boolean {
        val until = blocked[ip] ?: return false
        if (System.currentTimeMillis() < until) return true
        blocked.remove(ip)
        return false
    }

    @Synchronized
    fun fail(ip: String) {
        val now = System.currentTimeMillis()
        val list = fails.getOrPut(ip) { mutableListOf() }
        list.removeAll { it < now - windowMs }
        list.add(now)
        if (list.size >= maxFails) {
            blocked[ip] = now + blockMs
            list.clear()
        }
    }

    @Synchronized
    fun success(ip: String) {
        fails.remove(ip)
        blocked.remove(ip)
    }
}
