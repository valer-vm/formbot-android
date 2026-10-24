package com.formbot.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextRulesTest {
    @Test fun matchIgnoresSpacesAndLookalikes() {
        val opts = listOf("Ginkgo biloba Everton Broom stam 15   C5   30/40", "2")
        assertEquals(0, TextRules.matchLabelIndex(opts, "ginkgo biloba everton broom stam 15C5 30/40"))
        assertEquals(1, TextRules.matchLabelIndex(opts, "2"))
        assertEquals(1, TextRules.matchLabelIndex(listOf("A B", "AB"), "ab"))
        assertEquals(-1, TextRules.matchLabelIndex(listOf("A B", "A  B"), "ab"))  // неоднозначно
    }

    @Test fun math() {
        assertEquals("56", TextRules.solveMath("Сколько будет 7 умножить на 8?"))
        assertEquals("6", TextRules.solveMath("2+2*2"))
        assertEquals("2.5", TextRules.solveMath("Реши пример: 5 / 2"))
        assertNull(TextRules.solveMath("Сезон 2024-2025"))
        assertNull(TextRules.solveMath("Возраст (18-25)"))
    }

    @Test fun instruction() {
        assertEquals("Привет", TextRules.answerByInstruction("Напишите слово «Привет»"))
    }

    @Test fun answerKeyEquivalence() {
        assertEquals(TextRules.answerKey("3x²"), TextRules.answerKey("3x^2"))
        assertEquals(TextRules.answerKey("∞"), TextRules.answerKey("Бесконечности"))
        assertEquals(TextRules.answerKey("восемь"), TextRules.answerKey("8.0"))
        assertTrue(TextRules.answerKey("Париж") != TextRules.answerKey("Лондон"))
    }

    @Test fun unsureAndHard() {
        assertTrue(TextRules.looksUnsure("Хотел бы уточнения: не хватает самого выражения"))
        assertTrue(TextRules.looksUnsure("Нужна формула"))
        assertFalse(TextRules.looksUnsure("Москва"))
        assertTrue(TextRules.isHardQuestion("Чему будет равняться предел от n при n, стремящейся к бесконечности?"))
        assertFalse(TextRules.isHardQuestion("Столица России?"))
    }
}

class GoogleFormParseTest {
    private val html = javaClass.classLoader!!.getResource("form_sample.html")!!.readText()

    @Test fun parsesRealForm() {
        val f = GoogleForm.parse(html, "https://docs.google.com/forms/d/e/X/viewform")!!
        assertEquals("Новая форма", f.title)
        assertEquals(8, f.questions.size)
        val plants = f.questions.first { it.title == "Растения" }
        assertEquals(QKind.DROPDOWN, plants.kind)
        assertEquals(listOf("1", "2"), plants.options)
        assertEquals(1859561551L, plants.entryId)
        assertTrue(plants.required)
        assertFalse(f.questions.first { it.title == "Примечание" }.required)
        assertTrue(f.fbzx.matches(Regex("-?\\d+")))
        assertTrue(f.actionUrl.endsWith("/formResponse"))
        assertEquals(1, f.pageCount)
        assertFalse(f.needsEmail)
    }

    @Test fun submitResultIgnoresLanguage() {
        val confirmLv = javaClass.classLoader!!.getResource("confirm_lv.html")!!.readText()
        assertEquals(SubmitResult.OK, GoogleForm.classify(200, confirmLv))       // «Jūsu atbilde tika reģistrēta»
        assertEquals(SubmitResult.INVALID, GoogleForm.classify(400, html))       // отказ проверки
        assertEquals(SubmitResult.UNKNOWN, GoogleForm.classify(200, html))       // снова форма — не повторяем
        assertEquals(SubmitResult.UNKNOWN, GoogleForm.classify(500, ""))
    }

    @Test fun closedOrGarbageReturnsNull() {
        assertNull(GoogleForm.parse("<html>no data</html>", "x"))
    }
}

class SettingsImportTest {
    @Test fun importsDesktopConfig() {
        val json = """{"form_url":"https://f","plant1":"1","nickname":"TestNick","column":"1",
            "ai_vote":true,"extra_answers":{"город":"Москва"},
            "ai_providers":{"deepseek":{"enabled":true,"model":"deepseek-flash","api_key":"sk-x","expert":true},
                            "gemini":{"enabled":true,"model":"gemini-3.6-flash","api_key":"g","reasoning":["low",null]}}}"""
        val s = Settings.fromDesktopJson(json)
        assertEquals("TestNick", s.nickname)
        assertEquals(listOf("город" to "Москва"), s.extraAnswers)
        val ds = s.providers.first { it.id == "deepseek" }
        assertTrue(ds.enabled && ds.expert); assertEquals("sk-x", ds.apiKey)
        assertEquals(listOf("low", null), s.providers.first { it.id == "gemini" }.reasoning)
        // Сохранение без ключей и обратное чтение
        val back = Settings().withJson(s.toJson())
        assertEquals("TestNick", back.nickname)
        assertEquals("", back.providers.first { it.id == "deepseek" }.apiKey)
    }
}
