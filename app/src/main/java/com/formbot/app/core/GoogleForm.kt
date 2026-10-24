package com.formbot.app.core

import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.time.LocalDate

enum class QKind { TEXT, PARAGRAPH, RADIO, DROPDOWN, CHECKBOX, SCALE, GRID_ROW, DATE, TIME, UNSUPPORTED }

data class Question(
    val itemId: Long,
    val entryId: Long,
    val title: String,
    val description: String,
    val kind: QKind,
    val options: List<String>,
    val hasOther: Boolean,
    val required: Boolean,
    val page: Int,
    /** (подпись, url) — картинки самого вопроса, блоков «Изображение» над ним и вариантов. */
    val images: List<Pair<String, String>> = emptyList(),
) {
    val isChoice get() = kind in setOf(QKind.RADIO, QKind.DROPDOWN, QKind.CHECKBOX, QKind.SCALE, QKind.GRID_ROW)
    val isText get() = kind == QKind.TEXT || kind == QKind.PARAGRAPH
    val forAi: String get() = if (description.isBlank()) title else "$title\nПояснение к вопросу: $description"
}

sealed class Answer {
    data class Text(val value: String) : Answer()
    data class Choice(val label: String) : Answer()
    data class Multi(val labels: List<String>) : Answer()
    data class Date(val date: LocalDate) : Answer()
    data class Time(val hour: Int, val minute: Int) : Answer()

    fun display(): String = when (this) {
        is Text -> value
        is Choice -> label
        is Multi -> labels.joinToString(", ")
        is Date -> "%02d.%02d.%04d".format(date.dayOfMonth, date.monthValue, date.year)
        is Time -> "%02d:%02d".format(hour, minute)
    }
}

data class ParsedForm(
    val title: String,
    val questions: List<Question>,
    val fbzx: String,
    val pageCount: Int,
    val actionUrl: String,
    val needsEmail: Boolean,
)

sealed class FormState {
    data class Open(val form: ParsedForm) : FormState()
    data object Closed : FormState()
    data object NeedsLogin : FormState()
    data class Error(val message: String) : FormState()
}

enum class SubmitResult { OK, INVALID, UNKNOWN }

/** Google Forms без браузера: вопросы берутся из FB_PUBLIC_LOAD_DATA_, ответ — обычный POST. */
class GoogleForm(private val http: OkHttpClient) {

    fun fetch(url: String): FormState {
        val req = Request.Builder().url(normalizeUrl(url)).header("User-Agent", UA).header("Accept-Language", LANG).build()
        val (finalUrl, code, html) = try {
            http.newCall(req).execute().use { r -> Triple(r.request.url.toString(), r.code, r.body?.string().orEmpty()) }
        } catch (e: Exception) {
            return FormState.Error("сеть: ${e.message ?: e.javaClass.simpleName}")
        }
        if ("accounts.google.com" in finalUrl || "ServiceLogin" in finalUrl) return FormState.NeedsLogin
        if ("closedform" in finalUrl || CLOSED_RE.containsMatchIn(html)) return FormState.Closed
        if (code >= 400) return FormState.Error("HTTP $code")
        return try {
            parse(html, finalUrl)?.let { FormState.Open(it) } ?: FormState.Closed
        } catch (e: Exception) {
            FormState.Error("не удалось разобрать форму: ${e.message}")
        }
    }

    /** ID формы; короткую ссылку раскрываем переходом по редиректу (только GET, без отправки). */
    fun resolveFormId(url: String): String? {
        formIdOf(url)?.let { return it }
        val req = Request.Builder().url(normalizeUrl(url)).header("User-Agent", UA).head().build()
        return runCatching { http.newCall(req).execute().use { formIdOf(it.request.url.toString()) } }.getOrNull()
    }

    fun submit(form: ParsedForm, answers: Map<Question, Answer>, email: String): SubmitResult {
        val body = FormBody.Builder()
        for ((q, a) in answers) addAnswer(body, q, a)
        if (form.needsEmail && email.isNotBlank()) body.add("emailAddress", email)
        body.add("fvv", "1")
        body.add("partialResponse", "[null,null,\"${form.fbzx}\"]")
        body.add("pageHistory", (0 until form.pageCount).joinToString(","))
        body.add("fbzx", form.fbzx)
        body.add("submissionTimestamp", System.currentTimeMillis().toString())
        val req = Request.Builder().url(form.actionUrl).header("User-Agent", UA).header("Accept-Language", LANG)
            .post(body.build()).build()
        return try {
            http.newCall(req).execute().use { r -> classify(r.code, r.body?.string().orEmpty()) }
        } catch (e: Exception) {
            SubmitResult.UNKNOWN  // запрос мог дойти — повтор создал бы дубль
        }
    }

    private fun addAnswer(b: FormBody.Builder, q: Question, a: Answer) {
        val key = "entry.${q.entryId}"
        when (a) {
            is Answer.Text -> b.add(key, a.value)
            is Answer.Choice -> {
                if (q.hasOther && TextRules.matchLabelIndex(q.options, a.label) < 0) {
                    b.add(key, "__other_option__"); b.add("$key.other_option_response", a.label)
                } else b.add(key, a.label)
            }
            is Answer.Multi -> a.labels.forEach { b.add(key, it) }
            is Answer.Date -> {
                b.add("${key}_year", a.date.year.toString())
                b.add("${key}_month", a.date.monthValue.toString())
                b.add("${key}_day", a.date.dayOfMonth.toString())
            }
            is Answer.Time -> {
                b.add("${key}_hour", "%02d".format(a.hour)); b.add("${key}_minute", "%02d".format(a.minute))
            }
        }
    }

    companion object {
        const val LANG = "ru-RU,ru;q=0.9,en;q=0.5"
        const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
        private val CLOSED_RE = Regex(
            "больше не принимает ответы|no longer accepting responses|не принимает ответы",
            RegexOption.IGNORE_CASE,
        )
        private val OK_RE = Regex(
            "freebirdFormviewerViewResponseConfirmationMessage|ответ записан|response has been recorded",
            RegexOption.IGNORE_CASE,
        )
        private val DATA_RE = Regex("FB_PUBLIC_LOAD_DATA_\\s*=\\s*(.*?);\\s*</script>", RegexOption.DOT_MATCHES_ALL)
        private val FBZX_RE = Regex("name=\"fbzx\"\\s+value=\"([^\"]+)\"")
        private val ACTION_RE = Regex("<form[^>]*action=\"([^\"]+/formResponse)\"")
        private val PARAMS_ID_RE = Regex("data-params=\"%\\.@\\.\\[(\\d+),")
        private val IMG_RE = Regex("<img[^>]*src=\"(https?://[^\"]+)\"")

        /**
         * Итог отправки. Язык страницы зависит от IP (бывает и латышский!), поэтому
         * успех определяем по разметке: ссылка «отправить ещё ответ» и нет вопросов.
         * Отказ проверки — только HTTP 400. Всё прочее — UNKNOWN: повторять нельзя,
         * иначе можно создать дубль.
         */
        fun classify(code: Int, html: String): SubmitResult = when {
            code == 400 -> SubmitResult.INVALID
            code == 200 && ("usp=form_confirm" in html || OK_RE.containsMatchIn(html) ||
                ("role=\"listitem\"" !in html && ACTION_RE.find(html) == null && "FB_PUBLIC_LOAD_DATA_" in html)) -> SubmitResult.OK
            else -> SubmitResult.UNKNOWN
        }

        fun normalizeUrl(url: String): String {
            var u = url.trim()
            if (!u.startsWith("http")) u = "https://$u"
            return u
        }

        /**
         * Ссылка на эту же форму с уже проставленными ответами (usp=pp_url): пользователь открывает её
         * в браузере, проверяет и сам нажимает «Отправить».
         */
        fun prefillUrl(form: ParsedForm, answers: Map<Question, Answer>, email: String = ""): String {
            val b = form.actionUrl.substringBefore("?").replace(Regex("/formResponse$"), "/viewform")
                .toHttpUrl().newBuilder().addQueryParameter("usp", "pp_url")
            for ((q, a) in answers) {
                val key = "entry.${q.entryId}"
                when (a) {
                    is Answer.Text -> b.addQueryParameter(key, a.value)
                    is Answer.Choice -> if (q.hasOther && TextRules.matchLabelIndex(q.options, a.label) < 0) {
                        b.addQueryParameter(key, "__other_option__")
                        b.addQueryParameter("$key.other_option_response", a.label)
                    } else b.addQueryParameter(key, a.label)
                    is Answer.Multi -> a.labels.forEach { b.addQueryParameter(key, it) }
                    is Answer.Date -> b.addQueryParameter(key, "%04d-%02d-%02d".format(a.date.year, a.date.monthValue, a.date.dayOfMonth))
                    is Answer.Time -> b.addQueryParameter(key, "%02d:%02d".format(a.hour, a.minute))
                }
            }
            if (form.needsEmail && email.isNotBlank()) b.addQueryParameter("emailAddress", email)
            return b.build().toString()
        }

        private val FORM_ID_RE = Regex("docs\\.google\\.com/forms/d/(?:e/)?([\\w-]+)")

        /** ID формы из полной ссылки; у короткой forms.gle его не видно — null. */
        fun formIdOf(url: String): String? = FORM_ID_RE.find(url)?.groupValues?.get(1)

        private fun unescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")

        private fun JSONArray.str(i: Int): String = if (length() <= i || isNull(i)) "" else optString(i)
        private fun JSONArray.arr(i: Int): JSONArray? = if (length() <= i || isNull(i)) null else optJSONArray(i)
        private fun truthy(v: Any?): Boolean = when (v) {
            is Boolean -> v
            is Number -> v.toInt() == 1
            else -> false
        }

        /** Картинки по id вопроса из серверной разметки (только первая страница формы). */
        private fun imagesById(html: String, firstPageIds: List<Long>): Map<Long, List<String>> {
            val chunks = html.split("role=\"listitem\"").drop(1)
            val result = mutableMapOf<Long, List<String>>()
            chunks.forEachIndexed { index, chunk ->
                val id = PARAMS_ID_RE.find(chunk)?.groupValues?.get(1)?.toLongOrNull()
                    ?: firstPageIds.getOrNull(index) ?: return@forEachIndexed
                val imgs = IMG_RE.findAll(chunk).map { unescape(it.groupValues[1]) }
                    .filter { "gstatic.com" !in it }.toList()
                if (imgs.isNotEmpty()) result[id] = imgs
            }
            return result
        }

        fun parse(html: String, finalUrl: String): ParsedForm? {
            val raw = DATA_RE.find(html)?.groupValues?.get(1) ?: return null
            val data = JSONArray(raw)
            val info = data.arr(1) ?: return null
            val items = info.arr(1) ?: JSONArray()
            val title = data.str(3).ifBlank { info.str(8) }
            val fbzx = FBZX_RE.find(html)?.groupValues?.get(1) ?: data.str(14)
            val action = ACTION_RE.find(html)?.groupValues?.get(1)?.let(::unescape)
                ?: finalUrl.substringBefore("?").replace(Regex("/viewform$"), "/formResponse")

            val firstPageIds = mutableListOf<Long>()
            for (i in 0 until items.length()) {
                val it = items.optJSONArray(i) ?: continue
                if (it.optInt(3) == 8) break
                firstPageIds += it.optLong(0)
            }
            val imgMap = imagesById(html, firstPageIds)

            val questions = mutableListOf<Question>()
            var page = 0
            var imagesBefore = mutableListOf<Pair<String, String>>()
            for (i in 0 until items.length()) {
                val item = items.optJSONArray(i) ?: continue
                val itemId = item.optLong(0)
                val qTitle = item.str(1).trim()
                val desc = item.str(2).trim()
                val type = item.optInt(3, -1)
                val answers = item.arr(4)
                val imgs = imgMap[itemId].orEmpty()
                when (type) {
                    8 -> { page++; continue }
                    11 -> {
                        val cap = "Картинка перед вопросом" + if (qTitle.isNotBlank()) " «$qTitle»" else ""
                        imagesBefore.addAll(imgs.map { cap to it }); continue
                    }
                    6, 12 -> continue
                }
                if (answers == null || answers.length() == 0) continue

                fun options(row: JSONArray): Pair<List<String>, Boolean> {
                    val opts = row.arr(1) ?: return emptyList<String>() to false
                    var other = false
                    val labels = mutableListOf<String>()
                    for (k in 0 until opts.length()) {
                        val o = opts.optJSONArray(k) ?: continue
                        if (truthy(o.opt(4))) { other = true; continue }
                        labels += o.str(0)
                    }
                    return labels to other
                }

                val kind = when (type) {
                    0 -> QKind.TEXT; 1 -> QKind.PARAGRAPH; 2 -> QKind.RADIO; 3 -> QKind.DROPDOWN
                    4 -> QKind.CHECKBOX; 5 -> QKind.SCALE; 7 -> QKind.GRID_ROW; 9 -> QKind.DATE
                    10 -> QKind.TIME; else -> QKind.UNSUPPORTED
                }
                val shownTitle = qTitle.ifBlank { if (imgs.isNotEmpty() || imagesBefore.isNotEmpty()) "(вопрос на картинке)" else "" }

                if (kind == QKind.GRID_ROW) {
                    for (r in 0 until answers.length()) {
                        val row = answers.optJSONArray(r) ?: continue
                        val rowTitle = row.arr(3)?.str(0).orEmpty()
                        questions += Question(
                            itemId, row.optLong(0), "$shownTitle — $rowTitle", desc, kind,
                            options(row).first, false, truthy(row.opt(2)), page,
                        )
                    }
                    imagesBefore = mutableListOf()
                    continue
                }

                val row = answers.optJSONArray(0) ?: continue
                val (labels, other) = options(row)
                // Варианты-картинки идут в разметке последними: их число знаем из данных.
                val optsArr = row.arr(1)
                val optionHasImage = (0 until (optsArr?.length() ?: 0)).map { k ->
                    optsArr!!.optJSONArray(k)?.arr(5) != null
                }.filterIndexed { k, _ -> optsArr?.optJSONArray(k)?.let { !truthy(it.opt(4)) } ?: true }
                val nOptImgs = optionHasImage.count { it }
                val qImgs = imgs.dropLast(nOptImgs.coerceAtMost(imgs.size))
                val oImgs = imgs.takeLast(nOptImgs.coerceAtMost(imgs.size))
                val finalLabels = labels.mapIndexed { k, l -> l.ifBlank { "Картинка ${k + 1}" } }
                var oIdx = 0
                val optionImages = optionHasImage.mapIndexedNotNull { k, has ->
                    if (has && oIdx < oImgs.size) "Вариант ${k + 1} (${finalLabels.getOrElse(k) { "" }})" to oImgs[oIdx++] else null
                }

                questions += Question(
                    itemId = itemId,
                    entryId = row.optLong(0),
                    title = shownTitle,
                    description = desc,
                    kind = kind,
                    options = finalLabels,
                    hasOther = other,
                    required = truthy(row.opt(2)),
                    page = page,
                    images = imagesBefore + qImgs.map { "Картинка к вопросу" to it } + optionImages,
                )
                imagesBefore = mutableListOf()
            }
            return ParsedForm(
                title = title,
                questions = questions,
                fbzx = fbzx,
                pageCount = page + 1,
                actionUrl = action,
                needsEmail = "name=\"emailAddress\"" in html,
            )
        }
    }
}
