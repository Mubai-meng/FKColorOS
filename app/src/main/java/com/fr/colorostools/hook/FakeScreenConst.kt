package com.fr.colorostools.hook

import android.content.Context
import android.provider.Settings

/**
 * 熄屏挂机（假熄屏）的跨进程约定。
 *
 * 磁贴进程（模块 App 自身）与 system_server（hook 侧）不是同一个进程，两者只通过
 * 两样东西通信：
 *
 * | 方向 | 载体 | 常量 |
 * |---|---|---|
 * | 磁贴 → system_server | 定向广播（`setPackage("android")`） | [ACTION_SET_ARMED] / [EXTRA_ARMED] |
 * | system_server → 磁贴 | `Settings.Global` | [GLOBAL_KEY] |
 *
 * ## 语义：磁贴只控制「待命」，不直接改屏
 *
 * [GLOBAL_KEY] 存的是**待命开关**（armed），不是「当前是否假熄屏」：
 *
 * - 磁贴打开 ⇒ 待命，**屏幕毫无反应**；下一次按电源键才进入假熄屏；
 * - 磁贴关闭 ⇒ 取消待命，电源键立刻回归系统原生行为（含真锁屏）。
 *
 * 这样磁贴就是一个纯粹的「功能开关」，用户在控制中心点它不会突然黑屏 ——
 * 熄屏永远只由电源键触发，行为可预期。
 *
 * 广播里带的是**目标状态**而非「翻转」指令：磁贴点击时用户看到的是已知状态，
 * 发目标状态可避免首次点击时两端对当前值理解不一致导致「点了没反应」。
 */
object FakeScreenConst {

    /** 磁贴点击 → system_server：设置「熄屏挂机待命」开关 */
    const val ACTION_SET_ARMED = "com.fr.colorostools.action.FAKE_SCREEN_SET_ARMED"

    /** 广播 extra：目标待命状态（true = 待命，false = 取消待命） */
    const val EXTRA_ARMED = "armed"

    /**
     * 当前是否「待命」（1 = 是）。由 system_server 写入，磁贴读取刷新 UI。
     *
     * 这是**用户的开关选择**，故持久化（重启后仍是用户上次的选择）；
     * 「当前是否正处假熄屏」是纯内存状态，不落盘（进程重启必然已亮屏）。
     *
     * `Settings.Global` 而非 `Settings.System`：这是设备的运行开关，不属于用户显示配置，
     * 也不该出现在系统设置备份里。
     */
    const val GLOBAL_KEY = "colorostools_fake_screen"

    /** system_server 的包名（`setPackage` 定向广播用） */
    const val SYSTEM_SERVER_PKG = "android"

    /** 读取当前是否待命（磁贴侧用；读失败按「未待命」处理，绝不误熄屏） */
    fun isArmed(context: Context): Boolean = runCatching {
        Settings.Global.getInt(context.contentResolver, GLOBAL_KEY, 0) == 1
    }.getOrDefault(false)
}