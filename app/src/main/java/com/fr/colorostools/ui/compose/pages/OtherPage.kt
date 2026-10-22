package com.fr.colorostools.ui.compose.pages

import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.collection.ArrayMap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AddToHomeScreen
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.fr.colorostools.IAdbDebugController
import com.fr.colorostools.ITileServiceController
import com.fr.colorostools.R
import com.fr.colorostools.ui.activity.MainActivity
import com.fr.colorostools.ui.compose.components.AppPickerDialog
import com.fr.colorostools.ui.compose.components.LocalScopeTopInset
import com.fr.colorostools.ui.compose.components.PrefIconBadge
import com.fr.colorostools.ui.compose.components.ScopeScreen
import com.fr.colorostools.ui.compose.components.TopBarActionButton
import com.fr.colorostools.ui.compose.components.material.MaterialPageScaffold
import com.fr.colorostools.ui.theme.LocalUiMode
import com.fr.colorostools.ui.theme.UiMode
import com.fr.colorostools.utils.A13
import com.fr.colorostools.utils.GlobalKeyValue.keyTouchSamplingRateLevel
import com.fr.colorostools.utils.OtherPrefs
import com.fr.colorostools.utils.PackageUtils
import com.fr.colorostools.utils.PrefState
import com.fr.colorostools.utils.RestartMenuUtils
import com.fr.colorostools.utils.SDK
import com.fr.colorostools.utils.SettingsPrefs
import com.fr.colorostools.utils.copyStr
import com.fr.colorostools.utils.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.CheckboxLocation
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton
import top.yukonga.miuix.kmp.basic.TextField as MiuixTextField

/**
 * 其他页（旧 OtherFragment 的 Compose 等价实现）。
 * 卡片顺序、可见性条件、对话框行为与旧实现逐项对齐。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OtherPage(activity: MainActivity) {
    val context = LocalContext.current
    val settings = remember { PrefState.of(context, SettingsPrefs) }
    val otherPrefs = remember { PrefState.of(context, OtherPrefs) }
    val scope = rememberCoroutineScope()
    // 初始值从进程级缓存取：tab 切换销毁本页组合、remember 会重置，若从 null 起步
    // 每次切回都会重新加载并重放显隐动画；用服务端已缓存的存活控制器做种子即可瞬时还原
    var tileController by remember { mutableStateOf<ITileServiceController?>(null) }
    var adbController by remember { mutableStateOf<IAdbDebugController?>(null) }
    var showOptimizePicker by remember { mutableStateOf(false) }
    var optimizeScopes by remember { mutableStateOf<ArrayMap<String, CharSequence>?>(null) }
    var showTileDialog by remember { mutableStateOf(false) }
    var showTouchDialog by remember { mutableStateOf(false) }
    var showAdbDialog by remember { mutableStateOf(false) }

    // 壳子阶段：tiles / adb 的 root 控制器服务尚未接入，控制器恒为 null，
    // 相关入口（触控采样率 / 远程 ADB）在控制器就绪后才出现。
    // 后续接入 hook 时恢复 loadControllers()（TilesService.get / AdbService.get）即可。

    MaterialPageScaffold(
        title = stringResource(R.string.nav_other),
        actions = {
            TopBarActionButton(
                onClick = { showOptimizePicker = true },
                contentDescription = "优化App",
                painter = rememberVectorPainter(Icons.Filled.AutoFixHigh),
            )
        },
    ) { padding ->
        val layoutDirection = LocalLayoutDirection.current
        val uiMode = LocalUiMode.current
        // 作用域内容列表自带 LazyColumn（不能再套 ExpressiveList）；Miuix 线只保留 start/end/bottom
        // 外置 padding（top 归 0，顶栏高度经 LocalScopeTopInset 交给列表做 contentPadding.top）。
        val scopeModifier = if (uiMode == UiMode.Miuix) {
            Modifier
                .fillMaxSize()
                .padding(
                    start = padding.calculateStartPadding(layoutDirection),
                    end = padding.calculateEndPadding(layoutDirection),
                    bottom = padding.calculateBottomPadding(),
                )
        } else {
            Modifier
                .fillMaxSize()
                .padding(padding)
        }
        val scopeContent: @Composable () -> Unit = {
            // 控制器状态在 @Composable 作用域内先读取（原因见 HomePage.scopeContent 注释）：
            // 否则 AIDL 控制器连上后入口不出现，需要切页才显示
            val touchModeNow = tileController?.checkTouchMode() == true
            val hasAdbNow = adbController != null
            ScopeScreen(
                state = settings,
                modifier = scopeModifier,
            ) {
                // 全部入口合并为一张分段卡片（KernelSU 主题页外观）
                click(
                    title = context.getString(R.string.quick_entry),
                    summary = context.getString(R.string.quick_entry_summary),
                    leading = { PrefIconBadge(Icons.Filled.Bolt, Color(0xFFFF9800)) },
                    onClick = {
                        activity.requestFunctionNavigation(
                            "quick_entry", context.getString(R.string.quick_entry)
                        )
                    },
                )
                if (SDK >= A13) {
                    click(
                        title = context.getString(R.string.tile_list),
                        summary = context.getString(R.string.tile_list_summary),
                        leading = { PrefIconBadge(Icons.Filled.GridView, Color(0xFF4CAF50)) },
                        onClick = {
                            context.showToast(context.getString(R.string.tile_list_click_tips))
                            showTileDialog = true
                        },
                    )
                }
                if (touchModeNow) {
                    click(
                        title = context.getString(R.string.set_touch_sampling_rate_tile_level),
                        summary = context.getString(R.string.set_touch_sampling_rate_tile_level_summary),
                        leading = { PrefIconBadge(Icons.Filled.TouchApp, Color(0xFF009688)) },
                        onClick = { showTouchDialog = true },
                    )
                }
                if (hasAdbNow) {
                    click(
                        title = context.getString(R.string.remote_adb_debug_title),
                        summary = context.getString(R.string.remote_adb_debug_summary),
                        leading = { PrefIconBadge(Icons.Filled.Terminal, Color(0xFF607D8B)) },
                        onClick = { showAdbDialog = true },
                    )
                }
            }
        }
        if (uiMode == UiMode.Miuix) {
            CompositionLocalProvider(LocalScopeTopInset provides padding.calculateTopPadding()) {
                scopeContent()
            }
        } else {
            scopeContent()
        }
    }

    if (showOptimizePicker) {
        AppPickerDialog(
            title = "优化App",
            multiMode = true,
            showSystemApps = true,
            onDismiss = { showOptimizePicker = false },
            onConfirm = { list ->
                showOptimizePicker = false
                optimizeScopes = RestartMenuUtils.buildScopeMaps(
                    context, list.map { it.packageName }.toTypedArray()
                )
            },
        )
    }
    optimizeScopes?.let { scopes ->
        RestartMenuUtils.OptimizeDexDialog(context, scopes, confirmFirst = false) {
            optimizeScopes = null
        }
    }

    if (showTileDialog) {
        TileListDialog(context) { showTileDialog = false }
    }
    if (showTouchDialog && tileController?.checkTouchMode() == true) {
        TouchSamplingRateDialog(context, settings, tileController) { showTouchDialog = false }
    }
    if (showAdbDialog && adbController != null) {
        AdbDebugDialog(context, otherPrefs, adbController, scope) { showAdbDialog = false }
    }
}

/** 模块内置磁贴列表：请求添加到控制中心（旧 initQuickTile） */
@SuppressLint("NewApi")
@Composable
private fun TileListDialog(context: Context, onDismiss: () -> Unit) {
    val info = remember(context) {
        PackageUtils(context.packageManager).getPackageInfo(
            context.packageName, PackageManager.GET_SERVICES
        )
    } ?: return
    val tileInfos = remember(info) {
        info.services?.filter {
            it.permission == "android.permission.BIND_QUICK_SETTINGS_TILE"
        }?.toList() ?: emptyList()
    }
    val statusBarManager = remember { context.getSystemService(StatusBarManager::class.java) }
    when (LocalUiMode.current) {
        UiMode.Miuix -> OverlayDialog(
            show = true,
            onDismissRequest = onDismiss,
        ) {
            Column {
                tileInfos.forEach { serviceInfo ->
                    val label = serviceInfo.loadLabel(context.packageManager).toString()
                    BasicComponent(
                        title = label,
                        insideMargin = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                        onClick = {
                            val icon = serviceInfo.loadIcon(context.packageManager)
                            statusBarManager.requestAddTileService(
                                ComponentName(context.packageName, serviceInfo.name),
                                label,
                                android.graphics.drawable.Icon.createWithBitmap(icon.toBitmap()),
                                context.mainExecutor
                            ) { resultCode ->
                                when (resultCode) {
                                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED ->
                                        context.showToast("$label ${context.getString(R.string.add_fail)}")

                                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ->
                                        context.showToast("$label ${context.getString(R.string.add_repeat)}")

                                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ->
                                        context.showToast("$label ${context.getString(R.string.add_success)}")
                                }
                            }
                            onDismiss()
                        },
                    )
                }
            }
        }

        UiMode.Material -> AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = {},
            text = {
                Column {
                    tileInfos.forEach { serviceInfo ->
                        val label = serviceInfo.loadLabel(context.packageManager).toString()
                        Text(
                            text = label,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val icon = serviceInfo.loadIcon(context.packageManager)
                                    statusBarManager.requestAddTileService(
                                        ComponentName(context.packageName, serviceInfo.name),
                                        label,
                                        android.graphics.drawable.Icon.createWithBitmap(icon.toBitmap()),
                                        context.mainExecutor
                                    ) { resultCode ->
                                        when (resultCode) {
                                            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED ->
                                                context.showToast("$label ${context.getString(R.string.add_fail)}")

                                            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ->
                                                context.showToast("$label ${context.getString(R.string.add_repeat)}")

                                            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ->
                                                context.showToast("$label ${context.getString(R.string.add_success)}")
                                        }
                                    }
                                    onDismiss()
                                }
                                .padding(vertical = 12.dp)
                        )
                    }
                }
            },
        )
    }
}


/** 触摸采样率档位（旧 initTouchPanelView） */
@Composable
private fun TouchSamplingRateDialog(
    context: Context,
    settings: PrefState,
    controller: ITileServiceController?,
    onDismiss: () -> Unit,
) {
    val touchs = remember { arrayOf("120", "180", "240", "360", "480", "600", "720") }
    var tempSelection by remember {
        mutableStateOf(touchs.indexOf(settings.getString(keyTouchSamplingRateLevel, "240")))
    }
    when (LocalUiMode.current) {
        UiMode.Miuix -> OverlayDialog(
            show = true,
            title = stringResource(R.string.set_touch_sampling_rate_tile_level),
            onDismissRequest = onDismiss,
        ) {
            Column {
                touchs.forEachIndexed { position, level ->
                    RadioButtonPreference(
                        title = level,
                        selected = tempSelection == position,
                        onClick = { tempSelection = position },
                    )
                }
            }
            Row(modifier = Modifier.padding(top = 12.dp)) {
                MiuixTextButton(
                    text = stringResource(android.R.string.cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(20.dp))
                MiuixTextButton(
                    text = stringResource(android.R.string.ok),
                    onClick = {
                        val value =
                            if (tempSelection > 0) touchs[tempSelection] else tempSelection.toString()
                        settings.set(keyTouchSamplingRateLevel, value)
                        controller?.touchMode = value.toInt()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }

        UiMode.Material -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.set_touch_sampling_rate_tile_level)) },
            text = {
                Column {
                    touchs.forEachIndexed { position, level ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = tempSelection == position,
                                    role = Role.RadioButton,
                                    onClick = { tempSelection = position },
                                )
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = tempSelection == position,
                                onClick = { tempSelection = position },
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(level)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val value =
                            if (tempSelection > 0) touchs[tempSelection] else tempSelection.toString()
                        settings.set(keyTouchSamplingRateLevel, value)
                        controller?.touchMode = value.toInt()
                        onDismiss()
                    },
                ) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}

/** 远程 ADB 调试（旧 initAdbDebugView，布局与状态机保持原样） */
@OptIn(ExperimentalFoundationApi::class)
@SuppressLint("SetTextI18n")
@Composable
private fun AdbDebugDialog(
    context: Context,
    otherPrefs: PrefState,
    controller: IAdbDebugController?,
    scope: CoroutineScope,
    onDismiss: () -> Unit,
) {
    val adb = controller ?: return
    val getPort = adb.adbPort
    var getIP by remember { mutableStateOf(adb.wifiIP ?: "IP") }

    var portText by remember {
        mutableStateOf(
            (if (getPort == 0 || getPort == -1) otherPrefs.getString("adb_port", "6666")
            else getPort.toString()) ?: "6666"
        )
    }
    var adbTvText by remember {
        mutableStateOf(if (getPort != 0 && getPort != -1) "adb connect $getIP:$getPort" else "")
    }
    var busy by remember { mutableStateOf(false) }
    var adbEnabled by remember { mutableStateOf(getPort != 0 && getPort != -1) }

    when (LocalUiMode.current) {
        UiMode.Miuix -> OverlayDialog(
            show = true,
            onDismissRequest = onDismiss,
        ) {
            SwitchPreference(
                checked = adbEnabled,
                onCheckedChange = { checked ->
                    if (checked) {
                        val portStr = portText
                        if (portStr.isBlank()) {
                            adbTvText = context.getString(R.string.adb_debug_port_cannot_null)
                        } else {
                            scope.launch {
                                val port = portStr.toIntOrNull()
                                if (port == null) {
                                    adbTvText =
                                        context.getString(R.string.adb_debug_port_cannot_null)
                                    return@launch
                                }
                                busy = true
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        adb.adbPort = port
                                        adb.restartAdb()
                                    }
                                    getIP = adb.wifiIP ?: "IP"
                                    otherPrefs.set("adb_port", port.toString())
                                }
                                adbEnabled = true
                                adbTvText = "adb connect $getIP:$portStr"
                                busy = false
                            }
                        }
                    } else {
                        scope.launch {
                            busy = true
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    adb.adbPort = -1
                                    adb.restartAdb()
                                    adb.adbPort = 0
                                }
                            }
                            adbEnabled = false
                            adbTvText = ""
                            busy = false
                        }
                    }
                },
                title = stringResource(R.string.enable_remote_adb_debugging),
                enabled = !busy,
            )
            MiuixTextField(
                value = portText,
                onValueChange = { portText = it },
                enabled = !adbEnabled && !busy,
                label = stringResource(R.string.adb_port),
                useLabelAsPlaceholder = true,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            if (adbTvText.isNotBlank()) {
                MiuixText(
                    text = adbTvText,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onLongClick = { context.copyStr(adbTvText) },
                            onClick = {},
                        )
                        .padding(vertical = 12.dp),
                )
                MiuixText(
                    text = stringResource(R.string.adb_tv_tip),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onLongClick = { context.copyStr(adbTvText) },
                            onClick = {},
                        )
                        .padding(bottom = 20.dp),
                )
            }
        }

        UiMode.Material -> AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = {},
            text = {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.enable_remote_adb_debugging),
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = adbEnabled,
                            enabled = !busy,
                            onCheckedChange = { checked ->
                                if (checked) {
                                    val portStr = portText
                                    if (portStr.isBlank()) {
                                        adbTvText =
                                            context.getString(R.string.adb_debug_port_cannot_null)
                                    } else {
                                        scope.launch {
                                            val port = portStr.toIntOrNull()
                                            if (port == null) {
                                                adbTvText =
                                                    context.getString(R.string.adb_debug_port_cannot_null)
                                                return@launch
                                            }
                                            busy = true
                                            runCatching {
                                                withContext(Dispatchers.IO) {
                                                    adb.adbPort = port
                                                    adb.restartAdb()
                                                }
                                                getIP = adb.wifiIP ?: "IP"
                                                otherPrefs.set("adb_port", port.toString())
                                            }
                                            adbEnabled = true
                                            adbTvText = "adb connect $getIP:$portStr"
                                            busy = false
                                        }
                                    }
                                } else {
                                    scope.launch {
                                        busy = true
                                        runCatching {
                                            withContext(Dispatchers.IO) {
                                                adb.adbPort = -1
                                                adb.restartAdb()
                                                adb.adbPort = 0
                                            }
                                        }
                                        adbEnabled = false
                                        adbTvText = ""
                                        busy = false
                                    }
                                }
                            },
                        )
                    }
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it },
                        enabled = !adbEnabled && !busy,
                        label = { Text(stringResource(R.string.adb_port)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    if (adbTvText.isNotBlank()) {
                        Text(
                            text = adbTvText,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onLongClick = { context.copyStr(adbTvText) },
                                    onClick = {},
                                )
                                .padding(vertical = 12.dp),
                        )
                        Text(
                            text = stringResource(R.string.adb_tv_tip),
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onLongClick = { context.copyStr(adbTvText) },
                                    onClick = {},
                                )
                                .padding(bottom = 20.dp),
                        )
                    }
                }
            },
        )
    }
}
