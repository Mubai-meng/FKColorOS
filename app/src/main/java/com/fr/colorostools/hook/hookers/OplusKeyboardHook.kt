package com.fr.colorostools.hook.hookers

import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.inputmethodservice.InputMethodService
import com.fr.colorostools.hook.HookEnv
import com.fr.colorostools.hook.scopes.opluskeyboard.ForceEnableOplusKeyboardBlur
import com.fr.colorostools.hook.scopes.opluskeyboard.OplusKeyboardConfig
import com.fr.colorostools.hook.xiaobuswipe.ImeRuntime
import com.fr.colorostools.hook.xiaobuswipe.SwipeConst
import com.fr.colorostools.hook.xiaobuswipe.SwipeGesture
import com.fr.colorostools.ref.Reflect
import com.fr.colorostools.utils.LogUtils
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 小布输入法（`com.oplus.keyboard`）两个功能的安装器：
 *
 * | 功能 | 实现 | 文档 |
 * |---|---|---|
 * | 强制启用液态玻璃模糊 | [ForceEnableOplusKeyboardBlur]（移植自上游 LuckyTool） | 见该类文档 |
 * | 按键下滑快捷键 | `hook/xiaobuswipe/` 各文件（移植自 `app.xiaobuswipe` 模块） | 见 [SwipeGesture] / [SwipeConst] 文档 |
 *
 * ## 两个功能独立安装、独立开关
 * 分别读各自的开关（`oplus_keyboard_blur_enabled` / `xiaobu_swipe_enabled`），
 * 关掉其中一个不影响另一个；都关着时本安装器只装生命周期钩子（代价可忽略）。
 *
 * ## 下滑功能的钩子清单（对照参考模块的 1+1 方案）
 *
 * | # | 目标 | 时机 | 作用 |
 * |---|---|---|---|
 * | 1 | `View#dispatchTouchEvent(MotionEvent)` | 拦截 | 手势识别入口；按「视图是不是键盘视图」过滤 |
 * | 2 | 键盘视图类的 `onDraw(Canvas)`（或 `dispatchDraw`） | 后置 | 在键面画绑定标签；**首次按下时才挂**（此时才知道具体类） |
 * | 3 | `InputMethodService#onCreate` | 后置 | 捕获 IME 实例 |
 * | 4 | `InputMethodService#onStartInput(EditorInfo, boolean)` | 前置 | 记录当前输入框是否密码框（密码框禁手势） |
 * | 5 | `InputMethodService#onConfigurationChanged(Configuration)` | 后置 | 刷新深浅色（标签配色） |
 * | 6 | `InputMethodService#onUpdateSelection(...)` | 后置 | 跟踪选区（段落/文档动作起点） |
 * | 7 | `ImeService#onUpdateSelection(...)` | 后置 | **必须挂具体类**：AOSP 基类回调在子类覆写后不再分发给 super 之上的 hook |
 *
 * 第 7 项是踩过的坑：宿主 `ImeService` 覆写了 `onUpdateSelection`，只挂
 * `InputMethodService` 上的声明时回调不会经过它（hook 框架只拦「实际被调用的那个方法」）。
 * 所以这里同时挂「基类声明」与「子类覆写」两处，谁被调用都能收到。
 *
 * ## 为什么手势钩 `View#dispatchTouchEvent` 而不是键盘类自己的方法
 * 键盘视图类名是混淆名且随版本变化，`dispatchTouchEvent` 是 `View` 上的公开方法，
 * 挂一次就能覆盖所有 View —— 用「`getSoftKeyboard()` 是否存在」判断是不是键盘视图，
 * 非键盘视图在第一行就 `proceed`，开销仅一次 `instanceof` + 类名缓存比对。
 *
 * ## 兜底原则
 * 每个钩子独立 `runCatching`；手势体内部再包保护，异常一律 `chain.proceed()`。
 * 输入法是高频前台应用（用户每天打字上万次），绝不能因本模块崩或明显卡顿。
 */
internal object OplusKeyboardHook {

    private const val TAG = "OplusKeyboard"

    /** 键盘视图类名前缀（宿主自身包名下的 `input.view.body.*`） */
    private const val BODY_PREFIX = "com.oplus.keyboard.input.view.body."

    /** 已知键盘视图类名（1.8.35.17 实证：`body.r` 是 26 键主视图） */
    private val KNOWN_KEYBOARD_CLASSES = setOf(
        "$BODY_PREFIX.r",
        "$BODY_PREFIX.T9View",
        "$BODY_PREFIX.QwertySeparateView",
        "$BODY_PREFIX.T9SeparateView",
    )

    /** 已判定为「非键盘视图」的类（避免每次都反射找方法） */
    private val nonKeyboardClasses = ConcurrentHashMap.newKeySet<Class<*>>()

    /** 已判定为键盘视图的类缓存 */
    @Volatile
    private var keyboardBaseClass: Class<*>? = null

    /** 当前键盘视图（弱引用，画标签用） */
    private var keyboardViewRef: WeakReference<View> = WeakReference(null)

    /** 已挂过绘制钩子的类（一个类只挂一次） */
    private val drawHookedClasses = ConcurrentHashMap.newKeySet<Class<*>>()

    /** 已挂过 onUpdateSelection 的类 */
    private val selectionHookedClasses = ConcurrentHashMap.newKeySet<Class<*>>()

    /** 安装标记 */
    private val installed = AtomicBoolean(false)

    private val selectionHookInstalled = AtomicBoolean(false)

    // ------------------------------------------------------------------ 安装

    fun install() {
        if (!installed.compareAndSet(false, true)) {
            LogUtils.i(TAG, "install", "已安装过，跳过")
            return
        }
        val blur = OplusKeyboardConfig.blurEnabled()
        val swipe = OplusKeyboardConfig.swipeEnabled()

        if (blur) {
            runCatching { ForceEnableOplusKeyboardBlur.install() }
                .onFailure { LogUtils.w(TAG, "install", "模糊增强安装异常：${it.message}") }
        } else {
            LogUtils.i(TAG, "install", "模糊增强未开启，跳过")
        }

        // 生命周期钩子：模糊与下滑都要（成本仅几次虚调用），都关着也装 —— 便于设置页开关后立刻生效
        runCatching { installImeLifecycle() }
            .onFailure { LogUtils.w(TAG, "install", "IME 生命周期钩子安装异常：${it.message}") }

        if (swipe) {
            runCatching { installTouch() }
                .onFailure { LogUtils.w(TAG, "install", "手势钩子安装异常：${it.message}") }
        } else {
            LogUtils.i(TAG, "install", "下滑快捷键未开启，跳过手势钩子")
        }
        LogUtils.i(TAG, "install", "小布输入法 Hook 安装完成（模糊=$blur，下滑=$swipe）")
    }

    // ------------------------------------------------------------------ 1. 手势入口

    /**
     * 挂 `View#dispatchTouchEvent(MotionEvent)`。
     *
     * 用 `ExceptionMode.PROTECTIVE`（对齐参考模块）：本钩子抛异常时只记日志、原样下放，
     * 不会把宿主输入法整个搞崩。
     */
    private fun installTouch() {
        val viewCls = Reflect.classOrNull(HookEnv.classLoader, "android.view.View") ?: run {
            LogUtils.w(TAG, "installTouch", "android.view.View 不存在")
            return
        }
        val m = viewCls.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java)
        m.isAccessible = true
        HookEnv.module.hook(m)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? = onDispatchTouch(chain)
            })
        LogUtils.i(TAG, "installTouch", "已挂 View#dispatchTouchEvent")
    }

    /**
     * 触摸分发拦截：
     * 1. 下滑功能关着 ⇒ 直接下放（零开销路径，只读一次 remote prefs）；
     * 2. 不是键盘视图 ⇒ 下放（判定结果按类缓存，非键盘类永不重复反射）；
     * 3. 是键盘视图 ⇒ 交给 [SwipeGesture]；返回值非 null 时用它，null 表示已内部 proceed。
     */
    private fun onDispatchTouch(chain: XposedInterface.Chain): Any? {
        val view = chain.thisObject as? View ?: return chain.proceed()
        val event = chain.args.getOrNull(0) as? MotionEvent ?: return chain.proceed()

        return runCatching {
            if (!OplusKeyboardConfig.swipeEnabled()) return@runCatching chain.proceed()
            if (!isKeyboardView(view)) return@runCatching chain.proceed()
            keyboardViewRef = WeakReference(view)
            // 首次识别出键盘视图时补挂绘制钩子（此时才知道具体类）
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                ensureDrawHook(view)
            }
            SwipeGesture.onTouch(chain, view, event) ?: chain.proceed()
        }.getOrElse {
            LogUtils.w(TAG, "onDispatchTouch", "手势处理异常：${it.javaClass.simpleName}: ${it.message}")
            chain.proceed()
        }
    }

    /**
     * 判断该 View 是不是键盘视图。
     *
     * 判定顺序：
     * 1. 已知键盘类 / 已判定非键盘类 → 直接返回缓存；
     * 2. 类名命中 `KNOWN_KEYBOARD_CLASSES`；
     * 3. 类名在 `com.oplus.keyboard.input.view.body.` 下且自身声明了无参 `getSoftKeyboard()`。
     *
     * 沿继承链最多看 12 层（对齐参考模块），命中后把**命中的那个类**记进 cache ——
     * 后续同类的所有实例（包括子类实例经 isInstance 判断）都能一次命中。
     */
    private fun isKeyboardView(view: View): Boolean {
        val cls = view.javaClass
        keyboardBaseClass?.let { base ->
            if (base.isInstance(view)) return true
        }
        if (cls in nonKeyboardClasses) return false

        var c: Class<*>? = cls
        var depth = 0
        while (c != null && depth < 12) {
            val name = c.name
            val hit = name in KNOWN_KEYBOARD_CLASSES ||
                (name.startsWith(BODY_PREFIX) &&
                    View::class.java.isAssignableFrom(c) &&
                    hasNoArgMethod(c, "getSoftKeyboard"))
            if (hit) {
                keyboardBaseClass = c
                LogUtils.i(TAG, "isKeyboardView", "识别到键盘视图类：$name")
                return true
            }
            c = c.superclass
            depth++
        }
        // 只对「键盘包内」的类做负缓存，避免污染其它 View 子类（如 FrameLayout 等）
        if (cls.name.startsWith("com.oplus.keyboard")) nonKeyboardClasses.add(cls)
        return false
    }

    private fun hasNoArgMethod(cls: Class<*>, name: String): Boolean =
        runCatching { cls.getDeclaredMethod(name); true }.getOrDefault(false)

    // ------------------------------------------------------------------ 2. 绘制钩子

    /**
     * 挂键盘视图类的 `onDraw(Canvas)`（找不到则 `dispatchDraw`），后置画按键标签。
     *
     * 只在第一次收到该视图的 DOWN 时调用，每个类只挂一次。
     */
    private fun ensureDrawHook(view: View) {
        val cls = view.javaClass
        if (!drawHookedClasses.add(cls)) return

        val method = findDrawMethod(cls, "onDraw") ?: findDrawMethod(cls, "dispatchDraw")
        if (method == null) {
            LogUtils.w(TAG, "ensureDrawHook", "${cls.name} 既无 onDraw 也无 dispatchDraw，标签不画")
            return
        }
        runCatching {
            method.isAccessible = true
            HookEnv.module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val r = chain.proceed()
                        runCatching {
                            val v = chain.thisObject as? View ?: return@runCatching
                            val canvas = chain.args.getOrNull(0) as? android.graphics.Canvas
                                ?: return@runCatching
                            // 编辑框是密码时标签也不该露出（等于泄露「这里可复制/粘贴」）
                            SwipeGesture.drawLabels(v, canvas)
                        }
                        return r
                    }
                })
            view.postInvalidate()
            LogUtils.i(
                TAG, "ensureDrawHook",
                "已挂标签绘制：${method.declaringClass.name}#${method.name}",
            )
        }.onFailure {
            LogUtils.w(TAG, "ensureDrawHook", "挂绘制失败：${it.message}")
        }
    }

    private fun findDrawMethod(cls: Class<*>, name: String): java.lang.reflect.Method? {
        var c: Class<*>? = cls
        var depth = 0
        while (c != null && c != View::class.java && depth < 12) {
            runCatching { return c.getDeclaredMethod(name, android.graphics.Canvas::class.java) }
            c = c.superclass
            depth++
        }
        return null
    }

    // ------------------------------------------------------------------ 3~7. IME 生命周期

    /**
     * 挂 IME 生命周期全部钩子。
     *
     * `InputMethodService` 上的声明挂一次即可覆盖「宿主没覆写」的方法；
     * 宿主覆写了的方法（`onUpdateSelection`）要额外挂具体类 —— 见类文档第 7 项。
     */
    private fun installImeLifecycle() {
        val imsCls = Reflect.classOrNull(HookEnv.classLoader, "android.inputmethodservice.InputMethodService")
        if (imsCls == null) {
            LogUtils.w(TAG, "installImeLifecycle", "InputMethodService 不存在")
            return
        }
        var hooked = 0

        // onCreate：捕获 IME 实例（也顺手补挂具体类的回调）
        runCatching {
            val m = imsCls.getDeclaredMethod("onCreate")
            m.isAccessible = true
            HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val r = chain.proceed()
                    val ime = chain.thisObject as? InputMethodService ?: return r
                    runCatching {
                        ImeRuntime.captureIme(ime)
                        ensureConcreteHooks(ime.javaClass)
                    }
                    return r
                }
            })
            hooked++
        }.onFailure { LogUtils.w(TAG, "installImeLifecycle", "onCreate 挂载失败：${it.message}") }

        // onStartInput：记录密码框
        runCatching {
            val m = imsCls.getDeclaredMethod(
                "onStartInput", EditorInfo::class.java, java.lang.Boolean.TYPE
            )
            m.isAccessible = true
            HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    runCatching {
                        ImeRuntime.captureStartInput(chain.args.getOrNull(0) as? EditorInfo)
                        (chain.thisObject as? InputMethodService)?.let {
                            ImeRuntime.captureIme(it)
                            ensureConcreteHooks(it.javaClass)
                        }
                        SwipeGesture.onConfigMaybeChanged()
                    }
                    return chain.proceed()
                }
            })
            hooked++
        }.onFailure { LogUtils.w(TAG, "installImeLifecycle", "onStartInput 挂载失败：${it.message}") }

        // onConfigurationChanged：刷新深浅色
        runCatching {
            val m = imsCls.getDeclaredMethod("onConfigurationChanged", android.content.res.Configuration::class.java)
            m.isAccessible = true
            HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val r = chain.proceed()
                    runCatching {
                        (chain.thisObject as? InputMethodService)?.let {
                            ImeRuntime.refreshNightMode(it.resources.configuration.uiMode)
                        }
                        SwipeGesture.onConfigMaybeChanged()
                    }
                    return r
                }
            })
            hooked++
        }.onFailure { LogUtils.w(TAG, "installImeLifecycle", "onConfigurationChanged 挂载失败：${it.message}") }

        // onUpdateSelection：基类声明处
        if (hookUpdateSelection(imsCls)) hooked++

        LogUtils.i(TAG, "installImeLifecycle", "IME 生命周期已挂 $hooked/4（具体类回调按需补挂）")
    }

    /** 挂某个类上的 `onUpdateSelection`（6 个 int 参数），成功返回 true */
    private fun hookUpdateSelection(cls: Class<*>): Boolean {
        val m = runCatching {
            cls.getDeclaredMethod(
                "onUpdateSelection",
                java.lang.Integer.TYPE, java.lang.Integer.TYPE, java.lang.Integer.TYPE,
                java.lang.Integer.TYPE, java.lang.Integer.TYPE, java.lang.Integer.TYPE,
            )
        }.getOrNull() ?: return false
        m.isAccessible = true
        return runCatching {
            HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val r = chain.proceed()
                    runCatching {
                        val a = chain.args
                        val newStart = (a.getOrNull(0) as? Int) ?: -1
                        val newEnd = (a.getOrNull(1) as? Int) ?: -1
                        ImeRuntime.captureSelection(newStart, newEnd)
                    }
                    return r
                }
            })
            true
        }.getOrElse {
            LogUtils.w(TAG, "hookUpdateSelection", "${cls.name} 挂载失败：${it.message}")
            false
        }
    }

    /**
     * 补挂「具体 IME 类」上覆写的生命周期方法。
     *
     * 目前只需 `onUpdateSelection`（宿主 `ImeService` 覆写了它）。
     * 用「按类去重」保证一个类只挂一次；`onCreate` 与 `onStartInput` 两个时机都会调用，
     * 因为 `onCreate` 早于 `Application` 完全就绪，某些版本可能拿不到最终的类。
     */
    private fun ensureConcreteHooks(cls: Class<*>) {
        if (!selectionHookedClasses.add(cls)) return
        if (hookUpdateSelection(cls)) {
            selectionHookInstalled.set(true)
            LogUtils.i(TAG, "ensureConcreteHooks", "已挂具体类选区回调：${cls.name}")
        } else {
            // 该类没覆写，去掉缓存标记以便下次（换类时）再试
            selectionHookedClasses.remove(cls)
        }
    }

    // ------------------------------------------------------------------ 对外

    /** 当前键盘视图（诊断用） */
    fun currentKeyboardView(): View? = keyboardViewRef.get()

    /** 下滑快捷键的动作标签是否已就绪（诊断用） */
    fun isSelectionHookInstalled(): Boolean = selectionHookInstalled.get()

    /** 作用域变更 / 宿主重启时清理内存状态（本模块无热重载入口，保留给排障） */
    fun reset() {
        keyboardBaseClass = null
        nonKeyboardClasses.clear()
        drawHookedClasses.clear()
        selectionHookedClasses.clear()
        keyboardViewRef = WeakReference(null)
        ImeRuntime.reset()
        SwipeGesture.reset()
    }

    /** 供日志页显示的目标包名 */
    val targetPackage: String get() = SwipeConst.TARGET
}