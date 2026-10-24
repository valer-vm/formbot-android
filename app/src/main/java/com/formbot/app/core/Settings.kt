package com.formbot.app.core

import org.json.JSONArray
import org.json.JSONObject

/** Тип API провайдера. */
enum class ApiType { ANTHROPIC, OPENAI }

data class ProviderConfig(
    val id: String,
    val title: String,
    val type: ApiType,
    val enabled: Boolean,
    val model: String,
    val baseUrl: String,
    val apiKey: String = "",
    val expert: Boolean = false,
    val maxTokens: Int = 400,
    /** Значения reasoning_effort по очереди: первое неподдерживаемое отбрасывается. */
    val reasoning: List<String?> = listOf(null),
)

/** Откуда брать посты с растениями. apiHash хранится зашифрованным, как ключи ИИ. */
data class TelegramConfig(
    val apiId: String = "",
    val apiHash: String = "",
    val chatId: Long = 0,
    val chatTitle: String = "",
    /** message_thread_id темы; 0 — в чате нет тем. */
    val threadId: Long = 0,
    val topicTitle: String = "",
    val watch: Boolean = false,
    /** Необязательный прокси (MTProto/SOCKS5/HTTP), если серверы Telegram недоступны напрямую. */
    val proxy: String = "",
) {
    val hasKeys get() = apiId.toIntOrNull() != null && apiHash.length >= 16
    val hasSource get() = chatId != 0L

    fun toJson(): JSONObject = JSONObject().apply {
        put("api_id", apiId); put("chat_id", chatId); put("chat_title", chatTitle)
        put("thread_id", threadId); put("topic_title", topicTitle); put("watch", watch)
        put("proxy", proxy)
    }

    fun withJson(o: JSONObject?): TelegramConfig = if (o == null) this else copy(
        apiId = o.optString("api_id", apiId), chatId = o.optLong("chat_id", chatId),
        chatTitle = o.optString("chat_title", chatTitle), threadId = o.optLong("thread_id", threadId),
        topicTitle = o.optString("topic_title", topicTitle), watch = o.optBoolean("watch", watch),
        proxy = o.optString("proxy", proxy),
    )
}

data class Settings(
    val formUrl: String = "",
    val plant1: String = "",
    val plant2: String = "",
    val nickname: String = "",
    val column: String = "",
    val phone: String = "",
    val note: String = "",
    val email: String = "",
    val checkInterval: Double = 2.0,
    val retryDelay: Double = 2.0,
    val delayBetweenPlants: Double = 0.0,
    val maxAttempts: Int = 30,
    val useAi: Boolean = true,
    /** Не отправлять самому: открыть форму с проставленными ответами, «Отправить» жмёт пользователь. */
    val manualSubmit: Boolean = false,
    val aiOptional: Boolean = false,
    val aiVote: Boolean = true,
    val aiTimeout: Double = 15.0,
    val aiHedgeDelay: Double = 2.0,
    val providers: List<ProviderConfig> = PRESETS,
    val extraAnswers: List<Pair<String, String>> = emptyList(),
    /** system / light / dark */
    val theme: String = "system",
    val telegram: TelegramConfig = TelegramConfig(),
) {
    val plants: List<String> get() = listOf(plant1, plant2).map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        val PRESETS = listOf(
            ProviderConfig(
                "anthropic", "Claude (Anthropic)", ApiType.ANTHROPIC, enabled = false,
                model = "claude-haiku-5-5", baseUrl = "https://api.anthropic.com",
                expert = true, maxTokens = 300,
            ),
            ProviderConfig(
                "gemini", "Google Gemini", ApiType.OPENAI, enabled = true,
                model = "gemini-3.6-flash",
                baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
                expert = true, reasoning = listOf("low", null),
            ),
            ProviderConfig(
                "groq", "Groq", ApiType.OPENAI, enabled = true,
                model = "qwen/qwen3.8-27b", baseUrl = "https://api.groq.com/openai/v1",
                reasoning = listOf("none", null),
            ),
            ProviderConfig(
                "deepseek", "DeepSeek", ApiType.OPENAI, enabled = false,
                model = "deepseek-flash", baseUrl = "https://api.deepseek.com", expert = true,
            ),
            ProviderConfig(
                "custom", "Свой (OpenAI-совместимый)", ApiType.OPENAI, enabled = false,
                model = "", baseUrl = "",
            ),
        )

        /** Импорт form_bot_config.json из настольной версии (вместе с ключами). */
        fun fromDesktopJson(text: String, base: Settings = Settings()): Settings {
            val o = JSONObject(text)
            fun str(k: String, d: String) = if (o.has(k) && !o.isNull(k)) o.optString(k, d) else d
            fun dbl(k: String, d: Double) = o.optDouble(k, d).takeIf { !it.isNaN() } ?: d
            val ov = o.optJSONObject("ai_providers") ?: JSONObject()
            val providers = base.providers.map { p ->
                val x = ov.optJSONObject(p.id) ?: return@map p
                p.copy(
                    enabled = x.optBoolean("enabled", p.enabled),
                    model = x.optString("model", p.model).ifBlank { p.model },
                    baseUrl = x.optString("base_url", p.baseUrl).ifBlank { p.baseUrl },
                    apiKey = x.optString("api_key", p.apiKey),
                    expert = x.optBoolean("expert", p.expert),
                    reasoning = x.optJSONArray("reasoning")?.let { arr ->
                        (0 until arr.length()).map { if (arr.isNull(it)) null else arr.getString(it) }
                    } ?: p.reasoning,
                )
            }
            val extra = o.optJSONObject("extra_answers")?.let { e ->
                e.keys().asSequence().map { it to e.optString(it) }.toList()
            } ?: base.extraAnswers
            return base.copy(
                formUrl = str("form_url", base.formUrl),
                plant1 = str("plant1", base.plant1),
                plant2 = str("plant2", base.plant2),
                nickname = str("nickname", base.nickname),
                column = str("column", base.column),
                phone = str("phone", base.phone),
                note = str("note", base.note),
                checkInterval = dbl("check_interval", base.checkInterval),
                retryDelay = dbl("retry_delay", base.retryDelay),
                delayBetweenPlants = dbl("delay_between_plants", base.delayBetweenPlants),
                maxAttempts = o.optInt("max_attempts", base.maxAttempts),
                useAi = o.optBoolean("use_ai", base.useAi),
                aiOptional = o.optBoolean("ai_optional", base.aiOptional),
                aiVote = o.optBoolean("ai_vote", base.aiVote),
                aiTimeout = dbl("ai_timeout", base.aiTimeout),
                aiHedgeDelay = dbl("ai_hedge_delay", base.aiHedgeDelay),
                providers = providers,
                extraAnswers = extra,
            )
        }
    }

    /** Сериализация без ключей (ключи хранятся отдельно, в зашифрованном виде). */
    fun toJson(): JSONObject = JSONObject().apply {
        put("form_url", formUrl); put("plant1", plant1); put("plant2", plant2)
        put("nickname", nickname); put("column", column); put("phone", phone)
        put("note", note); put("email", email)
        put("check_interval", checkInterval); put("retry_delay", retryDelay)
        put("delay_between_plants", delayBetweenPlants); put("max_attempts", maxAttempts)
        put("manual_submit", manualSubmit)
        put("use_ai", useAi); put("ai_optional", aiOptional); put("ai_vote", aiVote)
        put("ai_timeout", aiTimeout); put("ai_hedge_delay", aiHedgeDelay)
        put("providers", JSONObject().apply {
            for (p in providers) put(p.id, JSONObject().apply {
                put("enabled", p.enabled); put("model", p.model); put("base_url", p.baseUrl)
                put("expert", p.expert)
                put("reasoning", JSONArray().apply { p.reasoning.forEach { put(it ?: JSONObject.NULL) } })
            })
        })
        put("extra_answers", JSONArray().apply {
            extraAnswers.forEach { (f, a) -> put(JSONArray().put(f).put(a)) }
        })
        put("theme", theme)
        put("telegram", telegram.toJson())
    }

    fun withJson(o: JSONObject): Settings {
        val provs = o.optJSONObject("providers") ?: JSONObject()
        return copy(
            formUrl = o.optString("form_url", formUrl),
            plant1 = o.optString("plant1", plant1), plant2 = o.optString("plant2", plant2),
            nickname = o.optString("nickname", nickname), column = o.optString("column", column),
            phone = o.optString("phone", phone), note = o.optString("note", note),
            email = o.optString("email", email),
            checkInterval = o.optDouble("check_interval", checkInterval),
            retryDelay = o.optDouble("retry_delay", retryDelay),
            delayBetweenPlants = o.optDouble("delay_between_plants", delayBetweenPlants),
            maxAttempts = o.optInt("max_attempts", maxAttempts),
            manualSubmit = o.optBoolean("manual_submit", manualSubmit),
            useAi = o.optBoolean("use_ai", useAi), aiOptional = o.optBoolean("ai_optional", aiOptional),
            aiVote = o.optBoolean("ai_vote", aiVote),
            aiTimeout = o.optDouble("ai_timeout", aiTimeout),
            aiHedgeDelay = o.optDouble("ai_hedge_delay", aiHedgeDelay),
            providers = providers.map { p ->
                val x = provs.optJSONObject(p.id) ?: return@map p
                p.copy(
                    enabled = x.optBoolean("enabled", p.enabled),
                    model = x.optString("model", p.model),
                    baseUrl = x.optString("base_url", p.baseUrl),
                    expert = x.optBoolean("expert", p.expert),
                    reasoning = x.optJSONArray("reasoning")?.let { arr ->
                        (0 until arr.length()).map { if (arr.isNull(it)) null else arr.getString(it) }
                    } ?: p.reasoning,
                )
            },
            extraAnswers = o.optJSONArray("extra_answers")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONArray(i)?.let { it.optString(0) to it.optString(1) }
                }
            } ?: extraAnswers,
            theme = o.optString("theme", theme),
            telegram = telegram.withJson(o.optJSONObject("telegram")),
        )
    }
}
