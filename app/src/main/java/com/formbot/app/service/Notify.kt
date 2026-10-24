package com.formbot.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.formbot.app.R
import com.formbot.app.ui.MainActivity

/** Каналы и уведомления слежения за растениями. */
object Notify {
    const val CH_WATCH = "watch"
    const val CH_PLANTS = "plants"
    const val CH_QUIET = "plants_quiet"
    const val CH_PROBLEM = "problem"

    const val ID_WATCH = 10
    const val ID_PLANTS = 11
    const val ID_NONE = 12
    const val ID_CHANGED = 13
    private var problemId = 100

    fun channels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_WATCH, "Ожидание растений", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(
            NotificationChannel(CH_PLANTS, "Растения появились", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) }
        )
        nm.createNotificationChannel(NotificationChannel(CH_QUIET, "Растений сегодня нет", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(
            NotificationChannel(CH_PROBLEM, "Проблемы с заявкой", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) }
        )
    }

    fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(MainActivity.EXTRA_TAB_RUN, true),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun watching(context: Context, text: String): Notification =
        NotificationCompat.Builder(context, CH_WATCH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Жду растения")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context))
            .build()

    fun show(context: Context, id: Int, channel: String, title: String, text: String) {
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(if (channel == CH_QUIET) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, n) }  // нет разрешения — молча
    }

    /** Форма заполнена — нажатие открывает её в браузере, «Отправить» жмёт пользователь. */
    fun prepared(context: Context, plant: String, url: String) {
        channels(context)
        val id = problemId++
        val open = PendingIntent.getActivity(
            context, id, Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CH_PLANTS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("📝 Форма заполнена — отправьте сами")
            .setContentText(plant.ifBlank { "Откройте и нажмите «Отправить»" })
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, "Открыть форму", open)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, n) }
    }

    fun problem(context: Context, text: String) {
        channels(context)
        show(context, problemId++, CH_PROBLEM, "⚠️ Заявка", text)
    }
}
