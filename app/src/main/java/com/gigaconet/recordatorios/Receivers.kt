package com.gigaconet.recordatorios

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent

/** Android lo llama cuando entras o sales de la zona de un lugar guardado. */
class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            // Si el GPS se apagó y se volvió a encender, Android puede borrar las geovallas
            if (event.errorCode == GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE) {
                GeofenceHelper.syncAll(context)
            }
            return
        }
        val trigger = when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> Trigger.ARRIVE
            Geofence.GEOFENCE_TRANSITION_EXIT -> Trigger.LEAVE
            else -> return
        }

        val places = Store.load(context)
        event.triggeringGeofences?.forEach { fence ->
            val place = places.find { it.id == fence.requestId } ?: return@forEach
            Notifier.show(context, place, place.pendingFor(trigger), trigger)
        }
    }
}

/** Botón "Listo, ya lo hice" de la notificación. */
class ActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_DONE = "com.gigaconet.recordatorios.DONE"
        const val EXTRA_PLACE_ID = "place_id"
        const val EXTRA_TRIGGER = "trigger"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DONE) return
        val placeId = intent.getStringExtra(EXTRA_PLACE_ID) ?: return
        val trigger = if (intent.getStringExtra(EXTRA_TRIGGER) == "LEAVE") Trigger.LEAVE else Trigger.ARRIVE
        Store.update(context) { list ->
            list.find { it.id == placeId }?.notes?.filter { it.trigger == trigger }?.forEach { it.done = true }
        }
        NotificationManagerCompat.from(context).cancel(Notifier.notificationId(placeId, trigger))
        val result = goAsync()
        GeofenceHelper.syncAll(context) { result.finish() }
    }
}

/** Al reiniciar el teléfono Android borra las geovallas: aquí se vuelven a crear. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            val result = goAsync()
            GeofenceHelper.syncAll(context) { result.finish() }
        }
    }
}
