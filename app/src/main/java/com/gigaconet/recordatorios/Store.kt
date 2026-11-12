package com.gigaconet.recordatorios

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Note(
    val id: String = UUID.randomUUID().toString(),
    var text: String,
    var done: Boolean = false
)

data class Place(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    val lat: Double,
    val lng: Double,
    var radius: Float,
    val notes: MutableList<Note> = mutableListOf()
) {
    val pending: List<Note> get() = notes.filter { !it.done }
}

/** Guarda los lugares y notas en el teléfono (sin internet, sin cuenta). */
object Store {
    private const val PREFS = "recuerdame_data"
    private const val KEY = "places"
    private val lock = Any()

    fun load(ctx: Context): MutableList<Place> {
        synchronized(lock) { return loadUnsafe(ctx) }
    }

    private fun loadUnsafe(ctx: Context): MutableList<Place> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                val notesArr = o.optJSONArray("notes") ?: JSONArray()
                val notes = MutableList(notesArr.length()) { j ->
                    val n = notesArr.getJSONObject(j)
                    Note(n.getString("id"), n.getString("text"), n.optBoolean("done", false))
                }
                Place(
                    o.getString("id"), o.getString("name"),
                    o.getDouble("lat"), o.getDouble("lng"),
                    o.getDouble("radius").toFloat(), notes
                )
            }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun save(ctx: Context, places: List<Place>): Unit = synchronized(lock) {
        val arr = JSONArray()
        places.forEach { p ->
            val notes = JSONArray()
            p.notes.forEach { n ->
                notes.put(JSONObject().put("id", n.id).put("text", n.text).put("done", n.done))
            }
            arr.put(
                JSONObject()
                    .put("id", p.id).put("name", p.name)
                    .put("lat", p.lat).put("lng", p.lng)
                    .put("radius", p.radius.toDouble())
                    .put("notes", notes)
            )
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    /** Carga, aplica un cambio y guarda en un solo paso. */
    fun update(ctx: Context, change: (MutableList<Place>) -> Unit): Unit = synchronized(lock) {
        val list = loadUnsafe(ctx)
        change(list)
        save(ctx, list)
    }
}
