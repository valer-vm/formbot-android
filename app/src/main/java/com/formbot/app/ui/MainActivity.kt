package com.formbot.app.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.rememberHazeState

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    /** Уведомление о растениях открывает вкладку «Запуск». */
    private var openRun = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (intent?.getBooleanExtra(EXTRA_TAB_RUN, false) == true) openRun.intValue++
        setContent {
            val s by vm.settings.collectAsStateWithLifecycle()
            FormBotTheme(ThemeMode.of(s.theme)) {
                // Значки статус-бара — под выбранную в приложении тему, а не системную.
                val dark = LocalDarkTheme.current
                LaunchedEffect(dark) {
                    val t = android.graphics.Color.TRANSPARENT
                    val style = if (dark) SystemBarStyle.dark(t) else SystemBarStyle.light(t, t)
                    enableEdgeToEdge(style, style)
                }
                App(vm, openRun.intValue)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_TAB_RUN, false)) openRun.intValue++
        vm.refreshOffer()
    }

    companion object {
        const val EXTRA_TAB_RUN = "tab_run"
    }
}

private enum class Tab(val title: String, val icon: ImageVector) {
    RUN("Запуск", Icons.Outlined.PlayCircle),
    FORM("Форма", Icons.Outlined.Description),
    AI("ИИ", Icons.Outlined.AutoAwesome),
    ANSWERS("Ответы", Icons.Outlined.QuestionAnswer),
}

@Composable
private fun App(vm: AppViewModel, openRun: Int) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val run by vm.run.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val haze = rememberHazeState()
    val dark = LocalDarkTheme.current

    // Уведомления нужны, чтобы бот мог позвать вас, когда ИИ не знает ответа, и сообщить о растениях.
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    LaunchedEffect(openRun) { if (openRun > 0) tab = 0 }
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }

    GlassBackground(haze) {
        CompositionLocalProvider(LocalHaze provides haze, LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
            Scaffold(
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onBackground,
                topBar = {
                    Row(
                        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)
                            .glass(RoundedCornerShape(22.dp), strong = true)
                            .padding(start = 18.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (tab == 0) "FormBot" else Tab.entries[tab].title,
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                        )
                        // Быстрый переключатель: светлая ↔ тёмная. Полный выбор — во вкладке «Форма».
                        IconButton({ vm.update { it.copy(theme = if (dark) ThemeMode.LIGHT.id else ThemeMode.DARK.id) } }) {
                            Icon(if (dark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode, "Сменить тему")
                        }
                    }
                },
                bottomBar = {
                    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(
                            Modifier.fillMaxWidth().glass(RoundedCornerShape(30.dp), strong = true).padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            Tab.entries.forEachIndexed { i, t ->
                                NavigationBarItem(
                                    selected = tab == i,
                                    onClick = { tab = i },
                                    icon = { Icon(t.icon, null) },
                                    label = { Text(t.title) },
                                    colors = NavigationBarItemDefaults.colors(
                                        indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = if (dark) 0.35f else 0.18f),
                                    ),
                                )
                            }
                        }
                    }
                },
                snackbarHost = { SnackbarHost(snackbar) },
            ) { pad ->
                val m = Modifier.padding(pad)
                when (Tab.entries[tab]) {
                    Tab.RUN -> RunScreen(vm, settings, run, onOpenForm = { tab = 1 }, modifier = m)
                    Tab.FORM -> FormScreen(vm, settings, m)
                    Tab.AI -> AiScreen(vm, m)
                    Tab.ANSWERS -> AnswersScreen(settings, vm::update, m)
                }
            }
        }
    }

    run.pending?.let { HumanSheet(it) }
}
