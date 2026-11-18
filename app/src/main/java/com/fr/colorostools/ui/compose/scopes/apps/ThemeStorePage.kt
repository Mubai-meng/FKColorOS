package com.fr.colorostools.ui.compose.scopes.apps

import com.fr.colorostools.R
import com.fr.colorostools.hook.HookEnv
import com.fr.colorostools.hook.hookers.ThemeStoreConfig
import com.fr.colorostools.ui.compose.scopes.ScopePageSpec
import com.fr.colorostools.utils.SettingsPrefs

/**
 * 主题域（宿主 `com.heytap.themestore` 主题商店 + `com.oplus.themestore` 主题基础服务）
 * 功能页 —— 功能树里的「主题商店」节点。
 *
 * 两个包同源代码：商店负责 UI/广告/到期广播，基础服务负责资源应用与**试用还原执行**。
 * 三个开关在两端各挂各能挂的（见 `ThemeStoreHook.install(pkg)` 的按包分流）；
 * 基础服务不在功能树单独占节点，作为本页的第二个作用域一起重启。
 *
 * 三个开关同属一个宿主，故合并为一页（同一宿主只占一个顶层节点）：
 *
 * 1. **解锁会员功能**：`VipUserDto#getVipStatus` 恒 1（配合 `getEndTime` 恒远期，
 *    避免 `UserInfoManager#T()` 判过期后回落到 CHECKING）+ 资源卡片的会员标记与价格。
 *    hook 侧 [com.fr.colorostools.hook.hookers.ThemeStoreHook.hookVip]。
 * 2. **去广告**：开屏（展示判定 + 自有素材 + DTO 字段）、首页横幅、信息流广告卡。
 *    hook 侧 `ThemeStoreHook#hookAds`。
 * 3. **试用资源不过期**：`ThemeTrialExpireReceiver` 的试用到期广播不处理。
 *    hook 侧 `ThemeStoreHook#hookTrial`。
 *
 * 改完**重启主题商店**生效（钩子装在应用进程启动时；开关值本身 1s 内热生效）。
 *
 * 页面标识 `pageKey = "theme_store"`，与旧功能树节点、搜索索引、`page()` 跳转自映射一致。
 */
val ThemeStoreSpec = ScopePageSpec(
    pageKey = "theme_store",
    prefsName = SettingsPrefs,
    packName = HookEnv.PKG_THEME_STORE,
    // 两个宿主：主题商店（UI/广告/到期广播）+ 主题基础服务（资源应用与试用还原执行端）。
    // 基础服务不是必选（不勾也能用商店侧功能），但「试用不过期」要真正挡住到期还原需要它。
    scopes = arrayOf(HookEnv.PKG_THEME_STORE, HookEnv.PKG_THEME_BASE),
    restartEnabled = true,
) {
    val ctx = context ?: return@ScopePageSpec

    category(ctx.getString(R.string.theme_store_category_vip))
    switch(
        key = ThemeStoreConfig.KEY_VIP,
        title = ctx.getString(R.string.theme_store_vip),
        summary = ctx.getString(R.string.theme_store_vip_summary),
        // 作用域门禁：打开开关前先检测 com.heytap.themestore 是否在作用域内，缺失则弹窗补全
        requiredScopes = arrayOf(HookEnv.PKG_THEME_STORE),
    )

    category(ctx.getString(R.string.theme_store_category_ads))
    switch(
        key = ThemeStoreConfig.KEY_ADS,
        title = ctx.getString(R.string.theme_store_remove_ads),
        summary = ctx.getString(R.string.theme_store_remove_ads_summary),
        requiredScopes = arrayOf(HookEnv.PKG_THEME_STORE),
    )
    switch(
        key = ThemeStoreConfig.KEY_KEEP_TRIAL,
        title = ctx.getString(R.string.theme_store_keep_trial),
        summary = ctx.getString(R.string.theme_store_keep_trial_summary),
        requiredScopes = arrayOf(HookEnv.PKG_THEME_STORE),
    )
}
