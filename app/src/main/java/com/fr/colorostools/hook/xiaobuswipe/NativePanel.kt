package com.fr.colorostools.hook.xiaobuswipe

import android.view.View
import com.fr.colorostools.ref.Reflect
import com.fr.colorostools.utils.LogUtils
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * 原生面板桥：打开小布输入法自己的**剪贴板** / **快捷短语**面板。
 *
 * ## 链路（1.8.35.17-mkt 反编译实证，`_ref_kb_jadx`）
 *
 * ```
 * Z.a.a()                                  // com.oplus.keyboard.input.view.Z 单例
 *   └─ b()                                 // → com.oplus.keyboard.input.manager.e（容器管理器）
 *        └─ n(b headType, Function1 lVar)  // ← 宿主自己用的入口（e#n 会先 super.n(lVar, headType)）
 * ```
 *
 * 宿主的**唯一正确用法**（`input/view/head/l0.java:383` 工具栏剪贴板按钮、`t.java` case 10、
 * `input/view/J.java:579` BOX_PHRASE 分支）都是：
 *
 * ```
 * n(com.oplus.keyboard.base.manager.b.f.f13765a, new com.oplus.keyboard.input.manager.t(16))
 * └─ headType = b$f  ⇒  e#c 里 `bVar instanceof b.f → new H(context)`，H 即剪贴板/短语面板
 * ```
 *
 * `t(int)` 的 case 决定面板打开后干什么：
 * - **16** = 剪贴板页（`H.q()` + 段选 0）
 * - **19** = 快捷短语页（`H.t(false)` → 段选 1）
 *
 * ## ⚠️ 两个曾踩死的坑（v1.1.5x 真机「下滑打开剪贴板」炸出工具箱浮层）
 *
 * 1. **headType 必须是 `b$f`，不是 `b$d`**。`b$d` 在 `e#c` 里映射到
 *    `com.oplus.keyboard.input.toolbox.b`（**工具箱**）。用 `b$d` 会把工具箱塞进容器，
 *    而 `t(16)` 内部第一行是 `H h = (H) view` ⇒ **ClassCastException**。
 *    此时工具箱视图**已经 addView 完毕**（base#n 是先 put/addView 再 invoke lambda），
 *    于是屏幕上留下一坨图标压在键位上、面板彻底错乱。
 * 2. **参数顺序不能盲试**。`base#n` 是 `(Function1, Object)`，`e#n` 是 `(b, Function1)`，
 *    两个重载同时存在。盲试 `(handler, headType)` 会命中 base#n 并**先产生副作用再抛异常**，
 *    所以现在改成**按 `parameterTypes` 判定顺序**，只发一次调用。
 *
 * 另有第三重保险：调用后用 `d(headType)` 取回真正创建的视图校验类名必须是 `body.H`，
 * 不符合则调 `a.h(manager)` 回滚隐藏，绝不留脏面板。
 */
internal object NativePanel {

    private const val TAG = SwipeConst.TAG

    /** 容器管理器持有者（`Z.a.a()` 单例，`b()` 返回 `input.manager.e`） */
    private const val CLS_Z = "com.oplus.keyboard.input.view.Z"

    /** 头部面板类型基类（静态内部类 `a`..`p` 各是一个单例，字段名都是 `a`） */
    private const val CLS_HEAD_TYPE = "com.oplus.keyboard.base.manager.b"

    /** `b$f` ⇒ `e#c` 分支 `new H(context)`，H = 剪贴板/短语面板 */
    private const val HEAD_TYPE_SIMPLE = "f"

    /** 头部面板类型实例字段名（`public static final b$X a`） */
    private const val HEAD_TYPE_FIELD = "a"

    /** 面板打开后的处理器 lambda（`t implements kotlin.jvm.functions.Function1`，构造参数 = case） */
    private const val CLS_HANDLER = "com.oplus.keyboard.input.manager.t"

    /** 剪贴板 / 短语面板视图类（用来校验真的开对了面板） */
    private const val CLS_CLIP_PANEL = "com.oplus.keyboard.input.view.body.H"

    /** 容器管理器基类（静态 `h(manager)` = 隐藏当前面板，失败回滚用） */
    private const val CLS_BASE_MANAGER = "com.oplus.keyboard.base.manager.a"

    /** `t(16)` ⇒ 打开后 `H.q()`：切到剪贴板页 */
    private const val CASE_CLIPBOARD = 16

    /** `t(19)` ⇒ 打开后 `H.t(false)`：切到快捷短语页 */
    private const val CASE_PHRASE = 19

    /**
     * 打开面板。
     *
     * @param phrase true = 快捷短语页；false = 剪贴板页
     * @return 是否真的开出了 `body.H` 面板（不代表内容有数据，只保证面板正确上屏）
     */
    fun open(view: View?, phrase: Boolean): Boolean {
        val loader = view?.context?.classLoader ?: SwipeConst::class.java.classLoader ?: return false
        return runCatching {
            val bus = eventBus(loader) ?: return fail("事件总线不可用（宿主结构变化？）")
            val headType = headType(loader) ?: return fail("头部面板类型实例不可用")
            val call = resolveCall(bus, headType, loader, phrase) ?: return fail(
                "事件总线上找不到可用的 n()：候选＝" + collectPostMethods(bus).joinToString("; ") { m ->
                    "${m.declaringClass.simpleName}(${m.parameterTypes.joinToString(",") { it.simpleName }})"
                }
            )
            val (method, args) = call
            LogUtils.d(
                TAG, "panel",
                "打开${if (phrase) "短语" else "剪贴板"}面板 → " +
                    "${method.declaringClass.simpleName}#n(" +
                    method.parameterTypes.joinToString(",") { it.simpleName } + ")，" +
                    "headType=${headType.javaClass.simpleName}"
            )

            val thrown = runCatching {
                method.isAccessible = true
                method.invoke(bus, *args)
                null
            }.getOrElse { e -> e }
            if (thrown != null) {
                val cause = (thrown as? InvocationTargetException)?.cause ?: thrown
                fail("n() 内部抛出 ${cause.javaClass.simpleName}: ${cause.message}")
                // 出错时宿主可能已经把半成品视图 addView 进容器了，必须回滚
                rollback(bus)
                return false
            }

            // 校验真的开出了剪贴板面板：曾经因为 headType 选成工具箱，界面被图标浮层糊死
            val panel = panelView(bus, headType)
            if (panel == null) {
                fail("n() 已返回但容器里没有面板视图")
                rollback(bus)
                return false
            }
            if (panel.javaClass.name != CLS_CLIP_PANEL) {
                fail("开出了非预期面板 ${panel.javaClass.name}（应为 $CLS_CLIP_PANEL），已回滚")
                rollback(bus)
                return false
            }
            LogUtils.i(TAG, "panel", "面板已打开（phrase=$phrase）")
            true
        }.getOrElse {
            LogUtils.w(TAG, "panel", "打开面板失败：${it.javaClass.simpleName}: ${it.message}")
            false
        }
    }

    private fun fail(msg: String): Boolean {
        LogUtils.w(TAG, "panel", msg)
        return false
    }

    // ---------------------------------------------------------------- 链路组装

    /** `Z.a.a()` 拿到 `Z` 单例，再 `b()` 拿到容器管理器（= 事件总线对象） */
    private fun eventBus(loader: ClassLoader): Any? {
        val zClass = Reflect.classOrNull(loader, CLS_Z) ?: return null
        val holder = zClass.declaredClasses.firstOrNull { it.simpleName == "a" } ?: return null
        val singleton = runCatching {
            holder.getDeclaredMethod("a").apply { isAccessible = true }.invoke(null)
        }.getOrNull() ?: return null
        return Reflect.callDeep(singleton, "b")
    }

    /**
     * 取头部面板类型实例。
     *
     * 优先 `b$f`（剪贴板/短语面板）；宿主改版若换了内部类名，则退化为扫描全部内部类，
     * 但**必须**是 `b` 的子类实例（否则 `e#c`/`e#e` 里的 `(b) obj` 直接 CCE）。
     */
    private fun headType(loader: ClassLoader): Any? {
        val base = Reflect.classOrNull(loader, CLS_HEAD_TYPE) ?: return null
        val inner = base.declaredClasses
        val preferred = inner.firstOrNull { it.simpleName == HEAD_TYPE_SIMPLE }
        val candidates = listOfNotNull(preferred) +
            inner.filter { it !== preferred && it.simpleName.length == 1 }
        for (cls in candidates) {
            val instance = runCatching {
                val f = cls.getDeclaredField(HEAD_TYPE_FIELD)
                f.isAccessible = true
                f.get(null)
            }.getOrNull() ?: continue
            if (!base.isInstance(instance)) {
                LogUtils.w(TAG, "panel", "${cls.simpleName}.$HEAD_TYPE_FIELD 不是 ${base.simpleName} 实例，跳过")
                continue
            }
            if (cls !== preferred) {
                LogUtils.w(TAG, "panel", "未找到 b\$$HEAD_TYPE_SIMPLE，回退到 b\$${cls.simpleName}")
            }
            return instance
        }
        return null
    }

    /**
     * 组装调用：按 `parameterTypes` 判定 headType 与 lambda 的位置，**不做盲试**。
     *
     * - `e#n(b headType, Function1)`（宿主真实入口）⇒ `(headType, handler)`
     * - `a#n(Function1, Object)`（基类老签名）⇒ `(handler, headType)`
     */
    private fun resolveCall(
        bus: Any,
        headType: Any,
        loader: ClassLoader,
        phrase: Boolean,
    ): Pair<Method, Array<Any>>? {
        for (m in collectPostMethods(bus)) {
            val p = m.parameterTypes
            if (p.size != 2) continue
            if (p[0].isInstance(headType) && p[1].isInterface) {
                val h = buildHandler(loader, p[1], phrase) ?: continue
                return m to arrayOf(headType, h)
            }
            if (p[1].isInstance(headType) && p[0].isInterface) {
                val h = buildHandler(loader, p[0], phrase) ?: continue
                return m to arrayOf(h, headType)
            }
        }
        return null
    }

    /**
     * 造处理 lambda。
     *
     * 首选宿主自己的 `com.oplus.keyboard.input.manager.t(case)`（行为与用户点工具栏完全一致）；
     * 该类被改名 / 构造签名变化时，退化为**自己实现的 Function1 代理**，内部按 case 语义
     * 直接调 `body.H#q()`（剪贴板）或 `body.H#t(false)`（短语）。
     */
    private fun buildHandler(loader: ClassLoader, paramType: Class<*>, phrase: Boolean): Any? {
        val case = if (phrase) CASE_PHRASE else CASE_CLIPBOARD
        val cls = Reflect.classOrNull(loader, CLS_HANDLER)
        val built = cls?.let { Reflect.newInstance(it, listOf(Int::class.javaPrimitiveType), case) }
        if (built != null && paramType.isInstance(built)) return built

        LogUtils.w(TAG, "panel", "宿主 ${CLS_HANDLER}($case) 不可用，改用自建 lambda")
        return runCatching {
            java.lang.reflect.Proxy.newProxyInstance(loader, arrayOf(paramType)) { proxy, method, args ->
                when (method.name) {
                    "invoke" -> {
                        applyPanel(args?.firstOrNull(), phrase)
                        Unit
                    }
                    "toString" -> "ColorOSToolsPanelHandler"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> null
                }
            }
        }.getOrNull()
    }

    /** 自建 lambda 的实际动作（对齐 `t` 的 case 16 / 19） */
    private fun applyPanel(created: Any?, phrase: Boolean) {
        val view = created as? View ?: return
        if (view.javaClass.name != CLS_CLIP_PANEL) {
            LogUtils.w(TAG, "panel", "自建 lambda 收到非剪贴板视图 ${view.javaClass.name}，忽略")
            return
        }
        if (phrase) {
            // H#t(boolean)：独立于布尔值本身，都会切到「短语」段。
            // 必须按参数类型过滤 —— H 里还有其它单参 t(...) 重载
            val m = Reflect.findMethodDeep(view.javaClass, "t", 1, mapOf(0 to "boolean"))
            Reflect.call(view, m, false)
        } else {
            // H#q()：回到剪贴板页（隐藏编辑条 / 功能条 / 短语条）
            Reflect.callDeep(view, "q")
        }
    }

    /** 事件总线（及其父类）上所有名为 `n` 且两参的方法 */
    private fun collectPostMethods(bus: Any): List<Method> {
        val out = ArrayList<Method>(2)
        var cls: Class<*>? = bus.javaClass
        while (cls != null && cls != Any::class.java) {
            cls.declaredMethods
                .filter { it.name == "n" && it.parameterCount == 2 }
                .forEach { out.add(it) }
            cls = cls.superclass
        }
        return out
    }

    // ---------------------------------------------------------------- 校验与回滚

    /** `a#d(Object)`：只取容器里**已存在**的视图，不新建 */
    private fun panelView(bus: Any, headType: Any): View? =
        runCatching { Reflect.callDeep(bus, "d", headType) as? View }.getOrNull()

    /** `a.h(manager)`：隐藏当前面板（失败路径清理半成品视图，避免图标浮层糊在键位上） */
    private fun rollback(bus: Any) {
        runCatching {
            val baseCls = Reflect.classOrNull(bus.javaClass.classLoader, CLS_BASE_MANAGER)
            val r = Reflect.callStatic(baseCls, "h", bus)
            LogUtils.i(TAG, "panel", "已回滚面板（hidden=${r ?: "void"}）")
        }.onFailure {
            LogUtils.w(TAG, "panel", "回滚失败：${it.javaClass.simpleName}: ${it.message}")
        }
    }
}
