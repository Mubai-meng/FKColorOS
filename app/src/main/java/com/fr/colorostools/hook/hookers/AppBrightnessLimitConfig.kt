package com.fr.colorostools.hook.hookers

/**
 * 「解锁 App 亮度限制」（系统框架页）的开关。
 *
 * App 设置页写入、hook 侧读取，两边同一份 remote prefs。
 *
 * 一个总开关 + 四个子开关：总开关关闭时子开关一律不生效（[AppBrightnessLimitHook]
 * 在拦截器里两级判定），这样用户可以先全开再逐项排查是哪一类限制在压暗。
 *
 * 子开关默认 **true**：装完打开总开关就是「全部解锁」，符合功能名直觉；
 * 想细调再关掉某一项。
 *
 * ⚠️ 「两侧都要用的默认值必须在读取侧兜底」：UI 的 `default=` 只做展示、
 * 从不落盘，未存过时 hook 侧读到的是这里的常量。
 */
internal object AppBrightnessLimitConfig {

    /** 总开关 */
    const val KEY_ENABLED = "app_br_unlock_enabled"

    /**
     * 去除 App 降亮度特调。
     *
     * 原机 `display_brightness_app_list.xml` 对视频/短视频类 App 配了削减曲线
     * （如哔哩哔哩 `<nit>500,800,1000,2000</nit>` + `<ratio>0.0,0.375,0.0,0.5</ratio>`），
     * 这些 App 在前台时整体压暗、切回桌面立刻恢复。
     */
    const val KEY_APP_REDUCE = "app_br_unlock_app_reduce"

    /** 去除 FOSS 面板调优（短视频类 App 的额外 panel 参数改写，同样表现为变暗） */
    const val KEY_FOSS = "app_br_unlock_foss"

    /** 解除窗口亮度上限（支付/出行类 App 的 600nit 钳制） */
    const val KEY_WINDOW = "app_br_unlock_window"

    /** 解除全局亮度上限（白名单以外的 App 被钳到 1200nit 一类） */
    const val KEY_GLOBAL = "app_br_unlock_global"
}
