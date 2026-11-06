package com.fr.colorostools.ui.compose.scopes.apps

import com.fr.colorostools.R
import com.fr.colorostools.hook.HookEnv
import com.fr.colorostools.hook.hookers.FloatWindowConfig
import com.fr.colorostools.ui.compose.scopes.ScopePageSpec
import com.fr.colorostools.utils.SettingsPrefs

/**
 * 「浮窗」功能页 —— 八个小窗（浮窗）功能，移植自 coloros-mod。
 *
 * ## 为什么它是个**分类节点**而不是某个 App 的节点
 *
 * 这八个功能横跨两个进程：
 *
 * | 功能 | 进程 |
 * |---|---|
 * | 数量上限 / 贴边挂机 / 静音 / 白色竖条 / 横屏保持比例 / 贴边尺寸优化 | system_server |
 * | 多任务隐藏小窗应用 | 系统桌面 |
 * | 关闭小窗时从多任务隐藏 | system_server |
 *
 * 一个页面只能有一个 `packName`（功能树标题/图标来源），所以这里不给它挂某个 App 的图标，
 * 而是在 `categoryBadges` 里给它一个固定彩色徽标 + 在 `treeTitleRes` 里覆盖标题为「浮窗」
 * —— 与「状态栏」「锁屏」这类分类节点同一套做法。
 *
 * ## 作用域
 *
 * `scopes` 同时声明了 `system` 与 `com.android.launcher`：
 * 前者是六个功能的宿主，后者只服务「多任务隐藏小窗应用」（多任务卡片列表由桌面侧过滤）。
 * 各开关上的 `requiredScopes` 会在**打开时**按需检测并补全，避免用户全量勾选后才发现不生效。
 *
 * hook 侧实现：
 * - [com.fr.colorostools.hook.hookers.FloatWindowCountHook]
 * - [com.fr.colorostools.hook.hookers.FloatWindowSizeHook]
 * - [com.fr.colorostools.hook.hookers.FloatWindowEdgeHangHook]
 * - [com.fr.colorostools.hook.hookers.FloatWindowRecentsHook]
 */
val FloatWindowSpec = ScopePageSpec(
    pageKey = "float_window",
    prefsName = SettingsPrefs,
    packName = HookEnv.PKG_SYSTEM_SERVER,
    scopes = arrayOf(HookEnv.PKG_SYSTEM_SERVER, HookEnv.PKG_LAUNCHER),
    restartEnabled = true,
) {
    val ctx = context ?: return@ScopePageSpec
    val systemScopes = arrayOf(HookEnv.PKG_SYSTEM_SERVER)

    // ---------------------------------------------------------- 多窗口
    category(ctx.getString(R.string.float_window_section_multi))
    switch(
        key = FloatWindowConfig.KEY_COUNT_ENABLED,
        title = ctx.getString(R.string.float_window_count_enable),
        summary = ctx.getString(R.string.float_window_count_enable_summary),
        requiredScopes = systemScopes,
    )
    slider(
        key = FloatWindowConfig.KEY_COUNT,
        title = ctx.getString(R.string.float_window_count),
        valueRange = FloatWindowConfig.COUNT_MIN..FloatWindowConfig.COUNT_MAX,
        default = FloatWindowConfig.COUNT_DEFAULT,
        summary = ctx.getString(R.string.float_window_count_summary),
        valueLabel = { ctx.getString(R.string.float_window_count_value, it) },
        enabled = state.getBoolean(FloatWindowConfig.KEY_COUNT_ENABLED, false),
    )

    // ---------------------------------------------------------- 多任务
    category(ctx.getString(R.string.float_window_section_recents))
    switch(
        key = FloatWindowConfig.KEY_RECENTS_HIDE_FREEFORM,
        title = ctx.getString(R.string.float_window_recents_hide),
        // 这条 summary 就是用户要求的「需要勾选系统桌面」提示
        summary = ctx.getString(R.string.float_window_recents_hide_summary),
        requiredScopes = arrayOf(HookEnv.PKG_LAUNCHER),
    )
    switch(
        key = FloatWindowConfig.KEY_RECENTS_HIDE_CLOSED_FREEFORM,
        title = ctx.getString(R.string.float_window_recents_closed_hide),
        summary = ctx.getString(R.string.float_window_recents_closed_hide_summary),
        requiredScopes = systemScopes,
    )

    // ---------------------------------------------------------- 贴边挂机
    val edgeHang = state.getBoolean(FloatWindowConfig.KEY_EDGE_HANG, false)
    category(ctx.getString(R.string.float_window_section_edge))
    switch(
        key = FloatWindowConfig.KEY_EDGE_HANG,
        title = ctx.getString(R.string.float_window_edge_hang),
        summary = ctx.getString(R.string.float_window_edge_hang_summary),
        requiredScopes = systemScopes,
    )
    switch(
        key = FloatWindowConfig.KEY_EDGE_HANG_MUTE,
        title = ctx.getString(R.string.float_window_edge_hang_mute),
        summary = ctx.getString(R.string.float_window_edge_hang_mute_summary),
        // 静音依附于挂机：挂机没开就没有「贴边挂机中」这个状态
        enabled = edgeHang,
        requiredScopes = systemScopes,
    )
    switch(
        key = FloatWindowConfig.KEY_EDGE_HANG_WHITE_BAR,
        title = ctx.getString(R.string.float_window_edge_white_bar),
        summary = ctx.getString(R.string.float_window_edge_white_bar_summary),
        requiredScopes = systemScopes,
    )

    // ---------------------------------------------------------- 比例与尺寸
    category(ctx.getString(R.string.float_window_section_size))
    switch(
        key = FloatWindowConfig.KEY_LANDSCAPE_KEEP_RATIO,
        title = ctx.getString(R.string.float_window_landscape_ratio),
        summary = ctx.getString(R.string.float_window_landscape_ratio_summary),
        requiredScopes = systemScopes,
    )
    switch(
        key = FloatWindowConfig.KEY_EDGE_SIZE_OPTIMIZE,
        title = ctx.getString(R.string.float_window_edge_size),
        summary = ctx.getString(R.string.float_window_edge_size_summary),
        requiredScopes = systemScopes,
    )
}
