package com.formbot.app.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.formbot.app.core.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration

/**
 * Ожидание поста с растениями: 13:59–14:00 МСК — проверка каждую секунду, дальше —
 * мгновенно по обновлениям Telegram и подстраховкой раз в 30 с, до 14:30.
 */
class PlantWatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notify.channels(this)
        ServiceCompat.startForeground(
            this, Notify.ID_WATCH, Notify.watching(this, "Проверяю канал…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (job?.isActive == true) return START_NOT_STICKY
        AppSettings.init(this)
        PlantRuntime.schedule(this)  // следующий день
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "formbot:watch").apply { acquire(45 * 60 * 1000L) }
        job = scope.launch {
            try { watch() } finally { shutdown() }
        }
        return START_NOT_STICKY
    }

    private fun status(text: String) {
        runCatching { NotificationManagerCompat.from(this).notify(Notify.ID_WATCH, Notify.watching(this, text)) }
    }

    private suspend fun watch() {
        val cfg = AppSettings.value.telegram
        if (!cfg.watch || !cfg.hasSource) return
        Telegram.start(this, cfg)
        val ready = withTimeoutOrNull(60_000) { Telegram.auth.first { it == TgAuth.Ready || it is TgAuth.WaitPhone || it is TgAuth.NoKeys } }
        if (ready != TgAuth.Ready) {
            PlantRuntime.setStatus(WatchPhase.ERROR, "Нет входа в Telegram")
            Notify.problem(this, "Не удалось проверить канал: нет входа в Telegram")
            return
        }
        PlantRuntime.setStatus(WatchPhase.WAITING, "Жду пост до 14:30 МСК…")
        var found = PlantRuntime.state.value.offer != null
        while (true) {
            val now = PlantRuntime.nowMsk()
            val t = now.toLocalTime()
            if (!t.isBefore(PlantRuntime.STOP) || t.isBefore(PlantRuntime.START)) break
            val offer = runCatching { PlantRuntime.refresh(this, notify = true) }
                .onFailure { status("⚠️ ${it.message}") }.getOrNull()
            if (offer != null && !found) {
                found = true
                status("Растения появились: ${offer.items.size}")
            }
            val untilStop = Duration.between(t, PlantRuntime.STOP).toMillis()
            if (!found && t.isBefore(PlantRuntime.FAST_UNTIL)) {
                status("Проверяю каждую секунду до 14:00")
                delay(1_000)
            } else {
                if (!found) status("Жду пост до 14:30 МСК")
                // Новое сообщение или правка в чате — сразу перечитываем; иначе раз в 30 с.
                withTimeoutOrNull(minOf(30_000L, untilStop)) { Telegram.changes.first { it == cfg.chatId } }
            }
        }
        if (!found) {
            PlantRuntime.setStatus(WatchPhase.NONE_TODAY, "Сегодня растений не было")
            Notify.show(this, Notify.ID_NONE, Notify.CH_QUIET, "FormBot", "Сегодня растений не было")
        }
    }

    private fun shutdown() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }
}

/** Будильник 13:59 МСК и перезапуск расписания после перезагрузки/обновления. */
class WatchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppSettings.init(context)
        when (intent.action) {
            ACTION_WATCH -> {
                val cfg = AppSettings.value.telegram
                if (cfg.watch && cfg.hasSource) PlantRuntime.startWatch(context)
                PlantRuntime.schedule(context)
            }
            else -> PlantRuntime.schedule(context)  // BOOT_COMPLETED, MY_PACKAGE_REPLACED
        }
    }

    companion object {
        const val ACTION_WATCH = "com.formbot.app.WATCH"
    }
}
