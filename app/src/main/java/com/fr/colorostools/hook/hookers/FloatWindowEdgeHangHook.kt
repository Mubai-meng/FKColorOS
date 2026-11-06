package com.fr.colorostools.hook.hookers

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.fr.colorostools.hook.HookEnv
import com.fr.colorostools.ref.Reflect
import com.fr.colorostools.utils.LogUtils
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference

/**
 * 功能：**悬浮小窗贴边挂机** + **贴边挂机静音** + **贴边小窗显示为白色竖条**（宿主 = system_server）。
 *
 * 移植来源：coloros-mod（MIT）`hooks/SystemServerHooks#hookFloatWindowEdgeHangSystemServer`
 * / `#hookFloatWindowEdgeHangMute` / `#hookFloatWindowHandleWhiteBar`。
 *
 * ## 一、贴边挂机在做什么
 *
 * ColorOS 的「贴边」= 把小窗缩成一个贴在屏幕边缘的把手，**应用退到后台**（原生会调
 * `Task#moveTaskToBack`）。挂机要的效果正相反：**把手照旧显示，但应用继续在台前跑**。
 *
 * 所以整条链路是「拦下退后台 + 把画面藏起来 + 焦点交还给下面的窗口」：
 *
 * | 环节 | hook 点 | 做法 |
 * |---|---|---|
 * | 判定「这次贴边」 | `FlexibleTaskController#exitFlexibleTask(...,7 参)` | `args[2] == 16`（EXIT_TO_FLOAT）时记到 [edgeExitTask] |
 * | 拦下退后台 | `TaskExtImpl#moveTaskToBackForPanorama(Task, boolean, int, int)` | 目标任务是本次贴边任务且已在浮窗列表 ⇒ 登记挂机并 **不 proceed** |
 * | 藏住画面 | `Task#prepareSurfaces()` | 后置 `getSyncTransaction().hide(surface)` |
 * | 焦点下移 | `DisplayContent#setFocusedApp(ActivityRecord)` | 把焦点改成「小窗下面那个可聚焦任务」的顶层 Activity |
 * | 拒绝输入 | `WindowState#canReceiveKeys` / `canReceiveTouchInput` | 挂机窗口一律 false |
 * | 拦住退栈 | `ActivityRecord#addToStopping(..., "startToFloatAnimation")` | 挂机任务不加入 stopping |
 * | 恢复 | `prepareSurfaces` / `removeImmediately` / `notifyKeyguardStateChanged` | 离开浮窗列表或解锁时清标记、重新聚焦 |
 *
 * ⚠️ **核心钩子不全时整体不接管**（[coreReady]）：`canReceiveKeys` / `canReceiveTouchInput` /
 * `prepareSurfaces` 任一缺失，就宁可保持原生「退后台」行为 —— 否则会出现
 * 「小窗还在但点不动、背后窗口也没焦点」的死状态。
 *
 * ## 二、静音
 *
 * 走系统「多应用音量」通道（`PlaybackActivityMonitorExtImpl#setVolumeForUid`），
 * 把该 uid 的应用音量置 0，恢复时写回原值（原值从 `mMusicVolumeMap` 读）。
 *
 * 该 ExtImpl **没有静态取实例入口**，只能在构造时捕获（`PlaybackActivityMonitorExtImpl(Object)`，本机实证）。
 * 恢复时机不依赖「贴边退出」：`ActivityTaskManagerService#setFocusedTask(int, ActivityRecord)`
 * 是任何应用回前台必经的焦点中枢，在那里统一检查「已静音的任务是否还在浮窗列表」。
 *
 * ## 三、白色竖条
 *
 * 把手（`FloatHandleView`）原生是「半透明胶囊 + 应用图标」。白色竖条模式把它改成
 * 4dp 宽白色圆角条、隐藏图标、贴到距右边缘 8dp。需要改 5 个出口，因为框架会在多处
 * 重新覆盖宽度/位置/图标可见性：
 *
 * | 方法 | 为什么 |
 * |---|---|---|
 * | `updateIconStyle(int, FloatHandleInfo)` | 图标刷新时重设样式 |
 * | `updateViewLayoutParams(boolean, boolean)` | **框架每次都用整条把手宽度覆盖容器宽度，必须在这里拦** |
 * | `updateCurrentPosition(float, float, int)` | 吸附后位置 |
 * | `computeCurrentPosition(int)` | 位置计算结果（原生是「探出屏幕」的坐标） |
 * | `switchToFullMode(...)` | 展开回小窗时套用样式并保持不透明 |
 * | `FloatIconListAdapter#onBindViewHolder` | 兜底：图标列表被重新显示时逐个隐藏 |
 * | `FloatIconListAnimationLayout#animateTo{Expand,Stacking}` | 跳过图标展开/堆叠动画 |
 *
 * ## 本机实证（ColorOS 17 / oplus-services 反编译）
 *
 * - `TaskExtImpl#moveTaskToBackForPanorama(Task,boolean,int,int)` :4310
 * - `FlexibleTaskController#exitFlexibleTask` 两个重载 :7952（4 参）/ :7956（7 参）
 * - `FlexibleTaskController#notifyKeyguardStateChanged(boolean,boolean,int)` :19496
 * - `FlexibleTaskController#getTaskUnderFlexible(Task)` :16533（private）、
 *   `#getFlexibleTaskFullScreenRatio` 之外的 `getFlexibleTaskController()` 在 FWMS 上
 * - `FloatHandleController#{getInstance():103, isInFloatingList(int):384, getScreenWidth():1662}`
 * - `FloatHandleView#{updateIconStyle(int,FloatHandleInfo):1049, updateViewLayoutParams(boolean,boolean):971,
 *   updateCurrentPosition(float,float,int):1035, computeCurrentPosition(int):1864, switchToFullMode(5 参):1114}`，
 *   字段 `mFloatHandleViewContainer` / `mViewContainerLayoutParams` / `mFloatIconListAnimationLayout` / `mCurrentSide`
 * - `PlaybackActivityMonitorExtImpl(Object)` :34、`setVolumeForUid(float,int,String,boolean)` :49、`mMusicVolumeMap` :29
 *
 * ## `services.jar` 复核（AOSP 侧钩子逐个查过）
 *
 * - `WindowState#canReceiveKeys()` / `#canReceiveKeys(Z)` / `#canReceiveTouchInput()` 都在（本机两条重载都有）
 * - `ActivityRecord#addToStopping(Z, String)` —— **2 参、String 在下标 1**；
 *   `exitFlexibleTask` 里就是这么调的：`addToStopping(true, "startToFloatAnimation")`
 * - `Task#prepareSurfaces()` 只有 24 条指令（真逻辑在父类 `TaskFragment`），后置 hide 仍然有效
 * - `Task#removeImmediately()` 与 `#removeImmediately(String)` **两个重载都在**，都是真删除入口
 * - `Task#mLastSurfaceShowing` **不存在**（全库搜 `SurfaceShowing` 0 行）⇒ 释放时必须显式 show
 * - `DisplayContent#setFocusedApp(ActivityRecord)Z`、`DisplayContent#updateFocusedWindowLocked(IZI)Z`、
 *   `WindowManagerService#updateFocusedWindowLocked(IZ)Z`（本 Hook 走 WMS 的两参版）
 * - `ActivityTaskManagerService#setFocusedTask(I)` + `#setFocusedTask(I, ActivityRecord)` 都在
 */
internal object FloatWindowEdgeHangHook {

    private const val TAG = "FloatWindowEdgeHang"

    private const val CLS_FTC = "com.android.server.wm.FlexibleTaskController"
    private const val CLS_TASK = "com.android.server.wm.Task"
    private const val CLS_TASK_EXT = "com.android.server.wm.TaskExtImpl"
    private const val CLS_WINDOW_STATE = "com.android.server.wm.WindowState"
    private const val CLS_ACTIVITY_RECORD = "com.android.server.wm.ActivityRecord"
    private const val CLS_DISPLAY_CONTENT = "com.android.server.wm.DisplayContent"
    private const val CLS_FLOAT_HANDLE_CONTROLLER = "com.android.server.wm.FloatHandleController"
    private const val CLS_FWMS = "com.android.server.wm.FlexibleWindowManagerService"
    private const val CLS_ATMS = "com.android.server.wm.ActivityTaskManagerService"
    private const val CLS_FLOAT_HANDLE_VIEW = "com.android.server.wm.floathandle.FloatHandleView"
    private const val CLS_FLOAT_HANDLE_INFO = "com.android.server.wm.floathandle.FloatHandleInfo"
    private const val CLS_ICON_LIST = "com.android.server.wm.floathandle.FloatIconListAnimationLayout"
    private const val CLS_ICON_ADAPTER = "com.android.server.wm.floathandle.FloatHandleView\$FloatIconListAdapter"
    private const val CLS_ICON_HOLDER = "com.android.server.wm.floathandle.FloatHandleView\$FloatIconViewHolder"
    private const val CLS_PAM_EXT = "com.android.server.audio.PlaybackActivityMonitorExtImpl"

    /** `exitFlexibleTask` 的 flexibleCustomTransition 取值：贴边成浮窗 */
    private const val EXIT_TO_FLOAT = 16

    /** 白条：竖条宽度与距右边缘距离（dp，对齐上游） */
    private const val BAR_WIDTH_DP = 4
    private const val BAR_EDGE_DP = 8

    // ------------------------------------------------------------ 状态

    /** 当前处于「贴边挂机」的 taskId 集合 */
    private val hungIds: MutableSet<Int> = java.util.Collections.synchronizedSet(HashSet())

    /** taskId → Task（弱引用，只用于解锁后纠正焦点时取对象） */
    private val hungTasks: MutableMap<Int, WeakReference<Any>> =
        java.util.Collections.synchronizedMap(HashMap())

    /** `exitFlexibleTask` 提交期间标记「本次贴边的是哪个任务」（线程局部，避免嵌套调用串号） */
    private val edgeExitTask = ThreadLocal<Any?>()

    /** 核心钩子是否装全（装不全就不接管，保持原生退后台） */
    @Volatile
    private var coreReady = false

    @Volatile
    private var floatHandleControllerCls: Class<*>? = null

    @Volatile
    private var fwmsCls: Class<*>? = null

    @Volatile
    private var flexibleTaskController: Any? = null

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun install() {
        floatHandleControllerCls = Reflect.classOrNull(HookEnv.classLoader, CLS_FLOAT_HANDLE_CONTROLLER)
        fwmsCls = Reflect.classOrNull(HookEnv.classLoader, CLS_FWMS)

        var core = true
        core = hookExitFlexibleTask() && core
        core = hookWindowInput() && core
        core = hookAddToStopping() && core
        core = hookPrepareSurfaces() && core
        coreReady = core
        LogUtils.i(TAG, "install", "贴边挂机核心钩子 ${if (core) "已就绪" else "不完整（保持原生退后台）"}")

        hookMoveTaskToBackForPanorama()
        hookRemoveTask()
        hookKeyguardRefocus()
        hookFocusedApp()
        hookWhiteBar()
        hookMute()
    }

    // ============================================================ 核心：拦截退后台

    private fun hookExitFlexibleTask(): Boolean {
        val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_FTC) ?: return false
        val task = Reflect.classOrNull(HookEnv.classLoader, CLS_TASK)
        val m = Reflect.findMethodByShape(
            cls,
            "exitFlexibleTask",
            listOf(
                task, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            ),
            null,
        ) ?: run {
            LogUtils.w(TAG, "install", "exitFlexibleTask 7 参签名不存在，贴边挂机不可用")
            return false
        }
        return runCatching {
            HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val previous = edgeExitTask.get()
                    val exitTo = chain.args.getOrNull(2) as? Int
                    edgeExitTask.set(if (exitTo == EXIT_TO_FLOAT) chain.args.getOrNull(0) else null)
                    try {
                        val r = chain.proceed()
                        afterExit(chain)
                        return r
                    } finally {
                        if (previous == null) edgeExitTask.remove() else edgeExitTask.set(previous)
                    }
                }
            })
            true
        }.getOrElse {
            LogUtils.w(TAG, "install", "挂 exitFlexibleTask 失败：${it.message}")
            false
        }
    }

    /** 贴边退出提交完成后：若该任务仍在挂机，重新把焦点交给它下面的窗口 */
    private fun afterExit(chain: XposedInterface.Chain) {
        val task = chain.args.getOrNull(0) ?: return
        if (hungIds.isEmpty()) return
        runCatching {
            val wm = Reflect.getField(task, "mWmService") ?: return
            val lock = Reflect.getField(wm, "mGlobalLock") ?: return
            synchronized(lock) {
                if (isActiveHungTask(task)) focusTaskBehind(task)
            }
        }
    }

    /** 挂机窗口不接受按键/触摸，否则焦点下移后仍会「看不见但吃掉输入」 */
    private fun hookWindowInput(): Boolean {
        val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_WINDOW_STATE) ?: return false
        val reject = object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                if (hungIds.isEmpty()) return chain.proceed()
                return runCatching {
                    if (isActiveHungTask(Reflect.callByName(chain.thisObject, "getTask"))) false else chain.proceed()
                }.getOrElse { chain.proceed() }
            }
        }
        var hooked = 0
        // AOSP 里是 canReceiveKeys() / canReceiveTouchInput()；个别固件给 canReceiveKeys 加了 boolean 参数
        listOf("canReceiveKeys" to 0, "canReceiveKeys" to 1, "canReceiveTouchInput" to 0).forEach { (name, count) ->
            val m = Reflect.findMethod(cls, name, count) ?: return@forEach
            runCatching {
                HookEnv.module.hook(m).intercept(reject)
                hooked++
            }.onFailure { LogUtils.w(TAG, "install", "挂 ${cls.simpleName}#$name/$count 失败：${it.message}") }
        }
        LogUtils.i(TAG, "install", "输入门控已挂 $hooked 处")
        return hooked > 0
    }

    private fun hookAddToStopping(): Boolean {
        val arCls = Reflect.classOrNull(HookEnv.classLoader, CLS_ACTIVITY_RECORD) ?: return false
        val m = Reflect.findMethod(arCls, "addToStopping", 2, mapOf(1 to "String"))
            ?: Reflect.findMethod(arCls, "addToStopping", 3, mapOf(2 to "String"))
            ?: run {
                LogUtils.w(TAG, "install", "ActivityRecord#addToStopping 不存在，贴边挂机不接管")
                return false
            }
        return runCatching {
            HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    // 只拦「贴边成浮窗」这一路，普通退栈照旧
                    if (chain.args.lastOrNull() != "startToFloatAnimation") return chain.proceed()
                    return runCatching {
                        val task = Reflect.callByName(chain.thisObject, "getTask")
                        if (isActiveHungTask(task)) null else chain.proceed()
                    }.getOrElse { chain.proceed() }
                }
            })
            true
        }.getOrElse {
            LogUtils.w(TAG, "install", "挂 addToStopping 失败：${it.message}")
            false
        }
    }

    /** 藏住挂机任务的画面：系统每次重画都会 show 回来，所以只能每次都 hide */
    private fun hookPrepareSurfaces(): Boolean {
        val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_TASK) ?: return false
        val m = Reflect.findMethod(cls, "prepareSurfaces", 0) ?: run {
            LogUtils.w(TAG, "install", "Task#prepareSurfaces() 不存在，贴边挂机不接管")
            return false
        }
        return runCatching {
            HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val task = chain.thisObject ?: return chain.proceed()
                    if (hungIds.isEmpty()) return chain.proceed()
                    val taskId = (Reflect.getField(task, "mTaskId") as? Int) ?: return chain.proceed()
                    if (!hungIds.contains(taskId)) return chain.proceed()

                    val floating = isTaskInFloatingList(taskId)
                    if (!floating || !enabled()) {
                        // 离开浮窗列表（用户点开小窗 / 关闭应用）或开关被关：清标记并按原生退后台
                        releaseHungTask(task)
                        if (floating) Reflect.callByName(task, "moveTaskToBack", task)
                        return chain.proceed()
                    }

                    val r = chain.proceed()
                    runCatching { hideSurface(task, taskId) }
                    return r
                }
            })
            true
        }.getOrElse {
            LogUtils.w(TAG, "install", "挂 prepareSurfaces 失败：${it.message}")
            false
        }
    }

    private fun hideSurface(task: Any, taskId: Int) {
        if (!isTaskInFloatingList(taskId)) {
            // 本轮之后已离开浮窗列表：交给下一轮 prepareSurfaces 的释放分支处理
            return
        }
        val sc = Reflect.callDeep(task, "getSurfaceControl") ?: return
        if (Reflect.callByName(sc, "isValid") != true) return
        val tx = Reflect.callDeep(task, "getSyncTransaction") ?: return
        Reflect.callByName(tx, "hide", sc)
        // 每次重画都要 hide，日志只在首次打一条（避免刷屏）
        if (hiddenLogged.add(taskId)) {
            LogUtils.d(TAG, "prepareSurfaces", "已隐藏挂机任务 $taskId 的画面（之后每轮重画都会再隐藏）")
        }
    }

    /** 已经打过「已隐藏」日志的 taskId，仅用于抑制日志刷屏 */
    private val hiddenLogged: MutableSet<Int> = java.util.Collections.synchronizedSet(HashSet())

    // ============================================================ 拦截 moveTaskToBackForPanorama

    private fun hookMoveTaskToBackForPanorama() {
        val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_TASK_EXT) ?: run {
            LogUtils.w(TAG, "install", "$CLS_TASK_EXT 不存在，贴边挂机不可用")
            return
        }
        val task = Reflect.classOrNull(HookEnv.classLoader, CLS_TASK)
        // 本机是 4 参 (Task, boolean, int, int)；旧固件 3 参 (Task, boolean, int)
        val m = Reflect.findMethodByShape(
            cls, "moveTaskToBackForPanorama",
            listOf(task, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType),
            null,
        ) ?: Reflect.findMethodByShape(
            cls, "moveTaskToBackForPanorama",
            listOf(task, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType),
            null,
        )
        if (m == null) {
            LogUtils.w(TAG, "install", "moveTaskToBackForPanorama 不存在，贴边挂机不可用")
            return
        }
        runCatching {
            HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    if (!coreReady || !enabled()) return chain.proceed()
                    val target = chain.args.getOrNull(0) ?: return chain.proceed()
                    // 只介入「本次贴边成浮窗」这一路
                    if (target !== edgeExitTask.get()) return chain.proceed()

                    return runCatching {
                        val taskId = Reflect.getField(target, "mTaskId") as? Int ?: return@runCatching chain.proceed()
                        // 只介入「贴边成浮窗」：此时 addFloatHandle 已把任务加入浮窗列表
                        if (!isTaskInFloatingList(taskId)) return@runCatching chain.proceed()

                        // 先登记再算焦点，让本次计算就排除隐藏小窗的输入窗口
                        hungIds.add(taskId)
                        hungTasks[taskId] = WeakReference(target)
                        focusTaskBehind(target)
                        if (muteEnabled()) muteFloatTask(taskId, target)

                        LogUtils.i(TAG, "edgeHang", "跳过 moveTaskToBack，taskId=$taskId 继续挂机")
                        null // 不 proceed = 跳过原生退后台
                    }.getOrElse {
                        LogUtils.e(TAG, "edgeHang", "接管失败：${it.javaClass.simpleName}: ${it.message}", true)
                        chain.proceed()
                    }
                }
            })
            LogUtils.i(TAG, "install", "已挂 TaskExtImpl#moveTaskToBackForPanorama")
        }.onFailure {
            LogUtils.w(TAG, "install", "挂 moveTaskToBackForPanorama 失败：${it.message}")
        }
    }

    private fun hookRemoveTask() {
        val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_TASK) ?: return
        // 本机有两个重载：removeImmediately() 与 removeImmediately(String)，
        // 两个都是真的删除入口（前者被 removeIfPossible / 直接销毁走），必须全挂。
        val targets = cls.declaredMethods.filter { it.name == "removeImmediately" }
        if (targets.isEmpty()) {
            LogUtils.w(TAG, "install", "Task#removeImmediately 不存在，挂机任务销毁时不会自动释放")
            return
        }
        var hooked = 0
        targets.forEach { m ->
            runCatching {
                HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val task = chain.thisObject
                        if (task != null && hungIds.isNotEmpty()) {
                            val taskId = Reflect.getField(task, "mTaskId") as? Int
                            if (taskId != null && hungIds.contains(taskId)) releaseHungTask(task)
                        }
                        return chain.proceed()
                    }
                })
                hooked++
            }.onFailure { LogUtils.w(TAG, "install", "挂 removeImmediately/${m.parameterCount} 失败：${it.message}") }
        }
        LogUtils.i(TAG, "install", "removeImmediately 已挂 $hooked/${targets.size} 个重载")
    }

    /** 解锁后 resume 会晚于本回调，延后两次纠正以兜住更慢的时序 */
    private fun hookKeyguardRefocus() {
        val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_FTC) ?: return
        val m = Reflect.findMethod(cls, "notifyKeyguardStateChanged", 3) ?: return
        runCatching {
            HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val r = chain.proceed()
                    if (hungIds.isEmpty()) return r
                    // args = (keyguardChanged, keyguardShowing, displayId)：只处理解锁
                    if (chain.args.getOrNull(0) != true) return r
                    if (chain.args.getOrNull(1) == true) return r
                    if (chain.args.getOrNull(2) != 0) return r
                    mainHandler.postDelayed({ refocusBehindHungTasks("unlock") }, 500L)
                    mainHandler.postDelayed({ refocusBehindHungTasks("unlock-recheck") }, 1500L)
                    return r
                }
            })
        }.onFailure { LogUtils.w(TAG, "install", "挂 notifyKeyguardStateChanged 失败：${it.message}") }
    }

    private fun hookFocusedApp() {
        val dcCls = Reflect.classOrNull(HookEnv.classLoader, CLS_DISPLAY_CONTENT) ?: return
        val arCls = Reflect.classOrNull(HookEnv.classLoader, CLS_ACTIVITY_RECORD) ?: return
        val m = Reflect.findMethodByShape(dcCls, "setFocusedApp", listOf(arCls), null) ?: return
        runCatching {
            HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    if (hungIds.isEmpty() || !enabled()) return chain.proceed()
                    val ar = chain.args.getOrNull(0) ?: return chain.proceed()
                    return runCatching {
                        val task = Reflect.callByName(ar, "getTask") ?: return@runCatching chain.proceed()
                        val id = Reflect.getField(task, "mTaskId") as? Int ?: return@runCatching chain.proceed()
                        if (!hungIds.contains(id)) return@runCatching chain.proceed()
                        if (!isTaskInFloatingList(id)) {
                            // 已还原成浮窗 / 已关闭：焦点归它是对的，交还系统
                            releaseHungTask(task)
                            return@runCatching chain.proceed()
                        }
                        val behind = findBehindActivity(task) ?: return@runCatching chain.proceed()
                        LogUtils.i(TAG, "setFocusedApp", "把焦点从挂机任务 $id 交给下面窗口")
                        chain.proceed(arrayOf(behind))
                    }.getOrElse { chain.proceed() }
                }
            })
        }.onFailure { LogUtils.w(TAG, "install", "挂 setFocusedApp 失败：${it.message}") }
    }

    // ============================================================ 焦点 / 释放

    private fun isActiveHungTask(task: Any?): Boolean {
        if (task == null || hungIds.isEmpty() || !enabled()) return false
        val id = (Reflect.getField(task, "mTaskId") as? Int) ?: return false
        val ref = hungTasks[id] ?: return false
        return hungIds.contains(id) && ref.get() === task && isTaskInFloatingList(id)
    }

    private fun releaseHungTask(task: Any?) {
        if (task == null) return
        val id = (Reflect.getField(task, "mTaskId") as? Int) ?: return
        val wasHung = hungIds.remove(id)
        hungTasks.remove(id)
        hiddenLogged.remove(id)
        releaseMuteAsync(id)
        // ⚠️ AOSP 的 `Task#mLastSurfaceShowing` 在本机 ColorOS 的 services.jar 里**不存在**
        //    （fields 表里全库搜 `SurfaceShowing` 为 0 行），所以不能靠「把记账改回 false
        //    让框架下一轮自己 show」——挂机期间被我们 hide 的画面会一直是黑的。必须显式 show。
        if (wasHung) showSurface(task)
    }

    /** 把挂机期间隐藏的任务画面放出来 */
    private fun showSurface(task: Any) {
        runCatching {
            val sc = Reflect.callDeep(task, "getSurfaceControl") ?: return
            if (Reflect.callByName(sc, "isValid") != true) return
            val tx = Reflect.callDeep(task, "getSyncTransaction") ?: return
            Reflect.callByName(tx, "show", sc)
        }.onFailure { LogUtils.w(TAG, "showSurface", "恢复画面失败：${it.message}") }
    }

    private fun refocusBehindHungTasks(reason: String) {
        if (hungIds.isEmpty()) return
        val snapshot = synchronized(hungIds) { ArrayList(hungIds) }
        for (id in snapshot) {
            val task = hungTasks[id]?.get()
            if (task == null) {
                hungIds.remove(id)
                hungTasks.remove(id)
                releaseMuteAsync(id)
                continue
            }
            runCatching {
                if (!isActiveHungTask(task)) return@runCatching
                if (!isFocusedTask(task)) return@runCatching
                val wm = Reflect.getField(task, "mWmService")
                val lock = wm?.let { Reflect.getField(it, "mGlobalLock") }
                if (lock != null) {
                    synchronized(lock) { focusTaskBehind(task) }
                } else {
                    focusTaskBehind(task)
                }
                LogUtils.i(TAG, "refocus", "解锁后把焦点移回下面窗口（$reason），taskId=$id")
            }
        }
    }

    /** Task 没有可靠的 isFocused()，用 DisplayContent.mFocusedApp 判定 */
    private fun isFocusedTask(task: Any): Boolean = runCatching {
        val dc = Reflect.getField(task, "mDisplayContent") ?: return@runCatching false
        val focusedApp = Reflect.getField(dc, "mFocusedApp") ?: return@runCatching false
        Reflect.callByName(focusedApp, "getTask") === task
    }.getOrElse { false }

    private fun focusTaskBehind(floatTask: Any) {
        runCatching {
            val behind = findBehindActivity(floatTask) ?: return
            val dc = Reflect.getField(floatTask, "mDisplayContent") ?: return
            Reflect.callByName(dc, "setFocusedApp", behind)
            val wm = Reflect.getField(dc, "mWmService") ?: return
            Reflect.callByName(wm, "updateFocusedWindowLocked", 0, true)
        }.onFailure { LogUtils.w(TAG, "focusTaskBehind", "失败：${it.message}") }
    }

    /** 小窗下方那个可聚焦任务（原生 `getTaskUnderFlexible`，排除小窗与画中画）的顶层 Activity */
    private fun findBehindActivity(floatTask: Any?): Any? {
        if (floatTask == null) return null
        return runCatching {
            val ftc = flexibleTaskController() ?: return@runCatching null
            val behind = Reflect.callByName(ftc, "getTaskUnderFlexible", floatTask) ?: return@runCatching null
            if (behind === floatTask || isActiveHungTask(behind)) return@runCatching null
            if (Reflect.callDeep(behind, "isTopActivityFocusable") != true) return@runCatching null
            if (Reflect.callDeep(behind, "isVisible") != true) return@runCatching null
            Reflect.callDeep(behind, "getTopNonFinishingActivity")
        }.getOrElse { null }
    }

    /** FlexibleWindowManagerService 是单例，FlexibleTaskController 由它持有（attach 之后才非空） */
    private fun flexibleTaskController(): Any? {
        flexibleTaskController?.let { return it }
        val cls = fwmsCls ?: return null
        val fwms = Reflect.callStatic(cls, "getInstance", *arrayOfNulls<Any>(1)) ?: return null
        val ftc = Reflect.callByName(fwms, "getFlexibleTaskController") ?: return null
        flexibleTaskController = ftc
        return ftc
    }

    private fun isTaskInFloatingList(taskId: Int): Boolean {
        val cls = floatHandleControllerCls ?: return true
        return runCatching {
            val fhc = Reflect.callStatic(cls, "getInstance") ?: return@runCatching true
            Reflect.callByName(fhc, "isInFloatingList", taskId) == true
        }.getOrElse { true }
    }

    // ============================================================ 白色竖条

    private fun hookWhiteBar() {
        val viewCls = Reflect.classOrNull(HookEnv.classLoader, CLS_FLOAT_HANDLE_VIEW)
        val infoCls = Reflect.classOrNull(HookEnv.classLoader, CLS_FLOAT_HANDLE_INFO)
        if (viewCls == null || infoCls == null) {
            LogUtils.w(TAG, "install", "FloatHandleView / FloatHandleInfo 不存在，跳过白色竖条")
            return
        }
        var hooked = 0

        // 1) 图标刷新时重设样式
        Reflect.findMethodByShape(
            viewCls, "updateIconStyle",
            listOf(Int::class.javaPrimitiveType, infoCls), null,
        )?.let { m ->
            hooked += hookSafely(m, "updateIconStyle") { chain ->
                val r = chain.proceed()
                if (whiteBarEnabled()) runCatching { applyWhiteBarStyle(chain.thisObject) }
                r
            }
        }

        // 2) 布局参数应用点：框架每次都用整条把手宽度覆盖，必须前置拦
        Reflect.findMethodByShape(
            viewCls, "updateViewLayoutParams",
            listOf(Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType), null,
        )?.let { m ->
            hooked += hookSafely(m, "updateViewLayoutParams") { chain ->
                if (whiteBarEnabled()) {
                    runCatching {
                        val lp = Reflect.getField(chain.thisObject, "mViewContainerLayoutParams")
                        if (lp is ViewGroup.LayoutParams) lp.width = barWidthPx(chain.thisObject)
                    }
                }
                chain.proceed()
            }
        }

        // 3) 位置收口：吸附后把把手摆到距右边缘 8dp
        Reflect.findMethodByShape(
            viewCls, "updateCurrentPosition",
            listOf(Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Int::class.javaPrimitiveType), null,
        )?.let { m ->
            hooked += hookSafely(m, "updateCurrentPosition") { chain ->
                val r = chain.proceed()
                if (whiteBarEnabled()) runCatching {
                    val container = Reflect.getField(chain.thisObject, "mFloatHandleViewContainer") as? View
                    if (container != null) {
                        applyWhiteBarPosition(chain.thisObject, container)
                        container.alpha = 1.0f
                    }
                }
                r
            }
        }

        // 4) 位置计算结果：原生是「探出屏幕」的坐标
        Reflect.findMethodByShape(
            viewCls, "computeCurrentPosition",
            listOf(Int::class.javaPrimitiveType), null,
        )?.let { m ->
            hooked += hookSafely(m, "computeCurrentPosition") { chain ->
                val r = chain.proceed()
                if (whiteBarEnabled()) runCatching {
                    val point = r ?: return@runCatching
                    val controller = Reflect.getField(chain.thisObject, "mFloatHandleController")
                    val screenW = (controller?.let { Reflect.callByName(it, "getScreenWidth") } as? Number)?.toInt()
                    if (screenW != null) {
                        val x = screenW - edgePx(chain.thisObject) - barWidthPx(chain.thisObject)
                        Reflect.setField(point, "x", x)
                    }
                }
                r
            }
        }

        // 5) 吸附/展开回小窗：套样式并保持不透明
        var switchHooked = 0
        viewCls.declaredMethods
            .filter { it.name == "switchToFullMode" && it.parameterTypes.firstOrNull() == infoCls }
            .forEach { m ->
                switchHooked += hookSafely(m, "switchToFullMode(${m.parameterCount})") { chain ->
                    if (whiteBarEnabled()) runCatching { applyWhiteBarStyle(chain.thisObject) }
                    val r = chain.proceed()
                    if (whiteBarEnabled()) runCatching {
                        val container = Reflect.getField(chain.thisObject, "mFloatHandleViewContainer") as? View
                        if (container != null) {
                            applyWhiteBarPosition(chain.thisObject, container)
                            container.alpha = 1.0f
                        }
                    }
                    r
                }
            }
        hooked += switchHooked

        // 6) 兜底：图标列表被重新显示时逐个隐藏
        val adapterCls = Reflect.classOrNull(HookEnv.classLoader, CLS_ICON_ADAPTER)
        val holderCls = Reflect.classOrNull(HookEnv.classLoader, CLS_ICON_HOLDER)
        if (adapterCls != null && holderCls != null) {
            Reflect.findMethodByShape(adapterCls, "onBindViewHolder", listOf(holderCls, Int::class.javaPrimitiveType), null)
                ?.let { m ->
                    hooked += hookSafely(m, "onBindViewHolder") { chain ->
                        val r = chain.proceed()
                        if (whiteBarEnabled()) runCatching {
                            val iv = Reflect.getField(chain.args.getOrNull(0), "mImageView")
                            if (iv is View) iv.visibility = View.GONE
                        }
                        r
                    }
                }
        }

        // 7) 跳过图标展开/堆叠动画
        val iconListCls = Reflect.classOrNull(HookEnv.classLoader, CLS_ICON_LIST)
        if (iconListCls != null) {
            listOf("animateToExpand", "animateToStacking").forEach { name ->
                Reflect.findMethod(iconListCls, name, 0)?.let { m ->
                    hooked += hookSafely(m, name) { chain ->
                        if (whiteBarEnabled()) null else chain.proceed()
                    }
                }
            }
        }

        LogUtils.i(TAG, "install", "白色竖条已挂 $hooked 处")
    }

    private inline fun hookSafely(
        m: java.lang.reflect.Method,
        label: String,
        crossinline body: (XposedInterface.Chain) -> Any?,
    ): Int = runCatching {
        HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? = body(chain)
        })
        1
    }.getOrElse {
        LogUtils.w(TAG, "install", "挂 $label 失败：${it.javaClass.simpleName}: ${it.message}")
        0
    }

    private fun applyWhiteBarStyle(view: Any?) {
        val container = Reflect.getField(view, "mFloatHandleViewContainer") as? View ?: return
        val barW = barWidthPx(view)

        // 白色圆角竖条（两端全圆 = 胶囊形）
        val bg = GradientDrawable()
        bg.setColor(Color.WHITE)
        bg.cornerRadius = barW / 2.0f
        container.background = bg
        container.alpha = 1.0f

        // 隐藏图标列表
        val list = Reflect.getField(view, "mFloatIconListAnimationLayout")
        if (list is View) list.visibility = View.GONE

        // 容器宽度压成细竖条
        val lp = container.layoutParams
        if (lp != null) {
            lp.width = barW
            container.layoutParams = lp
        }
        applyWhiteBarPosition(view, container)
    }

    /** 固定在距屏幕右边缘 8dp（左右停靠都贴右边缘，对齐上游） */
    private fun applyWhiteBarPosition(view: Any?, container: View) {
        runCatching {
            val side = Reflect.getField(view, "mCurrentSide") as? Int ?: return
            if (side != 0 && side != 1) return
            val controller = Reflect.getField(view, "mFloatHandleController") ?: return
            val screenWidth = (Reflect.callByName(controller, "getScreenWidth") as? Number)?.toInt() ?: return
            container.translationX = (screenWidth - edgePx(view) - barWidthPx(view)).toFloat()
        }
    }

    private fun density(view: Any?): Float = runCatching {
        (view as? View)?.resources?.displayMetrics?.density
    }.getOrNull()?.takeIf { it > 0f }
        ?: android.content.res.Resources.getSystem().displayMetrics.density

    private fun barWidthPx(view: Any?): Int = Math.round(BAR_WIDTH_DP * density(view))

    private fun edgePx(view: Any?): Int = Math.round(BAR_EDGE_DP * density(view))

    // ============================================================ 静音

    private val muteLock = Any()

    @Volatile
    private var pamExt: Any? = null

    private var mutedTaskId = -1
    private var mutedUid = -1
    private var mutedPrevGain = 1.0f
    private var pendingTaskId = -1
    private var pendingUid = -1
    private var muteGeneration = 0L

    private fun hookMute() {
        hookPamExtCapture()
        hookFocusedTaskForRestore()
    }

    /** `PlaybackActivityMonitorExtImpl` 没有静态实例入口，只能在构造时捕获（其构造早于任何播放） */
    private fun hookPamExtCapture() {
        val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_PAM_EXT) ?: run {
            LogUtils.w(TAG, "install", "$CLS_PAM_EXT 不存在，贴边挂机静音不可用")
            return
        }
        val ctor = Reflect.findConstructorByShape(cls, listOf(Any::class.java)) ?: run {
            LogUtils.w(TAG, "install", "$CLS_PAM_EXT 构造器 (Object) 不存在，静音不可用")
            return
        }
        runCatching {
            HookEnv.module.hook(ctor).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val r = chain.proceed()
                    // 构造期 thisObject 可能还没就绪，退回返回值（就是新实例）
                    pamExt = chain.thisObject ?: r
                    LogUtils.i(TAG, "install", "已捕获 PlaybackActivityMonitorExtImpl，静音可用")
                    return r
                }
            })
        }.onFailure { LogUtils.w(TAG, "install", "挂 PAM 构造器失败：${it.message}") }
    }

    /** 焦点中枢：`setFocusedTask(int)` 内部也转调这个两参版本，应用回前台必过此 */
    private fun hookFocusedTaskForRestore() {
        val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_ATMS) ?: return
        val arCls = Reflect.classOrNull(HookEnv.classLoader, CLS_ACTIVITY_RECORD) ?: return
        val m = Reflect.findMethodByShape(cls, "setFocusedTask", listOf(Int::class.javaPrimitiveType, arCls), null) ?: run {
            LogUtils.w(TAG, "install", "ActivityTaskManagerService#setFocusedTask(int,ActivityRecord) 不存在，静音恢复退化到离开浮窗列表时")
            return
        }
        runCatching {
            HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val r = chain.proceed()
                    if (muteEnabled()) mainHandler.post { restoreMuteIfNeeded() }
                    return r
                }
            })
        }.onFailure { LogUtils.w(TAG, "install", "挂 setFocusedTask 失败：${it.message}") }
    }

    /** 贴边成浮窗时静音：先同步登记 pending，再把音量调用抛到主线程，封住竞态窗口 */
    private fun muteFloatTask(taskId: Int, task: Any) {
        val uid = taskUid(task)
        if (uid <= 0) return
        val generation: Long
        synchronized(muteLock) {
            generation = ++muteGeneration
            pendingTaskId = taskId
            pendingUid = uid
        }
        val posted = mainHandler.post {
            synchronized(muteLock) {
                if (generation != muteGeneration || pendingTaskId != taskId || pendingUid != uid) return@post
                pendingTaskId = -1
                pendingUid = -1
                // 排队期间任务可能已经离开浮窗列表
                if (!isTaskInFloatingList(taskId)) return@post
                if (pamExt == null) return@post
                // 同一应用重复贴边：已静音，保留最初记录的原始音量（别把 0 记成原值）
                if (mutedUid == uid) {
                    mutedTaskId = taskId
                    return@post
                }
                if (mutedUid >= 0) applyAppVolume(mutedUid, mutedPrevGain)
                val prev = applyAppVolume(uid, 0.0f)
                mutedTaskId = taskId
                mutedUid = uid
                mutedPrevGain = prev
            }
        }
        if (!posted) {
            synchronized(muteLock) { if (generation == muteGeneration) cancelPendingLocked() }
            LogUtils.w(TAG, "mute", "静音任务投递失败")
        }
    }

    private fun releaseMuteAsync(taskId: Int) {
        synchronized(muteLock) { if (pendingTaskId == taskId) cancelPendingLocked() }
        mainHandler.post { restoreMuteIfNeeded() }
    }

    private fun restoreMuteIfNeeded() {
        synchronized(muteLock) {
            if (pendingTaskId >= 0 && (!hungIds.contains(pendingTaskId) || !isTaskInFloatingList(pendingTaskId))) {
                cancelPendingLocked()
            }
            if (mutedUid >= 0 && (!hungIds.contains(mutedTaskId) || !isTaskInFloatingList(mutedTaskId))) {
                val uid = mutedUid
                val gain = mutedPrevGain
                mutedTaskId = -1
                mutedUid = -1
                mutedPrevGain = 1.0f
                applyAppVolume(uid, gain)
            }
        }
    }

    private fun cancelPendingLocked() {
        muteGeneration++
        pendingTaskId = -1
        pendingUid = -1
    }

    /** 经系统多应用音量通道设置某 uid 的应用音量，返回设置前的音量（不在表中即 1.0） */
    @Suppress("UNCHECKED_CAST")
    private fun applyAppVolume(uid: Int, gain: Float): Float {
        val ext = pamExt
        if (ext == null || uid <= 0) return 1.0f
        return runCatching {
            val pkg = packageNameForUid(ext, uid) ?: return@runCatching 1.0f
            var prev = 1.0f
            val map = Reflect.getField(ext, "mMusicVolumeMap") as? Map<String, Float>
            if (map != null) {
                synchronized(map) { map[pkg]?.let { prev = it } }
            }
            Reflect.callByName(ext, "setVolumeForUid", gain, uid, pkg, true)
            LogUtils.i(TAG, "mute", "$pkg 音量 $prev → $gain（uid=$uid）")
            prev
        }.getOrElse {
            LogUtils.w(TAG, "mute", "设置音量失败：${it.javaClass.simpleName}: ${it.message}")
            1.0f
        }
    }

    private fun packageNameForUid(ext: Any, uid: Int): String? {
        // 1) 直接走 AppGlobals 的 IPackageManager，不依赖任何 Context
        runCatching {
            val globals = Reflect.classOrNull(HookEnv.classLoader, "android.app.AppGlobals")
            val ipm = Reflect.callStatic(globals, "getPackageManager")
            val name = Reflect.callByName(ipm, "getNameForUid", uid) as? String
            if (!name.isNullOrEmpty()) return name
        }
        // 2) ExtImpl.mContext
        runCatching {
            val ctx = Reflect.getField(ext, "mContext") as? Context
            val name = ctx?.packageManager?.getNameForUid(uid)
            if (!name.isNullOrEmpty()) return name
        }
        return null
    }

    private fun taskUid(task: Any): Int {
        (Reflect.getField(task, "effectiveUid") as? Int)?.let { if (it > 0) return it }
        return (Reflect.getField(task, "mCallingUid") as? Int) ?: -1
    }

    // ------------------------------------------------------------ 取值

    private fun enabled(): Boolean = HookEnv.settingBoolean(FloatWindowConfig.KEY_EDGE_HANG, false)

    private fun muteEnabled(): Boolean = HookEnv.settingBoolean(FloatWindowConfig.KEY_EDGE_HANG_MUTE, false)

    private fun whiteBarEnabled(): Boolean =
        HookEnv.settingBoolean(FloatWindowConfig.KEY_EDGE_HANG_WHITE_BAR, false)
}
