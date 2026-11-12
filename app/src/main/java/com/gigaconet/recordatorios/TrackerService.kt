package com.gigaconet.recordatorios

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.json.JSONObject

/**
 * "Aviso al instante": vigila el GPS mientras haya notas pendientes.
 * Cerca de un lugar revisa cada pocos segundos; lejos, cada minuto, para cuidar la batería.
 */
class TrackerService : Service() {

    companion object {
        private const val TAG = "Vigilancia"
        private const val CHANNEL = "vigilancia"
        private const val NOTIF_ID = 77
        private const val PREFS = "tracker"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_STATE = "inside_state"
        private const val KEY_STATE_TS = "inside_state_ts"
        private const val STATE_MAX_AGE = 30 * 60_000L

        private const val MODE_FAR = 0
        private const val MODE_MID = 1
        private const val MODE_NEAR = 2

        fun isEnabled(ctx: Context) =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

        fun setEnabled(ctx: Context, on: Boolean) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
            refresh(ctx)
        }

        /** Arranca o detiene la vigilancia según haya notas pendientes. */
        fun refresh(ctx: Context) {
            val app = ctx.applicationContext
            val intent = Intent(app, TrackerService::class.java)
            val need = isEnabled(app) && GeofenceHelper.hasFine(app) &&
                Store.load(app).any { it.pending.isNotEmpty() }
            try {
                if (need) ContextCompat.startForegroundService(app, intent) else app.stopService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo iniciar la vigilancia", e)
            }
        }
    }

    private lateinit var client: FusedLocationProviderClient
    private var mode = -1
    private var shownCount = -1

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { onFix(it) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        client = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val count = Store.load(this).count { it.pending.isNotEmpty() }
        // Android exige mostrar el aviso fijo enseguida, aunque luego se detenga
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(count), type)
            shownCount = count
        } catch (e: Exception) {
            Log.w(TAG, "startForeground falló", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (count == 0 || !GeofenceHelper.hasFine(this) || !isEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (mode == -1) request(MODE_NEAR) // primera lectura rápida
        return START_STICKY
    }

    override fun onDestroy() {
        try { client.removeLocationUpdates(callback) } catch (_: Exception) {}
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun request(newMode: Int) {
        if (newMode == mode) return
        mode = newMode
        val req = when (newMode) {
            MODE_FAR -> LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 60_000)
                .setMinUpdateIntervalMillis(30_000).build()
            MODE_MID -> LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 15_000)
                .setMinUpdateIntervalMillis(8_000).build()
            else -> LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 4_000)
                .setMinUpdateIntervalMillis(2_000).build()
        }
        try {
            client.removeLocationUpdates(callback)
            client.requestLocationUpdates(req, callback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            stopSelf()
        }
    }

    private fun onFix(loc: Location) {
        val places = Store.load(this).filter { it.pending.isNotEmpty() }
        if (places.isEmpty()) { stopSelf(); return }

        val state = loadState()
        val acc = if (loc.hasAccuracy()) loc.accuracy else 50f
        var nearestEdge = Float.MAX_VALUE

        for (p in places) {
            val d = p.distanceTo(loc.latitude, loc.longitude)
            nearestEdge = minOf(nearestEdge, d - p.radius)
            if (acc > maxOf(60f, p.radius)) continue // lectura del GPS muy imprecisa

            val wasInside = state[p.id]
            val inside = if (wasInside == true) {
                d <= p.radius + maxOf(25f, acc) // margen para no avisar dos veces al borde
            } else {
                d <= p.radius
            }
            if (wasInside == null) {
                state[p.id] = inside // primera lectura: solo recordar dónde estás
                continue
            }
            if (inside != wasInside) {
                state[p.id] = inside
                val t = if (inside) Trigger.ARRIVE else Trigger.LEAVE
                Notifier.show(this, p, p.pendingFor(t), t)
            }
        }
        state.keys.retainAll(places.map { it.id }.toSet())
        saveState(state)

        if (places.size != shownCount) {
            shownCount = places.size
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(places.size))
        }
        request(
            when {
                nearestEdge > 3000 -> MODE_FAR
                nearestEdge > 600 -> MODE_MID
                else -> MODE_NEAR
            }
        )
    }

    // ---------- Recordar si estás dentro o fuera de cada lugar ----------

    private fun loadState(): MutableMap<String, Boolean> {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ts = prefs.getLong(KEY_STATE_TS, 0)
        if (System.currentTimeMillis() - ts > STATE_MAX_AGE) return mutableMapOf()
        return try {
            val o = JSONObject(prefs.getString(KEY_STATE, "{}") ?: "{}")
            o.keys().asSequence().associateWith { o.getBoolean(it) }.toMutableMap()
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    private fun saveState(state: Map<String, Boolean>) {
        val o = JSONObject()
        state.forEach { (k, v) -> o.put(k, v) }
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_STATE, o.toString())
            .putLong(KEY_STATE_TS, System.currentTimeMillis())
            .apply()
    }

    // ---------- Aviso fijo en la barra ----------

    private fun buildNotification(count: Int): android.app.Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Vigilancia activa", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Aviso fijo mientras la app revisa tu ubicación"
                setShowBadge(false)
            }
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = when (count) {
            0 -> "Sin pendientes"
            1 -> "1 lugar con pendientes. Te aviso al instante."
            else -> "$count lugares con pendientes. Te aviso al instante."
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Recuérdame Aquí está atento")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .build()
    }
}
