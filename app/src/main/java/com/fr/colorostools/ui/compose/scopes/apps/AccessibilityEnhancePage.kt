package com.fr.colorostools.ui.compose.scopes.apps

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fr.colorostools.R
import com.fr.colorostools.hook.HookEnv
import com.fr.colorostools.hook.hookers.AccessibilityConfig
import com.fr.colorostools.ui.compose.components.AppPickerDialog
import com.fr.colorostools.ui.compose.scopes.ScopePageSpec
import com.fr.colorostools.utils.PrefState
import com.fr.colorostools.utils.SettingsPrefs

/**
 * 页面间内存会话：白名单选择对话框可见性。
 *
 * 放会话对象而非 remember —— DSL 内容 lambda 每次重组都会重跑，
 * remember 在条目重建/重组后状态会丢（范式同 [FluidCloudSession]）。
 */
private object AccessibilitySession {
    var showWhitelistPicker by mutableStateOf(false)
}

/** 读白名单（逗号分隔包名串 → 列表；hook 侧 [com.fr.colorostools.hook.hookers.AccessibilityEnhanceHook] 同键读） */
private fun readWhitelist(state: PrefState): List<String> =
    state.getString(AccessibilityConfig.KEY_WHITELIST, "")
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        .orEmpty()

/** 写白名单并强制刷新（revision 显式 ++ 兜底：remote prefs 跨进程回调未打通时列表仍立即重建） */
private fun writeWhitelist(state: PrefState, list: List<String>) {
    state.set(AccessibilityConfig.KEY_WHITELIST, list.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(","))
    state.revision.value++
}

/**
 * 无障碍增强（宿主 com.android.settings）—— 功能树一级节点即功能页。
 *
 * 四项开关 + 智能授权白名单入口。原理与生效条件见
 * `docs/无障碍增强-方案评估.md` 与
 * [com.fr.colorostools.hook.hookers.AccessibilityEnhanceHook]。
 *
 * 白名单用 [AppPickerDialog] 维护（多选 + 搜索 + 排序，自带列表回收与异步加载），
 * 确认时回传**完整勾选集合**（confirmAllSelected = true）——
 * 避免用户挂着搜索条件点确认时，被过滤掉的既有白名单条目被静默丢弃。
 */
val AndroidSettingsSpec = ScopePageSpec(
    pageKey = "android_settings",
    prefsName = SettingsPrefs,
    packName = HookEnv.PKG_SETTINGS,
    scopes = arrayOf(HookEnv.PKG_SETTINGS),
    restartEnabled = true,
) {
    val ctx = context ?: return@ScopePageSpec

    switch(
        key = AccessibilityConfig.KEY_DIRECT_AUTH,
        title = ctx.getString(R.string.a11y_direct_auth),
        summary = ctx.getString(R.string.a11y_direct_auth_summary),
        // 作用域门禁：打开开关前自动检测 com.android.settings 是否在作用域内，缺失则弹窗补全
        requiredScopes = arrayOf(HookEnv.PKG_SETTINGS),
    )
    switch(
        key = AccessibilityConfig.KEY_JUMP,
        title = ctx.getString(R.string.a11y_jump),
        summary = ctx.getString(R.string.a11y_jump_summary),
    )
    switch(
        key = AccessibilityConfig.KEY_AUTO_AUTH,
        title = ctx.getString(R.string.a11y_auto_auth),
        summary = ctx.getString(R.string.a11y_auto_auth_summary),
    )
    switch(
        key = AccessibilityConfig.KEY_RESTART,
        title = ctx.getString(R.string.a11y_restart),
        summary = ctx.getString(R.string.a11y_restart_summary),
    )

    // 白名单入口：仅「智能授权」开启时有意义（关闭时置灰，语义同原项目 NavRow.enabled）
    val autoAuth = state.getBoolean(AccessibilityConfig.KEY_AUTO_AUTH, false)
    val whitelist = readWhitelist(state)
    click(
        title = ctx.getString(R.string.a11y_whitelist_title),
        summary = ctx.getString(R.string.a11y_whitelist_summary, whitelist.size),
        enabled = autoAuth,
        onClick = { AccessibilitySession.showWhitelistPicker = true },
    )

    // 白名单选择对话框（bare = 无卡片外壳；不可见时不渲染任何内容）
    custom(key = "a11y_whitelist_picker", bare = true) {
        if (AccessibilitySession.showWhitelistPicker) {
            AppPickerDialog(
                title = ctx.getString(R.string.a11y_whitelist_title),
                multiMode = true,
                enabledList = whitelist.toSet(),
                confirmAllSelected = true,
                onDismiss = { AccessibilitySession.showWhitelistPicker = false },
                onConfirm = { list ->
                    AccessibilitySession.showWhitelistPicker = false
                    writeWhitelist(state, list.map { it.packageName })
                },
            )
        }
    }
}
