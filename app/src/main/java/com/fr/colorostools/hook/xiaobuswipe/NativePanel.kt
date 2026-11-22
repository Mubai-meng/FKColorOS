package com.fr.colorostools.hook.xiaobuswipe

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.fr.colorostools.ref.Reflect
import com.fr.colorostools.utils.LogUtils
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * 原生面板桥：打开小布输入法自己的**剪贴板** / **常用语（快捷短语）**面板。
 *
 * ## 为什么要「版本坐标表」
 *
 * 小布每次大版本升级 R8 都会重排混淆名，写死单套类名必然随版本失效。上游 v2.0.0 因此改成
 * [Variant] 表：每个 Variant 记录该版本下 **单例 → 总线 → 投递方法 → lambda → 面板** 的反射坐标，
 * 运行时按顺序尝试，首个走通的即生效（新版在前，失败自动回退旧版）。
 *
 * 已核实两套：
 * ```
 * 1.9.18.17-mkt:  j0.c.getValue().b().n(base.manager.g.a, new input.manager.b(22))  面板 body.I
 * 1.8.35.17-mkt:  Z.a.a().b()    .n(base.manager.b$f.a, new input.manager.t(16))  面板 body.H
 * ```
 *
 * ⚠️ 表里的名字必须是 **dex 真实名**，不能用 jadx 的重命名显示名（jadx 会把与其它 dex
 * 同名的类/字段改名并在源码标注 `renamed from`）：1.9.18 里看到的 `C0991b` 实为
 * `input.manager.b`、`j0.f15327c` 实为 `j0.c`、`g.f11777a` 实为 `g.a`。
 *
 * ## 三个曾踩死的坑（v1.1.5x 真机「下滑打开剪贴板」炸出工具箱浮层）
 *
 * 1. **headType 必须是 `b$f`（1.8.35）/ `g.a`（1.9.18）**。`b$d` 在容器管理器里映射到
 *    `input.toolbox.b`（**工具箱**），会把工具箱塞进容器，而 lambda 内部第一行就把它 CCE 成
 *    面板类型 ⇒ 此时工具箱视图**已经 addView 完毕**（先 put/addView 再 invoke lambda），
 *    屏幕上留下一坨图标压在键位上。因此本实现**不做 headType 猜测回退**，坐标一律来自变体表。
 * 2. **参数顺序不能按源码顺序假设**。jadx 显示 `n(headType, lambda)`，但运行时存在合成桥
 *    方法 `(Function1, Object)`。故两种实参顺序都试；`IllegalArgumentException` = 该重载不
 *    接受这组实参（可安全继续）；`InvocationTargetException` = 坐标已对上但目标内部抛异常
 *    ⇒ 必须**立即回退下一套变体**，不能继续拿别的方法乱捅。
 * 3. 面板建好后用容器 `d(headType)` 取回真视图校验类名，命中 `toolbox` 之类明显错误的面板时
 *    调 `base.manager.a.h(manager)` 回滚，绝不留脏浮层。
 *
 * ## 常用语的打开方式（上游 v2.0.0 修正）
 *
 * 旧实现用 `t(19)`（case 19）走「快捷短语」分支。v2.0.0 改为**仍用剪贴板 case 打开面板**
 * （面板本来就是多段容器，段 0 = 剪贴板、段 1 = 常用语），再对面板调 `t(true)` 把段状态置 1
 * 并 `selectSegmentAt(1)`。这样只依赖一个 case id，抗混淆更稳。面板尚未挂上视图树时
 * 80ms 后补跳一次。
 */
internal object NativePanel {

    private const val TAG = SwipeConst.TAG

    /** 单个小布版本的反射坐标 */
    private class Variant(
        val label: String,
        /** 单例门面类 */
        val singletonClass: String,
        /** 非 null：kotlin Lazy 静态字段名（新式）；null：走旧式内部类 `a()`（Companion） */
        val singletonLazyField: String?,
        /** headType 静态实例所在类（`base.manager.b$f` / `base.manager.g`） */
        val paramClass: String,
        /** headType 实例字段名 */
        val paramField: String,
        /** 面板投递 lambda 类（构造参数 = case id） */
        val lambdaClass: String,
        /** 打开剪贴板段的 case id */
        val clipboardCase: Int,
        /** 剪贴板/常用语面板视图类 */
        val panelClass: String,
    )

    /** 新版在前：命中即返回，失败自动回退旧版 */
    private val VARIANTS = arrayOf(
        Variant(
            "1.9.18",
            "com.oplus.keyboard.input.view.j0", "c",
            "com.oplus.keyboard.base.manager.g", "a",
            "com.oplus.keyboard.input.manager.b", 22,
            "com.oplus.keyboard.input.view.body.I",
        ),
        Variant(
            "1.8.35",
            "com.oplus.keyboard.input.view.Z", null,
            "com.oplus.keyboard.base.manager.b\$f", "a",
            "com.oplus.keyboard.input.manager.t", 16,
            "com.oplus.keyboard.input.view.body.H",
        ),
    )

    /** 容器管理器基类（静态 `h(manager)` = 隐藏当前面板，失败回滚用） */
    private const val CLS_BASE_MANAGER = "com.oplus.keyboard.base.manager.a"

    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * 打开面板。
     *
     * @param view 键盘视图（用来取 ClassLoader 与视图树根）
     * @param phrase true = 常用语段；false = 剪贴板段
     * @return 是否有一整套变体走通（不代表面板里有数据）
     */
    fun open(view: View?, phrase: Boolean): Boolean {
        val loader = view?.context?.classLoader ?: SwipeConst::class.java.classLoader ?: return false
        val root: View? = view?.rootView ?: view
        for (variant in VARIANTS) {
            val ok = runCatching { openWithVariant(loader, root, phrase, variant) }.getOrElse { e ->
                LogUtils.i(
                    TAG, "panel",
                    "变体 ${variant.label} 不可用：${e.javaClass.simpleName}: ${e.message}"
                )
                false
            }
            if (ok) {
                LogUtils.i(
                    TAG, "panel",
                    "已用变体 ${variant.label} 打开${if (phrase) "常用语" else "剪贴板"}面板"
                )
                return true
            }
        }
        LogUtils.w(TAG, "panel", "全部变体均未匹配（宿主结构已变更？）")
        return false
    }

    private fun openWithVariant(
        loader: ClassLoader,
        keyboardRoot: View?,
        phrase: Boolean,
        variant: Variant,
    ): Boolean {
        val facade = facade(loader, variant) ?: run {
            LogUtils.i(TAG, "panel", "${variant.label}: 单例门面不可用")
            return false
        }
        val bus = Reflect.callDeep(facade, "b") ?: run {
            LogUtils.i(TAG, "panel", "${variant.label}: 事件总线不可用")
            return false
        }
        val param = staticField(loader, variant.paramClass, variant.paramField)
        val lambda = panelLambda(loader, variant.lambdaClass, variant.clipboardCase)
        if (param == null || lambda == null) {
            LogUtils.i(
                TAG, "panel",
                "${variant.label}: headType/lambda 不可用" +
                    "（param=${param != null}, lambda=${lambda != null}）"
            )
            return false
        }
        val candidates = collectPostMethods(bus)
        if (candidates.isEmpty()) {
            LogUtils.i(TAG, "panel", "${variant.label}: 总线上没有 2 参 n()")
            return false
        }
        LogUtils.d(
            TAG, "panel",
            "候选=[" + candidates.joinToString(" ") { m ->
                "${m.declaringClass.name}.n(" +
                    m.parameterTypes.joinToString(",") { it.name } + ")"
            } + "] param=${param.javaClass.name} lambda=${lambda.javaClass.name}"
        )

        // 运行时可能是 (Function1, Object)，也可能与源码一致为 (headType, Function1)，两种都试
        val argOrders = arrayOf(arrayOf<Any>(lambda, param), arrayOf<Any>(param, lambda))
        var last: Throwable? = null
        for (m in candidates) {
            for (args in argOrders) {
                try {
                    m.isAccessible = true
                    m.invoke(bus, *args)
                } catch (e: IllegalArgumentException) {
                    last = e
                    continue
                } catch (e: InvocationTargetException) {
                    // 方法被接受但内部抛异常：坐标已对上，本次无法继续，直接回退下一套
                    val cause = e.cause ?: e
                    LogUtils.w(
                        TAG, "panel",
                        "${variant.label}: n() 内部抛出 ${cause.javaClass.simpleName}: ${cause.message}"
                    )
                    rollback(bus)
                    return false
                } catch (t: Throwable) {
                    last = t
                    break
                }
                verifyPanel(bus, param, variant)
                if (phrase) switchToPhrase(keyboardRoot, variant.panelClass)
                return true
            }
        }
        LogUtils.i(TAG, "panel", "${variant.label}: 所有重载都拒绝实参 → $last")
        return false
    }

    // ---------------------------------------------------------------- 变体坐标解析

    /** 解析单例门面：新式为 kotlin Lazy 静态字段，旧式为内部类 Companion.a() */
    private fun facade(loader: ClassLoader, variant: Variant): Any? {
        val cls = Reflect.classOrNull(loader, variant.singletonClass) ?: return null
        val field = variant.singletonLazyField
        if (field != null) {
            val lazy = runCatching {
                cls.getDeclaredField(field).apply { isAccessible = true }.get(null)
            }.getOrNull()
            if (lazy != null) {
                // ⚠️ 不能 loadClass("kotlin.Lazy")：小布 dex 里该符号不可见（ClassNotFoundException）
                lazyValue(lazy)?.let { return it }
            }
        }
        for (inner in cls.declaredClasses) {
            if (inner.simpleName != "a") continue
            return runCatching {
                inner.getDeclaredMethod("a").apply { isAccessible = true }.invoke(null)
            }.getOrNull()
        }
        return null
    }

    /** 反射调 kotlin Lazy 的 `getValue()`：优先实现类，其次其接口 */
    private fun lazyValue(lazy: Any): Any? {
        var type: Class<*>? = lazy.javaClass
        while (type != null && type != Any::class.java) {
            try {
                val m = type.getDeclaredMethod("getValue")
                m.isAccessible = true
                return m.invoke(lazy)
            } catch (_: NoSuchMethodException) {
            } catch (t: Throwable) {
                return null
            }
            type = type.superclass
        }
        for (itf in lazy.javaClass.interfaces) {
            try {
                val m = itf.getMethod("getValue")
                m.isAccessible = true
                return m.invoke(lazy)
            } catch (_: NoSuchMethodException) {
            } catch (t: Throwable) {
                return null
            }
        }
        return null
    }

    private fun staticField(loader: ClassLoader, className: String, fieldName: String): Any? {
        val cls = Reflect.classOrNull(loader, className) ?: return null
        return runCatching {
            cls.getDeclaredField(fieldName).apply { isAccessible = true }.get(null)
        }.getOrNull()
    }

    private fun panelLambda(loader: ClassLoader, className: String, caseId: Int): Any? {
        val cls = Reflect.classOrNull(loader, className) ?: return null
        return Reflect.newInstance(cls, listOf(Int::class.javaPrimitiveType), caseId)
    }

    /** 总线上（含父类与 R8 合成桥方法）所有名为 `n` 且两参的方法 */
    private fun collectPostMethods(bus: Any): List<Method> {
        val out = ArrayList<Method>(4)
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

    /**
     * 取回容器里**已存在**的视图（`a#d(Object)`，不新建）并校验类名。
     *
     * 只对「明显开错」的情况动手：命中 `toolbox` 才回滚。同一套坐标下不同小版本的面板类名
     * 偶尔会多包一层，此时不匹配只记日志——宁可留着可用面板，也不要误杀。
     */
    private fun verifyPanel(bus: Any, headType: Any, variant: Variant) {
        val panel = runCatching { Reflect.callDeep(bus, "d", headType) as? View }.getOrNull()
        if (panel == null) {
            LogUtils.d(TAG, "panel", "${variant.label}: 容器里暂时取不到面板视图（不影响使用）")
            return
        }
        val name = panel.javaClass.name
        if (name == variant.panelClass) return
        if (name.contains("toolbox")) {
            LogUtils.w(TAG, "panel", "${variant.label}: 开出了工具箱 $name，已回滚")
            rollback(bus)
            return
        }
        LogUtils.i(TAG, "panel", "${variant.label}: 面板类名 $name（预期 ${variant.panelClass}）")
    }

    /** `base.manager.a.h(manager)`：隐藏当前面板，清掉可能残留的半成品视图 */
    private fun rollback(bus: Any) {
        runCatching {
            val cls = Reflect.classOrNull(bus.javaClass.classLoader, CLS_BASE_MANAGER)
            Reflect.callStatic(cls, "h", bus)
            LogUtils.i(TAG, "panel", "已回滚面板")
        }.onFailure {
            LogUtils.w(TAG, "panel", "回滚失败：${it.javaClass.simpleName}: ${it.message}")
        }
    }

    // ---------------------------------------------------------------- 常用语段切换

    /**
     * 常用语：面板本身就是剪贴板面板的第 1 段（段 0=剪贴板、段 1=常用语）。
     * 剪贴板 lambda 已把面板打开并停在段 0，这里对面板调 `t(true)` 把段状态置 1 并
     * `selectSegmentAt(1)`，从而直接展示常用语。
     */
    private fun switchToPhrase(root: View?, panelClass: String) {
        val panel = findPanelByClass(root, panelClass)
        if (panel != null) {
            switchToPhraseNow(panel)
            return
        }
        // 极少数情况下面板尚未挂到视图树，短延时兜底重试一次
        mainHandler.postDelayed({
            val late = findPanelByClass(root, panelClass)
            if (late != null) {
                switchToPhraseNow(late)
            } else {
                LogUtils.i(TAG, "panel", "常用语切换：视图树里没找到 $panelClass")
            }
        }, 80L)
    }

    private fun switchToPhraseNow(panel: View) {
        runCatching {
            val m = panel.javaClass.getMethod("t", Boolean::class.javaPrimitiveType)
            m.isAccessible = true
            m.invoke(panel, true)
            LogUtils.i(TAG, "panel", "常用语切换完成（${panel.javaClass.name}.t(true)）")
        }.onFailure {
            LogUtils.w(TAG, "panel", "常用语切换失败：${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /** 深度优先查找指定类名的面板实例 */
    private fun findPanelByClass(view: View?, panelClass: String): View? {
        if (view == null) return null
        if (view.javaClass.name == panelClass) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findPanelByClass(view.getChildAt(i), panelClass)
                if (found != null) return found
            }
        }
        return null
    }
}