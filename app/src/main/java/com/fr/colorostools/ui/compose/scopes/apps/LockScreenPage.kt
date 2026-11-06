package com.fr.colorostools.ui.compose.scopes.apps

import com.fr.colorostools.R
import com.fr.colorostools.hook.HookEnv
import com.fr.colorostools.hook.hookers.KeyguardNotificationOffsetConfig
import com.fr.colorostools.ui.compose.scopes.ScopePageSpec
import com.fr.colorostools.utils.SettingsPrefs

/**
 * 「锁屏」功能页（宿主 `com.android.systemui`）。
 *
 * 当前只有一个功能：**锁屏通知区域下移**（移植自 coloros-mod）。开启后用滑条控制下移量
 * （0..40 dp，默认 20 dp），把锁屏上的通知整体往下推，避开时钟/日历那块。
 *
 * 生效范围：锁屏静止态、下拉拖拽态、锁屏通知中心（展开 + 收起动画）三条通道一起改，
 * 因此拖动滑条后立刻可见，**不需要重启 SystemUI**。
 *
 * hook 侧实现：`hook/hookers/KeyguardNotificationOffsetHook.kt`。
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
    val on = state.getBoolean(KeyguardNotificationOffsetConfig.KEY_ENABLED, false)
    slider(
        key = KeyguardNotificationOffsetConfig.KEY_DP,
        title = ctx.getString(R.string.keyguard_ntf_offset_dp),
        valueRange = 0..KeyguardNotificationOffsetConfig.DP_MAX,
        default = KeyguardNotificationOffsetConfig.DP_DEFAULT,
        summary = ctx.getString(R.string.keyguard_ntf_offset_dp_summary),
        valueLabel = { ctx.getString(R.string.keyguard_ntf_offset_dp_value, it) },
        enabled = on,
    )
}