package com.formbot.app.core

import java.net.URI
import java.net.URLDecoder

/** Прокси для Telegram: ссылка tg://proxy, t.me/socks или socks5://… / http://… */
data class ProxyLink(
    val server: String,
    val port: Int,
    val kind: Kind,
    val secret: String = "",
    val user: String = "",
    val password: String = "",
) {
    enum class Kind { MTPROTO, SOCKS5, HTTP }

    companion object {
        fun parse(text: String): ProxyLink? {
            val t = text.trim()
            if (t.isEmpty()) return null
            val uri = runCatching { URI(t) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase() ?: return null
            val q = uri.rawQuery.orEmpty().split('&').mapNotNull {
                val i = it.indexOf('=')
                if (i <= 0) null else it.substring(0, i).lowercase() to URLDecoder.decode(it.substring(i + 1), "UTF-8")
            }.toMap()
            // tg://proxy?…, https://t.me/proxy?…, tg://socks?…, https://t.me/socks?…
            val what = if (scheme == "tg") uri.host?.lowercase() else if (uri.host?.lowercase()?.endsWith("t.me") == true) uri.path.trim('/').lowercase() else null
            if (what == "proxy" || what == "socks") {
                val server = q["server"] ?: return null
                val port = q["port"]?.toIntOrNull() ?: return null
                return if (what == "proxy") ProxyLink(server, port, Kind.MTPROTO, secret = q["secret"] ?: return null)
                else ProxyLink(server, port, Kind.SOCKS5, user = q["user"].orEmpty(), password = q["pass"].orEmpty())
            }
            val kind = when (scheme) {
                "socks5", "socks" -> Kind.SOCKS5
                "http", "https" -> Kind.HTTP
                else -> return null
            }
            val host = uri.host ?: return null
            if (uri.port <= 0) return null
            val (user, pass) = uri.rawUserInfo?.split(':', limit = 2)?.map { URLDecoder.decode(it, "UTF-8") }
                ?.let { it[0] to it.getOrElse(1) { "" } } ?: ("" to "")
            return ProxyLink(host, uri.port, kind, user = user, password = pass)
        }
    }
}
