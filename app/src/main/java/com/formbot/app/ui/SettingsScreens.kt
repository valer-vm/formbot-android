package com.formbot.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.formbot.app.core.ProviderConfig
import com.formbot.app.core.Settings

// ---------- общие элементы ----------

@Composable
fun Section(title: String, hint: String? = null, content: @Composable ColumnScope.() -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
fun Field(
    label: String, value: String, onChange: (String) -> Unit,
    placeholder: String? = null, keyboard: KeyboardType = KeyboardType.Text,
    trailing: @Composable (() -> Unit)? = null, secret: Boolean = false,
) {
    var visible by remember { mutableStateOf(false) }
    val icon: (@Composable () -> Unit)? = if (secret) {
        {
            IconButton({ visible = !visible }) {
                Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, "Показать ключ")
            }
        }
    } else trailing
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard),
        visualTransformation = if (secret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = icon,
    )
}

/** Числовое поле: хранит введённый текст, в настройки пишет только корректное число. */
@Composable
fun NumberField(label: String, value: Double, onChange: (Double) -> Unit, integer: Boolean = false) {
    fun fmt(v: Double) = if (integer) v.toInt().toString() else v.toString().removeSuffix(".0")
    var text by remember { mutableStateOf(fmt(value)) }
    // Значение изменилось снаружи (например, импорт) — показываем новое.
    LaunchedEffect(value) { if (text.replace(',', '.').toDoubleOrNull() != value) text = fmt(value) }
    Field(label, text, { t ->
        text = t
        t.replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 }?.let(onChange)
    }, keyboard = if (integer) KeyboardType.Number else KeyboardType.Decimal)
}

@Composable
fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}

// ---------- «Форма» ----------

@Composable
fun FormScreen(vm: AppViewModel, s: Settings, modifier: Modifier = Modifier) {
    val update = vm::update
    val onImport: (Uri) -> Unit = { vm.importDesktopConfig(it) }
    val clipboard = LocalClipboardManager.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(onImport) }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Section("Google-форма") {
            Field("Ссылка на форму", s.formUrl, { v -> update { it.copy(formUrl = v.trim()) } },
                placeholder = "https://docs.google.com/forms/…",
                keyboard = KeyboardType.Uri,
                trailing = {
                    IconButton({ clipboard.getText()?.text?.let { v -> update { it.copy(formUrl = v.trim()) } } }) {
                        Icon(Icons.Outlined.ContentPaste, "Вставить")
                    }
                })
            SwitchRow(
                "Отправлять вручную",
                "Бот заполнит ответы и откроет форму в браузере — «Отправить» нажмёте сами",
                s.manualSubmit,
            ) { v -> update { it.copy(manualSubmit = v) } }
        }
        TelegramSection(vm, s)
        Section("Растения вручную", "Запасной вариант, если поста нет: «Запустить вручную» на вкладке «Запуск». Для каждого растения — отдельная заявка") {
            Field("Растение 1", s.plant1, { v -> update { it.copy(plant1 = v) } })
            Field("Растение 2", s.plant2, { v -> update { it.copy(plant2 = v) } }, placeholder = "необязательно")
        }
        Section("Ваши данные", "Подставляются в вопросы с такими словами") {
            Field("Ник", s.nickname, { v -> update { it.copy(nickname = v) } })
            Field("Столбик", s.column, { v -> update { it.copy(column = v) } }, keyboard = KeyboardType.Number)
            Field("Телефон", s.phone, { v -> update { it.copy(phone = v) } }, placeholder = "можно не заполнять", keyboard = KeyboardType.Phone)
            Field("Примечание", s.note, { v -> update { it.copy(note = v) } }, placeholder = "можно не заполнять")
            Field("E-mail", s.email, { v -> update { it.copy(email = v.trim()) } }, placeholder = "если форма собирает почту", keyboard = KeyboardType.Email)
        }
        Section("Ожидание и повторы") {
            NumberField("Проверять, открылась ли форма, каждые … с", s.checkInterval, { v -> update { it.copy(checkInterval = v) } })
            NumberField("Пауза перед повторной отправкой, с", s.retryDelay, { v -> update { it.copy(retryDelay = v) } })
            NumberField("Пауза между растениями, с", s.delayBetweenPlants, { v -> update { it.copy(delayBetweenPlants = v) } })
            NumberField("Максимум попыток", s.maxAttempts.toDouble(), { v -> update { it.copy(maxAttempts = v.toInt().coerceAtLeast(1)) } }, integer = true)
        }
        Section("Оформление") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { i, mode ->
                    SegmentedButton(
                        selected = ThemeMode.of(s.theme) == mode,
                        onClick = { update { it.copy(theme = mode.id) } },
                        shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                    ) { Text(mode.title) }
                }
            }
        }
        Section("Перенос с компьютера", "Выберите form_bot_config.json из папки программы на Mac — подтянутся ссылка, данные, ответы и ключи ИИ") {
            FilledTonalButton({ picker.launch(arrayOf("application/json", "text/plain", "*/*")) }, Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.FileOpen, null); Spacer(Modifier.width(8.dp)); Text("Импортировать настройки")
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ---------- «ИИ» ----------

@Composable
fun AiScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val checks by vm.checks.collectAsStateWithLifecycle()
    val checking by vm.checking.collectAsStateWithLifecycle()
    fun setProvider(p: ProviderConfig) = vm.update { st -> st.copy(providers = st.providers.map { if (it.id == p.id) p else it }) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Section("Как отвечать") {
            SwitchRow("Отвечать с помощью ИИ", "Иначе неизвестные вопросы задаются вам", s.useAi) { v -> vm.update { it.copy(useAi = v) } }
            SwitchRow(
                "Голосование экспертов",
                "Сложные вопросы и картинки получают все эксперты сразу; ответ принимается, если двое согласны",
                s.aiVote,
            ) { v -> vm.update { it.copy(aiVote = v) } }
            SwitchRow("Отвечать и на необязательные", null, s.aiOptional) { v -> vm.update { it.copy(aiOptional = v) } }
        }

        OutlinedButton({ vm.checkProviders() }, Modifier.fillMaxWidth(), enabled = !checking) {
            if (checking) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
            Text(if (checking) "Проверяю…" else "Проверить провайдеров")
        }

        for (p in s.providers) ProviderCard(p, checks[p.id], ::setProvider)

        Text(
            "Простые вопросы решает самая быстрая модель (без отметки «эксперт»), сложные и картинки — эксперты. " +
                "Ключи хранятся на телефоне в зашифрованном виде.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ProviderCard(p: ProviderConfig, check: Pair<Boolean?, String>?, onChange: (ProviderConfig) -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (p.expert) {
                            Spacer(Modifier.width(8.dp))
                            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                                Text("эксперт", Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    val sub = when {
                        !p.enabled -> "выключен"
                        p.apiKey.isBlank() && p.id != "custom" -> "нет ключа"
                        else -> p.model
                    }
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(p.enabled, { onChange(p.copy(enabled = it)) })
            }
            check?.let { (ok, msg) ->
                val color = when (ok) {
                    true -> MaterialTheme.colorScheme.primary
                    false -> MaterialTheme.colorScheme.error
                    null -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text((if (ok == true) "✅ " else if (ok == false) "❌ " else "") + msg, color = color, style = MaterialTheme.typography.bodySmall)
            }
            if (p.enabled) {
                HorizontalDivider()
                Field("API-ключ", p.apiKey, { onChange(p.copy(apiKey = it.trim())) }, secret = true)
                Field("Модель", p.model, { onChange(p.copy(model = it.trim())) })
                Field("Адрес API", p.baseUrl, { onChange(p.copy(baseUrl = it.trim())) }, keyboard = KeyboardType.Uri)
                SwitchRow("Эксперт", "Сложные вопросы, картинки и то, с чем не справились быстрые модели", p.expert) {
                    onChange(p.copy(expert = it))
                }
            }
        }
    }
}

// ---------- «Ответы» ----------

@Composable
fun AnswersScreen(s: Settings, update: ((Settings) -> Settings) -> Unit, modifier: Modifier = Modifier) {
    var adding by remember { mutableStateOf(false) }
    Box(modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text(
                    "Готовые ответы на повторяющиеся вопросы — мгновенно и без ИИ. Если вопрос содержит фрагмент, " +
                        "подставляется ответ. Для регулярного выражения начните фрагмент с re:",
                    Modifier.padding(top = 16.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            itemsIndexed(s.extraAnswers) { i, (fragment, answer) ->
                GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), padding = 0.dp) {
                    Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("«$fragment»", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("→ $answer", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        }
                        IconButton({ update { st -> st.copy(extraAnswers = st.extraAnswers.filterIndexed { k, _ -> k != i }) } }) {
                            Icon(Icons.Outlined.Delete, "Удалить")
                        }
                    }
                }
            }
            if (s.extraAnswers.isEmpty()) item {
                Text("Пока пусто. Например: «город» → «Москва».", Modifier.padding(vertical = 24.dp),
                    color = MaterialTheme.colorScheme.outline)
            }
            item { Spacer(Modifier.height(88.dp)) }
        }
        ExtendedFloatingActionButton(
            onClick = { adding = true },
            icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Добавить") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
    if (adding) {
        var fragment by remember { mutableStateOf("") }
        var answer by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Новый ответ") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("Фрагмент вопроса", fragment, { fragment = it }, placeholder = "город доставки")
                    Field("Ответ", answer, { answer = it }, placeholder = "Москва")
                }
            },
            confirmButton = {
                TextButton({
                    update { it.copy(extraAnswers = it.extraAnswers + (fragment.trim() to answer)) }
                    adding = false
                }, enabled = fragment.isNotBlank()) { Text("Добавить") }
            },
            dismissButton = { TextButton({ adding = false }) { Text("Отмена") } },
        )
    }
}
