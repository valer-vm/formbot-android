package com.formbot.app.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.formbot.app.core.Answer
import com.formbot.app.core.HumanRequest
import com.formbot.app.core.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import java.time.format.DateTimeFormatter

enum class Phase { IDLE, RUNNING, WAITING_HUMAN, DONE, FAILED }

data class LogLine(val time: String, val text: String)

data class RunState(
    val phase: Phase = Phase.IDLE,
    val status: String = "Готов к запуску",
    val log: List<LogLine> = emptyList(),
    val pending: HumanRequest? = null,
    val result: String? = null,
    /** Ручная отправка: (растение, ссылка на заполненную форму) в порядке готовности. */
    val prepared: List<Pair<String, String>> = emptyList(),
)

/** Общее состояние бота: служба пишет, интерфейс читает. Живёт, пока жив процесс. */
object BotRuntime {
    private val _state = MutableStateFlow(RunState())
    val state: StateFlow<RunState> = _state.asStateFlow()

    @Volatile internal var settings: Settings? = null
    /** Что отправлять: выбранное из поста или ручные «Растение 1/2». */
    @Volatile internal var plants: List<String> = emptyList()
    private var answer: CompletableDeferred<Answer?>? = null
    private val fmt = DateTimeFormatter.ofPattern("HH:mm:ss")

    val isRunning get() = _state.value.phase in setOf(Phase.RUNNING, Phase.WAITING_HUMAN)

    fun start(context: Context, s: Settings, chosen: List<String> = s.plants) {
        if (isRunning) return
        settings = s
        plants = chosen
        _state.value = RunState(phase = Phase.RUNNING, status = "Запуск", log = _state.value.log.takeLast(200))
        ContextCompat.startForegroundService(context, Intent(context, BotService::class.java))
    }

    fun stop(context: Context) {
        answer?.complete(null)
        context.startService(Intent(context, BotService::class.java).setAction(BotService.ACTION_STOP))
    }

    fun clearLog() = _state.update { it.copy(log = emptyList()) }

    internal fun log(text: String) {
        val now = LocalTime.now().format(fmt)
        val lines = text.trimStart('\n').lines().filter { it.isNotBlank() }.map { LogLine(now, it) }
        _state.update { it.copy(log = (it.log + lines).takeLast(1000)) }
    }

    internal fun prepared(plant: String, url: String) = _state.update { it.copy(prepared = it.prepared + (plant to url)) }

    /** Ссылки, которые уже открывались сами: при возврате на «Запуск» повторно не открываем. */
    private val autoOpened = mutableSetOf<String>()

    /** true — только при первом вызове для этой ссылки. */
    @Synchronized
    fun takeAutoOpen(url: String): Boolean = autoOpened.add(url)

    /** «Скрыть»: убрать карточку с готовыми формами. */
    fun clearPrepared() = _state.update { it.copy(prepared = emptyList()) }

    internal fun status(text: String) = _state.update { it.copy(status = text) }

    internal suspend fun ask(req: HumanRequest): Answer? {
        val d = CompletableDeferred<Answer?>()
        answer = d
        _state.update { it.copy(phase = Phase.WAITING_HUMAN, pending = req, status = "Нужен ваш ответ") }
        return try {
            d.await()
        } finally {
            answer = null
            _state.update { it.copy(phase = if (it.phase == Phase.WAITING_HUMAN) Phase.RUNNING else it.phase, pending = null) }
        }
    }

    /** Ответ из интерфейса (null — пропустить вопрос). */
    fun answerHuman(a: Answer?) {
        answer?.complete(a)
    }

    internal fun finish(ok: Boolean, result: String) =
        _state.update { it.copy(phase = if (ok) Phase.DONE else Phase.FAILED, status = result, result = result, pending = null) }
}
