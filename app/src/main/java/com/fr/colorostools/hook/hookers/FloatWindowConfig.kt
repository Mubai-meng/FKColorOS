package com.fr.colorostools.hook.hookers

/**
 * 「浮窗」八个功能的开关与数值（App 设置页写入、hook 侧读取，两边同一份 remote prefs）。
 *
 * 键名与上游 coloros-mod（MIT）保持一致，便于对照移植来源与上游改版。
 *
 * ⚠️ 这些键分属**两个进程**：
 * | 键 | 读取进程 |
 * |---|---|
 * | [KEY_COUNT_*] / [KEY_EDGE_*] / [KEY_LANDSCAPE_KEEP_RATIO] | 只有 system_server |
 * | [KEY_RECENTS_HIDE_FREEFORM] | 只有系统桌面 |
 * | [KEY_RECENTS_HIDE_CLOSED_FREEFORM] | 只有 system_server（记录关闭态并按此过滤） |
 */
internal object FloatWindowConfig {

    // ---------------------------------------------------------------- 小窗数量上限
    const val KEY_COUNT_ENABLED = "float_window_count_enabled"
    const val KEY_COUNT = "float_window_count"

    /** 默认 3 个（= 原生默认值），下限 1、上限 10（对齐上游） */
    const val COUNT_DEFAULT = 3
    const val COUNT_MIN = 1
    const val COUNT_MAX = 10

    // ---------------------------------------------------------------- 多任务
    const val KEY_RECENTS_HIDE_FREEFORM = "recents_hide_freeform_enabled"
    const val KEY_RECENTS_HIDE_CLOSED_FREEFORM = "recents_hide_closed_freeform_enabled"

    // ---------------------------------------------------------------- 贴边挂机
    const val KEY_EDGE_HANG = "float_window_edge_hang_enabled"
    const val KEY_EDGE_HANG_MUTE = "float_window_edge_hang_mute_enabled"
    const val KEY_EDGE_HANG_WHITE_BAR = "float_window_edge_hang_white_bar_enabled"

    // ---------------------------------------------------------------- 尺寸
    const val KEY_LANDSCAPE_KEEP_RATIO = "float_window_landscape_keep_ratio_enabled"
    const val KEY_EDGE_SIZE_OPTIMIZE = "float_window_edge_size_optimize_enabled"

    /** 小窗放到最大时左右各留的边距（dp）：最大宽度 = 屏宽 − 2 × 此值 */
    const val SIDE_MARGIN_DP = 8f
}
