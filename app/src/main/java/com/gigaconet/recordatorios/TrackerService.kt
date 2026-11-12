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
import kotlin.math.abs

/**
 * "Aviso al instante", cuidando la batería:
 * - Solo corre cuando estás cerca de un lugar (Android lo despierta con una zona grande alrededor).
 * - Cerca del borde de un lugar revisa rápido con GPS; si estás quieto, revisa lento sin GPS.
 * - Al alejarte de todo, se apaga solo y quita la notificación.
 */
class TrackerService : Service() {

    companion object {
        private const val TAG = "Vigilancia"
        private const val CHANNEL = "vigilancia_min"
        private const val OLD_CHANNEL = "vigilancia"
        private const val NOTIF_ID = 77
        private const val PREFS = "tracker"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_STATE = "inside_state"
        private const val KEY_STATE_TS = "inside_state_ts"
        private const val STATE_MAX_AGE = 30 * 60_000L

        /** Zona grande alrededor de cada lugar donde se activa la vigilancia rápida. */
        const val APPROACH_MARGIN = 700f
        private const val STOP_DISTANCE = 1500f

        private const val MODE_FAST = 0   // GPS cada 5 s: a pocos metros del borde
        private const val MODE_NORMAL = 1 // GPS cada 12 s: acercándote
        private const val MODE_SLOW = 2   // sin GPS cada 30 s: quieto o algo lejos

        fun isEnabled(ctx: Context) =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

        fun setEnabled(ctx: Context, on: Boolean) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
            GeofenceHelper.syncAll(ctx)
            refresh(ctx)
        }

        private fun pendingPlaces(ctx: Context) = Store.load(ctx).filter { it.pending.isNotEmpty() }

        /**
         * Arranca la vigilancia solo si estás cerca de algún lugar con pendientes;
         * si no, la detiene (Android la despertará al acercarte).
         */
        @SuppressLint("MissingPermission")
        fun refresh(ctx: Context) {
            val app = ctx.applicationContext
            val places = pendingPlaces(app)
            if (!isEnabled(app) || !GeofenceHelper.hasFine(app) || places.isEmpty()) {
                stop(app); return
            }
            LocationServices.getFusedLocationProviderClient(app).lastLocation
                .addOnSuccessListener { loc ->
                    val near = loc == null || places.any {
                        it.distanceTo(loc.latitude, loc.longitude) - it.radius < APPROACH_MARGIN
                    }
                    // Sin permiso "todo el tiempo" las zonas de Android no funcionan: vigilar siempre
                    if (near || !GeofenceHelper.hasBackground(app)) start(app) else stop(app)
                }
                .addOnFailureListener { start(app) }
        }

        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, TrackerService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo iniciar la vigilancia", e)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, TrackerService::class.java))
        }
    }

    private lateinit var client: FusedLocationProviderClient
    private var mode = -1
    private var shownText = ""
    private var anchor: Location? = null
    private var anchorTime = 0L
    private var farCount = 0

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
        // Android exige mostrar la notificación enseguida, aunque luego se detenga
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            shownText = "Revisando si estás cerca de tus lugares"
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(shownText), type)
        } catch (e: Exception) {
            Log.w(TAG, "startForeground falló", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (pendingPlaces(this).isEmpty() || !GeofenceHelper.hasFine(this) || !isEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (mode == -1) request(MODE_NORMAL)
        return START_NOT_STICKY
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
            MODE_FAST -> LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000)
                .setMinUpdateIntervalMillis(3_000).build()
            MODE_NORMAL -> LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 12_000)
                .setMinUpdateIntervalMillis(8_000).build()
            else -> LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 30_000)
                .setMinUpdateIntervalMillis(20_000).build()
        }
        try {
            client.removeLocationUpdates(callback)
            client.requestLocationUpdates(req, callback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            stopSelf()
        }
    }

    private fun onFix(loc: Location) {
        val places = pendingPlaces(this)
        if (places.isEmpty()) { stopSelf(); return }

        val state = loadState()
        val acc = if (loc.hasAccuracy()) loc.accuracy else 50f
        var nearestBoundary = Float.MAX_VALUE // distancia al borde que importa
        var nearestName = ""
        var nearestAny = Float.MAX_VALUE

        for (p in places) {
            val d = p.distanceTo(loc.latitude, loc.longitude)
            nearestAny = minOf(nearestAny, d - p.radius)

            if (acc <= maxOf(60f, p.radius)) { // ignorar lecturas muy imprecisas
                val wasInside = state[p.id]
                val inside = if (wasInside == true) d <= p.radius + maxOf(25f, acc) else d <= p.radius
                if (wasInside == null) {
                    state[p.id] = inside // primera lectura: solo recordar dónde estás
                } else if (inside != wasInside) {
                    state[p.id] = inside
                    val t = if (inside) Trigger.ARRIVE else Trigger.LEAVE
                    Notifier.show(this, p, p.pendingFor(t), t)
                }
            }

            // ¿Este borde importa? Dentro solo si hay notas "al salir"; fuera solo si hay "al llegar".
            val inside = state[p.id] ?: (d <= p.radius)
            val matters = if (inside) p.pendingFor(Trigger.LEAVE).isNotEmpty()
            else p.pendingFor(Trigger.ARRIVE).isNotEmpty()
            if (matters) {
                val b = abs(d - p.radius)
                if (b < nearestBoundary) { nearestBoundary = b; nearestName = p.name }
            }
        }
        state.keys.retainAll(places.map { it.id }.toSet())
        saveState(state)

        // Lejos de todo: apagarse; Android la vuelve a despertar al acercarte
        if (nearestAny > STOP_DISTANCE && GeofenceHelper.hasBackground(this)) {
            if (++farCount >= 2) { stopSelf(); return }
        } else farCount = 0

        // ¿Estás quieto? (no te has movido más de 30 m en 2 minutos)
        val now = System.currentTimeMillis()
        val a = anchor
        if (a == null || a.distanceTo(loc) > 30f) { anchor = loc; anchorTime = now }
        val stillFor = now - anchorTime
        val still = stillFor > 2 * 60_000

        // Quieto un rato: apagarse para no gastar batería. Al moverte, el sensor de
        // movimiento la vuelve a encender; y la zona del lugar sigue avisando de respaldo.
        if (GeofenceHelper.hasBackground(this) &&
            (stillFor > 4 * 60_000 || (nearestBoundary == Float.MAX_VALUE && stillFor > 60_000))
        ) {
            stopSelf(); return
        }

        val newMode = when {
            nearestBoundary == Float.MAX_VALUE -> MODE_SLOW
            still -> MODE_SLOW
            nearestBoundary < 200f -> MODE_FAST
            nearestBoundary < 800f -> MODE_NORMAL
            else -> MODE_SLOW
        }
        request(newMode)

        val text = if (nearestName.isNotEmpty()) "Cerca de $nearestName" else "Cerca de tus lugares"
        if (text != shownText) {
            shownText = text
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
        }
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

    // ---------- Notificación fija (Android la exige mientras usa la ubicación) ----------

    private fun buildNotification(text: String): android.app.Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel(OLD_CHANNEL)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Vigilancia cerca de tus lugares", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Aparece solo cuando estás cerca de un lugar con pendientes"
                setShowBadge(false)
            }
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val off = PendingIntent.getBroadcast(
            this, 1,
            Intent(this, ActionReceiver::class.java).setAction(ActionReceiver.ACTION_INSTANT_OFF),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(text)
            .setContentText("Te aviso al instante. Se quita sola al alejarte.")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(open)
            .addAction(0, "Apagar", off)
            .build()
    }
}
