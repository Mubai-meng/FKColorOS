package com.fr.colorostools.hook.hookers

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.fr.colorostools.hook.HookEnv
import com.fr.colorostools.ref.Reflect
import com.fr.colorostools.utils.LogUtils
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method

/**
 * 功能：主题域两个宿主（`com.heytap.themestore` 主题商店 + `com.oplus.themestore` 主题基础服务）
 * —— **解锁会员功能** + **去广告** + **试用资源不过期**。
 *
 * 来源与归并（两项要求合并成一份实现，重复 hook 只留一个）：
 * - `fuckapp` 参考项目里的主题商店规则：`VipUserDto` / `WeatherPageResponseDto` /
 *   `ResourceItemDto#getIsVip` / `ThemeGroup#isVip` / `ThemeTrialExpireReceiver`；
 * - `ONextBox`（GPL-3.0）`ThemeStoreHooker` + `ThemeStoreAdsHooker`：VIP getter 表、开屏与广告卡片过滤。
 *
 * ## 零、两个宿主（同一份 themespace 代码，能力不同）
 *
 * | | 主题商店 `com.heytap.themestore` | 主题基础服务 `com.oplus.themestore` |
 * |---|---|---|
 * | 会员 DTO（VipUserDto 等） | ✔ | ✔（同签名，可直接复用） |
 * | 开屏 / 广告卡 / 首页横幅 | ✔ | ✘ 类不存在 |
 * | 首页弹窗 / 促销弹窗 / H5 `ThemeGroup` | ✔ | ✘ 类不存在 |
 * | `ThemeTrialExpireReceiver`（到期广播） | ✔ | ✘ 不存在 |
 * | 试用还原执行端（`nd.s#e0`） | 还原动作由基础服务执行 | ✔ |
 *
 * 所以 [install] 按包名分流：基础服务只装 VIP getter + 试用还原拦截，去广告整段跳过
 * （否则会为 11 个卡片容器 + 开屏管理器刷一堆「类不存在」WARN）。
 * ONextBox 的做法是把两个包都塞进 scope 后跑同一个 hooker；这里保留了同样的覆盖面，
 * 但按包裁剪，日志与功耗都更干净。
 * 两者重复项（`VipUserDto#getVipStatus`、`WeatherPageResponseDto#getVipStatus`、
 * `ResourceItemDto#getIsVip*`、`SplashDto` 广告字段、`ThemeTrialExpireReceiver`）**只挂一次**，
 * 并统一由三个开关控制。所有符号已按本机版本（主题商店 17.11.21）反汇编复核，见文末「版本适配」。
 *
 * ## 一、解锁会员（开关 [ThemeStoreConfig.KEY_VIP]）
 *
 * 会员判定链（17.11.21 实证）：
 * ```
 * UserInfoManager#P() → U() 已登录？ → q3#o() 取 VipUserDto
 *                     → T(dto) 已过期？(endTime>0 && now>=endTime ⇒ CHECKING)
 *                     → dto.getVipStatus()==1 ⇒ VipUserStatus.VALID
 * ```
 * 因此只要让 `VipUserDto.getVipStatus()` 恒 1、`getEndTime()` 恒远期，
 * 整条链就判 VALID，且**未登录时仍走宿主原生的 INVALID**（不伪造登录态，副作用最小）。
 * 这是全 App 唯一的会员状态汇聚点（`je.a#w/x/y`、`s0`、各 Holder 都从这里取）。
 *
 * 其余 getter 是**资源卡片与详情页**各自的会员标记（`isVip` / `isVipAvailable` / 价格），
 * 与状态链独立；只影响客户端展示与「会员可下载」判定，不触碰订单与支付。
 *
 * ## 二、去广告（开关 [ThemeStoreConfig.KEY_ADS]）
 *
 * | 位置 | hook 点 | 动作 |
 * |---|---|---|
 * | 开屏广告（展示） | `com.nearme.themespace.ad.l#y(SplashDto)Z` | false ⇒ 走宿主原生「无广告」分支 |
 * | 开屏广告（自有素材） | `com.nearme.themespace.ad.self.f#M(Context,SplashDto)Z` | false ⇒ 自有广告也不可用 |
 * | 开屏 DTO | `SplashDto#getAdData/getShowTime/getIsSkip` | null / 0 / true |
 * | 首页横幅 | `HomePageDto#getAdBanner` | null |
 * | 信息流广告卡 | 11 个卡片容器的列表 getter | 过滤 `AdCardDto` / `GoodsAdCardDto` 实例 |
 *
 * `ad.l#I(SplashDto)` 的**无广告原生分支**是 `if (dto == null || !y(dto)) → J() + 回调 f0(false)`；
 * 因此让 `y()` 返回 false 与传 null 等价（`label_006f`），但**不需要改实参**，
 * 避免 `proceed(args)` 参数个数/顺序问题。宿主自己的「无广告」路径会正常收尾
 * （清计时器、通知 splash owner），不会卡在开屏。
 *
 * 广告卡片在**数据边界**过滤：卡片容器 getter 返回新列表，宿主原生就允许空列表（无数据时本身返回空）。
 *
 * ## 三、试用资源不过期（开关 [ThemeStoreConfig.KEY_KEEP_TRIAL]）
 *
 * `ThemeTrialExpireReceiver#onReceive` 是「试用到期」广播（主题/字体/动态壁纸/SystemUI/锁屏
 * 五类 SKU + `RESOURCE_EXPIRE`）的唯一处理点，跳过即不撤销试用资源。只拦这 6 个 action，
 * 其余广播（如 `CALL_ACTIVE`）照常放行。
 *
 * ## 性能与功耗
 *
 * - 全部 hook 都在 **DTO getter / 列表 getter / 广播接收**，没有轮询、没有定时器、没有 IO；
 *   广告过滤先在原生列表上 `none(isAd)` 短路，只有本页确实含广告卡时才新建列表。
 * - 三个开关经 [Flags] 做 **1s TTL 快照**：热路径（列表滚动时 getCards 每秒上千次）
 *   只读 volatile 布尔，不反复查 SharedPreferences。
 * - 不引入 DexKit：主题商店 5 个 dex / 60MB，DexKit 首次建索引要几百毫秒且常驻内存，
 *   与「开屏广告只省几秒」的收益不成比例。代价是混淆名（`ad.l` / `ad.self.f`）随应用升级
 *   可能失效 —— 失效时按方法形状校验并打 WARN，绝不静默。
 *
 * ## 版本适配（本机 主题商店 17.11.21 反汇编实证）
 *
 * - `VipUserDto#getVipStatus()I` / `getVipDays()I` / `getEndTime()J` ✔
 * - `WeatherPageResponseDto#getVipStatus()I` ✔
 * - `ResourceItemDto#getIsVip()Ljava/lang/Integer;`（**装箱 Integer，不是 int**）/
 *   `getIsVipAvailable()Ljava/lang/Integer;` ✔
 * - `PublishProductItemDto#getPrice()D` / `getIsVipAvailable()I` ✔
 * - `ThemeGroup#isVip()Z` ✔（H5 主题页 JS 接口）
 * - `ThemeTrialExpireReceiver#onReceive(Context,Intent)V` ✔
 * - `SplashDto#getAdData/getShowTime/getIsSkip` ✔、`HomePageDto#getAdBanner` ✔
 * - 11 个卡片容器 getter 全部存在 ✔；广告卡 DTO 本版**只剩** `AdCardDto`、`GoodsAdCardDto`
 *   （ONextBox 里的 `TemplateAdCardDTO` / `SearchBannerAdCardDto` 已不存在，保留名字无副作用）
 * - 开屏管理器本版是 `com.nearme.themespace.ad.l`（`h` 为回调接口、`l$c` 为展示任务），
 *   展示判定 `y(SplashDto)Z`、自有广告判定 `self.f#M(...)Z` 均按形状校验后安装
 */
internal object ThemeStoreHook {

    private const val TAG = "ThemeStore"

    // ---------------------------------------------------------------- 类名

    private const val CLS_VIP_USER = "com.oppo.cdo.card.theme.dto.vip.VipUserDto"
    private const val CLS_WEATHER_PAGE = "com.oppo.cdo.card.theme.dto.page.WeatherPageResponseDto"
    private const val CLS_RESOURCE_ITEM = "com.oppo.cdo.theme.domain.dto.response.ResourceItemDto"
    private const val CLS_PUBLISH_PRODUCT = "com.oppo.cdo.theme.domain.dto.response.PublishProductItemDto"
    private const val CLS_THEME_GROUP = "com.nearme.themespace.jsinterface.ThemeGroup"

    private const val CLS_TRIAL_RECEIVER = "com.nearme.themespace.trial.ThemeTrialExpireReceiver"

    private const val CLS_SPLASH_DTO = "com.oppo.cdo.card.theme.dto.SplashDto"
    private const val CLS_HOME_PAGE = "com.oppo.cdo.card.theme.dto.page.HomePageDto"

    /** 开屏管理器（混淆名）：本机 17.11.21 = `com.nearme.themespace.ad.l` */
    private const val CLS_SPLASH_MANAGER = "com.nearme.themespace.ad.l"

    /** 自有（本地缓存）开屏素材管理器：本机 = `com.nearme.themespace.ad.self.f` */
    private const val CLS_SELF_SPLASH = "com.nearme.themespace.ad.self.f"

    /** 会员到期时间伪造值：2100-01-01 00:00:00 UTC（远离 now，且不溢出） */
    private const val VIP_END_TIME = 4102444800000L

    /**
     * 主题基础服务的「试用还原」执行体：`nd.s` = `AtomTrialRecoverApplyManager`（混淆名），
     * 入口 `e0()V`。见 [hookTrialRecover]。
     */
    private const val CLS_TRIAL_RECOVER = "nd.s"
    private const val M_TRIAL_RECOVER = "e0"

    /** 会员剩余天数展示值（与上游一致） */
    private const val VIP_DAYS = 999

    /** 卡片容器 → 列表 getter；包前缀固定 `com.oppo.cdo.card.theme.dto`，本机全部复核存在 */
    private val CARD_LIST_GETTERS = listOf(
        "page.HomePageDto" to "getCards",
        "page.ViewLayerWrapDto" to "getCards",
        "AssembleCardDto" to "getCards",
        "RichMultiCardDto" to "getCards",
        "MultiTabCardDto" to "getTabs",
        "MultiTabCardDtoV2" to "getTabs",
        "RankListCardDto" to "getRankCards",
        "TopicListCardDto" to "getTopicList",
        "TopicListV2CardDto" to "getTopicList",
        "TabItemCardDto" to "getItems",
        "TabItemCardDtoV2" to "getItems",
    )

    /**
     * 广告卡类型（含上游历史类型，本版不存在也无副作用）。
     * 按**继承链**判定：宿主可能派生具体广告卡，只看 `javaClass` 会漏。
     */
    private val AD_CARD_TYPES: Set<String> = hashSetOf(
        "com.oppo.cdo.card.theme.dto.ad.AdCardDto",
        "com.oppo.cdo.card.theme.dto.GoodsAdCardDto",
        "com.oppo.cdo.card.theme.dto.ad.template.TemplateAdCardDTO",
        "com.oppo.cdo.card.theme.dto.SearchBannerAdCardDto",
    )

    /** 试用到期类广播：只拦这 6 个 action，其余照常放行 */
    private val TRIAL_EXPIRE_ACTIONS: Set<String> = hashSetOf(
        "com.nearme.themespace.action.THEME_TRIAL_EXPIRE",
        "com.nearme.themespace.action.FONT_TRIAL_EXPIRE",
        "com.nearme.themespace.action.LIVE_WP_TRIAL_EXPIRE",
        "com.nearme.themespace.action.SKU_SYSTEMUI_TRIAL_EXPIRE",
        "com.nearme.themespace.action.SKU_LOCKSCREEN_TRIAL_EXPIRE",
        "com.nearme.themespace.action.RESOURCE_EXPIRE",
    )

    fun install(pkg: String) {
        val base = pkg == HookEnv.PKG_THEME_BASE
        val vip = hookVip(base)
        // 基础服务裁掉了广告/弹窗/JS 接口类，装去广告只会刷一堆「类不存在」的 WARN
        val ads = if (base) 0 else hookAds()
        val trial = if (base) hookTrialRecover() else hookTrial()
        LogUtils.i(
            TAG, "install",
            "${if (base) "主题基础服务" else "主题商店"}（$pkg）钩子已装：" +
                "解锁会员 $vip 处、去广告 $ads 处、试用保留 $trial 处" +
                "（开关 vip=${Flags.vip()} ads=${Flags.ads()} trial=${Flags.keepTrial()}）",
        )
    }

    // ================================================================ 一、解锁会员

    /**
     * @param base 主题基础服务（`com.oplus.themestore`）：同源代码里**没有**
     *   `jsinterface.ThemeGroup`（H5 接口只在商店），缺失属正常，用 info 而不是 WARN。
     */
    private fun hookVip(base: Boolean): Int {
        var n = 0
        // ---- 会员状态汇聚点：getVipStatus()==1 ⇒ VipUserStatus.VALID ----
        n += hookConstGetter(CLS_VIP_USER, "getVipStatus", "vip-status", 1, Flags::vip, base)
        n += hookConstGetter(CLS_VIP_USER, "getVipDays", "vip-days", VIP_DAYS, Flags::vip, base)
        // 到期时间：UserInfoManager#T() 用 endTime 判过期，过期会覆盖 getVipStatus ⇒ 必须一起改
        n += hookConstGetter(CLS_VIP_USER, "getEndTime", "vip-end-time", VIP_END_TIME, Flags::vip, base)
        // ---- 天气 Tab 卡片自己的会员标记 ----
        n += hookConstGetter(CLS_WEATHER_PAGE, "getVipStatus", "weather-vip", 1, Flags::vip, base)
        // ---- 资源卡片：getIsVip / getIsVipAvailable 是装箱 Integer ----
        n += hookConstGetter(CLS_RESOURCE_ITEM, "getIsVip", "res-isvip", 1, Flags::vip, base)
        n += hookConstGetter(CLS_RESOURCE_ITEM, "getIsVipAvailable", "res-isvip-available", 1, Flags::vip, base)
        // ---- 详情页价格与会员可购标记（ONextBox 同款：价格归零 + 会员可用）----
        n += hookConstGetter(CLS_PUBLISH_PRODUCT, "getIsVipAvailable", "product-vip-available", 1, Flags::vip, base)
        n += hookConstGetter(CLS_PUBLISH_PRODUCT, "getPrice", "product-price", 0.0, Flags::vip, base)
        // ---- H5 主题页 JS 接口（fuckapp 来源；基础服务无此类）----
        n += hookConstGetter(CLS_THEME_GROUP, "isVip", "theme-group-vip", true, Flags::vip, base)
        return n
    }

    // ================================================================ 二、去广告

    private fun hookAds(): Int {
        var n = 0
        // ---- 开屏：展示判定（false ⇒ 宿主原生「无广告」分支）----
        val splashManager = Reflect.classOrNull(HookEnv.classLoader, CLS_SPLASH_MANAGER)
        if (splashManager == null) {
            LogUtils.w(TAG, "install", "$CLS_SPLASH_MANAGER 不存在（应用升级后混淆名可能变化），跳过开屏拦截")
        } else {
            val showGate = findSplashShowGate(splashManager)
            if (showGate == null) {
                LogUtils.w(TAG, "install", "开屏展示判定（SplashDto→boolean）不存在，跳过开屏拦截")
            } else {
                n += if (hookConst(method = showGate, label = "splash-show", value = false, flag = Flags::ads)) 1 else 0
            }
        }
        // ---- 开屏：自有素材可用性（自有广告同样不该出现）----
        val selfSplash = Reflect.classOrNull(HookEnv.classLoader, CLS_SELF_SPLASH)
        if (selfSplash == null) {
            LogUtils.w(TAG, "install", "$CLS_SELF_SPLASH 不存在，跳过自有开屏拦截")
        } else {
            val m = selfSplash.declaredMethods.firstOrNull {
                it.name == "M" && it.parameterCount == 2 &&
                    it.returnType == java.lang.Boolean.TYPE &&
                    it.parameterTypes[1]?.name == CLS_SPLASH_DTO
            }
            if (m == null) {
                LogUtils.w(TAG, "install", "自有开屏判定 M(Context,SplashDto)Z 不存在，跳过")
            } else {
                n += if (hookConst(method = m, label = "self-splash", value = false, flag = Flags::ads)) 1 else 0
            }
        }
        // ---- 开屏 DTO 字段（双保险：另有分支直接读 DTO 时也拿不到可展示数据）----
        n += hookConstGetter(CLS_SPLASH_DTO, "getAdData", "splash-ad-data", null, Flags::ads)
        n += hookConstGetter(CLS_SPLASH_DTO, "getShowTime", "splash-show-time", 0, Flags::ads)
        n += hookConstGetter(CLS_SPLASH_DTO, "getIsSkip", "splash-is-skip", true, Flags::ads)
        // ---- 首页横幅 ----
        n += hookConstGetter(CLS_HOME_PAGE, "getAdBanner", "home-banner", null, Flags::ads)
        // ---- 信息流广告卡：容器列表 getter 后置过滤 ----
        for ((shortName, getter) in CARD_LIST_GETTERS) {
            n += hookAdCardList("com.oppo.cdo.card.theme.dto.$shortName", getter)
        }
        return n
    }

    /**
     * 找开屏展示判定：`y(SplashDto)Z`（17.11.21）。
     *
     * 名字漂移时退化为「唯一一个 (SplashDto)→boolean 的方法」——
     * 该形状在 `ad.l` 里唯一（其余是 `G()Z`、`K()Z` 之类无参）。
     */
    private fun findSplashShowGate(cls: Class<*>): Method? {
        val candidates = cls.declaredMethods.filter {
            it.parameterCount == 1 && it.returnType == java.lang.Boolean.TYPE &&
                it.parameterTypes[0]?.name == CLS_SPLASH_DTO
        }
        if (candidates.isEmpty()) return null
        candidates.firstOrNull { it.name == "y" }?.let { return it }
        return candidates.singleOrNull()
    }

    /** 卡片列表 getter：先放行取原生结果，再按需剔除广告卡实例 */
    private fun hookAdCardList(className: String, getterName: String): Int {
        val cls = Reflect.classOrNull(HookEnv.classLoader, className)
        if (cls == null) {
            LogUtils.w(TAG, "install", "$className 不存在，跳过该卡片容器的广告过滤")
            return 0
        }
        val m = cls.declaredMethods.firstOrNull {
            it.name == getterName && it.parameterCount == 0 && !it.isBridge &&
                List::class.java.isAssignableFrom(it.returnType)
        }
        if (m == null) {
            LogUtils.w(TAG, "install", "$className#$getterName()Ljava/util/List; 不存在，跳过")
            return 0
        }
        return if (runCatching {
                HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val result = chain.proceed()
                        // 开关关闭 / 非列表 / 空表：零额外开销直接返回原生值
                        if (!Flags.ads()) return result
                        val source = result as? List<*> ?: return result
                        if (source.isEmpty() || source.none { isAdCard(it) }) return result
                        // 宿主可能返回 unmodifiable 集合 ⇒ 一律新建列表
                        return source.filterNot { isAdCard(it) }
                    }
                })
                true
            }.getOrElse {
                logHookFail(m, it)
                false
            }
        ) 1 else 0
    }

    /** 广告卡判定：沿继承链比对类名（宿主可能派生具体广告卡类） */
    private fun isAdCard(o: Any?): Boolean {
        var c: Class<*>? = o?.javaClass ?: return false
        while (c != null && c != Any::class.java) {
            if (c.name in AD_CARD_TYPES) return true
            c = c.superclass
        }
        return false
    }

    // ================================================================ 三、试用资源不过期
    private fun hookTrial(): Int {
        val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_TRIAL_RECEIVER)
        if (cls == null) {
            LogUtils.w(TAG, "install", "$CLS_TRIAL_RECEIVER 不存在，跳过试用保留")
            return 0
        }
        val m = cls.declaredMethods.firstOrNull {
            it.name == "onReceive" && it.parameterCount == 2 &&
                it.parameterTypes[0] == Context::class.java && it.parameterTypes[1] == Intent::class.java
        }
        if (m == null) {
            LogUtils.w(TAG, "install", "ThemeTrialExpireReceiver#onReceive(Context,Intent) 不存在，跳过")
            return 0
        }
        return if (runCatching {
                HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        if (!Flags.keepTrial()) return chain.proceed()
                        val action = (chain.getArg(1) as? Intent)?.action
                        if (action != null && action in TRIAL_EXPIRE_ACTIONS) return null
                        return chain.proceed()
                    }
                })
                true
            }.getOrElse {
                logHookFail(m, it)
                false
            }
        ) 1 else 0
    }

    /**
     * 主题基础服务侧（`com.oplus.themestore`）的「试用不过期」。
     *
     * 该包**没有** `ThemeTrialExpireReceiver`（到期广播只注册在商店），但它是**实际执行还原**
     * 的那一端：`nd.s`（`AtomTrialRecoverApplyManager.java`）的 `e0()V` 会遍历校验器
     * （`TrialRecoverResInstallValidate` = `hd.l` 等），任一个 `pd.d#b()` 为 false 就
     * `qd.b#u()` **恢复默认主题** —— 这就是「试用到期主题被打回」的最后一步。
     *
     * 直接跳过 `e0()` 即可：它属于 trial-recover 专用 apply 流程，普通主题应用走别的
     * manager（父类 `resourcemanager.apply.b` 里没有 `e0`，已核对）。
     *
     * 类名是混淆名，用**形状锚定**而不是只认名字：
     * 构造 `(Context, ApplyParams, apply/d)` + public 无参 `e0()V` 同时成立才挂，
     * 否则打 WARN 跳过（宁可失效，也不误挂别的方法）。
     */
    private fun hookTrialRecover(): Int {
        val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_TRIAL_RECOVER)
        if (cls == null) {
            LogUtils.w(TAG, "install", "$CLS_TRIAL_RECOVER 不存在（混淆名随版本变化），跳过基础服务试用还原拦截")
            return 0
        }
        val anchored = cls.declaredConstructors.any {
            it.parameterCount == 3 && it.parameterTypes[0]?.name == "android.content.Context" &&
                it.parameterTypes[1]?.name == "com.nearme.themespace.base.apply.model.ApplyParams" &&
                it.parameterTypes[2]?.name == "com.nearme.themespace.resourcemanager.apply.d"
        }
        if (!anchored) {
            LogUtils.w(TAG, "install", "$CLS_TRIAL_RECOVER 构造签名不符（疑似改名复用），跳过试用还原拦截")
            return 0
        }
        val m = cls.declaredMethods.firstOrNull {
            it.name == M_TRIAL_RECOVER && it.parameterCount == 0 && it.returnType == Void.TYPE
        }
        if (m == null) {
            LogUtils.w(TAG, "install", "$CLS_TRIAL_RECOVER#$M_TRIAL_RECOVER()V 不存在，跳过试用还原拦截")
            return 0
        }
        return if (runCatching {
                HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? =
                        if (Flags.keepTrial()) null else chain.proceed()
                })
                true
            }.getOrElse {
                logHookFail(m, it)
                false
            }
        ) 1 else 0
    }

    // ================================================================ 通用工具

    /**
     * 挂「无参 getter → 固定值」的 hook（值在挂载时按返回类型装箱一次，hook 内零分配）。
     *
     * 类型不支持（签名变了）或类/方法不存在时**不挂**并打 WARN ——
     * 返回错类型会在宿主侧抛 CCE，比不生效更糟。
     */
    private fun hookConstGetter(
        className: String,
        methodName: String,
        label: String,
        value: Any?,
        flag: () -> Boolean,
        quiet: Boolean = false,
    ): Int {
        val cls = Reflect.classOrNull(HookEnv.classLoader, className)
        if (cls == null) {
            if (quiet) LogUtils.i(TAG, "install", "$className 不存在，跳过 $label")
            else LogUtils.w(TAG, "install", "$className 不存在，跳过 $label")
            return 0
        }
        val m = cls.declaredMethods.firstOrNull {
            it.name == methodName && it.parameterCount == 0 && !it.isBridge && !it.isSynthetic
        }
        if (m == null) {
            if (quiet) LogUtils.i(TAG, "install", "$className#$methodName() 不存在，跳过 $label")
            else LogUtils.w(TAG, "install", "$className#$methodName() 不存在，跳过 $label")
            return 0
        }
        val boxed = coerceValue(m.returnType, value)
        // 基本类型不能被 null / 错类型顶替（会在宿主侧 NPE 或 CCE）；对象类型允许 null
        if (boxed == null && (value != null || m.returnType.isPrimitive)) {
            LogUtils.w(TAG, "install", "$className#$methodName 返回类型不支持（${m.returnType.name}），跳过 $label")
            return 0
        }
        return if (hookConst(m, label, boxed, flag)) 1 else 0
    }

    /** 把数字/布尔值按方法返回类型装箱；类型不匹配返回 null */
    private fun coerceValue(type: Class<*>, value: Any?): Any? = when (type) {
        java.lang.Integer.TYPE, Integer::class.java -> (value as? Number)?.toInt()
        java.lang.Long.TYPE, java.lang.Long::class.java -> (value as? Number)?.toLong()
        java.lang.Double.TYPE, java.lang.Double::class.java -> (value as? Number)?.toDouble()
        java.lang.Float.TYPE, java.lang.Float::class.java -> (value as? Number)?.toFloat()
        java.lang.Short.TYPE, java.lang.Short::class.java -> (value as? Number)?.toShort()
        java.lang.Byte.TYPE, java.lang.Byte::class.java -> (value as? Number)?.toByte()
        java.lang.Boolean.TYPE, java.lang.Boolean::class.java -> value as? Boolean
        else -> value
    }

    /** 挂「开关打开时返回固定值、否则放行」的钩子；返回是否挂成功 */
    private fun hookConst(method: Method, label: String, value: Any?, flag: () -> Boolean): Boolean =
        runCatching {
            // 开屏展示判定 `ad.l#y` 是 private，必须放开可访问性再挂（与 WeatherPurifyHook 同做法）
            runCatching { method.isAccessible = true }
            HookEnv.module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? =
                    if (flag()) value else chain.proceed()
            })
            true
        }.getOrElse {
            logHookFail(method, it)
            false
        }.also { ok ->
            if (ok) LogUtils.i(TAG, "install", "已挂 ${method.declaringClass.simpleName}#${method.name}（$label）")
        }

    private fun logHookFail(m: Method, t: Throwable? = null) {
        LogUtils.w(
            TAG, "install",
            "挂 ${m.declaringClass.simpleName}#${m.name} 失败" +
                (t?.let { "：${it.javaClass.simpleName}: ${it.message}" } ?: ""),
        )
    }

    // ================================================================ 开关快照

    /**
     * 三个开关的 **1s TTL 快照**。
     *
     * 热路径（列表滚动时 `getCards()` 每秒上千次、每张卡还要判广告类型）只读 volatile 布尔；
     * 每秒最多回读一次 remote prefs。设置页改动 1s 内自动生效，无需重启宿主。
     */
    private object Flags {
        private const val TTL_MS = 1000L

        @Volatile private var at = 0L

        @Volatile private var vipOn = false

        @Volatile private var adsOn = false

        @Volatile private var trialOn = false

        private fun refresh() {
            val now = SystemClock.elapsedRealtime()
            if (at != 0L && now - at < TTL_MS) return
            at = now
            vipOn = HookEnv.settingBoolean(ThemeStoreConfig.KEY_VIP, false)
            adsOn = HookEnv.settingBoolean(ThemeStoreConfig.KEY_ADS, false)
            trialOn = HookEnv.settingBoolean(ThemeStoreConfig.KEY_KEEP_TRIAL, false)
        }

        fun vip(): Boolean {
            refresh()
            return vipOn
        }

        fun ads(): Boolean {
            refresh()
            return adsOn
        }

        fun keepTrial(): Boolean {
            refresh()
            return trialOn
        }
    }
}

/**
 * 主题商店三个开关（App 设置页写入、hook 侧读取，两边同一份 remote prefs）。
 *
 * 键名一经发布不再改：改名 = 用户原有开关值丢失。
 */
internal object ThemeStoreConfig {
    /** 解锁会员功能（会员状态 / 会员资源 / 会员价格） */
    const val KEY_VIP = "theme_store_vip"

    /** 去广告（开屏 + 首页横幅 + 信息流广告卡） */
    const val KEY_ADS = "theme_store_remove_ads"

    /** 试用资源不过期（试用到期广播不生效） */
    const val KEY_KEEP_TRIAL = "theme_store_keep_trial"
}
