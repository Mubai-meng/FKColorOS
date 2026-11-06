package com.fr.colorostools.hook.hookers

import android.content.res.Resources
import android.graphics.Point
import android.graphics.Rect
import com.fr.colorostools.hook.HookEnv
import com.fr.colorostools.ref.Reflect
import com.fr.colorostools.utils.LogUtils
import io.github.libxposed.api.XposedInterface

/**
 * 功能：**横屏应用小窗保持比例** + **优化小窗贴边位置及最大尺寸**（宿主 = system_server）。
 *
 * 移植来源：coloros-mod（MIT）
 * `hooks/FloatWindowLandscapeRatioHooks` + `SystemServerHooks#hookFloatWindowSizeLimits`。
 *
 * ## 一、横屏保持比例
 *
 * 原生给横屏应用算小窗尺寸时用的是「全屏比例」的倒数之外的默认档，导致横屏视频/游戏被
 * 拉成竖屏比例。两个出口一起改才完整：
 *
 * | 方法 | 作用 |
 * |---|---|
 * | `FlexibleTaskController#fillFlexibleTaskInfo(..., boolean isLandscape, ...)` | 真正算尺寸的地方（后置写 `setRatio` / `setScale`） |
 * | `…#getFlexibleTaskAvailableRatioByActivity(ActivityRecord, String, boolean)` | 可选手动比例列表（补一个横屏比例档） |
 *
 * `isLandscape` 参数 = 上游的 `args[4]`；只有它为 true 才介入。原生返回的候选比例列表若
 * **不含任何 < 1 的比例**（说明该应用不允许横屏），也不介入 —— 否则会给不能横屏的应用
 * 硬塞一个横屏档。
 *
 * ## 二、贴边位置及最大尺寸
 *
 * ColorOS 的小窗最大宽度被压到「屏宽 − 2×安全边距」，而安全边距默认很大 ⇒ 小窗永远贴不到边。
 * 上游的解法是把安全边距换成 8dp，并把三个尺寸计算器里的最大可视宽度按「屏宽 − 16dp」重算：
 *
 * | 方法 | 改什么 |
 * |---|---|
 * | `OplusZoomDisplay#getLeftRightLimitInMini()` | 左右最小留白 → 8dp |
 * | `OplusZoom{Small,Middle,Large}ScreenParameter#computeAllRatioData()` | 各比例档的 `mMaxVisualWidth` 放大到屏宽 − 16dp |
 * | `OplusZoomDisplay#getFreeRatioSupportMaxWidthProp()` | 任意比例模式的宽度上限系数 |
 * | `OplusFlexibleTaskLayoutPolicy#{regulateSizeIfOverScreen, getTaskRealSize}` | 计算期间临时把 `mSecurityMarginRight` 换掉 |
 *
 * 最后两个是**前后成对**的：`mSecurityMarginRight` 是实例字段，改了必须还原，
 * 否则会永久污染布局策略（小窗再也不会被裁到屏内）。用 ThreadLocal 保存旧值，
 * 避免嵌套调用/多线程互相覆盖。
 *
 * ## 本机实证（ColorOS 17 / oplus-services 反编译）
 *
 * - `FlexibleTaskController#fillFlexibleTaskInfo(Builder,Rect,Intent,ActivityInfo,boolean,DisplayContent)` :5070（private，**6 参**）
 * - `FlexibleTaskController#getFlexibleTaskAvailableRatioByActivity(ActivityRecord,String,boolean)` :10892（private）
 * - `FlexibleTaskController#getFlexibleTaskFullScreenRatio(int,int)` :10833（private）
 * - `OplusZoomDisplay#{getLeftRightLimitInMini():679, getFreeRatioSupportMaxWidthProp():599, getDefaultFlexibleScale(float):490, getScreenWidth():262, getScreenHeight():253, getInstance():78}`
 * - `OplusZoomSmallScreenParameter#computeAllRatioData()` :177（private，三个档位各有一份）
 * - `OplusFlexibleTaskLayoutPolicy#{regulateSizeIfOverScreen(...):817, getTaskRealSize(boolean,float):1509, mSecurityMarginRight:35}`
 * - `OplusFlexibleRatioData{ mIsPort:6, mMaxVisualWidth:7 }`
 */
internal object FloatWindowSizeHook {

    private const val TAG = "FloatWindowSize"

    private const val CLS_FTC = "com.android.server.wm.FlexibleTaskController"
    private const val CLS_ZOOM_DISPLAY = "com.android.server.wm.OplusZoomDisplay"
    private const val CLS_LAYOUT_POLICY = "com.android.server.wm.OplusFlexibleTaskLayoutPolicy"

    private const val CLS_TASK = "com.android.server.wm.Task"
    private const val CLS_FLEXIBLE_TASK_INFO = "com.android.server.wm.FlexibleTaskInfo"
    private const val CLS_FTI_BUILDER = "com.android.server.wm.FlexibleTaskInfo\$Builder"

    /** 三个尺寸档位的参数类（各自覆写了 `computeAllRatioData`） */
    private val SCREEN_PARAMETER_CLASSES = arrayOf(
        "com.android.server.wm.OplusZoomSmallScreenParameter",
        "com.android.server.wm.OplusZoomMiddleScreenParameter",
        "com.android.server.wm.OplusZoomLargeScreenParameter",
    )

    /** `regulateSizeIfOverScreen` / `getTaskRealSize` 期间被替换的字段原名与其备份 */
    private val savedMargin = ThreadLocal<Int?>()

    fun install() {
        installLandscapeRatio()
        installSizeLimits()
    }

    // ================================================================ 横屏保持比例

    private fun installLandscapeRatio() {
        val controller = Reflect.classOrNull(HookEnv.classLoader, CLS_FTC)
        val zoom = Reflect.classOrNull(HookEnv.classLoader, CLS_ZOOM_DISPLAY)
        if (controller == null || zoom == null) {
            LogUtils.w(TAG, "install", "$CLS_FTC 或 $CLS_ZOOM_DISPLAY 不存在，跳过横屏保持比例")
            return
        }
        hookFillFlexibleTaskInfo(controller, zoom)
        hookAvailableRatio(controller, zoom)
    }

    private fun hookFillFlexibleTaskInfo(controller: Class<*>, zoom: Class<*>) {
        val builder = Reflect.classOrNull(HookEnv.classLoader, CLS_FTI_BUILDER)
        val displayContent = Reflect.classOrNull(HookEnv.classLoader, "com.android.server.wm.DisplayContent")
        val activityInfo = Reflect.classOrNull(HookEnv.classLoader, "android.content.pm.ActivityInfo")

        // 先试 6 参（ColorOS 17 本机形态），再退 5 参（旧固件）
        val m = Reflect.findMethodByShape(
            controller,
            "fillFlexibleTaskInfo",
            listOf(builder, Rect::class.java, android.content.Intent::class.java, activityInfo, Boolean::class.javaPrimitiveType, displayContent),
            null,
        ) ?: Reflect.findMethodByShape(
            controller,
            "fillFlexibleTaskInfo",
            listOf(builder, Rect::class.java, android.content.Intent::class.java, activityInfo, Boolean::class.javaPrimitiveType),
            null,
        )
        if (m == null) {
            LogUtils.w(TAG, "install", "fillFlexibleTaskInfo 5/6 参签名都不存在，跳过横屏尺寸初始化")
            return
        }
        runCatching {
            HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val info = chain.proceed()
                    if (info == null) return info
                    if (!landscapeEnabled()) return info
                    // args[4] = isLandscapeOrientation：只对横屏应用介入
                    if (chain.args.getOrNull(4) != true) return info
                    runCatching {
                        val ratio = landscapeRatio(chain.thisObject, zoom) ?: return@runCatching
                        val display = Reflect.callStatic(zoom, "getInstance") ?: return@runCatching
                        val scale = (Reflect.callByName(display, "getDefaultFlexibleScale", ratio) as? Number)?.toFloat()
                            ?: return@runCatching
                        if (!scale.isFinite() || scale <= 0f) return@runCatching
                        // 调用方用 setRatio/setScale 的结果算 launchBounds ⇒ 只改这两个字段，不碰矩形
                        Reflect.callByName(info, "setRatio", ratio)
                        Reflect.callByName(info, "setScale", scale)
                    }
                    return info
                }
            })
            LogUtils.i(TAG, "install", "已挂 FlexibleTaskController#fillFlexibleTaskInfo(${m.parameterCount} 参)")
        }.onFailure {
            LogUtils.w(TAG, "install", "挂 fillFlexibleTaskInfo 失败：${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private fun hookAvailableRatio(controller: Class<*>, zoom: Class<*>) {
        val m = Reflect.findMethod(
            controller,
            "getFlexibleTaskAvailableRatioByActivity",
            3,
            mapOf(1 to "String"),
        ) ?: run {
            LogUtils.w(TAG, "install", "getFlexibleTaskAvailableRatioByActivity(ActivityRecord,String,boolean) 不存在，跳过比例档补充")
            return
        }
        runCatching {
            HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val r = chain.proceed()
                    if (!landscapeEnabled()) return r
                    val native = r as? List<*> ?: return r
                    return runCatching {
                        // 原生已按应用方向做过过滤：只有列表里存在 <1 的比例才说明允许横屏
                        val allowsLandscape = native.any { (it as? Number)?.toFloat()?.let { f -> f > 0f && f < 1f } == true }
                        if (!allowsLandscape) return@runCatching native
                        val ratio = landscapeRatio(chain.thisObject, zoom) ?: return@runCatching native
                        if (native.any { (it as? Number)?.toFloat()?.let { f -> Math.abs(f - ratio) < 0.001f } == true }) {
                            return@runCatching native
                        }
                        val out = ArrayList<Any?>(native.size + 1)
                        out.addAll(native)
                        out.add(ratio)
                        out
                    }.getOrElse { native }
                }
            })
            LogUtils.i(TAG, "install", "已挂 FlexibleTaskController#getFlexibleTaskAvailableRatioByActivity")
        }.onFailure {
            LogUtils.w(TAG, "install", "挂 getFlexibleTaskAvailableRatioByActivity 失败：${it.message}")
        }
    }

    /**
     * 横屏比例 = 全屏比例的倒数。取不到（≤0）时返回 null，调用方原样放行。
     */
    private fun landscapeRatio(controller: Any?, zoom: Class<*>): Float? {
        val display = Reflect.callStatic(zoom, "getInstance") ?: return null
        val width = (Reflect.callByName(display, "getScreenWidth") as? Number)?.toInt() ?: return null
        val height = (Reflect.callByName(display, "getScreenHeight") as? Number)?.toInt() ?: return null
        if (width <= 0 || height <= 0) return null
        // getFlexibleTaskFullScreenRatio 声明在 FlexibleTaskController 自己（private）
        val portrait = (Reflect.callByName(controller, "getFlexibleTaskFullScreenRatio", width, height) as? Number)
            ?.toFloat() ?: return null
        if (portrait <= 0f) return null
        val ratio = 1f / portrait
        return if (ratio > 0f && ratio < 1f) ratio else null
    }

    // ================================================================ 贴边位置 / 最大尺寸

    private fun installSizeLimits() {
        hookLeftRightLimit()
        hookComputeRatioData()
        hookFreeRatioMaxWidth()
        hookSecurityMargin()
    }

    private fun hookLeftRightLimit() {
        val zoom = Reflect.classOrNull(HookEnv.classLoader, CLS_ZOOM_DISPLAY) ?: return
        val m = Reflect.findMethodByShape(zoom, "getLeftRightLimitInMini", emptyList(), Int::class.javaPrimitiveType)
        if (m == null) {
            LogUtils.w(TAG, "install", "getLeftRightLimitInMini()I 不存在，跳过")
            return
        }
        runCatching {
            HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val r = chain.proceed()
                    if (!sizeEnabled()) return r
                    return sideMarginPx()
                }
            })
            LogUtils.i(TAG, "install", "已挂 OplusZoomDisplay#getLeftRightLimitInMini")
        }.onFailure { LogUtils.w(TAG, "install", "挂 getLeftRightLimitInMini 失败：${it.message}") }
    }

    private fun hookComputeRatioData() {
        var hooked = 0
        SCREEN_PARAMETER_CLASSES.forEach { name ->
            val cls = Reflect.classOrNull(HookEnv.classLoader, name) ?: return@forEach
            val m = Reflect.findMethod(cls, "computeAllRatioData") ?: return@forEach
            runCatching {
                HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val r = chain.proceed()
                        if (sizeEnabled()) runCatching { widenMaxVisualWidth(chain.thisObject) }
                        return r
                    }
                })
                hooked++
            }.onFailure {
                LogUtils.w(TAG, "install", "挂 ${cls.simpleName}#computeAllRatioData 失败：${it.message}")
            }
        }
        LogUtils.i(TAG, "install", "尺寸档位重算已挂 $hooked/${SCREEN_PARAMETER_CLASSES.size}")
    }

    private fun hookFreeRatioMaxWidth() {
        val zoom = Reflect.classOrNull(HookEnv.classLoader, CLS_ZOOM_DISPLAY) ?: return
        val m = Reflect.findMethodByShape(zoom, "getFreeRatioSupportMaxWidthProp", emptyList(), Float::class.javaPrimitiveType)
        if (m == null) {
            LogUtils.w(TAG, "install", "getFreeRatioSupportMaxWidthProp()F 不存在，跳过")
            return
        }
        runCatching {
            HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val r = chain.proceed()
                    if (!sizeEnabled()) return r
                    val prop = freeRatioMaxWidthProp()
                    return if (prop > 0f) prop else r
                }
            })
            LogUtils.i(TAG, "install", "已挂 OplusZoomDisplay#getFreeRatioSupportMaxWidthProp")
        }.onFailure { LogUtils.w(TAG, "install", "挂 getFreeRatioSupportMaxWidthProp 失败：${it.message}") }
    }

    private fun hookSecurityMargin() {
        val policy = Reflect.classOrNull(HookEnv.classLoader, CLS_LAYOUT_POLICY) ?: return
        val task = Reflect.classOrNull(HookEnv.classLoader, CLS_TASK)
        val info = Reflect.classOrNull(HookEnv.classLoader, CLS_FLEXIBLE_TASK_INFO)

        val regulate = Reflect.findMethodByShape(
            policy,
            "regulateSizeIfOverScreen",
            listOf(task, info, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Point::class.java, Point::class.java),
            null,
        )
        val realSize = Reflect.findMethodByShape(
            policy,
            "getTaskRealSize",
            listOf(Boolean::class.javaPrimitiveType, Float::class.javaPrimitiveType),
            Point::class.java,
        )
        var hooked = 0
        listOfNotNull(regulate, realSize).forEach { m ->
            runCatching {
                HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        if (!sizeEnabled()) {
                            savedMargin.remove()
                            return chain.proceed()
                        }
                        savedMargin.set(overrideSecurityMargin(chain.thisObject))
                        try {
                            return chain.proceed()
                        } finally {
                            restoreSecurityMargin(chain.thisObject)
                        }
                    }
                })
                hooked++
            }.onFailure {
                LogUtils.w(TAG, "install", "挂 ${m.name} 失败：${it.javaClass.simpleName}: ${it.message}")
            }
        }
        if (hooked == 0) {
            LogUtils.w(TAG, "install", "regulateSizeIfOverScreen / getTaskRealSize 都不存在，安全边距未接管")
        } else {
            LogUtils.i(TAG, "install", "安全边距已接管（$hooked 处）")
        }
    }

    /**
     * 重算各比例档的最大可视宽度：竖屏档 = 屏宽 − 16dp、横屏档 = 屏高 − 16dp。
     *
     * 参数类的 `mDisplayContent` 是 **`OplusFlexibleDisplayContentWrapper`**（不是 DisplayContent），
     * 它的 `getDisplayInfo()` / `getDisplayMetrics()` 都在（本机实证），
     * 取不到就退回 `Resources.getSystem()`。
     */
    private fun widenMaxVisualWidth(parameterStore: Any?) {
        if (parameterStore == null) return
        val list = Reflect.getField(parameterStore, "mRatioDataList") as? List<*> ?: return
        if (list.isEmpty()) return

        var density = 0f
        var shortSide = 0
        var longSide = 0
        val display = Reflect.getField(parameterStore, "mDisplayContent")
        if (display != null) {
            val info = Reflect.callByName(display, "getDisplayInfo")
            val metrics = Reflect.callByName(display, "getDisplayMetrics")
            if (info != null) {
                val w = (Reflect.getField(info, "logicalWidth") as? Int) ?: 0
                val h = (Reflect.getField(info, "logicalHeight") as? Int) ?: 0
                shortSide = minOf(w, h)
                longSide = maxOf(w, h)
            }
            density = (Reflect.getField(metrics, "density") as? Float) ?: 0f
        }
        if (density <= 0f || shortSide <= 0 || longSide <= 0) {
            val dm = Resources.getSystem().displayMetrics
            density = dm.density
            shortSide = minOf(dm.widthPixels, dm.heightPixels)
            longSide = maxOf(dm.widthPixels, dm.heightPixels)
        }
        if (density <= 0f || shortSide <= 0 || longSide <= 0) return

        val marginDp = Math.round(2 * FloatWindowConfig.SIDE_MARGIN_DP)
        val portWidthDp = Math.round(shortSide / density) - marginDp
        val landWidthDp = Math.round(longSide / density) - marginDp
        if (portWidthDp <= 0 || landWidthDp <= 0) return

        for (data in list) {
            if (data == null) continue
            val isPort = Reflect.getField(data, "mIsPort") as? Boolean ?: continue
            Reflect.setField(data, "mMaxVisualWidth", if (isPort) portWidthDp else landWidthDp)
        }
    }

    private fun overrideSecurityMargin(policy: Any?): Int? {
        if (policy == null) return null
        val old = Reflect.getField(policy, "mSecurityMarginRight") as? Int ?: return null
        return if (Reflect.setField(policy, "mSecurityMarginRight", sideMarginPx())) old else null
    }

    private fun restoreSecurityMargin(policy: Any?) {
        val backup = savedMargin.get() ?: return
        savedMargin.remove()
        if (policy == null) return
        Reflect.setField(policy, "mSecurityMarginRight", backup)
    }

    // ------------------------------------------------------------ 取值

    private fun landscapeEnabled(): Boolean =
        HookEnv.settingBoolean(FloatWindowConfig.KEY_LANDSCAPE_KEEP_RATIO, false)

    private fun sizeEnabled(): Boolean =
        HookEnv.settingBoolean(FloatWindowConfig.KEY_EDGE_SIZE_OPTIMIZE, false)

    private fun sideMarginPx(): Int {
        val density = Resources.getSystem().displayMetrics.density
        if (density <= 0f) return 0
        return Math.round(FloatWindowConfig.SIDE_MARGIN_DP * density)
    }

    /** 自由比例模式的最大宽度系数：把「屏宽 − 2×边距」换算成相对屏宽的比值 */
    private fun freeRatioMaxWidthProp(): Float {
        val dm = Resources.getSystem().displayMetrics
        val widthPx = minOf(dm.widthPixels, dm.heightPixels)
        if (widthPx <= 0) return 0f
        val margin = sideMarginPx() * 2
        if (margin >= widthPx) return 0f
        return (widthPx - margin) * 1.0f / widthPx
    }
}
