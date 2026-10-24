package com.formbot.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.formbot.app.core.AiAnswerer
import com.formbot.app.core.AppSettings
import com.formbot.app.core.Settings
import com.formbot.app.service.BotRuntime
import com.formbot.app.service.Notify
import com.formbot.app.service.PlantRuntime
import com.formbot.app.service.Telegram
import com.formbot.app.service.TgDiagnostics
import com.formbot.app.service.TgAuth
import com.formbot.app.service.TgChat
import com.formbot.app.service.TgTopic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AppViewModel(app: Application) : AndroidViewModel(app) {
    init {
        AppSettings.init(app)
        Notify.channels(app)
        AppSettings.keyProblem?.let {
            BotRuntime.log("⚠️ Сохранённые API-ключи пока не расшифровались ($it). Они не стёрты — повторю при следующем открытии.")
        }
    }

    /** Вернулись в приложение — перечитать ключи, если раньше не получилось. */
    fun onResume() = AppSettings.repair()

    val settings = AppSettings.flow
    val run = BotRuntime.state
    val watch = PlantRuntime.state
    val tgAuth = Telegram.auth
    val tgError = Telegram.error
    val tgConnection = Telegram.connection

    private val _chats = MutableStateFlow<List<TgChat>?>(null)
    val chats = _chats.asStateFlow()
    private val _topics = MutableStateFlow<Pair<TgChat, List<TgTopic>>?>(null)
    val topics = _topics.asStateFlow()
    private val _tgBusy = MutableStateFlow(false)
    val tgBusy = _tgBusy.asStateFlow()
    /** Отчёт диагностики: null — закрыт, "" — собирается. */
    private val _diag = MutableStateFlow<String?>(null)
    val diag = _diag.asStateFlow()

    fun diagnose() = viewModelScope.launch {
        _diag.value = ""
        _diag.value = runCatching { TgDiagnostics.run(getApplication()) }.getOrElse { "Не удалось собрать отчёт: ${it.message}" }
    }

    fun closeDiag() { _diag.value = null }

    /** Результаты «Проверить провайдеров»: id → (ок?, сообщение). */
    private val _checks = MutableStateFlow<Map<String, Pair<Boolean?, String>>>(emptyMap())
    val checks = _checks.asStateFlow()
    private val _checking = MutableStateFlow(false)
    val checking = _checking.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    init {
        PlantRuntime.onAppOpen(app)
        // Как только Telegram готов — сегодняшний пост проверяется всегда, даже после 14:30.
        viewModelScope.launch {
            Telegram.auth.collect { if (it == TgAuth.Ready) refreshOffer() }
        }
    }

    fun update(f: (Settings) -> Settings) {
        AppSettings.update(f)
    }

    /** Ручной запуск: растения из полей «Растение 1/2». */
    fun start() = BotRuntime.start(getApplication(), AppSettings.value)

    /** «Заполнить»: отмеченные растения из поста, в порядке отметки. */
    fun fillSelected() {
        val chosen = PlantRuntime.state.value.selected
        if (chosen.isNotEmpty()) BotRuntime.start(getApplication(), AppSettings.value, chosen)
    }

    fun toggleOffer(raw: String) = PlantRuntime.toggle(raw)

    fun refreshOffer() = viewModelScope.launch(Dispatchers.IO) {
        runCatching { PlantRuntime.refresh(getApplication(), notify = false) }
            .onFailure { _message.value = "Telegram: ${it.message}" }
    }

    fun setWatch(on: Boolean) {
        update { it.copy(telegram = it.telegram.copy(watch = on)) }
        PlantRuntime.onAppOpen(getApplication())
    }

    // ---------- Telegram ----------

    fun connectTelegram(proxy: String? = null) {
        Telegram.clearError()
        val cfg = AppSettings.value.telegram
        Telegram.start(getApplication(), if (proxy != null) cfg.copy(proxy = proxy) else cfg)
    }

    private fun tg(block: suspend () -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        _tgBusy.value = true
        try { block() } catch (e: Exception) { _message.value = "Telegram: ${e.message}" } finally { _tgBusy.value = false }
    }

    fun sendPhone(v: String) = tg { Telegram.sendPhone(v) }
    fun sendCode(v: String) = tg { Telegram.sendCode(v) }
    fun sendPassword(v: String) = tg { Telegram.sendPassword(v) }
    fun logOutTelegram() = tg {
        Telegram.logOut()
        update { it.copy(telegram = it.telegram.copy(chatId = 0, chatTitle = "", threadId = 0, topicTitle = "", watch = false)) }
        PlantRuntime.schedule(getApplication())
    }

    fun loadChats() = tg { _chats.value = Telegram.chats().sortedBy { it.title.lowercase() } }
    fun closeChats() { _chats.value = null; _topics.value = null }

    fun pickChat(chat: TgChat) = tg {
        if (chat.isForum) {
            _topics.value = chat to Telegram.topics(chat.id)
        } else {
            saveSource(chat, null)
        }
    }

    fun pickTopic(chat: TgChat, topic: TgTopic) = tg { saveSource(chat, topic) }

    private fun saveSource(chat: TgChat, topic: TgTopic?) {
        update {
            it.copy(telegram = it.telegram.copy(
                chatId = chat.id, chatTitle = chat.title,
                threadId = topic?.id?.toLong() ?: 0, topicTitle = topic?.name.orEmpty(),
                watch = true,  // по умолчанию слежение включается после выбора
            ))
        }
        _chats.value = null; _topics.value = null
        PlantRuntime.onAppOpen(getApplication())
        refreshOffer()
    }

    fun stop() = BotRuntime.stop(getApplication())

    fun consumeMessage() { _message.value = null }

    fun importDesktopConfig(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        val result = runCatching {
            val text = getApplication<Application>().contentResolver.openInputStream(uri)!!.use {
                it.readBytes().toString(Charsets.UTF_8)
            }
            Settings.fromDesktopJson(text, AppSettings.value)
        }
        result.onSuccess { s ->
            update { s }
            val keys = s.providers.count { it.apiKey.isNotBlank() }
            _message.value = "Настройки импортированы (ключей: $keys)"
        }.onFailure { _message.value = "Не удалось прочитать файл: ${it.message}" }
    }

    fun checkProviders() = viewModelScope.launch {
        if (_checking.value) return@launch
        _checking.value = true
        val s = AppSettings.value
        val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).build()
        val targets = s.providers.filter { it.enabled }
        _checks.value = targets.associate { it.id to (null to "проверяю…") }
        targets.map { p ->
            async(Dispatchers.IO) {
                val logs = mutableListOf<String>()
                val one = s.copy(useAi = true, aiVote = false, providers = s.providers.map {
                    it.copy(enabled = it.id == p.id, expert = false)
                })
                val ai = AiAnswerer(one, http) { logs += it }
                val started = System.nanoTime()
                val res = if (ai.providers.isEmpty()) false to "нет ключа или модели"
                else withTimeoutOrNull(40_000) { ai.text("Ответь одним словом: ок") }?.let { r ->
                    val sec = "%.1f с".format((System.nanoTime() - started) / 1e9)
                    if (r.value != null) true to "${p.model}: «${r.value}», $sec"
                    else false to (logs.lastOrNull { it.startsWith("⚠️") }?.removePrefix("⚠️ ") ?: "нет ответа")
                } ?: (false to "нет ответа за 40 с")
                ai.close()
                withContext(Dispatchers.Main) { _checks.update { it + (p.id to res) } }
            }
        }.awaitAll()
        _checking.value = false
    }
}
