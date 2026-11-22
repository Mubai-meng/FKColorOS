package com.fr.colorostools.ui.compose.scopes.apps

import com.fr.colorostools.hook.HookEnv
import com.fr.colorostools.ui.compose.scopes.ScopePageSpec
import com.fr.colorostools.utils.SettingsPrefs

/**
 * 26 键字母的逐键绑定子页（宿主 `com.oplus.keyboard`）—— v2.0.0 四方向模型。
 *
 * 自 v2.0.0 起本页**同时承载「其它按键」卡片**（退格/回车/空格/Shift/符号/123/逗号/句号/
 * 拼英/重输/@#/问号/感叹号）—— 上游把两者放在同一张页面里，独立的
 * `oplus_keyboard_special_qwerty` / `_special_t9` 两个子页已删除。
 *
 * ## 交互（对齐上游 `app.xiaobuswipe` v2.0.0）
 * - **点击某键** → 先选方向（上/下/左/右）→ 再选动作
 * - **长按方向项** → 单独设置该方向的触发距离（0 = 跟随全局）
 * - **长按某键** → 设置键面标签（自动 / 自定义 / 隐藏）
 * - 底部固定「保存并应用配置」，另有「恢复默认 Z/X/C/V」「清空 26 键」
 *
 * ## 默认绑定
 * `z` 全选、`x` 剪切、`c` 复制、`v` 粘贴（桌面 Ctrl+Z/X/C/V 习惯）——**只作用于下滑方向**。
 * 回落规则见 [currentAction]：prefs 为空串时回落
 * [com.fr.colorostools.hook.xiaobuswipe.SwipeConst.DEFAULT_QWERTY]，与 hook 侧
 * `SwipeConfig.load()` 严格一致。
 *
 * 整页布局见 [OplusKeyboardBindContent]；行清单（`content`）故意为空，
 * 因为本页走自带布局槽位，`selfRendered = true` 让功能树/搜索不按空索引隐藏入口。
 */
val OplusKeyboardQwertySpec = ScopePageSpec(
    pageKey = "oplus_keyboard_qwerty",
    prefsName = SettingsPrefs,
    packName = HookEnv.PKG_OPLUS_KEYBOARD,
    scopes = arrayOf(HookEnv.PKG_OPLUS_KEYBOARD),
    restartEnabled = true,
    selfRendered = true,
    contentMiuix = { OplusKeyboardBindContent(scope = this, isQwerty = true) },
    contentMaterial = { OplusKeyboardBindContent(scope = this, isQwerty = true) },
) {
    // 整页自带布局（contentMiuix/contentMaterial）：共享层的行声明留空。
    // 构造器要求尾随 lambda，不能删。
}

/**
 * 九宫格数字 2–9 的逐键绑定子页（同样内含「其它按键」卡片）。
 *
 * 与 26 键页同一套四方向交互，差异：
 * - 数字 `1` 不参与绑定（宿主上它是普通键），网格里留空占位保持 3x3 排布；
 * - 无默认绑定（`2`–`9` 初始全部未绑定）；
 * - 批量操作只有「清空九宫格映射」，没有「恢复默认」。
 */
val OplusKeyboardT9Spec = ScopePageSpec(
    pageKey = "oplus_keyboard_t9",
    prefsName = SettingsPrefs,
    packName = HookEnv.PKG_OPLUS_KEYBOARD,
    scopes = arrayOf(HookEnv.PKG_OPLUS_KEYBOARD),
    restartEnabled = true,
    selfRendered = true,
    contentMiuix = { OplusKeyboardBindContent(scope = this, isQwerty = false) },
    contentMaterial = { OplusKeyboardBindContent(scope = this, isQwerty = false) },
) {
    // 同上：整页自带布局，无共享行。
}
