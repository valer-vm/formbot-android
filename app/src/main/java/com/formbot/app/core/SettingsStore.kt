package com.formbot.app.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Настройки — в SharedPreferences, API-ключи — зашифрованы ключом из Android Keystore.
 *
 * Хранилище ключей сразу после обновления приложения или на некоторых прошивках (Xiaomi) иногда
 * на мгновение недоступно. Поэтому: ключ шифрования создаётся заново только если зашифрованных
 * значений ещё нет, а значения, которые не удалось расшифровать, никогда не стираются — их можно
 * перечитать позже ([repair]).
 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("formbot", Context.MODE_PRIVATE)

    /** Имена записей, которые есть на диске, но не расшифровались. */
    private val unreadable = mutableSetOf<String>()
    @Volatile var lastError: String? = null; private set

    fun load(): Settings {
        val base = Settings()
        val withPrefs = prefs.getString("settings", null)
            ?.let { runCatching { base.withJson(JSONObject(it)) }.getOrNull() } ?: base
        return withSecrets(withPrefs)
    }

    /** Подставляет расшифрованные ключи; пустые поля в [s] заполняются тем, что удалось прочитать. */
    @Synchronized
    private fun withSecrets(s: Settings): Settings = s.copy(
        telegram = s.telegram.copy(apiHash = s.telegram.apiHash.ifBlank { read(TG_HASH) }),
        providers = s.providers.map { p -> p.copy(apiKey = p.apiKey.ifBlank { read("key_${p.id}") }) },
    )

    private fun read(name: String): String {
        val stored = prefs.getString(name, null) ?: return ""
        val plain = decrypt(stored)
        if (plain == null) unreadable += name else unreadable -= name
        return plain.orEmpty()
    }

    /** Есть ли ключи, которые сохранены, но пока не расшифровались. */
    val hasUnreadable: Boolean @Synchronized get() = unreadable.isNotEmpty()

    /** Повторная попытка расшифровать то, что не прочиталось раньше. */
    @Synchronized
    fun repair(s: Settings): Settings = if (unreadable.isEmpty()) s else withSecrets(s)

    @Synchronized
    fun save(s: Settings) {
        val e = prefs.edit().putString("settings", s.toJson().toString())
        fun secret(name: String, value: String) {
            when {
                // Хранилище не ответило — оставляем прежнюю запись, а не роняем приложение.
                value.isNotBlank() -> runCatching { encrypt(value) }
                    .onSuccess { e.putString(name, it); unreadable -= name }
                    .onFailure { lastError = "${it.javaClass.simpleName}: ${it.message}" }
                name in unreadable -> {}  // не прочиталось — не значит «пусто»: не стираем
                else -> e.remove(name)
            }
        }
        for (p in s.providers) secret("key_${p.id}", p.apiKey)
        secret(TG_HASH, s.telegram.apiHash)
        e.apply()
    }

    @Synchronized
    private fun secretKey(): SecretKey {
        cachedKey?.let { return it }
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { cachedKey = it.secretKey; return it.secretKey }
        // Ключа «нет», а зашифрованные значения есть — скорее всего, хранилище не ответило.
        // Новый ключ сделал бы старые значения нечитаемыми навсегда, поэтому не создаём его.
        if (ks.containsAlias(ALIAS) || prefs.all.keys.any { it.startsWith("key_") || it == TG_HASH }) {
            throw IllegalStateException("хранилище ключей Android не отдало ключ шифрования")
        }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey().also { cachedKey = it }
    }

    private var cachedKey: SecretKey? = null

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secretKey())
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String? = runCatching {
        val data = Base64.decode(encoded, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, data, 0, 12))
        String(c.doFinal(data, 12, data.size - 12), Charsets.UTF_8)
    }.onFailure { lastError = "${it.javaClass.simpleName}: ${it.message}" }.getOrNull()

    private companion object {
        const val ALIAS = "formbot_keys"
        const val TG_HASH = "tg_hash"
    }
}

/**
 * Настройки на весь процесс: их меняют и экран, и фоновое слежение (например, ссылку на форму из поста),
 * поэтому источник один, а запись на диск — сразу после изменения.
 */
object AppSettings {
    private lateinit var store: SettingsStore
    private val _flow = kotlinx.coroutines.flow.MutableStateFlow(Settings())
    val flow: kotlinx.coroutines.flow.StateFlow<Settings> get() = _flow

    /**
     * Первый вызов читает настройки; повторные (открыли приложение, сработал будильник) перечитывают
     * ключи, которые раньше не расшифровались, — например, если процесс поднялся сразу после обновления.
     */
    @Synchronized
    fun init(context: Context) {
        if (::store.isInitialized) { repair(); return }
        store = SettingsStore(context.applicationContext)
        _flow.value = store.load()
    }

    @Synchronized
    fun repair() {
        if (!::store.isInitialized || !store.hasUnreadable) return
        _flow.value = store.repair(_flow.value)
    }

    /** Почему ключи не расшифровались (для журнала); null — всё прочиталось. */
    val keyProblem: String? get() = if (::store.isInitialized && store.hasUnreadable) store.lastError ?: "неизвестная ошибка" else null

    val value: Settings get() = _flow.value

    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val dirty = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Меняет сразу в памяти; на диск (с шифрованием ключей) — в фоне, частые правки схлопываются. */
    @Synchronized
    fun update(f: (Settings) -> Settings): Settings {
        val s = f(_flow.value)
        _flow.value = s
        if (dirty.compareAndSet(false, true)) writer.execute {
            Thread.sleep(300)
            dirty.set(false)
            store.save(_flow.value)
        }
        return s
    }
}
