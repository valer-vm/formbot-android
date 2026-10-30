package com.formbot.app.ui

import android.graphics.BitmapFactory
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Switch
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.formbot.app.core.PlantPost
import com.formbot.app.service.PlantRuntime
import com.formbot.app.service.WatchPhase
import com.formbot.app.service.WatchState
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.formbot.app.core.Answer
import com.formbot.app.core.HumanRequest
import com.formbot.app.core.QKind
import com.formbot.app.core.Settings
import com.formbot.app.service.BotRuntime
import com.formbot.app.service.Phase
import com.formbot.app.service.RunState
import java.time.LocalDate

@Composable
fun RunScreen(
    vm: AppViewModel,
    s: Settings,
    run: RunState,
    onOpenForm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val watch by vm.watch.collectAsStateWithLifecycle()
    val running = run.phase == Phase.RUNNING || run.phase == Phase.WAITING_HUMAN
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TodayCard(vm, s, watch, running, onOpenForm)
        StatusCard(s, run, running)
        PreparedCard(run)

        if (s.formUrl.isBlank()) {
            GlassCard(Modifier.fillMaxWidth().clickable(onClick = onOpenForm)) {
                Text("Сначала вставьте ссылку на форму во вкладке «Форма» →", style = MaterialTheme.typography.bodyMedium)
            }
        }

        // Ручной запуск — по полям «Растение 1/2»; запасной вариант, если поста нет.
        Button(
            onClick = { if (running) vm.stop() else vm.start() },
            enabled = running || s.formUrl.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(18.dp),
            colors = if (running) ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError,
            ) else ButtonDefaults.filledTonalButtonColors(),
        ) {
            Icon(if (running) Icons.Filled.Stop else Icons.Filled.PlayArrow, null)
            Spacer(Modifier.width(8.dp))
            Text(
                if (running) "Остановить" else "Запустить вручную",
                fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            )
        }

        LogCard(run, Modifier.height(340.dp))
        Spacer(Modifier.height(8.dp))
    }
}

/** «Сегодня в продаже»: статус ожидания, позиции из поста с галочками (до двух), «Заполнить». */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TodayCard(vm: AppViewModel, s: Settings, w: WatchState, running: Boolean, onOpenForm: () -> Unit) {
    val ctx = LocalContext.current
    val tg = s.telegram
    GlassCard(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Сегодня в продаже", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                val sub = when {
                    !tg.hasKeys || !tg.hasSource -> "Подключите Telegram во вкладке «Форма»"
                    w.offer != null -> "Отметьте до ${PlantRuntime.MAX_SELECTED} растений — в порядке отправки"
                    w.status.isNotBlank() -> w.status
                    else -> listOf(tg.chatTitle, tg.topicTitle).filter { it.isNotBlank() }.joinToString(" · ")
                }
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (tg.hasSource) {
                Switch(tg.watch, { on ->
                    vm.setWatch(on)
                    if (on) askBackgroundPermissions(ctx)
                })
            } else {
                TextButton(onOpenForm) { Text("Настроить") }
            }
        }
        if (tg.hasSource) Text(
            "Следить за каналом: 13:59–14:30 МСК",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
        )

        val offer = w.offer
        if (offer == null) {
            if (w.phase == WatchPhase.WAITING) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)))
            }
            return@GlassCard
        }

        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (item in offer.items) {
                val vs = if (item.container == null) w.variants[item.name] else null
                val loading = item.container == null && vs == null && w.variantsLoading
                when {
                    // Несколько размеров в форме — отмечается конкретный размер.
                    vs != null && vs.size >= 2 -> {
                        Text(
                            item.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                        )
                        if (item.raw in w.selected) PickRow(w, item.raw, "Отмечено без размера — бот спросит", emptyList(), vm::toggleOffer)
                        for (v in vs) {
                            val p = PlantPost.parseLine(v)
                            val title = listOfNotNull(p?.container, p?.size).joinToString(" · ").ifEmpty { v }
                            PickRow(w, v, title, p?.notes.orEmpty(), vm::toggleOffer)
                        }
                    }
                    // Один размер — выбираем сразу его, строку формы целиком.
                    vs != null && vs.size == 1 -> {
                        val p = PlantPost.parseLine(vs[0])
                        PickRow(w, vs[0], item.name, listOfNotNull(p?.container, p?.size) + item.notes, vm::toggleOffer)
                    }
                    else -> PickRow(
                        w, item.raw, item.name,
                        listOfNotNull(item.container, item.size) + item.notes +
                            listOfNotNull(if (item.count > 1) "×${item.count}" else null, if (loading) "размеры загружаются…" else null),
                        vm::toggleOffer,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = vm::fillSelected,
            enabled = w.selected.isNotEmpty() && !running && s.formUrl.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(18.dp),
        ) {
            Icon(Icons.Filled.PlayArrow, null)
            Spacer(Modifier.width(8.dp))
            Text(
                if (w.selected.isEmpty()) "Отметьте растения" else "Заполнить (${w.selected.size})",
                fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** Строка выбора: кружок с номером (порядок отправки), название и чипы. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PickRow(w: WatchState, key: String, title: String, chips: List<String>, onToggle: (String) -> Unit) {
    val order = w.selected.indexOf(key)
    val picked = order >= 0
    val full = !picked && w.selected.size >= PlantRuntime.MAX_SELECTED
    val bg by animateColorAsState(
        if (picked) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else Color.White.copy(alpha = if (LocalDarkTheme.current) 0.05f else 0.35f),
        label = "pick",
    )
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(bg)
            .clickable(enabled = !full) { onToggle(key) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(28.dp).clip(CircleShape)
                .background(if (picked) MaterialTheme.colorScheme.primary else Color.Transparent)
                .border(1.5.dp, if (picked) Color.Transparent else MaterialTheme.colorScheme.outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (picked) Text("${order + 1}", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                color = if (full) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
            )
            if (chips.isNotEmpty()) FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                for (c in chips) Chip(c, accent = PlantPost.isOneEach(c))
            }
        }
    }
}

/** Ручная отправка: ссылки на заполненные формы. Первая открывается сама, остальные — кнопкой. */
@Composable
private fun PreparedCard(run: RunState) {
    if (run.prepared.isEmpty()) return
    val ctx = LocalContext.current
    fun open(url: String) = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    // Сразу после заполнения — один раз; при повторном заходе на вкладку форма сама не открывается.
    LaunchedEffect(run.prepared.first().second) {
        val url = run.prepared.first().second
        if (BotRuntime.takeAutoOpen(url)) open(url)
    }
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Форма заполнена — отправьте сами", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(BotRuntime::clearPrepared) { Text("Скрыть") }
        }
        Text(
            "Проверьте ответы и нажмите «Отправить» внизу формы",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            run.prepared.forEachIndexed { i, (plant, url) ->
                Button({ open(url) }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Text("Открыть форму ${i + 1}" + if (plant.isNotBlank()) ": ${plant.substringBefore(" C").take(32)}" else "",
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String, accent: Boolean = false) {
    val c = if (accent) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    Text(
        text,
        Modifier.clip(RoundedCornerShape(8.dp)).background(c.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelMedium, color = c, fontWeight = if (accent) FontWeight.SemiBold else FontWeight.Normal,
    )
}

/** Один раз: исключение из оптимизации батареи (и точные будильники, если система их отключила). */
private fun askBackgroundPermissions(ctx: Context) {
    val pm = ctx.getSystemService(PowerManager::class.java)
    if (!pm.isIgnoringBatteryOptimizations(ctx.packageName)) runCatching {
        ctx.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
    }
    val am = ctx.getSystemService(AlarmManager::class.java)
    if (!am.canScheduleExactAlarms()) runCatching {
        ctx.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
    }
}

@Composable
private fun StatusCard(s: Settings, run: RunState, running: Boolean) {
    val (icon, tint) = when (run.phase) {
        Phase.IDLE -> Icons.Outlined.Eco to MaterialTheme.colorScheme.primary
        Phase.RUNNING -> Icons.Filled.HourglassTop to MaterialTheme.colorScheme.primary
        Phase.WAITING_HUMAN -> Icons.Filled.RecordVoiceOver to MaterialTheme.colorScheme.tertiary
        Phase.DONE -> Icons.Filled.CheckCircle to MaterialTheme.colorScheme.primary
        Phase.FAILED -> Icons.Filled.ErrorOutline to MaterialTheme.colorScheme.error
    }
    val bg by animateColorAsState(tint.copy(alpha = 0.16f), label = "statusBg")
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(run.status, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val who = listOfNotNull(
                    (if (running) BotRuntime.plants else s.plants).joinToString(", ").ifBlank { null }?.let { "🌱 $it" },
                    s.nickname.ifBlank { null }?.let { "👤 $it" },
                ).joinToString("   ")
                if (who.isNotBlank()) Text(who, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (running) {
            Spacer(Modifier.height(14.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)))
        }
    }
}

@Composable
private fun LogCard(run: RunState, modifier: Modifier) {
    val list = rememberLazyListState()
    LaunchedEffect(run.log.size) { if (run.log.isNotEmpty()) list.animateScrollToItem(run.log.lastIndex) }
    GlassCard(modifier.fillMaxWidth(), padding = 0.dp) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Журнал", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = BotRuntime::clearLog) { Icon(Icons.Outlined.DeleteSweep, "Очистить журнал") }
        }
        if (run.log.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Здесь появится ход работы: открытие формы, ответы ИИ, отправка.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else LazyColumn(state = list, modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            items(run.log) { line ->
                Row(Modifier.padding(vertical = 3.dp)) {
                    Text(line.time, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(end = 8.dp, top = 1.dp))
                    Text(line.text, fontSize = 13.sp, lineHeight = 17.sp)
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

/** Карточка-вопрос, когда ИИ не знает ответа. Закрыть её можно только ответом или пропуском. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HumanSheet(req: HumanRequest) {
    val q = req.question
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden })
    var text by remember(req) { mutableStateOf("") }
    var single by remember(req) { mutableStateOf<String?>(null) }
    val multi = remember(req) { mutableStateListOf<String>() }

    val answer: Answer? = when (q.kind) {
        QKind.CHECKBOX -> if (multi.isNotEmpty()) Answer.Multi(multi.toList()) else null
        QKind.RADIO, QKind.DROPDOWN, QKind.SCALE, QKind.GRID_ROW -> single?.let { Answer.Choice(it) }
        QKind.DATE -> Regex("(\\d{1,2})[./](\\d{1,2})[./](\\d{4})").find(text)?.let {
            runCatching { Answer.Date(LocalDate.of(it.groupValues[3].toInt(), it.groupValues[2].toInt(), it.groupValues[1].toInt())) }.getOrNull()
        }
        QKind.TIME -> Regex("(\\d{1,2})[:.](\\d{2})").find(text)?.let { Answer.Time(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
        else -> text.trim().ifBlank { null }?.let { Answer.Text(it) }
    }

    ModalBottomSheet(onDismissRequest = {}, sheetState = sheet) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Нужен ваш ответ", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
            Text(q.title, style = MaterialTheme.typography.titleLarge)
            if (q.description.isNotBlank()) Text(q.description, color = MaterialTheme.colorScheme.onSurfaceVariant)

            for (bytes in req.images) {
                val bmp = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
                if (bmp != null) Image(
                    bmp, null, contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(RoundedCornerShape(16.dp)),
                )
            }

            when (q.kind) {
                QKind.CHECKBOX -> q.options.forEach { opt ->
                    OptionRow(opt, selected = opt in multi, multi = true) { if (opt in multi) multi.remove(opt) else multi.add(opt) }
                }
                QKind.RADIO, QKind.DROPDOWN, QKind.SCALE, QKind.GRID_ROW -> q.options.forEach { opt ->
                    OptionRow(opt, selected = single == opt, multi = false) { single = opt }
                }
                else -> OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(when (q.kind) { QKind.DATE -> "Дата, ДД.ММ.ГГГГ"; QKind.TIME -> "Время, ЧЧ:ММ"; else -> "Ответ" }) },
                    singleLine = q.kind != QKind.PARAGRAPH,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (q.kind == QKind.DATE || q.kind == QKind.TIME) KeyboardType.Number else KeyboardType.Text,
                    ),
                )
            }

            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { BotRuntime.answerHuman(null) }, modifier = Modifier.weight(1f)) { Text("Пропустить") }
                Button(onClick = { BotRuntime.answerHuman(answer) }, enabled = answer != null, modifier = Modifier.weight(1f)) {
                    Text("Ответить")
                }
            }
        }
    }
}

@Composable
private fun OptionRow(label: String, selected: Boolean, multi: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(bg).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (multi) Checkbox(selected, { onClick() }) else RadioButton(selected, onClick)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
