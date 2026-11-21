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
 * Android vigila esas zonas aunque la app esté cerrada y avisa a GeofenceReceiver
 * al entrar (notas "al llegar") o al salir (notas "al salir").
 */
object GeofenceHelper {
    private const val TAG = "Geovallas"
    private const val MAX_FENCES = 100 // límite de Android por app
    const val NEAR_PREFIX = "near:"

    fun hasFine(ctx: Context) = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasBackground(ctx: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    fun hasActivity(ctx: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            granted(ctx, Manifest.permission.ACTIVITY_RECOGNITION)

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

        Motion.sync(ctx)
        client.removeGeofences(pi).addOnCompleteListener {
            if (!hasFine(ctx) || !hasBackground(ctx)) {
                onDone?.invoke(); return@addOnCompleteListener
            }
            val instant = TrackerService.isEnabled(ctx)
            val all = Store.active(ctx)

            fun mainFence(p: Place): Geofence? {
                var transitions = 0
                if (p.pendingFor(Trigger.ARRIVE).isNotEmpty()) transitions = transitions or Geofence.GEOFENCE_TRANSITION_ENTER
                if (p.pendingFor(Trigger.LEAVE).isNotEmpty()) transitions = transitions or Geofence.GEOFENCE_TRANSITION_EXIT
                if (transitions == 0) return null
                return Geofence.Builder()
                    .setRequestId(p.id)
                    .setCircularRegion(p.lat, p.lng, p.radius)
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(transitions)
                    .setNotificationResponsiveness(30_000)
                    .build()
            }

            // Zona grande: al entrar despierta el "aviso al instante" (casi sin gastar batería)
            fun nearFence(p: Place): Geofence? {
                if (p.pending.isEmpty()) return null
                return Geofence.Builder()
                    .setRequestId(NEAR_PREFIX + p.id)
                    .setCircularRegion(p.lat, p.lng, p.radius + TrackerService.APPROACH_MARGIN)
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
                    .setNotificationResponsiveness(60_000)
                    .build()
            }

            val real = all.filter { !Chains.isVirtual(it.id) }
            val center = Chains.center(ctx)
            val branches = all.filter { Chains.isVirtual(it.id) && it.pending.isNotEmpty() }
                .let { list ->
                    if (center == null) list
                    else list.sortedBy { it.distanceTo(center.first, center.second) }
                }

            val fences = mutableListOf<Geofence>()
            // Primero tus lugares guardados (siempre caben), luego las sucursales más cercanas.
            real.forEach { p ->
                mainFence(p)?.let { fences += it }
                if (instant) nearFence(p)?.let { fences += it }
            }
            // Al salir de esta zona se buscan las sucursales de tu nueva ubicación
            if (center != null && branches.isNotEmpty()) {
                fences += Geofence.Builder()
                    .setRequestId(Chains.RING_ID)
                    .setCircularRegion(center.first, center.second, Chains.RING_RADIUS_M)
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
                    .setNotificationResponsiveness(60_000)
                    .build()
            }
            branches.take(35).forEach { p -> mainFence(p)?.let { fences += it } }
            if (instant) branches.take(12).forEach { p -> nearFence(p)?.let { fences += it } }
            if (fences.size > MAX_FENCES) fences.subList(MAX_FENCES, fences.size).clear()

            if (fences.isEmpty()) {
                onDone?.invoke(); return@addOnCompleteListener
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
