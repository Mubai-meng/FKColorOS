package com.fr.colorostools.hook.hookers

import android.os.SystemClock
import com.fr.colorostools.hook.HookEnv
import com.fr.colorostools.ref.Reflect
import com.fr.colorostools.utils.LogUtils
import io.github.libxposed.api.XposedInterface

/**
 * 功能：**自动亮度低照度提升**（宿主 = system_server）。
 *
 * ## ⚠️ ColorOS 17 关键实证（2026-11-19，本机 services.jar / oplus-services.jar）
 *
 * 旧实现挂 `com.android.server.display.AutomaticBrightnessController#calculateAmbientLux(JJ)F`
 * 在本机**永远不触发**（钩子能装上、日志有记录，但运行时零调用）：
 * - `DisplayPowerController.mAutomaticBrightnessController` 字段类型是
 *   `Lcom/android/server/display/IOplusAutomaticBrightnessController;`，不是 AOSP 的 ABC；
 * - 实现类 `OplusAutomaticBrightnessController` 直接 `extends java.lang.Object`，
 *   既不继承 `AutomaticBrightnessController`，也没有 `calculateAmbientLux`；
 * - 真实链路：`OplusBrightnessAmbientAdapter` 处理环境光 → `OplusLuxResult.mFinalLux`
 *   → `OplusAutomaticBrightnessController#onLuxResultChange`（唯一消费 `getFinalLux()` 算亮度）
 *   → `OplusBrightnessPostProcessingManager#postProcessing`（各特性再改 nit）。
 *
 * 因此旧钩子属于「装成功但空转」：install 日志齐全，实际一次都不进。
 *
 * ## 挂点
 *
 * 1. `OplusLuxResult#getFinalLux()F` post-hook —— 放大系统相信的环境光：
 *    `lux' = lux + lux × k(lux) + 补偿(lux)`，增益与补偿在 `生效上限` 处线性淡出为 0，
 *    保证跨过上限时输出连续、不跳变（详见 [boost]）；≥ 生效上限完全直通。
 * 2. `OplusAutomaticBrightnessController#validHandleUpdateBrightness` —— **改参数立即生效**的闸门。
 * 系统只在 `OplusLuxResult#isNeedUpdate()`（`mFinalLux != mLastFinalLux`）时才重算亮度，
 * 环境光稳定时 lux 不变 ⇒ 用户改了滑块，屏幕**不会**有任何反应，必须等环境光变化。
 * 这里在参数指纹变化后放行一次，配合 [triggerRecompute] 主动调
 * `OplusAutomaticBrightnessController#forceUpdateBrightness()`，把「等环境光」变成「1 秒内」。
 * 3. `OplusFeatureDarkEnvReduceBrightness#shouldSkipProcessing(Builder)Z` → true ——
 *    **仅**用于解除 ColorOS 自带的「长时间暗环境下自动再降一档亮度」。
 *    ⚠️ 语义边界（别误读）：`mShouldReduce` 只在 `onScreenEvent(isOn=true)` 时计算，
 *    条件是「连续亮屏 ≥ 1~2 小时」或「已降过且 20 分钟内」⇒ 绝大多数场景该方法本来就
 *    返回 `true`，本 hook 是空转。它**不是**暗环境变亮的主要来源，主要来源是挂点 1。
 *    保留它的意义只是防「亮屏 1~2 小时后屏幕又被压暗」这一个场景。
 *
 * 三者共用同一个总开关；AOSP 方法作为兜底保留（其它 ROM / 链路回归时仍可用）。
 *
 * ## 参数换算
 * - 百分比键（提升强度 / 强度上限）：存 160 = 1.6 倍
 * - lux 键（低照度补偿 / 生效上限）：直接是 lux
 */
internal object AutoBrightnessHook {

    private const val TAG = "AutoBrightness"

    /** ColorOS 17 实际链路：环境光结果载体 */
    private const val CLS_OPLUS_LUX_RESULT =
        "com.android.server.display.feature.ambience.utils.OplusLuxResult"

    /** ColorOS 17 实际链路：暗环境降亮度后处理 */
    private const val CLS_DARK_ENV_REDUCE =
        "com.android.server.display.feature.postprocess.OplusFeatureDarkEnvReduceBrightness"

    /** ColorOS 17 实际链路：Oplus 自动亮度控制器（持有 mBrightnessModel 与重算入口） */
    private const val CLS_OPLUS_ABC =
        "com.android.server.display.OplusAutomaticBrightnessController"

    /** AOSP 兜底（其它 ROM 或未来链路回归） */
    private const val CLS_ABC = "com.android.server.display.AutomaticBrightnessController"

    /** 「强制刷新」放行窗口：`forceUpdateBrightness` 内部是 post，留 2s 足够覆盖调度延迟 */
    private const val FORCE_WINDOW_MS = 2000L

    /** 参数轮询周期。每秒读 5 个 remote prefs（内存镜像，非跨进程 IPC），开销可忽略 */
    private const val POLL_INTERVAL_MS = 1000L

    /** 总开关关闭时的空闲轮询周期（线程仍在，只是长睡；见 [startParamWatcher]） */
    private const val IDLE_POLL_MS = 30_000L

    /**
     * ABC 实例。从 `onLuxResultChange` 的 `thisObject` 捕获 —— 该类没有单例，
     * 这是唯一能拿到它的途径。用于参数变化后主动触发一次亮度重算。
     */
    @Volatile
    private var abcInstance: Any? = null

    /**
     * 参数指纹刚变化 ⇒ 在 [forceDeadline] 之前放行一次 `validHandleUpdateBrightness`。
     *
     * 用截止时间而不是纯布尔：`forceUpdateBrightness()` 是 `mPowerHandler.post` 异步执行，
     * 若 `mLuxResult` / `mPowerHandler` 为 null 它根本不会跑，布尔会永远挂着 true，
     * 导致之后第一次真实环境光更新也被无谓放行。带期限后最多影响 2 秒。
     */
    @Volatile
    private var forceDeadline: Long = 0L

    /** 上一次已生效的参数指纹（含开关），空串 = 尚未初始化 */
    @Volatile
    private var lastFingerprint: String = ""

    /** 「控制器未捕获」只提示一次，避免每秒刷日志（见 [triggerRecompute]） */
    @Volatile
    private var warnedNoController = false

    /**
     * 热路径参数快照。
     *
     * [boost] 挂在 `OplusLuxResult#getFinalLux()` 上 —— 每次环境光回调都会被调用（1~10Hz），
     * 而原实现每次都要 5 次 prefs 读（开关 + 4 个参数）+ 4 次 `coerceIn`。这些参数在一次
     * 会话内几乎不变，完全没必要每次重读。
     *
     * 由 [refreshSnapshot] 在指纹变化时刷新；[boost] 只读这几个 volatile 字段。
     */
    private class Params(
        @JvmField val enabled: Boolean,
        @JvmField val factor: Float,
        @JvmField val offset: Float,
        @JvmField val luxMax: Float,
        @JvmField val maxGain: Float,
    )

    /** 参数快照引用（整体替换，天然原子；读侧只取一次引用再拆字段，不会读到撕裂的组合） */
    @Volatile
    private var params: Params? = null

    /** 读一次 prefs 组装快照；[boost] 不在热路径做这个 */
    private fun readParams(): Params = Params(
        enabled = enabled(),
        factor = configFactor(),
        offset = configOffset(),
        luxMax = configLuxMax(),
        maxGain = configMaxGain(),
    )

    /** 刷新快照（指纹变化 / 线程启动时调用） */
    private fun refreshSnapshot(): Params {
        val p = readParams()
        params = p
        return p
    }

    fun install() {
        val oplusLux = installOplusLux()
        // 暗环境降亮度：即使 lux 放大，长时间亮屏后它也会把结果压回去。
        // 失败不阻塞主链路（不同 ROM 可能没有这个后处理特性）。
        installSkipDarkEnvReduce()
        if (oplusLux) {
            // 「改参数立即生效」三件套：捕获实例 → 放行闸门 → 指纹轮询。
            // 捕获点都挂不上时轮询毫无意义（triggerRecompute 恒 false），直接不起线程。
            val canCapture = installCaptureController()
            installForceUpdateGate()
            if (canCapture) startParamWatcher()
        } else {
            installAospFallback()
        }
    }

    // ------------------------------------------------------------ 挂点 1：Oplus 环境光放大

    private fun installOplusLux(): Boolean {
        val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_OPLUS_LUX_RESULT)
        if (cls == null) {
            LogUtils.w(TAG, "install", "$CLS_OPLUS_LUX_RESULT 不存在，回退 AOSP 兜底")
            return false
        }
        val m = Reflect.findMethodByShape(
            cls,
            "getFinalLux",
            listOf<Class<*>?>(),
            Float::class.javaPrimitiveType,
        )
        if (m == null) {
            LogUtils.w(TAG, "install", "OplusLuxResult#getFinalLux()F 不存在，回退 AOSP 兜底")
            return false
        }
        val ok = runCatching {
            m.isAccessible = true
            HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val r = chain.proceed()
                    val lux = r as? Float ?: return r
                    return boost(lux) ?: r
                }
            })
        }.onFailure {
            LogUtils.w(TAG, "install", "挂 getFinalLux 失败：${it.javaClass.simpleName}: ${it.message}")
        }.isSuccess
        if (ok) {
            LogUtils.i(TAG, "install", "已挂 ${cls.simpleName}#getFinalLux()F（ColorOS Oplus 链路，低照度提升）")
        }
        return ok
    }

    // ------------------------------------------------------------ 立即生效 1/3：捕获控制器实例

    /**
     * 捕获 `OplusAutomaticBrightnessController` 实例。
     *
     * 该类**没有单例**（`getInstance` 零命中），只能从它自己的方法里取 `this`。
     * `onLuxResultChange(OplusLuxResult)` 是环境光每次更新必经的入口，且 `public`，
     * 作为捕获点是零风险的（只读 `thisObject`，不干预返回值）。
     */
    private fun installCaptureController(): Boolean {
        val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_OPLUS_ABC)
        if (cls == null) {
            LogUtils.w(TAG, "install", "$CLS_OPLUS_ABC 不存在，「改参数立即生效」不可用")
            return false
        }
        val m = Reflect.findMethodByShape(
            cls,
            "onLuxResultChange",
            listOf<Class<*>?>(null),
            null,
        )
        if (m == null) {
            LogUtils.w(TAG, "install", "onLuxResultChange(OplusLuxResult)V 不存在，立即生效不可用")
            return false
        }
        val ok = runCatching {
            HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val self = chain.thisObject
                    // 多显示器时只认 displayId == 0（主屏）：别把外接屏的控制器当成主屏
                    if (self != null && isPrimaryDisplay(self) && abcInstance == null) {
                        abcInstance = self
                    }
                    return chain.proceed()
                }
            })
        }.onFailure {
            LogUtils.w(TAG, "install", "挂 onLuxResultChange 失败：${it.javaClass.simpleName}: ${it.message}")
        }.isSuccess
        if (ok) LogUtils.i(TAG, "install", "已挂 ${cls.simpleName}#onLuxResultChange（捕获控制器实例）")
        return ok
    }

    /** `mDisplayId == 0` 判定；字段缺失时按「是主屏」处理（宁可捕获也不要空转） */
    private fun isPrimaryDisplay(controller: Any): Boolean =
        (Reflect.getField(controller, "mDisplayId") as? Int)?.let { it == 0 } ?: true

    // ------------------------------------------------------------ 立即生效 2/3：放行闸门

    /**
     * `validHandleUpdateBrightness(OplusLuxResult)Z` 是 `onLuxResultChange` 内部的**唯一早退闸**：
     *
     * ```java
     * if (!result.isNeedUpdate()) { AmbienceLog.w(TAG, "lack of parameters!!!"); return false; }
     * if (this.mIsNeedLockBright) { ...; return false; }   // GameSpace 锁亮度
     * ```
     *
     * 而 `isNeedUpdate()` = `mFinalLux != mLastFinalLux`。环境光稳定时两者相等 ⇒ 返回 false
     * ⇒ 即使我们主动调 `forceUpdateBrightness()`，也会在这里被挡回去，屏幕毫无反应。
     *
     * 因此在「参数指纹刚变化」时放行**一次**：让系统用当前（已被放大的）lux 重新算一遍 nit。
     * 放行后立即复位，绝不影响后续正常的环境光去重逻辑。
     *
     * 实现上**先跑原方法再决定是否改写**：
     * - 原方法返回 true（真实的环境光变化）⇒ 直接放行，不消耗窗口；
     * - 原方法返回 false ⇒ 才检查是不是被 `mIsNeedLockBright`（游戏空间锁亮度）挡的，
     *   是则尊重系统决策、返回 false；否则在窗口内改判为 true。
     *
     * 这样既不会抢游戏空间的锁亮度，也不会因为窗口没被消耗而影响后续正常更新。
     */
    private fun installForceUpdateGate(): Boolean {
        val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_OPLUS_ABC) ?: return false
        val m = Reflect.findMethodByShape(
            cls,
            "validHandleUpdateBrightness",
            listOf<Class<*>?>(null),
            Boolean::class.javaPrimitiveType,
        )
        if (m == null) {
            LogUtils.w(TAG, "install", "validHandleUpdateBrightness(OplusLuxResult)Z 不存在，立即生效降级")
            return false
        }
        val ok = runCatching {
            m.isAccessible = true
            HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    // 先跑原逻辑：正常的环境光更新（isNeedUpdate 为真）本来就会返回 true，
                    // 那种情况不消耗放行窗口，窗口只留给「被去重挡住」的那一次。
                    val r = chain.proceed()
                    if (r == true) return r
                    val dl = forceDeadline
                    if (dl == 0L || SystemClock.uptimeMillis() > dl) return r
                    // 系统挡住了，但要区分原因：游戏空间锁亮度属于系统决策，不抢
                    if ((Reflect.getField(chain.thisObject, "mIsNeedLockBright") as? Boolean) == true) {
                        LogUtils.d(TAG, "recompute", "当前被游戏空间锁定亮度，放弃本次强制刷新")
                        return r
                    }
                    forceDeadline = 0L // 只放行一次
                    return true
                }
            })
        }.onFailure {
            LogUtils.w(TAG, "install", "挂 validHandleUpdateBrightness 失败：${it.javaClass.simpleName}: ${it.message}")
        }.isSuccess
        if (ok) LogUtils.i(TAG, "install", "已挂 ${cls.simpleName}#validHandleUpdateBrightness（参数变化放行一次）")
        return ok
    }

    // ------------------------------------------------------------ 立即生效 3/3：指纹轮询

    /**
     * 参数变化检测线程。
     *
     * 为什么必须有它：挂点 1 是「读的时候放大」，只有环境光回调（`onAmbienceChanged`）才会
     * 触发 `getFinalLux()`。用户坐在室内不动、环境光稳定时，lux 值不变 ⇒ 系统不重算
     * ⇒ 改滑块后屏幕**永远**看不到变化，直到环境光自己变一次。
     *
     * 线程本身极轻：每秒只读 5 个 remote prefs 拼一个指纹字符串，**指纹不变时零动作**
     * （不反射、不触发重算）。只有用户真的改了参数才走一次重算。
     *
     * 指纹**只在重算成功后提交**：若控制器实例还没捕获到（刚开机 / 刚重启作用域），
     * 下一秒会再试，不会因为「已经记下新指纹」而永久丢掉这次改动。
     */
    private fun startParamWatcher() {
        // 先填快照：之后 boost() 在热路径上只读 volatile 引用，不再碰 prefs
        lastFingerprint = fingerprint()
        refreshSnapshot()
        val t = Thread({
            while (true) {
                try {
                    // 总开关关闭 ⇒ 长睡。线程不能退（remote prefs 无变更通知，退了就没人能唤醒），
                    // 但每秒醒一次会让 CPU 进不了深度 idle，直接体现在待机耗电上。
                    val idle = !enabled()
                    Thread.sleep(if (idle) IDLE_POLL_MS else POLL_INTERVAL_MS)
                    val fp = fingerprint()
                    if (fp != lastFingerprint && triggerRecompute()) {
                        lastFingerprint = fp
                        // 参数已生效 ⇒ 刷新热路径快照（boost 的放大参数随之更新）
                        refreshSnapshot()
                    }
                } catch (e: InterruptedException) {
                    return@Thread
                } catch (t: Throwable) {
                    // 单次失败继续下一轮，绝不让线程死掉
                }
            }
        }, "fkcoloros-autobrightness")
        t.isDaemon = true
        runCatching { t.start() }
            .onFailure { LogUtils.w(TAG, "install", "参数监听线程启动失败：${it.message}") }
            .onSuccess { LogUtils.i(TAG, "install", "参数监听已启动（改设置 1s 内重算亮度）") }
    }

    /** 当前生效参数的指纹；开关状态也计入，关闭时同样要立刻恢复原生亮度 */
    private fun fingerprint(): String = buildString {
        append(if (enabled()) '1' else '0')
        append('|').append(configFactor())
        append('|').append(configOffset())
        append('|').append(configLuxMax())
        append('|').append(configMaxGain())
    }

    /**
     * 主动让系统重算一次亮度。
     *
     * 通道选择：`OplusAutomaticBrightnessController#forceUpdateBrightness()` —— 系统自己就在用
     * （`OplusFeatureVisualComfort` 第 237 行调它），内部 `mPowerHandler.post` 后重跑
     * `onLuxResultChange(mLuxResult)`，等价于「假装环境光刚更新」。
     *
     * 前置条件：`mLuxResult != null`（至少有过一次环境光回调）。没有时静默跳过 ——
     * 等第一次环境光到来自然会按新参数算，无需额外处理。
     *
     * @return true = 已成功发起重算（调用方据此决定是否提交新指纹）
     */
    private fun triggerRecompute(): Boolean {
        val abc = abcInstance
        if (abc == null) {
            // 还没有过环境光回调：等下次 onLuxResultChange 自然生效，指纹不提交以便下轮重试。
            // 只提示一次 —— 否则每秒一条会把日志刷满（开屏后要等第一次环境光才捕获得到）。
            if (!warnedNoController) {
                warnedNoController = true
                LogUtils.d(TAG, "recompute", "控制器实例未捕获（等首次环境光回调），期间改动将在捕获后生效")
            }
            return false
        }
        if (warnedNoController) {
            warnedNoController = false
            LogUtils.d(TAG, "recompute", "控制器实例已捕获，「改参数立即生效」就绪")
        }
        // 注意：Reflect.callByName 内部吞异常并返回 null（void 方法成功也是 null），
        // 因此不能用 runCatching 判断成败 —— 先确认方法存在，再决定是否置位。
        val m = Reflect.findMethod(abc.javaClass, "forceUpdateBrightness", 0)
        if (m == null) {
            LogUtils.w(TAG, "recompute", "forceUpdateBrightness 不存在，跳过本次重算")
            return false
        }
        // 先置位再调用：forceUpdateBrightness 是 post 异步，闸门可能比这行代码更晚执行
        forceDeadline = SystemClock.uptimeMillis() + FORCE_WINDOW_MS
        Reflect.call(abc, m)
        LogUtils.i(TAG, "recompute", "参数已变化，主动重算亮度")
        return true
    }

    // ------------------------------------------------------------ 挂点 3：跳过暗环境降亮度

    private fun installSkipDarkEnvReduce(): Boolean {
        val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_DARK_ENV_REDUCE)
        if (cls == null) {
            LogUtils.w(TAG, "install", "$CLS_DARK_ENV_REDUCE 不存在，跳过「暗环境降亮度」拦截")
            return false
        }
        // Boolean shouldSkipProcessing(OplusDisplayBrightnessState$Builder)
        // 参数类型传 null = 该位任意，避免多加载一个内部类做形状匹配
        val m = Reflect.findMethodByShape(
            cls,
            "shouldSkipProcessing",
            listOf<Class<*>?>(null),
            Boolean::class.javaPrimitiveType,
        )
        if (m == null) {
            LogUtils.w(TAG, "install", "shouldSkipProcessing(Builder)Z 不存在，跳过「暗环境降亮度」拦截")
            return false
        }
        val ok = runCatching {
            m.isAccessible = true
            HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    if (!enabled()) return chain.proceed()
                    // 主动判定「无需处理」⇒ postProcessing 提前返回，不再 adjustBrightness 压暗
                    return true
                }
            })
        }.onFailure {
            LogUtils.w(TAG, "install", "挂 shouldSkipProcessing 失败：${it.javaClass.simpleName}: ${it.message}")
        }.isSuccess
        if (ok) {
            LogUtils.i(TAG, "install", "已挂 ${cls.simpleName}#shouldSkipProcessing（长时间暗环境不再降亮度）")
        }
        return ok
    }

    // ------------------------------------------------------------ 兜底：AOSP 链路

    private fun installAospFallback() {
        val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_ABC)
        if (cls == null) {
            LogUtils.w(TAG, "install", "$CLS_ABC 不存在，跳过自动亮度低照度提升")
            return
        }
        val m = Reflect.findMethodByShape(
            cls,
            "calculateAmbientLux",
            listOf(
                Long::class.javaPrimitiveType as Class<*>,
                Long::class.javaPrimitiveType as Class<*>,
            ),
            Float::class.javaPrimitiveType,
        )
        if (m == null) {
            LogUtils.w(TAG, "install", "calculateAmbientLux(JJ)F 不存在，跳过自动亮度低照度提升")
            return
        }
        runCatching {
            HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val r = chain.proceed()
                    val lux = r as? Float ?: return r
                    return boost(lux) ?: r
                }
            })
            LogUtils.i(TAG, "install", "已挂 ${cls.simpleName}#calculateAmbientLux(JJ)F（AOSP 兜底，低照度提升）")
        }.onFailure {
            LogUtils.w(TAG, "install", "挂 calculateAmbientLux 失败：${it.javaClass.simpleName}: ${it.message}")
        }
    }

    // ------------------------------------------------------------ 放大算法（返回 null = 不改写）

    /** 采样计数：仅供「详细日志」节流，不参与任何判定 */
    private val sampleCount = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * 低照度放大，**全程单调、在上限处连续收敛到直通**。
     *
     * ```
     * w        = 1 - lux / luxMax                     // 淡出权重：0 lux 满额，luxMax 归零
     * k        = min(提升强度, 强度上限) - 1           // 有效增益（被「强度上限」夹住）
     * offEff   = min(低照度补偿, luxMax × (1 - k)) × w // 补偿不得超过满增益后的剩余空间
     * out      = min(lux × (1 + k·w) + offEff, luxMax)
     * ```
     *
     * ## 为什么必须这么写（2026-11-22 修正，前两版都错了）
     *
     * 直通分支是 `lux >= luxMax ⇒ 原样返回`。若 `lux < luxMax` 时把结果抬到
     * `luxMax × 强度上限`，那么环境光从 `luxMax - ε` 升到 `luxMax` 时，输出会从
     * （例：1.6×27+3 = 46.2）**骤降到 30** —— 屏幕「突然变暗一档」。
     * 所以增益与补偿都按 `w` 线性淡出，`lux → luxMax` 时输出 → `luxMax`，与直通值衔接。
     *
     * **但只做淡出还不够**：旧版 `coerceAtMost(luxMax × 强度上限)` 这个硬截断会把
     * 淡出曲线的末端切掉 —— 补偿较大时，曲线先被削平再下降，形成「倒挂」：
     *
     * ```
     * L=5, 补偿=3:  lux 4.0 -> 5.080    lux 4.5 -> 5.070    lux 4.9 -> 5.019    lux 5 -> 5.000
     * ```
     *
     * 即「光变亮了，屏幕反而变暗」，末端还掉一档。全参数空间（强度 110–220 × 补偿 0–10 ×
     * 上限 {5,10,20,30,50,80} × 强度上限 120–260，共 3456 组 / 225 万个采样点）扫描出
     * **68964 个非单调采样点**。
     *
     * 两处修正彻底消除：
     * 1. **天花板从 `luxMax × 强度上限` 改成 `luxMax`** —— 淡出本身已保证 `lux → luxMax`
     *    时输出 → `luxMax`，把上限设成 `luxMax` 恰好与直通值对齐，不再切掉曲线；
     * 2. **补偿受 `luxMax × (1 - k)` 约束** —— `k ≤ 1` 时这个空间必然 ≥ 0，
     *    补偿再大也只是把输出推到 `luxMax` 而不越界；`k > 1`（强度/上限 > 200%）时
     *    该空间为 0，补偿被完全放弃，避免出现负偏移。
     *
     * 修正后同一套 3456 组参数 / 225 万采样点复扫：**非单调点 0、负值 0**；默认参数
     * （160/3/30/180）下 601 个采样点与旧版**逐点完全一致**（差异 0 处）——
     * 用户无感知，只在极端参数下从「倒挂」变成「正常」。
     *
     * 副作用（有意为之）：越接近上限，提升幅度越小 —— 符合「生效上限」的直觉语义。
     * 「强度上限」滑块语义变为「提升强度的天花板」（`min(强度, 强度上限)`），仍能限制
     * 强度拉满时的激进程度。
     *
     * 负值 / 非有限值是缓冲区无数据的哨兵，绝不放大。
     */
    private fun boost(lux: Float): Float? {
        // 热路径：只读一次 volatile 快照引用（原来是 1+4=5 次 prefs 读/次调用）。
        // 快照不会被写成 null —— install() 与 watcher 都会先 refreshSnapshot()，
        // 这里兜底再读一次以覆盖「挂在 install 之前就被调用」的极端时序。
        val p = params ?: refreshSnapshot()
        if (!p.enabled) return null
        if (!lux.isFinite() || lux < 0f) return null
        val luxMax = p.luxMax
        if (lux >= luxMax) return null
        val u = (lux / luxMax).coerceIn(0f, 1f)
        val w = 1f - u
        // 有效增益：强度与「强度上限」取小，且不低于 1（不低于原值）
        val k = (minOf(p.factor, p.maxGain) - 1f).coerceAtLeast(0f)
        // 补偿不得超过「满增益后距上限的剩余空间」，否则淡出末端会倒挂
        val offEff = p.offset
            .coerceAtMost(luxMax * (1f - k))
            .coerceAtLeast(0f) * w
        val out = (lux * (1f + k * w) + offEff).coerceAtMost(luxMax)
        // 可观测：「装上了」≠「被调用」（上一版正是在这里空转而不自知）。
        // 详细日志开启时每 5 次采样打一条，可直接看到原始 / 放大后的 lux。
        // ⚠️ 先取模再判 isEnabled：LogUtils.isEnabled() 内部走 enableProvider（一次 prefs 读），
        // 原写法每次采样都付这个代价。现在只有「恰好第 5n 次」才判，省掉 4/5。
        if (sampleCount.getAndIncrement() % 5 == 0 && LogUtils.isEnabled()) {
            LogUtils.i(TAG, "hit", "lux ${lux} -> ${out}（生效上限 ${luxMax}）")
        }
        return out
    }

    // ------------------------------------------------------------ 取值（读取侧兜底）

    private fun enabled(): Boolean =
        HookEnv.settingBoolean(AutoBrightnessConfig.KEY_ENABLED, false)

    private fun configFactor(): Float = HookEnv
        .settingInt(AutoBrightnessConfig.KEY_FACTOR, AutoBrightnessConfig.FACTOR_DEFAULT)
        .coerceIn(AutoBrightnessConfig.FACTOR_MIN, AutoBrightnessConfig.FACTOR_MAX) / 100f

    private fun configOffset(): Float = HookEnv
        .settingInt(AutoBrightnessConfig.KEY_OFFSET, AutoBrightnessConfig.OFFSET_DEFAULT)
        .coerceIn(AutoBrightnessConfig.OFFSET_MIN, AutoBrightnessConfig.OFFSET_MAX).toFloat()

    private fun configLuxMax(): Float = HookEnv
        .settingInt(AutoBrightnessConfig.KEY_LUX_MAX, AutoBrightnessConfig.LUX_MAX_DEFAULT)
        .coerceIn(AutoBrightnessConfig.LUX_MAX_MIN, AutoBrightnessConfig.LUX_MAX_MAX).toFloat()

    private fun configMaxGain(): Float = HookEnv
        .settingInt(AutoBrightnessConfig.KEY_MAX_GAIN, AutoBrightnessConfig.MAX_GAIN_DEFAULT)
        .coerceIn(AutoBrightnessConfig.MAX_GAIN_MIN, AutoBrightnessConfig.MAX_GAIN_MAX) / 100f
}
