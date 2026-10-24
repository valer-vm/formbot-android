package com.formbot.app.core

import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Позиция из поста: «Picea abies Irva Snake C5 30/40 один в руки». */
data class OfferItem(
    /** Строка без галочки — так её видит пользователь и так она уходит в сопоставление. */
    val raw: String,
    val name: String,
    val container: String?,
    val size: String?,
    val notes: List<String>,
) {
    val oneEach: Boolean get() = notes.any { PlantPost.isOneEach(it) }
}

/** Сообщение из Telegram, уже без TDLib-типов. date/editDate — unix-время. */
data class Post(val id: Long, val date: Int, val editDate: Int, val text: String, val urls: List<String> = emptyList())

/** Предложение дня: позиции из самого свежего подходящего поста. */
data class Offer(val postId: Long, val date: Int, val items: List<OfferItem>, val formUrl: String?)

sealed class OptionMatch {
    data class Found(val index: Int) : OptionMatch()
    data object NotFound : OptionMatch()
    data class Ambiguous(val labels: List<String>) : OptionMatch()
}

/** Разбор поста питомника и строгое сопоставление позиций с вариантами формы. */
object PlantPost {
    private val CHECK = Regex("^\\s*(?:✅|✔️|✔|☑️|☑)\\s*")
    private val CONTAINER = Regex("(?<![\\p{L}\\d])[CcСс]\\s?(\\d+(?:[.,]\\d+)?)(?![\\p{L}\\d])")
    private val SIZE = Regex("^\\s*(\\d+\\s*/\\s*(?:\\d+|-))")
    private val FORM_URL = Regex("https?://(?:forms\\.gle/[\\w-]+|docs\\.google\\.com/forms/[^\\s)\\]>»\"]+)")
    private val PARENS = Regex("\\([^)]*\\)")
    private val SPACES = Regex("\\s+")
    /** Пометки внутри названия, которых нет в форме. */
    private val NOTE_WORDS = setOf("new", "новинка", "новинки")

    val MSK: ZoneId = ZoneId.of("Europe/Moscow")

    fun mskDate(unix: Int): LocalDate = Instant.ofEpochSecond(unix.toLong()).atZone(MSK).toLocalDate()

    /** Сегодняшний (по МСК) пост с растениями; из нескольких — самый свежий, правки учтены в тексте. */
    fun todayOffer(posts: List<Post>, today: LocalDate = LocalDate.now(MSK)): Offer? =
        posts.filter { mskDate(it.date) == today }
            .map { it to parseItems(it.text) }
            .filter { it.second.isNotEmpty() }
            .maxWithOrNull(compareBy({ it.first.date }, { it.first.id }))
            ?.let { (p, items) -> Offer(p.id, p.date, items, formUrl(p.text, p.urls)) }

    fun isPlantLine(line: String) = CHECK.containsMatchIn(line)

    fun isOneEach(note: String) = Regex("од(ин|на|но)\\s+в\\s+рук", RegexOption.IGNORE_CASE).containsMatchIn(note)

    /** Позиции из текста поста; пустой список — пост не про растения. */
    fun parseItems(text: String): List<OfferItem> =
        text.lines().filter(::isPlantLine).mapNotNull { parseLine(CHECK.replace(it, "")) }

    /** Первая ссылка на Google-форму: из текста или из скрытых ссылок (entities). */
    fun formUrl(text: String, extraUrls: List<String> = emptyList()): String? =
        (extraUrls + text).firstNotNullOfOrNull { FORM_URL.find(it)?.value }

    /** Строка позиции (или вариант из формы) → название, контейнер, размер, пометки. */
    fun parseLine(line: String): OfferItem? {
        val raw = line.replace(SPACES, " ").trim()
        if (raw.isEmpty()) return null
        val c = CONTAINER.find(raw) ?: return OfferItem(raw, raw, null, null, emptyList())
        val name = raw.substring(0, c.range.first).trim()
        val rest = raw.substring(c.range.last + 1)
        val size = SIZE.find(rest)
        val tail = (if (size != null) rest.substring(size.range.last + 1) else rest).trim()
        val notes = buildList {
            PARENS.findAll(name).forEach { add(it.value.trim('(', ')').trim()) }
            name.split(' ').filter { it.lowercase() in NOTE_WORDS }.forEach { add(it) }
            if (tail.isNotEmpty()) add(tail)
        }.filter { it.isNotBlank() && it != "x" }
        return OfferItem(
            raw = raw,
            name = name.ifEmpty { raw },
            container = "C" + c.groupValues[1].replace('.', ','),
            size = size?.groupValues?.get(1)?.replace(" ", ""),
            notes = notes,
        )
    }

    /** Ключ сравнения: регистр, похожие буквы, диакритика, апострофы; пометки и скобки — прочь. */
    fun nameKey(name: String, squeeze: Boolean = false): String {
        var t = PARENS.replace(name, " ")
        t = t.split(SPACES).filter { it.lowercase() !in NOTE_WORDS }.joinToString(" ")
        t = TextRules.norm(t).replace('ø', 'o').replace('æ', 'a').replace('ß', 's')
        t = Normalizer.normalize(t, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        t = t.replace(SPACES, " ").trim()
        return if (squeeze) t.replace(Regex("[^\\p{L}\\d]"), "") else t
    }

    private fun key(i: OfferItem, squeeze: Boolean) =
        Triple(nameKey(i.name, squeeze), i.container?.lowercase(), i.size)

    /**
     * Вариант формы для позиции: совпадают название, контейнер и размер.
     * Сначала точное сравнение, затем без пробелов и знаков; два подходящих — не угадываем.
     */
    fun matchOption(options: List<String>, item: OfferItem): OptionMatch {
        if (item.container == null) {
            // Строку не удалось разобрать — только однозначное совпадение текста.
            val i = TextRules.matchLabelIndex(options, item.raw)
            return if (i >= 0) OptionMatch.Found(i) else OptionMatch.NotFound
        }
        val parsed = options.map { parseLine(it) }
        for (squeeze in listOf(false, true)) {
            val want = key(item, squeeze)
            val hits = parsed.indices.filter { parsed[it]?.let { p -> key(p, squeeze) } == want }
            if (hits.size == 1) return OptionMatch.Found(hits[0])
            if (hits.size > 1) return OptionMatch.Ambiguous(hits.map { options[it] })
        }
        return OptionMatch.NotFound
    }

    /** То, что ввели руками в «Растение 1/2», тоже сопоставляем строго, если в строке есть контейнер. */
    fun matchManual(options: List<String>, wanted: String): OptionMatch {
        val item = parseLine(CHECK.replace(wanted, ""))
            ?: return OptionMatch.NotFound
        if (item.container != null) return matchOption(options, item)
        val i = TextRules.matchLabelIndex(options, wanted)
        return if (i >= 0) OptionMatch.Found(i) else OptionMatch.NotFound
    }
}
