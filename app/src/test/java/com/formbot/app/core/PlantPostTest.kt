package com.formbot.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlantPostTest {
    private val post1 = """
        Добрый  день, уважаемые участники!☀️

        Актуальное предложение питомника на сегодня!📃

        ✅Larix kaempferi Krejčí C5 30/40 один в руки
        ✅Picea abies Gold Drift (staked) C10 50/60 один в руки
        ✅Picea pungens Hermann Naue C7,5 25/- один в руки
        ✅Picea pungens Hermann Naue C7,5 30/25 один в руки
        ✅Pinus mugo Kaja C2 10/12

        ‼️ Через минуту все будет доступно для заказа через форму. Приятного выбора и хорошего дня!🤗🌲🌲

        Форма заказа:
        https://forms.gle/EXAMPLE123
    """.trimIndent()

    private val post2 = """
        Актуальное предложение питомника на сегодня!📃

        ✔️ Juniperus scopulorum Tolleson's Weeping   C5   50/60
        ✔️ Larix marschlinsii (x) Filip's Road Show   C5   30/40
        ✔️ Picea abies Virgata Holata   C5   50/60
        ✔️ Pinus koraiensis Baishan   C2   15/20
        ✔️ Pinus mugo Frohlingsgold   C2   20/30

        https://forms.gle/EXAMPLE123
    """.trimIndent()

    private val post3 = """
        ✅Larix laricina Nash Pendula C5 55/75
        ✅Picea abies Irva Snake C2 20/25
        ✅Picea abies Irva Snake C5 30/40
        ✅Picea pungens Gold Maria C2 25/30
        ✅Pinus nigra Golden Sunrise new C5 15/-
        ✅Pinus sylvestris Ødegård C2 20/25
    """.trimIndent()

    @Test fun parsesPost() {
        val items = PlantPost.parseItems(post1)
        assertEquals(5, items.size)
        val a = items[0]
        assertEquals("Larix kaempferi Krejčí", a.name)
        assertEquals("C5", a.container)
        assertEquals("30/40", a.size)
        assertTrue(a.oneEach)
        assertEquals("C7,5", items[2].container)
        assertEquals("25/-", items[2].size)
        assertEquals(listOf("staked", "один в руки"), items[1].notes)
        assertEquals("https://forms.gle/EXAMPLE123", PlantPost.formUrl(post1))
    }

    @Test fun parsesCheckVariantsAndWideSpacing() {
        val items = PlantPost.parseItems(post2)
        assertEquals(5, items.size)
        assertEquals("Juniperus scopulorum Tolleson's Weeping", items[0].name)
        assertEquals("C5", items[0].container)
        assertEquals("50/60", items[0].size)
        assertEquals(listOf("new"), PlantPost.parseItems(post3)[4].notes)
    }

    @Test fun nonPlantPost() {
        assertTrue(PlantPost.parseItems("Сегодня поставки не будет 😔").isEmpty())
        assertNull(PlantPost.formUrl("без ссылки"))
        assertEquals("https://forms.gle/abc", PlantPost.formUrl("текст", listOf("https://forms.gle/abc")))
    }

    @Test fun strictMatchingDistinguishesVariants() {
        val options = listOf(
            "Picea pungens Hermann Naue   C7,5   25/-",
            "Picea pungens Hermann Naue   C7,5   30/25",
            "Picea abies Irva Snake   С2   20/25",          // кириллическая «С»
            "Picea abies Irva Snake   C5   30/40",
            "Pinus sylvestris Odegard   C2   20/25",
            "Juniperus scopulorum Tolleson’s Weeping   C5   50/60",  // фигурный апостроф
            "Pinus nigra Golden Sunrise   C5   15/-",
            "Picea abies Gold Drift (staked)   C10   50/60",
            "Picea abies Gold Drift (flat growing)   C5   30/40",
        )
        fun m(line: String) = PlantPost.matchOption(options, PlantPost.parseLine(line)!!)
        assertEquals(OptionMatch.Found(1), m("Picea pungens Hermann Naue C7,5 30/25 один в руки"))
        assertEquals(OptionMatch.Found(0), m("Picea pungens Hermann Naue C7.5 25/-"))
        assertEquals(OptionMatch.Found(2), m("Picea abies Irva Snake C2 20/25"))
        assertEquals(OptionMatch.Found(3), m("Picea abies Irva Snake C5 30/40"))
        assertEquals(OptionMatch.Found(4), m("Pinus sylvestris Ødegård C2 20/25"))
        assertEquals(OptionMatch.Found(5), m("Juniperus scopulorum Tolleson's Weeping C5 50/60"))
        assertEquals(OptionMatch.Found(6), m("Pinus nigra Golden Sunrise new C5 15/-"))
        assertEquals(OptionMatch.Found(7), m("Picea abies Gold Drift (staked) C10 50/60 один в руки"))
        assertEquals(OptionMatch.NotFound, m("Picea abies Irva Snake C5 20/25"))   // размер не тот
        assertEquals(OptionMatch.NotFound, m("Picea abies Irva Snake C7,5 30/40")) // контейнер не тот
        assertEquals(OptionMatch.NotFound, m("Pinus mugo Kaja C2 10/12"))
    }

    @Test fun ambiguousIsNotGuessed() {
        val options = listOf("Pinus mugo Kaja   C2   10/12", "Pinus mugo Kaja (new)   C2   10/12")
        val r = PlantPost.matchOption(options, PlantPost.parseLine("Pinus mugo Kaja C2 10/12")!!)
        assertTrue(r is OptionMatch.Ambiguous)
    }

    @Test fun matchesRealNurseryOptions() {
        // Варианты выпадающего списка настоящей формы питомника (кириллическая «С» в контейнере, фигурный апостроф).
        val options = javaClass.classLoader!!.getResource("nursery_options.txt")!!.readText().lines().filter { it.isNotBlank() }
        for (opt in options) {
            val r = PlantPost.matchOption(options, PlantPost.parseLine(opt)!!)
            assertEquals(opt, OptionMatch.Found(options.indexOf(opt)), r)
        }
        val gold = PlantPost.parseLine("Pinus mugo Frohlingsgold C2 20/30")!!
        assertEquals("Pinus mugo Frohlingsgold   C2   20/30",
            options[(PlantPost.matchOption(options, gold) as OptionMatch.Found).index])
        val virgata = PlantPost.parseLine("Picea abies Virgata Vancgold C5 65/85")!!
        assertTrue(PlantPost.matchOption(options, virgata) is OptionMatch.Found)
    }

    @Test fun formIds() {
        assertEquals("1FAIpQLSexample", GoogleForm.formIdOf("https://docs.google.com/forms/d/e/1FAIpQLSexample/viewform?usp=send_form"))
        assertEquals("abc-1_2", GoogleForm.formIdOf("https://docs.google.com/forms/d/abc-1_2/edit"))
        assertNull(GoogleForm.formIdOf("https://forms.gle/EXAMPLE123"))
    }
}

class TodayOfferTest {
    private val today = java.time.LocalDate.of(2026, 10, 16)
    private fun at(h: Int, m: Int) = java.time.ZonedDateTime.of(today, java.time.LocalTime.of(h, m), PlantPost.MSK).toEpochSecond().toInt()

    @Test fun picksLatestTodayPostWithPlants() {
        val posts = listOf(
            Post(1, at(14, 0) - 86_400, 0, "✅Old plant C2 10/12"),
            Post(2, at(13, 0), 0, "Объявление: завтра будет поставка"),
            Post(3, at(14, 0), 0, "✅Pinus mugo Kaja C2 10/12\nhttps://forms.gle/AAA"),
            Post(4, at(14, 5), 0, "✅Picea abies Irva Snake C5 30/40", listOf("https://forms.gle/BBB")),
        )
        val o = PlantPost.todayOffer(posts, today)!!
        assertEquals(4L, o.postId)
        assertEquals("https://forms.gle/BBB", o.formUrl)
        assertEquals(1, o.items.size)
    }

    @Test fun nothingToday() {
        assertNull(PlantPost.todayOffer(listOf(Post(1, at(14, 0) - 86_400, 0, "✅A C2 1/2")), today))
        assertNull(PlantPost.todayOffer(listOf(Post(2, at(14, 0), 0, "Сегодня без поставки")), today))
    }

    @Test fun midnightMskBoundary() {
        // 23:30 МСК вчера = 20:30 UTC — по UTC тот же «вчера», по МСК тоже; 00:10 МСК сегодня — сегодня.
        assertNull(PlantPost.todayOffer(listOf(Post(1, at(0, 0) - 1800, 0, "✅A C2 1/2")), today))
        assertEquals(2L, PlantPost.todayOffer(listOf(Post(2, at(0, 10), 0, "✅A C2 1/2")), today)!!.postId)
    }
}

class PrefillTest {
    @Test fun buildsViewformLinkWithAnswers() {
        val html = javaClass.classLoader!!.getResource("form_sample.html")!!.readText()
        val f = GoogleForm.parse(html, "https://docs.google.com/forms/d/e/X/viewform")!!
        val plants = f.questions.first { it.title == "Растения" }
        val text = f.questions.first { it.kind == QKind.TEXT }
        val url = GoogleForm.prefillUrl(f, mapOf(plants to Answer.Choice("2"), text to Answer.Text("Ник с пробелом")))
        assertTrue(url, url.contains("/viewform?usp=pp_url"))
        assertTrue(url, url.contains("entry.1859561551=2"))
        assertTrue(url, url.contains("entry.${text.entryId}=%D0%9D%D0%B8%D0%BA%20"))
    }
}
