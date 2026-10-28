package com.fr.colorostools.hook.scopes.opluskeyboard

import com.fr.colorostools.hook.HookEnv
import com.fr.colorostools.ref.Reflect
import com.fr.colorostools.utils.LogUtils
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 功能：**强制启用小布输入法（`com.oplus.keyboard`）的液态玻璃模糊**。
 *
 * 移植来源：上游 LuckyTool `hook/scopes/opluskeyboard/ForceEnableOplusKeyboardBlur.kt`
 * （`origin/main` 分支）。上游用 YukiHookAPI + KavaRef + DexKit 定位，本模块是 libxposed
 * 原生实现，故改写为「直接反射 + DexKit 兜底」两层定位，逻辑等价。
 *
 * ## 宿主原本的模糊生效条件（1.8.35.17 逆向实证）
 *
 * 键盘里所有模糊渲染分支（容器背景模糊 delegate、各 view 在 `*Blur` 资源之间的二选一、
 * `ImeService` 的窗口标记）**收敛到唯一总闸** `com.oplus.keyboard.base.util.h#b()`：
 *
 * ```java
 * public static boolean b() {          // 模糊总闸
 *     return (oplusOSVersion >= 40)
 *         && (LiveData f14330a == TRUE)   // 系统「质感模糊」开关
 *         && f14332c                        // 当前应用命中白名单（input/manager/k 里赋值）
 *         && c();                           // 主题条件
 * }
 * public static boolean c() {          // 主题条件
 *     return themeManager.q()                                  // 内置默认主题
 *         && themeUtil.f.a() == DayNightMode.MODE_FOLLOW_SYS;  // 跟随系统深浅
 * }
 * ```
 *
 * 四个条件里 `>= 40` 在 ColorOS 17 天然成立；剩下三个都要改：
 * - `c()` —— 主题条件（用户若用第三方主题 / 固定深浅色，宿主就不给模糊）；
 * - `f14332c` —— 输入应用白名单（宿主只对 `com.heytap.quicksearchbox`、`com.android.contacts`、
 *   `com.android.settings` 等 8 个应用开模糊，其它应用一律没有）；
 * - LiveData `f14330a` —— 系统「质感模糊」开关（`ImeApp` 启动时一次性灌入）。
 *
 * ## 改哪几处（本实现）
 *
 * | # | 目标 | 手法 | 理由 |
 * |---|---|---|---|
 * | 1 | `base.util.h#b()` | 恒返回 true | 总闸，一改全通；返回值由宿主自己算，不做部分放行 |
 * | 2 | `base.util.h#c()` | 恒返回 true | `input/manager/k` 用它算「夜间也开模糊」的 `themeUtil.f.f14134b`，不改则窗口标志不推送 |
 * | 3 | `base.data.e#i()` | 恒返回 true | 系统「质感模糊」开关的读取方法，`ImeApp#onCreate` 用它灌 LiveData |
 * | 4 | `Settings.System#getInt` 等 | `system_material_blur_enable` → 1 | `e#i()` 之外还有 `C1089u` 直接读原值注册监听 |
 * | 5 | `Settings.Global#getInt` 等 | `disable_window_blurs` → 0 | 框架侧在宿主进程内自己再读一遍，宿主被改后的判断挡不住 |
 * | 6 | `ViewRootManager#setBlurRadius(int)` | 正数替换成配置值 | 模糊强度的唯一入口；保留 0 的「关闭」语义 |
 *
 * ## 为什么必须改第 5 项（非多余）
 * 模糊 drawable 由框架 `com.oplus.view.ViewRootManager` 在**宿主进程内**按系统设置决定：
 * 系统「质感模糊」关着或全局禁用窗口模糊时 `getBackgroundBlurDrawable()` 直接返回 null，
 * 宿主拿不到 drawable 就静默不画（**无异常、无日志**，极难排查）。
 *
 * ## 定位方式：直接类名优先 + DexKit 特征兜底
 * 上游全用 DexKit 是因为 YukiHookAPI 的类名引用不便。本模块可以直接 `Class.forName`，
 * 所以先用实证类名（宿主 1.8.35.17：`base.util.h`、`base.data.e`），失败或方法形状不符
 * 才走 DexKit 按字符串特征找（`key_day_night_mode` / `system_material_blur_enable` + `SettingsPrefs`）。
 * 两条路都失败只记 W，不影响下滑快捷键等其它功能。
 *
 * ## 刻意不碰的两处（与上游一致）
 * - `utils.h#j()` 折叠判断 —— 被分屏键盘、悬浮工具栏复用，`b()` 拦截后已无需它；
 * - `reflect.a#v()` 版本号 —— 还喂给 appcompat、应用更新等处，改它风险大于收益。
 *
 * ## 兜底原则
 * 每个 hook 独立 `runCatching`；任何一处失败只记日志。所有拦截体内部再包一层 try/catch，
 * 异常时退回 `chain.proceed()` —— 输入法是高频前台应用，绝不能因本模块崩。
 */
internal object ForceEnableOplusKeyboardBlur {

    private const val TAG = "OplusKbBlur"

    /** 宿主包名 */
    private const val PKG = "com.oplus.keyboard"

    /** 模糊总闸类（1.8.35.17 实证名；jadx 因类名大小写冲突显示为 AbstractC0818h） */
    private const val CLS_GATE = "com.oplus.keyboard.base.util.h"

    /** 系统「质感模糊」开关读取类（`i()` 方法） */
    private const val CLS_SETTINGS_DATA = "com.oplus.keyboard.base.data.e"

    /** 模糊半径入口（在 `oplus-framework.jar` 里，不受宿主混淆影响） */
    private const val CLS_VIEW_ROOT_MANAGER = "com.oplus.view.ViewRootManager"

    /** 系统「质感模糊」总开关 */
    private const val KEY_MATERIAL_BLUR = "system_material_blur_enable"

    /** 全局禁用窗口模糊 */
    private const val KEY_DISABLE_WINDOW_BLURS = "disable_window_blurs"

    /** 宿主自己请求的模糊半径，作为自定义项的默认值 */
    const val DEFAULT_BLUR_RADIUS = 150

    /** 自定义模糊半径的上下限（与设置页滑条一致） */
    const val MIN_BLUR_RADIUS = 50
    const val MAX_BLUR_RADIUS = 500

    /** 诊断日志一次性标记（避免高频刷屏） */
    private val gateHitLogged = AtomicBoolean(false)
    private val drawableLogged = AtomicBoolean(false)

    /** 上一次记录的请求半径（只在变化时打日志） */
    @Volatile
    private var lastLoggedRadius = Int.MIN_VALUE

    /** 已装标记（重复 install 直接跳过） */
    private val installed = AtomicBoolean(false)

    // ------------------------------------------------------------------ 安装

    fun install() {
        if (!installed.compareAndSet(false, true)) {
            LogUtils.i(TAG, "install", "已安装过，跳过")
            return
        }
        var ok = 0
        if (runCatching { hookGate() }.getOrDefault(false)) ok++
        if (runCatching { hookMaterialBlurSetting() }.getOrDefault(false)) ok++
        if (runCatching { hookSystemSettings() }.getOrDefault(false)) ok++
        if (runCatching { hookBlurRadius() }.getOrDefault(false)) ok++

        if (ok == 0) {
            LogUtils.w(TAG, "install", "四个注入点全部失败（宿主实现已改变？看上方逐项日志）")
        } else {
            LogUtils.i(TAG, "install", "模糊增强已装 $ok/4（强度 ${blurRadius()}）")
        }
    }

    // ------------------------------------------------------------------ 1&2. 模糊总闸

    /**
     * hook 总闸类里的**全部「无参 + 返回 boolean」静态方法**（即 `b()` 与 `c()`）。
     *
     * 为什么不按方法名精确挂：这两个方法名都是混淆后的单字母，ROM 一改就错位；
     * 而「该类里的无参 boolean 方法」恰好只有这两个（实证 `a()` 返回 int、`d()` void、
     * `e(int)` 返回 int），所以按形状挂既准又抗版本变化。
     */
    private fun hookGate(): Boolean {
        val cls = resolveGateClass()
        if (cls == null) {
            LogUtils.w(TAG, "hookGate", "总闸类定位失败（直接类名 + DexKit 均未命中）")
            return false
        }
        var hooked = 0
        for (m in cls.declaredMethods) {
            if (!isNoArgBooleanStatic(m)) continue
            m.isAccessible = true
            runCatching {
                HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        // 解释：总闸恒开。异常时原样下放，绝不让宿主受影响。
                        return runCatching {
                            if (gateHitLogged.compareAndSet(false, true)) {
                                LogUtils.i(TAG, "gate", "模糊总闸被调用并放行（${m.name}）")
                            }
                            true
                        }.getOrElse { chain.proceed() }
                    }
                })
                hooked++
            }.onFailure {
                LogUtils.w(TAG, "hookGate", "挂 ${cls.name}#${m.name} 失败：${it.message}")
            }
        }
        if (hooked == 0) {
            LogUtils.w(TAG, "hookGate", "${cls.name} 里没有无参 boolean 方法（形状已变）")
            return false
        }
        LogUtils.i(TAG, "hookGate", "总闸已挂 $hooked 个方法：${cls.name}")
        return true
    }

    /**
     * 定位总闸类：先试实证类名，再用 DexKit 按特征兜底。
     *
     * DexKit 兜底的两条特征（对齐上游，已按 1.8.35.17 实证修正）：
     * 1. 旧版本 `c()` 自己就用 `key_day_night_mode` ⇒ 找「无参 + boolean + 用该字符串」；
     * 2. 1.8.35.17 起该字符串被挪进 `base.theme.util.f$a#a()` ⇒ 找「无参 + boolean +
     *    调用了 `a()`」的方法。
     */
    private fun resolveGateClass(): Class<*>? {
        // 路径一：实证类名（快，且绝大多数情况够用）
        val direct = Reflect.classOrNull(HookEnv.classLoader, CLS_GATE)
        if (direct != null && direct.declaredMethods.any { isNoArgBooleanStatic(it) }) {
            return direct
        }
        // 路径二：DexKit 特征兜底
        return runCatching {
            val sourceDir = HookEnv.context()?.applicationInfo?.sourceDir ?: return@runCatching null
            var className: String? = null
            com.fr.colorostools.utils.DexkitUtils.create(sourceDir) { bridge ->
                // 旧特征：c() 自己使用 key_day_night_mode
                val oldGate = bridge.findMethod {
                    matcher {
                        paramCount(0)
                        returnType(Boolean::class.java)
                        usingStrings("key_day_night_mode")
                    }
                }
                className = if (oldGate.isNotEmpty()) {
                    oldGate.first().className
                } else {
                    // 新特征：谁调用了「读 key_day_night_mode 的无参方法」
                    val getter = bridge.findMethod {
                        matcher {
                            paramCount(0)
                            usingStrings("key_day_night_mode")
                        }
                    }.firstOrNull()
                    if (getter == null) {
                        null
                    } else {
                        bridge.findMethod {
                            matcher {
                                paramCount(0)
                                returnType(Boolean::class.java)
                                addInvoke {
                                    paramCount(0)
                                    name(getter.methodName)
                                    declaredClass(getter.className)
                                }
                            }
                        }.firstOrNull()?.className
                    }
                }
            }
            val name = className
            if (name == null) {
                null
            } else {
                LogUtils.i(TAG, "resolveGateClass", "DexKit 兜底命中：$name")
                Reflect.classOrNull(HookEnv.classLoader, name)
            }
        }.getOrNull()
    }

    // ------------------------------------------------------------------ 3. 系统「质感模糊」开关

    /**
     * hook `base.data.e#i()`（读 `system_material_blur_enable` 的唯一方法）。
     *
     * `ImeApp#onCreate` 会用它灌 LiveData `f14330a`，而该 LiveData 是总闸条件之一。
     * 定位：先试类名 + 方法形状（无参 static boolean，且与 `Settings` 相关），
     * 失败再走 DexKit 的 `system_material_blur_enable` + `SettingsPrefs` 双字符串特征。
     */
    private fun hookMaterialBlurSetting(): Boolean {
        val method = findMaterialBlurMethod()
        if (method == null) {
            LogUtils.w(TAG, "hookMaterialBlurSetting", "系统质感模糊开关读取方法定位失败")
            return false
        }
        method.isAccessible = true
        return runCatching {
            HookEnv.module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? =
                    runCatching { true }.getOrElse { chain.proceed() }
            })
            LogUtils.i(
                TAG, "hookMaterialBlurSetting",
                "已挂 ${method.declaringClass.name}#${method.name}（恒 true，驱动 LiveData）",
            )
            true
        }.onFailure {
            LogUtils.w(TAG, "hookMaterialBlurSetting", "挂载失败：${it.message}")
        }.getOrDefault(false)
    }

    private fun findMaterialBlurMethod(): Method? {
        // 路径一：实证类名
        Reflect.classOrNull(HookEnv.classLoader, CLS_SETTINGS_DATA)
            ?.declaredMethods
            ?.firstOrNull { isNoArgBooleanStatic(it) && it.name == "i" }
            ?.let { return it }
        // 路径二：DexKit 双字符串特征（唯一性最好）
        return runCatching {
            val sourceDir = HookEnv.context()?.applicationInfo?.sourceDir ?: return@runCatching null
            var found: Method? = null
            com.fr.colorostools.utils.DexkitUtils.create(sourceDir) { bridge ->
                val data = bridge.findMethod {
                    matcher {
                        paramCount(0)
                        returnType(Boolean::class.java)
                        usingStrings(KEY_MATERIAL_BLUR, "SettingsPrefs")
                    }
                }.firstOrNull()
                if (data != null) {
                    val cls = Reflect.classOrNull(HookEnv.classLoader, data.className)
                    found = cls?.declaredMethods?.firstOrNull {
                        it.name == data.methodName && it.parameterCount == 0
                    }
                }
            }
            found
        }.getOrNull()
    }

    // ------------------------------------------------------------------ 4. 系统设置读取

    /**
     * 把进程内对两个系统设置键的读取固定成指定值。
     *
     * 只按 key 精确匹配（第二个参数），其它键原样返回。挂 `getInt` / `getIntForUser` 的全部重载。
     *
     * 为什么还要改这里：`e#i()` 之外，`input/view/container/C1089u` 直接
     * `Settings.System.getUriFor("system_material_blur_enable")` 注册监听并读原值；
     * 框架侧 `ViewRootManager` 也读 `disable_window_blurs`。改方法返回值可同时覆盖这两条。
     */
    private fun hookSystemSettings(): Boolean {
        val targets = listOf(
            "android.provider.Settings\$System" to (KEY_MATERIAL_BLUR to 1),
            "android.provider.Settings\$Global" to (KEY_DISABLE_WINDOW_BLURS to 0),
        )
        var hooked = 0
        for ((clsName, keyAndValue) in targets) {
            val (key, value) = keyAndValue
            val cls = Reflect.classOrNull(HookEnv.classLoader, clsName) ?: continue
            for (m in cls.declaredMethods) {
                if (m.name != "getInt" && m.name != "getIntForUser") continue
                // getInt(ContentResolver, String[, int]) / getIntForUser(ContentResolver, String, int)
                if (m.parameterTypes.size < 2) continue
                if (m.parameterTypes[1] != String::class.java) continue
                m.isAccessible = true
                runCatching {
                    HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            return runCatching {
                                if (chain.args.getOrNull(1) == key) value else chain.proceed()
                            }.getOrElse { chain.proceed() }
                        }
                    })
                    hooked++
                }.onFailure {
                    LogUtils.w(TAG, "hookSystemSettings", "挂 $clsName#${m.name} 失败：${it.message}")
                }
            }
        }
        if (hooked > 0) {
            LogUtils.i(TAG, "hookSystemSettings", "系统设置读取已固定 $hooked 个重载")
        }
        return hooked > 0
    }

    // ------------------------------------------------------------------ 5. 模糊强度

    /**
     * hook `com.oplus.view.ViewRootManager#setBlurRadius(int)`：宿主请求**正数**时替换成配置值。
     *
     * 保留 0 的「关闭」语义（宿主关模糊时仍传 0），这样用户关掉开关的行为完全回到原生。
     */
    private fun hookBlurRadius(): Boolean {
        val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_VIEW_ROOT_MANAGER)
        if (cls == null) {
            LogUtils.w(TAG, "hookBlurRadius", "$CLS_VIEW_ROOT_MANAGER 不存在（oplus-framework 版本变了？）")
            return false
        }
        val radius = blurRadius()
        var hooked = 0
        for (m in cls.declaredMethods) {
            if (m.name != "setBlurRadius") continue
            if (m.parameterTypes.size != 1 || m.parameterTypes[0] != Int::class.java) continue
            m.isAccessible = true
            runCatching {
                HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        return runCatching {
                            val requested = chain.args.getOrNull(0) as? Int ?: return@runCatching chain.proceed()
                            if (requested != lastLoggedRadius) {
                                lastLoggedRadius = requested
                                LogUtils.i(TAG, "blurRadius", "宿主请求半径 $requested")
                            }
                            if (requested > 0 && requested != radius) {
                                val args = chain.args.toTypedArray()
                                args[0] = radius
                                chain.proceed(args)
                            } else {
                                chain.proceed()
                            }
                        }.getOrElse { chain.proceed() }
                    }
                })
                hooked++
            }.onFailure {
                LogUtils.w(TAG, "hookBlurRadius", "挂 setBlurRadius 失败：${it.message}")
            }
        }
        // 诊断：框架是否真给了模糊 drawable（null = 框架层拒绝，与宿主逻辑无关）
        for (m in cls.declaredMethods) {
            if (m.name != "getBackgroundBlurDrawable") continue
            runCatching {
                m.isAccessible = true
                HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val r = chain.proceed()
                        if (drawableLogged.compareAndSet(false, true)) {
                            LogUtils.i(TAG, "drawable", "getBackgroundBlurDrawable → ${r?.javaClass?.simpleName ?: "null"}")
                        }
                        return r
                    }
                })
            }
        }
        if (hooked > 0) LogUtils.i(TAG, "hookBlurRadius", "模糊强度已接管，目标值 $radius")
        return hooked > 0
    }

    // ------------------------------------------------------------------ 工具

    private fun isNoArgBooleanStatic(m: Method): Boolean =
        m.parameterCount == 0 &&
            m.returnType == Boolean::class.javaPrimitiveType &&
            java.lang.reflect.Modifier.isStatic(m.modifiers)

    /** 当前配置的模糊半径（remote prefs 内存读，无 IO） */
    private fun blurRadius(): Int = HookEnv
        .settingInt(OplusKeyboardConfig.KEY_BLUR_RADIUS, DEFAULT_BLUR_RADIUS)
        .coerceIn(MIN_BLUR_RADIUS, MAX_BLUR_RADIUS)
}