package com.tv.player.core

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.tv.player.data.VodGroup
import com.tv.player.data.VodItem
import com.tv.player.data.toJsonString
import com.tv.player.data.vodItemsFromJson
import org.json.JSONArray
import org.json.JSONObject

/** Favorites and watch history in a local SQLite database. */
object Library {
    data class Entry(
        val key: String,
        val title: String,
        val pic: String,
        val items: List<VodItem>,
        val ep: Int = 0,
        val epName: String = "",
        val posMs: Long = 0,
        val time: Long = System.currentTimeMillis(),
        /** Favorites only: episodes the user has seen listed, and the most the sources currently list (0 = unknown). */
        val seen: Int = 0,
        val latest: Int = 0,
    ) {
        /** Episodes added since the user last opened the series. */
        val fresh get() = if (seen > 0 && latest > seen) latest - seen else 0

        fun toGroup(withProgress: Boolean) =
            VodGroup(key, title, pic, if (withProgress && epName.isNotEmpty()) "看到 $epName" else "", items)

        fun toFavoriteGroup() = VodGroup(
            key, title, pic,
            when {
                fresh > 0 -> "更新 +$fresh 集 · 共 $latest 集"
                latest > 1 -> "更新至 $latest 集"
                else -> ""
            },
            items,
            if (fresh > 0) "新" else "",
        )
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, "tv.db", null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE favorites(k TEXT PRIMARY KEY, title TEXT NOT NULL, pic TEXT NOT NULL, items TEXT NOT NULL, time INTEGER NOT NULL, " +
                    "seen INTEGER NOT NULL DEFAULT 0, latest INTEGER NOT NULL DEFAULT 0)",
            )
            db.execSQL(
                "CREATE TABLE history(k TEXT PRIMARY KEY, title TEXT NOT NULL, pic TEXT NOT NULL, items TEXT NOT NULL, " +
                    "ep INTEGER NOT NULL, ep_name TEXT NOT NULL, pos INTEGER NOT NULL, time INTEGER NOT NULL)",
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE favorites ADD COLUMN seen INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE favorites ADD COLUMN latest INTEGER NOT NULL DEFAULT 0")
            }
        }
    }

    private lateinit var helper: Helper
    private val db get() = helper.writableDatabase

    fun init(context: Context) {
        helper = Helper(context.applicationContext)
    }

    private fun favorite(c: Cursor) =
        Entry(c.getString(0), c.getString(1), c.getString(2), vodItemsFromJson(c.getString(3)), time = c.getLong(4), seen = c.getInt(5), latest = c.getInt(6))

    private fun history(c: Cursor) =
        Entry(c.getString(0), c.getString(1), c.getString(2), vodItemsFromJson(c.getString(3)), c.getInt(4), c.getString(5), c.getLong(6), c.getLong(7))

    fun favorites(): List<Entry> =
        db.rawQuery("SELECT k,title,pic,items,time,seen,latest FROM favorites ORDER BY time DESC", null).use { c ->
            buildList { while (c.moveToNext()) add(favorite(c)) }
        }

    fun history(limit: Int = 100): List<Entry> =
        db.rawQuery("SELECT k,title,pic,items,ep,ep_name,pos,time FROM history ORDER BY time DESC LIMIT ?", arrayOf(limit.toString())).use { c ->
            buildList { while (c.moveToNext()) add(history(c)) }
        }

    fun progress(key: String): Entry? =
        db.rawQuery("SELECT k,title,pic,items,ep,ep_name,pos,time FROM history WHERE k=?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) history(c) else null
        }

    fun isFavorite(key: String): Boolean =
        db.rawQuery("SELECT 1 FROM favorites WHERE k=?", arrayOf(key)).use { it.moveToFirst() }

    fun setFavorite(e: Entry, on: Boolean) {
        if (on) {
            val cv = ContentValues().apply {
                put("k", e.key); put("title", e.title); put("pic", e.pic); put("items", e.items.toJsonString()); put("time", System.currentTimeMillis())
                put("seen", e.seen); put("latest", e.latest)
            }
            db.insertWithOnConflict("favorites", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        } else {
            db.delete("favorites", "k=?", arrayOf(e.key))
        }
    }

    /** What the sources list now. The first reading becomes the baseline so an old favorite does not show as new. */
    fun updateLatest(key: String, latest: Int) {
        db.execSQL("UPDATE favorites SET latest=?, seen=CASE WHEN seen=0 THEN ? ELSE seen END WHERE k=?", arrayOf(latest, latest, key))
    }

    /** The user opened the series and saw [count] episodes listed. */
    fun markSeen(key: String, count: Int) {
        db.execSQL("UPDATE favorites SET seen=?, latest=MAX(latest, ?) WHERE k=? AND ?>0", arrayOf(count, count, key, count))
    }

    fun saveProgress(e: Entry) {
        val cv = ContentValues().apply {
            put("k", e.key); put("title", e.title); put("pic", e.pic); put("items", e.items.toJsonString())
            put("ep", e.ep); put("ep_name", e.epName); put("pos", e.posMs); put("time", System.currentTimeMillis())
        }
        db.insertWithOnConflict("history", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun remove(favorites: Boolean, key: String) {
        db.delete(if (favorites) "favorites" else "history", "k=?", arrayOf(key))
    }

    private fun toJson(e: Entry) = JSONObject()
        .put("k", e.key).put("title", e.title).put("pic", e.pic).put("items", JSONArray(e.items.toJsonString()))
        .put("ep", e.ep).put("epName", e.epName).put("pos", e.posMs).put("time", e.time).put("seen", e.seen).put("latest", e.latest)

    private fun fromJson(o: JSONObject) = Entry(
        o.getString("k"), o.getString("title"), o.optString("pic"), vodItemsFromJson(o.getJSONArray("items").toString()),
        o.optInt("ep"), o.optString("epName"), o.optLong("pos"), o.optLong("time", System.currentTimeMillis()),
        o.optInt("seen"), o.optInt("latest"),
    )

    fun exportJson(): JSONObject = JSONObject()
        .put("favorites", JSONArray().also { a -> favorites().forEach { a.put(toJson(it)) } })
        .put("history", JSONArray().also { a -> history(1000).forEach { a.put(toJson(it)) } })

    /**
     * 局域网同步：把另一台设备的记录合并进来。观看进度以时间新的为准；收藏只增不减（在一台上取消收藏不会同步过去）。
     * 返回新增或更新的条数（收藏，观看记录）。
     */
    fun mergeJson(o: JSONObject): Pair<Int, Int> {
        var f = 0
        var h = 0
        val localFavorites = favorites().map { it.key }.toSet()
        db.beginTransaction()
        try {
            o.optJSONArray("favorites")?.let { a ->
                for (i in 0 until a.length()) {
                    val e = fromJson(a.getJSONObject(i))
                    if (e.key in localFavorites) continue
                    db.insertWithOnConflict(
                        "favorites", null,
                        ContentValues().apply {
                            put("k", e.key); put("title", e.title); put("pic", e.pic); put("items", e.items.toJsonString()); put("time", e.time)
                            put("seen", e.seen); put("latest", e.latest)
                        },
                        SQLiteDatabase.CONFLICT_IGNORE,
                    )
                    f++
                }
            }
            o.optJSONArray("history")?.let { a ->
                for (i in 0 until a.length()) {
                    val e = fromJson(a.getJSONObject(i))
                    val local = progress(e.key)
                    if (local != null && local.time >= e.time) continue
                    db.insertWithOnConflict(
                        "history", null,
                        ContentValues().apply {
                            put("k", e.key); put("title", e.title); put("pic", e.pic); put("items", e.items.toJsonString())
                            put("ep", e.ep); put("ep_name", e.epName); put("pos", e.posMs); put("time", e.time)
                        },
                        SQLiteDatabase.CONFLICT_REPLACE,
                    )
                    h++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return f to h
    }

    /** Returns (favorites, history) counts imported. */
    fun importJson(o: JSONObject): Pair<Int, Int> {
        var f = 0
        var h = 0
        db.beginTransaction()
        try {
            o.optJSONArray("favorites")?.let { a ->
                for (i in 0 until a.length()) {
                    val e = fromJson(a.getJSONObject(i))
                    db.insertWithOnConflict(
                        "favorites", null,
                        ContentValues().apply {
                            put("k", e.key); put("title", e.title); put("pic", e.pic); put("items", e.items.toJsonString()); put("time", e.time)
                            put("seen", e.seen); put("latest", e.latest)
                        },
                        SQLiteDatabase.CONFLICT_REPLACE,
                    )
                    f++
                }
            }
            o.optJSONArray("history")?.let { a ->
                for (i in 0 until a.length()) {
                    val e = fromJson(a.getJSONObject(i))
                    db.insertWithOnConflict(
                        "history", null,
                        ContentValues().apply {
                            put("k", e.key); put("title", e.title); put("pic", e.pic); put("items", e.items.toJsonString())
                            put("ep", e.ep); put("ep_name", e.epName); put("pos", e.posMs); put("time", e.time)
                        },
                        SQLiteDatabase.CONFLICT_REPLACE,
                    )
                    h++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return f to h
    }
}
