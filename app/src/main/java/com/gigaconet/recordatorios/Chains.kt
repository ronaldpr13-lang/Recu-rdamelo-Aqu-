package com.gigaconet.recordatorios

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Tasks
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Una sucursal encontrada en el mapa (OpenStreetMap). */
data class Branch(val name: String, val lat: Double, val lng: Double)

/**
 * Avisos por comercio ("cualquier sucursal del Banco Reservas").
 *
 * Cómo funciona: cada cierto tiempo (y al salir de la zona donde se buscó) se buscan en
 * OpenStreetMap las sucursales a 8 km de ti. Cada una se vigila como un lugar más.
 * Así el teléfono solo vigila las cercanas y no gasta batería de más.
 */
object Chains {
    private const val PREFS = "cadenas"
    private const val KEY_CACHE = "cache"
    private const val KEY_LAT = "lat"
    private const val KEY_LNG = "lng"
    private const val KEY_TS = "ts"

    const val VIRTUAL_PREFIX = "br:"
    const val RING_ID = "ring"
    const val SEARCH_RADIUS_M = 8000
    const val RING_RADIUS_M = 3000f
    const val MAX_PER_CHAIN = 40

    /** Para refrescar la pantalla cuando termina una búsqueda. */
    @Volatile var onUpdated: (() -> Unit)? = null

    fun isVirtual(id: String) = id.startsWith(VIRTUAL_PREFIX)

    /** "br:<id del comercio>:<n>" -> id del comercio. */
    fun baseId(id: String) = if (isVirtual(id)) id.removePrefix(VIRTUAL_PREFIX).substringBefore(":") else id

    // ---------- Datos guardados ----------

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadCache(ctx: Context): Map<String, List<Branch>> {
        val raw = prefs(ctx).getString(KEY_CACHE, null) ?: return emptyMap()
        return try {
            val o = JSONObject(raw)
            o.keys().asSequence().associateWith { id ->
                val arr = o.getJSONArray(id)
                List(arr.length()) { i ->
                    val b = arr.getJSONObject(i)
                    Branch(b.getString("n"), b.getDouble("a"), b.getDouble("o"))
                }
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** null = todavía no se ha buscado; lista vacía = se buscó y no hay cerca. */
    fun branches(ctx: Context, chainId: String): List<Branch>? = loadCache(ctx)[chainId]

    /** Dónde estabas cuando se buscaron las sucursales. */
    fun center(ctx: Context): Pair<Double, Double>? {
        val p = prefs(ctx)
        if (!p.contains(KEY_LAT)) return null
        return Pair(java.lang.Double.longBitsToDouble(p.getLong(KEY_LAT, 0)), java.lang.Double.longBitsToDouble(p.getLong(KEY_LNG, 0)))
    }

    fun lastSearchTime(ctx: Context): Long = prefs(ctx).getLong(KEY_TS, 0)

    fun save(ctx: Context, found: Map<String, List<Branch>>, at: Location?) {
        val o = JSONObject()
        found.forEach { (id, list) ->
            val arr = JSONArray()
            list.forEach { arr.put(JSONObject().put("n", it.name).put("a", it.lat).put("o", it.lng)) }
            o.put(id, arr)
        }
        val e = prefs(ctx).edit().putString(KEY_CACHE, o.toString()).putLong(KEY_TS, System.currentTimeMillis())
        if (at != null) {
            e.putLong(KEY_LAT, java.lang.Double.doubleToRawLongBits(at.latitude))
            e.putLong(KEY_LNG, java.lang.Double.doubleToRawLongBits(at.longitude))
        } else {
            e.remove(KEY_LAT).remove(KEY_LNG)
        }
        e.apply()
    }

    /** Convierte las sucursales encontradas en "lugares" que el resto de la app ya sabe vigilar. */
    fun virtualPlaces(ctx: Context, chains: List<Place>): List<Place> {
        if (chains.isEmpty()) return emptyList()
        val cache = loadCache(ctx)
        return chains.flatMap { c ->
            (cache[c.id] ?: emptyList()).mapIndexed { i, b ->
                Place(
                    id = "$VIRTUAL_PREFIX${c.id}:$i", name = b.name, address = c.name,
                    lat = b.lat, lng = b.lng, radius = c.radius, notes = c.notes
                )
            }
        }
    }

    // ---------- Búsquedas en segundo plano ----------

    private fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Busca ahora las sucursales cerca de ti. */
    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        val req = OneTimeWorkRequestBuilder<ChainWorker>()
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork("cadenas_ahora", ExistingWorkPolicy.REPLACE, req)
    }

    /** Repaso cada 12 horas por si abren sucursales nuevas o cambiaste de zona sin que Android avisara. */
    fun schedulePeriodic(ctx: Context) {
        val app = ctx.applicationContext
        val req = PeriodicWorkRequestBuilder<ChainWorker>(12, TimeUnit.HOURS)
            .setConstraints(constraints())
            .build()
        WorkManager.getInstance(app)
            .enqueueUniquePeriodicWork("cadenas_periodico", ExistingPeriodicWorkPolicy.KEEP, req)
    }
}

class ChainWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    @SuppressLint("MissingPermission")
    private fun currentLocation(): Location? {
        val client = LocationServices.getFusedLocationProviderClient(applicationContext)
        val fresh = try {
            Tasks.await(
                client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, CancellationTokenSource().token),
                20, TimeUnit.SECONDS
            )
        } catch (e: Exception) {
            null
        }
        return fresh ?: try {
            Tasks.await(client.lastLocation, 5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            null
        }
    }

    private fun giveUp() = if (runAttemptCount < 3) Result.retry() else Result.success()

    override fun doWork(): Result {
        val app = applicationContext
        val chains = Store.load(app).filter { it.chain != null && it.pending.isNotEmpty() }
        if (chains.isEmpty()) {
            Chains.save(app, emptyMap(), null)
            GeofenceHelper.syncAll(app)
            return Result.success()
        }
        if (!GeofenceHelper.hasFine(app)) return Result.success()

        val loc = currentLocation() ?: return giveUp()
        val found = mutableMapOf<String, List<Branch>>()
        var failed = false
        for (c in chains) {
            try {
                found[c.id] = Overpass.search(c.chain!!, loc.latitude, loc.longitude)
            } catch (e: Exception) {
                failed = true
            }
        }
        if (failed && runAttemptCount < 3) return Result.retry()
        if (found.isEmpty()) return Result.success()

        Chains.save(app, found, loc)
        GeofenceHelper.syncAll(app)
        TrackerService.refresh(app)
        Handler(Looper.getMainLooper()).post { Chains.onUpdated?.invoke() }
        return Result.success()
    }
}

/** Búsqueda de comercios en OpenStreetMap (gratis, sin clave). */
object Overpass {
    private val ENDPOINTS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter"
    )
    private val STOP_WORDS = setOf("de", "del", "la", "el", "los", "las", "y")

    private fun charClass(c: Char): String = when (c) {
        'a', 'á' -> "[aáAÁ]"
        'e', 'é' -> "[eéEÉ]"
        'i', 'í' -> "[iíIÍ]"
        'o', 'ó' -> "[oóOÓ]"
        'u', 'ú', 'ü' -> "[uúüUÚÜ]"
        'n', 'ñ' -> "[nñNÑ]"
        else -> c.toString()
    }

    /**
     * "Banco Reservas" -> encuentra "Banco de Reservas", "BANCO DE RESERVAS", "Banreservas"...
     * Ignora mayúsculas, tildes y palabras sueltas como "de".
     */
    fun regexFor(text: String): String {
        val words = text.lowercase(Locale.ROOT).split(Regex("\\s+"))
            .map { w -> w.filter { it.isLetterOrDigit() } }
            .filter { it.isNotEmpty() && it !in STOP_WORDS }
        if (words.isEmpty()) return ""
        val main = words.joinToString(".*") { w -> w.map { charClass(it) }.joinToString("") }
        val alias = if ("reservas" in words) "|[bB]anreservas" else ""
        return main + alias
    }

    @Throws(IOException::class)
    fun search(text: String, lat: Double, lng: Double): List<Branch> {
        val rx = regexFor(text)
        if (rx.isEmpty()) return emptyList()
        val r = Chains.SEARCH_RADIUS_M
        val query = "[out:json][timeout:25];(" +
            "nwr[\"name\"~\"$rx\",i](around:$r,$lat,$lng);" +
            "nwr[\"brand\"~\"$rx\",i](around:$r,$lat,$lng);" +
            ");out center 150;"
        var last: Exception? = null
        for (url in ENDPOINTS) {
            try {
                return parse(post(url, query), text, lat, lng)
            } catch (e: Exception) {
                last = e
            }
        }
        throw IOException("Sin respuesta de OpenStreetMap", last)
    }

    private fun post(url: String, query: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 35_000
            conn.doOutput = true
            conn.setRequestProperty("User-Agent", "RecuerdameAqui/5.0 (Android)")
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray()) }
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(body: String, fallbackName: String, lat: Double, lng: Double): List<Branch> {
        val elements = JSONObject(body).optJSONArray("elements") ?: return emptyList()
        val all = mutableListOf<Branch>()
        for (i in 0 until elements.length()) {
            val e = elements.getJSONObject(i)
            val c = e.optJSONObject("center")
            val la = if (e.has("lat")) e.getDouble("lat") else c?.optDouble("lat", Double.NaN) ?: Double.NaN
            val lo = if (e.has("lon")) e.getDouble("lon") else c?.optDouble("lon", Double.NaN) ?: Double.NaN
            if (la.isNaN() || lo.isNaN()) continue
            val tags = e.optJSONObject("tags")
            val name = tags?.optString("name").orEmpty().ifBlank { tags?.optString("brand").orEmpty() }
                .ifBlank { fallbackName }
            all += Branch(name, la, lo)
        }
        fun dist(b: Branch): Float {
            val r = FloatArray(1)
            Location.distanceBetween(lat, lng, b.lat, b.lng, r)
            return r[0]
        }
        fun between(a: Branch, b: Branch): Float {
            val r = FloatArray(1)
            Location.distanceBetween(a.lat, a.lng, b.lat, b.lng, r)
            return r[0]
        }
        // Más cercanas primero; quitar duplicados (el edificio y su punto en el mapa)
        val kept = mutableListOf<Branch>()
        for (b in all.sortedBy { dist(it) }) {
            if (kept.none { between(it, b) < 40f }) kept += b
            if (kept.size >= Chains.MAX_PER_CHAIN) break
        }
        return kept
    }
}
