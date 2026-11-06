package com.fr.colorostools.ui.compose.scopes.apps

import com.fr.colorostools.R
import com.fr.colorostools.hook.HookEnv
import com.fr.colorostools.hook.hookers.WeatherNoBrowserConfig
import com.fr.colorostools.hook.hookers.WeatherPurifyConfig
import com.fr.colorostools.ui.compose.scopes.ScopePageSpec
import com.fr.colorostools.utils.SettingsPrefs

/**
 * 天气 App（宿主 `com.coloros.weather2`）功能页 —— 功能树里的「天气」节点。
 *
 * 三组开关，同一宿主，故合并为一页（同一宿主只占一个顶层节点）：
 *
 * 1. **隐藏广告**：运营卡片里的广告位（`cardType == 6`）+ 开屏广告。
 *    hook 侧 `WeatherPurifyHook#hookOperationsCardAd` / `#hookSplashAd`。
 * 2. **隐藏全部运营卡片**：首页运营卡片区（含实时提醒）整体不显示。
 *    hook 侧 `WeatherPurifyHook#hookOperationsItem`。
 * 3. **详情页应用内打开**：二级页改用应用自带 WebView，不再跳浏览器。
 *    hook 侧 `WeatherNoBrowserHook`（已知边界见 `docs/天气App不外跳浏览器-方案分析.md` §5，
 *    热点新闻的 `heytapbrowser://` 深链仍跳浏览器）。
 *
 * 改完**重启天气**生效（钩子装在天气进程启动时）。
 *
 * 页面标识 `pageKey = "weather"`，与功能树节点、搜索索引、`page()` 跳转自映射一致。
 */
val WeatherSpec = ScopePageSpec(
    pageKey = "weather",
    prefsName = SettingsPrefs,
    packName = HookEnv.PKG_WEATHER,
    scopes = arrayOf(HookEnv.PKG_WEATHER),
    restartEnabled = true,
) {
    val ctx = context ?: return@ScopePageSpec

    category(ctx.getString(R.string.weather_category_purify))
    switch(
        key = WeatherPurifyConfig.KEY_HIDE_ADS,
        title = ctx.getString(R.string.weather_hide_ads),
        summary = ctx.getString(R.string.weather_hide_ads_summary),
        // 作用域门禁：打开开关前自动检测 com.coloros.weather2 是否在作用域内，缺失则弹窗补全
        requiredScopes = arrayOf(HookEnv.PKG_WEATHER),
    )
    switch(
        key = WeatherPurifyConfig.KEY_HIDE_OPERATIONS,
        title = ctx.getString(R.string.weather_hide_operations),
        summary = ctx.getString(R.string.weather_hide_operations_summary),
        requiredScopes = arrayOf(HookEnv.PKG_WEATHER),
    )

    category(ctx.getString(R.string.weather_category_browse))
    switch(
        key = WeatherNoBrowserConfig.KEY_ENABLED,
        title = ctx.getString(R.string.weather_no_browser_enable),
        summary = ctx.getString(R.string.weather_no_browser_enable_summary),
        requiredScopes = arrayOf(HookEnv.PKG_WEATHER),
    )
}
