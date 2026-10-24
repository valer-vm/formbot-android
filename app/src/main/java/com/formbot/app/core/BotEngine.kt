package com.formbot.app.core

import android.util.Base64
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate
import kotlin.coroutines.coroutineContext

/** Вопрос, на который нужен ответ человека. */
data class HumanRequest(
    val question: Question,
    val images: List<ByteArray>,
    val reason: String,
)

class FatalError(message: String) : Exception(message)

/** Растения пока нет среди вариантов — форма, видимо, ещё не обновилась. */
private class OptionMissing(message: String) : Exception(message)

/**
 * Полный цикл для каждого растения: ждём открытия формы → отвечаем (правила → ИИ → человек)
 * → отправляем. Без браузера, поэтому работает на телефоне в фоне.
 */
class BotEngine(
    private val s: Settings,
    private val http: OkHttpClient,
    private val log: (String) -> Unit,
    private val onStatus: (String) -> Unit,
    private val askHuman: suspend (HumanRequest) -> Answer?,
    /** Проблема с конкретным растением — для громкого уведомления. */
    private val onProblem: (String) -> Unit = {},
    /** Ручная отправка: растение и ссылка на форму с готовыми ответами. */
    private val onPrepared: (String, String) -> Unit = { _, _ -> },
) {
    private val form = GoogleForm(http)
    private val ai = AiAnswerer(s, http, log)
    private val cache = mutableMapOf<String, Answer>()
    private val aiKeys = mutableSetOf<String>()

    fun close() = ai.close()

    /** Возвращает число успешно отправленных растений. */
    suspend fun run(chosen: List<String> = s.plants): Int {
        if (s.formUrl.isBlank()) throw FatalError("Не указана ссылка на форму")
        val plants = chosen.map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("") }
        var done = 0
        for ((index, plant) in plants.withIndex()) {
            log(if (plant.isBlank()) "\n🌿 Заполнение формы" else "\n🌿 Растение ${index + 1}/${plants.size}: $plant")
            if (processPlant(plant)) { done++; log("✅ Готово${if (plant.isBlank()) "" else ": $plant"}") }
            if (index < plants.lastIndex && s.delayBetweenPlants > 0) {
                log("⏱ Пауза ${s.delayBetweenPlants} с"); delay((s.delayBetweenPlants * 1000).toLong())
            }
        }
        log("\n🎉 Завершено: $done/${plants.size}")
        return done
    }

    private suspend fun waitForForm(): ParsedForm {
        var announced = false
        while (true) {
            coroutineContext.ensureActive()
            when (val st = form.fetch(s.formUrl)) {
                is FormState.Open -> { log("✅ Форма доступна: «${st.form.title}»"); return st.form }
                FormState.NeedsLogin -> throw FatalError(
                    "Форма требует входа в Google-аккаунт — такие формы приложение заполнить не может"
                )
                FormState.Closed -> if (!announced) {
                    log("⏳ Форма ещё закрыта — проверяю каждые ${s.checkInterval} с"); announced = true
                    onStatus("Жду открытия формы")
                }
                is FormState.Error -> log("⚠️ ${st.message}")
            }
            delay((s.checkInterval * 1000).toLong().coerceAtLeast(300))
        }
    }

    private suspend fun processPlant(plant: String): Boolean {
        var missingSince = 0L
        var attempt = 0
        while (attempt < s.maxAttempts) {
            attempt++
            try {
                onStatus("Открываю форму")
                val f = waitForForm()
                onStatus("Заполняю")
                val started = System.nanoTime()
                val answers = fill(f, plant)
                log("✅ Ответы готовы за %.1f с".format((System.nanoTime() - started) / 1e9))
                if (s.manualSubmit) {
                    val url = GoogleForm.prefillUrl(f, answers, s.email)
                    log("📝 Форма заполнена — откройте её и нажмите «Отправить» сами")
                    onPrepared(plant, url)
                    return true
                }
                onStatus("Отправляю")
                log("📤 Отправляю…")
                when (form.submit(f, answers, s.email)) {
                    SubmitResult.OK -> { log("✅ Ответ отправлен!"); return true }
                    SubmitResult.UNKNOWN -> {
                        log("❗ Не удалось понять, принят ли ответ. Повторять не буду, чтобы не создать дубль — проверь таблицу.")
                        return false
                    }
                    SubmitResult.INVALID -> {
                        log("⚠️ Попытка $attempt/${s.maxAttempts}: форма не приняла ответ")
                        aiKeys.forEach { cache.remove(it) }; aiKeys.clear()  // возможно, ошибся ИИ
                    }
                }
            } catch (e: OptionMissing) {
                val now = System.currentTimeMillis()
                if (missingSince == 0L) {
                    missingSince = now
                    log("⏳ «$plant» пока нет в форме — жду обновления до ${OPTION_WAIT_MIN} мин")
                    onStatus("Жду растение в форме")
                }
                if (now - missingSince > OPTION_WAIT_MIN * 60_000L) {
                    log("❌ ${e.message}"); onProblem("Растения нет в форме: $plant"); return false
                }
                attempt--  // ожидание обновления — не попытка
                delay((s.checkInterval * 1000).toLong().coerceAtLeast(300))
                continue
            } catch (e: FatalError) {
                log("❌ ${e.message}"); onProblem("${e.message}"); return false
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                log("⚠️ Попытка $attempt/${s.maxAttempts}: ${e.message ?: e.javaClass.simpleName}")
            }
            if (attempt < s.maxAttempts) delay((s.retryDelay * 1000).toLong())
        }
        log("❌ Не удалось обработать${if (plant.isBlank()) " форму" else ": $plant"}")
        onProblem("Не удалось отправить${if (plant.isBlank()) " форму" else ": $plant"}")
        return false
    }

    private fun key(q: Question) = TextRules.norm(q.forAi) + "|" + q.images.joinToString("|") { it.second }

    /** Ответ по правилам — мгновенно и без ИИ. */
    private fun ruleAnswer(q: Question): String? {
        val text = q.title.lowercase()
        for ((fragment, answer) in s.extraAnswers) if (TextRules.fragmentMatches(fragment, text)) return answer
        if ("столбик" in text && s.column.isNotBlank()) return s.column
        if (Regex("(?<![\\p{L}])ник(?![\\p{L}])").containsMatchIn(text) && s.nickname.isNotBlank()) return s.nickname
        if ("телефон" in text && s.phone.isNotBlank()) return s.phone
        if (Regex("примечан|комментар|пожелан").containsMatchIn(text) && s.note.isNotBlank()) return s.note
        if (Regex("e-?mail|почт").containsMatchIn(text) && s.email.isNotBlank()) return s.email
        if ("день недели" in text && Regex("сегодня|сейчас|текущ").containsMatchIn(text)) {
            return TextRules.weekdayToday().replaceFirstChar { it.uppercase() }.also { log("📅 ${q.title} → $it") }
        }
        TextRules.answerByInstruction(q.title)?.let { log("✍️ ${q.title} → $it"); return it }
        TextRules.solveMath(q.title)?.let { log("🧮 ${q.title} → $it"); return it }
        return null
    }

    private fun textToAnswer(q: Question, value: String): Answer? = when (q.kind) {
        QKind.DATE -> parseDate(value)?.let { Answer.Date(it) }
        QKind.TIME -> Regex("(\\d{1,2})[:.](\\d{2})").find(value)?.let {
            Answer.Time(it.groupValues[1].toInt(), it.groupValues[2].toInt())
        }
        QKind.TEXT, QKind.PARAGRAPH -> Answer.Text(value)
        QKind.CHECKBOX -> TextRules.matchLabelIndex(q.options, value).takeIf { it >= 0 }
            ?.let { Answer.Multi(listOf(q.options[it])) }
        else -> TextRules.matchLabelIndex(q.options, value).takeIf { it >= 0 }?.let { Answer.Choice(q.options[it]) }
            ?: if (q.hasOther) Answer.Choice(value) else null
    }

    private fun parseDate(v: String): LocalDate? {
        Regex("(\\d{1,2})[./-](\\d{1,2})[./-](\\d{4})").find(v)?.let {
            return runCatching { LocalDate.of(it.groupValues[3].toInt(), it.groupValues[2].toInt(), it.groupValues[1].toInt()) }.getOrNull()
        }
        Regex("(\\d{4})-(\\d{1,2})-(\\d{1,2})").find(v)?.let {
            return runCatching { LocalDate.of(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }.getOrNull()
        }
        return null
    }

    private suspend fun fill(f: ParsedForm, plant: String): Map<Question, Answer> {
        ai.formTitle = f.title
        val answers = linkedMapOf<Question, Answer>()
        val plantQ = if (plant.isBlank()) null else pickPlant(f, plant, answers)

        for (q in f.questions) {
            val picks = if (q.isChoice) " (${q.options.size} вар.)" else ""
            log("📋 [${q.kind.name.lowercase()}] ${q.title}$picks" + if (q.images.isNotEmpty()) " 🖼×${q.images.size}" else "")
            if (q.kind == QKind.UNSUPPORTED) {
                if (q.required) throw FatalError("Вопрос «${q.title}» такого типа (например, загрузка файла) приложение заполнить не может")
                continue
            }
            if (q == plantQ) continue
            val byRule = ruleAnswer(q)?.let { textToAnswer(q, it) }
            if (byRule != null) { answers[q] = byRule; continue }
            cache[key(q)]?.let { answers[q] = it }
        }

        val todo = f.questions.filter {
            it !in answers && it.kind != QKind.UNSUPPORTED && (it.required || s.aiOptional)
        }
        if (todo.isNotEmpty() && ai.enabled) resolveWithAi(todo, answers)

        for (q in f.questions) {
            if (q in answers || !q.required || q.kind == QKind.UNSUPPORTED) continue
            onStatus("Нужен ваш ответ")
            val imgs = q.images.mapNotNull { download(it.second) }
            val a = askHuman(HumanRequest(q, imgs, "ИИ не знает ответа")) ?: throw Exception("Не получен ответ на обязательный вопрос: ${q.title}")
            answers[q] = a
            cache[key(q)] = a
            onStatus("Заполняю")
        }
        if (f.needsEmail && s.email.isBlank()) throw FatalError("Форма собирает e-mail — укажи его в настройках")
        return answers
    }

    /**
     * Вопрос с растениями и вариант в нём. Строго: название + контейнер + размер.
     * Нет варианта — OptionMissing (ждём обновления формы), два подходящих — не угадываем.
     */
    private fun pickPlant(f: ParsedForm, plant: String, answers: MutableMap<Question, Answer>): Question {
        val choices = f.questions.filter { it.kind == QKind.RADIO || it.kind == QKind.DROPDOWN }
        if (choices.isEmpty()) throw FatalError("В форме нет вопроса с выбором растения")
        for (q in choices) when (val m = PlantPost.matchManual(q.options, plant)) {
            is OptionMatch.Found -> {
                answers[q] = Answer.Choice(q.options[m.index])
                log("🌱 Выбрано: ${q.options[m.index]}")
                return q
            }
            is OptionMatch.Ambiguous -> throw FatalError(
                "«$plant» подходит к нескольким вариантам: ${m.labels.joinToString("; ")}"
            )
            OptionMatch.NotFound -> {}
        }
        val q = choices.firstOrNull { Regex("растен", RegexOption.IGNORE_CASE).containsMatchIn(it.title) }
            ?: choices.maxBy { it.options.size }
        throw OptionMissing("Растение «$plant» не найдено. Варианты: ${q.options.take(15).joinToString(", ").ifEmpty { "нет" }}")
    }

    private suspend fun resolveWithAi(todo: List<Question>, answers: MutableMap<Question, Answer>) = coroutineScope {
        val started = System.nanoTime()
        val images = todo.associateWith { q -> loadImages(q) }
        for (q in todo) if (!images[q].isNullOrEmpty()) log("🖼 ${q.title}: картинок для ИИ — ${images[q]!!.size}")

        fun hint(q: Question) = when (q.kind) {
            QKind.DATE -> q.forAi + "\n(Ответь датой в формате ДД.ММ.ГГГГ)"
            QKind.TIME -> q.forAi + "\n(Ответь временем в формате ЧЧ:ММ)"
            else -> q.forAi
        }
        suspend fun single(q: Question, expert: Boolean): AiReply<Answer> {
            val imgs = images[q]
            return if (q.isChoice) {
                val r = ai.choose(hint(q), q.options, q.kind == QKind.CHECKBOX, imgs, expert)
                AiReply(r.value?.let { if (q.kind == QKind.CHECKBOX) Answer.Multi(it) else Answer.Choice(it[0]) }, r.info)
            } else {
                val r = ai.text(hint(q), imgs, expert)
                AiReply(r.value?.let { textToAnswer(q, it) }, r.info)
            }
        }

        // Пакетом (быстрая модель) — простые вопросы; картинки и сложные — отдельно.
        val solo = todo.filter { !images[it].isNullOrEmpty() || (ai.hasExpert && TextRules.isHardQuestion(it.forAi)) }
        val batchable = todo - solo.toSet()
        val outcome = mutableMapOf<Question, AiReply<Answer>>()
        val pending = solo.map { it to false }.toMutableList()
        if (batchable.size >= 2) {
            val r = ai.batch(batchable.map {
                AiAnswerer.BatchItem(hint(it), it.options, it.kind == QKind.CHECKBOX, !it.isChoice)
            })
            val list = r.value
            if (list == null) pending += batchable.map { it to false } else batchable.forEachIndexed { i, q ->
                val v = list[i]
                val unsure = v == "" || (v is String && TextRules.looksUnsure(v))
                when {
                    v == null -> pending += q to false
                    unsure && ai.hasExpert -> { log("🧠 Пакет не справился с «${q.title.take(60)}» — спрашиваю эксперта"); pending += q to true }
                    unsure -> outcome[q] = AiReply(null, r.info)
                    else -> {
                        @Suppress("UNCHECKED_CAST")
                        val a = when (v) {
                            is String -> textToAnswer(q, v)
                            is List<*> -> if (q.kind == QKind.CHECKBOX) Answer.Multi(v as List<String>) else Answer.Choice(v[0] as String)
                            else -> null
                        }
                        outcome[q] = AiReply(a, r.info)
                    }
                }
            }
        } else pending += batchable.map { it to false }

        pending.map { (q, expert) -> async { q to single(q, expert) } }.awaitAll().forEach { (q, r) -> outcome[q] = r }

        var answered = 0
        for (q in todo) {
            val r = outcome[q] ?: AiReply(null, "")
            val a = r.value
            when {
                a != null -> {
                    answers[q] = a; cache[key(q)] = a; aiKeys += key(q); answered++
                    log("🤖 ${q.title} → ${a.display()}  [${r.info}]")
                }
                r.info.isNotEmpty() -> log("❔ ИИ не знает ответа: ${q.title}  [${r.info}]")
                else -> log("⚠️ ИИ недоступен для вопроса: ${q.title}")
            }
        }
        log("⚡ ИИ ответил на $answered из ${todo.size} за %.1f с".format((System.nanoTime() - started) / 1e9))
    }

    // ---------- картинки ----------

    private fun download(url: String): ByteArray? {
        val candidates = buildList {
            if ("googleusercontent.com" in url && Regex("=w\\d+").containsMatchIn(url)) {
                add(Regex("=w\\d+(?:-h\\d+)?").replaceFirst(url, "=w1600"))  // крупнее — ИИ прочитает мелкий текст
            }
            add(url)
        }
        for (c in candidates) {
            try {
                http.newCall(Request.Builder().url(c).header("User-Agent", GoogleForm.UA).build()).execute().use { r ->
                    val bytes = r.body?.bytes()
                    if (r.isSuccessful && bytes != null && sniffMime(bytes) != null && bytes.size <= MAX_IMAGE) return bytes
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun loadImages(q: Question): List<AiImage> = q.images.take(8).mapNotNull { (caption, url) ->
        val bytes = download(url) ?: return@mapNotNull null
        AiImage(caption, sniffMime(bytes)!!, Base64.encodeToString(bytes, Base64.NO_WRAP))
    }

    companion object {
        const val MAX_IMAGE = 4_500_000
        const val OPTION_WAIT_MIN = 5

        fun sniffMime(d: ByteArray): String? = when {
            d.size > 4 && d[0] == 0x89.toByte() && d[1] == 'P'.code.toByte() -> "image/png"
            d.size > 3 && d[0] == 0xFF.toByte() && d[1] == 0xD8.toByte() -> "image/jpeg"
            d.size > 6 && String(d, 0, 6, Charsets.ISO_8859_1).let { it == "GIF87a" || it == "GIF89a" } -> "image/gif"
            d.size > 12 && String(d, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(d, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "image/webp"
            else -> null
        }
    }
}
