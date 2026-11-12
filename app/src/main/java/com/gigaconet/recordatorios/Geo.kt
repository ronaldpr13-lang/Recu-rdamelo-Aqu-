package com.gigaconet.recordatorios

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Handler
import android.os.Looper
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Direcciones y búsqueda usando el servicio que ya trae Android (gratis, sin clave).
 */
object Geo {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val locale = Locale("es", "DO")

    /** Convierte un punto del mapa en una dirección legible. */
    fun reverse(ctx: Context, lat: Double, lng: Double, cb: (String?) -> Unit) {
        if (!Geocoder.isPresent()) { cb(null); return }
        val app = ctx.applicationContext
        executor.execute {
            val text = try {
                @Suppress("DEPRECATION")
                Geocoder(app, locale).getFromLocation(lat, lng, 1)?.firstOrNull()?.let { format(it) }
            } catch (e: Exception) {
                null
            }
            main.post { cb(text?.ifBlank { null }) }
        }
    }

    /** Busca una dirección o lugar por nombre, primero dentro de República Dominicana. */
    fun search(ctx: Context, query: String, cb: (List<Address>) -> Unit) {
        if (!Geocoder.isPresent()) { cb(emptyList()); return }
        val app = ctx.applicationContext
        executor.execute {
            val results = try {
                val g = Geocoder(app, locale)
                @Suppress("DEPRECATION")
                val inRd = g.getFromLocationName(query, 6, 17.3, -72.1, 20.1, -68.2).orEmpty()
                @Suppress("DEPRECATION")
                inRd.ifEmpty { g.getFromLocationName(query, 6).orEmpty() }
            } catch (e: Exception) {
                emptyList()
            }
            main.post { cb(results) }
        }
    }

    fun format(a: Address): String {
        val street = listOfNotNull(a.thoroughfare, a.subThoroughfare).joinToString(" ").trim()
        val parts = listOf(
            street.ifBlank { a.featureName?.takeIf { it != a.subThoroughfare } },
            a.subLocality,
            a.locality ?: a.subAdminArea
        ).filterNotNull().filter { it.isNotBlank() }.distinct()
        return if (parts.isNotEmpty()) parts.joinToString(", ") else (a.getAddressLine(0) ?: "")
    }

    fun distanceText(meters: Float): String =
        if (meters < 1000) "${((meters / 10).roundToInt() * 10)} m"
        else String.format(locale, "%.1f km", meters / 1000f)
}
