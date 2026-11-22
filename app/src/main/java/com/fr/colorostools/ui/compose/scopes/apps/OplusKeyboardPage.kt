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
 * ## 功能二：按键滑动快捷键（v2.0.0 四方向）
 * 在键盘某个键上向上／下／左／右滑动即触发编辑动作（全选/复制/粘贴/剪贴板/段落跳转/大小写切换…），
 * 每个键的 4 个方向各自独立绑定动作、自定义文本与触发距离。键位绑定分两个子页
 * （**其它按键卡片已并入各自页面**，对齐上游 v2.0.0）：
 * - [OplusKeyboardQwertySpec] —— 26 键字母（默认下滑 z=全选、x=剪切、c=复制、v=粘贴）+ 26 键其它按键
 * - [OplusKeyboardT9Spec] —— 九宫格数字 2..9 + 九宫格其它按键
 *
 * ## 设置项结构（对齐上游 `MainActivity.buildGestureCard` / `buildGeneralCard`）
 * 「手势设置」放两个触发距离滑块（26 键 / 九宫格），「通用设置」放 3 个开关
 * （按键底部功能文字 / 下滑触发提示 / 额外震动）。
 *
 * 注意：**不在此页提供「隐藏桌面图标」**——模块「设置」页已有该开关
 * （`com.fr.colorostools.ui.compose.pages.SettingPage` 用 key `hide_desktop_module_icon`），
 * 重复一份只会造成两个入口，已移除。
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
        // 键位绑定入口（两页：26 键 / 九宫格，各自内含「其它按键」卡片）
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

        // ---------------- 手势设置：两个触发距离 ----------------
        category(
            title = ctx.getString(R.string.oplus_keyboard_section_gesture),
            summary = ctx.getString(R.string.oplus_keyboard_section_gesture_summary),
        )
        slider(
            key = SwipeConst.KEY_THRESHOLD_DP,
            title = ctx.getString(R.string.oplus_keyboard_swipe_threshold),
            valueRange = SwipeConst.MIN_THRESHOLD_DP..SwipeConst.MAX_THRESHOLD_DP,
            step = 1,
            default = SwipeConst.DEFAULT_THRESHOLD_DP,
            valueLabel = { ctx.getString(R.string.oplus_keyboard_dp_value, it) },
        )
        slider(
            key = SwipeConst.KEY_T9_THRESHOLD_DP,
            title = ctx.getString(R.string.oplus_keyboard_swipe_t9_threshold),
            valueRange = SwipeConst.MIN_T9_THRESHOLD_DP..SwipeConst.MAX_T9_THRESHOLD_DP,
            step = 1,
            default = SwipeConst.DEFAULT_T9_THRESHOLD_DP,
            valueLabel = { ctx.getString(R.string.oplus_keyboard_dp_value, it) },
        )

        // ---------------- 通用设置：显示与反馈 ----------------
        category(ctx.getString(R.string.oplus_keyboard_section_general))
        switch(
            key = SwipeConst.KEY_SHOW_LABELS,
            title = ctx.getString(R.string.oplus_keyboard_swipe_labels),
            default = true,
        )
        switch(
            key = SwipeConst.KEY_SHOW_HINT,
            title = ctx.getString(R.string.oplus_keyboard_swipe_hint),
            summary = ctx.getString(R.string.oplus_keyboard_swipe_hint_summary),
            default = true,
        )
        switch(
            key = SwipeConst.KEY_VIBRATION,
            title = ctx.getString(R.string.oplus_keyboard_swipe_vibration),
            default = true,
        )
    }
}
