package com.lumilink.app.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

data class ClipItem(
    val id: Long, val ts: Long, val kind: String, val text: String,
    val mime: String, val path: String, val name: String, val size: Long,
    val sha: String, val source: String, val peer: String,
)

// Histórico em SQLite. Blobs em files/clips/.
class ClipStore(ctx: Context) : SQLiteOpenHelper(ctx, "clips.db", null, 1) {
    val clipDir = File(ctx.filesDir, "clips").apply { mkdirs() }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE clips (
          id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL,
          kind TEXT NOT NULL, text TEXT DEFAULT '', mime TEXT DEFAULT '',
          path TEXT DEFAULT '', name TEXT DEFAULT '', size INTEGER DEFAULT 0,
          sha TEXT DEFAULT '', source TEXT DEFAULT '', peer TEXT DEFAULT '')""")
        db.execSQL("CREATE INDEX idx_clips_ts ON clips(ts DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
        db.execSQL("DROP TABLE IF EXISTS clips")
        onCreate(db)
    }

    @Synchronized
    fun insert(kind: String, text: String = "", mime: String = "", path: String = "",
        name: String = "", size: Long = 0, sha: String = "",
        source: String = "", peer: String = ""): Long {
        val cv = ContentValues().apply {
            put("ts", System.currentTimeMillis()); put("kind", kind); put("text", text)
            put("mime", mime); put("path", path); put("name", name)
            put("size", size); put("sha", sha); put("source", source); put("peer", peer)
        }
        return writableDatabase.insert("clips", null, cv)
    }

    fun addText(t: String, source: String, peer: String) = insert("text", text = t, source = source, peer = peer)

    @Synchronized
    fun list(limit: Int = 100): List<ClipItem> {
        val out = mutableListOf<ClipItem>()
        readableDatabase.query("clips", null, null, null, null, null, "id DESC", "$limit").use { c ->
            while (c.moveToNext()) out += ClipItem(
                c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4),
                c.getString(5), c.getString(6), c.getLong(7), c.getString(8), c.getString(9), c.getString(10))
        }
        return out
    }

    fun get(id: Long): ClipItem? = list(100000).firstOrNull { it.id == id }

    @Synchronized
    fun delete(id: Long) {
        val item = get(id)
        writableDatabase.delete("clips", "id=?", arrayOf("$id"))
        try {
            if (item != null && item.path.isNotEmpty()) {
                val f = File(item.path)
                if (f.exists() && f.parent == clipDir.absolutePath) f.delete()
            }
        } catch (e: Exception) { }
    }

    @Synchronized
    fun clear() {
        writableDatabase.delete("clips", null, null)
        try { clipDir.listFiles()?.forEach { it.delete() } } catch (e: Exception) { }
    }
}
