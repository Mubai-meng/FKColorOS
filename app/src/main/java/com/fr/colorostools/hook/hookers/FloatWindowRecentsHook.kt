package com.fr.colorostools.hook.hookers

import android.app.TaskInfo
import android.os.Handler
import android.os.Looper
import com.fr.colorostools.hook.HookEnv
import com.fr.colorostools.ref.Reflect
import com.fr.colorostools.utils.LogUtils
import io.github.libxposed.api.XposedInterface

/**
 * 功能：**多任务隐藏小窗应用**（宿主 = 系统桌面）+ **关闭小窗时从多任务隐藏**（宿主 = system_server）。
 *
 * 移植来源：coloros-mod（MIT）`hooks/RecentsHideFreeformHooks` + `hooks/RecentFreeformHistoryHooks`。
 *
 * ## 两个功能的进程归属不同（这点决定作用域怎么勾）
 *
 * | 功能 | 进程 | 原因 |
 * |---|---|---|
 * | 多任务隐藏小窗应用 | **系统桌面** | 多任务卡片列表由 Launcher 侧的 `OplusRecentTasksFilter` 过滤 |
 * | 关闭小窗时从多任务隐藏 | **system_server** | 「哪个任务被关闭了」只有 WM 知道，且 `RecentTasks` 在系统进程 |
 *
 * 所以设置页里「多任务隐藏小窗应用」下面必须提示**勾选系统桌面**。
 *
 * ## 一、多任务隐藏小窗应用（launcher）
 *
 * 原生过滤入口（本机 `系统桌面` 实证，混淆名随版本漂移，见 `CLS_RECENT_FILTERS`）：
 * 17.5.4 = `jn7#b(int, int, GroupedTaskInfo, ArrayList)Z`、17.3.12 = `ko.a#d(...)`，
 * **返回 true = 该卡片被移除**。它是 `synchronized` 方法，前置返回 true 即可跳过原生判定。
 *
 * 两个约束：
 * 1. **成对卡片（分屏/混合）不动**：`getTaskInfo2()` 非空说明这组是两张卡，原生会在那里
 *    走「回收站过滤」的早退分支，我们插手会把正常分屏也吞掉；
 * 2. 「是否小窗」用 OEM 自己的判据 `FlexibleWindowManager#getFlexibleWindowState(TaskInfo) == 1`
 *    （本机 `oplus-framework.jar` 实证）。**不能**只看 `windowingMode == 5`：ColorOS 的
 *    弹性小窗并不总是 FREEFORM 模式，只看窗口模式会漏掉一大半。
 *
 * ## 二、关闭小窗时从多任务隐藏（system_server）
 *
 * 分两步：
 *
 * 1. **记录**：`FlexibleTaskController#exitFlexibleTask(..., flexibleCustomTransition, ...)`
 *    的 `args[2] == 0` 表示「退到后台」，此时若任务本身是小窗（`getLaunchScenario()==1 &&
 *    !isTaskEmbedded() && isHasCaption()`）就把 `userId:taskId` 记进 [closedKeys]；
 * 2. **过滤**：`RecentTasks#getRecentTasksImpl(IIZII)ArrayList` 的返回列表里剔除这些任务。
 *
 * 重新打开（`Task#moveToFront` / `Task#onAppFocusChanged(true)` / TaskFragment RESUMED /
 * `RecentTasks#remove(Task)`）时清记录，所以「关掉→再从桌面点开」会正常回到多任务列表。
 *
 * ## 三、数据通路（`services.jar` 反汇编实证，别再猜）
 *
 * ColorOS 的多任务列表**确实**经过本 Hook 拦截的这个方法，链路是：
 *
 * ```
 * 系统桌面  com.android.wm.shell.recents.IRecentTasks#getRecentTasks        (桌面进程)
 *   ↓ binder
 * 系统界面  RecentTasksController#getRecentTasks(III)                       (SystemUI 进程)
 *            └─ d1 行 5 条指令：ActivityTaskManager.getRecentTasks(III)
 *   ↓ binder
 * 系统      ActivityTaskManagerService#getRecentTasks → RecentTasks#getRecentTasks(IIZII)
 *            └─ 只有 5 条指令：new ParceledListSlice(getRecentTasksImpl(...))
 *            └─ RecentTasks#getRecentTasksImpl(IIZII)  ← 本 Hook（332 条指令，不会被内联）
 * ```
 *
 * 所以 ① 桌面侧不做这件事、② 不要在 `com.android.wm.shell.recents.RecentTasks` 上找钩子
 * （那是 Shell 自己的存储类，只有 `getRecentTasks` / `addAnimationStateListener` 三个方法）。
 *
 * ⚠️ 隐藏名单必须与 `RecentTasks#getRawTasks()` 交叉校验：taskId 会被系统回收复用，
 * 只按键匹配会把**后来新建的无关任务**一起吞掉。
 */
internal object FloatWindowRecentsHook {

    private const val TAG = "FloatWindowRecents"

    // ---------------------------------------------------------- launcher 侧
    /**
     * 多任务过滤类候选（混淆名随 OTA 漂移，按新到旧排列）：
     * - 17.5.4 = **`jn7`**（日志 tag `OplusRecentTasksFilter`）：方法
     *   `b(int,int,GroupedTaskInfo,ArrayList)Z`（484 opcode，synchronized），返回 true = 移除卡片；
     *   调用方 `com.android.quickstep.e#i`（多任务列表装载）与 `pn7#i`。
     * - 17.3.12 = `ko.a#d(...)`；更早版本用语义名 `OplusRecentTasksFilter`。
     */
    private val CLS_RECENT_FILTERS = listOf(
        "jn7",
        "ko.a",
        "com.oplus.quickstep.data.OplusRecentTasksFilter",
    )
    private const val CLS_GROUPED_TASK = "com.android.wm.shell.shared.GroupedTaskInfo"
    private const val CLS_GROUPED_TASK_ALT = "com.android.wm.shell.util.GroupedRecentTaskInfo"
    private const val CLS_FLEXIBLE_WM = "com.oplus.flexiblewindow.FlexibleWindowManager"

    // ---------------------------------------------------------- system_server 侧
    private const val CLS_FTC = "com.android.server.wm.FlexibleTaskController"
    private const val CLS_TASK = "com.android.server.wm.Task"
    private const val CLS_RECENT_TASKS = "com.android.server.wm.RecentTasks"
    private const val CLS_TASK_FRAGMENT = "com.android.server.wm.TaskFragment"

    /** `exitFlexibleTask` 的 flexibleCustomTransition = 0 ⇒ 退到后台（关闭小窗） */
    private const val EXIT_TO_BACK = 0

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    // ============================================================ 一、多任务隐藏小窗（launcher）

    fun installInLauncher() {
        val filterCls = CLS_RECENT_FILTERS.firstNotNullOfOrNull {
            Reflect.classOrNull(HookEnv.classLoader, it)
        }
        val groupedCls = Reflect.classOrNull(HookEnv.classLoader, CLS_GROUPED_TASK)
            ?: Reflect.classOrNull(HookEnv.classLoader, CLS_GROUPED_TASK_ALT)
        if (filterCls == null || groupedCls == null) {
            LogUtils.w(
                TAG, "installLauncher",
                "过滤类或 GroupedTaskInfo 不存在（${CLS_RECENT_FILTERS.joinToString()} / $CLS_GROUPED_TASK），跳过多任务隐藏小窗",
            )
            return
        }
        // 17.5.4 的 GroupedTaskInfo 没有语义名 getter，混淆名也从 e/f 变成了 c/d：
        // c() = 第一张卡、d() = 第二张卡（d() != null 即成对卡片）。名字找不到时
        // 退化按「0 参返回 TaskInfo 的方法按字典序」兜底。
        val first = taskGetter(groupedCls, "getTaskInfo1", "e")
            ?: shapeGetter(groupedCls, 0)
        val second = taskGetter(groupedCls, "getTaskInfo2", "f")
            ?: shapeGetter(groupedCls, 1)
        if (first == null) {
            LogUtils.w(TAG, "installLauncher", "GroupedTaskInfo 的取任务方法找不到，跳过多任务隐藏小窗")
            return
        }
        // 原生签名：(int, int, GroupedTaskInfo, ArrayList)Z，返回 true = 过滤掉这张卡。
        // 方法名随 OTA 漂移：17.3.12=`ko.a#d`、17.5.4=`jn7#b`（日志实证：只试 d/filterTaskInfo
        // 会静默跳过）；名字全 miss 时按「唯一匹配该形状的方法」兜底（不看名字）。
        val filterShape = listOf(
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            groupedCls, ArrayList::class.java,
        )
        val m = arrayOf("d", "b", "filterTaskInfo").firstNotNullOfOrNull { name ->
            Reflect.findMethodByShape(filterCls, name, filterShape, Boolean::class.javaPrimitiveType)
        } ?: filterCls.declaredMethods.firstOrNull {
            it.parameterTypes.contentEquals(filterShape.toTypedArray()) &&
                it.returnType == Boolean::class.javaPrimitiveType
        }?.apply { isAccessible = true } ?: run {
            LogUtils.w(TAG, "installLauncher", "${filterCls.simpleName} 里没有 (int,int,GroupedTaskInfo,ArrayList)Z，跳过")
            return
        }

        runCatching {
            HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    if (!recentsHideEnabled()) return chain.proceed()
                    return runCatching {
                        val group = chain.args.getOrNull(2) ?: return@runCatching chain.proceed()
                        // 成对卡片（分屏/混合）保持原生行为
                        if (second != null && second.invoke(group) != null) return@runCatching chain.proceed()
                        val task = first.invoke(group) ?: return@runCatching chain.proceed()
                        if (!isFlexibleFloating(task)) return@runCatching chain.proceed()
                        true // 不 proceed = 直接过滤掉
                    }.getOrElse {
                        LogUtils.w(TAG, "filter", "过滤小窗卡片失败：${it.javaClass.simpleName}: ${it.message}")
                        chain.proceed()
                    }
                }
            })
            LogUtils.i(TAG, "installLauncher", "已挂 ${filterCls.simpleName}#${m.name}（多任务隐藏小窗）")
        }.onFailure {
            LogUtils.w(TAG, "installLauncher", "挂 ${filterCls.simpleName}#${m.name} 失败：${it.message}")
        }
    }

    /** 取任务方法：优先语义名，退化为本机混淆名（旧 17.3.12=`e`/`f`，17.5.4=`c`/`d`） */
    private fun taskGetter(grouped: Class<*>, primary: String, fallback: String): java.lang.reflect.Method? {
        for (name in arrayOf(primary, fallback)) {
            val m = grouped.declaredMethods.firstOrNull {
                it.name == name && it.parameterCount == 0 && TaskInfo::class.java.isAssignableFrom(it.returnType)
            } ?: continue
            runCatching { m.isAccessible = true }
            return m
        }
        return null
    }

    /**
     * 兜底取任务方法：该类里所有「0 参返回 TaskInfo」的方法按**名字字典序**排序后取第 [idx] 个。
     *
     * 17.5.4 `GroupedTaskInfo` 实证：0 参返回 TaskInfo 的只有 `c()`（第一张）与 `d()`（第二张），
     * 字典序 c < d 正好与前后顺序一致；语义名/旧混淆名命中时不会走到这里。
     */
    private fun shapeGetter(grouped: Class<*>, idx: Int): java.lang.reflect.Method? {
        val list = grouped.declaredMethods.filter {
            it.parameterCount == 0 && TaskInfo::class.java.isAssignableFrom(it.returnType)
        }.sortedBy { it.name }
        return list.getOrNull(idx)?.apply { runCatching { isAccessible = true } }
    }

    /**
     * 是否弹性小窗。
     *
     * 首选 OEM 判据（`getFlexibleWindowState(TaskInfo) == 1`，类在 `oplus-framework.jar`，
     * 任何进程都能解析）；取不到才退回「窗口模式 == FREEFORM(5)」。
     *
     * ⚠️ 该函数在多任务过滤热路径上（每次 recents 数据刷新 × 每张任务卡调用一次）：
     * `getInstance` 工厂与 `findMethodByShape` 的结果按进程缓存（单例 + 方法引用不变），
     * 命中后每次调用只剩一次反射 invoke。解析失败不缓存，下次重试（类可能晚加载）。
     */
    @Volatile
    private var flexibleWmInstance: Any? = null

    @Volatile
    private var flexibleWmMethod: java.lang.reflect.Method? = null

    private fun isFlexibleFloating(taskInfo: Any?): Boolean {
        if (taskInfo == null) return false
        val byOem = runCatching {
            val m = flexibleWmMethod ?: resolveFlexibleWm() ?: return@runCatching null
            (m.invoke(flexibleWmInstance, taskInfo) as? Int)
        }.getOrNull()
        if (byOem != null) return byOem == 1

        val mode = Reflect.getField(taskInfo, "windowingMode") as? Int
        if (mode != null) return mode == WINDOWING_MODE_FREEFORM
        // 最后一档：读 configuration 里的 windowConfiguration
        return runCatching {
            val cfg = Reflect.callByName(taskInfo, "getConfiguration") ?: return@runCatching false
            val wc = Reflect.callByName(cfg, "getWindowConfiguration") ?: return@runCatching false
            (Reflect.callByName(wc, "getWindowingMode") as? Int) == WINDOWING_MODE_FREEFORM
        }.getOrDefault(false)
    }

    /** 解析 OEM 判据的单例 + 方法（成功才缓存；失败返回 null，下次重试） */
    private fun resolveFlexibleWm(): java.lang.reflect.Method? {
        val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_FLEXIBLE_WM) ?: return null
        val instance = Reflect.callStatic(cls, "getInstance") ?: return null
        val m = Reflect.findMethodByShape(
            cls, "getFlexibleWindowState",
            listOf(TaskInfo::class.java), Int::class.javaPrimitiveType,
        ) ?: return null
        m.isAccessible = true
        flexibleWmInstance = instance
        flexibleWmMethod = m
        return m
    }

    private const val WINDOWING_MODE_FREEFORM = 5

    // ============================================================ 二、关闭小窗后从多任务隐藏（system）

    private val closedLock = Any()

    /** 已关闭、需要从多任务里隐藏的任务（`userId:taskId`） */
    private val closedKeys = HashSet<String>()

    @Volatile
    private var lastEnabled = false

    @Volatile
    private var taskNotificationController: Any? = null

    fun installInSystemServer() {
        hookExitToRecord()
        hookRecentTasksFilter()
        hookRecentTasksRemove()
        hookReopenClear()
        LogUtils.i(TAG, "installSystem", "关闭小窗后从多任务隐藏已装")
    }

    private fun hookExitToRecord() {
        val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_FTC) ?: return
        val taskCls = Reflect.classOrNull(HookEnv.classLoader, CLS_TASK) ?: return
        val m = Reflect.findMethodByShape(
            cls, "exitFlexibleTask",
            listOf(
                taskCls, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            ),
            null,
        ) ?: run {
            LogUtils.w(TAG, "installSystem", "exitFlexibleTask 7 参签名不存在，关闭小窗记录不可用")
            return
        }
        runCatching {
            HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val task = chain.args.getOrNull(0)
                    val exitTo = chain.args.getOrNull(2) as? Int
                    // 只处理「退到后台」这一路；全屏/分屏退出不算关闭
                    if (task != null && exitTo == EXIT_TO_BACK && closedEnabled() && isFloatingTask(task)) {
                        recordClosed(task)
                    }
                    val r = chain.proceed()
                    if (task != null && (exitTo != EXIT_TO_BACK || !closedEnabled())) clearClosed(task)
                    return r
                }
            })
            LogUtils.i(TAG, "installSystem", "已挂 FlexibleTaskController#exitFlexibleTask（记录关闭态）")
        }.onFailure { LogUtils.w(TAG, "installSystem", "挂 exitFlexibleTask 失败：${it.message}") }
    }

    /**
     * 过滤多任务返回列表。
     *
     * 用「所有同名重载」而不是单一签名：`getRecentTasksImpl` 的参数个数随固件漂移，
     * 只认一种签名迟早会失效。
     */
    private fun hookRecentTasksFilter() {
        val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_RECENT_TASKS) ?: run {
            LogUtils.w(TAG, "installSystem", "$CLS_RECENT_TASKS 不存在，关闭小窗隐藏不生效")
            return
        }
        val targets = cls.declaredMethods.filter { it.name == "getRecentTasksImpl" }
        if (targets.isEmpty()) {
            LogUtils.w(TAG, "installSystem", "RecentTasks#getRecentTasksImpl 不存在，关闭小窗隐藏不生效")
            return
        }
        var hooked = 0
        targets.forEach { m ->
            runCatching {
                HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val r = chain.proceed()
                        if (!closedEnabled()) {
                            clearAll()
                            return r
                        }
                        taskNotificationController =
                            taskNotificationController ?: Reflect.getField(chain.thisObject, "mTaskNotificationController")
                        val list = r as? List<*> ?: return r
                        val hidden = hiddenKeysNow(chain.thisObject)
                        if (hidden.isEmpty()) return r
                        val kept = ArrayList<Any?>(list.size)
                        var removed = 0
                        for (info in list) {
                            val key = keyOf(info)
                            if (key != null && key in hidden) removed++ else kept.add(info)
                        }
                        if (removed == 0) return r
                        LogUtils.d(TAG, "getRecentTasksImpl", "从多任务剔除已关闭小窗 $removed 项")
                        return kept
                    }
                })
                hooked++
            }.onFailure { LogUtils.w(TAG, "installSystem", "挂 getRecentTasksImpl/${m.parameterCount} 失败：${it.message}") }
        }
        LogUtils.i(TAG, "installSystem", "多任务过滤已挂 $hooked/${targets.size} 个 getRecentTasksImpl 重载")
    }

    /**
     * 当前真正需要隐藏的键集合 = 已关闭记录 ∩ `RecentTasks#getRawTasks()`。
     *
     * 为什么要交叉校验：`taskId` 由系统回收复用，如果只按键匹配，一个早就消失的任务
     * 留下的记录会把**后来新建的、完全无关的任务**一起从多任务里吞掉。以 raw tasks 为准
     * 还顺便把失效记录清干净（任务都不在 RecentTasks 里了，就不可能出现在返回列表里）。
     */
    private fun hiddenKeysNow(recents: Any?): Set<String> {
        // 常态（没有已关闭记录）直接空集早退：getRecentTasks 在 system_server 是中高频
        // 出口（SystemUI / 桌面每次查多任务都走），别为空记录做 getRawTasks + HashSet 构建
        synchronized(closedLock) { if (closedKeys.isEmpty()) return emptySet() }
        val raw = runCatching {
            (Reflect.callByName(recents, "getRawTasks") as? Collection<*>)
                ?.mapNotNull { keyOf(it) }
                ?.toHashSet()
        }.getOrNull() ?: return synchronized(closedLock) { HashSet(closedKeys) }
        return synchronized(closedLock) {
            closedKeys.retainAll(raw)
            HashSet(closedKeys)
        }
    }

    /**
     * 任务被真正从 RecentTasks 移除时清掉记录。
     *
     * 与 [hiddenKeysNow] 的交叉校验互补：任务消失后 taskId 会被回收，
     * 若不清记录，同一个 id 的新任务会被误隐藏。
     */
    private fun hookRecentTasksRemove() {
        val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_RECENT_TASKS) ?: return
        val taskCls = Reflect.classOrNull(HookEnv.classLoader, CLS_TASK) ?: return
        val m = Reflect.findMethodByShape(cls, "remove", listOf(taskCls), null) ?: run {
            LogUtils.w(TAG, "installSystem", "RecentTasks#remove(Task) 不存在，跳过")
            return
        }
        runCatching {
            HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val r = chain.proceed()
                    chain.args.getOrNull(0)?.let { if (clearClosed(it)) LogUtils.d(TAG, "remove", "任务已移出多任务，清记录") }
                    return r
                }
            })
        }.onFailure { LogUtils.w(TAG, "installSystem", "挂 RecentTasks#remove 失败：${it.message}") }
    }

    /** 重新打开时清记录：moveToFront / 拿到焦点 / RESUMED 三条路都兜住 */
    private fun hookReopenClear() {
        val taskCls = Reflect.classOrNull(HookEnv.classLoader, CLS_TASK)
        if (taskCls != null) {
            val targets = taskCls.declaredMethods.filter { it.name == "moveToFront" }
            targets.forEach { m ->
                runCatching {
                    HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val r = chain.proceed()
                            // 有的重载把目标任务放在 args[1]，否则就是 thisObject
                            val task = chain.args.getOrNull(1) ?: chain.thisObject
                            if (task != null && clearClosed(task)) notifyTaskListUpdated()
                            return r
                        }
                    })
                }
            }
            Reflect.findMethodByShape(taskCls, "onAppFocusChanged", listOf(Boolean::class.javaPrimitiveType), null)
                ?.let { m ->
                    runCatching {
                        HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                            override fun intercept(chain: XposedInterface.Chain): Any? {
                                val r = chain.proceed()
                                if (chain.args.getOrNull(0) == true && clearClosed(chain.thisObject)) {
                                    notifyTaskListUpdated()
                                }
                                return r
                            }
                        })
                    }
                }
        }

        val fragmentCls = Reflect.classOrNull(HookEnv.classLoader, CLS_TASK_FRAGMENT) ?: return
        fragmentCls.declaredMethods.filter { it.name == "onActivityStateChanged" && it.parameterCount >= 2 }.forEach { m ->
            runCatching {
                HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val r = chain.proceed()
                        val state = chain.args.getOrNull(1)
                        val name = if (state is Enum<*>) state.name else state?.toString()
                        if (name == "RESUMED") {
                            val task = chain.args.getOrNull(0)?.let { Reflect.callDeep(it, "getTask") }
                            if (task != null && clearClosed(task)) notifyTaskListUpdated()
                        }
                        return r
                    }
                })
            }
        }
    }

    // ------------------------------------------------------------ 记录 / 过滤辅助

    private fun recordClosed(task: Any) {
        val key = keyOf(task) ?: return
        synchronized(closedLock) {
            if (!closedEnabled()) {
                closedKeys.clear()
                return
            }
            closedKeys.add(key)
        }
        LogUtils.d(TAG, "recordClosed", "记录已关闭小窗 $key")
        mainHandler.post { notifyTaskListUpdated() }
    }

    private fun clearClosed(task: Any): Boolean {
        val key = keyOf(task) ?: return false
        return synchronized(closedLock) { closedKeys.remove(key) }
    }

    private fun clearAll() {
        val empty = synchronized(closedLock) {
            if (closedKeys.isEmpty()) true else {
                closedKeys.clear()
                false
            }
        }
        if (!empty) mainHandler.post { notifyTaskListUpdated() }
    }

    /** 让 Launcher 的 RecentTasksList 缓存失效（经 Shell 的 RecentTasksController） */
    private fun notifyTaskListUpdated() {
        val controller = taskNotificationController ?: return
        runCatching { Reflect.callByName(controller, "notifyTaskListUpdated") }
    }

    /**
     * 统一成 `userId:taskId` 键。
     *
     * 同一个键要能同时覆盖三种对象：`RecentTaskInfo`（`userId` / `taskId`）、
     * WM 侧的 `Task`（`mUserId` / `mTaskId`）、以及可能出现的 `ActivityManager.RecentTaskInfo`
     * 变体 —— 所以两组字段名都试。
     */
    private fun keyOf(obj: Any?): String? {
        if (obj == null) return null
        val userId = (Reflect.getField(obj, "userId") as? Int)
            ?: (Reflect.getField(obj, "mUserId") as? Int)
            ?: return null
        val taskId = (Reflect.getField(obj, "taskId") as? Int)
            ?: (Reflect.getField(obj, "mTaskId") as? Int)
            ?: return null
        return "$userId:$taskId"
    }

    /**
     * 任务是否小窗。判据同上游 `RecentFreeformHistoryHooks#floating`：
     * `getLaunchScenario() == 1`（弹性小窗）且非内嵌、且有标题栏。
     */
    private fun isFloatingTask(task: Any): Boolean = runCatching {
        val wrapper = Reflect.callDeep(task, "getWrapper") ?: return@runCatching false
        val ext = Reflect.callDeep(wrapper, "getExtImpl") ?: return@runCatching false
        // ⚠️ 这三个方法声明在接口 `ITaskExt` 上、实现在 `TaskExtImpl`（可能落在父类），
        //    用 callByName（只查精确类的 declaredMethods）会恒返回 null ⇒ 判「不是小窗」
        //    ⇒ 「关闭小窗后隐藏」永不记录、零日志。必须 callDeep。
        val scenario = Reflect.callDeep(ext, "getLaunchScenario") as? Int
        if (scenario != 1) return@runCatching false
        if (Reflect.callDeep(ext, "isTaskEmbedded") == true) return@runCatching false
        Reflect.callDeep(ext, "isHasCaption") == true
    }.getOrElse { false }

    // ------------------------------------------------------------ 取值

    private fun recentsHideEnabled(): Boolean =
        HookEnv.settingBoolean(FloatWindowConfig.KEY_RECENTS_HIDE_FREEFORM, false)

    private fun closedEnabled(): Boolean {
        val now = HookEnv.settingBoolean(FloatWindowConfig.KEY_RECENTS_HIDE_CLOSED_FREEFORM, false)
        if (lastEnabled && !now) {
            // 关闭时清空记录：再打开时不带旧账
            synchronized(closedLock) { closedKeys.clear() }
        }
        lastEnabled = now
        return now
    }
}
