package com.fr.colorostools.ui.compose.scopes.apps

import com.fr.colorostools.R
import com.fr.colorostools.hook.HookEnv
import com.fr.colorostools.hook.hookers.WeatherNoBrowserConfig
import com.fr.colorostools.ui.compose.scopes.ScopePageSpec
import com.fr.colorostools.utils.SettingsPrefs

/**
 * 天气 App「详情页不外跳浏览器」（宿主 `com.coloros.weather2`）。
 *
 * 开启后，天气主页各类信息（今日/未来天气详情、生活指数、气象详情、空气质量、
 * 天气标签、公告、运营卡片…）的二级页统一改用应用自带 WebView 展示，不再跳
 * HEYTap/系统浏览器。
 *
 * 已知边界（见 `docs/天气App不外跳浏览器-方案分析.md` §5）：
 * - **热点新闻**的 `heytapbrowser://` 深链是浏览器私有协议，应用内 WebView 加载不了，
 *   暂仍跳浏览器（等拿到真实样本提取其中的 http URL 后再做）；
 * - 应用市场（`market://`）等其它 App 深链不受影响，照常放行。
 *
 * 改完**重启天气**生效（钩子装在天气进程启动时）。hook 侧实现：
 * [com.fr.colorostools.hook.hookers.WeatherNoBrowserHook]。
 */
val WeatherNoBrowserSpec = ScopePageSpec(
    pageKey = "weather_no_browser",
    prefsName = SettingsPrefs,
    packName = HookEnv.PKG_WEATHER,
    scopes = arrayOf(HookEnv.PKG_WEATHER),
    restartEnabled = true,
) {
    val ctx = context ?: return@ScopePageSpec

    switch(
        key = WeatherNoBrowserConfig.KEY_ENABLED,
        title = ctx.getString(R.string.weather_no_browser_enable),
        summary = ctx.getString(R.string.weather_no_browser_enable_summary),
        // 作用域门禁：打开开关前自动检测 com.coloros.weather2 是否在作用域内，缺失则弹窗补全
        requiredScopes = arrayOf(HookEnv.PKG_WEATHER),
    )
}
