package com.fr.colorostools.hook.hookers

import android.content.Context
import android.graphics.Rect
import com.fr.colorostools.hook.HookEnv
import com.fr.colorostools.ref.Reflect
import com.fr.colorostools.utils.LogUtils
import io.github.libxposed.api.XposedInterface

/**
 * 功能：**修改小窗数量上限**（宿主 = system_server）。
 *
 * 移植来源：coloros-mod（MIT）`hooks/SystemServerHooks#hookFloatWindowCount` +
 * `hooks/FloatWindowCascadeHooks`。
 *
 * ## 三个 hook 点缺一不可
 *
 * | 方法 | 作用 | 时机 |
 * |---|---|---|
 * | `FlexibleWindowManagerService#getMaxWinNum(int scenario)` | 小窗数量上限的**唯一出口** | 前置改返回值 |
 * | `FlexibleWindowManagerService#getTasksNumber()` | 查询「窗口后方的全屏任务/桌面」时按此数取任务 | 后置抬高 |
 * | `OplusFlexibleTaskLayoutPolicy#avoidOverlappingAreas(...)` | 多小窗时的纵向避让布局 | 后置微调 top |
 *
 * 前两个是硬需求：只改 `getMaxWinNum` 时，虽然能开出第 4 个小窗，但 `getTasksNumber`
 * 仍按旧值 4 取任务 ⇒ 新开的小窗后面那个全屏任务查不到 ⇒ 小窗背后是黑的。
 *
 * `avoidOverlappingAreas` 是上游的「旧版多小窗纵向错位」补丁：原生只做横向避让，
 * 多个小窗叠在同一列时会完全重叠；这里按 32dp 间距往下错开。
 *
 * ## 本机实证（ColorOS 17 / oplus-services 反编译）
 *
 * ```java
 * public int getMaxWinNum(int scenario) { ... }            // FlexibleWindowManagerService:4647
 * public int getTasksNumber() { int maxNumber = getMaxWinNum(1); ... }  // :4671
 * private void avoidOverlappingAreas(Rect, ArrayList<Rect>, int, float, int)  // Policy:981
 * ```
 * `getMaxWinNum` 的 `scenario` 参数：`1` = 小窗场景（上游只改这一档，其它档位保持原生）。
 * 策略类字段 `mWindowBounds` / `mStatusBarFrame` / `mNaviBarFrame` / `mContext` 全部在
 * `OplusFlexibleTaskLayoutPolicy` 自己声明（:23/:37-39）。
 */
internal object FloatWindowCountHook {

    private const val TAG = "FloatWindowCount"

    private const val CLS_FWMS = "com.android.server.wm.FlexibleWindowManagerService"
    private const val CLS_LAYOUT_POLICY = "com.android.server.wm.OplusFlexibleTaskLayoutPolicy"

    /** 多小窗纵向错开的间距（dp，对齐上游） */
    private const val CASCADE_GAP_DP = 32

    /** `getMaxWinNum(scenario)` 里小窗场景的取值 */
    private const val SCENARIO_FLEXIBLE = 1

    fun install() {
        hookMaxWinNum()
        hookTasksNumber()
        hookCascadeLayout()
        LogUtils.i(TAG, "install", "小窗数量上限已装（当前 ${configCount()} 个）")
    }

    // ------------------------------------------------------------ 数量上限

    private fun hookMaxWinNum() {
        val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_FWMS)
        if (cls == null) {
            LogUtils.w(TAG, "install", "$CLS_FWMS 不存在，跳过小窗数量上限")
            return
        }
        val m = Reflect.findMethodByShape(cls, "getMaxWinNum", listOf(Int::class.javaPrimitiveType), Int::class.javaPrimitiveType)
        if (m == null) {
            LogUtils.w(TAG, "install", "getMaxWinNum(int)I 不存在，跳过")
            return
        }
        runCatching {
            HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    if (!enabled()) return chain.proceed()
                    // 只改小窗场景，其它 scenario（分屏/全景）保持原生判定
                    val scenario = chain.args.getOrNull(0) as? Int ?: return chain.proceed()
                    if (scenario != SCENARIO_FLEXIBLE) return chain.proceed()
                    return configCount()
                }
            })
            LogUtils.i(TAG, "install", "已挂 ${cls.simpleName}#getMaxWinNum(int)")
        }.onFailure {
            LogUtils.w(TAG, "install", "挂 getMaxWinNum 失败：${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private fun hookTasksNumber() {
        val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_FWMS) ?: return
        val m = Reflect.findMethodByShape(cls, "getTasksNumber", emptyList(), Int::class.javaPrimitiveType)
        if (m == null) {
            LogUtils.w(TAG, "install", "getTasksNumber()I 不存在，跳过（小窗背后可能变黑）")
            return
        }
        runCatching {
            HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val r = chain.proceed()
                    if (!enabled()) return r
                    val base = r as? Int ?: return r
                    // 原生是「上限 + 3」（4 个窗口时返回 7 那种语义），这里保持同样的余量关系
                    return maxOf(base, configCount() + 3)
                }
            })
            LogUtils.i(TAG, "install", "已挂 ${cls.simpleName}#getTasksNumber()")
        }.onFailure {
            LogUtils.w(TAG, "install", "挂 getTasksNumber 失败：${it.javaClass.simpleName}: ${it.message}")
        }
    }

    // ------------------------------------------------------------ 纵向级联

    private fun hookCascadeLayout() {
        val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_LAYOUT_POLICY)
        if (cls == null) {
            LogUtils.w(TAG, "install", "$CLS_LAYOUT_POLICY 不存在，跳过多小窗纵向错位")
            return
        }
        // (Rect launchBounds, ArrayList<Rect> 已有窗口, int flag, float scale, int scaleW)
        val m = Reflect.findMethodByShape(
            cls,
            "avoidOverlappingAreas",
            listOf(Rect::class.java, ArrayList::class.java, Int::class.javaPrimitiveType, Float::class.javaPrimitiveType, Int::class.javaPrimitiveType),
            null,
        )
        if (m == null) {
            LogUtils.w(TAG, "install", "avoidOverlappingAreas(Rect,ArrayList,int,float,int) 不存在，跳过")
            return
        }
        runCatching {
            HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val r = chain.proceed()
                    if (!enabled()) return r
                    runCatching { cascade(chain) }
                    return r
                }
            })
            LogUtils.i(TAG, "install", "已挂 ${cls.simpleName}#avoidOverlappingAreas（纵向错位）")
        }.onFailure {
            LogUtils.w(TAG, "install", "挂 avoidOverlappingAreas 失败：${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /**
     * 把本次要摆的小窗 `top` 挪到不与同列已有窗口重叠的位置。
     *
     * 全部用**原地写 Rect**（`offsetTo`）而不是换对象：调用方持有的是它自己传进来的
     * 那个 Rect 引用，换对象等于没改。
     */
    private fun cascade(chain: XposedInterface.Chain) {
        val owner = chain.thisObject ?: return
        val bounds = chain.args.getOrNull(0) as? Rect ?: return
        val existing = chain.args.getOrNull(1) as? List<*> ?: return
        val scale = chain.args.getOrNull(3) as? Float ?: return
        if (bounds.isEmpty || existing.isEmpty()) return
        if (!scale.isFinite() || scale <= 0f) return

        val screen = Reflect.getField(owner, "mWindowBounds") as? Rect ?: return
        val status = Reflect.getField(owner, "mStatusBarFrame") as? Rect
        val navigation = Reflect.getField(owner, "mNaviBarFrame") as? Rect
        val context = Reflect.getField(owner, "mContext") as? Context

        val density = context?.resources?.displayMetrics?.density
            ?: android.content.res.Resources.getSystem().displayMetrics.density
        val gap = maxOf(1, Math.round(CASCADE_GAP_DP * density))

        val minTop = maxOf(screen.top, status?.bottom ?: screen.top)
        var bottom = screen.bottom
        // 横屏时导航栏可能在侧边，不能把它的 top 当底部边界
        if (navigation != null && !navigation.isEmpty && navigation.width() >= screen.width() / 2 &&
            navigation.bottom >= screen.bottom
        ) {
            bottom = minOf(bottom, navigation.top)
        }
        val maxTop = bottom - Math.round(bounds.height() * scale)

        val right = bounds.left + Math.round(bounds.width() * scale)
        val occupiedTops = ArrayList<Int>(existing.size)
        for (item in existing) {
            if (item is Rect && !item.isEmpty && bounds.left < item.right && right > item.left) {
                occupiedTops.add(item.top)
            }
        }
        val top = findTop(bounds.top, minTop, maxTop, gap, occupiedTops)
        bounds.offsetTo(bounds.left, top)
    }

    /**
     * 无可用位置时保留原生位置；已有纵向错位的窗口不再移动（对齐上游语义）。
     */
    private fun findTop(original: Int, min: Int, max: Int, gap: Int, tops: List<Int>): Int {
        if (gap <= 0 || min > max || original < min || original > max) return original
        val candidate = nextFreeTop(original, max, gap, tops)
        if (candidate <= max) return candidate
        val fromMin = nextFreeTop(min, max, gap, tops)
        return if (fromMin <= max) fromMin else original
    }

    private fun nextFreeTop(start: Int, max: Int, gap: Int, tops: List<Int>): Int {
        var top = start
        var attempt = 0
        while (attempt <= tops.size && top <= max) {
            var next = top
            for (occupied in tops) {
                if (Math.abs(occupied.toLong() - top) < gap) next = maxOf(next, occupied + gap)
            }
            if (next == top) return top
            top = next
            attempt++
        }
        return top
    }

    // ------------------------------------------------------------ 取值

    private fun enabled(): Boolean =
        HookEnv.settingBoolean(FloatWindowConfig.KEY_COUNT_ENABLED, false)

    private fun configCount(): Int = HookEnv
        .settingInt(FloatWindowConfig.KEY_COUNT, FloatWindowConfig.COUNT_DEFAULT)
        .coerceIn(FloatWindowConfig.COUNT_MIN, FloatWindowConfig.COUNT_MAX)
}
