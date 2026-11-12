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
            description = "Avisos cuando pasas cerca de un lugar guardado"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 200, 400)
        }
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun notificationId(place: Place) = place.id.hashCode()

    @SuppressLint("MissingPermission")
    fun show(ctx: Context, place: Place, pending: List<Note>) {
        if (!GeofenceHelper.hasNotifications(ctx)) return
        ensureChannel(ctx)

        val openIntent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val open = PendingIntent.getActivity(
            ctx, notificationId(place), openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val doneIntent = Intent(ctx, ActionReceiver::class.java)
            .setAction(ActionReceiver.ACTION_DONE)
            .putExtra(ActionReceiver.EXTRA_PLACE_ID, place.id)
        val done = PendingIntent.getBroadcast(
            ctx, notificationId(place), doneIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val inbox = NotificationCompat.InboxStyle()
            .setBigContentTitle("Estás cerca de ${place.name}")
        pending.take(7).forEach { inbox.addLine("• ${it.text}") }
        if (pending.size > 7) inbox.setSummaryText("y ${pending.size - 7} más")

        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Estás cerca de ${place.name}")
            .setContentText(pending.joinToString(", ") { it.text })
            .setStyle(inbox)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, "Listo, ya lo hice", done)
            .build()

        NotificationManagerCompat.from(ctx).notify(notificationId(place), notification)
    }
}
