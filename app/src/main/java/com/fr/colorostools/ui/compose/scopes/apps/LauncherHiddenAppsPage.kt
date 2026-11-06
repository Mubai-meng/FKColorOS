package com.fr.colorostools.ui.compose.scopes.apps

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fr.colorostools.R
import com.fr.colorostools.hook.HookEnv
import com.fr.colorostools.hook.hookers.LauncherHiddenAppsConfig
import com.fr.colorostools.ui.compose.components.AppPickerDialog
import com.fr.colorostools.ui.compose.scopes.ScopePageSpec
import com.fr.colorostools.utils.PrefState
import com.fr.colorostools.utils.SettingsPrefs

/**
 * 页面间内存会话：隐藏应用选择对话框可见性。
 *
 * 放会话对象而非 remember —— DSL 内容 lambda 每次重组都会重跑，
 * remember 在条目重建/重组后状态会丢（范式同 [AccessibilitySession]）。
 */
private object LauncherHiddenSession {
    var showAppPicker by mutableStateOf(false)
}

/** 读已选组件名（`包名/类名` 的 flattenToString 形态） */
private fun readHiddenComponents(state: PrefState): Set<String> =
    state.getStringSet(LauncherHiddenAppsConfig.KEY_HIDDEN_COMPONENTS, emptySet())

/**
 * 写名单：**同时把总开关打开**。
 *
 * 语义理由：用户在这个页面点「添加」并选了应用，意图就是「把这些应用彻底藏起来」；
 * 若总开关还关着，选完什么都不会发生（hook 侧关闭时名单视为空集），属于典型「操作无反馈」。
 * 上游 `HiddenAppsScreen` 保存时同样强制 `ENABLED = true`。
 */
private fun writeHiddenComponents(state: PrefState, components: Set<String>) {
    state.set(LauncherHiddenAppsConfig.KEY_HIDDEN_COMPONENTS, components)
    if (components.isNotEmpty()) {
        state.set(LauncherHiddenAppsConfig.KEY_HIDDEN_ENABLED, true)
    }
    state.revision.value++
}

/**
 * 「系统桌面」功能页（宿主 `com.android.launcher`）—— 隐藏应用四件套。
 *
 * 与长按菜单（[LauncherMenuSpec]）共用同一个宿主，但**不各占一个功能树顶层节点**：
 * 本页挂在「系统桌面」节点的二级入口里（见 `ScopePageRegistry` 的 `page(target=)` 约定）。
 *
 * 四项：
 * - **桌面双指张开打开隐藏应用**：桌面上双指外张直接进入隐藏应用文件夹；
 * - **多任务显示已隐藏应用**：最近任务里也列出被隐藏的应用（否则点不回去）；
 * - **彻底隐藏所选应用图标**：总开关 + 名单维护，桌面/抽屉/搜索一律看不到；
 * - **彻底隐藏应用列表**：与上一项同一份名单的列表管理入口（添加/移除）。
 *
 * 改完**立即生效**（名单变更在下次回到桌面时刷模型）；hook 侧实现：
 * [com.fr.colorostools.hook.hookers.LauncherHiddenAppsHook]。
 */
val LauncherHiddenAppsSpec = ScopePageSpec(
    pageKey = "launcher_hidden_apps",
    prefsName = SettingsPrefs,
    packName = HookEnv.PKG_LAUNCHER,
    scopes = arrayOf(HookEnv.PKG_LAUNCHER),
    restartEnabled = true,
) {
    val ctx = context ?: return@ScopePageSpec

    // ---------------------------------------------------------- 系统隐藏文件夹
    category(ctx.getString(R.string.launcher_hidden_section_folder))
    switch(
        key = LauncherHiddenAppsConfig.KEY_PINCH_OUT,
        title = ctx.getString(R.string.launcher_hidden_pinch),
        summary = ctx.getString(R.string.launcher_hidden_pinch_summary),
        requiredScopes = arrayOf(HookEnv.PKG_LAUNCHER),
    )
    switch(
        key = LauncherHiddenAppsConfig.KEY_RECENTS_SHOW_HIDDEN,
        title = ctx.getString(R.string.launcher_hidden_recents),
        summary = ctx.getString(R.string.launcher_hidden_recents_summary),
        requiredScopes = arrayOf(HookEnv.PKG_LAUNCHER),
    )

    // ---------------------------------------------------------- 彻底隐藏
    val hiddenEnabled = state.getBoolean(LauncherHiddenAppsConfig.KEY_HIDDEN_ENABLED, false)
    val components = readHiddenComponents(state)

    category(ctx.getString(R.string.launcher_hidden_section_totally))
    switch(
        key = LauncherHiddenAppsConfig.KEY_HIDDEN_ENABLED,
        title = ctx.getString(R.string.launcher_hidden_enable),
        summary = ctx.getString(R.string.launcher_hidden_enable_summary),
        requiredScopes = arrayOf(HookEnv.PKG_LAUNCHER),
    )
    click(
        title = ctx.getString(R.string.launcher_hidden_list_title),
        summary = ctx.getString(R.string.launcher_hidden_list_summary, components.size),
        onClick = { LauncherHiddenSession.showAppPicker = true },
    )

    // 应用选择对话框（bare = 无卡片外壳；不可见时不渲染任何内容）
    // key 传 null：不占搜索索引（默认 key 会作为索引键，弹出式控件没有可搜索的字面）
    custom(bare = true) {
        if (LauncherHiddenSession.showAppPicker) {
            AppPickerDialog(
                title = ctx.getString(R.string.launcher_hidden_list_title),
                multiMode = true,
                showSystemApps = false,
                // 已选的用包名前缀匹配：名单里存的是「包名/类名」，选择器只认包名
                enabledList = components.mapNotNullTo(HashSet()) { it.substringBefore('/') },
                confirmAllSelected = true,
                onDismiss = { LauncherHiddenSession.showAppPicker = false },
                onConfirm = { apps ->
                    LauncherHiddenSession.showAppPicker = false
                    writeHiddenComponents(
                        state,
                        resolveComponents(ctx, apps.map { it.packageName }, components),
                    )
                },
            )
        }
    }

    // 总开关关闭时给一句「名单仅保留、当前不生效」的提示，避免用户以为功能坏了
    if (!hiddenEnabled && components.isNotEmpty()) {
        click(
            title = ctx.getString(R.string.launcher_hidden_disabled_hint),
            summary = ctx.getString(R.string.launcher_hidden_disabled_hint_summary, components.size),
            onClick = {
                state.set(LauncherHiddenAppsConfig.KEY_HIDDEN_ENABLED, true)
                // set() 已 commit 并 bump，但提示行的可见性依赖于本次重组，显式再抬一次更稳
                state.revision.value++
            },
        )
    }
}

/**
 * 把「包名集合」还原成「组件名集合」。
 *
 * 选择器只回传 `List<AppInfo>`（包名级），而 hook 侧按 `ComponentName` 精确匹配
 * （一个包可能有多个 launcher activity，必须精确到类）。所以：
 * - 已在 [existing] 里的组件，若其包名仍被选中 ⇒ **原样保留**（类名不丢）；
 * - 新增的包 ⇒ 取该包的默认 launcher activity 组件。
 *
 * 取不到默认入口的包（无 launcher activity）直接丢弃 —— 它本来也不会出现在桌面上，
 * 塞进名单只会让 `getActivityList` 过滤空转。
 */
private fun resolveComponents(
    context: android.content.Context,
    selectedPackages: List<String>,
    existing: Set<String>,
): Set<String> {
    val wanted = selectedPackages.toHashSet()
    val out = HashSet<String>(wanted.size)
    // 1) 保留既有的精确组件（包名仍在选中集合里）
    for (component in existing) {
        val pkg = component.substringBefore('/')
        if (pkg in wanted) out.add(component)
    }
    // 2) 新增包 → 补默认入口
    val covered = out.mapTo(HashSet()) { it.substringBefore('/') }
    for (pkg in wanted) {
        if (pkg in covered) continue
        val component = defaultLauncherComponent(context, pkg) ?: continue
        out.add(component)
    }
    return out
}

/** 取包的默认 launcher activity（`包名/类名`）；查不到返回 null */
private fun defaultLauncherComponent(context: android.content.Context, packageName: String): String? =
    runCatching {
        context.packageManager.getLaunchIntentForPackage(packageName)?.component?.flattenToString()
    }.getOrNull()