package com.gigaconet.recordatorios

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity

/**
 * Detecta cuando empiezas a caminar o a ir en vehículo (sensor del teléfono, casi sin batería)
 * para volver a encender el aviso al instante si estás cerca de un lugar.
 */
object Motion {
    private fun pi(ctx: Context): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(
            ctx, 5, Intent(ctx, MotionReceiver::class.java).setAction("com.gigaconet.recordatorios.MOTION"), flags
        )
    }

    @SuppressLint("MissingPermission")
    fun sync(context: Context) {
        val ctx = context.applicationContext
        val client = ActivityRecognition.getClient(ctx)
        val want = TrackerService.isEnabled(ctx) && GeofenceHelper.hasActivity(ctx) &&
            Store.load(ctx).any { it.pending.isNotEmpty() }
        try {
            if (!want) { client.removeActivityTransitionUpdates(pi(ctx)); return }
            val transitions = listOf(
                DetectedActivity.WALKING, DetectedActivity.RUNNING,
                DetectedActivity.ON_BICYCLE, DetectedActivity.IN_VEHICLE
            ).map {
                ActivityTransition.Builder()
                    .setActivityType(it)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                    .build()
            }
            client.requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pi(ctx))
        } catch (_: Exception) {
        }
    }
}

class MotionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        if (TrackerService.isEnabled(context)) TrackerService.refresh(context)
    }
}
