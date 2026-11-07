package com.fr.colorostools.hook

import android.content.Context
import android.content.SharedPreferences
import com.fr.colorostools.ref.Reflect
import com.fr.colorostools.utils.LogUtils
import io.github.libxposed.api.XposedModule

/**
 * hook 进程内的共享环境（对应上游 LuckyTool 里 `Hooks`/`ModuleApplication` 承担的那部分职责）。
 *
 * 每个被注入的进程初始化一次：
 *  - [module] —— 模块实例（挂钩子、写框架日志用）
 *  - [classLoader] —— 宿主进程的类加载器（反射宿主类用）
 *  - [currentPkg] —— 当前进程的包名（`android` = system_server）
 *  - 设置偏好 —— 经 [XposedModule.getRemotePreferences] 读 App 侧写入的 remote prefs
 *
 * 注意：remote prefs 与 App 侧 `SPUtils.appPrefs` 是**同一份数据**（App 侧绑定 service 时
 * 写入的就是 remote prefs），所以设置页改开关后，这里读到的是同一个值，无需额外同步通道。
 */
object HookEnv {

    /** 系统界面（SystemUI）：WMShell 侧宿主 */
    const val PKG_SYSTEMUI = "com.android.systemui"

    /**
     * system_server 的作用域名。libxposed「现代模块」用 `system`（LSPosed 显示为「系统框架」）；
     * 只有历史/传统模块才把它叫 `android` —— 现代模块里 `android` 指的是系统弹窗进程，切勿混用。
     */
    const val PKG_SYSTEM_SERVER = "system"

    /** 系统桌面（Launcher3 改造） */
    const val PKG_LAUNCHER = "com.android.launcher"

    /**
     * 智慧决策服务（com.oplus.metis）：流体云卡片管线 + 识别层真身。
     *
     * 2026-10-15 真机实测（ColorOS 17 / OnePlus 15，功能已验证生效）：metis 全部进程
     * （主进程 / :net / :text_intent）里注入点一应俱全 —— 卡片管线
     * （DeepLinkData#parseIntent / Repository#obtainKouLingData / ActionData#parse /
     *  CommandHandler#checkValidity）**与识别层**（TokenDetector#readTokens）都在此进程，
     * **单宿主即可全覆盖**（scope.list 已收敛为仅此一个）。
     */
    const val PKG_METIS = "com.oplus.metis"

    /**
     * 场景服务（com.coloros.sceneservice）：参考实现的第二宿主（识别层）。
     * 本机实测该应用**全程 0 次加载**、识别层实际跑在 metis —— 作用域已不预置，
     * 仅保留常量与分发条件作兜底（用户手动加回作用域时 Hooker 按类存在性自适应安装）。
     */
    const val PKG_SCENE_SERVICE = "com.coloros.sceneservice"

    /**
     * 智慧数据增强服务（com.coloros.colordirectservice）：新引擎
     * `com.coloros.direct.command.*` 的潜在宿主。本机实测 0 次加载且 direct 引擎类
     * 不存在（ColorOS 17 只用旧引擎 textintent）—— 同为兜底保留。
     */
    const val PKG_COLORDIRECT = "com.coloros.colordirectservice"

    /**
     * 系统「设置」App：无障碍增强功能宿主。
     *
     * 写 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 需要 `WRITE_SECURE_SETTINGS`
     * （signature|privileged），只有设置这类系统应用持有 —— 所以自动授权只能在设置进程里执行。
     * ColorOS 17（设置 17.0.0）逆向验证：`SettingsActivity#onCreate(Bundle)` 存在、
     * `Settings$AccessibilitySettingsActivity`（extends SettingsActivity、未覆写 onCreate）
     * 承接 `android.settings.ACCESSIBILITY_SETTINGS`、详情页 Activity 与
     * `OPEN_ACCESSIBILITY_DETAILS_SETTINGS` 权限均在。见 `docs/无障碍增强-方案评估.md`。
     */
    const val PKG_SETTINGS = "com.android.settings"

    /**
     * 系统「天气」App：不外跳浏览器功能宿主。
     *
     * 逆向实证（天气 17.7.4，`E:/WorkBuddy_All/APP/oneplus/decompiled/weather`）：
     * 主页各类信息的二级页跳转在**正式版**默认走外跳浏览器（ SecondaryPageUtil /
     * LocalUtils 两个家族，最终都是 ACTION_VIEW / DETAIL_PAGE + 浏览器包名 + http），
     * 应用自带 WebView（`com.oplus.weather.plugin.webview.WeatherWebActivity`）只是
     * 「浏览器不可用」时的兜底（体验版才默认走应用内）。
     * 本功能用 startActivity 级拦截把外跳改成应用内，见
     * `hook/hookers/WeatherNoBrowserHook.kt` 与 `docs/天气App不外跳浏览器-方案分析.md`。
     */
    const val PKG_WEATHER = "com.coloros.weather2"

    /**
     * 小布输入法（`com.oplus.keyboard`）：模糊增强 + 按键下滑快捷键两个功能宿主。
     *
     * 逆向实证（宿主 1.8.35.17-mkt `_ref_kb_jadx` + 1.9.18.17-mkt 现场反编译）：
     * - 模糊总闸 `base.util.h#b()` → **1.9.18.17 迁到 `base.util.f#b()`**（升级后整批重混淆；
     *   方法名 `b`/`c` 没变，只是类名从 h 换成 f。jadx 因类名大小写冲突显示为 `AbstractC0818h`/`AbstractC0915f`）。
     *   判定与定位见 [ForceEnableOplusKeyboardBlur.isGateClass]，**不要只按类名挂**；
     * - 系统「质感模糊」开关读取 `base.data.e#i()` → **1.9.18.17 迁到 `base.data.o#j()`**；
     * - 键盘视图：旧版 `input.view.body.r`（`getSoftKeyboard()` 取按键模型）；新版 `body.r` 已变成
     *   `RecyclerView.ItemDecoration`，键盘视图改由「`body` 包内声明 `getSoftKeyboard()` 的 View」
     *   按形状识别（`OplusKeyboardHook.isKeyboardView` 已走这条路，不依赖固定类名）；
     * - IME 服务 `service.ImeService`（extends `base.service.b` extends `InputMethodService`，
     *   **覆写了 `onUpdateSelection`**，选区块要挂具体类）。
     * 见 `hook/hookers/OplusKeyboardHook.kt` 与 `docs/小布输入法功能移植-方案评估.md`。
     */
    const val PKG_OPLUS_KEYBOARD = "com.oplus.keyboard"

    /**
     * 文件管理 App（`com.coloros.filemanager`）：「隐藏安全访问提示」的宿主。
     *
     * 该功能只改文件管理器自己的选择页卡片可见性，必须在它的进程里挂
     * （见 `hook/hookers/FileManagerHook.kt`）。另两个「文件选择」开关在 system_server
     * （`FilePickerHook`），两者同一功能页、不同宿主。
     */
    const val PKG_FILE_MANAGER = "com.coloros.filemanager"

    /**
     * 快应用服务 / 快应用平台（`com.nearme.instant.platform`）：「快递不跳小程序」主宿主。
     *
     * 快递卡片的核心逻辑（`hap://app/com.nearme.quickapp.express/...` 改写、hapjs 包探测屏蔽、
     * 微信 SDK 卡片请求拦截）都跑在这个进程里；桌面与小布建议只是**入口侧**的兜底
     * （见 `hook/hookers/ExpressCardHook.kt`）。
     */
    const val PKG_QUICK_APP = "com.nearme.instant.platform"

    /**
     * 小布建议 / 速览（`com.coloros.assistantscreen`）：快递卡片入口的第二个宿主。
     *
     * 只是「从卡片点进来时把 hap 目的地改写成应用内快递页」，不参与 hapjs 运行时。
     */
    const val PKG_ASSISTANT_SCREEN = "com.coloros.assistantscreen"

    /** 设置页偏好所在 group（与 App 侧 `SettingsPrefs` 一致） */
    const val PREFS_SETTINGS = "SettingsPrefs"

    /** 「启用详细日志」开关的偏好键（与设置页 `enable_log` 对应） */
    const val KEY_ENABLE_LOG = "enable_log"

    @Volatile
    lateinit var module: XposedModule
        private set

    @Volatile
    lateinit var classLoader: ClassLoader
        private set

    @Volatile
    var currentPkg: String? = null
        private set

    private var settings: SharedPreferences? = null

    @Volatile
    private var appContext: Context? = null

    /**
     * 宿主 APK 的真实路径（`ApplicationInfo#sourceDir`），由 [MainHook] 在
     * `onPackageLoaded` / `onPackageReady` 里用 `param.applicationInfo.sourceDir` 直接注入。
     *
     * 为什么不能靠 [context] 取：分发点在 `onPackageLoaded`，那时 Application 还没创建，
     * [context] 只能退回 **systemContext** —— 它的 `applicationInfo.sourceDir` 指向
     * framework，拿去做 DexKit 就是「搜错文件」，结果恒为空（**无异常、无日志**，
     * 表现是该功能的 DexKit 兜底路径永远不命中）。2026-11-07 小布输入法 1.9.18.17 实证。
     */
    @Volatile
    private var hostApkPathCache: String? = null

    /** 进程内初始化：由 [MainHook] 在回调里调用 */
    fun attach(module: XposedModule, loader: ClassLoader, pkg: String, apkPath: String? = null) {
        this.module = module
        this.classLoader = loader
        this.currentPkg = pkg
        // 只在有值时写入：onPackageReady 兜底回调可能不带路径，别把已有值覆盖成 null
        if (!apkPath.isNullOrEmpty()) hostApkPathCache = apkPath
        settings = runCatching { module.getRemotePreferences(PREFS_SETTINGS) }.getOrNull()
        // 详细日志开关：hook 进程内动态跟随设置页（remote prefs 内存镜像），无需重启即可开关
        LogUtils.enable = settingBoolean(KEY_ENABLE_LOG, false)
        LogUtils.enableProvider = { settingBoolean(KEY_ENABLE_LOG, false) }
    }

    /** 绑定宿主 Application Context（拿到真实 Context 时调用，优先于反射回落） */
    fun bindAppContext(ctx: Context) {
        appContext = ctx
        // Application 建好后顺便把 APK 路径补上（attach 时传的已是同一个值，这里只是兜底）
        if (hostApkPathCache.isNullOrEmpty()) {
            hostApkPathCache = runCatching { ctx.applicationInfo.sourceDir }.getOrNull()
        }
    }

    /**
     * 宿主 APK 路径（DexKit 的输入）。
     *
     * 取值顺序：`onPackageLoaded` 注入的真实路径 → Application 的 `sourceDir` →
     * 当前包名的 `PackageManager` 查询。**绝不回退到 [context] 的 `applicationInfo`** ——
     * 那可能是 systemContext 的 framework 路径，会让 DexKit 搜错文件且不报错。
     */
    fun hostApkPath(): String? {
        hostApkPathCache?.takeIf { it.isNotEmpty() }?.let { return it }
        appContext?.let { ctx ->
            runCatching { ctx.applicationInfo.sourceDir }.getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { hostApkPathCache = it; return it }
        }
        val pkg = currentPkg ?: return null
        return runCatching {
            val pm = context()?.packageManager ?: return@runCatching null
            pm.getApplicationInfo(pkg, 0)?.sourceDir
        }.getOrNull()?.takeIf { it.isNotEmpty() }?.also { hostApkPathCache = it }
    }

    /**
     * 宿主 Context。优先用已绑定的 Application；否则经 `ActivityThread` 反射取；
     * 最后才用 system context 救急（不可缓存）。
     */
    fun context(): Context? {
        appContext?.let { return it }
        val at = Reflect.classOrNull(classLoader, "android.app.ActivityThread") ?: return null
        val cur = Reflect.callStatic(at, "currentActivityThread") ?: return null
        val app = Reflect.callByName(cur, "getApplication") as? Context
        if (app != null) {
            appContext = app
            return app
        }
        return Reflect.callByName(cur, "getSystemContext") as? Context
    }

    /**
     * 读布尔设置（App 设置页写入的 remote prefs）。
     * remote prefs 未就绪（框架无 remote 能力）时返回 [default]，绝不抛异常。
     */
    fun settingBoolean(key: String, default: Boolean = false): Boolean =
        runCatching { settings?.getBoolean(key, default) ?: default }.getOrDefault(default)

    /** 读字符串设置 */
    fun settingString(key: String, default: String = ""): String =
        runCatching { settings?.getString(key, default) ?: default }.getOrDefault(default)

    /** 读整数设置（slider 等写入的是 Int，不能用 getString 读） */
    fun settingInt(key: String, default: Int = 0): Int =
        runCatching { settings?.getInt(key, default) ?: default }.getOrDefault(default)

    /**
     * 读字符串集合设置（应用多选名单用）。
     *
     * 返回**不可变副本**：remote prefs 的 `getStringSet` 按约定不得修改返回的 Set
     * （改了不一定回写、还可能把框架侧缓存弄脏），宿主侧只读比对就够了。
     */
    fun settingStringSet(key: String, default: Set<String> = emptySet()): Set<String> =
        runCatching { settings?.getStringSet(key, default)?.toSet() ?: default }.getOrDefault(default)
}
