package com.fr.colorostools.ui.compose.pages

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fr.colorostools.R
import com.fr.colorostools.ui.activity.MainActivity
import com.fr.colorostools.ui.compose.components.MainPageActivationEffect
import com.fr.colorostools.ui.service.XposedServiceBridge
import com.fr.colorostools.ui.shell.ShellBadgeState
import com.fr.colorostools.ui.theme.LocalUiMode
import com.fr.colorostools.ui.theme.UiMode
import com.fr.colorostools.utils.DeviceInfoEntry
import com.fr.colorostools.utils.DeviceUtils
import com.fr.colorostools.utils.LogUtils
import com.fr.colorostools.utils.PrefState
import com.fr.colorostools.utils.RestartMenuUtils
import com.fr.colorostools.utils.SettingsPrefs
import com.fr.colorostools.utils.UpdateChangelogDialog
import com.fr.colorostools.utils.UpdateDownloadProgressDialog
import com.fr.colorostools.utils.UpdateDownloadSourceDialog
import com.fr.colorostools.utils.UpdateDownloadedDialog
import com.fr.colorostools.utils.UpdateUtils
import com.fr.colorostools.utils.copyStr
import com.fr.colorostools.utils.getDeviceInfoEntries
import com.fr.colorostools.utils.getOSVersionCode
import com.fr.colorostools.utils.getVersionCode
import com.fr.colorostools.utils.getVersionName
import com.fr.colorostools.utils.isZh
import com.fr.colorostools.utils.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import java.io.File
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton

/** 「已优化 Dex 的系统版本」键名（UI 本地的系统更新判定状态，hook 侧不读） */
private const val OS_VERSION_KEY = "current_os_version"

/**
 * 主页数据与操作入口，展示层按当前主题使用对应的原生组件。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomePage(activity: MainActivity, isCurrentPage: Boolean = true) {
    val context = LocalContext.current
    val settings = remember { PrefState.of(context, SettingsPrefs) }
    val zh = remember(context) { isZh(context) }
    // 隐藏功能可在运行期被「关于作者」长按反复切换：必须走可观察 Flow，
    // 不能用 remember 快照（否则开关关掉后 isDev 仍停留在组合时的旧值）
    val isDev by settings.booleanFlow("hidden_function", false).collectAsStateWithLifecycle()

    // 激活状态直接跟随框架服务绑定/断开（观察式），不再靠进入页面时问一次
    val moduleActive by XposedServiceBridge.moduleActiveFlow.collectAsStateWithLifecycle()
    // 用缓存做种子：切回主页时避免 systemInfo 从 null 起步（闪现「加载中」再淡入）
    var systemInfo by remember { mutableStateOf(DeviceInfoCache.info) }
    var rootVersion by remember { mutableStateOf<String?>(null) }
    var frameworkVersion by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var runtimeInfoJob by remember { mutableStateOf<Job?>(null) }
    var deviceInfoJob by remember { mutableStateOf<Job?>(null) }
    var updateInfo by remember { mutableStateOf<String?>(null) }
    var updateClick by remember { mutableStateOf<(() -> Unit)?>(null) }
    var latestUpdate by remember { mutableStateOf<UpdateUtils.UpdateInfo?>(null) }
    var showAbout by remember { mutableStateOf(false) }
    var aboutText by remember { mutableStateOf("") }
    var dexDialogVisible by remember { mutableStateOf(false) }
    var pendingOsVersion by remember { mutableStateOf("") }
    var showRestartMenu by remember { mutableStateOf(false) }
    var showOptimizeDex by remember { mutableStateOf(false) }
    var updateStage by remember { mutableStateOf<UpdateStage?>(null) }
    // 本次进程内已被用户处置过的 OTA 版本：prefs 写入是异步的，仅靠 `!dexDialogVisible`
    // 拦不住 onResume 触发的立刻重查，会同一会话反复弹窗。
    var handledOsVersion by remember { mutableStateOf("") }
    // 「已优化 Dex 的系统版本」必须同时落本地 prefs：
    // appPrefs() 在 libxposed service 绑定前回落本地、绑定后走 remote，冷启动绑定是异步的，
    // 用户在这个窗口期点「忽略本次系统更新」只会写进本地；而 migrateLocalToRemote() 受
    // MIGRATED_FLAG 约束只搬一次，之后的本地新增键永不再搬 ⇒ 下次读 remote 就丢了（弹窗复现）。
    val localPrefs = remember(context) {
        context.getSharedPreferences(SettingsPrefs, Context.MODE_PRIVATE)
    }

    /** 已记录的「优化过 Dex 的系统版本」——两个数据源都查，任一对上即视为已处置 */
    fun recordedOsVersions(): List<String> = buildList {
        runCatching { settings.getString(OS_VERSION_KEY, "") }.getOrNull()
            ?.takeIf { it.isNotBlank() }?.let { add(it) }
        runCatching { localPrefs.getString(OS_VERSION_KEY, "") }.getOrNull()
            ?.takeIf { it.isNotBlank() }?.let { add(it) }
    }

    /**
     * 记住「已优化过 Dex 的系统版本」，并回读校验是否真的写进去了。
     *
     * 写入失败（remote prefs 尚未绑定 / 键被清空）时只抑制本次会话，不让用户
     * 反复点同一个按钮：返回值供调用方决定是否还要再次提示。
     */
    fun recordOsVersion(version: String): Boolean {
        if (version.isBlank()) return false
        // 无论落盘是否成功都记进会话标记：用户已经表过态，本次运行不该再被问第二次
        handledOsVersion = version
        // 双写：remote 供备份/跨进程，本地兜住「框架尚未绑定」的窗口期
        val remoteOk = runCatching { settings.set(OS_VERSION_KEY, version) }.getOrDefault(false)
        val localOk = runCatching {
            localPrefs.edit().putString(OS_VERSION_KEY, version).commit()
        }.getOrDefault(false)
        LogUtils.d(
            "HomePage", "recordOsVersion",
            "$version remote=$remoteOk local=$localOk", true
        )
        if (!remoteOk && !localOk) return false
        // 回读校验：任一源能读回即算成功（不能只查 remote，绑定前它本来就读不到）
        return recordedOsVersions().any { it == version }
    }

    fun checkDexOptimize() {
        if (getOSVersionCode < 34) return
        // OTA 版本必须与设备卡「版本」行同源：两处各自取源会导致
        // 「忽略本次系统更新」写入的值与下次比较的值不等，弹窗反复出现。
        val curOs = DeviceUtils.resolveOtaVersion()
        // 取不到 OTA 版本时不弹窗：没有可比对的值，让用户「忽略」一个空值没有意义，
        // 而且写下的空串会在下次 shell 就绪时被判为「换系统了」。
        if (curOs.isBlank()) return
        // 本次进程内已处置过这个版本 => 不再提示（落盘异步，不能只信 prefs 回读）
        if (curOs == handledOsVersion) return
        val recorded = recordedOsVersions()
        // 判定日志：若不生效可直接看这一行区分「读不到 OTA / 写到别处 / 值不一致」
        LogUtils.d(
            "HomePage", "checkDexOptimize",
            "cur=$curOs recorded=$recorded remoteBound=${XposedServiceBridge.xposedService != null}",
            true
        )
        if (curOs in recorded) return
        if (!dexDialogVisible) {
            pendingOsVersion = curOs
            dexDialogVisible = true
        }
    }

    fun checkForUpdates() {
        if (!settings.getBoolean("auto_check_update", true)) return
        UpdateUtils(activity, isDev).checkUpdate { info ->
            // 只留存发布信息，长按状态卡是否可用在展示侧按当前 isDev 现场判定
            latestUpdate = info
            // 导航角标：有新版本时在「主页」tab 上提示（受主题页的导航角标开关控制）
            ShellBadgeState.updateAvailable = getVersionCode < info.code
            if (getVersionCode < info.code) {
                updateInfo = "${info.name} (${info.code})"
                updateClick = { updateStage = UpdateStage.Changelog(info) }
            }
        }
    }

    // 仅当前页首次激活或恢复前台时读取；预组合其他标签不会发起服务请求。
    fun loadHomeData() {
        runtimeInfoJob?.cancel()
        runtimeInfoJob = scope.launch {
            val versions = withContext(Dispatchers.IO) {
                DeviceUtils.getRootVersion(context) to DeviceUtils.getFrameWorkVersion(context)
            }
            rootVersion = versions.first
            frameworkVersion = versions.second.takeUnless { "null" in it }
                ?: "${context.getString(R.string.framework_version)} —"
        }
        // 壳子阶段：GlobalFuncService（root 控制器）尚未接入，controller 恒为 null，
        // getDeviceInfoEntries 内部已按 null 回退到本地设备信息读取。
        deviceInfoJob?.cancel()
        deviceInfoJob = scope.launch {
            val (ota, entries) = withContext(Dispatchers.IO) {
                // 与 checkDexOptimize、设备卡「版本」行共用同一解析入口：
                // 三处不同源会让「忽略本次系统更新」记下的值与下次比较的值对不上
                val ota = DeviceUtils.resolveOtaVersion()
                ota to (DeviceInfoCache.get(ota) ?: getDeviceInfoEntries(null, ota))
            }
            DeviceInfoCache.put(ota, entries)
            systemInfo = entries
            checkDexOptimize()
        }
    }
    MainPageActivationEffect(
        isCurrentPage = isCurrentPage,
        onFirstActive = {
            checkForUpdates()
            loadHomeData()
        },
        onResume = ::loadHomeData,
    )

    // 长按状态卡查看更新日志属于隐藏功能：关闭后立刻失效，不残留上一次绑定的回调
    val statusCardLongClick: (() -> Unit)? = if (!isDev) null else latestUpdate?.let { info ->
        { updateStage = UpdateStage.Changelog(info) }
    }

    HomeDashboard(
        state = HomeDashboardState(
            moduleActive = moduleActive,
            moduleVersion = "$getVersionName ($getVersionCode)",
            rootVersion = rootVersion,
            frameworkVersion = frameworkVersion,
            deviceInfo = systemInfo,
            updateVersion = updateInfo,
            showNotice = zh,
        ),
        onRestart = { showRestartMenu = true },
        onAbout = {
            aboutText = if (settings.getBoolean("hidden_function", false)) "忆清鸣、luckyzyx T"
            else "忆清鸣、luckyzyx"
            showAbout = true
        },
        onUpdate = { updateClick?.invoke() },
        onStatusLongClick = statusCardLongClick,
        onCopyDeviceInfo = {
            context.copyStr(DeviceUtils.getOTACOnfigs())
            context.showToast(context.getString(R.string.home_device_info_copied))
        },
        // 底部提示语（authorized）保持纯文案，不再外跳到上游文档站
        onOpenNotice = { },
    )

    // 异步结果可以保留，但只在主页真正可交互时展示弹层。
    if (!isCurrentPage) return

    if (showAbout) {
        when (LocalUiMode.current) {
            UiMode.Miuix -> MiuixAboutDialog(
                aboutText = aboutText,
                onLongPress = {
                    val newValue = !settings.getBoolean("hidden_function", false)
                    settings.set("hidden_function", newValue)
                    aboutText = if (newValue) {
                        "忆清鸣、luckyzyx T"
                    } else {
                        "忆清鸣、luckyzyx"
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
                                        "忆清鸣、luckyzyx T"
                                    } else {
                                        "忆清鸣、luckyzyx"
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

    if (dexDialogVisible) {
        // 三种处置：本次先跳过（下次仍提示）/ 认可当前系统版本（不再提示）/ 立即优化
        val skipOnce: () -> Unit = { dexDialogVisible = false }
        val skipThisUpdate: () -> Unit = {
            dexDialogVisible = false
            if (!recordOsVersion(pendingOsVersion)) {
                // 写入没成功（remote prefs 还没绑定等）：本次会话别再弹，
                // 下次冷启动若仍对不上会再次提示 —— 这是唯一不骗用户的行为
                LogUtils.w("HomePage", "checkDexOptimize", "record $pendingOsVersion failed")
            }
        }
        val optimizeNow: () -> Unit = {
            dexDialogVisible = false
            recordOsVersion(pendingOsVersion)
            showOptimizeDex = true
        }
        when (LocalUiMode.current) {
            UiMode.Miuix -> OverlayDialog(
                show = true,
                onDismissRequest = skipOnce,
            ) {
                MiuixText(
                    text = stringResource(R.string.optimize_dex_after_system_update),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(modifier = Modifier.padding(top = 12.dp)) {
                    MiuixTextButton(
                        text = stringResource(R.string.ignore),
                        onClick = skipOnce,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(20.dp))
                    MiuixTextButton(
                        text = stringResource(android.R.string.ok),
                        onClick = optimizeNow,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
                // 三个选项横排会在窄屏挤出边界，第三项单独一行
                MiuixTextButton(
                    text = stringResource(R.string.ignore_this_system_update),
                    onClick = skipThisUpdate,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }

            UiMode.Material -> AlertDialog(
                onDismissRequest = skipOnce,
                text = { Text(stringResource(R.string.optimize_dex_after_system_update)) },
                confirmButton = {
                    TextButton(onClick = optimizeNow) { Text(stringResource(android.R.string.ok)) }
                },
                // 同槽位两个按钮由 AlertDialogFlowRow 自动换行，不会溢出
                dismissButton = {
                    TextButton(onClick = skipOnce) {
                        Text(stringResource(R.string.ignore))
                    }
                    TextButton(onClick = skipThisUpdate) {
                        Text(stringResource(R.string.ignore_this_system_update))
                    }
                },
            )
        }
    }

    if (showOptimizeDex) {
        RestartMenuUtils.OptimizeDexDialog(
            context,
            RestartMenuUtils.buildScopeMaps(
                context, context.resources.getStringArray(R.array.xposed_scope)
            ),
            confirmFirst = true,
        ) { showOptimizeDex = false }
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
 * 主页系统信息缓存：以 OTA 版本为键，OTA 未变化时复用上一次读取的字段，
 * 避免每次进入主页都重新走一遍 AIDL 调用。
 */
private object DeviceInfoCache {
    private data class Snapshot(val ota: String?, val entries: List<DeviceInfoEntry>)

    @Volatile
    private var snapshot: Snapshot? = null

    val info: List<DeviceInfoEntry>? get() = snapshot?.entries

    fun get(otaVersion: String?): List<DeviceInfoEntry>? {
        // 空 OTA 不命中：读不到版本时若还复用旧快照，用户会看到「版本」一直是上次的旧值，
        // 而且新读到的真实值永远进不来（键恒为 ""）。
        if (otaVersion.isNullOrBlank()) return null
        return snapshot?.takeIf { it.ota == otaVersion }?.entries
    }

    fun put(otaVersion: String?, value: List<DeviceInfoEntry>) {
        // 同理：空键不入缓存，等拿到真实 OTA 再落
        if (otaVersion.isNullOrBlank()) return
        snapshot = Snapshot(otaVersion, value)
    }
}

/** 更新对话框状态机：更新日志 → 下载源 → 下载中 → 已下载 */
private sealed class UpdateStage {
    data class Changelog(val info: UpdateUtils.UpdateInfo) : UpdateStage()
    data class Source(val info: UpdateUtils.UpdateInfo, val apkFile: File) : UpdateStage()
    data class Downloaded(val info: UpdateUtils.UpdateInfo, val apkFile: File) : UpdateStage()
    data class Downloading(val apkFile: File, val url: String) : UpdateStage()
}
