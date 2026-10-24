package com.formbot.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProxyLinkTest {
    @Test fun mtproto() {
        val p = ProxyLink.parse("tg://proxy?server=1.2.3.4&port=443&secret=ee00ff")!!
        assertEquals(ProxyLink("1.2.3.4", 443, ProxyLink.Kind.MTPROTO, secret = "ee00ff"), p)
        assertEquals(p, ProxyLink.parse("https://t.me/proxy?server=1.2.3.4&port=443&secret=ee00ff"))
    }

    @Test fun socks() {
        assertEquals(
            ProxyLink("h.example", 1080, ProxyLink.Kind.SOCKS5, user = "u", password = "p@ss"),
            ProxyLink.parse("socks5://u:p%40ss@h.example:1080"),
        )
        assertEquals(
            ProxyLink("h", 1080, ProxyLink.Kind.SOCKS5, user = "u", password = "x"),
            ProxyLink.parse("https://t.me/socks?server=h&port=1080&user=u&pass=x"),
        )
        assertEquals(ProxyLink("h", 8080, ProxyLink.Kind.HTTP), ProxyLink.parse("http://h:8080"))
    }

    @Test fun invalid() {
        assertNull(ProxyLink.parse(""))
        assertNull(ProxyLink.parse("tg://proxy?server=1.2.3.4"))
        assertNull(ProxyLink.parse("просто текст"))
        assertNull(ProxyLink.parse("http://no-port.example"))
    }
}
