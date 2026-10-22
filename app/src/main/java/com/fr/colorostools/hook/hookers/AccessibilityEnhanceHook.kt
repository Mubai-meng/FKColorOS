package com.fr.colorostools.hook.hookers

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.fr.colorostools.hook.HookEnv
import com.fr.colorostools.ref.Reflect
import com.fr.colorostools.utils.LogUtils
import io.github.libxposed.api.XposedInterface

/**
 * 功能：设置 App 的「无障碍」增强。
 *
 * 移植自原项目 fkfluidcloud `AccessibilityHook`（功能六），ColorOS 17 / Android 17
 * 逆向验证全部成立（设置 17.0.0）：
 *  - `com.android.settings.SettingsActivity#onCreate(Bundle)` 存在（public，未被子类覆写）；
 *  - `Settings$AccessibilitySettingsActivity extends SettingsActivity` 承接
 *    `android.settings.ACCESSIBILITY_SETTINGS`（manifest 3204 行）；
 *  - `Settings$AccessibilityDetailsSettingsActivity` 存在且带
 *    `OPEN_ACCESSIBILITY_DETAILS_SETTINGS` 权限（manifest 3216 行），详情 Fragment 读
 *    `android.intent.extra.COMPONENT_NAME`（AccessibilityDetailsSettingsFragment 147 行）；
 *  - 设置 App 清单持有 `WRITE_SECURE_SETTINGS`（manifest 75 行）。
 * 评估文档：`docs/无障碍增强-方案评估.md`。
 *
 * ## 为什么必须 hook「设置」而不是被授权的那个 App
 *
 * 把服务写进 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 需要
 * **`WRITE_SECURE_SETTINGS`** —— 它是 `signature|privileged` 级权限，只有系统应用拿得到。
 * 设置 App 的清单里正带着它，而普通 App 没有。所以「自动授权」只能**在设置进程里**执行，
 * 这就是本功能的宿主是 `com.android.settings` 的原因。
 *
 * ## Hook 点
 *
 * `com.android.settings.SettingsActivity#onCreate(Bundle)`，`proceed()` 之后。
 *
 * 触发条件：**当前 Intent 的 action 是
 * `android.settings.ACCESSIBILITY_SETTINGS`** —— 即「某个 App 主动跳到了无障碍设置页」。
 * 这时 `Activity.getReferrer()` 的 host 就是**发起跳转的应用包名**（系统在跨应用启动
 * Activity 时会自动填 referrer），于是我们就知道「用户想给哪个 App 开无障碍」。
 *
 * 设置 17.0.0 实测：该 action 由 `Settings$AccessibilitySettingsActivity` 承接，
 * 而它 `extends SettingsActivity` 且**没有覆写 `onCreate`** ⇒ 挂基类方法必被调用。
 *
 * ## 四个开关（键名与上游 fkfluidcloud 一致，便于对照溯源）
 *
 * | 开关 | 行为 |
 * |---|---|
 * | 直接授权无障碍服务 | 把来源 App 的无障碍服务写进已启用列表，然后关掉设置页（一次点击到位） |
 * | 无障碍服务直达 | 跳过服务列表，直接打开该 App 的**无障碍服务详情页** |
 * | 智能授权无障碍服务 | 白名单内的 App 自动授权；白名单外的走「直达」 |
 * | 自动重启服务 | （配合上面任一项）授权时**先移除再重新添加**该服务，迫使系统重启它 |
 *
 * ## 组件匹配规则（2026-10-18 修正）
 *
 * 无障碍服务组件全名 = `声明包名/完整类名`，系统按 `ComponentName(pkg, cls)` 与
 * serviceInfo 匹配，**类名不必以包名为前缀**。早期版本按「类名前缀 == 包名」校验
 * （疑代理声明），误把合法服务全部跳过 —— 真机实证：cn.trinea.android.developertools
 * 复刻声明的 `com.google.android.accessibility.selecttospeak.SelectToSpeakService`
 * 在系统设置页可正常开启使用。现改为 `PackageManager#getServiceInfo` 存在性校验
 * （manifest 级，与系统原生日径一致），多服务 App 授权首个并日志列出全部候选。
 *
 * ## 性能
 *
 * 钩子体每次只做「1 次 action 字符串比较（不匹配即退）+ 3 次 SP 内存读」，
 * 且**先查 action 再查开关**（action 检查更便宜，让绝大多数调用更早退出）。
 * 触发频率是用户手动导航设置页的节奏（一次会话几十次），不在任何热路径上。
 * 四开关全关（默认态）时早退，与不装钩子无实质差别。
 *
 * ## 兜底
 *
 * 全部 `runCatching`；拿不到 referrer / 找不到服务 / 写失败都只记日志后放行，
 * 绝不让设置页崩掉或卡住。安装时类/方法找不到只记一行 W 日志并跳过
 * （宿主换版本时功能不生效，但设置页行为完全不受影响）。
 */
internal object AccessibilityEnhanceHook {

    private const val TAG = "A11y"

    private const val SETTINGS_PKG = "com.android.settings"

    private const val CLS_SETTINGS_ACTIVITY = "com.android.settings.SettingsActivity"

    /** 单服务详情页 Activity（设置清单里导出，读 `android.intent.extra.COMPONENT_NAME`） */
    private const val CLS_DETAILS_ACTIVITY =
        "com.android.settings.Settings\$AccessibilityDetailsSettingsActivity"

    private const val ACTION_A11Y_SETTINGS = "android.settings.ACCESSIBILITY_SETTINGS"
    private const val EXTRA_COMPONENT_NAME = "android.intent.extra.COMPONENT_NAME"

    /** 重启时「先关」与「再开」之间的间隔：必须让 AMS 先观察到移除，否则两次写入会被合并 */
    private const val RESTART_GAP_MS = 600L

    @Volatile private var loggedInstall = false
    private val main = Handler(Looper.getMainLooper())

    fun install() {
        runCatching {
            val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_SETTINGS_ACTIVITY)
            if (cls == null) {
                LogUtils.w(TAG, "install", "未找到 $CLS_SETTINGS_ACTIVITY —— 宿主版本可能已升级，跳过（无障碍增强不生效）")
                return
            }
            val m = Reflect.findMethod(cls, "onCreate", 1, mapOf(0 to "Bundle"))
                ?: Reflect.findMethod(cls, "onCreate", 1)
            if (m == null) {
                LogUtils.w(TAG, "install", "$CLS_SETTINGS_ACTIVITY 上找不到 onCreate(Bundle)，跳过")
                return
            }
            HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val r = chain.proceed()
                    runCatching {
                        val act = chain.thisObject as? Activity ?: return@runCatching
                        maybeHandle(act)
                    }.onFailure {
                        LogUtils.e(TAG, "intercept", "处理失败（已忽略，不影响设置页）→ ${it.javaClass.simpleName}: ${it.message}")
                    }
                    return r
                }
            })
            if (!loggedInstall) {
                loggedInstall = true
                LogUtils.i(TAG, "install", "已挂 ${cls.name}#onCreate(Bundle) —— 无障碍四项已就绪")
            }
        }.onFailure { LogUtils.e(TAG, "install", "hook 安装失败 → ${it.javaClass.simpleName}: ${it.message}") }
    }

    // ------------------------------------------------------------------ 主逻辑

    private fun maybeHandle(activity: Activity) {
        // 先查 action（最便宜的过滤）：不是「App 主动跳无障碍设置页」就直接放行。
        // 注：SubSettings 等子页 onCreate 也会走到这里，这一步把 99% 的调用挡掉。
        val intent = activity.intent ?: return
        if (intent.action != ACTION_A11Y_SETTINGS) return

        // 三个开关全关时直接放行（默认态；读取是 SP 内存查表，无 IO）
        if (!directAuth && !jump && !autoAuth) return

        val srcPkg = referrerPackage(activity) ?: run {
            LogUtils.w(TAG, "maybeHandle", "是无障碍设置页，但拿不到来源包名（referrer 为空），放行")
            return
        }

        val svcs = findAccessibilityServices(activity, srcPkg)
        if (svcs.isEmpty()) {
            LogUtils.w(TAG, "maybeHandle", "$srcPkg 没有声明无障碍服务（或列表尚未就绪），放行")
            return
        }
        // 多服务 App：取第一个。系统列表页 hook 时用户尚未点具体服务，无法预知目标，
        // 日志列出全部候选便于定位（首个服务通常是 App 的主服务）。
        val svc = svcs.first()
        if (svcs.size > 1) {
            LogUtils.i(TAG, "maybeHandle", "$srcPkg 声明了 ${svcs.size} 个无障碍服务，授权首个 $svc；候选=$svcs")
        }

        // 组件存在性校验（2026-10-18 替代早期的「类名前缀 == 包名」校验）：
        // ENABLED_ACCESSIBILITY_SERVICES 的扁平条目 `pkg/cls` 由系统按
        // ComponentName(pkg, cls) 与 serviceInfo 匹配，**与类名前缀无关** ——
        // 类名是任意合法类名（含复刻/代理声明）的服务，系统照样能绑定。
        // 早期校验按「类名必以包名开头」跳过自动授权，误伤了全部合法服务：
        // 真机实证 cn.trinea.android.developertools 复刻声明的
        // com.google.android.accessibility.selecttospeak.SelectToSpeakService
        // 在系统设置页可正常开启使用，与我们的授权路径写同一扁平条目。
        // 现在只校验组件在当前系统里真实可解析（防退化路径拿到过期组件）。
        if (!serviceExists(activity, srcPkg, svc)) {
            LogUtils.w(
                TAG, "maybeHandle",
                "$srcPkg/$svc 在当前系统不可解析（组件可能已卸载/被禁用），跳过自动授权，放行",
            )
            return
        }

        // 白名单读取天然短路：不开「智能授权」就不会读（Kotlin && 从左到右短路）
        val whitelisted = autoAuth && (srcPkg in whitelist)
        val autoAuthorize = directAuth || whitelisted

        LogUtils.i(
            TAG, "maybeHandle",
            "来源=$srcPkg 服务=$svc 白名单内=$whitelisted → ${if (autoAuthorize) "自动授权" else "直达详情页"}",
        )

        if (autoAuthorize) authorize(activity, srcPkg, svc)
        else if (jump || autoAuth) jumpToDetails(activity, srcPkg, svc)
    }

    /** `Activity.getReferrer()` → `android-app://<包名>`，取 host 即来源包名 */
    private fun referrerPackage(activity: Activity): String? {
        val ref: Uri? = runCatching { activity.referrer }.getOrNull()
        val host = ref?.host
        if (host.isNullOrBlank()) return null
        // 自己（设置）或被启动器打开时没有意义，跳过
        if (host == SETTINGS_PKG || host.startsWith("com.android.launcher")) return null
        return host
    }

    /**
     * 找 [pkg] 声明的无障碍服务的完整类名列表（可能多个，按系统列表顺序）。
     *
     * 优先用 `AccessibilityManager.installedAccessibilityServiceList`（系统认可的列表）；
     * 为空时退化为用 PackageManager 按 action 反查，
     * 这样即使 AMS 还没加载完也能拿到。
     */
    private fun findAccessibilityServices(activity: Activity, pkg: String): List<String> {
        val fromAm = runCatching {
            val am = activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            am?.installedAccessibilityServiceList
                ?.mapNotNull { info -> info?.resolveInfo?.serviceInfo }
                ?.filter { it.packageName == pkg && !it.name.isNullOrBlank() }
                ?.mapNotNull { it.name }
                ?.distinct()
                ?: emptyList()
        }.getOrNull().orEmpty()
        if (fromAm.isNotEmpty()) return fromAm

        // 退化路径
        return runCatching {
            val pm = activity.packageManager
            @Suppress("DEPRECATION")
            val ris = pm.queryIntentServices(
                Intent("android.accessibilityservice.AccessibilityService"), 0
            )
            ris?.mapNotNull { it?.serviceInfo }
                ?.filter { it.packageName == pkg && !it.name.isNullOrBlank() }
                ?.map { it.name }
                ?.distinct()
                ?: emptyList()
        }.getOrNull().orEmpty()
    }

    /**
     * 组件是否真实可解析（manifest 声明级，与系统设置页的判据一致）。
     *
     * Android 无障碍服务组件全名 = `声明包名/完整类名`，类名**不必**以包名为前缀。
     * 存在性只校验到 manifest 级：类是否真在 dex 里由 AMS bind 时校验，
     * 与系统原生日径一致（设置页对同一服务同样放行）。
     */
    private fun serviceExists(activity: Activity, pkg: String, svc: String): Boolean =
        runCatching {
            activity.packageManager.getServiceInfo(ComponentName(pkg, svc), 0) != null
        }.getOrDefault(false)

    // ------------------------------------------------------------------ 自动授权

    private fun authorize(activity: Activity, pkg: String, svc: String) {
        val flat = "$pkg/$svc"
        val cr = activity.contentResolver

        val current = readEnabled(cr)
        val restart = restartService

        if (restart && current.contains(flat)) {
            // 「先关闭」：先写一份不含该服务的列表，等 AMS 处理完再「开启」
            val without = current.toMutableList().apply { remove(flat) }
            writeEnabled(cr, without)
            LogUtils.i(TAG, "authorize", "[重启服务] 已先移除 $flat，${RESTART_GAP_MS}ms 后重新启用")
            main.postDelayed({
                runCatching {
                    val now = readEnabled(activity.contentResolver).toMutableList()
                    now.remove(flat)
                    now.add(flat)
                    writeEnabled(activity.contentResolver, now)
                    ensureAccessibilityOn(activity)
                    LogUtils.i(TAG, "authorize", "[重启服务] 已重新启用 $flat")
                }.onFailure {
                    LogUtils.e(TAG, "authorize", "[重启服务] 重新启用失败 → ${it.javaClass.simpleName}: ${it.message}")
                }
            }, RESTART_GAP_MS)
        } else {
            val next = current.toMutableList().apply {
                remove(flat)   // 去重：系统对重复条目行为不确定
                add(flat)
            }
            writeEnabled(cr, next)
            LogUtils.i(TAG, "authorize", "[直接授权] 已启用 $flat（当前共 ${next.size} 项）")
        }

        ensureAccessibilityOn(activity)

        runCatching {
            activity.finish()
            LogUtils.i(TAG, "authorize", "[直接授权] 已关闭无障碍设置页")
        }.onFailure { LogUtils.w(TAG, "authorize", "finish 失败：${it.message}") }
    }

    private fun readEnabled(cr: android.content.ContentResolver): List<String> =
        runCatching {
            Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        }.getOrNull()
            ?.split(':')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    private fun writeEnabled(cr: android.content.ContentResolver, list: List<String>) {
        Settings.Secure.putString(
            cr,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            list.joinToString(":"),
        )
    }

    /**
     * 无障碍**总开关**。
     *
     * 只写 ENABLED_ACCESSIBILITY_SERVICES 而总开关是 0 时，服务依然不会运行 ——
     * 所以这一步不能省。
     */
    private fun ensureAccessibilityOn(activity: Activity) {
        runCatching {
            val cr = activity.contentResolver
            val on = Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
            if (on != 1) {
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                LogUtils.i(TAG, "ensureAccessibilityOn", "无障碍总开关原为 $on，已置 1（否则服务不会真正启动）")
            }
        }.onFailure { LogUtils.w(TAG, "ensureAccessibilityOn", "写 ACCESSIBILITY_ENABLED 失败：${it.message}") }
    }

    // ------------------------------------------------------------------ 直达详情页

    private fun jumpToDetails(activity: Activity, pkg: String, svc: String) {
        runCatching {
            val i = Intent()
            i.setClassName(SETTINGS_PKG, CLS_DETAILS_ACTIVITY)
            i.putExtra(EXTRA_COMPONENT_NAME, "$pkg/$svc")
            activity.startActivity(i)
            activity.finish()
            LogUtils.i(TAG, "jumpToDetails", "[直达] 已打开 $pkg/$svc 的无障碍详情页")
        }.onFailure {
            // 该 Activity 在本版不存在时，退回「什么都不做」——
            // 用户看到的还是系统原本的无障碍列表，行为不算坏
            LogUtils.w(TAG, "jumpToDetails", "[直达] 打开详情页失败（已忽略）：${it.message}")
        }
    }

    // ------------------------------------------------------------------ 开关读取（remote prefs，与 App 设置页同源）

    private val directAuth: Boolean
        get() = HookEnv.settingBoolean(AccessibilityConfig.KEY_DIRECT_AUTH, false)

    private val jump: Boolean
        get() = HookEnv.settingBoolean(AccessibilityConfig.KEY_JUMP, false)

    private val autoAuth: Boolean
        get() = HookEnv.settingBoolean(AccessibilityConfig.KEY_AUTO_AUTH, false)

    private val restartService: Boolean
        get() = HookEnv.settingBoolean(AccessibilityConfig.KEY_RESTART, false)

    private val whitelist: List<String>
        get() = HookEnv.settingString(AccessibilityConfig.KEY_WHITELIST, "")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
}

/**
 * 无障碍增强的配置键（App 设置页写入 `SettingsPrefs`，hook 侧经
 * [HookEnv.settingBoolean] / [HookEnv.settingString] 读同一份数据）。
 *
 * 键名与上游 fkfluidcloud `ConfigStore` 保持一致，便于对照溯源。
 */
internal object AccessibilityConfig {
    const val KEY_DIRECT_AUTH = "a11y_direct_auth"
    const val KEY_JUMP = "a11y_jump"
    const val KEY_AUTO_AUTH = "a11y_auto_auth"
    const val KEY_WHITELIST = "a11y_auto_auth_whitelist"
    const val KEY_RESTART = "a11y_restart_service"
}
