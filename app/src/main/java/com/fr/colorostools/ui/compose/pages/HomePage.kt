package com.fr.colorostools.ui.compose.pages

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.fr.colorostools.BuildConfig
import com.fr.colorostools.R
import com.fr.colorostools.ui.activity.MainActivity
import com.fr.colorostools.ui.compose.components.LocalScopeTopInset
import com.fr.colorostools.ui.compose.components.ScopeScreen
import com.fr.colorostools.ui.compose.components.TopBarActionButton
import com.fr.colorostools.ui.compose.components.material.MaterialPageScaffold
import com.fr.colorostools.ui.compose.components.material.MaterialTonalCard
import com.fr.colorostools.ui.compose.components.miuix.MiuixPrefDefaults
import com.fr.colorostools.ui.service.XposedServiceBridge
import com.fr.colorostools.ui.shell.ShellBadgeState
import com.fr.colorostools.ui.theme.LocalUiMode
import com.fr.colorostools.ui.theme.UiMode
import com.fr.colorostools.utils.DeviceUtils
import com.fr.colorostools.utils.DonateUtils
import com.fr.colorostools.utils.PrefState
import com.fr.colorostools.utils.RestartMenuUtils
import com.fr.colorostools.utils.SettingsPrefs
import com.fr.colorostools.utils.UpdateChangelogDialog
import com.fr.colorostools.utils.UpdateDownloadProgressDialog
import com.fr.colorostools.utils.UpdateDownloadSourceDialog
import com.fr.colorostools.utils.UpdateDownloadedDialog
import com.fr.colorostools.utils.UpdateUtils
import com.fr.colorostools.utils.copyStr
import com.fr.colorostools.utils.getDeviceInfo
import com.fr.colorostools.utils.getOSVersionCode
import com.fr.colorostools.utils.getVersionCode
import com.fr.colorostools.utils.getVersionName
import com.fr.colorostools.utils.isZh
import com.fr.colorostools.utils.openUrl
import com.fr.colorostools.utils.showToast
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import java.io.File

/**
 * 主页（旧 HomeFragment 的 Compose 等价实现）。
 * 状态卡 / 更新卡 / 系统信息卡 / 捐赠卡 / 授权提示与旧实现逐项对齐。
 *
 * 外观对齐 KernelSU 主题页（ThemeScreen）：可折叠大标题 AppBar +
 * surfaceContainer 底色 + 16dp 内容边距 + surfaceBright 分段卡片。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomePage(activity: MainActivity) {
    val context = LocalContext.current
    val settings = remember { PrefState.of(context, SettingsPrefs) }
    val zh = remember(context) { isZh(context) }
    val isDev = remember { settings.getBoolean("hidden_function", false) }

    // 模块激活态：订阅 Bridge 的推送（libxposed 绑定异步完成即刷新）——
    // 冷启动首轮读取必然早于绑定完成，轮询会先闪现「已停用」，要切一次 tab 才恢复
    val moduleActive by XposedServiceBridge.moduleActiveFlow.collectAsStateWithLifecycle()
    // 用缓存做种子：切回主页时避免 systemInfo 从 null 起步（闪现「加载中」再淡入）
    var systemInfo by remember { mutableStateOf(DeviceInfoCache.info) }
    var updateInfo by remember { mutableStateOf<String?>(null) }
    var updateClick by remember { mutableStateOf<(() -> Unit)?>(null) }
    var statusCardClick by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showAbout by remember { mutableStateOf(false) }
    var aboutText by remember { mutableStateOf("") }
    var showRestartMenu by remember { mutableStateOf(false) }
    var updateStage by remember { mutableStateOf<UpdateStage?>(null) }

    // 旧 onViewCreated：自动检查更新（每次进入页面组合执行一次）
    LaunchedEffect(Unit) {
        // 默认值必须与设置页开关的 default 保持一致（false = 默认不自动检查）
        if (!settings.getBoolean("auto_check_update", false)) return@LaunchedEffect
        UpdateUtils(activity, isDev).checkUpdate { info ->
            // 导航角标：有新版本时在「主页」tab 上提示（受主题页的导航角标开关控制）
            ShellBadgeState.updateAvailable = getVersionCode < info.code
            if (getVersionCode < info.code) {
                updateInfo = context.getString(R.string.check_update_hint) +
                        "  -->  ${info.name}(${info.code})"
                updateClick = { updateStage = UpdateStage.Changelog(info) }
            }
            if (isDev) statusCardClick = { updateStage = UpdateStage.Changelog(info) }
        }
    }

    // 系统信息：首次组合与每次恢复都触发。
    // RootService 冷启动时 daemon 可能尚未就绪，首次 ON_RESUME 的 bind 回调未必能触发；
    // 用 LaunchedEffect(Unit) 补一次首载触发，确保无需重新切换页面即可加载数据。
    // （模块激活态改订阅 XposedServiceBridge.moduleActiveFlow，绑定完成即推送，不在此轮询）
    fun loadHomeData() {
        // 壳子阶段：GlobalFuncService（root 控制器）尚未接入，controller 置 null；
        // getDeviceInfo / checkDexOptimize 内部已按 null 回退到本地设备信息读取。
        val controller: com.fr.colorostools.IGlobalFuncController? = null
        val ota = controller?.otaVersion
        // 缓存命中（OTA 版本未变化）：直接复用，避免每次进入主页都重新拉取设备信息
        val cachedInfo = DeviceInfoCache.get(ota)
        if (cachedInfo != null) {
            systemInfo = cachedInfo
        } else {
            val info = context.getDeviceInfo(controller)
            if (!info.isNullOrBlank()) {
                DeviceInfoCache.put(ota, info)
                systemInfo = info
            }
        }
    }
    LaunchedEffect(Unit) { loadHomeData() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { loadHomeData() }

    MaterialPageScaffold(
        title = stringResource(R.string.app_name),
        actions = {
            TopBarActionButton(
                onClick = { showRestartMenu = true },
                contentDescription = stringResource(R.string.menu_reboot),
                painter = painterResource(R.drawable.ic_baseline_refresh_24),
            )
            TopBarActionButton(
                onClick = {
                    aboutText = if (settings.getBoolean("hidden_function", false)) {
                        context.getString(R.string.app_name)
                    } else {
                        context.getString(R.string.app_name)
                    }
                    showAbout = true
                },
                contentDescription = stringResource(R.string.menu_settings),
                painter = painterResource(R.drawable.ic_baseline_info_24),
            )
        },
    ) { padding ->
        val layoutDirection = LocalLayoutDirection.current
        val uiMode = LocalUiMode.current
        val scopeModifier = if (uiMode == UiMode.Miuix) {
            Modifier
                .fillMaxSize()
                .padding(
                    start = padding.calculateStartPadding(layoutDirection),
                    end = padding.calculateEndPadding(layoutDirection),
                    bottom = padding.calculateBottomPadding(),
                )
        } else {
            Modifier.fillMaxSize().padding(padding)
        }
        val scopeContent: @Composable () -> Unit = {
            // 页面级状态必须在 @Composable 作用域内先读取：ScopeScreen 的参数都是稳定值
            // （本身不可跳过），这里读取后 mutableStateOf 变化会重跑 scopeContent →
            // ScopeScreen 重组 → 条目重建刷新。只在 ScopeScreen 的 content lambda 内读取
            // 不会被列表追踪（旧症状：SYSTEMINFO 需切页后才显示）。
            val systemInfoNow = systemInfo
            val updateInfoNow = updateInfo
            ScopeScreen(state = settings, modifier = scopeModifier) {
                // 状态卡沿用主色/灰色实心配色（保留原强调外观），bare 全宽自定义
                custom(key = "status_card", bare = true) {
                    MaterialTonalCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            // Miuix 线：bare 条目在渲染缝里被统一 offset(-CardHorizontalInset)，供裸分类标题
                            //（自带 16dp 内距）抵消列表级 12dp 内缩；状态卡是应跟随卡片 12dp 内缩的卡片，
                            // 故用等量正向 offset 回正，左右各 12dp 与其余卡片对齐（否则左侧直接贴屏边）。
                            .offset(
                                x = if (uiMode == UiMode.Miuix) {
                                    MiuixPrefDefaults.CardHorizontalInset
                                } else {
                                    0.dp
                                },
                            )
                            .padding(horizontal = if (uiMode == UiMode.Miuix) 0.dp else 16.dp)
                            .clip(MaterialTheme.shapes.large),
                        containerColor = if (moduleActive) {
                            // 深色主题下 colorScheme.primary 是浅色调（淡蓝/淡紫），与白字对比不足、
                            // 观感「发白」；此时回落 inversePrimary（深色主题下即饱和的品牌主色 tone40），
                            // 保持与浅色模式一致的实心强调卡观感。
                            val primary = MaterialTheme.colorScheme.primary
                            if (primary.luminance() > 0.5f) {
                                MaterialTheme.colorScheme.inversePrimary
                            } else {
                                primary
                            }
                        } else {
                            Color.Gray
                        },
                        contentColor = Color.White,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {},
                                    onLongClick = { statusCardClick?.invoke() },
                                )
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painterResource(
                                    if (moduleActive) {
                                        R.drawable.ic_round_check_24
                                    } else {
                                        R.drawable.ic_round_warning_24
                                    }
                                ),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = Color.White,
                            )
                            Column(modifier = Modifier.padding(start = 16.dp)) {
                                Text(
                                    text = stringResource(
                                        if (moduleActive) {
                                            R.string.module_isactivated
                                        } else {
                                            R.string.module_is_disabled
                                        }
                                    ),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontSize = 16.sp,
                                    color = Color.White,
                                )
                                Text(
                                    text = "${stringResource(R.string.module_version)} " +
                                            "$getVersionName ($getVersionCode) " +
                                            BuildConfig.BUILD_TYPE.uppercase(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontSize = 12.sp,
                                    color = Color.White,
                                )
                                Text(
                                    text = DeviceUtils.getRootVersion(context),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontSize = 12.sp,
                                    color = Color.White,
                                )
                                Text(
                                    text = DeviceUtils.getFrameWorkVersion(context),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontSize = 12.sp,
                                    color = Color.White,
                                )
                            }
                        }
                    }
                }
                // 更新卡 / 系统信息卡 / 捐赠卡合并为一张分段卡片（KernelSU 主题页外观）
                updateInfoNow?.let { info ->
                    click(title = info, onClick = { updateClick?.invoke() })
                }
                click(
                    title = systemInfoNow ?: context.getString(R.string.loading),
                    onLongClick = {
                        context.copyStr(DeviceUtils.getOTACOnfigs())
                        context.showToast("Copy Device OTA Data Success!")
                    },
                )
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

    if (showAbout) {
        when (LocalUiMode.current) {
            UiMode.Miuix -> MiuixAboutDialog(
                aboutText = aboutText,
                onLongPress = {
                    val newValue = !settings.getBoolean("hidden_function", false)
                    settings.set("hidden_function", newValue)
                    aboutText = if (newValue) {
                        context.getString(R.string.app_name)
                    } else {
                        context.getString(R.string.app_name)
                    }
                },
                onDismiss = { showAbout = false },
            )

            UiMode.Material -> AlertDialog(
                onDismissRequest = { showAbout = false },
                title = { Text(stringResource(R.string.about_author)) },
                text = {
                    Text(
                        text = aboutText,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                            .combinedClickable(
                                onClick = {},
                                onLongClick = {
                                    val newValue = !settings.getBoolean("hidden_function", false)
                                    settings.set("hidden_function", newValue)
                                    aboutText = if (newValue) {
                                        context.getString(R.string.app_name)
                                    } else {
                                        context.getString(R.string.app_name)
                                    }
                                },
                            ),
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showAbout = false }) {
                        Text(stringResource(android.R.string.ok))
                    }
                },
            )
        }
    }

    if (showRestartMenu) {
        RestartMenuUtils.RestartMenuDialog(context) { showRestartMenu = false }
    }





    when (val stage = updateStage) {
        null -> Unit
        is UpdateStage.Changelog -> UpdateChangelogDialog(
            context, stage.info, isDev,
            onDismiss = { updateStage = null },
            onDownload = {
                val apkFile = UpdateUtils(context).prepareApkFile(stage.info.fileName)
                updateStage = if (apkFile.exists()) {
                    UpdateStage.Downloaded(stage.info, apkFile)
                } else {
                    UpdateStage.Source(stage.info, apkFile)
                }
            },
        )
        is UpdateStage.Source -> UpdateDownloadSourceDialog(
            context, stage.info.downloadUrl, isDev,
            onDismiss = { updateStage = null },
            onSelect = { url -> updateStage = UpdateStage.Downloading(stage.apkFile, url) },
        )
        is UpdateStage.Downloaded -> UpdateDownloadedDialog(
            context, stage.apkFile, isDev,
            onDismiss = { updateStage = null },
            onDownloadAgain = {
                stage.apkFile.delete()
                updateStage = UpdateStage.Source(stage.info, stage.apkFile)
            },
            onInstall = { UpdateUtils(context).installApk(stage.apkFile) },
        )
        is UpdateStage.Downloading -> UpdateDownloadProgressDialog(
            context, stage.apkFile, stage.url,
            onDismiss = { updateStage = null },
        )
    }
}

/**
 * 关于作者对话框（Miuix 线）。
 *
 * 弹层由根部 Miuix Scaffold 的 popup host 承载（`renderInRootScaffold` 保持默认 true），
 * 页面内不自装 host。
 */
@Composable
private fun MiuixAboutDialog(
    aboutText: String,
    onLongPress: () -> Unit,
    onDismiss: () -> Unit,
) {
    OverlayDialog(
        show = true,
        title = stringResource(R.string.about_author),
        onDismissRequest = onDismiss,
    ) {
        MiuixText(
            text = aboutText,
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
                .combinedClickable(onClick = {}, onLongClick = onLongPress),
        )
        Row(modifier = Modifier.padding(top = 12.dp)) {
            MiuixTextButton(
                text = stringResource(android.R.string.ok),
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

/**
 * 主页系统信息缓存：以 OTA 版本为键，OTA 未变化时复用上一次拼好的设备信息，
 * 避免每次进入主页都重新走一遍 AIDL 调用 + 字符串拼接。
 */
private object DeviceInfoCache {
    @Volatile
    var info: String? = null
    @Volatile
    var ota: String? = null

    fun get(otaVersion: String?): String? = info.takeIf { ota == otaVersion }

    fun put(otaVersion: String?, value: String) {
        ota = otaVersion
        info = value
    }
}

/** 更新对话框状态机：更新日志 → 下载源 → 下载中 → 已下载 */
private sealed class UpdateStage {
    data class Changelog(val info: UpdateUtils.UpdateInfo) : UpdateStage()
    data class Source(val info: UpdateUtils.UpdateInfo, val apkFile: File) : UpdateStage()
    data class Downloaded(val info: UpdateUtils.UpdateInfo, val apkFile: File) : UpdateStage()
    data class Downloading(val apkFile: File, val url: String) : UpdateStage()
}
