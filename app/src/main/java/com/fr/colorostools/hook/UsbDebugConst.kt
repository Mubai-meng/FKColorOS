package com.fr.colorostools.hook

/**
 * USB 调试磁贴的跨进程约定。
 *
 * 与熄屏挂机（[FakeScreenConst]）不同，这里**不需要自定义状态 key**：
 * `Settings.Global.ADB_ENABLED`（`adb_enabled`）本身就是唯一状态位，而且
 * `framework.jar` 里该字段带 `@Settings$Readable` 注记 ⇒ 已进
 * `Settings$NameValueCache.mReadableFields` 白名单，普通 App 读取不会抛
 * `SecurityException: is not readable`。所以磁贴直接读系统 key 即可，
 * 不需要 hook 侧镜像。
 *
 * 唯一的落差在**写**：`adb_enabled` 需要 `WRITE_SECURE_SETTINGS`（signature|privileged），
 * 模块 App 拿不到 ⇒ 磁贴发定向广播给 system_server 代写。
 *
 * | 方向 | 载体 | 常量 |
 * |---|---|---|
 * | 磁贴 → system_server | 定向广播（`setPackage("android")`），发送方需持 [PERMISSION_TILE_CONTROL] | [ACTION_SET_USB_DEBUG] / [EXTRA_ENABLED] |
 * | system_server → 磁贴 | 无（磁贴直接读 `Settings.Global`） | [KEY_ADB_ENABLED] |
 */
object UsbDebugConst {

    /** 磁贴点击 → system_server：把 USB 调试设为指定状态（带目标值，不做翻转语义） */
    const val ACTION_SET_USB_DEBUG = "com.fr.colorostools.action.SET_USB_DEBUG"

    /** 广播 extra：目标状态（true = 开启 USB 调试） */
    const val EXTRA_ENABLED = "enabled"

    /**
     * 磁贴命令的签名级权限。
     *
     * USB 调试是**高危开关**：任何 App 若能伪造这条广播，就能悄悄打开调试通道
     * （配合设备上已授权的 adb key 等于提权）。所以接收器注册时把本权限作为
     * `broadcastPermission` 传入 —— 只有与模块同签名的进程能投递，杂散 App 的广播
     * 会在 AMS 层被直接丢弃。
     */
    const val PERMISSION_TILE_CONTROL = "com.fr.colorostools.permission.TILE_CONTROL"

    /** `Settings.Global.ADB_ENABLED` 的字面值（与 ColorOS 设置页 `OplusAdbPreferenceController` 同一个） */
    const val KEY_ADB_ENABLED = "adb_enabled"

    /** system_server 的包名（`setPackage` 定向广播用） */
    const val SYSTEM_SERVER_PKG = "android"
}
