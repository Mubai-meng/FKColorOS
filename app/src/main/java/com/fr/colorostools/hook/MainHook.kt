package com.fr.colorostools.hook

import android.annotation.SuppressLint
import android.util.Log
import com.fr.colorostools.hook.hookers.AccessibilityEnhanceHook
import com.fr.colorostools.hook.hookers.FakeScreenHook
import com.fr.colorostools.hook.hookers.FluidCloudReplaceHook
import com.fr.colorostools.hook.hookers.FluidCloudSeedlingHook
import com.fr.colorostools.hook.hookers.LauncherMenuHook
import com.fr.colorostools.hook.hookers.OplusKeyboardHook
import com.fr.colorostools.hook.hookers.WeatherNoBrowserHook
import com.fr.colorostools.ref.Reflect
import com.fr.colorostools.utils.LogUtils
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import org.lsposed.lsparanoid.Obfuscate
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 模块入口（libxposed API 102 原生实现）。
 *
 * 结构对齐上游 LuckyTool 的 `MainHook`：`onModuleLoaded` 只做日志标识配置、
 * `onSystemServerStarting` / `onPackageLoaded` 里按作用域分发各 hooker。
 * libxposed 的原生模型没有 `onInit/onHook` 这对回调，对应物是
 * [onModuleLoaded]（等价 onInit，进程内只触发一次）与
 * [onSystemServerStarting] / [onPackageLoaded]（等价 onHook 的分发点）。
 *
 * ⚠️ 分发点选 [onPackageLoaded] 而非 `onPackageReady`（2026-10-15 真机实测）：
 * 宿主应用进程里 **`onPackageReady` 不一定触发** —— metis 三个进程的 `onModuleLoaded`
 * 全部正常触发、`onPackageReady` 零触发，钩子静默未装、无任何日志（极难排查）。
 * 参考实现 fkfluidcloud 的 `HookEntry` 同样只用 `onPackageLoaded`。
 * 这里以 `onPackageLoaded` 为主分发点，`onPackageReady` 仅作兜底（[installed] 防重复）。
 *
 * 入口注册与模块元数据由 APK 内资源声明（对齐 libxposed 规范）：
 *  - `META-INF/xposed/java_init.list` —— 本类全限定名
 *  - `META-INF/xposed/module.prop`    —— minApiVersion / targetApiVersion / autoHotReload
 *  - `META-INF/xposed/scope.list`     —— 作用域包名清单（新增功能时同步补充）
 */
@Obfuscate
class MainHook : XposedModule() {

    /** 进程内防重复安装：同一进程 [onPackageLoaded] 与 [onPackageReady] 可能都触发，只装一次 */
    private val installed = AtomicBoolean(false)

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        // 日志接线：常规日志投递到框架日志（LSPosed → 日志页），总开关开启时才落盘到 Download
        LogUtils.frameworkSink = { priority, tag, message -> log(priority, tag, message) }
        // 读取「启用模块日志」总开关（remote prefs）：关闭时不输出任何常规日志，仅错误进框架日志
        LogUtils.enable = runCatching {
            getRemotePreferences(HookEnv.PREFS_SETTINGS)?.getBoolean(HookEnv.KEY_ENABLE_LOG, false)
        }.getOrNull() ?: false
        LogUtils.i(
            TAG, "onModuleLoaded",
            "module loaded: process=${param.processName}, systemServer=${param.isSystemServer}",
        )
    }

    /** 系统框架（system_server）分发点 —— 作用域 `system` 命中时触发 */
    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        HookEnv.attach(this, param.classLoader, HookEnv.PKG_SYSTEM_SERVER)
        LogUtils.i(TAG, "onSystemServerStarting", "已注入 system_server（作用域 system）")
        if (!isMasterEnabled()) {
            LogUtils.i(TAG, "onSystemServerStarting", "主开关已停用（/sdcard/disable_colorostools），跳过钩子安装")
            return
        }
        // 熄屏挂机（假熄屏）：挂 PWM#powerPress 汇聚点，见 FakeScreenHook 类文档
        FakeScreenHook.install()
    }

    /**
     * 普通应用进程分发点（**主路径**）。
     *
     * 此时宿主的类加载器已就绪、Application 尚未创建 —— 正好在
     * `AppComponentFactory` 实例化之前，是所有 hook 安装时机中最早的一档。
     * 类不存在/方法找不到由各 Hooker 内部按日志跳过，不会影响其它宿主。
     */
    override fun onPackageLoaded(param: PackageLoadedParam) {
        installForPackage(param.packageName, param.defaultClassLoader, "onPackageLoaded")
    }

    /** 兜底分发点：若某版本只触发 `onPackageReady` 也能装上（[installed] 保证不重复） */
    override fun onPackageReady(param: PackageReadyParam) {
        installForPackage(param.packageName, param.classLoader, "onPackageReady")
    }

    /** 统一的按包名分发（onPackageLoaded / onPackageReady 两条路径共用） */
    private fun installForPackage(pkg: String, loader: ClassLoader, via: String) {
        if (!isMasterEnabled()) return
        if (!installed.compareAndSet(false, true)) {
            LogUtils.i(TAG, via, "已安装过，跳过（pkg=$pkg）")
            return
        }
        HookEnv.attach(this, loader, pkg)
        LogUtils.i(TAG, via, "已注入 $pkg")
        // 流体云两功能（替换流体云目标 / 自定义流体云）：
        // 本机实测单宿主 metis 全覆盖（scope.list 已收敛）；另两个宿主仅兜底 ——
        // 用户手动把 sceneservice / colordirectservice 加回作用域时也能命中，
        // Hooker 内部按「类是否存在」自适应（哪套引擎在跑就挂哪套，不存在只记一行日志）。
        if (pkg == HookEnv.PKG_METIS || pkg == HookEnv.PKG_SCENE_SERVICE || pkg == HookEnv.PKG_COLORDIRECT) {
            LogUtils.i(TAG, via, "流体云宿主命中（$pkg），安装两个 Hooker")
            FluidCloudReplaceHook.install()
            FluidCloudSeedlingHook.install()
        }
        // 无障碍增强（设置 App）：写 Secure settings 需要 WRITE_SECURE_SETTINGS，只有设置进程持有
        if (pkg == HookEnv.PKG_SETTINGS) {
            LogUtils.i(TAG, via, "设置宿主命中（$pkg），安装无障碍增强 Hooker")
            AccessibilityEnhanceHook.install()
        }
        // 桌面长按菜单定制（系统桌面）：3 钩子方案，见 docs/桌面长按菜单移植-方案评估.md
        if (pkg == HookEnv.PKG_LAUNCHER) {
            LogUtils.i(TAG, via, "桌面宿主命中（$pkg），安装长按菜单 Hooker")
            LauncherMenuHook.install()
        }
        // 天气 App 不外跳浏览器（系统天气）：startActivity 级拦截，见 docs/天气App不外跳浏览器-方案分析.md
        if (pkg == HookEnv.PKG_WEATHER) {
            LogUtils.i(TAG, via, "天气宿主命中（$pkg），安装不外跳浏览器 Hooker")
            WeatherNoBrowserHook.install()
        }
        // 小布输入法（模糊增强 + 按键下滑快捷键）：两个功能独立开关，见 OplusKeyboardHook 类文档
        if (pkg == HookEnv.PKG_OPLUS_KEYBOARD) {
            LogUtils.i(TAG, via, "小布输入法宿主命中（$pkg），安装键盘 Hooker")
            OplusKeyboardHook.install()
        }
        bindAppContextLater(loader)
    }

    /**
     * [onPackageLoaded] 时 Application 尚未创建，[HookEnv.context] 只能退回 systemContext。
     * hook `Instrumentation#callApplicationOnCreate`，Application 一创建就重绑真实 context
     * （`getLaunchIntentForPackage` / `resolveActivity` 用真实 context 更稳；
     *  参考实现 fkfluidcloud 同样依赖这一步）。
     */
    private fun bindAppContextLater(loader: ClassLoader) {
        runCatching {
            val ins = Reflect.classOrNull(loader, "android.app.Instrumentation") ?: return
            val app = Reflect.classOrNull(loader, "android.app.Application") ?: return
            val m = ins.getDeclaredMethod("callApplicationOnCreate", app)
            hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val r = chain.proceed()
                    runCatching {
                        val application = chain.args.getOrNull(0) as? android.app.Application
                        application?.let { HookEnv.bindAppContext(it.applicationContext) }
                    }
                    return r
                }
            })
            LogUtils.i(TAG, "bindAppContextLater", "已挂 Instrumentation#callApplicationOnCreate（延迟重绑 context）")
        }.onFailure {
            LogUtils.w(TAG, "bindAppContextLater", "挂载失败（不影响功能）→ ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    companion object {
        const val TAG = "ColorOSTools"

        /**
         * 主开关：存在 /sdcard/disable_colorostools 时整体停用（便于排障时一键关闭）。
         *
         * ⚠️ 语义 = 「未放置禁用文件才启用」—— 早期版本漏了取反，导致常态下
         * isMasterEnabled()==false、所有分发点静默 return，钩子一个都没装
         * （2026-10-15 真机日志：onModuleLoaded 有输出、分发点零输出）。
         */
        @SuppressLint("SdCardPath")
        fun isMasterEnabled(): Boolean {
            return try {
                !File("/sdcard/disable_colorostools").exists()
            } catch (_: Throwable) {
                true
            }
        }
    }
}
