package com.formbot.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.formbot.app.R
import com.formbot.app.core.BotEngine
import com.formbot.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Фоновая служба: ждёт открытия формы и заполняет её даже при выключенном экране. */
class BotService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_STATUS, "Работа бота", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Нужен ваш ответ", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            job?.cancel()
            BotRuntime.log("🛑 Остановлено")
            BotRuntime.finish(false, "Остановлено")
            shutdown()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, ID_STATUS, statusNotification("Запуск…"),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        if (job?.isActive == true) return START_NOT_STICKY
        val settings = BotRuntime.settings ?: run { shutdown(); return START_NOT_STICKY }

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "formbot:run").apply { acquire(6 * 60 * 60 * 1000L) }

        // Статус — в уведомление; вопрос человеку — громким уведомлением.
        scope.launch {
            BotRuntime.state.map { it.status to (it.pending != null) }.distinctUntilChanged().collectLatest { (status, asking) ->
                notify(ID_STATUS, statusNotification(status))
                if (asking) notify(ID_ALERT, alertNotification()) else NotificationManagerCompat.from(this@BotService).cancel(ID_ALERT)
            }
        }

        job = scope.launch {
            val http = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
            val engine = BotEngine(
                settings, http, BotRuntime::log, BotRuntime::status, BotRuntime::ask,
                onProblem = { Notify.problem(this@BotService, it) },
                onPrepared = { plant, url -> BotRuntime.prepared(plant, url); Notify.prepared(this@BotService, plant, url) },
            )
            try {
                val chosen = BotRuntime.plants
                val plants = chosen.size.coerceAtLeast(1)
                val done = engine.run(chosen)
                if (settings.manualSubmit) {
                    BotRuntime.finish(done == plants, "Готово к отправке: $done из $plants — откройте форму")
                } else {
                    BotRuntime.finish(done == plants, if (done == plants) "Готово: $done/$plants" else "Отправлено $done из $plants")
                    notify(ID_DONE, doneNotification(if (done == plants) "✅ Форма отправлена" else "⚠️ Отправлено $done из $plants"))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                BotRuntime.log("❌ ${e.message}")
                BotRuntime.finish(false, e.message ?: "Ошибка")
                notify(ID_DONE, doneNotification("❌ ${e.message}"))
            } finally {
                engine.close()
                shutdown()
            }
        }
        return START_NOT_STICKY
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

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun statusNotification(text: String): Notification =
        NotificationCompat.Builder(this, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("FormBot работает")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .addAction(0, "Остановить", PendingIntent.getService(
                this, 1, Intent(this, BotService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
            ))
            .build()

    private fun alertNotification(): Notification =
        NotificationCompat.Builder(this, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Нужен ваш ответ")
            .setContentText(BotRuntime.state.value.pending?.question?.title ?: "ИИ не знает ответа на вопрос")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .setFullScreenIntent(openApp(), true)
            .build()

    private fun doneNotification(text: String): Notification =
        NotificationCompat.Builder(this, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("FormBot")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()

    private fun notify(id: Int, n: Notification) {
        runCatching { NotificationManagerCompat.from(this).notify(id, n) }  // нет разрешения — молча
    }

    companion object {
        const val ACTION_STOP = "com.formbot.app.STOP"
        private const val CH_STATUS = "status"
        private const val CH_ALERT = "alert"
        private const val ID_STATUS = 1
        private const val ID_ALERT = 2
        private const val ID_DONE = 3
    }
}
