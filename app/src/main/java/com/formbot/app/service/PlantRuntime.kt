package com.formbot.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.formbot.app.core.AppSettings
import com.formbot.app.core.GoogleForm
import com.formbot.app.core.Offer
import com.formbot.app.core.PlantPost
import com.formbot.app.core.FormState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

enum class WatchPhase { OFF, NOT_READY, SCHEDULED, WAITING, FOUND, NONE_TODAY, ERROR }

data class WatchState(
    val phase: WatchPhase = WatchPhase.OFF,
    val status: String = "",
    val offer: Offer? = null,
    /** Выбранные строки позиций в порядке отметки — в этом порядке и отправляются. */
    val selected: List<String> = emptyList(),
    /** Размеры из формы для растений, у которых в посте только название: название → варианты формы. */
    val variants: Map<String, List<String>> = emptyMap(),
    /** Форма ещё не обновилась — размеры подгружаются. */
    val variantsLoading: Boolean = false,
)

/**
 * Предложение дня из Telegram: расписание (13:59–14:30 МСК), разбор постов, выбор до двух растений.
 * Службу [PlantWatchService] будит будильник; при открытии приложения сегодняшний пост проверяется всегда.
 */
object PlantRuntime {
    val START: LocalTime = LocalTime.of(13, 59)
    val FAST_UNTIL: LocalTime = LocalTime.of(14, 0)
    val STOP: LocalTime = LocalTime.of(14, 30)
    const val MAX_SELECTED = 2

    private val _state = MutableStateFlow(WatchState())
    val state: StateFlow<WatchState> = _state.asStateFlow()
    private val lock = Mutex()
    private var offerDay: LocalDate? = null
    private val http by lazy { OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).followRedirects(true).build() }

    fun nowMsk(): ZonedDateTime = ZonedDateTime.now(PlantPost.MSK)

    fun toggle(raw: String) = _state.update { st ->
        when {
            raw in st.selected -> st.copy(selected = st.selected - raw)
            st.selected.size >= MAX_SELECTED -> st
            else -> st.copy(selected = st.selected + raw)
        }
    }

    internal fun setStatus(phase: WatchPhase, status: String) = _state.update { it.copy(phase = phase, status = status) }

    /** Статус «до/после окна» без сети — для экрана, пока ничего не найдено. */
    fun idleStatus(): Pair<WatchPhase, String> {
        val s = AppSettings.value.telegram
        val t = nowMsk().toLocalTime()
        return when {
            !s.hasKeys || !s.hasSource -> WatchPhase.NOT_READY to "Подключите Telegram во вкладке «Форма»"
            !s.watch -> WatchPhase.OFF to "Слежение выключено"
            t.isBefore(START) -> WatchPhase.SCHEDULED to "Проверю канал в 13:59 МСК"
            t.isBefore(STOP) -> WatchPhase.WAITING to "Жду пост до 14:30 МСК…"
            else -> WatchPhase.NONE_TODAY to "Сегодня растений не было"
        }
    }

    /**
     * Перечитывает тему и применяет найденное предложение. Возвращает его (или null).
     * notify = true — громкое уведомление, если предложение появилось впервые.
     */
    suspend fun refresh(context: Context, notify: Boolean): Offer? = lock.withLock {
        val cfg = AppSettings.value.telegram
        if (!cfg.hasSource || Telegram.auth.value != TgAuth.Ready) return null
        val today = LocalDate.now(PlantPost.MSK)
        if (offerDay != today) {
            offerDay = today
            _state.update { it.copy(offer = null, selected = emptyList(), variants = emptyMap(), variantsLoading = false) }
        }
        val offer = PlantPost.todayOffer(Telegram.recentPosts(cfg), today) ?: return _state.value.offer
        val prev = _state.value.offer
        if (prev == offer) {
            // Размеры не все нашлись (форма долго не обновлялась) — при следующей проверке ищем снова.
            val missing = offer.items.any { it.container == null && it.name !in _state.value.variants }
            if (missing && variantsJob?.isActive != true) loadVariants(offer)
            return offer
        }

        val raws = offer.items.map { it.raw }
        val known = _state.value.variants.values.flatten().toSet()
        val lost = _state.value.selected.filter { it !in raws && it !in known }
        _state.update {
            it.copy(phase = WatchPhase.FOUND, status = "Сегодня в продаже: ${offer.items.size}", offer = offer, selected = it.selected - lost.toSet())
        }
        when {
            prev == null && notify -> Notify.show(
                context, Notify.ID_PLANTS, Notify.CH_PLANTS, "🌱 Растения появились",
                offer.items.joinToString("\n") { it.name + if (it.count > 1) " ×${it.count}" else "" },
            )
            prev != null && lost.isNotEmpty() -> Notify.show(
                context, Notify.ID_CHANGED, Notify.CH_PLANTS, "Список изменился",
                "Больше нет в посте: ${lost.joinToString("; ")}",
            )
        }
        offer.formUrl?.let { syncFormUrl(it) }
        if (offer.items.any { it.container == null }) loadVariants(offer)
        offer
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var variantsJob: Job? = null

    /**
     * В посте только названия — размеры есть лишь в форме, а она обновляется примерно через минуту
     * после поста. Перечитываем форму (только просмотр) раз в 3 с, пока у всех растений не найдутся
     * варианты, но не дольше 10 минут.
     */
    private fun loadVariants(offer: Offer) {
        variantsJob?.cancel()
        val names = offer.items.filter { it.container == null }.map { it.name }.distinct()
        variantsJob = scope.launch {
            _state.update { it.copy(variantsLoading = true) }
            val form = GoogleForm(http)
            val deadline = System.currentTimeMillis() + 10 * 60_000L
            try {
                while (System.currentTimeMillis() < deadline) {
                    val st = form.fetch(AppSettings.value.formUrl)
                    if (st is FormState.Open) {
                        val q = st.form.questions.filter { it.kind == com.formbot.app.core.QKind.DROPDOWN || it.kind == com.formbot.app.core.QKind.RADIO }
                            .maxByOrNull { it.options.size }
                        if (q != null) {
                            val found = names.associateWith { PlantPost.variants(q.options, it) }.filterValues { it.isNotEmpty() }
                            _state.update { it.copy(variants = it.variants + found) }
                            if (found.size == names.size) break
                        }
                    }
                    delay(3_000)
                }
            } finally {
                _state.update { it.copy(variantsLoading = false) }
            }
        }
    }

    /** Ссылка из поста ведёт на другую форму — сохраняем её навсегда. Раскрытие forms.gle — только GET. */
    private fun syncFormUrl(postUrl: String) {
        val current = AppSettings.value.formUrl
        if (current == postUrl) return
        val form = GoogleForm(http)
        val newId = form.resolveFormId(postUrl)
        val oldId = if (current.isBlank()) null else form.resolveFormId(current)
        if (current.isBlank() || (newId != null && newId != oldId)) {
            AppSettings.update { it.copy(formUrl = postUrl) }
            BotRuntime.log("🔗 Ссылка на форму обновлена из поста: $postUrl")
        }
    }

    // ---------- расписание ----------

    private fun alarmIntent(context: Context) = PendingIntent.getBroadcast(
        context, 0, Intent(context, WatchReceiver::class.java).setAction(WatchReceiver.ACTION_WATCH),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Будильник на ближайшие 13:59 МСК (или отмена, если слежение выключено). */
    fun schedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = alarmIntent(context)
        val cfg = AppSettings.value.telegram
        if (!cfg.watch || !cfg.hasSource) { am.cancel(pi); return }
        val now = nowMsk()
        var at = now.with(START).withSecond(0).withNano(0)
        if (!at.isAfter(now)) at = at.plusDays(1)
        val ms = at.toInstant().toEpochMilli()
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
    }

    /** Открыли приложение: расписание, проверка сегодняшнего поста и, если окно идёт, ожидание. */
    fun onAppOpen(context: Context) {
        val cfg = AppSettings.value.telegram
        Telegram.start(context, cfg)
        schedule(context)
        if (_state.value.offer == null || offerDay != LocalDate.now(PlantPost.MSK)) {
            val (phase, status) = idleStatus()
            setStatus(phase, status)
        }
        if (cfg.watch && cfg.hasSource && inWindow()) startWatch(context)
    }

    fun inWindow(): Boolean = nowMsk().toLocalTime().let { !it.isBefore(START) && it.isBefore(STOP) }

    fun startWatch(context: Context) =
        ContextCompat.startForegroundService(context, Intent(context, PlantWatchService::class.java))
}
