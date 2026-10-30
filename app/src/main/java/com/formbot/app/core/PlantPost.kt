package com.formbot.app.core

import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Позиция из поста: «Picea abies Irva Snake C5 30/40 один в руки» или только название
 * «Pinus strobus Goldie 🌟» — тогда контейнер и размер берутся из вариантов формы.
 */
data class OfferItem(
    /** Строка без галочки — так её видит пользователь и так она уходит в сопоставление. */
    val raw: String,
    val name: String,
    val container: String?,
    val size: String?,
    val notes: List<String>,
    /** Сколько раз строка повторилась в посте (обычно — несколько размеров в форме). */
    val count: Int = 1,
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
    /** Украшения в посте: эмодзи, ‼, звёздочки — всё, что не буквы, цифры и знаки названий. */
    private val DECOR = Regex("[^\\p{L}\\p{N}\\s/.,'’()\\-+&×]")
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
            .groupBy { it.raw }.map { (_, same) -> same.first().copy(count = same.size) }

    /** Первая ссылка на Google-форму: из текста или из скрытых ссылок (entities). */
    fun formUrl(text: String, extraUrls: List<String> = emptyList()): String? =
        (extraUrls + text).firstNotNullOfOrNull { FORM_URL.find(it)?.value }

    /** Строка позиции (или вариант из формы) → название, контейнер, размер, пометки. */
    fun parseLine(line: String): OfferItem? {
        val raw = DECOR.replace(line, " ").replace(SPACES, " ").trim()
        if (raw.isEmpty() || raw.none { it.isLetter() }) return null
        val c = CONTAINER.find(raw)
        val head = (if (c != null) raw.substring(0, c.range.first) else raw).trim()
        val rest = if (c != null) raw.substring(c.range.last + 1) else ""
        val size = SIZE.find(rest)
        val tail = (if (size != null) rest.substring(size.range.last + 1) else rest).trim()
        val notes = buildList {
            PARENS.findAll(head).forEach { add(it.value.trim('(', ')').trim()) }
            head.split(' ').filter { it.lowercase() in NOTE_WORDS }.forEach { add(it) }
            if (tail.isNotEmpty()) add(tail)
        }.filter { it.isNotBlank() && it != "x" }
        // Название без «new»: в посте это украшение, а в форме его может и не быть.
        val name = head.split(' ').filter { it.lowercase() !in NOTE_WORDS }.joinToString(" ").trim()
        return OfferItem(
            raw = raw,
            name = name.ifEmpty { raw },
            container = c?.let { "C" + it.groupValues[1].replace('.', ',') },
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
            // В посте только название — подходят все размеры этого растения в форме.
            val hits = variantIndices(options, item.name)
            return when (hits.size) {
                0 -> OptionMatch.NotFound
                1 -> OptionMatch.Found(hits[0])
                else -> OptionMatch.Ambiguous(hits.map { options[it] })
            }
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
        val m = matchOption(options, item)
        if (m != OptionMatch.NotFound || item.container != null) return m
        // Ручной ввод без контейнера — ещё и прежнее мягкое сравнение текста.
        val i = TextRules.matchLabelIndex(options, wanted)
        return if (i >= 0) OptionMatch.Found(i) else OptionMatch.NotFound
    }

    /** Все варианты формы с этим названием (разные контейнеры и размеры). */
    fun variants(options: List<String>, name: String): List<String> = variantIndices(options, name).map { options[it] }

    private fun variantIndices(options: List<String>, name: String): List<Int> {
        val parsed = options.map { parseLine(it) }
        for (squeeze in listOf(false, true)) {
            val want = nameKey(name, squeeze)
            if (want.isEmpty()) return emptyList()
            val hits = parsed.indices.filter { parsed[it]?.let { p -> nameKey(p.name, squeeze) } == want }
            if (hits.isNotEmpty()) return hits
        }
        return emptyList()
    }
}
