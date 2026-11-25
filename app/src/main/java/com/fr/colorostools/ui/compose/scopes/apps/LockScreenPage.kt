package com.fr.colorostools.ui.compose.scopes.apps

import com.fr.colorostools.R
import com.fr.colorostools.hook.HookEnv
import com.fr.colorostools.hook.hookers.DesktopThemeIconConfig
import com.fr.colorostools.hook.hookers.KeyguardNotificationOffsetConfig
import com.fr.colorostools.ui.compose.scopes.ScopePageSpec
import com.fr.colorostools.utils.SettingsPrefs

/**
 * 「系统界面」功能页（宿主 `com.android.systemui`，`pageKey = "lock_screen"`）。
 *
 * ⚠️ `pageKey` 仍是历史名 `lock_screen`（导航键 / `pageTargetMap` 都在用它），
 * 但**功能树标题已不再叫「锁屏」**：`ScopePageRegistry.treeTitleRes` 里的覆盖条目已移除，
 * 标题按项目规范走宿主 App 标签 ⇒ 中文「系统界面」、英文「System UI」。
 *
 * 当前两项功能：
 * 1. **锁屏通知区域下移** —— 滑条控制下移量（0..40 dp，默认 20 dp），把锁屏上的通知整体
 *    往下推，避开时钟/日历那块。生效范围：锁屏静止态、下拉拖拽态、锁屏通知中心
 *    （展开 + 收起动画）三条通道一起改，拖滑条立刻可见，**不需要重启 SystemUI**。
 *    hook 侧：`hook/hookers/KeyguardNotificationOffsetHook.kt`。
 * 2. **通知图标使用桌面主题图标** —— 状态栏/悬浮通知的图标改为桌面当前主题（或用户自定义）
 *    的那张图标。hook 侧：`hook/hookers/DesktopThemeIconHook.kt`（含覆盖边界说明）。
 */
val LockScreenSpec = ScopePageSpec(
    pageKey = "lock_screen",
    prefsName = SettingsPrefs,
    packName = HookEnv.PKG_SYSTEMUI,
    scopes = arrayOf(HookEnv.PKG_SYSTEMUI),
    restartEnabled = true,
) {
    val ctx = context ?: return@ScopePageSpec

    switch(
        key = KeyguardNotificationOffsetConfig.KEY_ENABLED,
        title = ctx.getString(R.string.keyguard_ntf_offset_enable),
        summary = ctx.getString(R.string.keyguard_ntf_offset_enable_summary),
        // 作用域门禁：打开开关前自动检测 com.android.systemui 是否在作用域内，缺失则弹窗补全
        requiredScopes = arrayOf(HookEnv.PKG_SYSTEMUI),
    )
    val offsetOn = state.getBoolean(KeyguardNotificationOffsetConfig.KEY_ENABLED, false)
    slider(
        key = KeyguardNotificationOffsetConfig.KEY_DP,
        title = ctx.getString(R.string.keyguard_ntf_offset_dp),
        valueRange = 0..KeyguardNotificationOffsetConfig.DP_MAX,
        default = KeyguardNotificationOffsetConfig.DP_DEFAULT,
        summary = ctx.getString(R.string.keyguard_ntf_offset_dp_summary),
        valueLabel = { ctx.getString(R.string.keyguard_ntf_offset_dp_value, it) },
        enabled = offsetOn,
    )

    switch(
        key = DesktopThemeIconConfig.KEY_ENABLED,
        title = ctx.getString(R.string.desktop_theme_icon_enable),
        summary = ctx.getString(R.string.desktop_theme_icon_enable_summary),
        requiredScopes = arrayOf(HookEnv.PKG_SYSTEMUI),
    )
}
