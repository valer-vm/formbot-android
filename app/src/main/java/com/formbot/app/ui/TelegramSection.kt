package com.formbot.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import android.content.Intent
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.formbot.app.core.Settings
import com.formbot.app.service.TgAuth
import com.formbot.app.service.TgChat

/** Вход в Telegram под своим аккаунтом и выбор чата/темы с постами питомника. */
@Composable
fun TelegramSection(vm: AppViewModel, s: Settings) {
    val auth by vm.tgAuth.collectAsStateWithLifecycle()
    val error by vm.tgError.collectAsStateWithLifecycle()
    val busy by vm.tgBusy.collectAsStateWithLifecycle()
    val connection by vm.tgConnection.collectAsStateWithLifecycle()
    val tg = s.telegram
    fun setTg(f: (com.formbot.app.core.TelegramConfig) -> com.formbot.app.core.TelegramConfig) =
        vm.update { it.copy(telegram = f(it.telegram)) }

    Section("Telegram", "Откуда брать растения дня. Вход — под вашим аккаунтом, сессия хранится только на телефоне") {
        when (val a = auth) {
            TgAuth.NoKeys, TgAuth.LoggedOut -> {
                Text(
                    "Ключи берутся на my.telegram.org → API development tools.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Field("api_id", tg.apiId, { v -> setTg { it.copy(apiId = v.filter(Char::isDigit)) } }, keyboard = KeyboardType.Number)
                Field("api_hash", tg.apiHash, { v -> setTg { it.copy(apiHash = v.trim()) } }, secret = true)
                Button({ vm.connectTelegram() }, Modifier.fillMaxWidth(), enabled = tg.hasKeys) { Text("Подключить") }
            }
            TgAuth.Starting -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp)); Text("Подключаюсь к Telegram…")
            }
            TgAuth.WaitPhone -> {
                var phone by remember { mutableStateOf("+7") }
                Field("Номер телефона", phone, { phone = it }, keyboard = KeyboardType.Phone)
                Text("Номер в международном формате: +7…", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                BusyButton("Получить код", busy, enabled = phone.count(Char::isDigit) >= 10) { vm.sendPhone(phone) }
            }
            is TgAuth.WaitCode -> {
                var code by remember { mutableStateOf("") }
                Text("Код отправлен в Telegram на ${a.phone} (чат «Telegram»)", style = MaterialTheme.typography.bodyMedium)
                Field("Код", code, { code = it.filter(Char::isDigit) }, keyboard = KeyboardType.Number)
                BusyButton("Войти", busy, enabled = code.length >= 5) { vm.sendCode(code) }
            }
            is TgAuth.WaitPassword -> {
                var pass by remember { mutableStateOf("") }
                Text("Включена двухэтапная проверка — введите облачный пароль" +
                    (if (a.hint.isNotBlank()) " (подсказка: ${a.hint})" else ""), style = MaterialTheme.typography.bodyMedium)
                Field("Облачный пароль", pass, { pass = it }, secret = true)
                BusyButton("Войти", busy, enabled = pass.isNotEmpty()) { vm.sendPassword(pass) }
            }
            is TgAuth.Unsupported -> Text(a.what, color = MaterialTheme.colorScheme.error)
            TgAuth.Ready -> {
                Text("✅ Вход выполнен", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge)
                if (tg.hasSource) {
                    Text("Чат: ${tg.chatTitle}" + if (tg.topicTitle.isNotBlank()) "\nТема: ${tg.topicTitle}" else "",
                        style = MaterialTheme.typography.bodyMedium)
                }
                FilledTonalButton(vm::loadChats, Modifier.fillMaxWidth(), enabled = !busy) {
                    if (busy) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                    Text(if (tg.hasSource) "Выбрать другой чат" else "Выбрать чат")
                }
                TextButton(vm::logOutTelegram, Modifier.fillMaxWidth()) { Text("Выйти из Telegram") }
            }
        }
        if (auth != TgAuth.NoKeys && auth != TgAuth.LoggedOut) connection?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        error?.let { Text("❌ $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        if (auth != TgAuth.Ready) TextButton(vm::diagnose, Modifier.fillMaxWidth()) { Text("Диагностика соединения") }

        // Прокси — если Telegram недоступен напрямую. Применяется кнопкой, чтобы не переподключаться на каждую букву.
        var proxy by remember(tg.proxy) { mutableStateOf(tg.proxy) }
        Field("Прокси (необязательно)", proxy, { proxy = it.trim() }, placeholder = "tg://proxy?server=… или socks5://…",
            keyboard = KeyboardType.Uri)
        if (proxy != tg.proxy) TextButton({
            setTg { it.copy(proxy = proxy) }
            vm.connectTelegram(proxy)
        }) { Text(if (proxy.isBlank()) "Убрать прокси" else "Применить прокси") }
    }

    ChatPicker(vm)
    DiagnosticsDialog(vm)
}

@Composable
private fun DiagnosticsDialog(vm: AppViewModel) {
    val report by vm.diag.collectAsStateWithLifecycle()
    val text = report ?: return
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = vm::closeDiag,
        title = { Text("Диагностика") },
        text = {
            if (text.isEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Проверяю сеть и серверы Telegram — до минуты…")
            } else Text(
                text, Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
            )
        },
        confirmButton = {
            if (text.isNotEmpty()) TextButton({
                ctx.startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Отправить отчёт",
                ))
            }) { Text("Поделиться") }
        },
        dismissButton = { TextButton(vm::closeDiag) { Text("Закрыть") } },
    )
}

@Composable
private fun BusyButton(text: String, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick, Modifier.fillMaxWidth(), enabled = enabled && !busy) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("Жду ответ Telegram…")
        } else Text(text)
    }
}

@Composable
private fun ChatPicker(vm: AppViewModel) {
    val chats by vm.chats.collectAsStateWithLifecycle()
    val topics by vm.topics.collectAsStateWithLifecycle()
    val list = chats ?: return
    var query by remember { mutableStateOf("") }

    val pickingTopic = topics
    AlertDialog(
        onDismissRequest = vm::closeChats,
        title = { Text(if (pickingTopic != null) "Тема в «${pickingTopic.first.title}»" else "Чат с растениями") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (pickingTopic == null) Field("Поиск", query, { query = it })
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    if (pickingTopic != null) {
                        items(pickingTopic.second) { t ->
                            PickRow(t.name, Icons.Outlined.Forum) { vm.pickTopic(pickingTopic.first, t) }
                        }
                    } else {
                        val shown = list.filter { query.isBlank() || query.trim().lowercase() in it.title.lowercase() }
                        items(shown) { c: TgChat ->
                            PickRow(c.title + if (c.isForum) "  · темы" else "", Icons.Outlined.Groups) { vm.pickChat(c) }
                        }
                        if (shown.isEmpty()) item { Text("Ничего не найдено", color = MaterialTheme.colorScheme.outline) }
                    }
                }
            }
        },
        confirmButton = { TextButton(vm::closeChats) { Text("Отмена") } },
    )
}

@Composable
private fun PickRow(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge)
    }
}
