package com.fr.colorostools.hook.hookers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.fr.colorostools.hook.HookEnv
import com.fr.colorostools.hook.UsbDebugConst
import com.fr.colorostools.ref.Reflect
import com.fr.colorostools.utils.LogUtils
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 功能：**USB 调试磁贴的系统侧执行体** —— 替 App 进程写 `Settings.Global.ADB_ENABLED`。
 *
 * ## 一、为什么必须绕这一道
 *
 * 写 `adb_enabled` 需要 `WRITE_SECURE_SETTINGS`（signature|privileged），模块 App 拿不到；
 * 而**读**不需要特权 —— 该字段带 `@Settings$Readable` 注记（本机 `framework.jar` 实证），
 * 已进 `NameValueCache.mReadableFields` 白名单。所以磁贴自己读状态，只有写这一半交给这里。
 *
 * ## 二、写完之后系统自己会做完剩下的事（本机 `services.jar` 实证）
 *
 * ```
 * Settings.Global.putInt("adb_enabled", 0/1)
 *   → AdbService$AdbSettingsObserver#onChange          // 构造时注册了 ContentObserver
 *   → AdbService#setAdbEnabled(Z,B)                    // post 到 FgThread，无权限检查、无回写
 *   → setAdbEnabledDoNotCallDirectly → startAdbd()/stopAdbd()
 * ```
 *
 * 因此这里**不碰 adbd、不碰 UsbDeviceManager、不碰设置页 UI**，只做一次设置写入 ——
 * 与 ColorOS 设置页 `OplusAdbPreferenceController#writeAdbSetting` 完全等价
 * （那个方法体也只有这一句 putInt）。改完开发者选项的开关显示、重启后的持久化
 * 全部由系统自理，不需要状态镜像。
 *
 * ## 三、挂点选择：`AdbService#<init>(Context, ...)`
 *
 * 取系统 Context 的三个候选里选它，理由：
 *
 * | 候选 | 问题 |
 * |---|---|
 * | `ActivityThread#systemMain` | 太早，Settings/AMS 未必就绪 |
 * | `PhoneWindowManager#init` | 能用，但与本功能无关；熄屏挂机已占用该点 |
 * | **`AdbService#<init>`** | 第一个参数就是系统 Context；USB 调试的基础服务，必然启动 |
 *
 * 只在这里注册接收器、**不读 Settings**，所以即使它早于 `SettingsProvider` 启动也无影响
 * （真正读设置发生在广播到达时）。找不到 AdbService 时退回 `PWM#init` 取 Context 兜底。
 *
 * ## 四、安全
 *
 * 接收器注册时把 [UsbDebugConst.PERMISSION_TILE_CONTROL]（`protectionLevel=signature`）
 * 作为 `broadcastPermission` 传入 ⇒ 只有与模块同签名的进程能投递。USB 调试是高危开关，
 * 这条限制不能省 —— 否则任何 App 发一条广播就能打开调试通道。
 */
object UsbDebugHook {

    private const val TAG = "UsbDebugHook"

    private const val CLS_ADB_SERVICE = "com.android.server.adb.AdbService"
    private const val CLS_PWM = "com.android.server.wm.PhoneWindowManager"

    /** 接收器只注册一次（AdbService 构造理论上单次，PWM#init 会走多个重载） */
    private val receiverRegistered = AtomicBoolean(false)

    /** 注册失败的重试计数 / 上限：AdbService 构造会在 `startOtherServices` 中段，极少数机型上
     *  AMS 的自定义权限解析可能尚未就绪，失败后隔几秒重试比永久静默要好 */
    private var retryCount = 0
    private const val MAX_RETRY = 5
    private const val RETRY_DELAY_MS = 3_000L

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var systemContext: Context? = null

    /** system_server 入口：装接收器（主挂点 AdbService，失败退回 PWM） */
    fun install() {
        if (hookAdbServiceInit()) return
        LogUtils.w(TAG, "install", "AdbService 挂点不可用，退回 PhoneWindowManager#init 取 Context")
        hookPwmInit()
    }

    // ================================================================ 挂点

    /**
     * 挂 `AdbService#<init>`（按形状匹配：第一个参数是 `android.content.Context`）。
     *
     * 参数类型用**字符串**比较：跨 ClassLoader 的同名类不是同一个 Class 对象，
     * `== Context::class.java` 在宿主侧会恒不等（钩子静默不装）。
     */
    private fun hookAdbServiceInit(): Boolean {
        val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_ADB_SERVICE) ?: return false
        val ctor = runCatching {
            cls.declaredConstructors.firstOrNull { c ->
                c.parameterTypes.firstOrNull()?.name == "android.content.Context"
            }
        }.getOrNull() ?: run {
            LogUtils.w(TAG, "hookAdbServiceInit", "AdbService 没有以 Context 开头的构造函数")
            return false
        }
        return runCatching {
            ctor.isAccessible = true
            HookEnv.module.hook(ctor).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    // 参数在 proceed 之后不保证可读，先取出来
                    val ctx = chain.getArgs().firstOrNull() as? Context
                    val result = chain.proceed()
                    if (ctx != null) runCatching { attachContext(ctx) }
                    return result
                }
            })
            LogUtils.i(TAG, "hookAdbServiceInit", "已挂 AdbService#<init>(Context, ...)")
            true
        }.getOrElse {
            LogUtils.e(TAG, "hookAdbServiceInit", "挂载失败：${it.javaClass.simpleName}: ${it.message}")
            false
        }
    }

    /** 兜底挂点：`PWM#init(Context, ...)` after 取系统 Context（与熄屏挂机同一个时机） */
    private fun hookPwmInit() {
        val pwm = Reflect.classOrNull(HookEnv.classLoader, CLS_PWM) ?: run {
            LogUtils.e(TAG, "hookPwmInit", "PhoneWindowManager 不存在，磁贴命令无处接收")
            return
        }
        var hooked = 0
        pwm.declaredMethods
            .filter { it.name == "init" && it.parameterTypes.firstOrNull()?.name == "android.content.Context" }
            .forEach { m ->
                runCatching {
                    m.isAccessible = true
                    HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val ctx = chain.getArgs().firstOrNull() as? Context
                            val result = chain.proceed()
                            if (ctx != null) runCatching { attachContext(ctx) }
                            return result
                        }
                    })
                    hooked++
                }.onFailure {
                    LogUtils.w(TAG, "hookPwmInit", "挂 init 失败：${it.message}")
                }
            }
        if (hooked == 0) {
            LogUtils.e(TAG, "hookPwmInit", "PWM#init(Context, ...) 一个都没挂上，磁贴将无效")
        } else {
            LogUtils.i(TAG, "hookPwmInit", "已挂 PWM#init 兜底 $hooked 个重载")
        }
    }

    // ================================================================ 接收器

    /**
     * 拿到系统 Context 后注册命令接收器（幂等）。
     *
     * `broadcastPermission` 传签名权限 ⇒ 只有模块自己（同签名）能发这条广播；
     * `RECEIVER_EXPORTED` 仍必需，因为发送方是**另一个进程**（模块 App，不同 uid）。
     */
    private fun attachContext(context: Context) {
        systemContext = context.applicationContext ?: context
        if (receiverRegistered.get()) return
        val ctx = systemContext ?: return
        val ok = runCatching {
            ctx.registerReceiver(
                commandReceiver,
                IntentFilter(UsbDebugConst.ACTION_SET_USB_DEBUG),
                UsbDebugConst.PERMISSION_TILE_CONTROL,
                null,
                Context.RECEIVER_EXPORTED,
            )
            true
        }.getOrElse {
            LogUtils.w(
                TAG, "attachContext",
                "注册失败（第 ${retryCount + 1} 次）：${it.javaClass.simpleName}: ${it.message}",
            )
            false
        }
        if (ok) {
            receiverRegistered.set(true)
            LogUtils.i(
                TAG, "attachContext",
                "磁贴命令接收器已注册（要求 ${UsbDebugConst.PERMISSION_TILE_CONTROL}），" +
                    "当前 adb_enabled=${readAdbEnabled()}",
            )
        } else if (retryCount < MAX_RETRY) {
            retryCount++
            mainHandler.postDelayed({ attachContext(ctx) }, RETRY_DELAY_MS)
        } else {
            LogUtils.e(TAG, "attachContext", "重试 $MAX_RETRY 次仍失败，USB 调试磁贴将不可用")
        }
    }

    /**
     * 磁贴命令：直接改系统状态位。
     *
     * 只写 `Settings.Global`，不调 `startAdbd()`：走设置位才能让开发者选项的开关、
     * `AdbService` 的内存状态（`mIsAdbUsbEnabled`）与持久化三者保持一致。
     */
    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != UsbDebugConst.ACTION_SET_USB_DEBUG) return
            val enabled = intent.getBooleanExtra(UsbDebugConst.EXTRA_ENABLED, false)
            val ctx = systemContext ?: context ?: return
            runCatching {
                val ok = Settings.Global.putInt(
                    ctx.contentResolver,
                    UsbDebugConst.KEY_ADB_ENABLED,
                    if (enabled) 1 else 0,
                )
                LogUtils.i(
                    TAG, "onReceive",
                    "adb_enabled → ${if (enabled) 1 else 0}（putInt=$ok，回读=${readAdbEnabled()}）",
                )
            }.onFailure {
                LogUtils.e(TAG, "onReceive", "写 Settings.Global 失败：${it.javaClass.simpleName}: ${it.message}")
            }
        }
    }

    /** 读回状态位（system_server 内读无限制，仅用于日志与自检） */
    private fun readAdbEnabled(): Int {
        val ctx = systemContext ?: return -1
        return runCatching {
            Settings.Global.getInt(ctx.contentResolver, UsbDebugConst.KEY_ADB_ENABLED, 0)
        }.getOrDefault(-1)
    }
}
