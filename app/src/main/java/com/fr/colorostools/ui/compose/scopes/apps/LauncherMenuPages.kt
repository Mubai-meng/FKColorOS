package com.fr.colorostools.ui.compose.scopes.apps

import com.fr.colorostools.R
import com.fr.colorostools.hook.HookEnv
import com.fr.colorostools.hook.hookers.LauncherMenuConfig
import com.fr.colorostools.ui.compose.scopes.ScopePageSpec
import com.fr.colorostools.utils.SettingsPrefs

/**
 * 桌面长按菜单定制（宿主 `com.android.launcher`）—— 功能树一级节点即功能入口。
 *
 * 结构对齐上游 fkfluidcloud「桌面增强 → 长按菜单」两级：
 * - 本页（launcher_menu）：**功能总开关** + 三级页入口；
 * - 三级页（launcher_menu_detail）：菜单结构 3 项 + 批量隐藏 1 项 + 按项隐藏 6 项。
 *
 * 两层门控：
 * - 总开关 [LauncherMenuConfig.KEY_ENABLED] 关闭 ⇒ 三级页 10 个开关全部禁用（值保留）；
 * - 批量隐藏 [LauncherMenuConfig.KEY_HIDE_ALL] 打开 ⇒ 6 个按项开关禁用（语义 = 全部隐藏），
 *   与宿主「更多功能」那一栏的语义一致。
 *
 * 改完**立即生效**（已弹开的菜单需关掉重开）。hook 侧实现与方案评估：
 * [com.fr.colorostools.hook.hookers.LauncherMenuHook]、`docs/桌面长按菜单移植-方案评估.md`。
 */
val LauncherMenuSpec = ScopePageSpec(
    pageKey = "launcher_menu",
    prefsName = SettingsPrefs,
    packName = HookEnv.PKG_LAUNCHER,
    scopes = arrayOf(HookEnv.PKG_LAUNCHER),
    restartEnabled = true,
) {
    val ctx = context ?: return@ScopePageSpec

    switch(
        key = LauncherMenuConfig.KEY_ENABLED,
        title = ctx.getString(R.string.launcher_menu_enable),
        summary = ctx.getString(R.string.launcher_menu_enable_summary),
        // 作用域门禁：打开开关前自动检测 com.android.launcher 是否在作用域内，缺失则弹窗补全
        requiredScopes = arrayOf(HookEnv.PKG_LAUNCHER),
    )
    val featureOn = state.getBoolean(LauncherMenuConfig.KEY_ENABLED, false)
    page(
        title = ctx.getString(R.string.launcher_menu_detail_title),
        target = "launcher_menu_detail",
        summary = ctx.getString(R.string.launcher_menu_detail_summary),
        enabled = featureOn,
    )
}

/**
 * 三级页：**长按菜单**细分开关（10 项）。
 *
 * 两组门控见 [LauncherMenuSpec]；按项隐藏 6 项的 checked 在批量隐藏开启时恒显示为 true
 * （与上游一致：此时它们本就按「隐藏」生效，置灰避免误以为没作用）。
 */
val LauncherMenuDetailSpec = ScopePageSpec(
    pageKey = "launcher_menu_detail",
    prefsName = SettingsPrefs,
    packName = HookEnv.PKG_LAUNCHER,
    scopes = arrayOf(HookEnv.PKG_LAUNCHER),
    restartEnabled = true,
) {
    val ctx = context ?: return@ScopePageSpec

    // 功能总开关（二级页）关闭 ⇒ 本页一律不可操作
    val featureOn = state.getBoolean(LauncherMenuConfig.KEY_ENABLED, false)
    // 批量隐藏打开 ⇒ 6 个分项一律按「隐藏」生效，界面上禁用它们
    val hideAll = state.getBoolean(LauncherMenuConfig.KEY_HIDE_ALL, false)
    val itemEnabled = featureOn && !hideAll

    // ---------------------------------------------------------- 菜单结构
    category(ctx.getString(R.string.launcher_menu_section_structure))
    switch(
        key = LauncherMenuConfig.KEY_FLAT,
        title = ctx.getString(R.string.launcher_menu_flat),
        summary = ctx.getString(R.string.launcher_menu_flat_summary),
        enabled = featureOn,
    )
    switch(
        key = LauncherMenuConfig.KEY_FOLD_DEEP,
        title = ctx.getString(R.string.launcher_menu_fold_deep),
        summary = ctx.getString(R.string.launcher_menu_fold_deep_summary),
        enabled = featureOn,
    )
    switch(
        key = LauncherMenuConfig.KEY_HIDE_DEEP,
        title = ctx.getString(R.string.launcher_menu_hide_deep),
        summary = ctx.getString(R.string.launcher_menu_hide_deep_summary),
        enabled = featureOn,
    )

    // ---------------------------------------------------------- 批量隐藏
    category(ctx.getString(R.string.launcher_menu_section_batch))
    switch(
        key = LauncherMenuConfig.KEY_HIDE_ALL,
        title = ctx.getString(R.string.launcher_menu_hide_all),
        summary = ctx.getString(R.string.launcher_menu_hide_all_summary),
        enabled = featureOn,
    )

    // ---------------------------------------------------------- 按项隐藏
    category(ctx.getString(R.string.launcher_menu_section_items))
    switch(
        key = LauncherMenuConfig.KEY_HIDE_PRIVACY_LOCK,
        title = ctx.getString(R.string.launcher_menu_hide_privacy_lock),
        summary = ctx.getString(R.string.launcher_menu_hide_privacy_lock_summary),
        enabled = itemEnabled,
    )
    switch(
        key = LauncherMenuConfig.KEY_HIDE_CLOSE_PRIVACY_LOCK,
        title = ctx.getString(R.string.launcher_menu_hide_close_privacy_lock),
        summary = ctx.getString(R.string.launcher_menu_hide_close_privacy_lock_summary),
        enabled = itemEnabled,
    )
    switch(
        key = LauncherMenuConfig.KEY_HIDE_APP_INFO,
        title = ctx.getString(R.string.launcher_menu_hide_app_info),
        summary = ctx.getString(R.string.launcher_menu_hide_app_info_summary),
        enabled = itemEnabled,
    )
    switch(
        key = LauncherMenuConfig.KEY_HIDE_SHARE,
        title = ctx.getString(R.string.launcher_menu_hide_share),
        summary = ctx.getString(R.string.launcher_menu_hide_share_summary),
        enabled = itemEnabled,
    )
    switch(
        key = LauncherMenuConfig.KEY_HIDE_EDIT,
        title = ctx.getString(R.string.launcher_menu_hide_edit),
        summary = ctx.getString(R.string.launcher_menu_hide_edit_summary),
        enabled = itemEnabled,
    )
    switch(
        key = LauncherMenuConfig.KEY_HIDE_SERVICE_CARD,
        title = ctx.getString(R.string.launcher_menu_hide_service_card),
        summary = ctx.getString(R.string.launcher_menu_hide_service_card_summary),
        enabled = itemEnabled,
    )
}
