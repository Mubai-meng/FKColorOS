package com.fr.colorostools.hook.hookers

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
 *   → `OplusFeatureDarkEnvReduceBrightness#postProcessing`（暗环境再降一档亮度）。
 *
 * 因此旧钩子属于「装成功但空转」：install 日志齐全，实际一次都不进。
 *
 * ## 现在的挂点
 *
 * 1. `OplusLuxResult#getFinalLux()F` post-hook —— 放大系统相信的环境光：
 *    `lux' = lux × 增益(lux) + 补偿(lux)`，增益/补偿在 `生效上限` 处平滑淡出为 0，
 *    保证跨过上限时输出连续、不跳变（详见 [boost]）；≥ 生效上限完全直通。
 *    改的是映射输入，下游曲线/迟滞整链自洽，不会来回抖。
 * 2. `OplusFeatureDarkEnvReduceBrightness#shouldSkipProcessing(Builder)Z` → true ——
 *    跳过 ColorOS 自带的「暗环境降亮度」后处理。它是低照度偏暗的另一个直接原因，
 *    不关掉的话，放大后的亮度会被它再压回去。
 *
 * 两者共用同一个总开关；AOSP 方法作为兜底保留（其它 ROM / 链路回归时仍可用）。
 *
 * ## 参数换算
 * - 百分比键（提升强度 / 结果上限）：存 160 = 1.6 倍
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

    /** AOSP 兜底（其它 ROM 或未来链路回归） */
    private const val CLS_ABC = "com.android.server.display.AutomaticBrightnessController"

    fun install() {
        val oplusLux = installOplusLux()
        // 暗环境降亮度：即使 lux 放大，它也会把结果压回去，必须单独跳过。
        // 失败不阻塞主链路（不同 ROM 可能没有这个后处理特性）。
        installSkipDarkEnvReduce()
        if (!oplusLux) installAospFallback()
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

    // ------------------------------------------------------------ 挂点 2：跳过暗环境降亮度

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
            LogUtils.i(TAG, "install", "已挂 ${cls.simpleName}#shouldSkipProcessing（暗环境不再降亮度）")
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
     * 低照度放大，**在上限处平滑收敛到直通**。
     *
     * ## 为什么不能直接 `min(lux × 因子 + 补偿, 上限 × 增益)`（2026-11-19 修正）
     * 直通分支是 `lux >= luxMax ⇒ 原样返回`。若 `lux < luxMax` 时把结果抬到
     * `luxMax × gain`，那么环境光从 `luxMax - ε` 缓慢升到 `luxMax` 时，输出会从
     * （例：1.6×27+3 = 46.2）**骤降到 30** —— 表现为屏幕「突然变暗一档」。
     * 因此增益与补偿都按 `u = lux / luxMax` 线性淡出：
     * `u=0` 满额、`u→1` 归零，于是 `lux → luxMax` 时输出 → `luxMax`，
     * 与直通值连续衔接，全程单调、无跳变。
     *
     * 副作用（有意为之）：越接近上限，提升幅度越小 —— 这符合「生效上限」的直觉语义。
     *
     * 负值 / 非有限值是缓冲区无数据的哨兵，绝不放大。
     */
    private fun boost(lux: Float): Float? {
        if (!enabled()) return null
        if (!lux.isFinite() || lux < 0f) return null
        val luxMax = configLuxMax()
        if (lux >= luxMax) return null
        val u = (lux / luxMax).coerceIn(0f, 1f)
        val gainEff = 1f + (configFactor() - 1f) * (1f - u)
        val offEff = configOffset() * (1f - u)
        val out = (lux * gainEff + offEff).coerceAtMost(luxMax * configMaxGain())
        // 可观测：「装上了」≠「被调用」（上一版正是在这里空转而不自知）。
        // 详细日志开启时每 5 次采样打一条，可直接看到原始 / 放大后的 lux。
        if (LogUtils.isEnabled() && sampleCount.getAndIncrement() % 5 == 0) {
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
