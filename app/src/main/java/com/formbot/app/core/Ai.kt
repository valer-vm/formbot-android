package com.formbot.app.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AiImage(val caption: String, val mime: String, val base64: String)

class AiHttpError(val status: Int, val body: String, val retryAfter: Double? = null) :
    Exception("HTTP $status: ${body.take(200)}")

class Provider(cfg: ProviderConfig) {
    val id = cfg.id
    val title = cfg.title
    val type = cfg.type
    val baseUrl = cfg.baseUrl.trim().trimEnd('/')
    @Volatile var model = cfg.model.trim()
    val key = cfg.apiKey.trim()
    val maxTokens = cfg.maxTokens
    val expert = cfg.expert
    val reasoning = cfg.reasoning.toMutableList().ifEmpty { mutableListOf(null) }
    @Volatile var latency: Double? = LATENCY["$id/$model"]
    val inflight = AtomicInteger(0)
    @Volatile var cooldownUntil = 0L
    @Volatile var disabled = false
    @Volatile var noVision = false
    @Volatile var healed = false
    @Volatile var fallbackTried = false
    val short get() = title.substringBefore(" ")

    companion object {
        /** Скорость провайдеров переживает перезапуск бота, пока живёт приложение. */
        val LATENCY = ConcurrentHashMap<String, Double>()
    }
}

/** Ответ ИИ: текст (или выбор) + пояснение для журнала. */
data class AiReply<T>(val value: T?, val info: String)

/**
 * Несколько провайдеров с ролями:
 *  * простые вопросы — самой быстрой модели (по замеренной скорости), с подстраховкой;
 *  * сложные и картинки — экспертам; если экспертов двое и больше — голосование;
 *  * отговорки быстрой модели («нужна формула») уходят эксперту.
 */
class AiAnswerer(settings: Settings, http: OkHttpClient, private val log: (String) -> Unit) {
    var formTitle = ""
    val providers: List<Provider>
    private val timeoutMs = (settings.aiTimeout * 1000).toLong()
    private val hedgeMs = (settings.aiHedgeDelay * 1000).toLong()
    private val vote = settings.aiVote
    private val http = http
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    init {
        val list = mutableListOf<Provider>()
        if (settings.useAi) for (cfg in settings.providers) {
            if (!cfg.enabled) continue
            if (cfg.baseUrl.isBlank() || cfg.model.isBlank()) {
                log("ℹ️ ИИ «${cfg.title}»: не заданы адрес или модель — пропускаю"); continue
            }
            val local = cfg.baseUrl.startsWith("http://localhost") || cfg.baseUrl.startsWith("http://127.0.0.1") ||
                cfg.baseUrl.startsWith("http://10.0.2.2") || cfg.baseUrl.startsWith("http://192.168.")
            if (cfg.apiKey.isBlank() && !local && cfg.id != "custom") {
                log("ℹ️ ИИ «${cfg.title}»: нет ключа — пропускаю"); continue
            }
            list += Provider(cfg)
        }
        providers = list
        if (providers.isNotEmpty()) {
            log("🤖 ИИ: " + providers.joinToString(", ") { it.title + (if (it.expert) " (эксперт)" else "") })
        } else if (settings.useAi) {
            log("ℹ️ ИИ выключен: ни у одного провайдера нет ключа (вкладка «ИИ»)")
        }
    }

    val enabled get() = providers.any { !it.disabled }
    val hasExpert get() = providers.any { it.expert && !it.disabled }

    fun close() = bg.cancel()

    // ---------- запрос к одному провайдеру ----------

    private fun system(batch: Boolean): String {
        val now = LocalDateTime.now()
        val wd = TextRules.WEEKDAYS[now.dayOfWeek.value - 1]
        val form = if (formTitle.isNotBlank()) "Название формы: «$formTitle». " else ""
        val common = "Ты заполняешь поля Google-формы. " +
            "Сегодня %02d.%02d.%04d, %s, %02d:%02d. ".format(now.dayOfMonth, now.monthValue, now.year, wd, now.hour, now.minute) +
            form +
            "На вопросы на общие знания, математику и логику отвечай всегда. " +
            "Не проси уточнений и не пиши «зависит от…»: если вопрос сформулирован неточно, " +
            "дай самый вероятный ответ, который ожидает автор формы. "
        return if (batch) common +
            "Тебе дан список вопросов. Верни ТОЛЬКО один JSON-объект без пояснений и без markdown: " +
            "ключ — номер вопроса, значение — ответ. Для текстовых вопросов значение — короткая строка " +
            "(на языке вопроса, без кавычек и точки в конце), для вопросов с выбором — номер варианта " +
            "(для выбора нескольких — массив номеров). Только если нужны личные данные человека или его " +
            "мнение, значение — строка \"UNKNOWN\"."
        else common +
            "Отвечай ТОЛЬКО самим ответом: коротко, на языке вопроса, без пояснений, без кавычек и точки " +
            "в конце. Если вопрос просит написать конкретное слово или фразу — напиши именно её. Если к " +
            "вопросу приложены картинки, внимательно рассмотри их: вопрос, пример или нужный текст обычно " +
            "именно на них. Только если для ответа нужны личные данные человека или его мнение, ответь " +
            "ровно одним словом: UNKNOWN."
    }

    private fun content(p: Provider, prompt: String, images: List<AiImage>?): Any {
        if (images.isNullOrEmpty()) return prompt
        val parts = JSONArray().put(JSONObject().put("type", "text").put("text", prompt))
        for (img in images) {
            parts.put(JSONObject().put("type", "text").put("text", img.caption + ":"))
            if (p.type == ApiType.ANTHROPIC) {
                parts.put(JSONObject().put("type", "image").put("source", JSONObject()
                    .put("type", "base64").put("media_type", img.mime).put("data", img.base64)))
            } else {
                parts.put(JSONObject().put("type", "image_url").put("image_url",
                    JSONObject().put("url", "data:${img.mime};base64,${img.base64}")))
            }
        }
        return parts
    }

    private suspend fun httpJson(url: String, headers: Map<String, String>, body: JSONObject?, timeout: Long): JSONObject {
        val client = http.newBuilder().callTimeout(timeout, TimeUnit.MILLISECONDS).build()
        val rb = Request.Builder().url(url).header("User-Agent", "form-bot/1.0")
        headers.forEach { (k, v) -> rb.header(k, v) }
        if (body != null) rb.post(body.toString().toRequestBody(JSON)) else rb.get()
        val call = client.newCall(rb.build())
        val response = suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { cont.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) { cont.resume(response) }
            })
        }
        response.use { r ->
            val text = r.body?.string().orEmpty()
            if (r.code >= 400) throw AiHttpError(r.code, text, r.header("Retry-After")?.toDoubleOrNull())
            return JSONObject(text)
        }
    }

    private suspend fun request(p: Provider, prompt: String, maxTokens: Int?, batch: Boolean, images: List<AiImage>?): String {
        val sys = system(batch)
        val cnt = content(p, prompt, images)
        val timeout = if (images.isNullOrEmpty()) timeoutMs else maxOf(timeoutMs, 30_000L)
        val text = if (p.type == ApiType.ANTHROPIC) {
            val data = httpJson(
                "${p.baseUrl}/v1/messages",
                mapOf("x-api-key" to p.key, "anthropic-version" to "2023-06-01"),
                JSONObject().put("model", p.model).put("max_tokens", maxTokens ?: p.maxTokens)
                    .put("system", sys)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", cnt))),
                timeout,
            )
            val blocks = data.optJSONArray("content") ?: JSONArray()
            (0 until blocks.length()).mapNotNull { blocks.optJSONObject(it) }
                .filter { it.optString("type") == "text" }.joinToString("") { it.optString("text") }
        } else {
            val payload = JSONObject().put("model", p.model).put("max_tokens", maxTokens ?: p.maxTokens)
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", sys))
                    .put(JSONObject().put("role", "user").put("content", cnt)))
            p.reasoning.firstOrNull()?.let { payload.put("reasoning_effort", it) }
            val headers = if (p.key.isNotBlank()) mapOf("Authorization" to "Bearer ${p.key}") else emptyMap()
            val data = httpJson("${p.baseUrl}/chat/completions", headers, payload, timeout)
            val msg = data.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            if (msg == null || msg.isNull("content")) "" else msg.optString("content")
        }
        // «Думающие» модели присылают рассуждения в <think>.
        val clean = Regex("<think>.*?(?:</think>|$)", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .replace(text, "").trim()
        if (clean.isEmpty()) throw IOException("пустой ответ")
        return clean
    }

    private suspend fun requestWithRetry(p: Provider, prompt: String, maxTokens: Int?, batch: Boolean, images: List<AiImage>?): String {
        var attempt = 0
        while (true) {
            val used = p.reasoning.firstOrNull()
            try {
                return request(p, prompt, maxTokens, batch, images)
            } catch (e: AiHttpError) {
                if (e.status in setOf(400, 422) && p.reasoning.size > 1 &&
                    Regex("reasoning|thinking", RegexOption.IGNORE_CASE).containsMatchIn(e.body)
                ) {
                    synchronized(lock) {
                        if (p.reasoning.size > 1 && p.reasoning.first() == used) {
                            val dropped = p.reasoning.removeAt(0)
                            log("ℹ️ ИИ «${p.title}»: reasoning_effort=$dropped не поддерживается, пробую ${p.reasoning.first()}")
                        }
                    }
                    continue
                }
                attempt++
                if (e.status in setOf(500, 502, 503, 504) && attempt < 3) { delay(600L * attempt); continue }
                throw e
            }
        }
    }

    private suspend fun listModels(p: Provider): List<String> {
        val headers = if (p.key.isNotBlank()) mapOf("Authorization" to "Bearer ${p.key}") else emptyMap()
        val data = httpJson("${p.baseUrl}/models", headers, null, timeoutMs)
        val arr = data.optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("id")?.removePrefix("models/") }
    }

    private suspend fun callProvider(p: Provider, prompt: String, maxTokens: Int?, batch: Boolean, images: List<AiImage>?): String {
        try {
            return requestWithRetry(p, prompt, maxTokens, batch, images)
        } catch (e: AiHttpError) {
            // Gemini перегружена или упёрлась в лимит: пробуем соседние Flash-модели (у каждой свои лимиты).
            if (p.id == "gemini" && e.status in setOf(429, 503) && !p.fallbackTried) {
                p.fallbackTried = true
                val original = p.model
                val alternatives = runCatching { listModels(p) }.getOrDefault(emptyList())
                    .mapNotNull { name ->
                        Regex("gemini-(\\d+(?:\\.\\d+)?)-flash(-lite)?").matchEntire(name)
                            ?.let { Triple(it.groupValues[1].toDouble(), it.groupValues[2].isEmpty(), name) }
                    }
                    .filter { it.third != original }
                    .sortedWith(compareByDescending<Triple<Double, Boolean, String>> { it.first }.thenByDescending { it.second })
                    .take(3).map { it.third }
                for (name in alternatives) {
                    p.model = name
                    val text = runCatching { request(p, prompt, maxTokens, batch, images) }.getOrNull()
                    if (text != null) {
                        log("🔁 Gemini: «$original» недоступна (${e.status}), перехожу на «$name»")
                        p.cooldownUntil = 0
                        return text
                    }
                }
                p.model = original
            }
            throw e
        }
    }

    private fun penalize(p: Provider, e: Throwable, images: Boolean) {
        val status = (e as? AiHttpError)?.status
        if (images && status in setOf(400, 404, 413, 415, 422)) {
            p.noVision = true
            log("⚠️ ИИ «${p.title}» не принял картинку — для вопросов с картинками больше не использую")
            return
        }
        var seconds = 20.0
        var reason = e.message ?: e.javaClass.simpleName
        when {
            status == 402 -> { seconds = 3600.0; reason = "на балансе нет денег — пополни счёт"; p.disabled = true }
            status == 401 || status == 403 -> { seconds = 3600.0; reason = "ключ или модель не приняты (${reason.take(120)})"; p.disabled = true }
            status == 400 || status == 404 -> { seconds = 3600.0; reason = "запрос отклонён, проверь модель/адрес (${reason.take(120)})"; p.disabled = true }
            status == 429 -> { seconds = (e as AiHttpError).retryAfter ?: 60.0; reason = "лимит запросов" }
            status == 503 -> reason = "сервер перегружен (503)"
            reason.contains("timeout", true) -> reason = "нет ответа за ${timeoutMs / 1000} с"
        }
        p.cooldownUntil = System.currentTimeMillis() + (seconds * 1000).toLong()
        log("⚠️ ИИ «${p.title}»: $reason — " + if (p.disabled) "отключён до перезапуска" else "пауза ${seconds.toInt()} с")
    }

    private suspend fun timed(p: Provider, prompt: String, maxTokens: Int?, batch: Boolean, images: List<AiImage>?): String {
        val t0 = System.nanoTime()
        p.inflight.incrementAndGet()
        try {
            val text = callProvider(p, prompt, maxTokens, batch, images)
            if (images.isNullOrEmpty()) {
                val took = (System.nanoTime() - t0) / 1e9
                val l = p.latency
                p.latency = if (l == null) took else 0.6 * l + 0.4 * took
                Provider.LATENCY["${p.id}/${p.model}"] = p.latency!!
            }
            return text
        } finally {
            p.inflight.decrementAndGet()
        }
    }

    // ---------- выбор провайдера и подстраховка ----------

    private fun order(hard: Boolean, onlyExperts: Boolean): List<Provider> {
        val now = System.currentTimeMillis()
        var active = providers.filter { !it.disabled }
        active = when {
            onlyExperts -> active.filter { it.expert }
            hard -> active.filter { it.expert } + active.filter { !it.expert }
            else -> {
                val regular = active.filter { !it.expert }
                if (regular.any { it.cooldownUntil <= now }) regular else regular + active.filter { it.expert }
            }
        }
        val ordered = if (hard || onlyExperts) active else active.sortedBy { it.latency ?: 1e9 }
        return ordered.filter { it.cooldownUntil <= now } + ordered.filter { it.cooldownUntil > now }
    }

    private data class Asked(val text: String, val provider: Provider, val seconds: Double)

    private suspend fun ask(
        prompt: String, maxTokens: Int? = null, batch: Boolean = false, images: List<AiImage>? = null,
        hard: Boolean = false, onlyExperts: Boolean = false,
    ): Asked? {
        var order = order(hard || !images.isNullOrEmpty(), onlyExperts)
        if (!images.isNullOrEmpty()) order = order.filter { !it.noVision }
        if (order.isEmpty()) return null
        val started = System.nanoTime()
        // Проигравшие запросы не отменяем: досчитают в фоне и обновят замер скорости.
        val results = Channel<Pair<Provider, Result<String>>>(Channel.UNLIMITED)
        var next = 0
        var pending = 0
        fun launchNext() {
            val p = order[next++]
            pending++
            bg.launch {
                val r = try { Result.success(timed(p, prompt, maxTokens, batch, images)) }
                catch (e: CancellationException) { throw e }
                catch (e: Throwable) { Result.failure(e) }
                results.trySend(p to r)
            }
        }
        launchNext()
        while (pending > 0) {
            var hedge = hedgeMs * (if (images.isNullOrEmpty()) 1 else 3)
            val current = order[next - 1]
            val now = System.currentTimeMillis()
            if (current.expert && (hard || !images.isNullOrEmpty())) {
                hedge = maxOf(hedge, 6000L)  // ждём именно эксперта
            } else if (images.isNullOrEmpty() &&
                order.drop(next).any { it.latency == null && it.cooldownUntil <= now && it.inflight.get() == 0 }
            ) {
                hedge = 0  // скорость ещё не знаем — спрашиваем всех сразу и заодно меряем
            } else if (current.latency != null && images.isNullOrEmpty()) {
                hedge = minOf(hedge, maxOf(600L, (current.latency!! * 1800).toLong()))
            }
            val got = if (next < order.size) withTimeoutOrNull(hedge) { results.receive() } else results.receive()
            if (got == null) { launchNext(); continue }
            pending--
            val (p, r) = got
            r.onSuccess { return Asked(it, p, (System.nanoTime() - started) / 1e9) }
            penalize(p, r.exceptionOrNull()!!, !images.isNullOrEmpty())
            if (next < order.size) launchNext()
        }
        return null
    }

    // ---------- голосование экспертов ----------

    private fun voters(images: List<AiImage>?, exclude: Collection<Provider> = emptyList()): List<Provider> {
        val now = System.currentTimeMillis()
        return order(hard = true, onlyExperts = false).filter {
            it.cooldownUntil <= now && it !in exclude && !(images != null && images.isNotEmpty() && it.noVision)
        }
    }

    /**
     * Эксперты отвечают параллельно; совпавший ответ принимаем, при расхождении решает
     * третья модель, а если и она не согласна ни с кем — человек. null — голосовать некому.
     */
    private suspend fun <T> voteOn(
        prompt: String, images: List<AiImage>?, question: String, parse: (String) -> Pair<T, String>?,
    ): AiReply<T>? {
        val experts = voters(images).filter { it.expert }
        if (!vote || experts.size < 2) return null
        val started = System.nanoTime()
        fun took() = "%.1f с".format((System.nanoTime() - started) / 1e9)
        val extraWait = if (images.isNullOrEmpty()) 4000L else 6000L
        val hardCap = maxOf(timeoutMs, if (images.isNullOrEmpty()) 15_000L else 30_000L)
        val results = Channel<Pair<Provider, Result<String>>>(Channel.UNLIMITED)
        for (p in experts) bg.launch {
            val r = try { Result.success(timed(p, prompt, null, false, images)) }
            catch (e: CancellationException) { throw e }
            catch (e: Throwable) { Result.failure(e) }
            results.trySend(p to r)
        }
        data class Vote(val p: Provider, val value: T, val key: String)
        val votes = mutableListOf<Vote>()
        var answered = 0
        var pending = experts.size
        var deadline = System.currentTimeMillis() + hardCap
        while (pending > 0) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) break
            val got = withTimeoutOrNull(left) { results.receive() } ?: break
            pending--
            val (p, r) = got
            r.onFailure { penalize(p, it, !images.isNullOrEmpty()) }
            r.onSuccess { raw ->
                answered++
                parse(raw)?.let { votes += Vote(p, it.first, it.second) }
            }
            if (votes.groupBy { it.key }.any { it.value.size >= 2 }) break
            if (answered > 0 && pending > 0) deadline = minOf(deadline, System.currentTimeMillis() + extraWait)
        }

        votes.groupBy { it.key }.values.firstOrNull { it.size >= 2 }?.let { agreed ->
            return AiReply(agreed[0].value, agreed.joinToString("+") { it.p.short } + ", ${took()}, эксперты согласны")
        }
        if (answered == 0) return null
        val asked = experts
        if (votes.isEmpty()) {
            // Эксперты не знают или упали — последний шанс у запасной модели.
            voters(images, asked).firstOrNull()?.let { backup ->
                val parsed = try { parse(timed(backup, prompt, null, false, images)) }
                catch (e: CancellationException) { throw e }
                catch (e: Throwable) { penalize(backup, e, !images.isNullOrEmpty()); null }
                if (parsed != null) return AiReply(parsed.first, "${backup.short}, ${took()}, запасная модель")
            }
            return AiReply(null, "эксперты не знают ответа, ${took()}")
        }
        if (votes.size == 1) return AiReply(votes[0].value, "${votes[0].p.short}, ${took()}, эксперт (второй не ответил)")

        var shown = votes.joinToString("; ") { "${it.p.short}: ${it.value}" }
        log("⚖️ Эксперты разошлись в «${question.take(60)}» ($shown) — спрашиваю третью модель")
        voters(images, asked).firstOrNull()?.let { judge ->
            val parsed = try { parse(timed(judge, prompt, null, false, images)) }
            catch (e: CancellationException) { throw e }
            catch (e: Throwable) { penalize(judge, e, !images.isNullOrEmpty()); null }
            if (parsed != null) {
                votes.firstOrNull { it.key == parsed.second }?.let {
                    return AiReply(it.value, "${it.p.short}+${judge.short}, ${took()}, решила третья модель")
                }
                shown += "; ${judge.short}: ${parsed.first}"
            }
        }
        log("⚖️ Согласия нет ($shown) — вопрос уйдёт человеку")
        return AiReply(null, "разногласие ИИ, ${took()}")
    }

    // ---------- публичные методы ----------

    private fun cleanText(raw: String): String? {
        var line = raw.trim().lines().firstOrNull()?.trim()?.trim(' ', '"', '«', '»', '\'') ?: return null
        if (line.isEmpty() || line.uppercase().trim('.') == "UNKNOWN") return null
        if (line.endsWith(".") && line.count { it == '.' } == 1) line = line.dropLast(1)
        return line.take(300)
    }

    private fun isExpertName(p: Provider) = p.expert

    suspend fun text(question: String, images: List<AiImage>? = null, expert: Boolean = false): AiReply<String> {
        val hard = expert || !images.isNullOrEmpty() || TextRules.isHardQuestion(question)
        val prompt = "Вопрос формы: $question"
        if (hard) {
            voteOn(prompt, images, question) { raw ->
                cleanText(raw)?.takeIf { !TextRules.looksUnsure(it) }?.let { it to TextRules.answerKey(it) }
            }?.let { return it }
        }
        val r = ask(prompt, images = images, hard = hard, onlyExperts = expert) ?: return AiReply(null, "")
        val answer = cleanText(r.text)
        if ((answer == null || TextRules.looksUnsure(answer)) && hasExpert && !hard && !isExpertName(r.provider)) {
            log("🧠 ${r.provider.short} не справился с «${question.take(60)}» (${answer ?: "UNKNOWN"}) — спрашиваю эксперта")
            return text(question, images, expert = true)
        }
        val info = "${r.provider.short}, %.1f с".format(r.seconds) + if (hard && r.provider.expert) ", эксперт" else ""
        return AiReply(answer?.takeIf { !TextRules.looksUnsure(it) }, info)
    }

    private fun parseChoice(raw: String, labels: List<String>): List<String>? {
        if ("UNKNOWN" in raw.uppercase()) return null
        val picked = mutableListOf<String>()
        for (m in Regex("\\d+").findAll(raw)) {
            val n = m.value.toIntOrNull() ?: continue
            if (n in 1..labels.size && labels[n - 1] !in picked) picked += labels[n - 1]
        }
        return picked.ifEmpty { null }
    }

    /** Выбор варианта: для одиночного — список из одного элемента. */
    suspend fun choose(
        question: String, labels: List<String>, multi: Boolean,
        images: List<AiImage>? = null, expert: Boolean = false,
    ): AiReply<List<String>> {
        if (labels.isEmpty()) return AiReply(null, "")
        val hard = expert || !images.isNullOrEmpty() || TextRules.isHardQuestion(question)
        val options = labels.mapIndexed { i, l -> "${i + 1}. $l" }.joinToString("\n")
        val task = if (multi) "Выбери ВСЕ подходящие варианты. Ответь только номерами через запятую или UNKNOWN."
        else "Выбери один вариант. Ответь только его номером или UNKNOWN."
        val prompt = "Вопрос формы: $question\nВарианты:\n$options\n$task"
        fun parse(raw: String): Pair<List<String>, String>? {
            val picked = parseChoice(raw, labels) ?: return null
            val v = if (multi) picked else picked.take(1)
            return v to v.sorted().joinToString("|")
        }
        if (hard) voteOn(prompt, images, question, ::parse)?.let { return it }
        val r = ask(prompt, images = images, hard = hard, onlyExperts = expert) ?: return AiReply(null, "")
        val picked = parse(r.text)?.first
        if (picked == null && hasExpert && !hard && !r.provider.expert) {
            log("🧠 ${r.provider.short} не выбрал вариант в «${question.take(60)}» — спрашиваю эксперта")
            return choose(question, labels, multi, images, expert = true)
        }
        val info = "${r.provider.short}, %.1f с".format(r.seconds) + if (hard && r.provider.expert) ", эксперт" else ""
        return AiReply(picked, info)
    }

    data class BatchItem(val question: String, val labels: List<String>, val multi: Boolean, val isText: Boolean)

    /**
     * Один запрос на все простые вопросы. Элемент ответа: строка/список,
     * "" — ИИ не знает, null — пропущен в ответе. Целиком null — сбой.
     */
    suspend fun batch(items: List<BatchItem>): AiReply<List<Any?>> {
        val lines = items.mapIndexed { i, it ->
            if (it.isText) "${i + 1}. [текст] ${it.question}"
            else "${i + 1}. [${if (it.multi) "выбор нескольких" else "выбор одного"}] ${it.question} Варианты: " +
                it.labels.mapIndexed { k, l -> "${k + 1}) $l" }.joinToString("; ")
        }
        val prompt = "Вопросы формы:\n" + lines.joinToString("\n") + "\nВерни только JSON-объект вида {\"1\": ..., \"2\": ...}."
        val r = ask(prompt, maxTokens = maxOf(600, 150 * items.size), batch = true) ?: return AiReply(null, "")
        val info = "${r.provider.short}, %.1f с, пакетом".format(r.seconds)
        val raw = r.text.replace(Regex("```(?:json)?"), "")
        val start = raw.indexOf('{'); val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return AiReply(null, info)
        val data = runCatching { JSONObject(raw.substring(start, end + 1)) }.getOrNull() ?: return AiReply(null, info)
        val out = items.mapIndexed { i, it ->
            val v = data.opt((i + 1).toString())
            when {
                v == null || v == JSONObject.NULL -> null
                v is String && v.trim().trim('.').equals("UNKNOWN", true) -> ""
                it.isText -> cleanText(v.toString()) ?: ""
                else -> {
                    val nums = if (v is JSONArray) (0 until v.length()).map { k -> v.opt(k) } else listOf(v)
                    val picked = nums.mapNotNull { n ->
                        val idx = n.toString().trim().toIntOrNull()
                        if (idx != null && idx in 1..it.labels.size) it.labels[idx - 1]
                        else TextRules.matchLabelIndex(it.labels, n.toString()).takeIf { k -> k >= 0 }?.let { k -> it.labels[k] }
                    }.distinct()
                    if (picked.isEmpty()) null else if (it.multi) picked else picked.take(1)
                }
            }
        }
        return AiReply(out, info)
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
