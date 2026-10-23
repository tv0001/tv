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
    ) {
        fun toGroup(withProgress: Boolean) =
            VodGroup(key, title, pic, if (withProgress && epName.isNotEmpty()) "看到 $epName" else "", items)
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, "tv.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE favorites(k TEXT PRIMARY KEY, title TEXT NOT NULL, pic TEXT NOT NULL, items TEXT NOT NULL, time INTEGER NOT NULL)")
            db.execSQL(
                "CREATE TABLE history(k TEXT PRIMARY KEY, title TEXT NOT NULL, pic TEXT NOT NULL, items TEXT NOT NULL, " +
                    "ep INTEGER NOT NULL, ep_name TEXT NOT NULL, pos INTEGER NOT NULL, time INTEGER NOT NULL)",
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private lateinit var helper: Helper
    private val db get() = helper.writableDatabase

    fun init(context: Context) {
        helper = Helper(context.applicationContext)
    }

    private fun favorite(c: Cursor) = Entry(c.getString(0), c.getString(1), c.getString(2), vodItemsFromJson(c.getString(3)), time = c.getLong(4))

    private fun history(c: Cursor) =
        Entry(c.getString(0), c.getString(1), c.getString(2), vodItemsFromJson(c.getString(3)), c.getInt(4), c.getString(5), c.getLong(6), c.getLong(7))

    fun favorites(): List<Entry> =
        db.rawQuery("SELECT k,title,pic,items,time FROM favorites ORDER BY time DESC", null).use { c ->
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
            }
            db.insertWithOnConflict("favorites", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        } else {
            db.delete("favorites", "k=?", arrayOf(e.key))
        }
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
        .put("ep", e.ep).put("epName", e.epName).put("pos", e.posMs).put("time", e.time)

    private fun fromJson(o: JSONObject) = Entry(
        o.getString("k"), o.getString("title"), o.optString("pic"), vodItemsFromJson(o.getJSONArray("items").toString()),
        o.optInt("ep"), o.optString("epName"), o.optLong("pos"), o.optLong("time", System.currentTimeMillis()),
    )

    fun exportJson(): JSONObject = JSONObject()
        .put("favorites", JSONArray().also { a -> favorites().forEach { a.put(toJson(it)) } })
        .put("history", JSONArray().also { a -> history(1000).forEach { a.put(toJson(it)) } })

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
