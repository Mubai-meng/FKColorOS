package com.fr.colorostools.ui.compose.scopes.apps

import com.fr.colorostools.R
import com.fr.colorostools.hook.HookEnv
import com.fr.colorostools.hook.hookers.FileManagerHook
import com.fr.colorostools.hook.hookers.FilePickerHook
import com.fr.colorostools.ui.compose.scopes.ScopePageSpec
import com.fr.colorostools.utils.SettingsPrefs

/**
 * 「文件管理」功能页 —— 功能树 `oplus_file_manager` 节点。
 *
 * 三个开关分属**两个宿主**，所以 `scopes` 同时声明了它们：
 *
 * | 开关 | 宿主 | hook 侧 |
 * |---|---|---|
 * | 隐藏安全访问提示 | `com.coloros.filemanager` | [FileManagerHook] |
 * | 使用 MT 管理器为选择器 | system_server | [FilePickerHook] |
 * | 使用原生文件选择器 | system_server | [FilePickerHook] |
 *
 * 后两个必须在 system_server 里改：ColorOS 把「谁来响应选文件请求」的裁决放在 framework 侧
 * （`ActivityStarter` 与 `OplusDefaultAppPolicyManager`），文件管理器 App 自己决定不了。
 *
 * 三处 `requiredScopes` 分开写，是为了让作用域门禁**按开关**提示 —— 只开「隐藏安全访问提示」
 * 的用户不该被要求勾选 system（勾了会多注入一个进程）。
 *
 * 改完要重启对应宿主：前两项生效于「下一次选文件请求」，system_server 侧一般不必重启，
 * 但已经解析过组件的请求需要新一次调用才会走新路径；「隐藏安全访问提示」在下次打开
 * 文件选择页时生效。
 *
 * 页面标识 `pageKey = "oplus_file_manager"`，与旧功能树节点、`pageTargetMap` 自映射一致。
 */
val FileManagerSpec = ScopePageSpec(
    pageKey = "oplus_file_manager",
    prefsName = SettingsPrefs,
    packName = HookEnv.PKG_FILE_MANAGER,
    scopes = arrayOf(HookEnv.PKG_FILE_MANAGER, HookEnv.PKG_SYSTEM_SERVER),
    restartEnabled = true,
) {
    val ctx = context ?: return@ScopePageSpec
    val fileManagerScopes = arrayOf(HookEnv.PKG_FILE_MANAGER)
    val systemScopes = arrayOf(HookEnv.PKG_SYSTEM_SERVER)

    category(ctx.getString(R.string.file_manager_category_ui))
    switch(
        key = FileManagerHook.KEY_HIDE_SECURE_TIP,
        title = ctx.getString(R.string.file_manager_hide_secure_tip),
        summary = ctx.getString(R.string.file_manager_hide_secure_tip_summary),
        requiredScopes = fileManagerScopes,
    )

    category(ctx.getString(R.string.file_manager_category_picker))
    switch(
        key = FilePickerHook.KEY_USE_MT,
        title = ctx.getString(R.string.file_manager_use_mt),
        summary = ctx.getString(R.string.file_manager_use_mt_summary),
        requiredScopes = systemScopes,
    )
    switch(
        key = FilePickerHook.KEY_USE_NATIVE,
        title = ctx.getString(R.string.file_manager_use_native),
        summary = ctx.getString(R.string.file_manager_use_native_summary),
        requiredScopes = systemScopes,
    )
}
