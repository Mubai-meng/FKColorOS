package com.fr.colorostools.ui.compose.scopes.apps

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fr.colorostools.R
import com.fr.colorostools.hook.DeepLinkRule
import com.fr.colorostools.hook.FluidCloudConfig
import com.fr.colorostools.hook.FluidCloudPresets
import com.fr.colorostools.hook.FluidCloudRules
import com.fr.colorostools.hook.HookEnv
import com.fr.colorostools.hook.SeedlingRule
import com.fr.colorostools.ui.compose.components.PrefGroup
import com.fr.colorostools.ui.compose.components.PrefRow
import com.fr.colorostools.ui.compose.components.material.MaterialListItem
import com.fr.colorostools.ui.compose.components.material.MaterialSwitch
import com.fr.colorostools.ui.compose.components.material.MaterialTextField
import com.fr.colorostools.ui.compose.components.miuix.MiuixListItem
import com.fr.colorostools.ui.compose.scopes.ScopePageAction
import com.fr.colorostools.ui.compose.scopes.ScopePageSpec
import com.fr.colorostools.ui.theme.LocalUiMode
import com.fr.colorostools.ui.theme.UiMode
import com.fr.colorostools.utils.PrefState
import com.fr.colorostools.utils.SettingsPrefs
import com.fr.colorostools.utils.copyStr
import com.fr.colorostools.utils.showToast
import top.yukonga.miuix.kmp.basic.Switch as MiuixSwitch
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextField as MiuixTextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 流体云两功能的页面间内存会话。
 *
 * ScopeRoute 只带 pageKey/title/scrollKey，没有「编辑第几条规则」的传参能力；
 * 编辑器索引用这个会话对象传递（列表页点击时设 index + 预填表单，编辑器页读它）。
 * 表单字段本身也是快照状态：顶栏动作（保存/复制/粘贴）在事件期直接读写，
 * 无需经过 SharedPreferences 中转，也就没有临时键残留。
 */
object FluidCloudSession {

    /** 正在编辑的规则下标；-1 = 新建 */
    var editIndex by mutableStateOf(-1)

    /**
     * 功能二预设库折叠状态：15 条预设默认收起（全平铺会把用户自己的规则挤到屏幕外），
     * 点折叠头行展开。放会话对象里而非 remember —— 条目重建/重组后状态不丢。
     */
    var sdPresetsExpanded by mutableStateOf(false)

    val dl = DeeplinkForm()
    val sd = SeedlingForm()

    class DeeplinkForm {
        var enable by mutableStateOf(true)
        var sourceAppName by mutableStateOf("")
        var sourcePackageName by mutableStateOf("")
        var newAppName by mutableStateOf("")
        var newPackageName by mutableStateOf("")

        fun load(rule: DeepLinkRule?) {
            enable = rule?.enable ?: true
            sourceAppName = rule?.sourceAppName ?: ""
            sourcePackageName = rule?.sourcePackageName ?: ""
            newAppName = rule?.newAppName ?: ""
            newPackageName = rule?.newPackageName ?: ""
        }

        fun build(): DeepLinkRule = DeepLinkRule(
            enable = enable,
            sourceAppName = sourceAppName.trim(),
            sourcePackageName = sourcePackageName.trim(),
            newAppName = newAppName.trim(),
            newPackageName = newPackageName.trim(),
        )
    }

    class SeedlingForm {
        var enable by mutableStateOf(true)
        var name by mutableStateOf("")
        var uuid by mutableStateOf("")
        var platformType by mutableStateOf("")
        var supplier by mutableStateOf("CPS")
        var matchRegex by mutableStateOf("")
        var appName by mutableStateOf("")
        /** 每行一个包名的原始文本（编辑器里直接可编辑） */
        var appPackages by mutableStateOf("")
        var cardTitle by mutableStateOf("")
        var buttonName by mutableStateOf("打开")
        var title by mutableStateOf("")
        var smallIcon by mutableStateOf("")
        var largeIcon by mutableStateOf("")
        var downloadIcon by mutableStateOf("")
        var eventType by mutableStateOf("0")

        fun load(rule: SeedlingRule?) {
            enable = rule?.enable ?: true
            name = rule?.name ?: ""
            uuid = rule?.uuid ?: newUuid()
            platformType = rule?.platformType?.takeIf { it != 0 }?.toString() ?: ""
            supplier = rule?.supplier ?: "CPS"
            matchRegex = rule?.matchRegex ?: ""
            appName = rule?.appName ?: ""
            appPackages = rule?.appPackages?.joinToString("\n") ?: ""
            cardTitle = rule?.cardTitle ?: ""
            buttonName = rule?.buttonName ?: "打开"
            title = rule?.title ?: ""
            smallIcon = rule?.smallIcon ?: ""
            largeIcon = rule?.largeIcon ?: ""
            downloadIcon = rule?.downloadIcon ?: ""
            eventType = rule?.eventType?.toString() ?: "0"
        }

        fun build(): SeedlingRule = SeedlingRule(
            enable = enable,
            uuid = uuid.trim(),
            name = name.trim(),
            platformType = platformType.trim().toIntOrNull() ?: 0,
            supplier = supplier.trim(),
            matchRegex = matchRegex.trim(),
            appName = appName.trim(),
            appPackages = appPackages.split('\n', ',', ';').map { it.trim() }.filter { it.isNotBlank() },
            cardTitle = cardTitle.trim(),
            buttonName = buttonName.trim(),
            title = title.trim(),
            smallIcon = smallIcon.trim(),
            largeIcon = largeIcon.trim(),
            downloadIcon = downloadIcon.trim(),
            eventType = eventType.trim().toIntOrNull() ?: 0,
        )
    }
}

private fun newUuid(): String = runCatching { java.util.UUID.randomUUID().toString().uppercase() }
    .getOrDefault("A1B2C3D4-E5F6-7890-ABCD-EF0123456789")

// ---------------- 规则读写（SettingsPrefs：App 侧 state.set 写、hook 侧 HookEnv.settingString 读，同源） ----------------

private fun readDeeplinkRules(state: PrefState): List<DeepLinkRule> =
    FluidCloudRules.deeplinkFromJson(state.getString(FluidCloudConfig.KEY_REPLACE_RULES, ""))

private fun readSeedlingRules(state: PrefState): List<SeedlingRule> =
    FluidCloudRules.seedlingFromJson(state.getString(FluidCloudConfig.KEY_CUSTOM_RULES, ""))

/**
 * 写规则并强制刷新列表。
 *
 * [PrefState.set] 本身会在监听器就绪时 bump revision；这里再显式 ++ 一次是双保险 ——
 * remote prefs（libxposed）的跨进程变更回调未打通时，列表页仍能立即重建。
 */
private fun PrefState.putRules(key: String, json: String) {
    set(key, json)
    revision.value++
}

/** 新规则平台类型：> 10000 且与现有规则不重复（hook 注入端按 platformType 去重） */
private fun nextPlatformType(rules: List<SeedlingRule>): Int =
    (rules.maxOfOrNull { it.platformType } ?: 10000).coerceAtLeast(10000) + 1

private fun Context.clipText(): String? = try {
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text
        ?.takeIf { it.isNotBlank() }?.toString()
} catch (_: Throwable) {
    null
}

// ---------------- 两主题线通用的行原子 ----------------

/** 规则行：行体点击进编辑器，尾部开关单独启停该条（material / Miuix 双线分派） */
@Composable
private fun FluidRuleRow(
    title: String,
    summary: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    if (LocalUiMode.current == UiMode.Miuix) {
        MiuixListItem(
            title = title,
            summary = summary,
            onClick = onClick,
            endActions = { MiuixSwitch(checked = checked, onCheckedChange = onToggle) },
        )
    } else {
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            MaterialListItem(
                onClick = onClick,
                headlineContent = { Text(title) },
                supportingContent = { Text(summary) },
                trailingContent = {
                    MaterialSwitch(checked = checked, onCheckedChange = onToggle)
                },
            )
        }
    }
}

/**
 * 表单字段行（编辑器用）：material 线用 [MaterialTextField]（自带标签 + 输入框的行），
 * Miuix 线用「标签 Text + miuix TextField」两段式，均含 16dp 水平内缩（对齐 DSL 行）。
 */
@Composable
private fun FluidTextFieldRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    numeric: Boolean = false,
    multiline: Boolean = false,
) {
    val keyboardOptions =
        if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default
    if (LocalUiMode.current == UiMode.Miuix) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            MiuixText(
                text = label,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                style = MiuixTheme.textStyles.subtitle.copy(fontWeight = FontWeight.Medium),
                fontSize = 14.sp,
            )
            MiuixTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.padding(top = 4.dp),
                label = "",
                useLabelAsPlaceholder = false,
                keyboardOptions = keyboardOptions,
                maxLines = if (multiline) 4 else 1,
            )
        }
    } else {
        MaterialTextField(
            label = label,
            value = value,
            onValueChange = onValueChange,
            singleLine = !multiline,
            minLines = if (multiline) 2 else 1,
            keyboardOptions = keyboardOptions,
        )
    }
}

/**
 * `custom(standalone = true)` 卡片内的多行渲染（双线分派，避免 Miuix 线「卡中卡」）：
 * - material 线：外层 MaterialItem 只提供形状上下文、不画底色，故走 [PrefGroup]
 *   （每行 ListItem 自绘 surfaceBright + 组内连续圆角 = 一张连续卡片，与 DSL 行一致）；
 * - Miuix 线：外层 MiuixScopeCard 本身已是卡片 Surface，行必须直接渲染
 *   （再套 PrefGroup 会每行再画一个 Surface，形成双重底色 + 组间距空带）。
 */
@Composable
private fun FluidCardRows(rows: List<@Composable () -> Unit>) {
    if (LocalUiMode.current == UiMode.Miuix) {
        Column(modifier = Modifier.fillMaxWidth()) {
            rows.forEach { row -> row() }
        }
    } else {
        PrefGroup {
            rows.forEachIndexed { index, row ->
                item(key = index) { row() }
            }
        }
    }
}

// ---------------- 页面：oplus_metis（功能树一级节点） ----------------

/** 智慧决策服务（流体云引擎宿主）：两个流体云功能的入口节点 */
val OplusMetisSpec = ScopePageSpec(
    pageKey = "oplus_metis",
    prefsName = SettingsPrefs,
    packName = HookEnv.PKG_METIS,
    scopes = arrayOf(HookEnv.PKG_METIS),
    restartEnabled = true,
) {
    val ctx = context ?: return@ScopePageSpec
    val replaceCount = readDeeplinkRules(state).size
    val customCount = readSeedlingRules(state).size
    page(
        title = ctx.getString(R.string.fluid_replace_title),
        target = "fluid_replace",
        summary = ctx.getString(R.string.fluid_rule_count, replaceCount),
    )
    page(
        title = ctx.getString(R.string.fluid_custom_title),
        target = "fluid_custom",
        summary = ctx.getString(R.string.fluid_rule_count, customCount),
    )
}

// ---------------- 页面：fluid_replace（功能一 · 二级） ----------------

val FluidReplaceSpec = ScopePageSpec(
    pageKey = "fluid_replace",
    prefsName = SettingsPrefs,
    packName = HookEnv.PKG_METIS,
    scopes = arrayOf(HookEnv.PKG_METIS),
    restartEnabled = true,
) {
    val ctx = context ?: return@ScopePageSpec
    switch(
        key = FluidCloudConfig.KEY_REPLACE_ENABLED,
        title = ctx.getString(R.string.fluid_cloud_enable),
        summary = ctx.getString(R.string.fluid_replace_switch_summary),
        requiredScopes = arrayOf(HookEnv.PKG_METIS),
    )
    val rules = readDeeplinkRules(state)
    page(
        title = ctx.getString(R.string.fluid_replace_list_title),
        target = "fluid_replace_list",
        summary = ctx.getString(R.string.fluid_rule_count, rules.size),
    )
    category(ctx.getString(R.string.fluid_backup_section))
    click(
        title = ctx.getString(R.string.fluid_export),
        onClick = {
            if (rules.isEmpty()) {
                ctx.showToast(ctx.getString(R.string.fluid_export_empty))
            } else {
                ctx.copyStr(FluidCloudRules.deeplinkToJson(rules))
                ctx.showToast(ctx.getString(R.string.fluid_export_done, rules.size))
            }
        },
    )
    click(
        title = ctx.getString(R.string.fluid_import),
        onClick = {
            val text = ctx.clipText()
            val imported = text?.let { FluidCloudRules.deeplinkFromJson(it) }.orEmpty()
            when {
                text == null -> ctx.showToast(ctx.getString(R.string.fluid_import_empty))
                imported.isEmpty() -> ctx.showToast(ctx.getString(R.string.fluid_import_empty))
                else -> {
                    state.putRules(FluidCloudConfig.KEY_REPLACE_RULES, FluidCloudRules.deeplinkToJson(imported))
                    ctx.showToast(ctx.getString(R.string.fluid_import_done, imported.size))
                }
            }
        },
    )
}

// ---------------- 页面：fluid_replace_list（功能一 · 三级：预设 + 规则列表） ----------------

val FluidReplaceListSpec = ScopePageSpec(
    pageKey = "fluid_replace_list",
    prefsName = SettingsPrefs,
    packName = HookEnv.PKG_METIS,
    scopes = arrayOf(HookEnv.PKG_METIS),
    restartEnabled = true,
) {
    val ctx = context ?: return@ScopePageSpec
    val rules = readDeeplinkRules(state)

    val presetRows: List<@Composable () -> Unit> = FluidCloudPresets.DEEPLINK.map { preset ->
        {
            PrefRow(
                title = ctx.getString(R.string.fluid_preset_add, preset.label),
                onClick = {
                    val cur = readDeeplinkRules(state).toMutableList()
                    if (cur.any { it.sourcePackageName == preset.sourcePackageName }) {
                        ctx.showToast(ctx.getString(R.string.fluid_preset_exists, preset.label))
                    } else {
                        cur += FluidCloudPresets.deeplinkPresetRule(preset)
                        state.putRules(
                            FluidCloudConfig.KEY_REPLACE_RULES,
                            FluidCloudRules.deeplinkToJson(cur),
                        )
                        ctx.showToast(ctx.getString(R.string.fluid_preset_added, preset.label))
                    }
                },
            )
        }
    }
    custom(key = "dl_presets", standalone = true) {
        FluidCardRows(presetRows)
    }

    val unset = ctx.getString(R.string.fluid_rule_unset)
    val ruleRows: List<@Composable () -> Unit> = buildList {
        if (rules.isEmpty()) {
            add {
                PrefRow(title = ctx.getString(R.string.fluid_rules_empty), enabled = false)
            }
        }
        rules.forEachIndexed { index, rule ->
            add {
                FluidRuleRow(
                    title = "${rule.sourceAppName} → ${rule.newAppName.ifBlank { unset }}",
                    summary = "${rule.sourcePackageName} → ${rule.newPackageName.ifBlank { unset }}",
                    checked = rule.enable,
                    onToggle = { checked ->
                        val cur = readDeeplinkRules(state).toMutableList()
                        if (index in cur.indices) {
                            cur[index] = cur[index].copy(enable = checked)
                            state.putRules(
                                FluidCloudConfig.KEY_REPLACE_RULES,
                                FluidCloudRules.deeplinkToJson(cur),
                            )
                        }
                    },
                    onClick = {
                        FluidCloudSession.editIndex = index
                        FluidCloudSession.dl.load(rule)
                        navigate?.invoke("fluid_replace_edit", ctx.getString(R.string.fluid_dl_edit_title))
                    },
                )
            }
        }
        add {
            PrefRow(
                title = ctx.getString(R.string.fluid_dl_add_rule),
                onClick = {
                    FluidCloudSession.editIndex = -1
                    FluidCloudSession.dl.load(null)
                    navigate?.invoke("fluid_replace_edit", ctx.getString(R.string.fluid_dl_edit_title))
                },
            )
        }
    }
    custom(key = "dl_rules", standalone = true) {
        FluidCardRows(ruleRows)
    }
}

// ---------------- 页面：fluid_replace_edit（功能一 · 四级：规则编辑器） ----------------

private const val ACTION_SAVE = "save"
private const val ACTION_DELETE = "delete"
private const val ACTION_COPY = "copy"
private const val ACTION_PASTE = "paste"

private val FluidReplaceEditSpec: ScopePageSpec = ScopePageSpec(
    pageKey = "fluid_replace_edit",
    prefsName = SettingsPrefs,
    packName = HookEnv.PKG_METIS,
    scopes = arrayOf(HookEnv.PKG_METIS),
    restartEnabled = true,
    topActions = listOf(
        ScopePageAction(ACTION_SAVE, R.string.fluid_save, R.drawable.ic_baseline_save_24, showAsAction = true),
        ScopePageAction(ACTION_DELETE, R.string.fluid_delete),
        ScopePageAction(ACTION_COPY, R.string.fluid_copy),
        ScopePageAction(ACTION_PASTE, R.string.fluid_paste),
    ),
) {
    val ctx = context ?: return@ScopePageSpec

    // 顶栏动作（事件期读取本页 state 与内存表单；范式同 refresh）
    FluidReplaceEditSpec.actionHandler = { key ->
        val index = FluidCloudSession.editIndex
        val rules = readDeeplinkRules(state)
        when (key) {
            ACTION_SAVE -> {
                val rule = FluidCloudSession.dl.build()
                if (rule.sourcePackageName.isBlank() || rule.newPackageName.isBlank()) {
                    ctx.showToast(ctx.getString(R.string.fluid_dl_invalid))
                } else {
                    val cur = rules.toMutableList()
                    if (index in cur.indices) cur[index] = rule else cur += rule
                    state.putRules(FluidCloudConfig.KEY_REPLACE_RULES, FluidCloudRules.deeplinkToJson(cur))
                    ctx.showToast(ctx.getString(R.string.fluid_saved))
                    FluidReplaceEditSpec.pageBackHandler?.invoke()
                }
            }

            ACTION_DELETE -> {
                if (index in rules.indices) {
                    val cur = rules.toMutableList()
                    cur.removeAt(index)
                    state.putRules(FluidCloudConfig.KEY_REPLACE_RULES, FluidCloudRules.deeplinkToJson(cur))
                    ctx.showToast(ctx.getString(R.string.fluid_deleted))
                    FluidReplaceEditSpec.pageBackHandler?.invoke()
                }
            }

            ACTION_COPY -> {
                ctx.copyStr(FluidCloudRules.deeplinkToJson(listOf(FluidCloudSession.dl.build())))
                ctx.showToast(ctx.getString(R.string.fluid_copied))
            }

            ACTION_PASTE -> {
                val rule = ctx.clipText()
                    ?.let { FluidCloudRules.deeplinkFromJson(it).firstOrNull() }
                if (rule == null) {
                    ctx.showToast(ctx.getString(R.string.fluid_import_empty))
                } else {
                    FluidCloudSession.dl.load(rule)
                    ctx.showToast(ctx.getString(R.string.fluid_pasted))
                }
            }
        }
    }

    val form = FluidCloudSession.dl
    val fieldRows: List<@Composable () -> Unit> = listOf(
        {
            FluidTextFieldRow(
                label = ctx.getString(R.string.fluid_dl_field_src_name),
                value = form.sourceAppName,
                onValueChange = { form.sourceAppName = it },
            )
        },
        {
            FluidTextFieldRow(
                label = ctx.getString(R.string.fluid_dl_field_src_pkg),
                value = form.sourcePackageName,
                onValueChange = { form.sourcePackageName = it },
            )
        },
        {
            FluidTextFieldRow(
                label = ctx.getString(R.string.fluid_dl_field_new_name),
                value = form.newAppName,
                onValueChange = { form.newAppName = it },
            )
        },
        {
            FluidTextFieldRow(
                label = ctx.getString(R.string.fluid_dl_field_new_pkg),
                value = form.newPackageName,
                onValueChange = { form.newPackageName = it },
            )
        },
    )
    custom(key = "dl_form", standalone = true) {
        FluidCardRows(fieldRows)
    }
}

// ---------------- 页面：fluid_custom（功能二 · 二级） ----------------

val FluidCustomSpec = ScopePageSpec(
    pageKey = "fluid_custom",
    prefsName = SettingsPrefs,
    packName = HookEnv.PKG_METIS,
    scopes = arrayOf(HookEnv.PKG_METIS),
    restartEnabled = true,
) {
    val ctx = context ?: return@ScopePageSpec
    switch(
        key = FluidCloudConfig.KEY_CUSTOM_ENABLED,
        title = ctx.getString(R.string.fluid_cloud_enable),
        summary = ctx.getString(R.string.fluid_custom_switch_summary),
        requiredScopes = arrayOf(HookEnv.PKG_METIS),
    )
    val rules = readSeedlingRules(state)
    page(
        title = ctx.getString(R.string.fluid_custom_list_title),
        target = "fluid_custom_list",
        summary = ctx.getString(R.string.fluid_rule_count, rules.size),
    )
    category(ctx.getString(R.string.fluid_backup_section))
    click(
        title = ctx.getString(R.string.fluid_export),
        onClick = {
            if (rules.isEmpty()) {
                ctx.showToast(ctx.getString(R.string.fluid_export_empty))
            } else {
                ctx.copyStr(FluidCloudRules.seedlingToJson(rules))
                ctx.showToast(ctx.getString(R.string.fluid_export_done, rules.size))
            }
        },
    )
    click(
        title = ctx.getString(R.string.fluid_import),
        onClick = {
            val text = ctx.clipText()
            val imported = text?.let { FluidCloudRules.seedlingFromJson(it) }.orEmpty()
            when {
                text == null -> ctx.showToast(ctx.getString(R.string.fluid_import_empty))
                imported.isEmpty() -> ctx.showToast(ctx.getString(R.string.fluid_import_empty))
                else -> {
                    state.putRules(FluidCloudConfig.KEY_CUSTOM_RULES, FluidCloudRules.seedlingToJson(imported))
                    ctx.showToast(ctx.getString(R.string.fluid_import_done, imported.size))
                }
            }
        },
    )
}

// ---------------- 页面：fluid_custom_list（功能二 · 三级：预设库[默认折叠] + 规则列表） ----------------

val FluidCustomListSpec = ScopePageSpec(
    pageKey = "fluid_custom_list",
    prefsName = SettingsPrefs,
    packName = HookEnv.PKG_METIS,
    scopes = arrayOf(HookEnv.PKG_METIS),
    restartEnabled = true,
) {
    val ctx = context ?: return@ScopePageSpec
    val rules = readSeedlingRules(state)

    val presetRows: List<@Composable () -> Unit> = buildList {
        // 折叠头：15 条预设全平铺会把用户自己的规则挤到很下面，默认只显示一行入口
        add {
            PrefRow(
                title = ctx.getString(R.string.fluid_presets_header, FluidCloudPresets.SEEDLING.size),
                summary = ctx.getString(
                    if (FluidCloudSession.sdPresetsExpanded) R.string.fluid_presets_collapse
                    else R.string.fluid_presets_expand
                ),
                onClick = { FluidCloudSession.sdPresetsExpanded = !FluidCloudSession.sdPresetsExpanded },
            )
        }
        if (FluidCloudSession.sdPresetsExpanded) {
            FluidCloudPresets.SEEDLING.forEach { preset ->
                add {
                    PrefRow(
                        title = ctx.getString(R.string.fluid_preset_add, preset.name),
                        onClick = {
                            val cur = readSeedlingRules(state).toMutableList()
                            if (cur.any { it.name == preset.name }) {
                                ctx.showToast(ctx.getString(R.string.fluid_preset_exists, preset.name))
                            } else {
                                cur += FluidCloudPresets.seedlingPresetRule(preset, 0)
                                    .copy(platformType = nextPlatformType(cur))
                                state.putRules(
                                    FluidCloudConfig.KEY_CUSTOM_RULES,
                                    FluidCloudRules.seedlingToJson(cur),
                                )
                                ctx.showToast(ctx.getString(R.string.fluid_preset_added, preset.name))
                            }
                        },
                    )
                }
            }
        }
    }
    custom(key = "sd_presets", standalone = true) {
        FluidCardRows(presetRows)
    }

    val unnamed = ctx.getString(R.string.fluid_rule_unnamed)
    val ruleRows: List<@Composable () -> Unit> = buildList {
        if (rules.isEmpty()) {
            add {
                PrefRow(title = ctx.getString(R.string.fluid_rules_empty), enabled = false)
            }
        }
        rules.forEachIndexed { index, rule ->
            add {
                FluidRuleRow(
                    title = rule.name.ifBlank { unnamed },
                    summary = ctx.getString(
                        R.string.fluid_sd_rule_summary,
                        rule.platformType,
                        rule.appName,
                    ),
                    checked = rule.enable,
                    onToggle = { checked ->
                        val cur = readSeedlingRules(state).toMutableList()
                        if (index in cur.indices) {
                            cur[index] = cur[index].copy(enable = checked)
                            state.putRules(
                                FluidCloudConfig.KEY_CUSTOM_RULES,
                                FluidCloudRules.seedlingToJson(cur),
                            )
                        }
                    },
                    onClick = {
                        FluidCloudSession.editIndex = index
                        FluidCloudSession.sd.load(rule)
                        navigate?.invoke("fluid_custom_edit", ctx.getString(R.string.fluid_sd_edit_title))
                    },
                )
            }
        }
        add {
            PrefRow(
                title = ctx.getString(R.string.fluid_sd_add_rule),
                onClick = {
                    FluidCloudSession.editIndex = -1
                    FluidCloudSession.sd.load(null)
                    // 新建时预分配一个不冲突的平台类型（hook 注入端按 platformType 去重）
                    FluidCloudSession.sd.platformType = nextPlatformType(rules).toString()
                    navigate?.invoke("fluid_custom_edit", ctx.getString(R.string.fluid_sd_edit_title))
                },
            )
        }
    }
    custom(key = "sd_rules", standalone = true) {
        FluidCardRows(ruleRows)
    }
}

// ---------------- 页面：fluid_custom_edit（功能二 · 四级：规则编辑器） ----------------

private val FluidCustomEditSpec: ScopePageSpec = ScopePageSpec(
    pageKey = "fluid_custom_edit",
    prefsName = SettingsPrefs,
    packName = HookEnv.PKG_METIS,
    scopes = arrayOf(HookEnv.PKG_METIS),
    restartEnabled = true,
    topActions = listOf(
        ScopePageAction(ACTION_SAVE, R.string.fluid_save, R.drawable.ic_baseline_save_24, showAsAction = true),
        ScopePageAction(ACTION_DELETE, R.string.fluid_delete),
        ScopePageAction(ACTION_COPY, R.string.fluid_copy),
        ScopePageAction(ACTION_PASTE, R.string.fluid_paste),
    ),
) {
    val ctx = context ?: return@ScopePageSpec

    FluidCustomEditSpec.actionHandler = { key ->
        val index = FluidCloudSession.editIndex
        val rules = readSeedlingRules(state)
        when (key) {
            ACTION_SAVE -> {
                val rule = FluidCloudSession.sd.build()
                when {
                    rule.name.isBlank() || rule.matchRegex.isBlank() || rule.appPackages.isEmpty() ->
                        ctx.showToast(ctx.getString(R.string.fluid_sd_invalid))

                    runCatching { Regex(rule.matchRegex) }.isFailure ->
                        ctx.showToast(ctx.getString(R.string.fluid_sd_invalid_regex))

                    rule.platformType <= 0 ->
                        ctx.showToast(ctx.getString(R.string.fluid_sd_invalid_platform))

                    rule.eventType < 0 ->
                        ctx.showToast(ctx.getString(R.string.fluid_sd_invalid_event))

                    else -> {
                        val cur = rules.toMutableList()
                        if (cur.anyIndexed { i, it -> i != index && it.uuid == rule.uuid }) {
                            ctx.showToast(ctx.getString(R.string.fluid_sd_dup_uuid))
                        }
                        if (rule.platformType < 10000) {
                            ctx.showToast(ctx.getString(R.string.fluid_sd_platform_low))
                        }
                        if (index in cur.indices) cur[index] = rule else cur += rule
                        state.putRules(FluidCloudConfig.KEY_CUSTOM_RULES, FluidCloudRules.seedlingToJson(cur))
                        ctx.showToast(ctx.getString(R.string.fluid_saved))
                        FluidCustomEditSpec.pageBackHandler?.invoke()
                    }
                }
            }

            ACTION_DELETE -> {
                if (index in rules.indices) {
                    val cur = rules.toMutableList()
                    cur.removeAt(index)
                    state.putRules(FluidCloudConfig.KEY_CUSTOM_RULES, FluidCloudRules.seedlingToJson(cur))
                    ctx.showToast(ctx.getString(R.string.fluid_deleted))
                    FluidCustomEditSpec.pageBackHandler?.invoke()
                }
            }

            ACTION_COPY -> {
                ctx.copyStr(FluidCloudRules.seedlingToJson(listOf(FluidCloudSession.sd.build())))
                ctx.showToast(ctx.getString(R.string.fluid_copied))
            }

            ACTION_PASTE -> {
                val rule = ctx.clipText()
                    ?.let { FluidCloudRules.seedlingFromJson(it).firstOrNull() }
                if (rule == null) {
                    ctx.showToast(ctx.getString(R.string.fluid_import_empty))
                } else {
                    FluidCloudSession.sd.load(rule)
                    ctx.showToast(ctx.getString(R.string.fluid_pasted))
                }
            }
        }
    }

    val form = FluidCloudSession.sd
    val fieldRows: List<@Composable () -> Unit> = listOf(
        {
            FluidTextFieldRow(
                label = ctx.getString(R.string.fluid_sd_field_name),
                value = form.name,
                onValueChange = { form.name = it },
            )
        },
        {
            FluidTextFieldRow(
                label = ctx.getString(R.string.fluid_sd_field_uuid),
                value = form.uuid,
                onValueChange = { form.uuid = it },
            )
        },
        {
            FluidTextFieldRow(
                label = ctx.getString(R.string.fluid_sd_field_platform),
                value = form.platformType,
                onValueChange = { form.platformType = it },
                numeric = true,
            )
        },
        {
            FluidTextFieldRow(
                label = ctx.getString(R.string.fluid_sd_field_supplier),
                value = form.supplier,
                onValueChange = { form.supplier = it },
            )
        },
        {
            FluidTextFieldRow(
                label = ctx.getString(R.string.fluid_sd_field_regex),
                value = form.matchRegex,
                onValueChange = { form.matchRegex = it },
                multiline = true,
            )
        },
        {
            FluidTextFieldRow(
                label = ctx.getString(R.string.fluid_sd_field_app_name),
                value = form.appName,
                onValueChange = { form.appName = it },
            )
        },
        {
            FluidTextFieldRow(
                label = ctx.getString(R.string.fluid_sd_field_app_pkgs),
                value = form.appPackages,
                onValueChange = { form.appPackages = it },
                multiline = true,
            )
        },
        {
            FluidTextFieldRow(
                label = ctx.getString(R.string.fluid_sd_field_card_title),
                value = form.cardTitle,
                onValueChange = { form.cardTitle = it },
            )
        },
        {
            FluidTextFieldRow(
                label = ctx.getString(R.string.fluid_sd_field_button),
                value = form.buttonName,
                onValueChange = { form.buttonName = it },
            )
        },
        {
            FluidTextFieldRow(
                label = ctx.getString(R.string.fluid_sd_field_title),
                value = form.title,
                onValueChange = { form.title = it },
            )
        },
        {
            FluidTextFieldRow(
                label = ctx.getString(R.string.fluid_sd_field_small_icon),
                value = form.smallIcon,
                onValueChange = { form.smallIcon = it },
            )
        },
        {
            FluidTextFieldRow(
                label = ctx.getString(R.string.fluid_sd_field_large_icon),
                value = form.largeIcon,
                onValueChange = { form.largeIcon = it },
            )
        },
        {
            FluidTextFieldRow(
                label = ctx.getString(R.string.fluid_sd_field_download_icon),
                value = form.downloadIcon,
                onValueChange = { form.downloadIcon = it },
            )
        },
        {
            FluidTextFieldRow(
                label = ctx.getString(R.string.fluid_sd_field_event_type),
                value = form.eventType,
                onValueChange = { form.eventType = it },
                numeric = true,
            )
        },
    )
    custom(key = "sd_form", standalone = true) {
        FluidCardRows(fieldRows)
    }
}

private inline fun <T> List<T>.anyIndexed(predicate: (Int, T) -> Boolean): Boolean {
    forEachIndexed { i, v -> if (predicate(i, v)) return true }
    return false
}

/** 全部流体云页面（由 ScopePageRegistry.init 注册） */
val fluidCloudSpecs: List<ScopePageSpec> = listOf(
    OplusMetisSpec,
    FluidReplaceSpec,
    FluidReplaceListSpec,
    FluidReplaceEditSpec,
    FluidCustomSpec,
    FluidCustomListSpec,
    FluidCustomEditSpec,
)
