package com.fr.colorostools.hook.hookers

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.fr.colorostools.hook.HookEnv
import com.fr.colorostools.ref.Reflect
import com.fr.colorostools.utils.LogUtils
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method

/**
 * 功能：系统桌面（`com.android.launcher`）**隐藏应用**四件套。
 *
 * 移植来源：coloros-mod（MIT）`com.rikumi.colorosmod.hooks.LauncherHooks`，
 * 保留四个功能、按本机 ColorOS 17（系统桌面 17.3.12）重新验证钩子点。
 *
 * | 功能 | 钩子 | 备注 |
 * |---|---|---|
 * | 桌面双指张开打开隐藏应用 | `DragLayer#dispatchTouchEvent(MotionEvent)` | ScaleGestureDetector 判双指外张 |
 * | 多任务显示已隐藏应用 | `qp.c#i(Task)Z` | 本机已无 `OplusPrivacyManager#isHiddenPkg` |
 * | 彻底隐藏所选应用图标 | `LauncherApps#getActivityList` + `OplusAppFilter#shouldShowApp` | 双通道，缺一漏图标 |
 * | 彻底隐藏应用列表 | —— | 纯 UI 页（应用多选），本 hooker 只提供名单读取 |
 *
 * ## 关键适配（与上游的差异，都是本机逆向实证得出的）
 *
 * ### 1. 多任务：`OplusPrivacyManager#isHiddenPkg` 在本机已不存在
 *
 * 上游 hook 的是 `com.oplus.quickstep.privacy.OplusPrivacyManager#isHiddenPkg(String, int)`。
 * 本机 `系统桌面_17.3.12.apk` 里 **既没有这个类、也没有这个方法**
 * （`classes`/`methods` 全表 0 行；只有两处日志字符串残留）。
 * 逆向定位到实际替代者：`com.android.quickstep.i1`（混淆名）的
 * `qp.c#i(Task)Z`，被多任务**渲染路径**直接调用：
 *
 * - `OplusRecentsViewImpl#shouldSetAlphaTo1(...)` —— 决定卡片是否透明；
 * - `OplusTaskThumbnailViewImpl#checkAndUpdatePrivacy(...)` —— 决定缩略图是否打码。
 *
 * 另有 `qp.c#g(int, String)Z` 被手势路径（`gesture/a#t`、`#j2`）调用，
 * 那是「点隐藏应用时不截屏/不走最近任务」的保护，**不属于本功能**、
 * 也不该被放开 —— 所以只挂 `i(Task)`，不挂 `g`。
 *
 * `qp.c` 的字段形状佐证它就是原来那个隐私管理器：
 * `HashSet e/f/g`（三份隐藏名单：主用户 / 分身 / 多用户）+ `ArrayList h/i`（privacy 名单）
 * + 字符串常量 `mHiddenPackages` / `type_hide`。所以功能语义与上游完全一致。
 *
 * ### 2. `DeepProtectedAppsManager` 的入口被混淆
 *
 * 上游 `getInstance(Context)` → 本机不存在，只有 `k(Context)`（静态工厂）；
 * 上游 `showHideApps(Context, boolean)` → 本机不存在，只有 `N(Launcher)`（实例方法）。
 * 两者都按「先原名、再混淆名」探测，类升级后仍能自适应。
 *
 * ### 3. `isInState` 声明在父类
 *
 * `Launcher#isInState(BaseState)` 声明在 `com.android.launcher3.statemanager.StatefulActivity`，
 * **必须 callDeep** —— 用 `callByName` 恒返回 null（=永远判不出 NORMAL、手势永不触发且零报错）。
 *
 * ### 4. 彻底隐藏必须双通道 + 强制刷模型
 *
 * - `LauncherApps#getActivityList`：模型装载（桌面/抽屉/搜索）的**数据来源**；
 * - `OplusAppFilter#shouldShowApp`：ColorOS 自己的**过滤器**，桌面持久化模型与抽屉都过它。
 *
 * 只挂一个都会漏：只挂前者 ⇒ 已持久化在桌面上的图标还在；只挂后者 ⇒ 抽屉列表首帧有残留。
 *
 * 改名单后现有图标不会自己消失（模型是启动时装的），所以名单变化时在主线程
 * `LauncherModel#forceReload()`。刷新要等模型 `isModelLoaded()` 就绪、且延迟一轮，
 * 否则会和启动期装载撞车（上游为此写了 100 次重试 + idle 等待，这里保留简化版）。
 *
 * ## 兜底原则
 * 每一处独立 `runCatching`；类/方法找不到只记 W 后跳过；**任何一步失败都放行原始值** ——
 * 桌面是常驻高频应用，宁可不生效也不能让它崩。
 */
internal object LauncherHiddenAppsHook {

    private const val TAG = "LauncherHiddenApps"

    private const val CLS_DRAG_LAYER = "com.android.launcher3.dragndrop.DragLayer"
    private const val CLS_APP_FILTER = "com.android.launcher3.OplusAppFilter"
    private const val CLS_LAUNCHER_APPS = "android.content.pm.LauncherApps"
    private const val CLS_DPAM = "com.android.launcher.filter.DeepProtectedAppsManager"
    private const val CLS_LAUNCHER = "com.android.launcher.Launcher"
    private const val CLS_LAUNCHER_STATE = "com.android.launcher3.LauncherState"

    private const val CLS_APP_STATE = "com.android.launcher3.LauncherAppState"

    /**
     * 多任务判定类（混淆名，本机 ColorOS 17 / 系统桌面 17.3.12 实证）。
     *
     * ⚠️ 它在 **DEX 根包**里（描述符 `Lqp/c;`），不是 `com.android.quickstep.qp.c` ——
     * 后者在本机 `classes` 表 0 行，写错会导致 `classOrNull` 恒 null、hook 静默不装。
     * 该类的单例通过 `Lqp/c$c;->a:Lqp/c;` 暴露，被
     * `OplusRecentsViewImpl#shouldSetAlphaTo1` / `OplusTaskThumbnailViewImpl#checkAndUpdatePrivacy`
     * 直接调用（`method_calls` 实证）。
     */
    private const val CLS_PRIVACY_HOLDER = "qp.c"

    /** 名单缓存 TTL：桌面一次装载的连续调用走缓存，改名单后 1s 内一定读到新值 */
    private const val HIDDEN_CACHE_TTL_MS = 1000L

    /**
     * 触摸门控缓存 TTL。
     *
     * `dispatchTouchEvent` 是**每帧多次**的高频入口（滑动桌面时每秒上百次），
     * 每次去 remote prefs 取一次布尔值在不确定其内部是否走 IPC 的前提下太冒险。
     * 300ms 的窗口既能把读取次数压到可忽略，又让「设置页关掉开关 → 回桌面」几乎立刻生效。
     */
    private const val GATE_CACHE_TTL_MS = 300L

    fun install() {
        hookPinchOut()
        hookRecentsShowHidden()
        hookGetActivityList()
        hookShouldShowApp()
        hookLauncherResume()
        LogUtils.i(TAG, "install", "桌面隐藏应用四件套已装")
    }

    /**
     * `Launcher#onResume` 后置 → 检查「彻底隐藏名单」是否变了，变了就重装模型。
     *
     * 选这个时机的原因：名单是在**模块 App** 里改的，桌面进程只能靠「用户切回桌面」
     * 这个必然发生的时刻去对齐。上游用跨进程快照 + 首启等待链，复杂且易与启动期装载撞车；
     * 这里改成「回到桌面时对比上一次的值」，简单且不会误刷。
     */
    private fun hookLauncherResume() {
        val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_LAUNCHER) ?: return
        val m = Reflect.findMethod(cls, "onResume", 0) ?: run {
            LogUtils.w(TAG, "install", "Launcher#onResume 不存在，名单变更后无法自动刷新")
            return
        }
        runCatching {
            HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val r = chain.proceed()
                    runCatching { onLauncherResume() }
                    return r
                }
            })
            LogUtils.i(TAG, "install", "已挂 Launcher#onResume（名单变更刷新）")
        }.onFailure {
            LogUtils.w(TAG, "install", "挂 Launcher#onResume 失败：${it.message}")
        }
    }

    // ==================================================================
    // 功能 1：桌面双指张开 → 打开隐藏应用文件夹
    // ==================================================================

    private fun hookPinchOut() {
        val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_DRAG_LAYER) ?: run {
            LogUtils.w(TAG, "install", "$CLS_DRAG_LAYER 不存在，跳过双指张开")
            return
        }
        // 「限定本类声明」：dispatchTouchEvent 在 ViewGroup 也有实现，
        // 向上遍历会命中所有容器（Reflect.findMethod 只查 declaredMethods，正合需求）
        val m = Reflect.findMethod(cls, "dispatchTouchEvent", 1) ?: run {
            LogUtils.w(TAG, "install", "DragLayer#dispatchTouchEvent 不存在，跳过双指张开")
            return
        }
        runCatching {
            HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    // 手势全程都不能影响原生事件分发：先算手势、再原样 proceed
                    runCatching { onDragLayerTouch(chain.thisObject, chain.getArg(0) as? MotionEvent) }
                    return chain.proceed()
                }
            })
            LogUtils.i(TAG, "install", "已挂 DragLayer#dispatchTouchEvent（双指张开）")
        }.onFailure {
            LogUtils.w(TAG, "install", "挂双指张开失败：${it.message}")
        }
    }

    /**
     * 每个 DragLayer 实例挂一个 ScaleGestureDetector（存在附加字段里，随实例回收）。
     *
     * 触发条件（与上游一致，两个都要满足，避免误触）：
     * - 累计比例 `accum > 1.2`（明显外张，不是轻微抖动）；
     * - 实际张开距离 `spread > 100dp`（小屏单手持机时比例容易达标但距离不够）。
     *
     * 一个手势只触发一次（`fired` 位），抬手后 `onScaleBegin` 重置。
     */
    private fun onDragLayerTouch(dragLayer: Any?, ev: MotionEvent?) {
        if (dragLayer !is View) return
        if (ev == null) return
        // ⚠️ 运行时门控：hook 恒挂（便于随时开关），但开关关闭时必须完全不响应手势。
        // 少了这一行会让「双指张开」无条件生效、且设置页开关形同虚设。
        if (!pinchOutEnabled()) return
        val detector = pinchDetectorOf(dragLayer)
        // 仅在桌面 NORMAL 状态响应（编辑态/抽屉态等交给系统原生手势）
        if (!isLauncherInNormalState(dragLayer.context)) return
        detector.onTouchEvent(ev)
    }

    /** DragLayer 实例 → 它专用的 ScaleGestureDetector（首次触摸时创建） */
    private val pinchDetectors = java.util.WeakHashMap<Any, Holder>()

    private class Holder(val detector: ScaleGestureDetector, val state: PinchState)

    private class PinchState {
        var accum = 1.0f
        var beginSpan = 0f
        var fired = false
    }

    /** 双指张开开关（[GATE_CACHE_TTL_MS] 缓存，避免每个触摸事件都读设置） */
    @Volatile
    private var pinchGateValue = false

    @Volatile
    private var pinchGateAt = 0L

    private fun pinchOutEnabled(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - pinchGateAt < GATE_CACHE_TTL_MS) return pinchGateValue
        val v = HookEnv.settingBoolean(LauncherHiddenAppsConfig.KEY_PINCH_OUT, false)
        pinchGateValue = v
        pinchGateAt = now
        return v
    }

    private fun pinchDetectorOf(view: View): ScaleGestureDetector {
        pinchDetectors[view]?.let { return it.detector }
        val ctx = view.context
        val state = PinchState()
        // 双指需实际张开的最小距离（按 dp 折算，适配不同密度屏）
        val minSpreadPx = 100f * ctx.resources.displayMetrics.density
        val listener = object : ScaleGestureDetector.OnScaleGestureListener {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                state.accum = 1.0f
                state.beginSpan = d.currentSpan
                state.fired = false
                return true
            }

            override fun onScale(d: ScaleGestureDetector): Boolean {
                state.accum *= d.scaleFactor
                val spread = d.currentSpan - state.beginSpan
                if (!state.fired && state.accum > 1.2f && spread > minSpreadPx) {
                    state.fired = true
                    openHideApps(ctx)
                }
                return false
            }

            override fun onScaleEnd(d: ScaleGestureDetector) = Unit
        }
        val detector = ScaleGestureDetector(ctx, listener)
        pinchDetectors[view] = Holder(detector, state)
        return detector
    }

    /**
     * 打开系统原生的「隐藏应用」文件夹。
     *
     * 入口名在两代 ColorOS 之间变了：`getInstance(Context)` + `showHideApps(Context, boolean)`
     * → `k(Context)` + `N(Launcher)`。先按原名校验签名，不匹配再取混淆名，
     * 且**都要求返回类型/参数类型对得上**（只按名字盲取容易拿到同名无关方法）。
     */
    private fun openHideApps(ctx: Context) {
        val mgrCls = Reflect.classOrNull(HookEnv.classLoader, CLS_DPAM) ?: run {
            LogUtils.w(TAG, "openHideApps", "$CLS_DPAM 不存在，无法打开隐藏应用")
            return
        }
        val instance = runCatching {
            val factory = staticFactory(mgrCls)
            factory?.invoke(null, ctx)
        }.getOrNull() ?: run {
            LogUtils.w(TAG, "openHideApps", "取 DeepProtectedAppsManager 单例失败")
            return
        }
        if (invokeShowHideApps(instance, mgrCls, ctx)) {
            LogUtils.i(TAG, "openHideApps", "双指张开 → 已请求打开隐藏应用文件夹")
        } else {
            LogUtils.w(TAG, "openHideApps", "两种打开方式都失败（类结构可能已变）")
        }
    }

    /** 静态工厂：优先 `k(Context)`，回落 `getInstance(Context)` */
    private fun staticFactory(mgrCls: Class<*>): Method? {
        for (name in arrayOf("k", "getInstance")) {
            val m = Reflect.findMethod(mgrCls, name, 1) ?: continue
            if (!Reflect.isStatic(m)) continue
            if (m.parameterTypes[0] != Context::class.java) continue
            if (m.returnType != mgrCls) continue
            return m
        }
        return null
    }

    /** 打开文件夹：优先 `N(Launcher)`，回落 `showHideApps(Context, boolean)` */
    private fun invokeShowHideApps(instance: Any, mgrCls: Class<*>, ctx: Context): Boolean {
        val launcherCls = Reflect.classOrNull(HookEnv.classLoader, CLS_LAUNCHER)
        if (launcherCls != null && launcherCls.isInstance(ctx)) {
            val show = Reflect.findMethod(mgrCls, "N", 1)
            if (show != null && show.parameterTypes[0] == launcherCls &&
                show.returnType == Void.TYPE
            ) {
                runCatching { show.invoke(instance, ctx) }.onSuccess { return true }
            }
        }
        val legacy = Reflect.findMethod(mgrCls, "showHideApps", 2)
        if (legacy != null && legacy.returnType == Boolean::class.javaPrimitiveType) {
            runCatching { legacy.invoke(instance, ctx, false) }.onSuccess { return true }
        }
        return false
    }

    /**
     * 桌面是否处于 `LauncherState.NORMAL`。
     *
     * ⚠️ `isInState` 声明在父类 `StatefulActivity` 上 ⇒ 必须 [Reflect.callDeep]；
     * `NORMAL` 是静态字段，取法要按静态读（`Reflect.getField(null, …)` 拿不到）。
     */
    private fun isLauncherInNormalState(ctx: Context?): Boolean {
        if (ctx == null) return false
        val launcherCls = Reflect.classOrNull(HookEnv.classLoader, CLS_LAUNCHER) ?: return false
        if (!launcherCls.isInstance(ctx)) return false
        val normalState = normalLauncherState() ?: return false
        return Reflect.callDeep(ctx, "isInState", normalState) as? Boolean ?: false
    }

    /** `LauncherState.NORMAL` 静态字段（缓存；取不到则下次重试） */
    @Volatile
    private var normalStateCache: Any? = null

    private fun normalLauncherState(): Any? {
        normalStateCache?.let { return it }
        val stateCls = Reflect.classOrNull(HookEnv.classLoader, CLS_LAUNCHER_STATE) ?: return null
        val v = runCatching { Reflect.findField(stateCls, "NORMAL")?.get(null) }.getOrNull() ?: return null
        normalStateCache = v
        return v
    }

    // ==================================================================
    // 功能 2：多任务显示已隐藏应用
    // ==================================================================

    /**
     * hook `qp.c#i(Task)Z`（本机替身，见类文档 §1）。
     *
     * 只在调用方来自 quickstep 渲染路径时放开；**从桌面点击启动隐藏应用那条路
     * （`qp.c#g`）不动** —— 那是系统自己的隐私保护，放开会导致隐藏应用被截屏/留在最近任务。
     */
    private fun hookRecentsShowHidden() {
        val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_PRIVACY_HOLDER) ?: run {
            LogUtils.w(TAG, "install", "$CLS_PRIVACY_HOLDER 不存在（多任务显示已隐藏跳过）")
            return
        }
        val taskCls = Reflect.classOrNull(
            HookEnv.classLoader, "com.android.systemui.shared.recents.model.Task",
        ) ?: run {
            // ⚠️ Task 解析不到时必须放弃，**不能**把 null 传给 findMethodByShape：
            //    null 在那里表示「该位置任意类型」，会命中 qp.c 里同名同返回值的无关重载
            //    （例如 `i` 只有一个，但 `g/h` 都有 (int,String) 版；一旦签名漂移就会挂错方法）。
            LogUtils.w(TAG, "install", "recents Task 类不存在，跳过多任务显示已隐藏")
            return
        }
        // 按名字 + 精确形状取（混淆名在 OTA 后可能被重排，形状比名字更稳）
        val m = Reflect.findMethodByShape(
            cls, "i", listOf(taskCls), Boolean::class.javaPrimitiveType,
        ) ?: run {
            LogUtils.w(TAG, "install", "qp.c#i(Task)Z 不存在，跳过多任务显示已隐藏")
            return
        }
        runCatching {
            HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    // 只在「系统判定为隐藏」时才有必要改写，省掉绝大部分调用
                    if (result != true) return result
                    if (!recentsShowHiddenEnabled()) return result
                    if (!callerInQuickstepRenderPath()) return result
                    if (recentsBypassLogged < 20) {
                        recentsBypassLogged++
                        LogUtils.i(TAG, "recentsBypass", "多任务放开隐藏任务（第 $recentsBypassLogged 次）")
                    }
                    return false
                }
            })
            LogUtils.i(TAG, "install", "已挂 qp.c#i(Task)（多任务显示已隐藏）")
        }.onFailure {
            LogUtils.w(TAG, "install", "挂多任务显示已隐藏失败：${it.message}")
        }
    }

    private var recentsBypassLogged = 0

    /**
     * 调用栈里是否有 quickstep 的**渲染/手势**类。
     *
     * 排除含 `lock` 的类：`com.oplus.quickstep.applock.*` 是应用锁，与隐藏应用无关，
     * 放开它会把加锁应用也显示出来（上游同款过滤）。
     */
    private fun callerInQuickstepRenderPath(): Boolean =
        Throwable().stackTrace.any { e ->
            val cn = e.className ?: return@any false
            cn.startsWith("com.android.quickstep") && !cn.lowercase().contains("lock")
        }

    private fun recentsShowHiddenEnabled(): Boolean =
        HookEnv.settingBoolean(LauncherHiddenAppsConfig.KEY_RECENTS_SHOW_HIDDEN, false)

    // ==================================================================
    // 功能 3：彻底隐藏所选应用图标
    // ==================================================================

    /**
     * 通道 A：`LauncherApps#getActivityList(String, UserHandle)` 后置过滤。
     *
     * 这是**所有模型装载**（桌面 / 抽屉 / 搜索 / 最近更新）的数据来源，
     * 在这里剔除即等于「该应用从未被安装过」。
     */
    private fun hookGetActivityList() {
        val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_LAUNCHER_APPS) ?: run {
            LogUtils.w(TAG, "install", "$CLS_LAUNCHER_APPS 不存在，跳过程序列表过滤")
            return
        }
        val m = Reflect.findMethod(cls, "getActivityList", 2) ?: run {
            LogUtils.w(TAG, "install", "LauncherApps#getActivityList 不存在，跳过")
            return
        }
        runCatching {
            HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    val hidden = hiddenComponentsOrEmpty()
                    if (hidden.isEmpty()) return result
                    val list = result as? List<*> ?: return result
                    // ⚠️ 不要原地 removeAll：宿主返回的可能是 Collections.unmodifiableList / Arrays.asList，
                    //    原地改会抛 UnsupportedOperationException（runCatching 吞掉后等于没过滤）。
                    //    这里返回**新列表**，语义与上游一致（调用方只读使用）。
                    val kept = ArrayList<Any?>(list.size)
                    var removed = 0
                    for (info in list) {
                        val cn = (info as? LauncherActivityInfo)?.componentName
                        if (cn != null && cn in hidden) {
                            removed++
                        } else {
                            kept.add(info)
                        }
                    }
                    if (removed == 0) return result
                    LogUtils.d(TAG, "getActivityList", "剔除彻底隐藏应用 $removed 项（剩 ${kept.size} 项）")
                    return kept
                }
            })
            LogUtils.i(TAG, "install", "已挂 LauncherApps#getActivityList（彻底隐藏图标）")
        }.onFailure {
            LogUtils.w(TAG, "install", "挂 getActivityList 失败：${it.message}")
        }
    }

    /**
     * 通道 B：`OplusAppFilter#shouldShowApp` 前置返回 false。
     *
     * ColorOS 在 Launcher3 之外加的一层过滤器，桌面持久化模型与抽屉都过它；
     * 四个重载里只挂 `(ComponentName, UserHandle)` —— 那是模型装载的实际入参形状。
     */
    private fun hookShouldShowApp() {
        val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_APP_FILTER) ?: run {
            LogUtils.w(TAG, "install", "$CLS_APP_FILTER 不存在，跳过 OplusAppFilter 通道")
            return
        }
        // ⚠️ 必须按**精确形状**取，不能用 findMethod(name, 2)：本机有 4 个重载，
        //    declaredMethods 顺序里 (String, UserHandle)Z 排在 (ComponentName, UserHandle)Z 之前，
        //    按名+参数个数取会拿到 String 版，随后签名校验判不符 → 直接跳过 → 功能静默失效。
        // UserHandle 直接用编译期引用（公开 API，必存在），不要反射取 —— 反射取到 null 会让
        // 形状校验退化成「该位置任意」，等于丢失了「排除 String 重载」这个关键约束。
        val m = Reflect.findMethodByShape(
            cls,
            "shouldShowApp",
            listOf(ComponentName::class.java, android.os.UserHandle::class.java),
            Boolean::class.javaPrimitiveType,
        ) ?: run {
            LogUtils.w(TAG, "install", "OplusAppFilter#shouldShowApp(ComponentName,UserHandle)Z 不存在，跳过")
            return
        }
        runCatching {
            HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val cn = chain.getArg(0) as? ComponentName
                    if (cn != null && cn in hiddenComponentsOrEmpty()) return false
                    return chain.proceed()
                }
            })
            LogUtils.i(TAG, "install", "已挂 OplusAppFilter#shouldShowApp（彻底隐藏图标）")
        }.onFailure {
            LogUtils.w(TAG, "install", "挂 shouldShowApp 失败：${it.message}")
        }
    }

    // ==================================================================
    // 名单读取
    // ==================================================================

    /**
     * 彻底隐藏的组件集合。
     *
     * 存储形状：`SettingsPrefs["hidden_launcher_components"]` 是 **StringSet**，
     * 元素为 `ComponentName.flattenToString()` 即 `包名/类名`。
     *
     * ⚠️ 总开关关闭时**必须返回空集**（而不是只靠 UI 置灰）：名单保留、
     * 但语义是「本功能没启用」，否则关掉开关后图标仍然不出现，用户无从恢复。
     *
     * ⚠️ **必须走 [hiddenComponentsCached]**：`shouldShowApp` 对每个图标调用一次
     * （一次桌面装载几百次、且每次 OTA 变化后都会重跑），每次跨进程读 remote prefs
     * + 解析 ComponentName 会明显拖慢桌面首帧。
     */
    private fun hiddenComponentsOrEmpty(): Set<ComponentName> = hiddenComponentsCached()

    /** 名单缓存：值 + 到期时间。TTL 覆盖「一次装载的连续调用」，同时保证改名单后 <1s 生效 */
    @Volatile
    private var hiddenCache: Set<ComponentName>? = null

    @Volatile
    private var hiddenCacheAt = 0L

    private fun hiddenComponentsCached(): Set<ComponentName> {
        val now = SystemClock.uptimeMillis()
        hiddenCache?.let { if (now - hiddenCacheAt < HIDDEN_CACHE_TTL_MS) return it }
        val fresh = readHiddenComponents()
        hiddenCache = fresh
        hiddenCacheAt = now
        return fresh
    }

    /** 名单变更（`Launcher#onResume` 时机）时强制失效，避免「刚改完回桌面还看到旧结果」 */
    private fun invalidateHiddenCache() {
        hiddenCache = null
        hiddenCacheAt = 0L
    }

    private fun readHiddenComponents(): Set<ComponentName> {
        if (!HookEnv.settingBoolean(LauncherHiddenAppsConfig.KEY_HIDDEN_ENABLED, false)) {
            return emptySet()
        }
        val raw = HookEnv.settingStringSet(LauncherHiddenAppsConfig.KEY_HIDDEN_COMPONENTS)
        if (raw.isEmpty()) return emptySet()
        return raw.mapNotNullTo(HashSet()) { ComponentName.unflattenFromString(it) }
    }

    // ==================================================================
    // 模型刷新（名单变化后让已有图标消失）
    // ==================================================================

    /** 上次已应用的隐藏名单；变化时才刷模型，避免每次启动都白刷一次 */
    @Volatile
    private var lastApplied: Set<ComponentName>? = null

    @Volatile
    private var reloadPending = false

    /**
     * 名单变化时让模型重新装载（**必须主线程**）。
     *
     * 调用点：`Launcher#onResume` 之后 —— 那时模型一定已就绪，且用户从设置页切回桌面
     * 正好是「刚改完名单」的时刻。比 hook 设置写入的广播链可靠得多
     * （上游为此写了跨进程快照 + 100 次重试的等待链）。
     */
    fun onLauncherResume() {
        // 先失效缓存：回到桌面必然要读最新名单 / 最新开关
        invalidateHiddenCache()
        pinchGateAt = 0L
        val current = hiddenComponentsOrEmpty()
        val previous = lastApplied
        lastApplied = current
        // 进程启动后的第一次 resume：本次模型装载已经过 getActivityList / shouldShowApp 过滤，
        // 结果天然正确 ⇒ 只记录基线，不刷新（否则冷启动会白白多装一遍模型）。
        if (previous == null) return
        if (current == previous) return
        forceReloadModel()
    }

    private fun forceReloadModel() {
        if (reloadPending) return
        reloadPending = true
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed({ doForceReload(handler, 0) }, 200)
    }

    /**
     * 等模型就绪再刷。
     *
     * `forceReload()` 在模型还在首次装载时调用会被随后的装载覆盖掉（名字消失不了），
     * 所以先看 `isModelLoaded()`，最多重试 20 次（20 × 300ms ≈ 6s，足够覆盖冷启动）。
     */
    private fun doForceReload(handler: Handler, attempt: Int) {
        val done = runCatching {
            val appStateCls = Reflect.classOrNull(HookEnv.classLoader, CLS_APP_STATE)
                ?: return@runCatching false
            val appState = Reflect.callStatic(appStateCls, "getInstanceNoCreate")
                ?: return@runCatching false
            val model = Reflect.callByName(appState, "getModel") ?: return@runCatching false
            // ⚠️ `getModel()` 返回的是子类 OplusLauncherModel，而 `isModelLoaded()` / `forceReload()`
            //    声明在父类 LauncherModel（method_calls 实证）⇒ 必须 callDeep。
            //    用 callByName 恒返回 null ⇒ 判「未就绪」永远成立、20 次重试后超时放弃，
            //    现象是「改完名单回桌面，图标还在」且只有一行超时 W 日志。
            if (Reflect.callDeep(model, "isModelLoaded") != true) return@runCatching false
            Reflect.callDeep(model, "forceReload")
            LogUtils.i(TAG, "forceReload", "已请求重装桌面模型（应用彻底隐藏名单）")
            true
        }.getOrDefault(false)

        if (done) {
            reloadPending = false
            return
        }
        if (attempt < 20) {
            handler.postDelayed({ doForceReload(handler, attempt + 1) }, 300)
        } else {
            reloadPending = false
            LogUtils.w(TAG, "forceReload", "模型就绪等待超时，本次未刷新（下次回桌面会再试）")
        }
    }
}

/**
 * 桌面隐藏应用四功能的开关与名单（App 设置页写入、hook 侧读取，两边同一份 remote prefs）。
 */
internal object LauncherHiddenAppsConfig {

    /** 桌面双指张开打开隐藏应用 */
    const val KEY_PINCH_OUT = "pinch_out_open_hide_apps_enabled"

    /** 多任务显示已隐藏应用 */
    const val KEY_RECENTS_SHOW_HIDDEN = "recents_show_hidden_enabled"

    /** 彻底隐藏所选应用图标（总开关） */
    const val KEY_HIDDEN_ENABLED = "hidden_launcher_apps_enabled"

    /** 彻底隐藏的组件名单（StringSet，元素 = `包名/类名`） */
    const val KEY_HIDDEN_COMPONENTS = "hidden_launcher_components"
}