package com.fr.colorostools.hook.scopes.opluskeyboard

import com.fr.colorostools.hook.HookEnv

/**
 * 小布输入法（`com.oplus.keyboard`）两个功能的开关与参数。
 *
 * 通道 = libxposed remote prefs（App 设置页写、hook 进程读同一份内存镜像），
 * 与其它功能的约定一致，无需广播/缓存。所有 `KEY_*` 必须与
 * `ui/compose/scopes/apps/OplusKeyboardPage.kt` 里的键名逐字一致。
 */
internal object OplusKeyboardConfig {

    /** 模糊增强总开关 */
    const val KEY_BLUR_ENABLED = "oplus_keyboard_blur_enabled"

    /** 自定义模糊强度（滑条，50..500，宿主默认 150） */
    const val KEY_BLUR_RADIUS = "oplus_keyboard_blur_radius"

    /** 下滑快捷键总开关（下滑功能的键定义见 `xiaobuswipe/SwipeConst.kt`） */
    const val KEY_SWIPE_ENABLED = "xiaobu_swipe_enabled"

    /** 模糊增强是否开启（hook 侧实时读） */
    fun blurEnabled(): Boolean = HookEnv.settingBoolean(KEY_BLUR_ENABLED, false)

    /** 下滑快捷键是否开启（hook 侧实时读） */
    fun swipeEnabled(): Boolean = HookEnv.settingBoolean(KEY_SWIPE_ENABLED, false)
}