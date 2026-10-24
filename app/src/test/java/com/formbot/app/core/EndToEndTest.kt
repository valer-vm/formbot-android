package com.formbot.app.core

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Пост → выбор двух растений → заполнение и отправка. Шлёт настоящие ответы, поэтому запускается
 * только с -Pe2eForm=<ссылка на ТЕСТОВУЮ форму>. Реальную форму питомника сюда не подставлять.
 */
class EndToEndTest {
    private val formUrl = System.getProperty("e2eForm").orEmpty()

    @Test fun postToSubmittedForm() = runBlocking {
        assumeTrue("нужен -Pe2eForm", formUrl.isNotBlank())
        val post = Post(
            1, (System.currentTimeMillis() / 1000).toInt(), 0,
            """
            Актуальное предложение питомника на сегодня!📃

            ✅Larix laricina Nash Pendula C5 55/75
            ✅Pinus nigra Golden Sunrise new C5 15/-
            ✅Pinus sylvestris Ødegård C2 20/25 один в руки
            """.trimIndent(),
        )
        val offer = PlantPost.todayOffer(listOf(post))!!
        // Порядок отметки = порядок отправки: сначала Ødegård, потом Golden Sunrise.
        val chosen = listOf(offer.items[2].raw, offer.items[1].raw)

        val s = Settings(formUrl = formUrl, nickname = "FormBot-test", column = "0", useAi = false, maxAttempts = 3)
        val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        val problems = mutableListOf<String>()
        val engine = BotEngine(s, http, { println(it) }, {}, { req ->
            println("👤 ${req.question.title}")
            when {
                "Столица" in req.question.title -> Answer.Text("Москва")
                "предел" in req.question.title -> Answer.Text("Бесконечность")
                else -> null
            }
        }, onProblem = { problems += it })
        val done = engine.run(chosen)
        engine.close()
        assertEquals("проблемы: $problems", 2, done)
    }

    /** Ручной режим: ничего не отправляется, ссылка открывает форму с уже выбранным растением и ником. */
    @Test fun manualModeBuildsPrefilledLink() = runBlocking {
        assumeTrue("нужен -Pe2eForm", formUrl.isNotBlank())
        val s = Settings(formUrl = formUrl, nickname = "FormBot-prefill", column = "0", useAi = false, manualSubmit = true)
        val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        val links = mutableListOf<String>()
        val engine = BotEngine(s, http, { println(it) }, {}, { req ->
            if ("Столица" in req.question.title) Answer.Text("Москва") else Answer.Text("Бесконечность")
        }, onPrepared = { _, url -> links += url })
        assertEquals(1, engine.run(listOf("Pinus sylvestris Ødegård C2 20/25")))
        engine.close()
        val url = links.single()
        println(url)
        val html = http.newCall(okhttp3.Request.Builder().url(url).header("User-Agent", GoogleForm.UA).build()).execute().use { it.body!!.string() }
        // Предзаполненные значения Google вставляет в страницу — значит, форма откроется уже заполненной.
        assertTrue("ник не подставлен", html.contains("FormBot-prefill"))
        assertTrue("растение не выбрано", html.contains("Pinus sylvestris Ødegård C2 20/25"))
    }
}
