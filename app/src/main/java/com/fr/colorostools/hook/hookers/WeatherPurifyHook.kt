package com.fr.colorostools.hook.hookers

import android.content.Context
import com.fr.colorostools.hook.HookEnv
import com.fr.colorostools.ref.Reflect
import com.fr.colorostools.utils.LogUtils
import io.github.libxposed.api.XposedInterface

/**
 * 功能：天气 App（`com.coloros.weather2`）**内容净化** —— 隐藏广告 / 隐藏全部运营卡片。
 *
 * 移植来源：coloros-mod（MIT）`hooks/WeatherHooks#hookWeather` 的 `hide_ads` /
 * `hide_operations` 两项，hook 点按本机（ColorOS 17 / 天气 17.7.4）反汇编重新论证。
 *
 * ## 三个 hook 点
 *
 * | 开关 | 方法 | 动作 |
 * |---|---|---|
 * | 隐藏广告 | `IndexOperationsManager#getOperationsCardAdVisibility()Z` | 返回 false |
 * | 隐藏广告 | `ad.splash.SplashAdManager#enableSplash()Z`（private） | 返回 false |
 * | 隐藏全部运营卡片 | `indexoperations.IndexOperationsItemCreator#create(Context, WeatherWrapper, Object[])` | 返回 null |
 *
 * ### 为什么不用上游的 `getOperationsCards` 过滤
 *
 * 上游在数据边界过滤 `WeatherWrapper#getOperationsCards()` 的 `cardType == 6`（广告）。
 * 本机反汇编发现这个 getter 有 **7 个消费方**，其中
 * `IndexOperationsManager#supportShowIndexOperations` / `#supportShowWeatherWarnTag`
 * 用「list 是否为空」决定整块运营卡片 / 天气预警标签的可见性 ——
 * 一旦某城市只剩广告卡片，过滤后 list 变空会**连带隐藏预警标签**，副作用超出「隐藏广告」。
 *
 * 因此改为在**渲染判定**处拦：`IndexOperationsItemCreator#create` 遍历卡片时，
 * `cardType == 6` 的唯一开关就是 `getOperationsCardAdVisibility()`：
 *
 * ```java
 * invoke-virtual {card}, OperationsCard;->getCardType()I
 * const/4 v4, 0x6
 * if-ne v2, v4, :label_00e8                       // 不是广告 → 走通用分支
 * invoke-static {}, IndexOperationsManager;->getOperationsCardAdVisibility()Z
 * if-eqz v2, :label_00c8                          // ← false 即整张卡片跳过
 * ```
 *
 * 返回 false 只影响广告卡片本身，其它运营卡片、预警标签、数据层 list 全部保持原生。
 * 广告请求也随之减少（`create` 是唯一渲染入口）。
 *
 * ### 开屏广告
 *
 * `SplashAdManager#enableSplash()` 是 `loadAd(Z, Context, Function0)` 的**唯一前置闸门**，
 * 返回 false 时 `loadAd` 走「未请求原因」分支后 `return-void` 提前退出
 * （既不请求也不调用 `showSplashPlaceholderIfNeeded`），是宿主官方支持的关闭路径，
 * 不影响主界面加载。三处 `enableSplash` 调用均为 `invoke-direct`，未被 R8 内联。
 *
 * ### 隐藏全部运营卡片
 *
 * `IndexOperationsItemCreator#create` 是全 App 构建运营卡片列表的**唯一实现**
 * （另一个 `create` 是 3 opcode 的协变 bridge，委托到它）。
 * 返回 null 是宿主自身也认可的「无内容」结果（列表为空时原生同样 `return-object null`），
 * 因此置 null 不会让调用方 NPE。注意本机 `create` 内部还会追加实时提醒
 * （`appendRealTimeNotifyItem`），一并隐藏 —— 符合「全部运营卡片」语义。
 *
 * ## 覆盖边界
 *
 * 不含：信息流/横幅广告（`BannerAdApi`、`OPPOFeedAdManager`）、滚动条公告广告
 * （`NoticeAdImpl`）。这些是天气「热点/资讯」页的广告，与运营卡片无关，上游也未处理。
 *
 * ## 兜底原则
 *
 * 每处独立 `runCatching`；类或方法找不到只记 W 日志后跳过；任何一步失败都不影响宿主。
 */
internal object WeatherPurifyHook {

    private const val TAG = "WeatherPurify"

    private const val CLS_OPERATIONS_MANAGER = "com.oplus.weather.indexoperations.IndexOperationsManager"
    private const val CLS_OPERATIONS_CREATOR = "com.oplus.weather.indexoperations.IndexOperationsItemCreator"
    private const val CLS_OPERATIONS_ITEM = "com.oplus.weather.indexoperations.IndexOperationsItem"
    private const val CLS_SPLASH_MANAGER = "com.oplus.weather.ad.splash.SplashAdManager"

    fun install() {
        hookOperationsCardAd()
        hookSplashAd()
        hookOperationsItem()
        LogUtils.i(
            TAG, "install",
            "天气净化已装（隐藏广告=${hideAds()}，隐藏运营卡片=${hideOperations()}）",
        )
    }

    // ------------------------------------------------------------ 隐藏广告

    /** 运营卡片里的广告位（`cardType == 6`）的可见性判定 */
    private fun hookOperationsCardAd() {
        val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_OPERATIONS_MANAGER)
        if (cls == null) {
            LogUtils.w(TAG, "install", "$CLS_OPERATIONS_MANAGER 不存在，跳过运营卡片广告")
            return
        }
        val m = Reflect.findMethodByShape(
            cls,
            "getOperationsCardAdVisibility",
            emptyList(),
            Boolean::class.javaPrimitiveType,
        )
        if (m == null) {
            LogUtils.w(TAG, "install", "getOperationsCardAdVisibility()Z 不存在，跳过运营卡片广告")
            return
        }
        runCatching {
            HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    if (!hideAds()) return chain.proceed()
                    return false
                }
            })
            LogUtils.i(TAG, "install", "已挂 ${cls.simpleName}#getOperationsCardAdVisibility()")
        }.onFailure {
            LogUtils.w(TAG, "install", "挂 getOperationsCardAdVisibility 失败：${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /** 开屏广告总闸（`loadAd` 的唯一前置判断，false 即提前退出） */
    private fun hookSplashAd() {
        val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_SPLASH_MANAGER)
        if (cls == null) {
            LogUtils.w(TAG, "install", "$CLS_SPLASH_MANAGER 不存在，跳过开屏广告")
            return
        }
        // private 方法：findMethodByShape 内部已 setAccessible
        val m = Reflect.findMethodByShape(
            cls,
            "enableSplash",
            emptyList(),
            Boolean::class.javaPrimitiveType,
        )
        if (m == null) {
            LogUtils.w(TAG, "install", "enableSplash()Z 不存在，跳过开屏广告")
            return
        }
        runCatching {
            HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    if (!hideAds()) return chain.proceed()
                    return false
                }
            })
            LogUtils.i(TAG, "install", "已挂 ${cls.simpleName}#enableSplash()")
        }.onFailure {
            LogUtils.w(TAG, "install", "挂 enableSplash 失败：${it.javaClass.simpleName}: ${it.message}")
        }
    }

    // ------------------------------------------------------------ 隐藏运营卡片

    /** 运营卡片列表构建入口：返回 null = 首页不显示整个运营卡片区 */
    private fun hookOperationsItem() {
        val creator = Reflect.classOrNull(HookEnv.classLoader, CLS_OPERATIONS_CREATOR)
        if (creator == null) {
            LogUtils.w(TAG, "install", "$CLS_OPERATIONS_CREATOR 不存在，跳过隐藏运营卡片")
            return
        }
        val itemCls = Reflect.classOrNull(HookEnv.classLoader, CLS_OPERATIONS_ITEM)
        // create 有两个重载：真实实现返回 IndexOperationsItem，另一个是返回 BindingItem 的 bridge。
        // bridge 会委托到真实实现，只挂真实实现即可（避免重复拦截）。
        val targets = creator.declaredMethods.filter { m ->
            m.name == "create" &&
                m.parameterCount == 3 &&
                !m.isBridge &&
                (itemCls == null || itemCls.isAssignableFrom(m.returnType))
        }
        if (targets.isEmpty()) {
            LogUtils.w(TAG, "install", "IndexOperationsItemCreator#create 非 bridge 重载不存在，跳过")
            return
        }
        var hooked = 0
        for (m in targets) {
            runCatching {
                m.isAccessible = true
                HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        if (!hideOperations()) return chain.proceed()
                        // 首参必须是 Context 才是运营卡片构建入口；不符则原样放行（防御错挂）
                        if (chain.getArg(0) !is Context) return chain.proceed()
                        return null
                    }
                })
                hooked++
            }.onFailure {
                LogUtils.w(TAG, "install", "挂 create 失败：${it.javaClass.simpleName}: ${it.message}")
            }
        }
        LogUtils.i(TAG, "install", "已挂 IndexOperationsItemCreator#create（$hooked 个重载）")
    }

    // ------------------------------------------------------------ 配置

    private fun hideAds(): Boolean = HookEnv.settingBoolean(WeatherPurifyConfig.KEY_HIDE_ADS, false)

    private fun hideOperations(): Boolean =
        HookEnv.settingBoolean(WeatherPurifyConfig.KEY_HIDE_OPERATIONS, false)
}

/**
 * 天气净化的两个开关（App 设置页写入、hook 侧读取，两边同一份 remote prefs）。
 */
internal object WeatherPurifyConfig {
    /** 隐藏广告（运营卡片广告位 + 开屏广告） */
    const val KEY_HIDE_ADS = "weather_hide_ads"

    /** 隐藏全部运营卡片 */
    const val KEY_HIDE_OPERATIONS = "weather_hide_operations"
}
