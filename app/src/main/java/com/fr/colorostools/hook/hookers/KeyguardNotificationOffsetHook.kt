package com.fr.colorostools.hook.hookers

import android.os.SystemClock
import com.fr.colorostools.hook.HookEnv
import com.fr.colorostools.ref.Reflect
import com.fr.colorostools.utils.LogUtils
import io.github.libxposed.api.XposedInterface

/**
 * 功能：**锁屏通知区域下移**（宿主 `com.android.systemui`）。
 *
 * 移植来源：coloros-mod（MIT）`com.rikumi.colorosmod.hooks.KeyguardHooks#hookKeyguardNotificationOffset`。
 *
 * ## 为什么锚这三个方法
 *
 * ColorOS 17 锁屏通知的纵向位置由**三条独立通道**分别给出，任一条不改就会在对应状态下
 * 露出原始位置（上游同样三个都挂）：
 *
 * | 状态 | 方法 | 说明 |
 * |---|---|---|
 * | 静止 / 拖拽 / 收起 | `NotificationPanelViewController#getKeyguardNotificationStaticPadding()I` | 锁屏通知静态顶部内边距 |
 * | 锁屏通知中心（展开态） | `OplusLockscreenShadeTransitionControllerExImpl#getNtfTopPaddingInLockscreen()I` | ColorOS 自己的 stack 实现 |
 * | 锁屏通知中心（收起动画态） | `…#getNtfTopPaddingInLockscreenNtfCenter()I` | 同上 |
 *
 * 三处都是「返回 int、无参」的方法 ⇒ 后置改返回值即可，不碰任何布局计算。
 *
 * ⚠️ `getKeyguardNotificationStaticPadding` **只在 `isKeyguardShowing` 时叠加**：
 * 非锁屏时它的原始返回值本就是 0，叠加会污染下拉通知栏（上游注释明确踩过）。
 * 另两个方法只存在于锁屏过渡控制器，天然只在锁屏链路里被调用，无需再判。
 *
 * ## 适配（本机 SystemUI 17.99.02 逆向实证）
 *
 * - `com.android.systemui.shade.NotificationPanelViewController`：两个方法都在；
 * - 新实现 `com.oplus.systemui.notification.lockscreen.stack.OplusLockscreenShadeTransitionControllerExImpl`
 *   两个 padding 方法都在（另有 `setNtfTopPaddingInLockscreen`，本功能不改）；
 * - 旧实现 `com.android.systemui.statusbar.OplusLockscreenShadeTransitionControllerEx` 亦在，
 *   按「先新后旧」顺序取第一个存在的类（与上游一致，兼容后续 OTA 回退）。
 *
 * ## 单位与范围
 *
 * 设置页存的是 **dp**，hook 侧按 `density` 折算成 px 叠加（`Math.round(dp * density)`）。
 * 范围 0..40 dp、默认 20 dp（对齐上游 `KEYGUARD_NOTIFICATION_OFFSET_DP_DEFAULT/_MAX`）。
 * 改为 0 dp 等价于不偏移；总开关关闭时直接短路返回 0。
 */
internal object KeyguardNotificationOffsetHook {

    private const val TAG = "KeyguardNtfOffset"

    /** 锁屏通知静态内边距（下拉/静止/收起态的公共出口） */
    private const val CLS_NOTIFICATION_PANEL_VC =
        "com.android.systemui.shade.NotificationPanelViewController"

    /** ColorOS 17 锁屏 stack 过渡控制器（当前版本实现） */
    private const val CLS_SHADE_TRANSITION_EX_IMPL =
        "com.oplus.systemui.notification.lockscreen.stack.OplusLockscreenShadeTransitionControllerExImpl"

    /** 旧版锁屏过渡控制器（OTA 回退时的兜底） */
    private const val CLS_LEGACY_SHADE_TRANSITION_EX =
        "com.android.systemui.statusbar.OplusLockscreenShadeTransitionControllerEx"

    /** 锁屏通知中心的两条 padding 通道（展开态 / 收起动画态） */
    /** 偏移量缓存 TTL（布局期高频调用，见 [offsetPx]） */
    private const val OFFSET_CACHE_TTL_MS = 300L

    @Volatile
    private var offsetCachePx = 0

    @Volatile
    private var offsetCacheAt = 0L

    private val NTF_CENTER_METHODS = arrayOf(
        "getNtfTopPaddingInLockscreen",
        "getNtfTopPaddingInLockscreenNtfCenter",
    )

    fun install() {
        hookStaticPadding()
        hookLockscreenNtfCenter()
        LogUtils.i(TAG, "install", "锁屏通知偏移已装（dp=${configDp()}px），改开关/数值即时生效")
    }

    /** 通道 1：静止 / 拖拽 / 收起态 */
    private fun hookStaticPadding() {
        val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_NOTIFICATION_PANEL_VC)
        if (cls == null) {
            LogUtils.w(TAG, "install", "$CLS_NOTIFICATION_PANEL_VC 不存在，跳过静态内边距通道")
            return
        }
        val m = Reflect.findMethod(cls, "getKeyguardNotificationStaticPadding")
        if (m == null) {
            LogUtils.w(TAG, "install", "getKeyguardNotificationStaticPadding 不存在，跳过")
            return
        }
        runCatching {
            HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    val extra = offsetPx()
                    if (extra == 0) return result
                    val base = result as? Int ?: return result
                    // 非锁屏时原始返回值本就是 0，叠加会污染下拉通知栏
                    if (!isKeyguardShowing(chain.thisObject)) return result
                    return base + extra
                }
            })
            LogUtils.i(TAG, "install", "已挂 $CLS_NOTIFICATION_PANEL_VC#getKeyguardNotificationStaticPadding")
        }.onFailure {
            LogUtils.w(TAG, "install", "挂静态内边距失败：${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /** 通道 2 / 3：锁屏通知中心（展开态 + 收起动画态） */
    private fun hookLockscreenNtfCenter() {
        val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_SHADE_TRANSITION_EX_IMPL)
            ?: Reflect.classOrNull(HookEnv.classLoader, CLS_LEGACY_SHADE_TRANSITION_EX)
        if (cls == null) {
            LogUtils.w(
                TAG, "install",
                "锁屏过渡控制器都不存在（$CLS_SHADE_TRANSITION_EX_IMPL / $CLS_LEGACY_SHADE_TRANSITION_EX），跳过通知中心通道",
            )
            return
        }
        var hooked = 0
        NTF_CENTER_METHODS.forEach { name ->
            val m = Reflect.findMethod(cls, name) ?: run {
                LogUtils.w(TAG, "install", "${cls.simpleName}#$name 不存在，跳过")
                return@forEach
            }
            runCatching {
                HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val result = chain.proceed()
                        val extra = offsetPx()
                        if (extra == 0) return result
                        val base = result as? Int ?: return result
                        return base + extra
                    }
                })
                hooked++
                LogUtils.i(TAG, "install", "已挂 ${cls.simpleName}#$name")
            }.onFailure {
                LogUtils.w(TAG, "install", "挂 ${cls.simpleName}#$name 失败：${it.message}")
            }
        }
        LogUtils.i(TAG, "install", "通知中心通道 $hooked/${NTF_CENTER_METHODS.size}")
    }

    // ------------------------------------------------------------------ 取值

    /**
     * 偏移像素。
     *
     * 这三个方法都是**布局期高频出口**（拖拽通知栏时每帧都会问一次），所以对
     * 「开关 + dp + density」做 [OFFSET_CACHE_TTL_MS] 缓存；300ms 的窗口让滑条/开关
     * 仍然「拖完立刻可见」，同时把 remote prefs 读取压到可忽略。
     */
    private fun offsetPx(): Int {
        val now = SystemClock.uptimeMillis()
        if (now - offsetCacheAt < OFFSET_CACHE_TTL_MS) return offsetCachePx
        val px = computeOffsetPx()
        offsetCachePx = px
        offsetCacheAt = now
        return px
    }

    private fun computeOffsetPx(): Int {
        if (!HookEnv.settingBoolean(KeyguardNotificationOffsetConfig.KEY_ENABLED, false)) return 0
        val dp = configDp()
        if (dp <= 0) return 0
        return Math.round(dp * densityPx())
    }

    /** 屏幕密度（进程内只需取一次；SystemUI 不会运行时改密度） */
    @Volatile
    private var densityCache = 0f

    private fun densityPx(): Float {
        val cached = densityCache
        if (cached > 0f) return cached
        val d = runCatching {
            HookEnv.context()?.resources?.displayMetrics?.density
        }.getOrNull() ?: 0f
        // 取不到（Application 还没建）就先按 3.0 估算，下轮会拿到真值覆盖
        if (d <= 0f) return 3.0f
        densityCache = d
        return d
    }

    /** 当前设置的 dp 值（夹在 0..[KeyguardNotificationOffsetConfig.DP_MAX]） */
    private fun configDp(): Int = HookEnv
        .settingInt(KeyguardNotificationOffsetConfig.KEY_DP, KeyguardNotificationOffsetConfig.DP_DEFAULT)
        .coerceIn(0, KeyguardNotificationOffsetConfig.DP_MAX)

    /**
     * `NotificationPanelViewController#isKeyguardShowing()`：**必须 callDeep**。
     * 该方法声明在面板基类上（不在 VC 自己的 declaredMethods 里），
     * 用 `callByName` 会恒返回 null 且不报错 —— 现象就是「非锁屏也偏移」。
     */
    private fun isKeyguardShowing(panel: Any?): Boolean =
        Reflect.callDeep(panel, "isKeyguardShowing") as? Boolean ?: false
}

/**
 * 锁屏通知区域下移的开关与数值（App 设置页写入、hook 侧读取，两边同一份 remote prefs）。
 */
internal object KeyguardNotificationOffsetConfig {
    const val KEY_ENABLED = "keyguard_notification_offset_enabled"
    const val KEY_DP = "keyguard_notification_offset_dp"

    /** 默认下移 20dp（对齐上游） */
    const val DP_DEFAULT = 20

    /** 上限 40dp（对齐上游；再大就会把通知推出发烧区） */
    const val DP_MAX = 40
}