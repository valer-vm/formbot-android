package com.formbot.app.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import com.formbot.app.core.PlantPost
import com.formbot.app.core.ProxyLink
import com.formbot.app.core.Post
import com.formbot.app.core.TelegramConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/** Состояние входа в Telegram — то, что видит пользователь в разделе «Telegram». */
sealed class TgAuth {
    data object NoKeys : TgAuth()
    data object Starting : TgAuth()
    data object WaitPhone : TgAuth()
    data class WaitCode(val phone: String) : TgAuth()
    data class WaitPassword(val hint: String) : TgAuth()
    data object Ready : TgAuth()
    data class Unsupported(val what: String) : TgAuth()
    data object LoggedOut : TgAuth()
}

data class TgChat(val id: Long, val title: String, val isForum: Boolean)
data class TgTopic(val id: Int, val name: String)

class TgException(val code: Int, message: String) : Exception(message)

/**
 * Один клиент TDLib на процесс. Сессия хранится в filesDir/tdlib, поэтому вход нужен один раз.
 * Новые и отредактированные сообщения приходят в [changes] (id чата) — по ним слежение перечитывает тему.
 */
object Telegram {
    private val _auth = MutableStateFlow<TgAuth>(TgAuth.NoKeys)
    val auth: StateFlow<TgAuth> = _auth.asStateFlow()
    private val _changes = MutableSharedFlow<Long>(extraBufferCapacity = 64)
    val changes: SharedFlow<Long> = _changes.asSharedFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    /** Связь с серверами Telegram; null — всё в порядке. */
    private val _connection = MutableStateFlow<String?>(null)
    val connection: StateFlow<String?> = _connection.asStateFlow()

    @Volatile private var client: Client? = null
    private var keys: Pair<Int, String>? = null
    @Volatile private var proxy: String = ""
    private lateinit var dir: File

    private var logFile: File? = null
    private var netWatching = false
    /** Текущая сеть словами — для диагностики. */
    @Volatile var networkInfo: String = "неизвестно"; private set

    /**
     * Журнал TDLib в файл (до 2 МБ): по нему видно, к каким серверам идёт подключение и где обрыв.
     * Подробно (3) — пока не вошли, дальше только предупреждения (2).
     */
    private fun setupLog(context: Context) {
        if (logFile != null) return
        val f = File(context.filesDir, "tdlib.log")
        logFile = f
        runCatching {
            Client.execute(TdApi.SetLogStream(TdApi.LogStreamFile(f.absolutePath, 2L shl 20, false)))
            Client.execute(TdApi.SetLogVerbosityLevel(3))
        }
    }

    /**
     * Сообщаем TDLib о смене сети (включили VPN, переключились Wi-Fi ↔ мобильная):
     * он сразу переподключается, а не ждёт со всё растущими паузами.
     */
    private fun watchNetwork(context: Context) {
        if (netWatching) return
        netWatching = true
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = onNetwork(caps)
            override fun onLost(network: Network) = onNetwork(null)
        })
    }

    private var lastNetwork: String? = null

    private fun onNetwork(caps: NetworkCapabilities?) {
        val (type, info) = when {
            caps == null -> TdApi.NetworkTypeNone() to "нет сети"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> TdApi.NetworkTypeWiFi() to "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> TdApi.NetworkTypeMobile() to "мобильная сеть"
            else -> TdApi.NetworkTypeOther() to "другая сеть"
        }
        val vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        val desc = info + if (vpn) " + VPN" else ""
        networkInfo = desc
        if (desc == lastNetwork) return
        lastNetwork = desc
        client?.send(TdApi.SetNetworkType(type)) {}
    }

    /** Запуск клиента; повторный вызов с теми же ключами ничего не делает. */
    @Synchronized
    fun start(context: Context, cfg: TelegramConfig) {
        if (!cfg.hasKeys) { if (client == null) _auth.value = TgAuth.NoKeys; return }
        val k = cfg.apiId.toInt() to cfg.apiHash.trim()
        if (client != null && keys == k) {
            if (proxy != cfg.proxy) { proxy = cfg.proxy; applyProxy() }
            return
        }
        proxy = cfg.proxy
        setupLog(context)
        watchNetwork(context)
        if (client != null) close()
        keys = k
        dir = File(context.filesDir, "tdlib")
        _auth.value = TgAuth.Starting
        client = Client.create({ onUpdate(it) }, null, null)
    }

    @Synchronized
    private fun close() {
        client?.send(TdApi.Close()) {}
        client = null
    }

    private fun onUpdate(o: TdApi.Object) {
        when (o) {
            is TdApi.UpdateAuthorizationState -> onAuth(o.authorizationState)
            is TdApi.UpdateNewMessage -> _changes.tryEmit(o.message.chatId)
            is TdApi.UpdateMessageContent -> _changes.tryEmit(o.chatId)
            is TdApi.UpdateMessageEdited -> _changes.tryEmit(o.chatId)
            is TdApi.UpdateConnectionState -> _connection.value = when (o.state) {
                is TdApi.ConnectionStateWaitingForNetwork -> "Нет интернета"
                is TdApi.ConnectionStateConnectingToProxy -> "Подключаюсь к прокси…"
                is TdApi.ConnectionStateConnecting -> "Подключаюсь к серверам Telegram…"
                is TdApi.ConnectionStateUpdating -> "Обновляю данные…"
                else -> null
            }
        }
    }

    /** Ставит прокси из настроек (или убирает все, если поле пустое). */
    private fun applyProxy() {
        val c = client ?: return
        val link = ProxyLink.parse(proxy)
        if (proxy.isNotBlank() && link == null) { _error.value = "Не понял ссылку на прокси"; return }
        c.send(TdApi.GetProxies()) { r ->
            (r as? TdApi.AddedProxies)?.proxies?.forEach { c.send(TdApi.RemoveProxy(it.id)) {} }
            if (link == null) return@send
            val type = when (link.kind) {
                ProxyLink.Kind.MTPROTO -> TdApi.ProxyTypeMtproto(link.secret)
                ProxyLink.Kind.SOCKS5 -> TdApi.ProxyTypeSocks5(link.user, link.password)
                ProxyLink.Kind.HTTP -> TdApi.ProxyTypeHttp(link.user, link.password, false)
            }
            c.send(TdApi.AddProxy(TdApi.Proxy(link.server, link.port, type), true, "FormBot")) { a ->
                if (a is TdApi.Error) _error.value = "Прокси не принят: ${a.message}"
            }
        }
    }

    private fun onAuth(s: TdApi.AuthorizationState) {
        _auth.value = when (s) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                val (id, hash) = keys ?: return
                val p = TdApi.SetTdlibParameters().apply {
                    databaseDirectory = dir.absolutePath
                    filesDirectory = File(dir, "files").absolutePath
                    databaseEncryptionKey = ByteArray(0)
                    useMessageDatabase = true
                    useChatInfoDatabase = true
                    useFileDatabase = false
                    useSecretChats = false
                    apiId = id
                    apiHash = hash
                    systemLanguageCode = "ru"
                    deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
                    systemVersion = "Android ${Build.VERSION.RELEASE}"
                    applicationVersion = "FormBot 2.0"
                }
                client?.send(p) { r -> if (r is TdApi.Error) fail(r) else applyProxy() }
                TgAuth.Starting
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> TgAuth.WaitPhone
            is TdApi.AuthorizationStateWaitCode -> TgAuth.WaitCode(s.codeInfo.phoneNumber)
            is TdApi.AuthorizationStateWaitPassword -> TgAuth.WaitPassword(s.passwordHint)
            is TdApi.AuthorizationStateReady -> {
                _error.value = null
                runCatching { Client.execute(TdApi.SetLogVerbosityLevel(2)) }
                TgAuth.Ready
            }
            is TdApi.AuthorizationStateLoggingOut, is TdApi.AuthorizationStateClosing -> TgAuth.Starting
            is TdApi.AuthorizationStateClosed -> {
                synchronized(this) { client = null }
                if (_auth.value == TgAuth.LoggedOut) TgAuth.LoggedOut else TgAuth.NoKeys
            }
            is TdApi.AuthorizationStateWaitRegistration -> TgAuth.Unsupported("Этот номер не зарегистрирован в Telegram")
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> TgAuth.Unsupported("Подтвердите вход на другом устройстве")
            else -> TgAuth.Unsupported("Telegram просит шаг входа, который приложение не поддерживает (${s.javaClass.simpleName})")
        }
    }

    private fun fail(e: TdApi.Error) {
        _error.value = when {
            "PHONE_CODE_INVALID" in e.message -> "Неверный код"
            "PASSWORD_HASH_INVALID" in e.message -> "Неверный облачный пароль"
            "PHONE_NUMBER_INVALID" in e.message -> "Неверный номер телефона"
            "API_ID_INVALID" in e.message || "API_ID_PUBLISHED_FLOOD" in e.message -> "Неверные api_id / api_hash"
            "FLOOD_WAIT" in e.message -> "Слишком много попыток, Telegram просит подождать"
            else -> e.message
        }
    }

    fun clearError() { _error.value = null }

    @Suppress("UNCHECKED_CAST")
    suspend fun <T : TdApi.Object> send(f: TdApi.Function<T>, timeoutMs: Long = 20_000): T {
        val c = client ?: throw TgException(-1, "Telegram не запущен")
        val d = CompletableDeferred<TdApi.Object>()
        c.send(f) { d.complete(it) }
        val r = withTimeout(timeoutMs) { d.await() }
        if (r is TdApi.Error) throw TgException(r.code, r.message)
        return r as T
    }

    private suspend fun step(f: TdApi.Function<TdApi.Ok>) {
        _error.value = null
        try {
            send(f, 60_000)
        } catch (e: TgException) {
            fail(TdApi.Error(e.code, e.message ?: ""))
        } catch (e: TimeoutCancellationException) {
            _error.value = "Telegram не ответил за минуту" + (_connection.value?.let { " ($it)" } ?: "") +
                ". Похоже, серверы Telegram недоступны напрямую — включите VPN или укажите прокси ниже."
        }
    }

    suspend fun sendPhone(phone: String) = step(
        TdApi.SetAuthenticationPhoneNumber(phone.filter { it.isDigit() || it == '+' }, null)
    )
    suspend fun sendCode(code: String) = step(TdApi.CheckAuthenticationCode(code.trim()))
    suspend fun sendPassword(password: String) = step(TdApi.CheckAuthenticationPassword(password))

    suspend fun logOut() {
        _auth.value = TgAuth.LoggedOut
        runCatching { send(TdApi.LogOut()) }
    }

    /** Группы и каналы из основного списка чатов. */
    suspend fun chats(): List<TgChat> {
        runCatching { send(TdApi.LoadChats(TdApi.ChatListMain(), 300), 30_000) }  // 404 — всё уже загружено
        val ids = send(TdApi.GetChats(TdApi.ChatListMain(), 300)).chatIds
        return ids.toList().mapNotNull { id ->
            val chat = runCatching { send(TdApi.GetChat(id)) }.getOrNull() ?: return@mapNotNull null
            val type = chat.type as? TdApi.ChatTypeSupergroup ?: return@mapNotNull null
            val forum = !type.isChannel && runCatching { send(TdApi.GetSupergroup(type.supergroupId)).isForum }.getOrDefault(false)
            TgChat(chat.id, chat.title, forum)
        }
    }

    suspend fun topics(chatId: Long): List<TgTopic> =
        send(TdApi.GetForumTopics(chatId, "", 0, 0, 0, 100)).topics.map { TgTopic(it.info.forumTopicId, it.info.name) }

    /** Сообщения выбранного чата/темы за сегодня по МСК (с запасом в сутки), новые первыми. */
    suspend fun recentPosts(cfg: TelegramConfig): List<Post> {
        val since = LocalDate.now(PlantPost.MSK).minusDays(1).atTime(LocalTime.MIDNIGHT).atZone(PlantPost.MSK).toEpochSecond()
        val out = mutableListOf<Post>()
        var from = 0L
        repeat(6) {
            val batch = if (cfg.threadId != 0L) send(TdApi.GetForumTopicHistory(cfg.chatId, cfg.threadId.toInt(), from, 0, 50)).messages
            else send(TdApi.GetChatHistory(cfg.chatId, from, 0, 50, false)).messages
            if (batch.isEmpty()) return out
            batch.mapNotNullTo(out) { toPost(it) }
            val oldest = batch.last()
            if (oldest.date < since) return out
            from = oldest.id
        }
        return out
    }

    private fun toPost(m: TdApi.Message): Post? {
        val text = when (val c = m.content) {
            is TdApi.MessageText -> c.text
            is TdApi.MessagePhoto -> c.caption
            is TdApi.MessageVideo -> c.caption
            is TdApi.MessageDocument -> c.caption
            else -> null
        } ?: return null
        val urls = text.entities.orEmpty().mapNotNull { (it.type as? TdApi.TextEntityTypeTextUrl)?.url }
        return Post(m.id, m.date, m.editDate, text.text, urls)
    }
}
