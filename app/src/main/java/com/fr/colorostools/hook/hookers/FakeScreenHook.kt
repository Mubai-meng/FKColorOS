package com.fr.colorostools.hook.hookers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import com.fr.colorostools.hook.FakeScreenConst
import com.fr.colorostools.hook.HookEnv
import com.fr.colorostools.ref.Reflect
import com.fr.colorostools.utils.LogUtils
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 功能：**熄屏挂机（假熄屏）** —— 按电源键只熄灭屏幕，设备与 App 继续运行（不被冻结/降频）。
 *
 * 移植自 `chuyb-re/fakeScreen`，按 ColorOS 17 / Android 17 重写。**上游实现在本机完全不生效**，
 * 以下全部经本机 `services.jar` / `oplus-services.jar` / `framework.jar` 反编译实证。
 *
 * ## 零、设计总则：只碰内存，绝不落盘
 *
 * 本功能遵守一条硬约束：**不对用户设备产生任何不可逆影响**。具体是「四个不写」：
 *
 * | 不写什么 | 为什么 |
 * |---|---|
 * | 不写 `Settings.System.SCREEN_OFF_TIMEOUT` | 那是**用户的设置项**。一旦进程中途死亡就永久污染（屏幕永不熄） |
 * | 不用 `Settings.Global` 存任何**运行状态** | 只存「待命开关」这一个用户显式选择，值域 0/1，用户可见可清 |
 * | 不改系统/应用 APK | 全部运行时 hook |
 * | 不留后台服务/定时任务 | 只在 system_server 内挂几个内存钩子 |
 *
 * 超时控制因此改为 **hook `PowerManagerService#getScreenOffTimeoutLocked` 的返回值**：
 * 纯内存、进程重启即失效、**无需任何还原逻辑**。见 §六。
 *
 * ## 一、两个状态位（本版重点，别混用）
 *
 * | 状态 | 含义 | 持久化 | 谁改 |
 * |---|---|---|---|
 * | [armed] | **待命开关** = 磁贴是否打开 | **是**（`Settings.Global[colorostools_fake_screen]`） | 只由磁贴广播改 |
 * | [fakeOffActive] | **当前是否正处假熄屏** | 否（纯内存） | 只由电源键 / 系统接管改 |
 *
 * 拆开这两个位的意义：
 *
 * ```
 * 磁贴打开 ⇒ armed=true          ⇒ 屏幕毫无反应，只是「下次按电源键会假熄屏」
 * 按电源键 ⇒ fakeOffActive 翻转   ⇒ 屏幕灭 / 亮
 * 磁贴关闭 ⇒ armed=false         ⇒ 屏幕毫无反应，电源键立刻回归系统原生
 * ```
 *
 * 用户明确要求过这一点：**磁贴是功能开关，不是「立即熄屏」按钮**。
 * 在控制中心误触就黑屏是不可接受的；熄屏只能由电源键触发，行为才可预期。
 *
 * ## 二、上游为何失效
 *
 * | 项 | AOSP / 上游验证设备 | ColorOS 17（本机） |
 * |---|---|---|
 * | 单击入口签名 | `PhoneWindowManager#powerPress(J, I)` | `PhoneWindowManager#powerPress(J, I, I)` |
 * | 查找方式 | 要求「恰好 `(long, int)`」 | 参数个数不符 ⇒ **恒 null，功能静默失效** |
 * | `SurfaceControl#getInternalDisplayToken` | 存在 | **已整体移除**（只剩 `nativeSetDisplayPowerMode`） |
 * | `PWM#init` 重载数 | 1 | **2**：`(Context, WindowManagerFuncs)` / `(Injector)` |
 *
 * ## 三、单击链路（本机实证）
 *
 * ```
 * 非早发：SingleKeyGestureDetector → PowerKeyRule#onKeyGesture → onPress
 *          → IInputExt#powerPress → PhoneWindowManagerExtImpl#oplusPowerPress
 *          → sendGoToSleepDelayMessage（延时 200ms）→ IPhoneWindowManagerWrapper#powerPress
 *          → 【PWM#powerPress(J I I)】
 * 早发：  PowerKeyRule#onKeyUp →（mShouldEarlyShortPressOnPower 且 count==1）→
 *          【PWM#powerPress(J I I)】；同时 onPress 第一行直接 return
 * ```
 *
 * 两条路径**互斥**，每次单击只调一次 `PWM#powerPress` ⇒ 它是唯一汇聚点。
 *
 * ## 四、多击如何区分（本实现最关键的一处修正）
 *
 * 直觉方案「读 `PWM#powerPress` 的 count 参数」在本机**是错的**：
 *
 * ```smali
 * # PowerKeyRule#onPress                —— 单击路径
 * const/4 v5, 0x1                       # count 硬编码 1
 * # OplusPowerKeyHandler#handleMessage  —— Oplus 的延时路径
 * const/4 v7, 0x1                       # 又压回 1
 * ```
 *
 * 而且非早发模式下双击会被 `SingleKeyGestureDetector` 攒到 `pressCount=2`，
 * 只调**一次** `completeDelayedPress` ⇒ `onMultiPress` ⇒ 最终也只到 `PWM#powerPress` **一次**。
 * 所以「数 `powerPress` 调用次数」同样判不出双击。
 *
 * ⇒ 唯一正解：hook `PowerKeyRule#onMultiPress` 打**一次性标记**，由 `powerPress` 消费。
 * 一次性（而非时间窗）是关键：多击只对应一次 `powerPress`，消费即清空，
 * 不会波及紧随其后的正常单击。另加 [MULTI_FLAG_TTL_MS] 兜底过期，
 * 防某版本改了调用关系导致标记永久残留（那会让单击彻底失效）。
 *
 * ## 五、熄屏手段与恢复模型
 *
 * ### 5.1 为什么用 `SurfaceControl.setDisplayPowerMode` 直调
 *
 * 系统正规路径是 `DisplayPowerState#setScreenState` → `PhotonicModulator`
 * → `LocalDisplayDevice$1#setDisplayState` → `DisplayAdapter#getPowerModeForState`
 * → `SurfaceControl#setDisplayPowerMode`。映射已实证
 * （`getPowerModeForState` 的 packed-switch：`STATE_OFF(1)→0`、`STATE_ON(2)→2`）。
 *
 * 走正规路径会让 DPC 完全接管：屏幕"真的"灭了 ⇒ 进 Doze、冻结后台、释放 wakelock
 * ⇒ **挂机就失效了**。所以必须直调最底层，跳过 DPC 状态机。
 *
 * ### 5.2 恢复模型：翻转语义 + 单一状态同步点
 *
 * **恢复动作就是「再按一次电源键」**。不需要额外手势、通知、超时或按钮 ——
 * 因为电源键本来就是「熄屏/亮屏」的翻转键，用户零学习成本。
 *
 * 翻转判据**不是**某个单一 API（本机 `SurfaceControl#getDisplayPowerMode` 并不存在，
 * 详见 [onPowerPress] 的注释表格），而是「系统清醒状态 + 自身标记」的组合。
 *
 * 「我们改屏」与「系统改屏」通过两个**互不相交**的通道区分：
 *
 * | 谁在改屏 | 走哪条路 | 会经过 `DisplayPowerState#setScreenState` 吗 |
 * |---|---|---|
 * | 本功能（假熄屏 / 点亮） | 直调 `SurfaceControl#setDisplayPowerMode` | **不会** |
 * | 系统（真休眠 / 真唤醒 / 其它组件强制亮屏） | `DisplayPowerState#setScreenState` | **一定经过** |
 *
 * ⇒ 只需 hook `DisplayPowerState#setScreenState`：一旦它要把屏幕设成**非 OFF**，
 * 说明系统正在接管屏幕，我们就清标记、**不跟系统抢屏**。
 * 这一个钩子替代了更早版本的三个（`finishedGoingToSleep` / `screenTurnedOff` / `screenTurnedOn`）。
 *
 * ### 5.3 来电 / 闹钟：按用户要求**不**自动点亮
 *
 * 更早版本 hook 了 `startedWakingUp` / `goToSleep` 强行退出假熄屏，本版**移除**：
 *
 * - 用户明确要求「来电 / 闹钟时屏幕不需要自动点亮」（挂机时屏幕突然亮起会中断挂机）；
 * - 这两个钩子在「正常锁屏」时也会触发，属于**误伤面**，去掉更安全；
 * - 要看屏幕时按电源键即可（翻转语义），行为可预期。
 *
 * 唯一副作用：来电时屏幕黑着、铃声照常响，按电源键即可看到来电界面。
 *
 * ## 六、屏幕超时：改为 hook 返回值，不写用户设置
 *
 * 假熄屏绕过 DPC，系统仍认为屏幕亮着 ⇒ `SCREEN_OFF_TIMEOUT` 照常倒数
 * ⇒ 到点**真睡眠** ⇒ App 被冻结 ⇒ 挂机失效。所以假熄屏期间必须延长超时。
 *
 * hook `PowerManagerService#getScreenOffTimeoutLocked(J J)J`，把返回值顶成
 * [FAKE_OFF_TIMEOUT_MS]：
 *
 * - 该方法全类**仅 1 个重载**，主调用方就是 `updateUserActivitySummaryLocked`
 *   （超时计时器的**唯一**重算点），命中率 100%；
 * - **纯内存返回值**，不碰任何持久化存储 ⇒ 进程重启自动复原，**无需还原逻辑**；
 * - 取 12 小时（而非 `MAX_VALUE`）：既远超任何挂机时长，又落在 float 尾数精度
 *   舒适区内（`getScreenDimDurationLocked` 是 `long→float→long`，超大值会丢精度）。
 *
 * **生效条件 = [fakeOffActive] 为真**，即「只在屏幕已被我们假灭时保护」。
 * 不按 [armed] 保护是刻意的：待命但屏幕亮着时，系统按用户设定正常熄屏是正确行为；
 * 若也保护，就会出现「开了磁贴后忘了，屏幕亮 12 小时」的耗电事故。
 *
 * 进入 / 退出假熄屏时各触发一次 `PowerManager#userActivity` 让计时器**立即重算**，
 * 否则新返回值要等下一次自然重算才生效。`userActivity` 在「系统认为屏幕亮着」
 * 的前提下只重置计时器、不会点亮屏幕，安全。
 *
 * ## 七、兜底
 *
 * 每处独立 `runCatching`；取不到 token / 反射失败只记日志并**原样放行**
 * （退化为原生电源键），绝不让 system_server 崩或卡。
 */
internal object FakeScreenHook {

    private const val TAG = "FakeScreen"

    /** `PhoneWindowManager` 全限定名 */
    private const val CLS_PWM = "com.android.server.policy.PhoneWindowManager"

    /** 电源键手势规则类（内部类，多击信号来源） */
    private const val CLS_POWER_KEY_RULE = "com.android.server.policy.PhoneWindowManager\$PowerKeyRule"

    /** 显示电源状态持有者（系统改屏的必经之路，唯一状态同步点） */
    private const val CLS_DISPLAY_POWER_STATE = "com.android.server.display.DisplayPowerState"

    /** 电源管理服务（超时计算的唯一入口） */
    private const val CLS_PMS = "com.android.server.power.PowerManagerService"

    /** 主线程 Handler（`PWM` 本身跑在主线程，投递到此保证与系统同序） */
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * 一次性多击标记：`onMultiPress` 置，下一次 `PWM#powerPress` 消费。
     * 值为置位时刻（`SystemClock.elapsedRealtime`），用于 TTL 兜底过期。
     */
    private val multiFlagAt = AtomicLong(0L)

    /** 多击标记的兜底有效期：超过则视为残留并忽略（防某版本改调用关系后单击永久失效） */
    private const val MULTI_FLAG_TTL_MS = 2_000L

    /**
     * **待命开关**（= 磁贴是否打开）。唯一持久化的状态，见类文档 §一。
     *
     * 只有它为 true 时，电源键才会被本功能接管；为 false 时 `powerPress`
     * 直接 `proceed()`，电源键行为与未安装本模块时**完全一致**。
     */
    private val armed = AtomicBoolean(false)

    /**
     * **当前是否正处假熄屏**（屏幕已被我们直调灭掉）。纯内存，不持久化。
     *
     * ⚠️ 它**不参与**「待命」判定（那是 [armed]），只用于两件事：
     * 1. 决定电源键这次该灭还是该亮（翻转语义）；
     * 2. 决定 [hookScreenTimeout] 是否改写超时返回值。
     *
     * 进程重启后必然为 false，且此时物理屏一定已亮（无持久化），完全自洽。
     */
    private val fakeOffActive = AtomicBoolean(false)

    /** 系统 Context（取自 `PWM#mContext`；hook `init` 后可用） */
    @Volatile
    private var systemContext: Context? = null

    /** system_server 类加载器（解析 `DisplayControl` / `SurfaceControl`） */
    @Volatile
    private var systemClassLoader: ClassLoader? = null

    /** 缓存的物理显示 token（显示重建时反射调用会失败，届时清空重取） */
    @Volatile
    private var cachedToken: IBinder? = null

    /** 缓存的 `SurfaceControl#setDisplayPowerMode` 方法对象 */
    @Volatile
    private var cachedSetPowerMode: Method? = null

    private val receiverRegistered = AtomicBoolean(false)

    // SurfaceControl 电源模式常量。
    // 已由 DisplayAdapter#getPowerModeForState 的 packed-switch 实证：
    //   STATE_OFF(1) → 0，STATE_ON(2) → 2，故本两值正确。
    private const val POWER_MODE_OFF = 0
    private const val POWER_MODE_NORMAL = 2

    /** `android.view.Display.STATE_OFF`（AOSP 稳定值，同上实证） */
    private const val DISPLAY_STATE_OFF = 1

    /** `PowerManager.USER_ACTIVITY_EVENT_OTHER`（AOSP 稳定值） */
    private const val USER_ACTIVITY_EVENT_OTHER = 0

    /**
     * 假熄屏期间的屏幕超时返回值（12 小时）。
     *
     * 为什么不用 `Long.MAX_VALUE` / `Int.MAX_VALUE`：
     * `PowerManagerService#getScreenDimDurationLocked` 会做
     * `Math.min(maxDimDuration, timeout * maxDimRatio)`，其中 `timeout` 先
     * `long-to-float` —— 超大值会丢精度甚至溢出。12 小时 = 4.32e7 ms，
     * 是「远超任何真实挂机时长」与「float 精度安全」的折中。
     */
    private const val FAKE_OFF_TIMEOUT_MS = 12L * 60L * 60L * 1000L

    // ================================================================ 安装

    fun install() {
        systemClassLoader = HookEnv.classLoader
        val pwm = Reflect.classOrNull(HookEnv.classLoader, CLS_PWM)
        if (pwm == null) {
            LogUtils.w(TAG, "install", "PhoneWindowManager 不存在（非 system_server？），跳过")
            return
        }
        val inits = hookInit(pwm)
        val okPower = hookPowerPress(pwm)
        val okMulti = hookOnMultiPress()
        val okSync = hookDisplayPowerState()
        val okTimeout = hookScreenTimeout()

        // powerPress 是执行点、onMultiPress 是判别点，缺一都会误熄屏。
        // 另两个是健壮性钩子：缺了功能仍可用，只是自愈 / 超时保护退化。
        if (inits.isEmpty() || !okPower || !okMulti) {
            LogUtils.e(
                TAG, "install",
                "关键钩子缺失（init=$inits powerPress=$okPower onMultiPress=$okMulti）" +
                    " —— 功能可能不生效或不准确，宿主版本可能已变",
            )
        } else {
            LogUtils.i(
                TAG, "install",
                "熄屏挂机已装：init=${inits.size} 个、powerPress ✓、onMultiPress ✓、" +
                    "状态同步 ${if (okSync) "✓" else "✗"}、超时保护 ${if (okTimeout) "✓" else "✗"}" +
                    "（待命态在 init 后从 Settings.Global 读回）",
            )
        }
    }

    /**
     * hook `PWM#init` 的**全部重载**，实例就绪后取系统 Context、读回待命态、注册磁贴广播。
     *
     * ColorOS 17 有两个重载（`(Context, WindowManagerFuncs)` / `(Injector)`）：上游只找第一个
     * 同名方法，命中哪个不确定；这里全挂，保证无论走哪条初始化路径都能拿到 Context。
     *
     * @return 已挂载的方法签名列表（全失败时为空）
     */
    private fun hookInit(pwm: Class<*>): List<String> {
        val hooked = mutableListOf<String>()
        pwm.declaredMethods
            .filter { it.name == "init" && it.parameterCount > 0 }
            .forEach { m ->
                runCatching {
                    m.isAccessible = true
                    HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val r = chain.proceed()
                            runCatching { attachContext(chain.thisObject) }
                            return r
                        }
                    })
                    hooked += "init(${m.parameterTypes.joinToString { it.simpleName }})"
                }.onFailure {
                    LogUtils.w(TAG, "hookInit", "挂 ${m.name} 失败：${it.message}")
                }
            }
        return hooked
    }

    /**
     * hook 电源键汇聚点 `PWM#powerPress`（**执行点**）。
     *
     * 按**形状**匹配（名字 + 第 0 参 long），不硬编码参数个数：AOSP 各版本的
     * `(J I)` / `(J Z I)` / `(J I I)` / `(J I I I)` 都能命中，宿主 OTA 后不易失效。
     */
    private fun hookPowerPress(pwm: Class<*>): Boolean {
        val target: Method? = runCatching {
            pwm.declaredMethods.firstOrNull { m ->
                m.name == "powerPress" &&
                    m.parameterCount >= 2 &&
                    m.parameterTypes[0] == java.lang.Long.TYPE
            }
        }.getOrNull()
        if (target == null) {
            LogUtils.e(TAG, "hookPowerPress", "找不到 powerPress(long, ...)，功能无法生效")
            return false
        }
        return runCatching {
            target.isAccessible = true
            HookEnv.module.hook(target).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? = onPowerPress(chain)
            })
            LogUtils.i(
                TAG, "hookPowerPress",
                "已挂 powerPress(${target.parameterTypes.joinToString { it.simpleName }})",
            )
            true
        }.getOrElse {
            LogUtils.e(TAG, "hookPowerPress", "挂载失败：${it.javaClass.simpleName}: ${it.message}")
            false
        }
    }

    /**
     * hook `PowerKeyRule#onMultiPress(J I I)`（**多击判别点**）。
     *
     * 只在真正的多击手势里被 `onKeyGesture` 调用（`pressCount > 1` 分支）。这里只置一次性标记
     * 然后照常 `proceed()` —— 多击手势（如双击开相机）完全保持系统原生行为。
     *
     * **未待命时不置标记**：此时电源键完全归系统，留个标记只会污染之后的首次单击。
     *
     * 该类是 `PhoneWindowManager` 的内部类，`onMultiPress` 是 private，但 hook 不受访问限制。
     */
    private fun hookOnMultiPress(): Boolean {
        val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_POWER_KEY_RULE)
        if (cls == null) {
            LogUtils.e(TAG, "hookOnMultiPress", "$CLS_POWER_KEY_RULE 不存在，多击将无法区分")
            return false
        }
        val target = runCatching {
            cls.declaredMethods.firstOrNull { it.name == "onMultiPress" }
        }.getOrNull()
        if (target == null) {
            LogUtils.e(TAG, "hookOnMultiPress", "找不到 onMultiPress，多击将无法区分")
            return false
        }
        return runCatching {
            target.isAccessible = true
            HookEnv.module.hook(target).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    if (isArmed()) {
                        // 先置标记再 proceed：不论内部走「立即」还是「延时 200ms」路径都能覆盖
                        multiFlagAt.set(SystemClock.elapsedRealtime())
                        LogUtils.d(TAG, "onMultiPress", "识别为多击，本次不熄屏")
                    }
                    return chain.proceed()
                }
            })
            LogUtils.i(TAG, "hookOnMultiPress", "已挂 PowerKeyRule#onMultiPress")
            true
        }.getOrElse {
            LogUtils.e(TAG, "hookOnMultiPress", "挂载失败：${it.javaClass.simpleName}: ${it.message}")
            false
        }
    }

    /**
     * hook `DisplayPowerState#setScreenState` —— **唯一的系统改屏同步点**。
     *
     * 这是「系统改屏」与「我们改屏」的**唯一分界**（见类文档 §5.2）：
     * 系统无论真休眠、真唤醒还是被其它组件强制亮屏，最终都会走到这里；
     * 而我们的假熄屏走的是 `SurfaceControl#setDisplayPowerMode` 直调，**不会**经过它。
     *
     * 因此只要在这里看到「系统要设成非 OFF」，就说明系统正在接管屏幕 ——
     * 清掉标记、放弃假熄屏，绝不跟系统抢同一个物理屏（抢屏会闪一下甚至黑屏）。
     *
     * 本机实证该**只有一个重载** `(I I)`（state + brightness）。按名字挂即可，
     * 不硬编码签名，防 OEM 日后加参数。
     *
     * 参数顺序已双重实证 `args[0] == state`：
     * ```smali
     * # DisplayPowerState#setScreenState(II)V  .registers 6 ⇒ p0=v3, p1=v4, p2=v5
     * iput v4, v3, ...->mScreenState:I          # v4 = 第 1 个参数 = state
     * # 调用方 DisplayPowerController
     * invoke-virtual {v0, v14, v15}, ...->setScreenState(II)V
     * #               v14=state（同函数已用于 debug.tracing.screen_state / noteScreenState）
     * #               v15=brightness
     * ```
     */
    private fun hookDisplayPowerState(): Boolean {
        val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_DISPLAY_POWER_STATE)
        if (cls == null) {
            LogUtils.w(TAG, "hookDisplayPowerState", "$CLS_DISPLAY_POWER_STATE 不存在，系统改屏将无法感知")
            return false
        }
        var hooked = 0
        cls.declaredMethods
            .filter { it.name == "setScreenState" }
            .forEach { m ->
                runCatching {
                    m.isAccessible = true
                    HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val state = runCatching { chain.args[0] as? Int }.getOrNull()
                            val r = chain.proceed()
                            if (state != null && state != DISPLAY_STATE_OFF) {
                                runCatching { onSystemTookOverScreen(state) }
                            }
                            return r
                        }
                    })
                    hooked++
                }.onFailure {
                    LogUtils.w(TAG, "hookDisplayPowerState", "挂 setScreenState 失败：${it.message}")
                }
            }
        if (hooked > 0) {
            LogUtils.i(TAG, "hookDisplayPowerState", "已挂 setScreenState $hooked 个重载")
        }
        return hooked > 0
    }

    /**
     * hook `PowerManagerService#getScreenOffTimeoutLocked(J J)J` —— 假熄屏期间延长超时。
     *
     * 该方法全类**仅 1 个重载**，主调用方是 `updateUserActivitySummaryLocked`
     * （超时计时器的唯一重算点）。改写返回值即可把下一次熄屏推到 12 小时后，
     * **不触碰任何持久化存储**（见类文档 §六）。
     *
     * 只在 [fakeOffActive] 为 true 时改写；其余时刻原样返回，完全不影响正常使用。
     */
    private fun hookScreenTimeout(): Boolean {
        val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_PMS) ?: run {
            LogUtils.w(TAG, "hookScreenTimeout", "$CLS_PMS 不存在，屏幕超时保护不可用")
            return false
        }
        val target = runCatching {
            cls.declaredMethods.firstOrNull {
                it.name == "getScreenOffTimeoutLocked" && it.returnType == java.lang.Long.TYPE
            }
        }.getOrNull()
        if (target == null) {
            LogUtils.w(TAG, "hookScreenTimeout", "找不到 getScreenOffTimeoutLocked，超时保护不可用")
            return false
        }
        return runCatching {
            target.isAccessible = true
            HookEnv.module.hook(target).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val r = chain.proceed()
                    if (!fakeOffActive.get()) return r
                    val origin = r as? Long ?: return r
                    // 只在「系统算出的超时比我们的更短」时顶长，绝不缩短用户原本的长超时
                    return if (origin in 0 until FAKE_OFF_TIMEOUT_MS) FAKE_OFF_TIMEOUT_MS else r
                }
            })
            LogUtils.i(TAG, "hookScreenTimeout", "已挂 getScreenOffTimeoutLocked（内存态，不写设置）")
            true
        }.getOrElse {
            LogUtils.e(TAG, "hookScreenTimeout", "挂载失败：${it.javaClass.simpleName}: ${it.message}")
            false
        }
    }

    // ================================================================ 拦截体

    /**
     * 电源键拦截。分支按优先级排列：
     *
     * | # | 条件 | 动作 | 理由 |
     * |---|---|---|---|
     * | 1 | 模块远程总闸关闭 | `proceed()` | 线上排障后路，完全原生 |
     * | 2 | **未待命**（磁贴关闭） | `proceed()` | 用户要求「关磁贴后恢复正常电源键」 |
     * | 3 | 本次是多击 | `proceed()` | 多击手势原样交还系统 |
     * | 4 | 系统真的睡了（`!isInteractive`） | `proceed()` | **只有系统能正确唤醒**（要退 Doze、恢复 wakelock），我们直调只会得到「屏亮着但 CPU 仍睡」 |
     * | 5 | 其余（清醒 + 待命） | **翻转**，返回 null | 阻止原生锁屏/休眠 |
     *
     * 第 4 条是自愈点：若真出现「系统睡了但 [fakeOffActive] 还残留 true」，
     * 交给系统正常唤醒，随后 [hookDisplayPowerState] 会看到 `setScreenState(非 OFF)`
     * 自动把标记清掉，下一次按键恢复正常的灭屏语义。
     *
     * ## 为什么不能只读某个 API 当翻转判据
     *
     * 本机 `framework.jar` 实证 **`SurfaceControl#getDisplayPowerMode` 并不存在**
     * （只有 `setDisplayPowerMode` / `nativeSetDisplayPowerMode`），所以拿不到
     * 「物理屏当前电源模式」这个最直接的读数。
     *
     * 其余候选读数各有偏差：
     *
     * | 读数 | 假熄屏时会怎样 | 能否直接用 |
     * |---|---|---|
     * | `PWM#isScreenOn()` → `DisplayPolicy#isScreenOnEarly()` | 仍返回 **true**（是系统视角） | ✗ 永远判成「亮着」⇒ 只灭不亮 |
     * | `PowerManager#isInteractive()` | 仍为 true（我们绕过了 PMS 睡眠流程） | ✗ 同上 |
     * | 自身 [fakeOffActive] 标记 | 正确 | ✓ 但需防漂移 |
     *
     * 所以「清醒 + 假熄屏标记」组合才够用：标记本身的漂移由
     * [hookDisplayPowerState] 兜住（系统一旦自行接管屏幕就清标记）。
     */
    private fun onPowerPress(chain: XposedInterface.Chain): Any? {
        // ★ 磁贴关闭 ⇒ 电源键完全归系统。这是用户明确要求的边界。
        if (!isArmed()) return chain.proceed()
        return runCatching {
            if (consumeMultiFlag()) return chain.proceed()
            // 系统真睡了：交还系统唤醒，我们直调只能把屏"照亮"、CPU 仍在睡
            if (!isSystemInteractive()) return chain.proceed()

            val turnOff = !fakeOffActive.get()
            applyDisplayPower(turnOff)
            // 返回 null = 拦截：系统不会走原生锁屏 / 休眠
            null
        }.getOrElse {
            LogUtils.e(TAG, "onPowerPress", "拦截体异常，放行原生行为：${it.message}")
            chain.proceed()
        }
    }

    /**
     * 消费多击标记。
     *
     * 只有「存在且未过期」才算命中；命中后立即清零（一次性语义）。
     * TTL 兜底见 [MULTI_FLAG_TTL_MS] 的说明。
     */
    private fun consumeMultiFlag(): Boolean {
        val at = multiFlagAt.get()
        if (at == 0L) return false
        // 无条件清空：无论是否过期都要消费掉，避免残留影响后续单击
        multiFlagAt.compareAndSet(at, 0L)
        val age = SystemClock.elapsedRealtime() - at
        if (age > MULTI_FLAG_TTL_MS) {
            LogUtils.w(TAG, "consumeMultiFlag", "多击标记已过期（${age}ms），按单击处理")
            return false
        }
        return true
    }

    /**
     * 系统是否处于「清醒」状态（屏幕应当亮）。
     *
     * `PowerManager#isInteractive()` 是 public API（实证 access_flags=1），
     * 反映 PMS 的 wakefulness 判断，正好用来识别「系统真的睡了」这种场景。
     *
     * 读不到时保守返回 true（当作清醒）⇒ 走正常翻转逻辑，不会破坏主流程。
     */
    private fun isSystemInteractive(): Boolean {
        val ctx = systemContext ?: return true
        return runCatching {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
                ?: return true
            pm.isInteractive
        }.getOrDefault(true)
    }

    /**
     * 真正切换显示电源模式，并同步派生状态。
     *
     * 顺序关键：**先切硬件，成功后才更新状态位**。反过来的话，切屏失败会留下
     * 「状态说已熄、屏幕还亮」，之后每次按键都去「点亮」一个本来就亮的屏。
     *
     * 不写 `Settings.Global`：[fakeOffActive] 是运行态，磁贴显示的是 [armed]，
     * 两者语义不同，不能混写（见类文档 §一）。
     */
    private fun applyDisplayPower(turnOff: Boolean) {
        val target = if (turnOff) POWER_MODE_OFF else POWER_MODE_NORMAL
        if (!setDisplayPowerMode(target)) {
            LogUtils.e(TAG, "applyDisplayPower", "setDisplayPowerMode($target) 失败，状态保持不变")
            return
        }
        fakeOffActive.set(turnOff)
        // 让 PMS 立即按新的超时返回值重算计时器；否则要等下一次自然重算才生效
        nudgeActivityTimeout()
        LogUtils.i(
            TAG, "applyDisplayPower",
            if (turnOff) "进入假熄屏（屏幕灭、系统与 App 继续运行）" else "退出假熄屏（屏幕点亮）",
        )
    }

    /**
     * 系统接管屏幕时的收尾：清当前假熄屏标记。
     *
     * **不写显示电源模式** —— 屏幕正由系统接管，我们再去写就是抢屏。
     * 也**不动 [armed]** —— 用户开着挂机开关，只是这次系统自己接管了屏幕，
     * 下次按电源键仍应能进假熄屏。
     */
    private fun onSystemTookOverScreen(state: Int) {
        if (!fakeOffActive.compareAndSet(true, false)) return
        LogUtils.i(
            TAG, "onSystemTookOverScreen",
            "系统接管屏幕（state=$state），已同步退出假熄屏（待命态保持 ${armed.get()}）",
        )
    }

    // ================================================================ 待命态

    /** 当前是否待命（模块远程总闸也一并算入：总闸关掉等于不待命） */
    private fun isArmed(): Boolean =
        armed.get() && HookEnv.settingBoolean(FakeScreenConfig.KEY_ENABLED, true)

    /**
     * 处理磁贴广播 —— **只改待命态，不碰屏幕**（除取消待命时必要的收尾）。
     *
     * 取消待命时若正处假熄屏，**必须先点亮屏幕再交还电源键**：否则会留下
     * 「屏幕黑着、电源键已归系统」的死锁 —— 系统视角屏幕仍算「亮」，按电源键走原生
     * `goToSleep`，DPC 判定 `targetState == currentState` 可能直接返回，屏幕就不亮了。
     */
    private fun onArmedChanged(target: Boolean) {
        val prev = armed.getAndSet(target)
        persistArmed(target)
        if (prev == target) {
            LogUtils.d(TAG, "onArmedChanged", "待命态未变（$target），忽略")
            return
        }
        if (!target && fakeOffActive.get()) {
            LogUtils.i(TAG, "onArmedChanged", "取消待命时正处假熄屏 ⇒ 先点亮屏幕再交还电源键")
            applyDisplayPower(false)
        }
        LogUtils.i(TAG, "onArmedChanged", "熄屏挂机待命态 → $target（不触发熄屏）")
    }

    /** 写待命态到 `Settings.Global` —— 磁贴进程读它刷新 UI 的唯一通道 */
    private fun persistArmed(value: Boolean) {
        val ctx = systemContext ?: return
        runCatching {
            Settings.Global.putInt(
                ctx.contentResolver, FakeScreenConst.GLOBAL_KEY, if (value) 1 else 0
            )
        }.onFailure {
            LogUtils.w(TAG, "persistArmed", "写 Settings.Global 失败（磁贴状态会不同步）：${it.message}")
        }
    }

    /** 从 `Settings.Global` 读回待命态（进程启动时恢复用户的开关选择） */
    private fun readArmed(): Boolean {
        val ctx = systemContext ?: return false
        return runCatching {
            Settings.Global.getInt(ctx.contentResolver, FakeScreenConst.GLOBAL_KEY, 0) == 1
        }.getOrDefault(false)
    }

    // ================================================================ Context / IPC

    /**
     * 从 `PWM` 实例取系统 Context，读回待命态，并注册磁贴广播接收器。
     *
     * `mContext` 在本机 `services.jar` 实证存在（类型 `Context`）；取不到时退回 `getContext()`
     * （部分 ROM 会加这个访问器）。
     *
     * 复位规则（两类状态区别对待，见类文档 §一）：
     * - [fakeOffActive] 无条件复位：进程重启时物理屏一定已亮，无需任何还原动作；
     * - [armed] **从 Settings.Global 读回**：那是用户的开关选择，不能被进程重启抹掉。
     */
    private fun attachContext(pwmInstance: Any?) {
        if (pwmInstance == null) return
        val ctx = (Reflect.getField(pwmInstance, "mContext") as? Context)
            ?: (Reflect.callByName(pwmInstance, "getContext") as? Context)
        if (ctx == null) {
            LogUtils.w(TAG, "attachContext", "PhoneWindowManager 取不到 Context，磁贴将不可用")
            return
        }
        // 统一用 applicationContext：避免持有短生命周期 Context 导致广播收不到
        systemContext = ctx.applicationContext ?: ctx
        registerArmedReceiver()

        runCatching {
            // 运行态：进程重启必然已亮屏 ⇒ 复位（超时保护是内存 hook，也自动失效）
            fakeOffActive.set(false)
            multiFlagAt.set(0L)
            // 用户选择：读回，保持磁贴开关与 hook 侧一致
            armed.set(readArmed())
        }
        LogUtils.i(TAG, "attachContext", "已取到系统 Context，待命态=${armed.get()}（假熄屏已复位）")
    }

    /**
     * 注册磁贴广播接收器。
     *
     * `RECEIVER_EXPORTED` 是必需的：发送方是模块 App（不同 uid）。外部应用理论上也能伪造
     * 这条广播，但效果至多等价于「用户点了控制中心磁贴」—— 切换待命态，
     * 已不再有「直接熄屏」这条可利用路径。
     */
    private fun registerArmedReceiver() {
        if (!receiverRegistered.compareAndSet(false, true)) return
        val ctx = systemContext ?: return
        runCatching {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action != FakeScreenConst.ACTION_SET_ARMED) return
                    val target = intent.getBooleanExtra(FakeScreenConst.EXTRA_ARMED, false)
                    runCatching {
                        mainHandler.post { runCatching { onArmedChanged(target) } }
                    }.onFailure {
                        LogUtils.e(TAG, "onReceive", "切换待命态失败：${it.message}")
                    }
                }
            }
            ctx.registerReceiver(
                receiver,
                IntentFilter(FakeScreenConst.ACTION_SET_ARMED),
                Context.RECEIVER_EXPORTED,
            )
            LogUtils.i(TAG, "registerArmedReceiver", "磁贴广播接收器已注册")
        }.onFailure {
            receiverRegistered.set(false)
            LogUtils.e(TAG, "registerArmedReceiver", "注册失败：${it.message}")
        }
    }

    // ================================================================ 显示底层

    /**
     * 直接改显示电源模式。
     *
     * token 与 Method 都是**懒解析 + 缓存**（`getDisplayToken` 会进 native，`getDeclaredMethod`
     * 每次克隆方法数组）；失败时清空缓存，下次重试而不是永久卡死。
     *
     * @return true = 调用成功；false = 拿不到 token 或反射失败（调用方不应改状态位）
     */
    private fun setDisplayPowerMode(mode: Int): Boolean {
        val loader = systemClassLoader ?: return false
        val token = cachedToken ?: displayToken(loader)?.also { cachedToken = it }
        if (token == null) {
            LogUtils.e(TAG, "setDisplayPowerMode", "取不到物理显示 token，无法切屏")
            return false
        }
        return runCatching {
            val m = cachedSetPowerMode ?: run {
                val sc = Reflect.classOrNull(loader, "android.view.SurfaceControl")
                    ?: return false
                sc.getDeclaredMethod(
                    "setDisplayPowerMode", IBinder::class.java, java.lang.Integer.TYPE
                ).also { it.isAccessible = true }.also { cachedSetPowerMode = it }
            }
            m.invoke(null, token, mode)
            true
        }.getOrElse {
            // 调用失败：缓存的 token 可能因显示重建而失效，全部作废以便下次重新解析
            cachedToken = null
            cachedSetPowerMode = null
            LogUtils.e(
                TAG, "setDisplayPowerMode",
                "反射调用失败（已清缓存待重试）：${it.javaClass.simpleName}: ${it.message}",
            )
            false
        }
    }

    /**
     * 取物理显示屏 token。
     *
     * 主路径（SDK 34+）：`DisplayControl#getPhysicalDisplayIds()` →
     * `getPhysicalDisplayToken(long)`，本机 `services.jar` 实证 public static。
     *
     * ⚠️ `SurfaceControl#getInternalDisplayToken` 在 ColorOS 17 **已整体移除**，
     * 上游 SDK 29~33 分支在本机恒失败；这里的回退仅为兼容其它设备。
     */
    private fun displayToken(loader: ClassLoader): IBinder? {
        runCatching {
            val dc = Reflect.classOrNull(loader, "com.android.server.display.DisplayControl")
                ?: return@runCatching
            val idsMethod = dc.getDeclaredMethod("getPhysicalDisplayIds")
            idsMethod.isAccessible = true
            val ids = idsMethod.invoke(null) as? LongArray ?: return@runCatching
            if (ids.isEmpty()) return@runCatching
            val tokenMethod = dc.getDeclaredMethod("getPhysicalDisplayToken", java.lang.Long.TYPE)
            tokenMethod.isAccessible = true
            val token = tokenMethod.invoke(null, ids[0]) as? IBinder
            if (token != null) return token
        }
        runCatching {
            val sc = Reflect.classOrNull(loader, "android.view.SurfaceControl") ?: return@runCatching
            val m = sc.getDeclaredMethod("getInternalDisplayToken")
            m.isAccessible = true
            return m.invoke(null) as? IBinder
        }
        return null
    }

    /**
     * 触发一次用户活动，让 PMS 立即重算熄屏计时器。
     *
     * 只是让新的超时返回值立刻生效，不点亮屏幕（系统视角屏幕仍是「亮」的）。
     * 失败无害：最多让新值晚一个周期生效。
     *
     * `PowerManager#userActivity(J I I)` 是 `@hide`，SDK 不可见（实证 access_flags=1
     * 即 public，仅被注解隐藏），故走反射。
     */
    private fun nudgeActivityTimeout() {
        val ctx = systemContext ?: return
        runCatching {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) ?: return@runCatching
            val m = pm.javaClass.getMethod(
                "userActivity",
                java.lang.Long.TYPE,
                java.lang.Integer.TYPE,
                java.lang.Integer.TYPE,
            )
            m.isAccessible = true
            m.invoke(pm, SystemClock.uptimeMillis(), USER_ACTIVITY_EVENT_OTHER, 0)
        }.onFailure {
            LogUtils.d(TAG, "nudgeActivityTimeout", "触发 userActivity 失败（无害）：${it.message}")
        }
    }
}

/**
 * 熄屏挂机的配置键。
 *
 * 产品要求「不需要开关」，故 App 侧不提供任何 UI；默认值 `true`。
 * 保留键名是给排障留后路：线上若出问题，可用 LSPosed 的 remote prefs 把
 * `SettingsPrefs/fake_screen_enabled` 改成 false 直接停用，无需重新发包。
 *
 * 注意这与磁贴的「待命开关」（`Settings.Global[colorostools_fake_screen]`）是两回事：
 * 这里是**模块级总闸**，磁贴是**用户级功能开关**，两者都开才生效。
 */
internal object FakeScreenConfig {
    const val KEY_ENABLED = "fake_screen_enabled"
}