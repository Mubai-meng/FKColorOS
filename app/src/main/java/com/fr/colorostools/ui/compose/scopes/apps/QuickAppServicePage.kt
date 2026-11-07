package com.fr.colorostools.ui.compose.scopes.apps

import com.fr.colorostools.R
import com.fr.colorostools.hook.HookEnv
import com.fr.colorostools.hook.hookers.ExpressCardHook
import com.fr.colorostools.ui.compose.scopes.ScopePageSpec
import com.fr.colorostools.utils.SettingsPrefs

/**
 * 「快应用服务」功能页 —— 功能树新节点（`pageKey = "quick_app_service"`）。
 *
 * 树标题直接取 `com.nearme.instant.platform` 的 App 名称「快应用服务」，不做覆盖
 * （功能树标题 = 被 hook 的目标应用名）。
 *
 * ## 为什么需要三个作用域
 *
 * 快递卡片有三个入口，每个入口都在自己的进程里发起 `startActivity`：
 *
 * - `com.nearme.instant.platform` —— 平台运行时本身（改写的**主战场**：换成应用内快递页，
 *   并屏蔽电商/快递 App 的包探测、自动填单号回车、抑制键盘）；
 * - `com.android.launcher` —— 桌面（从卡片/浮层点进来时改写目的地）；
 * - `com.coloros.assistantscreen` —— 小布建议（同桌面）。
 *
 * 少勾一个，从那条入口点进去就会照旧跳小程序。所以 `requiredScopes` 三个都要，
 * 打开开关时缺失会弹窗一次性补全。
 *
 * hook 侧见 [ExpressCardHook]。
 */
val QuickAppServiceSpec = ScopePageSpec(
    pageKey = "quick_app_service",
    prefsName = SettingsPrefs,
    packName = HookEnv.PKG_QUICK_APP,
    scopes = arrayOf(
        HookEnv.PKG_QUICK_APP,
        HookEnv.PKG_LAUNCHER,
        HookEnv.PKG_ASSISTANT_SCREEN,
    ),
    restartEnabled = true,
) {
    val ctx = context ?: return@ScopePageSpec

    category(ctx.getString(R.string.quick_app_category_express))
    switch(
        key = ExpressCardHook.KEY_NO_MINI_PROGRAM,
        title = ctx.getString(R.string.quick_app_express_no_mini),
        summary = ctx.getString(R.string.quick_app_express_no_mini_summary),
        requiredScopes = arrayOf(
            HookEnv.PKG_QUICK_APP,
            HookEnv.PKG_LAUNCHER,
            HookEnv.PKG_ASSISTANT_SCREEN,
        ),
    )
}
