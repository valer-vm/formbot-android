package com.formbot.app.core

import com.formbot.app.service.TgDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ScrubTest {
    @Test fun hidesPhonesKeysCodes() {
        val hash = "0123456789abcdef0123456789abcdef"
        val raw = "phone +7 (999) 123-45-67, 79991234567 hash=$hash code=12345\n[1760000000.123] DC2 149.154.167.51:443"
        val s = TgDiagnostics.scrub(raw, hash)
        assertFalse(s.contains("999"))
        assertFalse(s.contains(hash))
        assertFalse(s.contains("12345 "))
        assertEquals(true, s.contains("[1760000000.123] DC2 149.154.167.51:443"))
    }
}
