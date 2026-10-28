package com.fr.colorostools.ui.compose.scopes.apps

import com.fr.colorostools.R
import com.fr.colorostools.hook.HookEnv
import com.fr.colorostools.hook.scopes.opluskeyboard.ForceEnableOplusKeyboardBlur
import com.fr.colorostools.hook.scopes.opluskeyboard.OplusKeyboardConfig
import com.fr.colorostools.hook.xiaobuswipe.SwipeConst
import com.fr.colorostools.ui.compose.scopes.ScopePageSpec
import com.fr.colorostools.utils.SettingsPrefs

/**
 * 小布输入法（宿主 `com.oplus.keyboard`）主页面：两个功能的总开关。
 *
 * ## 功能一：强制启用液态玻璃模糊
 * 开启后，无论输入框所在应用是否在宿主白名单内、是否使用内置主题与跟随系统深浅色，
 * 键盘都按液态玻璃渲染（强度可调）。实现见
 * [com.fr.colorostools.hook.scopes.opluskeyboard.ForceEnableOplusKeyboardBlur]。
 *
 * ## 功能二：按键下滑快捷键
 * 在键盘某个键上向下滑动即触发编辑动作（全选/复制/粘贴/剪贴板/段落跳转…），
 * 键位绑定分别在两个子页：
 * - [OplusKeyboardQwertySpec] —— 26 键字母（默认 z=全选、x=剪切、c=复制、v=粘贴）
 * - [OplusKeyboardT9Spec] —— 九宫格数字 2..9
 *
 * ## 生效时机
 * 钩子装在输入法进程启动时，且钩子体每帧按开关值早退 —— 所以**开关无需重启**，
 * 切换键盘后立即按新配置工作；首次启用需等输入法进程已注入（打字一次即生效）。
 */
val OplusKeyboardSpec = ScopePageSpec(
    pageKey = "oplus_keyboard",
    prefsName = SettingsPrefs,
    packName = HookEnv.PKG_OPLUS_KEYBOARD,
    scopes = arrayOf(HookEnv.PKG_OPLUS_KEYBOARD),
    restartEnabled = true,
) {
    val ctx = context ?: return@ScopePageSpec

    category(ctx.getString(R.string.oplus_keyboard_section_blur))

    switch(
        key = OplusKeyboardConfig.KEY_BLUR_ENABLED,
        title = ctx.getString(R.string.oplus_keyboard_blur_enable),
        summary = ctx.getString(R.string.oplus_keyboard_blur_enable_summary),
        requiredScopes = arrayOf(HookEnv.PKG_OPLUS_KEYBOARD),
    )

    // 强度只在模糊开启时出现（关闭时这个值没有意义，避免误解为「强度=0 已关闭」）
    if (state.getBoolean(OplusKeyboardConfig.KEY_BLUR_ENABLED, false)) {
        slider(
            key = OplusKeyboardConfig.KEY_BLUR_RADIUS,
            title = ctx.getString(R.string.oplus_keyboard_blur_radius),
            valueRange = ForceEnableOplusKeyboardBlur.MIN_BLUR_RADIUS..
                ForceEnableOplusKeyboardBlur.MAX_BLUR_RADIUS,
            step = 10,
            default = ForceEnableOplusKeyboardBlur.DEFAULT_BLUR_RADIUS,
            summary = ctx.getString(R.string.oplus_keyboard_blur_radius_summary),
            valueLabel = { "$it" },
        )
    }

    category(ctx.getString(R.string.oplus_keyboard_section_swipe))

    switch(
        key = OplusKeyboardConfig.KEY_SWIPE_ENABLED,
        title = ctx.getString(R.string.oplus_keyboard_swipe_enable),
        summary = ctx.getString(R.string.oplus_keyboard_swipe_enable_summary),
        requiredScopes = arrayOf(HookEnv.PKG_OPLUS_KEYBOARD),
    )

    if (state.getBoolean(OplusKeyboardConfig.KEY_SWIPE_ENABLED, false)) {
        slider(
            key = SwipeConst.KEY_THRESHOLD_DP,
            title = ctx.getString(R.string.oplus_keyboard_swipe_threshold),
            valueRange = SwipeConst.MIN_THRESHOLD_DP..SwipeConst.MAX_THRESHOLD_DP,
            step = 1,
            default = SwipeConst.DEFAULT_THRESHOLD_DP,
            summary = ctx.getString(R.string.oplus_keyboard_swipe_threshold_summary),
            valueLabel = { ctx.getString(R.string.oplus_keyboard_dp_value, it) },
        )
        slider(
            key = SwipeConst.KEY_T9_THRESHOLD_DP,
            title = ctx.getString(R.string.oplus_keyboard_swipe_t9_threshold),
            valueRange = SwipeConst.MIN_T9_THRESHOLD_DP..SwipeConst.MAX_T9_THRESHOLD_DP,
            step = 1,
            default = SwipeConst.DEFAULT_T9_THRESHOLD_DP,
            summary = ctx.getString(R.string.oplus_keyboard_swipe_t9_threshold_summary),
            valueLabel = { ctx.getString(R.string.oplus_keyboard_dp_value, it) },
        )
        switch(
            key = SwipeConst.KEY_VIBRATION,
            title = ctx.getString(R.string.oplus_keyboard_swipe_vibration),
            summary = ctx.getString(R.string.oplus_keyboard_swipe_vibration_summary),
            default = true,
        )
        switch(
            key = SwipeConst.KEY_SHOW_LABELS,
            title = ctx.getString(R.string.oplus_keyboard_swipe_labels),
            summary = ctx.getString(R.string.oplus_keyboard_swipe_labels_summary),
            default = true,
        )
        switch(
            key = SwipeConst.KEY_SHOW_HINT,
            title = ctx.getString(R.string.oplus_keyboard_swipe_hint),
            summary = ctx.getString(R.string.oplus_keyboard_swipe_hint_summary),
            default = true,
        )

        page(
            title = ctx.getString(R.string.oplus_keyboard_qwerty_title),
            target = "oplus_keyboard_qwerty",
            summary = ctx.getString(R.string.oplus_keyboard_qwerty_summary),
        )
        page(
            title = ctx.getString(R.string.oplus_keyboard_t9_title),
            target = "oplus_keyboard_t9",
            summary = ctx.getString(R.string.oplus_keyboard_t9_summary),
        )
    }
}