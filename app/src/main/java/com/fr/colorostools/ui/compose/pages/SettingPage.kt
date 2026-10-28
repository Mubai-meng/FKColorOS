package com.fr.colorostools.ui.compose.pages

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.util.ArraySet
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DisplaySettings
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fr.colorostools.R
import com.fr.colorostools.ui.activity.MainActivity
import com.fr.colorostools.ui.compose.components.LocalScopeTopInset
import com.fr.colorostools.ui.compose.components.ScopeScreen
import com.fr.colorostools.ui.compose.components.PrefIcon
import com.fr.colorostools.ui.compose.components.material.MaterialPageScaffold
import com.fr.colorostools.ui.theme.LocalUiMode
import com.fr.colorostools.ui.theme.ThemeController
import com.fr.colorostools.ui.theme.ThemePrefs
import com.fr.colorostools.ui.theme.UiMode
import com.fr.colorostools.utils.AppUtils
import com.fr.colorostools.utils.BiometricUtils
import com.fr.colorostools.utils.FileUtils
import com.fr.colorostools.utils.IntentPrefs
import com.fr.colorostools.utils.IntentUtils
import com.fr.colorostools.utils.LogUtils
import com.fr.colorostools.utils.ModulePrefs
import com.fr.colorostools.utils.OtherPrefs
import com.fr.colorostools.utils.PrefState
import com.fr.colorostools.utils.SettingsPrefs
import com.fr.colorostools.utils.backupAllPrefs
import com.fr.colorostools.utils.base64Decode
import com.fr.colorostools.utils.base64Encode
import com.fr.colorostools.utils.clearAllPrefs
import com.fr.colorostools.utils.formatDate
import com.fr.colorostools.utils.getOSVersionCode
import com.fr.colorostools.utils.getOSVersionName
import com.fr.colorostools.utils.isZh
import com.fr.colorostools.utils.openUrl
import com.fr.colorostools.utils.putBoolean
import com.fr.colorostools.utils.putInt
import com.fr.colorostools.utils.putString
import com.fr.colorostools.utils.putStringSet
import com.fr.colorostools.utils.removeKey
import com.fr.colorostools.utils.showToast
import org.json.JSONArray
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import kotlin.system.exitProcess
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton

/**
 * 设置页（旧 SettingsFragment 的 Compose 等价实现）。
 * 主题 / 其他 / 备份恢复 / 关于四组设置，含备份恢复 JSON 逻辑与生物识别门控。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingPage(activity: MainActivity, onOpenTheme: () -> Unit = {}) {
    val context = LocalContext.current
    val settings = remember { PrefState.of(context, SettingsPrefs) }
    // 以 Configuration 作 key：语言切换后 isZh 需要重新求值（供后续本地化分支使用）
    val zh = remember(LocalConfiguration.current) { isZh(context) }
    var pendingRestoreJson by remember { mutableStateOf<JSONObject?>(null) }

    // 备份/恢复（旧 registerForActivityResult）
    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) writeBackupData(activity, uri) }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val entryData = FileUtils.readFromUri(activity, uri)
            val json = JSONObject(base64Decode(entryData))
            val osCode = json.optInt("osCode")
            if (osCode > 0 && osCode != getOSVersionCode) {
                // 旧 checkRestoreData：跨版本备份先提示，再恢复
                pendingRestoreJson = json
            } else {
                writeRestoreData(activity, json)
            }
        }
    }

    val deviceSecure = remember(context) {
        context.getSystemService(KeyguardManager::class.java).isDeviceSecure
    }

    var showClearDialog by remember { mutableStateOf(false) }

    MaterialPageScaffold(
        title = stringResource(R.string.nav_setting),
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
            ScopeScreen(
                state = settings,
                modifier = scopeModifier,
            ) {
                // ---------------- 主题 ----------------
                // 主题模式 / 主题色 / 调色风格 / 色彩规格 / 动态取色 全部迁至独立主题页
                group()
                // 界面风格切换（Material / Miuix）：放在设置页而不是主题页，
                // 保证切到 Miuix 后仍能在这里切回 Material。界面风格在上，主题入口在下。
                list(
                    key = ThemeController.KEY_UI_MODE,
                    title = context.getString(R.string.settings_ui_mode),
                    entries = UiMode.entries.map { it.name }.toTypedArray(),
                    entryValues = UiMode.entries.map { it.value }.toTypedArray(),
                    default = UiMode.DEFAULT_VALUE,
                    summary = context.getString(R.string.settings_ui_mode_summary),
                    leading = { PrefIcon(Icons.Rounded.DisplaySettings) },
                    onChange = { ThemePrefs.notifyChanged() },
                )
                // 导航入口用箭头，不暴露内部色板名称。
                click(
                    title = context.getString(R.string.theme_palette),
                    summary = context.getString(R.string.theme_palette_summary),
                    leading = { PrefIcon(Icons.Rounded.Palette) },
                    value = "",
                    trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) },
                    onClick = onOpenTheme,
                )

                // ---------------- 其他 ----------------
                group()
                switch(
                    key = "auto_check_update",
                    title = context.getString(R.string.auto_check_update),
                    summary = context.getString(R.string.auto_check_update_summary),
                    // 与其他设置一致：默认关闭，由用户自行开启（HomePage 的读取默认值必须与此处相同）
                    default = false,
                    leading = { PrefIcon(Icons.Rounded.SystemUpdate) },
                )
                if (deviceSecure) {
                    switch(
                        key = "enable_biometric_unlock_verification",
                        title = context.getString(R.string.enable_biometric_unlock_verification),
                        default = false,
                        leading = { PrefIcon(Icons.Rounded.Fingerprint) },
                        beforeApply = { enable, apply ->
                            if (enable) {
                                // 旧行为：验证通过才真正写入
                                BiometricUtils.showBiometricPrompt(
                                    activity,
                                    onSucceed = { apply(true) })
                            } else {
                                apply(false)
                            }
                        },
                    )
                }
                switch(
                    key = "tile_auto_start",
                    title = context.getString(R.string.tile_auto_start),
                    summary = context.getString(R.string.tile_auto_start_summary),
                    default = false,
                    leading = { PrefIcon(Icons.Rounded.Widgets) },
                )
                switch(
                    key = "enable_log",
                    title = context.getString(R.string.enable_log),
                    summary = context.getString(R.string.enable_log_summary),
                    default = false,
                    leading = { PrefIcon(Icons.Rounded.Description) },
                    onChange = { value -> LogUtils.enable = value },
                )
                switch(
                    key = "hide_desktop_module_icon",
                    title = context.getString(R.string.hide_desktop_module_icon),
                    summary = context.getString(R.string.hide_desktop_module_icon_summary),
                    default = false,
                    leading = { PrefIcon(Icons.Rounded.VisibilityOff) },
                    onChange = { value ->
                        AppUtils(context).setComponentDisabled(
                            ComponentName(
                                context.packageName,
                                "${context.packageName}.Hide",
                            ),
                            value,
                        )
                    },
                )

                // ---------------- 备份/恢复/清除 ----------------
                group()
                click(
                    title = context.getString(R.string.backup_data),
                    leading = { PrefIcon(Icons.Rounded.SaveAlt) },
                    onClick = {
                        FileUtils.checkDownloadDir(context, "ColorOSTools").apply {
                            if (isFile) delete()
                            if (!exists()) mkdirs()
                        }
                        val fileName =
                            "ColorOSTools_" + formatDate("yyyyMMdd_HHmmss") + "_backup.json"
                        if (IntentUtils(activity).checkCreateDocument()) {
                            backupLauncher.launch(fileName)
                        } else {
                            context.showToast("Intent Create Document Error!")
                        }
                    },
                )
                click(
                    title = context.getString(R.string.restore_data),
                    leading = { PrefIcon(Icons.Rounded.Restore) },
                    onClick = {
                        FileUtils.checkDownloadDir(context, "ColorOSTools").apply {
                            if (isFile) delete()
                            if (!exists()) mkdirs()
                        }
                        restoreLauncher.launch("application/json")
                    },
                )
                click(
                    title = context.getString(R.string.clear_all_data),
                    summary = context.getString(R.string.clear_all_data_summary),
                    leading = { PrefIcon(Icons.Rounded.Delete) },
                    onClick = { showClearDialog = true },
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



    if (showClearDialog) {
        when (LocalUiMode.current) {
            UiMode.Miuix -> OverlayDialog(
                show = true,
                onDismissRequest = { showClearDialog = false },
            ) {
                MiuixText(
                    text = stringResource(R.string.clear_all_data_message),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(modifier = Modifier.padding(top = 12.dp)) {
                    MiuixTextButton(
                        text = stringResource(android.R.string.cancel),
                        onClick = { showClearDialog = false },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(20.dp))
                    MiuixTextButton(
                        text = stringResource(android.R.string.ok),
                        onClick = {
                            showClearDialog = false
                            context.clearAllPrefs(
                                ModulePrefs,
                                IntentPrefs,
                                SettingsPrefs,
                                OtherPrefs
                            )
                            exitProcess(0)
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }

            UiMode.Material -> AlertDialog(
                onDismissRequest = { showClearDialog = false },
                text = { Text(stringResource(R.string.clear_all_data_message)) },
                confirmButton = {
                    TextButton(onClick = {
                        showClearDialog = false
                        context.clearAllPrefs(ModulePrefs, IntentPrefs, SettingsPrefs, OtherPrefs)
                        exitProcess(0)
                    }) { Text(stringResource(android.R.string.ok)) }
                },
                dismissButton = {
                    TextButton(onClick = { showClearDialog = false }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                },
            )
        }
    }


    pendingRestoreJson?.let { json ->
        val osCode = json.optInt("osCode")
        val restoreMessage = """
                    ${context.getString(R.string.data_backup_data_version)}: ${
            getOSVersionName(
                osCode
            )
        }
                    ${context.getString(R.string.data_current_system_version)}: $getOSVersionName
                    
                    ${context.getString(R.string.data_restore_version_tips)}
                    """.trimIndent()
        when (LocalUiMode.current) {
            UiMode.Miuix -> OverlayDialog(
                show = true,
                onDismissRequest = { pendingRestoreJson = null },
            ) {
                MiuixText(
                    text = restoreMessage,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(modifier = Modifier.padding(top = 12.dp)) {
                    MiuixTextButton(
                        text = stringResource(R.string.ignore),
                        onClick = {
                            pendingRestoreJson = null
                            writeRestoreData(activity, json)
                        },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(20.dp))
                    MiuixTextButton(
                        text = stringResource(android.R.string.ok),
                        onClick = { pendingRestoreJson = null },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }

            UiMode.Material -> AlertDialog(
                onDismissRequest = { pendingRestoreJson = null },
                text = { Text(restoreMessage) },
                confirmButton = {
                    TextButton(onClick = { pendingRestoreJson = null }) {
                        Text(stringResource(android.R.string.ok))
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        pendingRestoreJson = null
                        writeRestoreData(activity, json)
                    }) { Text(stringResource(R.string.ignore)) }
                },
            )
        }
    }
}

/** 旧 writeBackupData：JSON(osCode + 四个 prefs 文件) → base64 → 写入 uri */
private fun writeBackupData(context: Context, uri: Uri) {
    val json = JSONObject().apply { put("osCode", getOSVersionCode) }
    val dataMapList = context.backupAllPrefs(ModulePrefs, IntentPrefs, SettingsPrefs, OtherPrefs)
    dataMapList.keys.forEach { prefs ->
        val jsons = JSONObject()
        dataMapList[prefs]?.keys?.forEach { key ->
            val value = dataMapList[prefs]?.get(key)
            // 集合值一律写 JSONArray（不能只认 HashSet）：JSONObject.put 会把其它集合实现
            // （SharedPreferences 复制出的 android.util.ArraySet 等）兜底成 toString() 字符串。
            // 不认识的类型同样不能落到那个兜底分支：记录日志后跳过该键，绝不写成字符串。
            try {
                when (value) {
                    null -> Unit
                    is Set<*> -> {
                        val arr = JSONArray()
                        value.forEach { arr.put(it) }
                        jsons.put(key, arr)
                    }

                    is String, is Boolean, is Int, is Long, is Float, is Double -> jsons.put(
                        key,
                        value
                    )

                    else -> error("${value.javaClass.name} is not JSON-safe")
                }
            } catch (t: Throwable) {
                LogUtils.e("SettingPage", "backup skip $key", "$t", false)
            }
        }
        json.put(prefs, jsons)
    }
    val str = base64Encode(json.toString())
    try {
        context.contentResolver.openFileDescriptor(uri, "w")?.use { its ->
            FileOutputStream(its.fileDescriptor).use { it.write(str.toByteArray()) }
        }
        context.showToast(context.getString(R.string.data_backup_complete))
    } catch (e: FileNotFoundException) {
        e.printStackTrace()
        context.showToast(context.getString(R.string.data_backup_error))
    } catch (e: IOException) {
        e.printStackTrace()
        context.showToast(context.getString(R.string.data_backup_error))
    }
}

/** 旧 writeRestoreData：逐 prefs 逐键写回，完成后重启 Activity */
private fun writeRestoreData(context: Context, json: JSONObject) {
    if (json.length() <= 0) return
    json.remove("osCode")
    var success = 0
    var failed = 0
    val failedKeys = ArrayList<String>()
    json.keys().forEach { prefs ->
        val prefsDatas = json.getJSONObject(prefs)
        if (prefsDatas.length() > 0) {
            prefsDatas.keys().forEach { key ->
                val value = prefsDatas.get(key)
                try {
                    val ok = when (value.javaClass.simpleName) {
                        "Boolean" -> context.putBoolean(prefs, key, value as Boolean)
                        "Integer" -> context.putInt(prefs, key, value as Int)
                        "JSONArray" -> {
                            val set = ArraySet<String>()
                            val list = value as JSONArray
                            for (i in 0 until list.length()) set.add(list[i] as String)
                            context.putStringSet(prefs, key, set)
                        }

                        "String" -> context.putString(prefs, key, value as String)
                        else -> error("${value.javaClass.simpleName} is not restorable")
                    }
                    if (!ok) error("write failed")
                    success++
                } catch (t: Throwable) {
                    // 恢复不了的值：记录日志、跳过，并把该键从本地 prefs 移除，不让坏数据留在设备上
                    failed++
                    failedKeys.add("$prefs/$key")
                    LogUtils.e("SettingPage", "restore skip $prefs/$key", "$t", false)
                    context.removeKey(prefs, key)
                }
            }
        }
    }
    if (failed > 0) {
        LogUtils.e(
            "SettingPage",
            "restore result",
            "success=$success failed=$failed ${failedKeys.joinToString()}",
            false,
        )
    }
    context.showToast(context.getString(R.string.data_restore_result, success, failed))
    (context as? MainActivity)?.restart()
}
