package com.gigaconet.recordatorios

import android.content.Context
import android.location.Location
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Cuándo avisar: al llegar al lugar o al salir de él. */
enum class Trigger { ARRIVE, LEAVE }

data class Note(
    val id: String = UUID.randomUUID().toString(),
    var text: String,
    var trigger: Trigger = Trigger.ARRIVE,
    var done: Boolean = false
)

data class Place(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var address: String = "",
    var lat: Double,
    var lng: Double,
    var radius: Float,
    val notes: MutableList<Note> = mutableListOf()
) {
    val pending: List<Note> get() = notes.filter { !it.done }
    fun pendingFor(t: Trigger) = pending.filter { it.trigger == t }

    fun distanceTo(lat: Double, lng: Double): Float {
        val r = FloatArray(1)
        Location.distanceBetween(this.lat, this.lng, lat, lng, r)
        return r[0]
    }
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
                    Note(
                        id = n.getString("id"),
                        text = n.getString("text"),
                        trigger = if (n.optString("trigger") == "LEAVE") Trigger.LEAVE else Trigger.ARRIVE,
                        done = n.optBoolean("done", false)
                    )
                }
                Place(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    address = o.optString("address", ""),
                    lat = o.getDouble("lat"),
                    lng = o.getDouble("lng"),
                    radius = o.getDouble("radius").toFloat(),
                    notes = notes
                )
            }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun saveUnsafe(ctx: Context, places: List<Place>) {
        val arr = JSONArray()
        places.forEach { p ->
            val notes = JSONArray()
            p.notes.forEach { n ->
                notes.put(
                    JSONObject().put("id", n.id).put("text", n.text)
                        .put("trigger", n.trigger.name).put("done", n.done)
                )
            }
            arr.put(
                JSONObject()
                    .put("id", p.id).put("name", p.name).put("address", p.address)
                    .put("lat", p.lat).put("lng", p.lng)
                    .put("radius", p.radius.toDouble())
                    .put("notes", notes)
            )
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    /** Carga, aplica un cambio y guarda en un solo paso. */
    fun update(ctx: Context, change: (MutableList<Place>) -> Unit) {
        synchronized(lock) {
            val list = loadUnsafe(ctx)
            change(list)
            saveUnsafe(ctx, list)
        }
    }
}
