package com.fr.colorostools.hook.hookers

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.fr.colorostools.hook.HookEnv
import com.fr.colorostools.ref.Reflect
import com.fr.colorostools.utils.LogUtils
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 功能：天气 App（`com.coloros.weather2`）**不外跳浏览器** —— 主页各类信息
 * （今日/未来天气详情、生活指数、气象详情、空气质量、天气标签、公告、运营卡片…）
 * 的二级页跳转改为应用自带 WebView（`WeatherWebActivity`）展示。
 *
 * 方案评估（四类跳转家族的逆向实证 + 方案对比）：`docs/天气App不外跳浏览器-方案分析.md`。
 *
 * ## 实现 = 方案 A：startActivity 级拦截
 *
 * 逆向结论：正式版所有外跳家庭最终都收敛到 `context.startActivity(...)` /
 * `startActivityForResult(...)`，intent 形状只有两种（外加一种自定义 action）：
 *
 * | 来源 | action | package |
 * |---|---|---|
 * | `SecondaryPageUtil.startJumpToBrowser` | VIEW | 浏览器三件套之一（resolve 出的） |
 * | `LocalUtils.launchHeyTapBrowserFirst` | `com.heytap.browser.action.DETAIL_PAGE` | 浏览器三件套之一 |
 * | `IndexOperationsManager.jumpActivity`（deepLink） | VIEW | 无（http 深链） |
 *
 * ⇒ 钩 6 个框架方法（Activity ×4 + ContextWrapper ×2），命中形状过滤后把**第一个参数**
 * 替换成 `WeatherWebActivity` 的 intent 再 `proceed`：
 *
 * ```
 * action ∈ {VIEW, com.heytap.browser.action.DETAIL_PAGE}
 * ∧ data.scheme ∈ {http, https}
 * ∧ ( package ∈ 浏览器三件套 ∨ (package == null ∧ component == null) )
 * ```
 *
 * 过滤器语义（其余一律原样放行）：
 * - `market://` 等其它 App 深链：scheme 不匹配 ⇒ 放行；
 * - App 内自跳（显式 component）：放行；
 * - `heytapbrowser://` 热点新闻 light-stack 深链：scheme 不匹配 ⇒ 放行（暂缓，见评估 §5）；
 * - 本钩子换上的 intent 带显式 component ⇒ 二次进入直接放行，无重入死循环。
 *
 * ## 为什么钩框架方法而不是应用工具类
 * 一网打尽（含未来新增路径）、不依赖应用混淆类名、拿到的是应用拼好参数的最终 URL
 * （from_app_location / 城市参数 / fromWeatherApp 等应用都已拼好）。
 * 应用类入口（SecondaryPageUtil/LocalUtils 各重载）作补充手段保留在评估文档里，本期不用。
 *
 * ## 兜底原则
 * 每一处独立 `runCatching`；类/方法找不到只记 W 日志后跳过；**任何一步失败都原样放行** ——
 * 天气 App 是高频系统应用，宁可不生效也绝不能让它崩。
 */
internal object WeatherNoBrowserHook {

    private const val TAG = "WeatherNoBrowser"

    /** 应用内 WebView Activity（逆向实证：`com/oplus/weather/plugin/webview/WeatherWebActivity`） */
    private const val CLS_WEATHER_WEB = "com.oplus.weather.plugin.webview.WeatherWebActivity"

    /** 普通网页跳转 */
    private const val ACTION_VIEW = "android.intent.action.VIEW"

    /**
     * HEYTap 浏览器详情页私有 action（`LocalUtils.REAL_MODIFY_BROWSER_ACTION` 常量实证值，
     * `launchHeyTapBrowserFirst` 拼 intent 用的就是它）。
     */
    private const val ACTION_DETAIL_PAGE = "com.heytap.browser.action.DETAIL_PAGE"

    /**
     * 浏览器三件套（天气 App 自己 `Constants` 里的三个常量实证值：
     * `com.heytap.browser` / `com.android.browser` / `com.coloros.browser`）。
     */
    private val BROWSER_PACKAGES = setOf(
        "com.heytap.browser",
        "com.android.browser",
        "com.coloros.browser",
    )

    /** 钩子目标：6 个 startActivity 家族框架方法（Intent 恒为第一个参数） */
    private val TARGETS = listOf(
        "android.app.Activity" to ("startActivity" to 1),
        "android.app.Activity" to ("startActivity" to 2),
        "android.app.Activity" to ("startActivityForResult" to 2),
        "android.app.Activity" to ("startActivityForResult" to 3),
        "android.content.ContextWrapper" to ("startActivity" to 1),
        "android.content.ContextWrapper" to ("startActivity" to 2),
    )

    /** WeatherWebActivity 找不到时只记一次 W（避免每次点击都刷） */
    private val webClassMissingLogged = AtomicBoolean(false)

    // ------------------------------------------------------------------ 安装

    fun install() {
        var hooked = 0
        for ((clsName, nameAndParams) in TARGETS) {
            val (name, params) = nameAndParams
            runCatching {
                val cls = Reflect.classOrNull(HookEnv.classLoader, clsName) ?: run {
                    LogUtils.w(TAG, "install", "$clsName 不存在（框架类？），跳过该方法")
                    return@runCatching
                }
                cls.declaredMethods
                    .filter { it.name == name && it.parameterCount == params }
                    .forEach { m ->
                        m.isAccessible = true
                        HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                            override fun intercept(chain: XposedInterface.Chain): Any? =
                                onStartActivity(chain)
                        })
                        hooked++
                    }
            }.onFailure {
                LogUtils.w(TAG, "install", "挂 $clsName#$name($params) 失败：${it.message}")
            }
        }
        LogUtils.i(TAG, "install", "不外跳浏览器已装（$hooked/${TARGETS.size} 个 startActivity 钩子）")
    }

    // ------------------------------------------------------------------ 拦截体

    /**
     * 统一的 startActivity 拦截：命中过滤 ⇒ 换第一个参数为 WeatherWebActivity intent；
     * 其余一切情况（含本钩子自己换上的 intent）原样 `proceed`。
     *
     * 用 `chain.proceed(新参数数组)` 换参（libxposed API 102 的 Chain 支持），
     * 不自己 launch —— 原方法里的权限检查/Instrumentation/动画选项全部保持原生。
     */
    private fun onStartActivity(chain: XposedInterface.Chain): Any? {
        if (!enabled()) return chain.proceed()
        val intent = runCatching { chain.getArg(0) as? Intent }.getOrNull() ?: return chain.proceed()
        val newIntent = runCatching { buildWebIntent(chain.thisObject, intent) }.getOrNull()
            ?: return chain.proceed()
        val args = chain.args.toTypedArray()
        args[0] = newIntent
        LogUtils.i(
            TAG, "redirect",
            "action=${intent.action} pkg=${intent.`package`} url=${intent.data}",
        )
        return chain.proceed(args)
    }

    /**
     * 形状过滤：是「去浏览器的网页 intent」才返回 true。
     * 见类文档里的过滤器语义（其它 App 深链 / App 内自跳 / light-stack 一律放行）。
     */
    private fun shouldRedirect(intent: Intent): Boolean {
        val action = intent.action
        if (action != ACTION_VIEW && action != ACTION_DETAIL_PAGE) return false
        val scheme = intent.data?.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        val pkg = intent.`package`
        if (pkg != null && pkg !in BROWSER_PACKAGES) return false
        if (pkg == null && intent.component != null) return false
        return true
    }

    /**
     * 构造应用内 WebView intent（对齐宿主 `BrowserCommonUtils.startWeatherWebActivity` 的拼法）：
     * 显式 component + 三个 extra + SINGLE_TOP（宿主应用内入口同款）；
     * 非 Activity context 才补 NEW_TASK（否则不需要、且会破坏返回栈）。
     *
     * @return null = 放行（不命中过滤 / 宿主类不存在 / URL 为空）
     */
    private fun buildWebIntent(thisObject: Any?, src: Intent): Intent? {
        if (!shouldRedirect(src)) return null
        val ctx = thisObject as? Context ?: return null
        val url = src.data?.toString() ?: return null
        val webCls = Reflect.classOrNull(HookEnv.classLoader, CLS_WEATHER_WEB) ?: run {
            if (webClassMissingLogged.compareAndSet(false, true)) {
                LogUtils.w(TAG, "buildWebIntent", "$CLS_WEATHER_WEB 不存在，全部放行（应用升级改类名？）")
            }
            return null
        }
        return Intent(ctx, webCls).apply {
            putExtra(extraUrlKey(), url)
            putExtra(extraIsFirstKey(), true)
            putExtra(extraStatisticsKey(), "")
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (ctx !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    // ------------------------------------------------------------------ extra 键名
    //
    // 三个键是 WeatherWebActivity 的 public static final String（dexdump/jadx 实证值）：
    //   intent_params_url / intent_params_isFirst / intent_params_statistics
    // 优先反射读常量（应用改值时自动跟随），读不到用硬编码兜底。

    private val extraUrl: String by lazy { staticString("INTENT_PARAMS_URL", "intent_params_url") }
    private val extraIsFirst: String by lazy { staticString("INTENT_PARAMS_IS_FIRST", "intent_params_isFirst") }
    private val extraStatistics: String by lazy { staticString("INTENT_PARAMS_STATISTICS", "intent_params_statistics") }

    private fun extraUrlKey() = extraUrl
    private fun extraIsFirstKey() = extraIsFirst
    private fun extraStatisticsKey() = extraStatistics

    private fun staticString(field: String, fallback: String): String = runCatching {
        val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_WEATHER_WEB)
        val f = cls?.let { Reflect.findField(it, field) }
        (f?.get(null) as? String)?.takeIf { it.isNotEmpty() } ?: fallback
    }.getOrNull() ?: fallback

    // ------------------------------------------------------------------ 配置

    /** 开关快照（remote prefs 内存读，无 IO；每次 startActivity 读一次，点击级频率可忽略） */
    private fun enabled(): Boolean = HookEnv.settingBoolean(WeatherNoBrowserConfig.KEY_ENABLED, false)
}

/**
 * 天气「不外跳浏览器」的开关（App 设置页写入、hook 侧读取，两边同一份 remote prefs）。
 */
internal object WeatherNoBrowserConfig {
    const val KEY_ENABLED = "weather_no_browser_enabled"
}
