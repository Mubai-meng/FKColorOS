package com.fr.colorostools.hook.hookers

import com.fr.colorostools.hook.HookEnv
import com.fr.colorostools.ref.Reflect
import com.fr.colorostools.utils.LogUtils
import io.github.libxposed.api.XposedInterface

/**
 * 功能：**自动亮度低照度提升**（宿主 = system_server，A 方案）。
 *
 * ## 背景
 *
 * 系统在低照度环境（<30 lux）下自动亮度过暗。完整链路（本机 services.jar 实证）：
 *
 * 1. 传感器采样 → `AmbientLightRingBuffer`（长窗/短窗双时间窗）；
 * 2. `AutomaticBrightnessController#calculateAmbientLux(JJ)F` —— 时间加权移动平均，
 *    长窗结果 = `mSlowAmbientLux`（稳）、短窗 = `mFastAmbientLux`（快）；
 * 3. `updateAmbientLux` 迟滞防抖：慢+快线同时越过亮/暗阈值且 debounce 到期才 `setAmbientLux`；
 * 4. ABC:710 `mCurrentBrightnessMapper.getBrightness(mAmbientLux, 前台包名, 前台类别)`
 *    —— `PhysicalMappingStrategy`（vendor nits 曲线插值 + 用户短时模型控制点）；
 * 5. `BrightnessClamperController` / `OplusBrightnessGainLutController` 等后处理钳制。
 *
 * 「过暗」常见根因：vendor 曲线低段本身保守 + 用户短时模型的手动调暗学习效应。
 *
 * ## 方案 A：环境光估计端放大（post-hook calculateAmbientLux）
 *
 * 只在结果低于生效上限时改写为 `lux' = lux × 因子 + 补偿`，并钳到 `生效上限 × 增益`：
 * - 改的是「系统相信的环境光」，下游映射、迟滞阈值、变暗回退**整链自洽**
 *   （回退到原亮度走同一曲线，不会振荡）；
 * - 不触碰用户短时模型：`addUserDataPoint` 的 lux 来自 `getAmbientLux()`（读字段，
 *   不经过本方法），手动调亮/调暗的学习链不受干扰；
 * - ≥ 生效上限（默认 30 lux）完全不受影响，功耗增量只发生在低照度场景，
 *   且被 `生效上限 × 增益` 钳住，不会跳到曲线高段。
 *
 * ## 钩子点抗 OTA
 *
 * 方法是 AOSP 原名（**不混淆**），全类唯一 `(JJ)F` 重载，形状极稳；
 * `system` 作用域钩子在 system_server 启动时装载，开关与参数改动即时生效。
 * 调用频率 = 传感器节流率（≥250ms 一次），偏好直接读即可（与其它 Hooker 一致）。
 */
internal object AutoBrightnessHook {

    private const val TAG = "AutoBrightness"

    /** 本机实证：com.android.server.display.AutomaticBrightnessController（AOSP 原名未混淆） */
    private const val CLS_ABC = "com.android.server.display.AutomaticBrightnessController"

    fun install() {
        val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_ABC)
        if (cls == null) {
            LogUtils.w(TAG, "install", "$CLS_ABC 不存在，跳过自动亮度低照度提升")
            return
        }
        val m = Reflect.findMethodByShape(
            cls, "calculateAmbientLux",
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
                    if (!enabled()) return r
                    val lux = r as? Float ?: return r
                    // 负值 = 缓冲区无数据的哨兵返回值（-1.0f），绝不能放大
                    if (!lux.isFinite() || lux < 0f) return r
                    val luxMax = configLuxMax()
                    if (lux >= luxMax) return r
                    return (lux * configFactor() + configOffset())
                        .coerceAtMost(luxMax * configMaxGain())
                }
            })
            LogUtils.i(TAG, "install", "已挂 ${cls.simpleName}#calculateAmbientLux(JJ)F（低照度提升）")
        }.onFailure {
            LogUtils.w(TAG, "install", "挂 calculateAmbientLux 失败：${it.javaClass.simpleName}: ${it.message}")
        }
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
