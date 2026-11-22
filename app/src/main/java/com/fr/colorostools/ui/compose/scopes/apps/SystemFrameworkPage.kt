package com.fr.colorostools.ui.compose.scopes.apps

import com.fr.colorostools.R
import com.fr.colorostools.hook.HookEnv
import com.fr.colorostools.hook.hookers.AppBrightnessLimitConfig
import com.fr.colorostools.hook.hookers.AutoBrightnessConfig
import com.fr.colorostools.ui.compose.scopes.ScopePageSpec
import com.fr.colorostools.utils.SettingsPrefs

/**
 * 「系统框架」功能页（宿主 = system_server）。
 *
 * 这是 system_server 功能的**分类顶层节点**（2026-11-16 用户指定新增），
 * 与「浮窗」「锁屏」同类：system_server 没有真实 App 名可借用，
 * 标题在 `treeTitleRes` 里固定为「系统框架」。
 *
 * 后续挂在 system_server 上、不属于既有浮窗/熄屏挂机等页面的新功能落这里。
 *
 * hook 侧实现：`hook/hookers/AutoBrightnessHook.kt`。
 * 钩子在 system_server 启动时装载，**开关与参数改动即时生效、无需重启**；
 * 只有第一次给模块补 `system` 作用域后需要重启一次手机。
 */
val SystemFrameworkSpec = ScopePageSpec(
    pageKey = "system_framework",
    prefsName = SettingsPrefs,
    packName = HookEnv.PKG_SYSTEM_SERVER,
    scopes = arrayOf(HookEnv.PKG_SYSTEM_SERVER),
    restartEnabled = true,
) {
    val ctx = context ?: return@ScopePageSpec
    val on = state.getBoolean(AutoBrightnessConfig.KEY_ENABLED, false)

    // ---------------------------------------------------------- 自动亮度低照度提升
    category(ctx.getString(R.string.sysfw_section_autobr))
    switch(
        key = AutoBrightnessConfig.KEY_ENABLED,
        title = ctx.getString(R.string.sysfw_autobr_enable),
        summary = ctx.getString(R.string.sysfw_autobr_enable_summary),
        requiredScopes = arrayOf(HookEnv.PKG_SYSTEM_SERVER),
    )
    slider(
        key = AutoBrightnessConfig.KEY_FACTOR,
        title = ctx.getString(R.string.sysfw_autobr_factor),
        valueRange = AutoBrightnessConfig.FACTOR_MIN..AutoBrightnessConfig.FACTOR_MAX,
        step = 5,
        default = AutoBrightnessConfig.FACTOR_DEFAULT,
        summary = ctx.getString(R.string.sysfw_autobr_factor_summary),
        valueLabel = { ctx.getString(R.string.sysfw_autobr_percent_value, it) },
        enabled = on,
    )
    slider(
        key = AutoBrightnessConfig.KEY_OFFSET,
        title = ctx.getString(R.string.sysfw_autobr_offset),
        valueRange = AutoBrightnessConfig.OFFSET_MIN..AutoBrightnessConfig.OFFSET_MAX,
        default = AutoBrightnessConfig.OFFSET_DEFAULT,
        summary = ctx.getString(R.string.sysfw_autobr_offset_summary),
        valueLabel = { ctx.getString(R.string.sysfw_autobr_lux_value, it) },
        enabled = on,
    )
    slider(
        key = AutoBrightnessConfig.KEY_LUX_MAX,
        title = ctx.getString(R.string.sysfw_autobr_luxmax),
        valueRange = AutoBrightnessConfig.LUX_MAX_MIN..AutoBrightnessConfig.LUX_MAX_MAX,
        step = 5,
        default = AutoBrightnessConfig.LUX_MAX_DEFAULT,
        summary = ctx.getString(R.string.sysfw_autobr_luxmax_summary),
        valueLabel = { ctx.getString(R.string.sysfw_autobr_lux_value, it) },
        enabled = on,
    )
    slider(
        key = AutoBrightnessConfig.KEY_MAX_GAIN,
        title = ctx.getString(R.string.sysfw_autobr_maxgain),
        valueRange = AutoBrightnessConfig.MAX_GAIN_MIN..AutoBrightnessConfig.MAX_GAIN_MAX,
        step = 10,
        default = AutoBrightnessConfig.MAX_GAIN_DEFAULT,
        summary = ctx.getString(R.string.sysfw_autobr_maxgain_summary),
        valueLabel = { ctx.getString(R.string.sysfw_autobr_percent_value, it) },
        enabled = on,
    )

    // ---------------------------------------------------------- 解锁 App 亮度限制
    // 一个总开关 + 四个子开关：子开关默认 true（打开总开关即全部解锁），
    // 出现「某类 App 仍然偏暗」时可逐项排查。hook 侧读取时同样以 true 为默认，
    // 见 AppBrightnessLimitConfig 类文档。
    val appBr = state.getBoolean(AppBrightnessLimitConfig.KEY_ENABLED, false)
    category(ctx.getString(R.string.sysfw_section_appbr))
    switch(
        key = AppBrightnessLimitConfig.KEY_ENABLED,
        title = ctx.getString(R.string.sysfw_appbr_enable),
        summary = ctx.getString(R.string.sysfw_appbr_enable_summary),
        requiredScopes = arrayOf(HookEnv.PKG_SYSTEM_SERVER),
    )
    switch(
        key = AppBrightnessLimitConfig.KEY_APP_REDUCE,
        title = ctx.getString(R.string.sysfw_appbr_app_reduce),
        summary = ctx.getString(R.string.sysfw_appbr_app_reduce_summary),
        default = true,
        enabled = appBr,
    )
    switch(
        key = AppBrightnessLimitConfig.KEY_FOSS,
        title = ctx.getString(R.string.sysfw_appbr_foss),
        summary = ctx.getString(R.string.sysfw_appbr_foss_summary),
        default = true,
        enabled = appBr,
    )
    switch(
        key = AppBrightnessLimitConfig.KEY_WINDOW,
        title = ctx.getString(R.string.sysfw_appbr_window),
        summary = ctx.getString(R.string.sysfw_appbr_window_summary),
        default = true,
        enabled = appBr,
    )
    switch(
        key = AppBrightnessLimitConfig.KEY_GLOBAL,
        title = ctx.getString(R.string.sysfw_appbr_global),
        summary = ctx.getString(R.string.sysfw_appbr_global_summary),
        default = true,
        enabled = appBr,
    )
}
