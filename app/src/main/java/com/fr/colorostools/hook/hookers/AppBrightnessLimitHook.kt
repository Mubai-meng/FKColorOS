package com.fr.colorostools.hook.hookers

import com.fr.colorostools.hook.HookEnv
import com.fr.colorostools.ref.Reflect
import com.fr.colorostools.utils.LogUtils
import io.github.libxposed.api.XposedInterface
import java.util.IdentityHashMap

/**
 * 功能：**解锁 App 亮度限制**（宿主 = system_server）。
 *
 * ## 为什么要做
 *
 * ColorOS 对一批 App 做了「进前台就整体压暗」的特调，切回桌面立刻恢复：
 * - 视频/短视频类（B 站、抖音…）走 `display_brightness_app_list.xml` 的 `<method>` 段，
 *   每段带 `nit` / `ratio` / `switch`，运行时落在 `OplusDisplayGlobalConfig.mAppReduceInfoList`；
 * - 同类 App 的 FOSS 面板调优（`<foss>` 段 → `mAppFossInfoList`）会额外下发 panel 参数；
 * - 支付/出行类 App 被 `<window_brightness>` 钳到 ~600nit；
 * - 白名单以外的 App 被 `<global_brightness_limit>` 钳到 ~1200nit。
 *
 * 结果就是「同一个亮度条，不同 App 亮度不一样」，而这些 App 往往正是看视频/看图的场景。
 *
 * ## 挂点（ColorOS 17 实证，见 `docs/` 逆向记录）
 *
 * 本功能的思路是**改配置对象的开关位**，而不是去 hook 每个消费方的算法 ——
 * 后者要跟着 `caculateReduceBrightness` / `getGlobalLimitNit` 的曲线逻辑走，
 * 一旦 ROM 改算法就失效；改 `mSwitch` 等价于把 XML 里的 `<switch>` 写成 0，
 * 是系统自己认的「关掉这条规则」的原生语义。
 *
 * 1. `OplusDisplayGlobalConfig#getInstance()` post —— 捕获单例，供 [apply] 读改配置。
 *    该类是全局单例，构造早于本 hook，只能从 getter 拿。
 * 2. `OplusFeatureGlobalLimitBrightness` 构造器 + `#onAppEnter(String)` post ——
 *    捕获功能实例，并把 `mDynamicLimitNit`（App 切换时按白名单重算的 nit 上限快照）
 *    统一抬到面板峰值，让所有 App 都享受原本只有相册/桌面才有的待遇。
 * 3. `OplusFeatureWindowBrightness#onAppEnter(String)` post —— 该实例的 `mLimitNit`
 *    在切入名单内 App 时从配置**复制**一份到实例字段，只改配置不会影响已切入的那一次，
 *    所以这里补一刀。
 *
 * 改动的字段与还原见 [apply] / [restoreAll]：**全部记原值快照**，关掉开关立刻写回，
 * 不留「关不掉」的残留。
 *
 * ## 快照为什么按「子对象身份」而不是按 config 实例
 *
 * `OplusDisplayGlobalConfigCloneHelper#cloneAppListExtendedAppAndSceneFromConfig`
 * 是**引用共享**克隆 —— `target.mAppReduceInfoList = config.getAppReduceInfoList()`、
 * `mWindowBrightnessLimitInfo` / `mGlobalBrightnessLimitInfo` / `mUIRConfig` 同样直接赋值。
 * 也就是说 RUS 重载生成新 config 实例后，里面装的还是**同一批子对象**。
 * 若按「config 换实例就清空快照」，重记时读到的会是我刚改过的值（0 / 中和值），
 * 于是关开关时「还原」成 0，配置永久留在关断态。
 *
 * 所以快照一律 `IdentityHashMap<子对象, 原值>`：子对象换新才需要新快照，
 * 子对象没换就一直记着最初的原值。只有 `mPrimaryLimitNit` 是值复制（clone 第 134 行），
 * 故以 config 实例为 key 单独记。
 *
 * ## 为什么要有轮询线程
 *
 * 配置对象会被 RUS（`/data/system/display_brightness_app_list.xml` 的云端更新）
 * 整体重建 —— 重建后新对象里 `mSwitch` 又是 1，纯 hook 改不到。所以：
 * - 每秒比对一次**开关指纹**，用户改设置后 1s 内生效（不用重启）；
 * - 每 [SELF_HEAL_TICKS] 轮无条件重放一次 [apply]（幂等，只在值不符时才写），
 *   把 RUS 重载 / 新配置实例的情况自愈回来。
 *
 * ## 安全边界
 *
 * - `mSwitch` 类改动是「关断规则」，零数值风险；面板峰值拿不到时**跳过**所有
 *   nit 类改动（`mGlobalLimit` / `mLimitNit` / `mDynamicLimitNit`），绝不猜数值。
 * - 只碰上述 4 类限制配置，不碰温控表（`mTemperatureNitTable`）、不碰包名名单、
 *   不碰 `mCustomizedSkipReduceAppList`（那是「厂商定制版额外跳过」的白名单）。
 */
internal object AppBrightnessLimitHook {

    private const val TAG = "AppBrightnessLimit"

    private const val CLS_GLOBAL_CONFIG =
        "com.android.server.display.config.global.OplusDisplayGlobalConfig"

    private const val CLS_GLOBAL_LIMIT =
        "com.android.server.display.feature.postprocess.OplusFeatureGlobalLimitBrightness"

    private const val CLS_WINDOW =
        "com.android.server.display.feature.postprocess.OplusFeatureWindowBrightness"

    /** 窗口亮度上限的中和值：大于任何面板峰值 ⇒ `min(nit, mLimitNit)` 恒等于 nit */
    private const val WINDOW_CAP_OFF_NIT = 9999f

    /** 开关指纹轮询周期 */
    private const val POLL_INTERVAL_MS = 1000L

    /** 每多少轮无条件重放一次（自愈 RUS 重载） */
    private const val SELF_HEAL_TICKS = 30

    // ------------------------------------------------------------ 状态

    /** 全局配置单例（`getInstance` 捕获） */
    @Volatile
    private var config: Any? = null

    /** `OplusFeatureGlobalLimitBrightness` 实例（主屏那一个） */
    @Volatile
    private var limitFeature: Any? = null

    /** `OplusFeatureWindowBrightness` 实例（主屏那一个） */
    @Volatile
    private var windowFeature: Any? = null

    /** 上一次已生效的开关指纹；空串 = 尚未初始化 */
    @Volatile
    private var lastFingerprint = ""

    /** 面板峰值 nit（>0 才算拿到；拿不到时所有 nit 类改动跳过） */
    @Volatile
    private var panelPeakNit = 0f

    // ---- 原值快照：key 一律是「真正被改的那个子对象」，不随 config 实例失效 ----

    /** `AppReduceBrightnessInfo` → 原始 `mSwitch`（int） */
    private val origReduceSwitch = IdentityHashMap<Any, Int>()

    /** `AppFossInfo` → 原始 `mSwitch`（boolean） */
    private val origFossSwitch = IdentityHashMap<Any, Boolean>()

    /** `AppFossInfo` → 原始 `mEnable`（boolean） */
    private val origFossEnable = IdentityHashMap<Any, Boolean>()

    /** `WindowBrightnessLimitInfo` → 原始 `mLimitNit`（float） */
    private val origWindowNit = IdentityHashMap<Any, Float>()

    /** `GlobalBrightnessLimitInfo` → 原始 `mGlobalLimit`（int） */
    private val origGlobalLimit = IdentityHashMap<Any, Int>()

    /** `UIRConfigInfo` → 原始 `mLimitNit`（int） */
    private val origUirNit = IdentityHashMap<Any, Int>()

    /** config 实例 → 原始 `mPrimaryLimitNit`（int；clone 是值复制，只能按实例记） */
    private val origPrimaryLimit = IdentityHashMap<Any, Int>()

    /** 功能实例 → 原始 `mPrimaryLimitNit`（float；构造时从 config 复制） */
    private val origFeaturePrimary = IdentityHashMap<Any, Float>()

    /** 窗口功能实例 → 原始 `mLimitNit`（float；只在名单命中时从 config 复制，需单独还原） */
    private val origWindowInstance = IdentityHashMap<Any, Float>()

    // ------------------------------------------------------------ 装配

    fun install() {
        installCaptureConfig()
        installCaptureLimitFeature()
        installWindowAppEnter()
        startWatcher()
    }

    /**
     * 捕获全局配置单例。
     *
     * 只读 `this`，不干预返回值，零风险。
     */
    private fun installCaptureConfig() {
        val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_GLOBAL_CONFIG)
        if (cls == null) {
            LogUtils.w(TAG, "install", "$CLS_GLOBAL_CONFIG 不存在，解锁 App 亮度限制不可用")
            return
        }
        val m = Reflect.findMethodByShape(cls, "getInstance", emptyList(), null)
        if (m == null) {
            LogUtils.w(TAG, "install", "OplusDisplayGlobalConfig#getInstance() 不存在，跳过")
            return
        }
        val ok = runCatching {
            m.isAccessible = true
            HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val r = chain.proceed()
                    // 单例可能被 RUS 换成新实例，每次都刷新引用（不用 == 判，直接覆盖）
                    if (r != null && config !== r) {
                        config = r
                        LogUtils.i(TAG, "install", "已捕获 OplusDisplayGlobalConfig 单例")
                    }
                    return r
                }
            })
        }.onFailure {
            LogUtils.w(TAG, "install", "挂 getInstance 失败：${it.javaClass.simpleName}: ${it.message}")
        }.isSuccess
        if (ok) LogUtils.i(TAG, "install", "已挂 OplusDisplayGlobalConfig#getInstance（捕获配置单例）")
    }

    /**
     * 捕获 `OplusFeatureGlobalLimitBrightness` 实例。
     *
     * 该类没有单例也没有静态持有者，只能从构造器与 `onAppEnter` 的 `this` 里拿。
     * `onAppEnter` 的 post 钩子同时承担「把 mDynamicLimitNit 抬到面板峰值」的职责。
     */
    private fun installCaptureLimitFeature() {
        val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_GLOBAL_LIMIT)
        if (cls == null) {
            LogUtils.w(TAG, "install", "$CLS_GLOBAL_LIMIT 不存在，全局上限解锁降级")
            return
        }
        // 构造器：开机时创建，覆盖「onAppEnter 还没被调过」的窗口期
        var ctorCount = 0
        for (c in cls.declaredConstructors) {
            runCatching {
                c.isAccessible = true
                HookEnv.module.hook(c).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val r = chain.proceed()
                        captureLimitFeature(chain.thisObject)
                        return r
                    }
                })
                ctorCount++
            }
        }
        // onAppEnter：每次切 App 后系统都会按白名单重算 mDynamicLimitNit，这里改回来
        val enter = Reflect.findMethodByShape(cls, "onAppEnter", listOf<Class<*>?>(null), null)
        if (enter != null) {
            runCatching {
                enter.isAccessible = true
                HookEnv.module.hook(enter).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val r = chain.proceed()
                        captureLimitFeature(chain.thisObject)
                        // 系统刚按白名单重算完，立刻覆盖成面板峰值（= 相册待遇）
                        applyDynamicLimit(chain.thisObject)
                        return r
                    }
                })
            }.onFailure {
                LogUtils.w(TAG, "install", "挂 onAppEnter 失败：${it.javaClass.simpleName}: ${it.message}")
            }
        } else {
            LogUtils.w(TAG, "install", "onAppEnter(String) 不存在，切 App 后需等自愈轮次")
        }
        LogUtils.i(TAG, "install", "已挂 OplusFeatureGlobalLimitBrightness（构造器 $ctorCount 个 + onAppEnter）")
    }

    /**
     * `OplusFeatureWindowBrightness#onAppEnter` post：把实例的 `mLimitNit` 也抬到中和值。
     *
     * 原因：该方法把配置里的 `mLimitNit` **复制**到实例字段，且只在名单内 App 命中时复制。
     * 只改配置的话，正在前台的那个 App 仍然按旧值钳制，得等下次切入。
     */
    private fun installWindowAppEnter() {
        val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_WINDOW) ?: return
        val enter = Reflect.findMethodByShape(cls, "onAppEnter", listOf<Class<*>?>(null), null)
        if (enter == null) {
            LogUtils.w(TAG, "install", "OplusFeatureWindowBrightness#onAppEnter 不存在，窗口上限需等下次切换")
            return
        }
        runCatching {
            enter.isAccessible = true
            HookEnv.module.hook(enter).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val r = chain.proceed()
                    val self = chain.thisObject
                    if (self != null && windowFeature == null && isPrimaryDisplay(self)) {
                        windowFeature = self
                    }
                    if (subEnabled(AppBrightnessLimitConfig.KEY_WINDOW)) {
                        liftWindowInstance(self)
                    }
                    return r
                }
            })
            LogUtils.i(TAG, "install", "已挂 OplusFeatureWindowBrightness#onAppEnter（实例级窗口上限中和）")
        }.onFailure {
            LogUtils.w(TAG, "install", "挂 WindowBrightness#onAppEnter 失败：${it.javaClass.simpleName}: ${it.message}")
        }
    }

    // ------------------------------------------------------------ 轮询

    /**
     * 开关指纹轮询 + 周期自愈。
     *
     * 指纹只拼 5 个布尔（remote prefs 内存镜像，非 IPC），不变时零动作。
     * 变化时立刻 [apply]；另外每 [SELF_HEAL_TICKS] 轮无条件重放一次，把 RUS 重载后的
     * 新配置对象重新压回「已解锁」状态。
     */
    private fun startWatcher() {
        lastFingerprint = fingerprint()
        val t = Thread({
            var tick = 0
            while (true) {
                try {
                    Thread.sleep(POLL_INTERVAL_MS)
                    tick++
                    val fp = fingerprint()
                    if (fp != lastFingerprint) {
                        lastFingerprint = fp
                        apply()
                        LogUtils.i(TAG, "watch", "开关已变化，重新应用")
                    } else if (tick % SELF_HEAL_TICKS == 0) {
                        // 配置可能被 RUS 重建：幂等重放，值不符才写
                        apply()
                    }
                } catch (e: InterruptedException) {
                    return@Thread
                } catch (t: Throwable) {
                    // 单次失败继续下一轮，绝不让线程死掉
                }
            }
        }, "fkcoloros-appbrightness")
        t.isDaemon = true
        runCatching { t.start() }
            .onFailure { LogUtils.w(TAG, "install", "监听线程启动失败：${it.message}") }
            .onSuccess { LogUtils.i(TAG, "install", "开关监听已启动（改设置 1s 内生效）") }
    }

    private fun fingerprint(): String = buildString {
        append(if (enabled()) '1' else '0')
        append(if (subEnabled(AppBrightnessLimitConfig.KEY_APP_REDUCE)) '1' else '0')
        append(if (subEnabled(AppBrightnessLimitConfig.KEY_FOSS)) '1' else '0')
        append(if (subEnabled(AppBrightnessLimitConfig.KEY_WINDOW)) '1' else '0')
        append(if (subEnabled(AppBrightnessLimitConfig.KEY_GLOBAL)) '1' else '0')
    }

    // ------------------------------------------------------------ 应用 / 还原

    /**
     * 幂等应用当前开关状态。
     *
     * 四类改动**各自独立**判定：子开关开则应用、关则把这一项还原回原值。
     * 只在总开关关闭时走 [restoreAll]（四项全还原）。
     *
     * 「单项还原」这个分支不能省：否则用户关掉某个子开关后，配置仍停在关断值，
     * 而总开关还开着 ⇒ 没有任何路径会把它写回去，表现为「子开关关不掉」。
     */
    private fun apply() {
        val gc = config ?: return
        if (!enabled()) {
            restoreAll()
            return
        }
        if (subEnabled(AppBrightnessLimitConfig.KEY_APP_REDUCE)) applyAppReduce(gc) else restoreAppReduce()
        if (subEnabled(AppBrightnessLimitConfig.KEY_FOSS)) applyFoss(gc) else restoreFoss()
        if (subEnabled(AppBrightnessLimitConfig.KEY_WINDOW)) applyWindow(gc) else restoreWindow()
        if (subEnabled(AppBrightnessLimitConfig.KEY_GLOBAL)) applyGlobal(gc) else restoreGlobal()
    }

    /** 关断 App 降亮度规则：每个 `<method>` 段的 `mSwitch`（int）置 0 */
    private fun applyAppReduce(gc: Any) {
        val list = Reflect.getField(gc, "mAppReduceInfoList") as? Collection<*> ?: return
        var n = 0
        for (info in list) {
            if (info == null) continue
            val cur = Reflect.getField(info, "mSwitch") as? Int ?: continue
            // 只有「还没记过」才记原值：重记会把已改成 0 的值当原值，导致关不掉
            origReduceSwitch.getOrPut(info) { cur }
            if (cur != 0 && Reflect.setField(info, "mSwitch", 0)) n++
        }
        if (n > 0) LogUtils.i(TAG, "apply", "App 降亮度特调已关闭 $n/${list.size} 条规则")
    }

    /**
     * 关断 FOSS 面板调优。
     *
     * 两个开关都要改：`getAppPkgs()` 判 `getSwitch()`（mSwitch, boolean），
     * `getFossInfo()` 判 `getFossEnable()`（mEnable, boolean），任一为真仍会进 FOSS 态。
     */
    private fun applyFoss(gc: Any) {
        val list = Reflect.getField(gc, "mAppFossInfoList") as? Collection<*> ?: return
        var n = 0
        for (info in list) {
            if (info == null) continue
            val sw = Reflect.getField(info, "mSwitch") as? Boolean
            if (sw != null) {
                origFossSwitch.getOrPut(info) { sw }
                if (sw && Reflect.setField(info, "mSwitch", false)) n++
            }
            val en = Reflect.getField(info, "mEnable") as? Boolean
            if (en != null) {
                origFossEnable.getOrPut(info) { en }
                if (en && Reflect.setField(info, "mEnable", false)) n++
            }
        }
        if (n > 0) LogUtils.i(TAG, "apply", "FOSS 面板调优已关闭（${list.size} 个 type 段）")
    }

    /** 解除窗口亮度上限：`mWindowBrightnessLimitInfo.mLimitNit`（float）写成中和值 */
    private fun applyWindow(gc: Any) {
        val info = Reflect.getField(gc, "mWindowBrightnessLimitInfo") ?: return
        val cur = Reflect.getField(info, "mLimitNit") as? Float ?: return
        origWindowNit.getOrPut(info) { cur }
        if (cur < WINDOW_CAP_OFF_NIT && Reflect.setField(info, "mLimitNit", WINDOW_CAP_OFF_NIT)) {
            LogUtils.i(TAG, "apply", "窗口亮度上限 $cur -> $WINDOW_CAP_OFF_NIT nit")
        }
        // 实例侧也补一刀（onAppEnter 已经把旧值复制到实例字段）
        windowFeature?.let { liftWindowInstance(it) }
    }

    /**
     * 解除全局亮度上限。
     *
     * 三处必须同口径，否则会被其中任意一层截断：
     * - `mGlobalBrightnessLimitInfo.mGlobalLimit`（int）：非白名单 App 的上限，
     *   `onAppEnter` 读它算 `mDynamicLimitNit`；
     * - `mPrimaryLimitNit`（int）：主屏额外上限，> 0 时 `getGlobalLimitNit` 会再 min 一次；
     * - `mUIRConfig.mConfigInfo.mLimitNit`（int）：UIR（面板侧）上限。
     *
     * 目标值 = 面板峰值（`getMaxPanelNit`）。拿不到就**整体跳过** —— 猜一个数值
     * 要么没效果、要么把上限改小。
     */
    private fun applyGlobal(gc: Any) {
        val peak = ensurePanelPeak() ?: return
        val target = kotlin.math.ceil(peak.toDouble()).toInt()

        val info = Reflect.getField(gc, "mGlobalBrightnessLimitInfo")
        if (info != null) {
            val cur = Reflect.getField(info, "mGlobalLimit") as? Int
            if (cur != null) {
                origGlobalLimit.getOrPut(info) { cur }
                if (cur < target && Reflect.setField(info, "mGlobalLimit", target)) {
                    LogUtils.i(TAG, "apply", "全局亮度上限 $cur -> $target nit")
                }
            }
        }
        val primary = Reflect.getField(gc, "mPrimaryLimitNit") as? Int
        if (primary != null) {
            origPrimaryLimit.getOrPut(gc) { primary }
            // 只有「本来就生效」时才抬：0 或负数表示系统没启用这条限制，别去创造一条
            if (primary in 1 until target && Reflect.setField(gc, "mPrimaryLimitNit", target)) {
                LogUtils.i(TAG, "apply", "主屏上限 $primary -> $target nit")
            }
        }
        val uirInfo = Reflect.getField(Reflect.getField(gc, "mUIRConfig"), "mConfigInfo")
        if (uirInfo != null) {
            val cur = Reflect.getField(uirInfo, "mLimitNit") as? Int
            if (cur != null) {
                origUirNit.getOrPut(uirInfo) { cur }
                if (cur in 1 until target && Reflect.setField(uirInfo, "mLimitNit", target)) {
                    LogUtils.i(TAG, "apply", "UIR 上限 $cur -> $target nit")
                }
            }
        }
        // 当前 App 的 mDynamicLimitNit / 实例 mPrimaryLimitNit 可能是旧值算出来的，立刻对齐
        limitFeature?.let { applyDynamicLimit(it) }
    }

    /**
     * 把功能实例的 nit 上限抬到面板峰值（= 系统给相册/白名单的取值）。
     *
     * `getGlobalLimitNit` 是 `min(mDynamicLimitNit, nit)` 再 `min(mPrimaryLimitNit, …)`，
     * 所以抬高这两个值只会**解除**限制，不会凭空提亮。仅当 global 子开关打开时生效。
     *
     * `mPrimaryLimitNit` 是构造时从 config **复制**的 float 实例字段（
     * `if (greater(config.getPrimaryLimitNit(), 0)) this.mPrimaryLimitNit = …`），
     * 只改 config 对已建好的实例无效，所以这里必须单独补。
     */
    private fun applyDynamicLimit(feature: Any?) {
        if (feature == null) return
        if (!enabled() || !subEnabled(AppBrightnessLimitConfig.KEY_GLOBAL)) return
        if (!isPrimaryDisplay(feature)) return
        val peak = ensurePanelPeak() ?: return

        val cur = Reflect.getField(feature, "mDynamicLimitNit") as? Float
        if (cur != null && kotlin.math.abs(cur - peak) > 0.05f) {
            if (Reflect.setField(feature, "mDynamicLimitNit", peak)) {
                LogUtils.i(TAG, "apply", "App 亮度上限 $cur -> $peak nit（相册待遇）")
                // 字段直写不会触发重算，主动请一次（系统自己改这个值后也是这么做的）
                Reflect.callDeep(feature, "sendUpdatePowerState")
            }
        }

        val prim = Reflect.getField(feature, "mPrimaryLimitNit") as? Float
        if (prim != null && prim > 0f && prim < peak) {
            origFeaturePrimary.getOrPut(feature) { prim }
            if (Reflect.setField(feature, "mPrimaryLimitNit", peak)) {
                LogUtils.i(TAG, "apply", "实例主屏上限 $prim -> $peak nit")
                Reflect.callDeep(feature, "sendUpdatePowerState")
            }
        }
    }

    /** 实例级窗口上限中和：`mIsLimit` 为真时 `postProcessing` 会钳到 `mLimitNit` */
    private fun liftWindowInstance(feature: Any?) {
        if (feature == null) return
        val cur = Reflect.getField(feature, "mLimitNit") as? Float ?: return
        // 先记原值：关掉子开关时要把实例字段也写回，否则会一直钳在 9999（= 中和态）
        origWindowInstance.getOrPut(feature) { cur }
        if (cur < WINDOW_CAP_OFF_NIT && Reflect.setField(feature, "mLimitNit", WINDOW_CAP_OFF_NIT)) {
            LogUtils.i(TAG, "apply", "实例窗口上限 $cur -> $WINDOW_CAP_OFF_NIT nit")
        }
    }

    /**
     * 单项还原：App 降亮度。
     *
     * 遍历完**无条件**清空快照 —— 此时要么已经写回原值，要么本来就等于原值，
     * 两种情况下这份快照都不再有价值；留着只会在子开关反复切换时让旧值越积越多。
     */
    private fun restoreAppReduce(): Int {
        var n = 0
        for ((info, v) in origReduceSwitch) {
            if (Reflect.getField(info, "mSwitch") != v && Reflect.setField(info, "mSwitch", v)) n++
        }
        origReduceSwitch.clear()
        if (n > 0) LogUtils.i(TAG, "restore", "App 降亮度特调已还原 $n 条")
        return n
    }

    /** 单项还原：FOSS */
    private fun restoreFoss(): Int {
        var n = 0
        for ((info, v) in origFossSwitch) {
            if (Reflect.getField(info, "mSwitch") != v && Reflect.setField(info, "mSwitch", v)) n++
        }
        for ((info, v) in origFossEnable) {
            if (Reflect.getField(info, "mEnable") != v && Reflect.setField(info, "mEnable", v)) n++
        }
        origFossSwitch.clear()
        origFossEnable.clear()
        if (n > 0) LogUtils.i(TAG, "restore", "FOSS 面板调优已还原 $n 处")
        return n
    }

    /** 单项还原：窗口上限（配置 + 功能实例） */
    private fun restoreWindow(): Int {
        var n = 0
        for ((info, v) in origWindowNit) {
            if (Reflect.getField(info, "mLimitNit") != v && Reflect.setField(info, "mLimitNit", v)) n++
        }
        for ((feature, v) in origWindowInstance) {
            if (Reflect.getField(feature, "mLimitNit") != v &&
                Reflect.setField(feature, "mLimitNit", v)
            ) n++
        }
        origWindowNit.clear()
        origWindowInstance.clear()
        if (n > 0) LogUtils.i(TAG, "restore", "窗口亮度上限已还原 $n 处")
        return n
    }

    /** 单项还原：全局上限（config 三处 + 功能实例两处） */
    private fun restoreGlobal(): Int {
        var n = 0
        for ((info, v) in origGlobalLimit) {
            if (Reflect.getField(info, "mGlobalLimit") != v && Reflect.setField(info, "mGlobalLimit", v)) n++
        }
        for ((owner, v) in origPrimaryLimit) {
            if (Reflect.getField(owner, "mPrimaryLimitNit") != v &&
                Reflect.setField(owner, "mPrimaryLimitNit", v)
            ) n++
        }
        for ((info, v) in origUirNit) {
            if (Reflect.getField(info, "mLimitNit") != v && Reflect.setField(info, "mLimitNit", v)) n++
        }
        for ((feature, v) in origFeaturePrimary) {
            if (Reflect.getField(feature, "mPrimaryLimitNit") != v &&
                Reflect.setField(feature, "mPrimaryLimitNit", v)
            ) n++
        }
        origGlobalLimit.clear()
        origPrimaryLimit.clear()
        origUirNit.clear()
        origFeaturePrimary.clear()
        if (n > 0) {
            limitFeature?.let { Reflect.callDeep(it, "sendUpdatePowerState") }
            LogUtils.i(TAG, "restore", "全局亮度上限已还原 $n 处")
        }
        return n
    }

    /** 按快照还原全部改动（总开关关闭时调用） */
    private fun restoreAll() {
        val n = restoreAppReduce() + restoreFoss() + restoreWindow() + restoreGlobal()
        if (n > 0) LogUtils.i(TAG, "restore", "已还原 $n 处限制配置")
    }

    // ------------------------------------------------------------ 面板峰值

    /**
     * 面板峰值 nit。
     *
     * 唯一可靠来源是功能实例上的 `getBrightnessModel().getMaxPanelNit()` ——
     * 与系统自己构造 `mDynamicLimitNit` 时走的是同一条路径（自动跟随本机档位表）。
     * 拿不到返回 null，调用方必须跳过所有数值类改动。
     */
    private fun ensurePanelPeak(): Float? {
        val cached = panelPeakNit
        if (cached > 0f) return cached
        val feature = limitFeature ?: return null
        val model = Reflect.callDeep(feature, "getBrightnessModel") ?: return null
        val peak = Reflect.callDeep(model, "getMaxPanelNit") as? Float ?: return null
        if (peak <= 0f) return null
        panelPeakNit = peak
        LogUtils.i(TAG, "install", "面板峰值 = $peak nit")
        return peak
    }

    // ------------------------------------------------------------ 捕获 / 判定

    private fun captureLimitFeature(self: Any?) {
        if (self == null || limitFeature != null) return
        if (!isPrimaryDisplay(self)) return
        limitFeature = self
        LogUtils.i(TAG, "install", "已捕获 OplusFeatureGlobalLimitBrightness")
        // 构造早于本 hook 时 mDynamicLimitNit 已按旧 globalLimit 算过，这里对齐一次
        applyDynamicLimit(self)
    }

    /** `mDisplayId == 0`；字段缺失按主屏处理（宁可捕获也不要空转） */
    private fun isPrimaryDisplay(o: Any): Boolean =
        (Reflect.getField(o, "mDisplayId") as? Int)?.let { it == 0 } ?: true

    private fun enabled(): Boolean =
        HookEnv.settingBoolean(AppBrightnessLimitConfig.KEY_ENABLED, false)

    /** 子开关：总开关关闭时一律视为关 */
    private fun subEnabled(key: String): Boolean =
        enabled() && HookEnv.settingBoolean(key, true)
}
