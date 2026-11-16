package com.fr.colorostools.hook.hookers

/**
 * 「自动亮度低照度提升」（系统框架页）的开关与参数。
 *
 * App 设置页写入、hook 侧读取，两边同一份 remote prefs。
 *
 * 数值型参数 UI 存 Int（slider 只支持整数），hook 侧按约定换算：
 * - 百分比键（因子 / 增益上限）：存 160 = 1.6 倍
 * - lux 键（补偿 / 生效上限）：直接是 lux
 *
 * ⚠️ 「两侧都要用的默认值必须在读取侧兜底」：UI 的 slider `default=` 只做展示、
 * 从不落盘，未存过时 hook 侧读到默认值 —— 读取侧必须 coerceIn 到同一范围。
 */
internal object AutoBrightnessConfig {

    const val KEY_ENABLED = "auto_brightness_boost_enabled"

    /** 提升强度：存百分比（110–220，默认 160），生效系数 = 值 / 100f */
    const val KEY_FACTOR = "auto_brightness_boost_factor"
    const val FACTOR_DEFAULT = 160
    const val FACTOR_MIN = 110
    const val FACTOR_MAX = 220

    /** 低照度补偿：直接 lux（0–10，默认 3），线性加到放大结果上 */
    const val KEY_OFFSET = "auto_brightness_boost_offset"
    const val OFFSET_DEFAULT = 3
    const val OFFSET_MIN = 0
    const val OFFSET_MAX = 10

    /** 生效上限 lux：只在此值以下放大（5–80，默认 30），以上原样返回零开销直通 */
    const val KEY_LUX_MAX = "auto_brightness_boost_lux_max"
    const val LUX_MAX_DEFAULT = 30
    const val LUX_MAX_MIN = 5
    const val LUX_MAX_MAX = 80

    /** 结果上限：存百分比（120–260，默认 180），boost 后不超过 生效上限 × 值/100f，防映射曲线跳档 */
    const val KEY_MAX_GAIN = "auto_brightness_boost_max_gain"
    const val MAX_GAIN_DEFAULT = 180
    const val MAX_GAIN_MIN = 120
    const val MAX_GAIN_MAX = 260
}
