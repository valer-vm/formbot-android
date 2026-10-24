package com.formbot.app.service

import android.content.Context
import android.os.Build
import com.formbot.app.core.AppSettings
import com.formbot.app.core.ProxyLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.drinkless.tdlib.TdApi
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Отчёт «почему Telegram не подключается»: сеть и VPN, прямые TCP-соединения с дата-центрами Telegram,
 * проверка самого TDLib и хвост его журнала. Номера телефонов и ключи вырезаются — отчёт можно пересылать.
 */
object TgDiagnostics {
    /** Адреса дата-центров Telegram (порт 443) — те же, с которых TDLib начинает подключение. */
    private val DCS = listOf(
        "DC1" to "149.154.175.53", "DC2" to "149.154.167.51", "DC3" to "149.154.175.100",
        "DC4" to "149.154.167.91", "DC5" to "91.108.56.130",
    )

    suspend fun run(context: Context): String = withContext(Dispatchers.IO) {
        val cfg = AppSettings.value.telegram
        val out = StringBuilder()
        fun line(s: String) { out.append(s).append('\n') }

        line("FormBot — диагностика Telegram")
        line(ZonedDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss xxx")))
        line("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        line("Сеть: ${Telegram.networkInfo}")
        line("Вход: " + when (val a = Telegram.auth.value) {
            TgAuth.NoKeys -> "нет ключей"
            TgAuth.Starting -> "запуск"
            TgAuth.WaitPhone -> "ждёт номер"
            is TgAuth.WaitCode -> "ждёт код"
            is TgAuth.WaitPassword -> "ждёт облачный пароль"
            TgAuth.Ready -> "выполнен"
            TgAuth.LoggedOut -> "вышли"
            is TgAuth.Unsupported -> "не поддерживается: ${a.what}"
        })
        line("Соединение TDLib: ${Telegram.connection.value ?: "готово"}")
        line("Прокси: " + when {
            cfg.proxy.isBlank() -> "нет"
            else -> ProxyLink.parse(cfg.proxy)?.let { "${it.kind} ${it.server}:${it.port}" } ?: "не удалось разобрать ссылку"
        })
        Telegram.error.value?.let { line("Последняя ошибка: $it") }

        line("\nПрямое TCP-подключение к серверам Telegram (порт 443):")
        coroutineScope {
            DCS.map { (name, ip) -> async { name to tcp(ip, 443) } }.awaitAll()
        }.forEach { (name, r) -> line("  $name: $r") }

        line("\nHTTPS:")
        val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
        for (url in listOf("https://www.google.com/generate_204", "https://api.telegram.org/", "https://web.telegram.org/")) {
            line("  $url: ${https(http, url)}")
        }

        line("\nTDLib:")
        if (Telegram.auth.value == TgAuth.NoKeys) line("  клиент не запущен (нет ключей)")
        else {
            line("  TestNetwork: " + tdlib { Telegram.send(TdApi.TestNetwork(), 25_000); "ok" })
            line("  PingProxy (прямое соединение): " + tdlib {
                val s = Telegram.send(TdApi.PingProxy(null), 25_000).seconds
                "%.0f мс".format(s * 1000)
            })
            if (cfg.proxy.isNotBlank()) {
                val p = ProxyLink.parse(cfg.proxy)
                if (p != null) line("  PingProxy (через прокси): " + tdlib {
                    val type = when (p.kind) {
                        ProxyLink.Kind.MTPROTO -> TdApi.ProxyTypeMtproto(p.secret)
                        ProxyLink.Kind.SOCKS5 -> TdApi.ProxyTypeSocks5(p.user, p.password)
                        ProxyLink.Kind.HTTP -> TdApi.ProxyTypeHttp(p.user, p.password, false)
                    }
                    "%.0f мс".format(Telegram.send(TdApi.PingProxy(TdApi.Proxy(p.server, p.port, type)), 25_000).seconds * 1000)
                })
            }
        }

        line("\nЖурнал TDLib (последние строки):")
        line(logTail(File(context.filesDir, "tdlib.log"), 150))
        scrub(out.toString(), cfg.apiHash)
    }

    private fun tcp(ip: String, port: Int): String = runCatching {
        val started = System.nanoTime()
        Socket().use { it.connect(InetSocketAddress(ip, port), 6_000) }
        "ok, %.0f мс".format((System.nanoTime() - started) / 1e6)
    }.getOrElse { "ошибка — ${it.javaClass.simpleName}: ${it.message}" }

    private fun https(http: OkHttpClient, url: String): String = runCatching {
        val started = System.nanoTime()
        http.newCall(Request.Builder().url(url).head().build()).execute().use {
            "HTTP ${it.code}, %.0f мс".format((System.nanoTime() - started) / 1e6)
        }
    }.getOrElse { "ошибка — ${it.javaClass.simpleName}: ${it.message}" }

    private suspend fun tdlib(block: suspend () -> String): String =
        runCatching { withTimeoutOrNull(30_000) { block() } ?: "нет ответа за 30 с" }
            .getOrElse { "ошибка — ${it.message}" }

    private fun logTail(f: File, lines: Int): String {
        if (!f.exists()) return "  (журнала ещё нет)"
        val all = runCatching { f.readLines() }.getOrElse { return "  (не читается: ${it.message})" }
        return all.takeLast(lines).joinToString("\n") { "  " + it.take(400) }
    }

    /** Вырезаем номера телефонов, api_hash и коды входа. */
    fun scrub(text: String, apiHash: String): String {
        var t = text
        if (apiHash.length >= 8) t = t.replace(apiHash, "<api_hash>")
        // «+7 999 …» в любом виде и российские 7XXXXXXXXXX/8XXXXXXXXXX; метки времени журнала не трогаем.
        t = Regex("\\+\\d[\\d\\s()-]{8,16}\\d|(?<![\\d.])[78]\\d{10}(?![\\d.])").replace(t, "<номер>")
        t = Regex("(phone_code|code|password)([\"'=: ]+)[^\\s,\"'}]+", RegexOption.IGNORE_CASE).replace(t) { "${it.groupValues[1]}${it.groupValues[2]}<скрыто>" }
        return t
    }
}
