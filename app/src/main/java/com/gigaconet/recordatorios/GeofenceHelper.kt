package com.gigaconet.recordatorios

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

/**
 * Registra en Android una "geovalla" (círculo) por cada lugar con notas pendientes.
 * Android vigila esas zonas aunque la app esté cerrada y avisa a GeofenceReceiver.
 */
object GeofenceHelper {
    private const val TAG = "Geovallas"
    private const val MAX_FENCES = 100 // límite de Android por app

    fun hasFine(ctx: Context) = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasBackground(ctx: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    fun hasNotifications(ctx: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            granted(ctx, Manifest.permission.POST_NOTIFICATIONS)

    private fun granted(ctx: Context, p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    private fun pendingIntent(ctx: Context): PendingIntent {
        val intent = Intent(ctx, GeofenceReceiver::class.java)
            .setAction("com.gigaconet.recordatorios.GEOFENCE")
        // Las geovallas necesitan un PendingIntent MUTABLE en Android 12+
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(ctx, 0, intent, flags)
    }

    /** Borra todas las geovallas y vuelve a crear las de lugares con notas pendientes. */
    @SuppressLint("MissingPermission")
    fun syncAll(context: Context, onDone: (() -> Unit)? = null) {
        val ctx = context.applicationContext
        val client = LocationServices.getGeofencingClient(ctx)
        val pi = pendingIntent(ctx)

        client.removeGeofences(pi).addOnCompleteListener {
            if (!hasFine(ctx) || !hasBackground(ctx)) {
                onDone?.invoke(); return@addOnCompleteListener
            }
            val places = Store.load(ctx).filter { it.pending.isNotEmpty() }.take(MAX_FENCES)
            if (places.isEmpty()) {
                onDone?.invoke(); return@addOnCompleteListener
            }
            val fences = places.map { p ->
                Geofence.Builder()
                    .setRequestId(p.id)
                    .setCircularRegion(p.lat, p.lng, p.radius)
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
                    .setNotificationResponsiveness(30_000)
                    .build()
            }
            // Sin aviso inicial: si creas el lugar estando ahí, no te avisa al instante.
            val request = GeofencingRequest.Builder()
                .setInitialTrigger(0)
                .addGeofences(fences)
                .build()
            client.addGeofences(request, pi)
                .addOnSuccessListener { Log.i(TAG, "Geovallas activas: ${fences.size}") }
                .addOnFailureListener { e -> Log.w(TAG, "No se pudieron crear las geovallas", e) }
                .addOnCompleteListener { onDone?.invoke() }
        }
    }
}
