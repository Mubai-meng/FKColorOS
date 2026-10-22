package com.fr.colorostools.ref

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * 极简反射工具。
 *
 * 约定：所有 public 方法**都不抛受检异常**，失败返回 null / false —— 这样调用方
 * 不需要 try/catch，也不会因为宿主某个字段改名就把整条 hook 链带崩。
 */
object Reflect {

    // ------------------------------------------------------------ 反射缓存
    //
    // ART 上的反射查找并不便宜：
    // - `Class.forName` 走类加载器查找并**持类加载器锁**，未命中还要构造异常（填栈，最贵）；
    // - `getDeclaredMethods` **每次克隆整个方法数组**再线性扫描；
    // - `getDeclaredField` 未命中同样抛 `NoSuchFieldException`。
    //
    // 本模块的反射大量出现在宿主**热路径**（磁贴 draw / QS 布局 / 多任务过滤 / 返回手势），
    // 所以这里做一层进程内缓存：Android 不卸载类、键不变，
    // **命中与未命中都缓存** —— 未命中缓存掉的正是最贵的异常构造。

    private val NOT_FOUND = Any()

    private data class ClassKey(val loader: ClassLoader?, val fqn: String)

    private data class FieldKey(val cls: Class<*>, val name: String)

    private data class MethodKey(
        val cls: Class<*>,
        val name: String,
        val paramCount: Int,
        val types: String,
        val deep: Boolean,
    )

    private val classCache = ConcurrentHashMap<ClassKey, Any>()
    private val fieldCache = ConcurrentHashMap<FieldKey, Any>()
    private val methodCache = ConcurrentHashMap<MethodKey, Any>()

    fun classOrNull(loader: ClassLoader?, fqn: String): Class<*>? {
        val key = ClassKey(loader, fqn)
        val hit = classCache[key]
        if (hit != null) return if (hit === NOT_FOUND) null else hit as Class<*>
        val v: Any = try {
            Class.forName(fqn, false, loader)
        } catch (t: Throwable) {
            NOT_FOUND
        }
        classCache[key] = v
        return if (v === NOT_FOUND) null else v as Class<*>
    }

    fun simpleName(c: Class<*>?): String = c?.simpleName ?: "null"

    /** 逐级向上找字段（含私有）；结果（含"未找到"）进缓存 */
    fun findField(cls: Class<*>?, name: String): Field? {
        if (cls == null) return null
        val key = FieldKey(cls, name)
        val hit = fieldCache[key]
        if (hit != null) return if (hit === NOT_FOUND) null else hit as Field
        val v: Any = resolveField(cls, name) ?: NOT_FOUND
        fieldCache[key] = v
        return if (v === NOT_FOUND) null else v as Field
    }

    private fun resolveField(cls: Class<*>, name: String): Field? {
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            try {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                return f
            } catch (_: Throwable) {
            }
            c = c.superclass
        }
        return null
    }

    fun getField(target: Any?, name: String): Any? {
        if (target == null) return null
        return try {
            findField(target.javaClass, name)?.get(target)
        } catch (t: Throwable) {
            null
        }
    }

    fun setField(target: Any?, name: String, value: Any?): Boolean {
        if (target == null) return false
        return try {
            val f = findField(target.javaClass, name) ?: return false
            f.isAccessible = true
            f.set(target, value)
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** 找 实例/静态 方法，支持按参数个数 + 参数类型简单名过滤；结果（含"未找到"）进缓存 */
    fun findMethod(
        cls: Class<*>?,
        name: String,
        paramCount: Int = -1,
        paramSimpleNames: Map<Int, String> = emptyMap()
    ): Method? = findMethodCached(cls, name, paramCount, paramSimpleNames, deep = false)

    /**
     * 同 [findMethod]，但**会向上遍历父类**。
     *
     * 必需场景：协程续体（`Continuation` 的运行时实现类，如 `Repository$f$1`）
     * 的 `resumeWith(Object)` 是声明在父类 `BaseContinuationImpl` 上的，
     * 只在 `declaredMethods` 里找会漏掉。
     */
    fun findMethodDeep(
        cls: Class<*>?,
        name: String,
        paramCount: Int = -1,
        paramSimpleNames: Map<Int, String> = emptyMap()
    ): Method? = findMethodCached(cls, name, paramCount, paramSimpleNames, deep = true)

    private fun findMethodCached(
        cls: Class<*>?,
        name: String,
        paramCount: Int,
        paramSimpleNames: Map<Int, String>,
        deep: Boolean,
    ): Method? {
        if (cls == null) return null
        val key = MethodKey(cls, name, paramCount, typesKey(paramSimpleNames), deep)
        val hit = methodCache[key]
        if (hit != null) return if (hit === NOT_FOUND) null else hit as Method
        val found = if (deep) {
            resolveMethodDeep(cls, name, paramCount, paramSimpleNames)
        } else {
            resolveMethod(cls, name, paramCount, paramSimpleNames)
        }
        val v: Any = found ?: NOT_FOUND
        methodCache[key] = v
        return if (v === NOT_FOUND) null else v as Method
    }

    private fun resolveMethod(
        cls: Class<*>,
        name: String,
        paramCount: Int,
        paramSimpleNames: Map<Int, String>,
    ): Method? {
        for (m in cls.declaredMethods) {
            if (m.name != name) continue
            if (paramCount >= 0 && m.parameterCount != paramCount) continue
            var ok = true
            for ((idx, want) in paramSimpleNames) {
                val p = m.parameterTypes.getOrNull(idx)
                if (p == null || p.simpleName != want) {
                    ok = false
                    break
                }
            }
            if (!ok) continue
            runCatching { m.isAccessible = true }
            return m
        }
        return null
    }

    private fun resolveMethodDeep(
        cls: Class<*>,
        name: String,
        paramCount: Int,
        paramSimpleNames: Map<Int, String>,
    ): Method? {
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            resolveMethod(c, name, paramCount, paramSimpleNames)?.let { return it }
            c = c.superclass
        }
        return null
    }

    /** `paramSimpleNames` → 稳定的缓存键片段（空表直接短路，零分配） */
    private fun typesKey(m: Map<Int, String>): String {
        if (m.isEmpty()) return ""
        return m.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" }
    }

    /** 按「参数个数 + 每个位置的期望类型（可空表示任意）」找方法；比名字更抗混淆 */
    fun findMethodByShape(
        cls: Class<*>?,
        name: String?,
        expect: List<Class<*>?>,
        returnType: Class<*>? = null
    ): Method? {
        if (cls == null) return null
        for (m in cls.declaredMethods) {
            if (name != null && m.name != name) continue
            if (m.parameterTypes.size != expect.size) continue
            if (returnType != null && m.returnType != returnType) continue
            var ok = true
            for ((i, want) in expect.withIndex()) {
                if (want == null) continue
                if (!want.isAssignableFrom(m.parameterTypes[i]) && m.parameterTypes[i] != want) {
                    ok = false
                    break
                }
            }
            if (!ok) continue
            runCatching { m.isAccessible = true }
            return m
        }
        return null
    }

    /** 按「参数类型形状」找构造器；expect 中 null 表示该位置任意 */
    fun findConstructorByShape(cls: Class<*>?, expect: List<Class<*>?>): java.lang.reflect.Constructor<*>? {
        if (cls == null) return null
        for (c in cls.declaredConstructors) {
            if (c.parameterTypes.size != expect.size) continue
            var ok = true
            for ((i, want) in expect.withIndex()) {
                if (want == null) continue
                val actual = c.parameterTypes[i]
                if (actual != want && !want.isAssignableFrom(actual)) {
                    ok = false
                    break
                }
            }
            if (!ok) continue
            runCatching { c.isAccessible = true }
            return c
        }
        return null
    }

    fun newInstance(cls: Class<*>?, expect: List<Class<*>?>, vararg args: Any?): Any? {
        val c = findConstructorByShape(cls, expect) ?: return null
        return try {
            c.newInstance(*args)
        } catch (t: Throwable) {
            null
        }
    }

    // ---------------- 自适应构造：参数顺序无关、多余参数填默认值 ----------------

    /** 规格名 → 是否匹配实际参数类型的简单名（宽容基本型/装箱差异） */
    private fun specMatches(spec: String, paramSimple: String): Boolean = when (spec) {
        "long" -> paramSimple == "long" || paramSimple == "Long"
        "int" -> paramSimple == "int" || paramSimple == "Integer" || paramSimple == "Int"
        "short" -> paramSimple == "short" || paramSimple == "Short"
        "byte" -> paramSimple == "byte" || paramSimple == "Byte"
        "boolean" -> paramSimple == "boolean" || paramSimple == "Boolean"
        "float" -> paramSimple == "float" || paramSimple == "Float"
        "double" -> paramSimple == "double" || paramSimple == "Double"
        else -> paramSimple == spec
    }

    /** 给规格匹配不到的参数造一个安全默认值（null 也可能被 Kotlin 非空类型拒绝，所以先给类型默认） */
    private fun defaultFor(paramType: Class<*>): Any? = when (paramType.simpleName) {
        "long", "Long", "int", "Integer", "Int", "short", "Short", "byte", "Byte" -> 0
        "boolean", "Boolean" -> false
        "float", "Float" -> 0.0f
        "double", "Double" -> 0.0
        "char", "Char" -> ' '
        "String" -> ""
        "List", "ArrayList", "Collection", "Map" -> try { paramType.newInstance() } catch (t: Throwable) { null }
        else -> null
    }

    /**
     * 自适应构造：specs = (规格名, 值) 列表。在 [cls] 的构造器里找一个**每个规格都能匹配到
     * 一个互不重复的参数**的构造器（按类型匹配，顺序无关），其余参数填安全默认值。
     *
     * 用途：宿主 data class 的构造器签名随版本漂移（如 metis 的 Expression 不再是
     * `(long, String, List)`）——按类型组合匹配比写死顺序耐受得多。
     * 返回 null 时 [failLog] 会收到全部构造器签名，方便下轮日志定位。
     */
    fun newInstanceAdaptive(
        cls: Class<*>?,
        specs: List<Pair<String, Any?>>,
        failLog: (String) -> Unit,
        /**
         * 信息性提示通道（如「构造器多出参数已填默认」）——与真正的失败分开，
         * 避免成功路径上的适配提示被误报成 E 级错误（2026-10-15 日志降噪）。
         */
        infoLog: ((String) -> Unit)? = null,
    ): Any? {
        if (cls == null) return null
        val ctors = cls.declaredConstructors
        for (c in ctors) {
            runCatching { c.isAccessible = true }
            val pts = c.parameterTypes
            if (pts.size < specs.size) continue
            // 贪心：每个规格认领第一个未占用的同类型参数位
            val slot = IntArray(specs.size) { -1 }
            val used = BooleanArray(pts.size)
            var allMatch = true
            for ((si, spec) in specs.withIndex()) {
                var found = false
                for (pi in pts.indices) {
                    if (used[pi]) continue
                    if (specMatches(spec.first, pts[pi].simpleName)) {
                        slot[si] = pi; used[pi] = true; found = true; break
                    }
                }
                if (!found) { allMatch = false; break }
            }
            if (!allMatch) continue

            val args = arrayOfNulls<Any>(pts.size)
            val filled = mutableListOf<String>()
            for ((si, spec) in specs.withIndex()) {
                val v = spec.second
                val pt = pts[slot[si]]
                args[slot[si]] = coerce(v, pt)
            }
            for (pi in pts.indices) {
                if (!used[pi]) {
                    args[pi] = defaultFor(pts[pi])
                    filled.add("$pi:${pts[pi].simpleName}")
                }
            }
            return try {
                c.newInstance(*args).also {
                    if (filled.isNotEmpty()) {
                        (infoLog ?: failLog)("提示: ${cls.simpleName} 构造器多出参数已填默认 $filled（签名=(${pts.joinToString { it.simpleName }})）")
                    }
                }
            } catch (t: Throwable) {
                failLog("${cls.simpleName} 自适应构造调用失败 ctor=(${pts.joinToString { it.simpleName }}) err=${t.message}")
                null
            }
        }
        failLog(
            "${cls.simpleName} 无匹配构造器（specs=${specs.joinToString(",") { it.first }}）；" +
                "全部签名 → [" + ctors.joinToString { c ->
                    "(" + c.parameterTypes.joinToString { it.simpleName } + ")"
                } + "]"
        )
        return null
    }

    /** 把值转成构造器参数需要的装箱类型（反射 invoke 时 int 参数给 Integer、float 给 Float 等） */
    private fun coerce(v: Any?, paramType: Class<*>): Any? {
        if (v == null) return null
        return when (paramType.simpleName) {
            "int", "Integer", "Int" -> (v as? Number)?.toInt() ?: v
            "long", "Long" -> (v as? Number)?.toLong() ?: v
            "float", "Float" -> (v as? Number)?.toFloat() ?: v
            "double", "Double" -> (v as? Number)?.toDouble() ?: v
            "short", "Short" -> (v as? Number)?.toShort() ?: v
            "byte", "Byte" -> (v as? Number)?.toByte() ?: v
            "boolean", "Boolean" -> v
            else -> v
        }
    }

    /**
     * `sun.misc.Unsafe.allocateInstance`：绕过构造器直接分配实例（Gson 对无无参构造的
     * data class 就是这么做的）。宿主没有 Gson 时的兜底——造出全 null/0 的对象，
     * 再由调用方用 setter 填字段。失败返回 null。
     */
    /** `sun.misc.Unsafe` 实例与其 `allocateInstance` 方法：只解析一次 */
    private val unsafeAlloc: Pair<Any, Method>? by lazy {
        runCatching {
            val unsafeCls = Class.forName("sun.misc.Unsafe")
            val f = unsafeCls.getDeclaredField("theUnsafe")
            f.isAccessible = true
            val unsafe = f.get(null)!!
            val m = unsafeCls.getMethod("allocateInstance", Class::class.java)
            m.isAccessible = true
            unsafe to m
        }.getOrNull()
    }

    fun allocateInstance(cls: Class<*>?): Any? {
        if (cls == null) return null
        val (unsafe, m) = unsafeAlloc ?: return null
        return try {
            m.invoke(unsafe, cls)
        } catch (t: Throwable) {
            null
        }
    }


    fun call(target: Any?, method: Method?, vararg args: Any?): Any? {
        if (target == null || method == null) return null
        return try {
            method.invoke(target, *args)
        } catch (t: Throwable) {
            null
        }
    }

    /** 按名字调用（第一个参数个数匹配的重载） */
    fun callByName(target: Any?, name: String, vararg args: Any?): Any? {
        if (target == null) return null
        val m = findMethod(target.javaClass, name, args.size) ?: return null
        return call(target, m, *args)
    }

    /**
     * 按名字调用，**向上遍历父类**（父类声明的方法专用）。
     *
     * 必需场景：`Task` 只声明 `prepareSurfaces()`，`getSurfaceControl()` / `getSyncTransaction()`
     * 声明在父类 `WindowContainer`，`Reflect.callByName` 只查 `declaredMethods` → 恒定 null
     * （v1.6.15 实测：贴边「保住画面」因拿到 null 而从未生效 ⇒ 抖音停声）。
     */
    fun callDeep(target: Any?, name: String, vararg args: Any?): Any? {
        if (target == null) return null
        val m = findMethodDeep(target.javaClass, name, args.size) ?: return null
        return call(target, m, *args)
    }

    fun callStatic(cls: Class<*>?, name: String, vararg args: Any?): Any? {
        if (cls == null) return null
        val m = findMethod(cls, name, args.size) ?: return null
        return try {
            m.invoke(null, *args)
        } catch (t: Throwable) {
            null
        }
    }

    /** 取枚举常量（按名字），比 valueOf 更抗异常 */
    fun enumValue(cls: Class<*>?, name: String): Any? {
        if (cls == null) return null
        return try {
            if (cls.isEnum) {
                cls.enumConstants?.firstOrNull { (it as Enum<*>).name == name }
            } else {
                val f = findField(cls, name)
                f?.get(null)
            }
        } catch (t: Throwable) {
            null
        }
    }

    fun isStatic(m: Method): Boolean = Modifier.isStatic(m.modifiers)

    fun describe(o: Any?): String = when (o) {
        null -> "null"
        is Class<*> -> o.name
        else -> runCatching { o.toString() }.getOrElse { "${o.javaClass.name}@${Integer.toHexString(System.identityHashCode(o))}" }
    }
}
