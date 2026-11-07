package com.fr.colorostools.hook.hookers

import android.content.Context
import android.widget.FrameLayout
import com.fr.colorostools.hook.HookEnv
import com.fr.colorostools.ref.Reflect
import com.fr.colorostools.utils.LogUtils
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method

/**
 * 功能：文件管理 App（`com.coloros.filemanager`）**隐藏安全访问提示**。
 *
 * 移植来源：ONextBox（GPL-3.0）`lsp/FileManagerHooker.kt` + `lsp/FileManagerCardRules.kt`。
 *
 * ## 行为
 *
 * 文件选择页顶部有一张「安全访问提示」卡片（选择文件时提示「已开启安全访问」之类）。
 * 宿主里这张卡片的可见性判定是一个返回 `boolean` 的方法，形如
 * `l(FragmentActivity, boolean)Z`；返回 `false` 时宿主自己的分支就是
 * 「移除卡片并把上边距 reSet 回 0」—— **宿主官方关闭路径**，不是把 View 藏起来，
 * 所以不会留下空白占位。
 *
 * ## 为什么用「形状」而不是硬编码方法名
 *
 * 文件管理器被 R8 混淆，方法名（`l` / `c`）与类名（`hn.d` / `in.d`）都随版本变，
 * 但**参数签名是稳定的**（`FragmentActivity` + `boolean`，返回 `boolean`）。
 * 因此定位分两层：
 *
 * 1. 先按「名字 + 精确形状」在候选 helper 类里找；
 * 2. 找不到时退化为「形状唯一匹配」—— 不看名字，只看该类里只有一个
 *    `(FragmentActivity, boolean)Z` 的方法时才用（多于一个说明形状不足以定位，
 *    宁可不装也不乱改）。
 *
 * 卡片构建方法 `c(ViewGroup, FragmentActivity, boolean, bz.l)` 只用作**交叉校验**
 * （确认这个 helper 类确实在管这张卡片）；找不到只记日志、不阻断安装 ——
 * 少一个校验点总比多一个失败点好，真正的安全性由「形状唯一」保证。
 *
 * ## 2026-11-07 修复记录（本机 文件管理 17.10.0 实证）
 *
 * 该功能移植后一直无效，逆向确认为**两个独立缺陷叠加**：
 *
 * 1. **类名失配**：本版 helper 是 `hn.d`，而 `in.d` 变成了**卡片 View 本身**
 *    （`extends FrameLayout`，方法里只有 `onTouchEvent` / `getAppName` /
 *    `setOnLearnMoreClickListener`，没有 `l`）⇒ 旧代码拿到 `in.d` 后形状校验失败、
 *    静默跳过。现候选表改为 `hn.d` / `in.d` / `jn.d`，逐个试形状而不是「第一个存在的类」。
 * 2. **参数类型按 Class 身份比较**：模块 APK 自带 `androidx.fragment`
 *    （`BiometricUtils` 用到），模块类加载器里的 `FragmentActivity` 与宿主里的
 *    不是同一个 Class 对象，`parameterTypes[0] == FragmentActivity::class.java`
 *    恒为 false ⇒ 即使类名对也匹配不上。现一律按 `parameterTypes[i].name` 比较。
 *
 * 实测签名（dexsql 反汇编）：
 * - `hn.d#l(FragmentActivity,Z)Z` —— 28 opcode；`instance-of PickerActivity` → 否则 false；
 *   参数 boolean 为 false → false；否则取 `KtAppUtils.t(activity)` 的包名，非空即 true，
 *   并打日志 `shouldShowCard=<bool> package=<pkg>`（tag `PickerSecureAccessHelper`）。
 *   返回 false 就是「不显示卡片」，与宿主自己的关闭分支一致。
 * - 全 APK 只有这一个 `(FragmentActivity,boolean)Z`，形状唯一性成立。
 *
 * ## 覆盖边界
 *
 * 只影响文件选择页那张提示卡片；文件访问权限本身、安全访问的 SELinux / FUSE 判定
 * 全部不动（本功能是纯 UI 层的「不显示提示」，不含任何提权）。
 */
internal object FileManagerHook {

    private const val TAG = "FileManager"

    /** 「隐藏安全访问提示」开关（App 侧 `SettingsPrefs` 同名键） */
    const val KEY_HIDE_SECURE_TIP = "file_manager_hide_secure_access_tip"

    /**
     * 卡片 helper 类候选（宿主混淆名，按命中概率排序）。
     *
     * 2026-11-07 用本机 `文件管理_17.10.0.apk` 实证（dexsql）：
     * - helper = **`hn.d`**：`l(FragmentActivity,Z)Z`（28 opcode，内部打
     *   `shouldShowCard=` 日志）与 `c(ViewGroup,FragmentActivity,Z,Lbz/l;)Lin/d;`（卡片工厂）；
     * - 卡片 = **`in.d`**（`extends FrameLayout`，用 `secure_access_banner_desc`）；
     * - 全 APK 只有 `hn.d#l` 一个 `(FragmentActivity,boolean)Z`，形状唯一。
     *
     * ⚠️ 上游（Find X9 Ultra）的 `in.d` 在本版是**卡片 View 本身**，根本没有 `l` 方法 ——
     * 这正是本功能此前失效的原因：只认 `in.d` 会在形状校验处静默跳过。
     *
     * 顺序只影响尝试成本：每个候选都必须**形状命中**才会被采用，全不中则整功能跳过。
     */
    private val HELPER_CLASSES = listOf("hn.d", "in.d", "jn.d")

    /**
     * 参数类型必须按**全限定名**比较，不能按 Class 身份比较。
     *
     * 模块 APK 自带 `androidx.fragment`（`BiometricUtils` 用到），模块类加载器里的
     * `FragmentActivity` 与宿主里的**不是同一个 Class 对象** ⇒
     * `parameterTypes[0] == FragmentActivity::class.java` 恒 false，形状永远匹配不上。
     */
    private const val FRAGMENT_ACTIVITY = "androidx.fragment.app.FragmentActivity"

    private const val VIEW_GROUP = "android.view.ViewGroup"

    fun install() {
        val loader = HookEnv.classLoader

        // 逐个候选试形状：类存在 ≠ 它是 helper（17.10.0 的 in.d 是卡片 View，没有 l 方法）
        val target = run {
            for (name in HELPER_CLASSES) {
                val cls = Reflect.classOrNull(loader, name) ?: continue
                val method = findDisplayPredicate(cls) ?: continue
                return@run cls to method
            }
            null
        }
        if (target == null) {
            LogUtils.w(
                TAG, "install",
                "候选 ${HELPER_CLASSES.joinToString()} 都没有 ($FRAGMENT_ACTIVITY, boolean)Z 形状的可见性判定，" +
                    "该版本混淆名可能已变，跳过隐藏安全访问提示",
            )
            return
        }
        val helper = target.first
        val predicate = target.second

        // 交叉校验：同一 helper 里应有返回卡片 View 的工厂方法。找不到不阻断，仅提示。
        val factory = findCardFactory(helper)
        if (factory == null) {
            LogUtils.w(
                TAG, "install",
                "${helper.name} 里没找到卡片工厂 (ViewGroup, $FRAGMENT_ACTIVITY, boolean) -> FrameLayout，按形状继续安装",
            )
        }

        runCatching {
            HookEnv.module.hook(predicate).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    if (!hideSecureTip()) return chain.proceed()
                    // 只在文件管理自己的进程里生效（防御：同签名方法被别处复用时不误伤）
                    val ctx = chain.args.firstOrNull() as? Context
                    if (ctx != null && ctx.packageName != HookEnv.PKG_FILE_MANAGER) return chain.proceed()
                    // 原生 false 分支 = 移除卡片 + 重置 top inset，是宿主自带的关闭路径
                    return false
                }
            })
            LogUtils.i(
                TAG, "install",
                "已挂 ${helper.name}#${predicate.name}（隐藏安全访问提示=${hideSecureTip()}，" +
                    "卡片=${factory?.returnType?.name ?: "未知"}）",
            )
        }.onFailure {
            LogUtils.w(TAG, "install", "挂可见性判定失败：${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /**
     * 找到「安全访问提示卡片是否显示」的判定方法。
     *
     * 第一层：名字 `l` + 精确形状（17.10.0 实证签名）；
     * 第二层：形状唯一匹配（抗改名），多义时放弃。
     */
    private fun findDisplayPredicate(helper: Class<*>): Method? {
        val shaped = helper.declaredMethods.filter(::looksLikePredicate)

        val named = shaped.filter { it.name == "l" }
        if (named.size == 1) return named.first().also { it.isAccessible = true }
        if (named.size > 1) {
            LogUtils.w(TAG, "install", "${helper.name} 里名为 l 的同形状方法有 ${named.size} 个，形状不足以定位，放弃")
            return null
        }

        if (shaped.size == 1) {
            val m = shaped.first()
            LogUtils.i(TAG, "install", "按形状兜底命中 ${helper.name}#${m.name}（名字与上游不同）")
            m.isAccessible = true
            return m
        }
        return null
    }

    /** 形状判定：`(FragmentActivity, boolean) -> boolean`，参数类型按**名字**比（见 [FRAGMENT_ACTIVITY] 注释） */
    private fun looksLikePredicate(m: Method): Boolean =
        m.returnType == Boolean::class.javaPrimitiveType &&
            m.parameterCount == 2 &&
            m.parameterTypes[0].name == FRAGMENT_ACTIVITY &&
            m.parameterTypes[1] == Boolean::class.javaPrimitiveType

    /** 卡片工厂：`(ViewGroup, FragmentActivity, boolean, ?) -> FrameLayout 子类`，只作交叉校验 */
    private fun findCardFactory(helper: Class<*>): Method? = helper.declaredMethods.firstOrNull { m ->
        m.parameterCount == 4 &&
            m.parameterTypes[0].name == VIEW_GROUP &&
            m.parameterTypes[1].name == FRAGMENT_ACTIVITY &&
            m.parameterTypes[2] == Boolean::class.javaPrimitiveType &&
            FrameLayout::class.java.isAssignableFrom(m.returnType)
    }

    private fun hideSecureTip(): Boolean = HookEnv.settingBoolean(KEY_HIDE_SECURE_TIP, false)
}

/**
 * 功能：文件选择器（picker）路由 —— 两个互斥方向，都在 **system_server** 里改。
 *
 * 移植来源：ONextBox（GPL-3.0）`lsp/NativeFilePickerHooker.kt` +
 * `lsp/DefaultFileManagerHooker.kt` + `lsp/DefaultFileManagerRouting.kt` +
 * `lsp/DefaultFileManagerSettings.kt`。
 *
 * ## 两个开关为什么都在 system_server
 *
 * ColorOS 把「谁来响应 `ACTION_GET_CONTENT` / `ACTION_OPEN_DOCUMENT`」的裁决放在
 * framework 侧，而不在文件管理器 App 里：
 *
 * | 开关 | hook 点 | 作用 |
 * |---|---|---|
 * | 使用 MT 管理器为选择器 | `ActivityStarter#execute()`、`ActivityStarter$Request#resolveActivity(ActivityTaskSupervisor)` | 把文件类 Intent 的**目标组件**换成 `bin.mt.plus` |
 * | 使用原生文件选择器 | `com.android.server.pm.OplusDefaultAppPolicyManager#interceptPickerIntent(Intent, List)` | 把「已改写到 ColorOS 文件管理器」的结果**退回**系统原生 documentsui |
 *
 * 前者是「主动改写」（覆盖所有入口：选择文件、打开文件夹、`CATEGORY_APP_FILES`、文件夹 `VIEW`），
 * 后者是「把 OEM 的默认选择器替换撤销掉」。两者同时开启时 MT 优先（`interceptPickerIntent`
 * 阶段还会再判一次）。
 *
 * ## 本机（ColorOS 17 / OnePlus 15）逆向复核
 *
 * services.jar + oplus-services.jar（dexsql 查询，2026-11-07）：
 * - `com.android.server.pm.OplusDefaultAppPolicyManager#interceptPickerIntent(Intent, List)` 存在，
 *   129 opcode（非 bridge）；**该类在 oplus-services.jar**（运行时并入 bootclasspath，system_server 可见）。
 * - `com.android.server.wm.ActivityStarter#execute()V` 存在（1196 opcode，未被完全内联）；
 * - `com.android.server.wm.ActivityStarter$Request#resolveActivity(ActivityTaskSupervisor)V` 存在（362 opcode）；
 * - `ActivityStarter` 字段 `mRequest` / `mService` 均在；
 * - `ActivityStarter$Request` 字段 `intent` / `userId` / `resolvedType` / `callingPackage` /
 *   `activityInfo` / `resolveInfo` / `intentGrants` 全部命中。
 *
 * ## 为什么必须 deoptimize
 *
 * ART 可能把 `execute()` / `resolveActivity()` 内联进调用方，挂钩子后不 deoptimize
 * 就只对「尚未编译」的调用生效 —— 表现为「时好时坏」。这里对 `execute()` 显式 deoptimize
 * （`resolveActivity` 的调用点分散在 executeRequest 内部，deopt execute 已覆盖主路径）。
 *
 * ## 安全边界
 *
 * - 只在**用户开了开关**时改写；关掉立即回原生（拦截时实时读 remote prefs）；
 * - 只处理文件类 Intent（picker 四动作 + oplus 文件浏览五动作 + `CATEGORY_APP_FILES` +
 *   文件夹 `VIEW`），短信/拨号/分享等一律放行；
 * - 显式指定了组件的 Intent（`component` / `package` / `selector` 非空）不改写；
 * - 目标 App 必须是**已安装、enabled、exported、无 permission 门槛**的 Activity，
 *   否则退回原生解析 —— 避免把请求改成一个打不开的组件。
 */
internal object FilePickerHook {

    private const val TAG = "FilePicker"

    /** 「使用 MT 管理器为选择器」开关 */
    const val KEY_USE_MT = "file_manager_use_mt_picker"

    /** 「使用原生文件选择器」开关 */
    const val KEY_USE_NATIVE = "file_manager_use_native_picker"

    /** MT 管理器包名（唯一的改写目标，不做成可选包，避免用户选到不可用的 App） */
    const val MT_PACKAGE = "bin.mt.plus"

    private const val FILE_MANAGER_PACKAGE = "com.coloros.filemanager"
    private const val FILE_MANAGER_PICKER = "com.oplus.filemanager.picker.PickerActivity"

    // ------------------------------------------------------------ 规则表

    private val pickerActions = setOf(
        android.content.Intent.ACTION_GET_CONTENT,
        android.content.Intent.ACTION_OPEN_DOCUMENT,
        android.content.Intent.ACTION_CREATE_DOCUMENT,
        android.content.Intent.ACTION_OPEN_DOCUMENT_TREE,
    )

    private val browseActions = setOf(
        "oplus.intent.action.filemanager.FILE_APK_VIEW",
        "oppo.intent.action.FILE_COMPRESS_VIEW",
        "oplus.intent.action.filemanager.FILE_DOCUMENT_VIEW",
        "oplus.intent.action.filemanager.FILE_MEDIA_VIEW",
        "oplus.intent.action.filemanager.BROWSER_FILE",
    )

    private val folderTypes = setOf("resource/folder", "vnd.android.document/directory")

    private val stockPackages = setOf(
        FILE_MANAGER_PACKAGE,
        "com.android.documentsui",
        "com.google.android.documentsui",
    )

    private val nativePackages = setOf("com.android.documentsui", "com.google.android.documentsui")

    // ------------------------------------------------------------ 安装

    fun install() {
        hookNativePickerRedirect()
        hookDefaultFileManagerRouting()
        LogUtils.i(
            TAG, "install",
            "文件选择器已装（MT 为选择器=${useMt()}，原生选择器=${useNative()}）",
        )
    }

    /**
     * 「使用原生文件选择器」：撤销 ColorOS 把 picker 默认改到自家文件管理器的行为。
     *
     * `interceptPickerIntent` 返回 `ResolveInfo` 表示「我替你定了用谁」，返回 null 表示走常规解析。
     * 因此只需在**已经指向 ColorOS 文件管理器 picker**、且原生 documentsui 可用时把结果置 null。
     */
    private fun hookNativePickerRedirect() {
        val cls = Reflect.classOrNull(
            HookEnv.classLoader,
            "com.android.server.pm.OplusDefaultAppPolicyManager",
        )
        if (cls == null) {
            LogUtils.w(TAG, "install", "OplusDefaultAppPolicyManager 不存在，跳过原生文件选择器")
            return
        }
        val m = Reflect.findMethodByShape(
            cls,
            "interceptPickerIntent",
            listOf(
                android.content.Intent::class.java,
                List::class.java,
            ),
            android.content.pm.ResolveInfo::class.java,
        )
        if (m == null) {
            LogUtils.w(TAG, "install", "interceptPickerIntent(Intent, List)ResolveInfo 不存在，跳过原生文件选择器")
            return
        }
        runCatching {
            HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    if (!useNative()) return result
                    val intent = chain.args.getOrNull(0) as? android.content.Intent ?: return result
                    @Suppress("UNCHECKED_CAST")
                    val query = chain.args.getOrNull(1) as? List<*> ?: return result
                    val selected = (result as? android.content.pm.ResolveInfo)?.activityInfo ?: return result
                    // 只撤销「ColorOS 文件管理器 picker」这一种改写
                    if (selected.packageName != FILE_MANAGER_PACKAGE ||
                        selected.name != FILE_MANAGER_PICKER
                    ) {
                        return result
                    }
                    val first = (query.firstOrNull() as? android.content.pm.ResolveInfo)?.activityInfo
                        ?: return result
                    if (!shouldRestoreToNative(intent, first)) return result
                    // 返回 null = 保留系统常规解析与 URI 授权
                    LogUtils.d(TAG, "intercept", "picker 退回原生：${first.packageName}/${first.name}")
                    return null
                }
            })
            LogUtils.i(TAG, "install", "已挂 ${cls.simpleName}#interceptPickerIntent")
        }.onFailure {
            LogUtils.w(TAG, "install", "挂 interceptPickerIntent 失败：${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /**
     * 是否可以把「已改写到 ColorOS picker」的结果退回原生 documentsui。
     *
     * 四个条件缺一不可：开关开、动作属于 picker 四类、**调用方没显式指定组件**、
     * 原生 documentsui 真的可用（enabled && exported && 应用 enabled）。
     */
    private fun shouldRestoreToNative(
        intent: android.content.Intent,
        first: android.content.pm.ActivityInfo,
    ): Boolean {
        if (intent.action !in pickerActions) return false
        if (intent.component != null || intent.`package` != null || intent.selector != null) return false
        if (first.packageName !in nativePackages) return false
        return available(first)
    }

    /**
     * 「使用 MT 管理器为选择器」：在 request 被 resolve / 执行之前把目标组件换成 MT。
     *
     * 两处 hook 互补：
     * - `ActivityStarter$Request#resolveActivity` —— 已构造 Request、尚未解析组件时；
     * - `ActivityStarter#execute` —— 直接 execute 的路径（部分入口跳过 Request 显式解析）。
     */
    private fun hookDefaultFileManagerRouting() {
        val loader = HookEnv.classLoader
        val starter = Reflect.classOrNull(loader, "com.android.server.wm.ActivityStarter")
        val requestClass = Reflect.classOrNull(loader, "com.android.server.wm.ActivityStarter\$Request")
        if (starter == null || requestClass == null) {
            LogUtils.w(TAG, "install", "ActivityStarter / Request 不存在，跳过 MT 选择器")
            return
        }
        val fields = runCatching { RequestFields(requestClass) }.getOrNull()
        if (fields == null) {
            LogUtils.w(TAG, "install", "ActivityStarter\$Request 字段不匹配（本机字段集已变），跳过 MT 选择器")
            return
        }
        val requestField = Reflect.findField(starter, "mRequest")?.apply { isAccessible = true }
        val serviceField = Reflect.findField(starter, "mService")?.apply { isAccessible = true }
        if (requestField == null || serviceField == null) {
            LogUtils.w(TAG, "install", "ActivityStarter 缺 mRequest / mService，跳过 MT 选择器")
            return
        }

        // --- 路径一：execute() 前改写 mRequest
        val execute = Reflect.findMethodByShape(starter, "execute", emptyList(), Void.TYPE)
        if (execute == null) {
            LogUtils.w(TAG, "install", "ActivityStarter#execute()V 不存在，跳过 execute 路径")
        } else {
            runCatching {
                HookEnv.module.hook(execute).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        runCatching {
                            val owner = chain.thisObject ?: return@runCatching
                            val request = requestField.get(owner) ?: return@runCatching
                            redirect(request, fields) { serviceField.get(owner) }
                        }
                        return chain.proceed()
                    }
                })
                // 已有编译产物可能把 execute 内联进调用方 —— deoptimize 后钩子才对所有调用生效
                runCatching { HookEnv.module.deoptimize(execute) }
                LogUtils.i(TAG, "install", "已挂 ActivityStarter#execute()")
            }.onFailure {
                LogUtils.w(TAG, "install", "挂 execute 失败：${it.javaClass.simpleName}: ${it.message}")
            }
        }

        // --- 路径二：Request#resolveActivity 前改写
        val resolve = Reflect.findMethodByShape(
            requestClass,
            "resolveActivity",
            listOf(Reflect.classOrNull(loader, "com.android.server.wm.ActivityTaskSupervisor")),
            Void.TYPE,
        )
        if (resolve == null) {
            LogUtils.w(TAG, "install", "Request#resolveActivity(ActivityTaskSupervisor)V 不存在，跳过该路径")
        } else {
            runCatching {
                HookEnv.module.hook(resolve).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        runCatching {
                            val request = chain.thisObject ?: return@runCatching
                            val supervisor = chain.args.firstOrNull() ?: return@runCatching
                            redirect(request, fields) { Reflect.getField(supervisor, "mService") }
                        }
                        return chain.proceed()
                    }
                })
                LogUtils.i(TAG, "install", "已挂 Request#resolveActivity")
            }.onFailure {
                LogUtils.w(TAG, "install", "挂 resolveActivity 失败：${it.javaClass.simpleName}: ${it.message}")
            }
        }
    }

    /**
     * 把 request 的文件类 Intent 改写到 MT 管理器。
     *
     * 改写后必须把 `activityInfo` / `resolveInfo` / `intentGrants` 三个缓存字段一起置空，
     * 否则宿主会继续用旧解析结果（改了 intent 却不生效）；`resolvedType` 同步成新 type。
     */
    private fun redirect(
        request: Any,
        fields: RequestFields,
        service: () -> Any?,
    ) {
        val selected = if (useMt()) MT_PACKAGE else return
        val original = fields.intent.get(request) as? android.content.Intent ?: return
        if (!mayRoute(original)) return
        runCatching {
            val owner = service() ?: return
            val context = Reflect.getField(owner, "mContext") as? Context ?: return
            val userId = fields.user.getInt(request)
            if (userId < 0) return
            // 以 system 身份查已安装组件，查完立即还原调用方身份（否则后续安全检查会拿到错误 uid）
            val token = android.os.Binder.clearCallingIdentity()
            val routed = try {
                // createContextAsUser 声明在抽象类 Context 上，运行体是 ContextImpl ⇒ 必须 deep 查找
                val userContext = Reflect.callDeep(
                    context,
                    "createContextAsUser",
                    android.os.UserHandle.getUserHandleForUid(userId * 100000),
                    0,
                ) as? Context ?: return
                rewrite(userContext.packageManager, original,
                    fields.type.get(request) as? String, selected,
                    fields.caller.get(request) as? String)
            } finally {
                android.os.Binder.restoreCallingIdentity(token)
            } ?: return
            fields.intent.set(request, routed)
            fields.type.set(request, routed.type)
            fields.activity.set(request, null)
            fields.resolve.set(request, null)
            fields.grants.set(request, null)
            LogUtils.d(
                TAG, "redirect",
                "文件请求改写到 MT：${original.action} → ${routed.component?.flattenToShortString()}",
            )
        }.onFailure {
            LogUtils.w(TAG, "redirect", "改写失败，保留原生请求：${it.javaClass.simpleName}: ${it.message}")
        }
    }

    // ------------------------------------------------------------ Intent 规则

    /**
     * 这个 Intent 是否属于「可以改写目标」的文件类请求。
     *
     * 显式指向非系统文件 App 的 Intent 一律不改（用户在别的 App 里主动选的路径不该被劫持）；
     * `ACTION_CHOOSER` 只在它包的内层 Intent 本身就符合条件时才跟着改写。
     */
    private fun mayRoute(intent: android.content.Intent): Boolean {
        if (intent.selector != null) return false
        if (intent.action == android.content.Intent.ACTION_CHOOSER) {
            return chooserTarget(intent)?.let(::mayRoute) == true
        }
        val target = intent.component?.packageName ?: intent.`package`
        if (target != null && target !in stockPackages) return false
        return intent.action in pickerActions || intent.action in browseActions ||
            ((intent.action == android.content.Intent.ACTION_MAIN || intent.action == null) &&
                (target in stockPackages ||
                    intent.hasCategory(android.content.Intent.CATEGORY_APP_FILES))) ||
            (intent.action == android.content.Intent.ACTION_VIEW && intent.type in folderTypes)
    }

    @Suppress("DEPRECATION")
    private fun chooserTarget(intent: android.content.Intent): android.content.Intent? = runCatching {
        intent.getParcelableExtra<android.content.Intent>(android.content.Intent.EXTRA_INTENT)
            ?.takeIf { it.action in pickerActions && it.selector == null }
    }.getOrNull()

    private fun hasChooserOptions(intent: android.content.Intent): Boolean = listOf(
        android.content.Intent.EXTRA_INITIAL_INTENTS,
        android.content.Intent.EXTRA_ALTERNATE_INTENTS,
        android.content.Intent.EXTRA_CHOOSER_TARGETS,
        android.content.Intent.EXTRA_REPLACEMENT_EXTRAS,
        android.content.Intent.EXTRA_CHOSEN_COMPONENT_INTENT_SENDER,
        android.content.Intent.EXTRA_EXCLUDE_COMPONENTS,
    ).any(intent::hasExtra)

    /**
     * 生成改写后的 Intent；返回 null 表示「不改写」。
     *
     * 分三类：
     * - picker 四动作 / 文件夹 `VIEW`：按 probe 查 MT 里能接这个 Intent 的 Activity；
     * - oplus 文件浏览五动作：先查有没有精确承接者，没有再退回「启动 MT 主页」；
     * - `MAIN` / 无 action / `CATEGORY_APP_FILES`：启动 MT 主页。
     */
    @Suppress("DEPRECATION")
    private fun rewrite(
        pm: android.content.pm.PackageManager,
        original: android.content.Intent,
        resolvedType: String?,
        selected: String,
        caller: String?,
    ): android.content.Intent? {
        if (!mayRoute(original)) return null

        if (original.action == android.content.Intent.ACTION_CHOOSER) {
            val inner = chooserTarget(original) ?: return null
            val routed = rewrite(pm, inner, inner.type, selected, caller) ?: return null
            // 选择器自带相机等额外选项时保留外层（只换内层），否则直接返回内层
            if (hasChooserOptions(original)) {
                return android.content.Intent(original)
                    .putExtra(android.content.Intent.EXTRA_INTENT, routed)
            }
            return routed.apply {
                addFlags(original.flags)
                if (clipData == null) clipData = original.clipData
            }
        }

        val target = original.component?.packageName ?: original.`package`
        if (target != null && target !in stockPackages) return null
        if (caller != null && caller == target) return null

        val probe = android.content.Intent(original).apply {
            component = null
            setPackage(selected)
            if (type == null && resolvedType != null) setDataAndType(data, resolvedType)
        }
        if (original.action in pickerActions) return matching(pm, probe)
        if (original.action == android.content.Intent.ACTION_VIEW) return matching(pm, probe)
        if (original.action in browseActions) return matching(pm, probe) ?: launch(pm, selected, original)
        if (original.action == android.content.Intent.ACTION_MAIN || original.action == null) {
            val appFiles = original.hasCategory(android.content.Intent.CATEGORY_APP_FILES)
            val stockLaunch = target in stockPackages && isLauncher(pm, original.component)
            if (appFiles || stockLaunch) return launch(pm, selected, original)
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun isLauncher(
        pm: android.content.pm.PackageManager,
        component: android.content.ComponentName?,
    ): Boolean {
        if (component == null) return false
        return listOf(
            android.content.Intent.CATEGORY_LAUNCHER,
            android.content.Intent.CATEGORY_INFO,
        ).any { category ->
            pm.queryIntentActivities(
                android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(category)
                    .setPackage(component.packageName),
                0,
            ).any { result ->
                result.activityInfo?.let { info ->
                    available(info) &&
                        (info.name == component.className || info.targetActivity == component.className)
                } == true
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun matching(
        pm: android.content.pm.PackageManager,
        probe: android.content.Intent,
    ): android.content.Intent? {
        val match = pm.queryIntentActivities(probe, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            .firstOrNull { it.activityInfo?.let(::available) == true }
            ?.activityInfo ?: return null
        return probe.apply {
            component = android.content.ComponentName(match.packageName, match.name)
        }
    }

    /** 启动目标 App 的主页（丢掉原 Intent 的 data / type / 授权标志，避免把文件 URI 递给不接受它的 Activity） */
    @Suppress("DEPRECATION")
    private fun launch(
        pm: android.content.pm.PackageManager,
        selected: String,
        original: android.content.Intent,
    ): android.content.Intent? {
        val launch = pm.getLaunchIntentForPackage(selected) ?: return null
        val component = launch.component ?: return null
        if (!available(pm.getActivityInfo(component, 0))) return null
        return android.content.Intent(original).apply {
            action = launch.action
            categories?.toList()?.forEach(::removeCategory)
            launch.categories?.forEach { addCategory(it) }
            setDataAndType(null, null)
            clipData = null
            flags = flags and (
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                ).inv()
            setPackage(selected)
            this.component = component
        }
    }

    private fun available(info: android.content.pm.ActivityInfo): Boolean =
        info.enabled && info.exported && info.applicationInfo?.enabled == true &&
            info.permission.isNullOrEmpty()

    private fun useMt(): Boolean = HookEnv.settingBoolean(KEY_USE_MT, false)

    private fun useNative(): Boolean = HookEnv.settingBoolean(KEY_USE_NATIVE, false)

    /**
     * `ActivityStarter$Request` 的字段访问器。
     *
     * 字段名已在 services.jar（ColorOS 17 / OnePlus 15）核对：`intent` / `userId` /
     * `resolvedType` / `callingPackage` / `activityInfo` / `resolveInfo` / `intentGrants` 全部命中。
     * 任一缺失直接抛（由上层捕获后跳过整个功能），不做「少一个字段也硬改」的将就
     * —— 半改的 Intent 会让宿主在后续校验里崩。
     */
    private class RequestFields(private val owner: Class<*>) {
        private fun field(name: String): java.lang.reflect.Field =
            owner.getDeclaredField(name).apply { isAccessible = true }

        val intent = field("intent")
        val user = field("userId")
        val type = field("resolvedType")
        val caller = field("callingPackage")
        val activity = field("activityInfo")
        val resolve = field("resolveInfo")
        val grants = field("intentGrants")
    }
}
