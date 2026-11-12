package com.gigaconet.recordatorios

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notifier {
    const val CHANNEL_ID = "recordatorios_lugar"

    fun ensureChannel(ctx: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID, "Recordatorios por lugar", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Avisos al llegar o salir de un lugar guardado"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 200, 400)
        }
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** Aviso de ejemplo para escuchar cómo suena. */
    @SuppressLint("MissingPermission")
    fun showTest(ctx: Context) {
        if (!GeofenceHelper.hasNotifications(ctx)) return
        ensureChannel(ctx)
        val title = "Estás cerca de Supermercado (ejemplo)"
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText("Comprar pan y leche")
            .setStyle(NotificationCompat.InboxStyle().setBigContentTitle(title).addLine("• Comprar pan y leche"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(TEST_ID, notification)
    }

    private const val TEST_ID = 4242

    fun notificationId(placeId: String, trigger: Trigger) = (placeId + trigger.name).hashCode()

    @SuppressLint("MissingPermission")
    fun show(ctx: Context, place: Place, notes: List<Note>, trigger: Trigger) {
        if (notes.isEmpty() || !GeofenceHelper.hasNotifications(ctx)) return
        ensureChannel(ctx)
        val id = notificationId(place.id, trigger)

        // El GPS rápido y Android pueden detectar lo mismo: no sonar dos veces
        val prefs = ctx.getSharedPreferences("ultimos_avisos", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(id.toString(), 0) < 3 * 60_000) return
        prefs.edit().putLong(id.toString(), now).apply()

        val openIntent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val open = PendingIntent.getActivity(
            ctx, id, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val doneIntent = Intent(ctx, ActionReceiver::class.java)
            .setAction(ActionReceiver.ACTION_DONE)
            .putExtra(ActionReceiver.EXTRA_PLACE_ID, place.id)
            .putExtra(ActionReceiver.EXTRA_TRIGGER, trigger.name)
        val done = PendingIntent.getBroadcast(
            ctx, id, doneIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = when (trigger) {
            Trigger.ARRIVE -> "Estás cerca de ${place.name}"
            Trigger.LEAVE -> "Saliste de ${place.name}. No olvides:"
        }
        val inbox = NotificationCompat.InboxStyle().setBigContentTitle(title)
        notes.take(7).forEach { inbox.addLine("• ${it.text}") }
        if (notes.size > 7) inbox.setSummaryText("y ${notes.size - 7} más")

        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(notes.joinToString(", ") { it.text })
            .setStyle(inbox)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, "Listo, ya lo hice", done)
            .build()

        NotificationManagerCompat.from(ctx).notify(id, notification)
    }
}
