package com.fr.colorostools.tile

import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.fr.colorostools.R
import com.fr.colorostools.hook.UsbDebugConst
import com.fr.colorostools.utils.LogUtils

/**
 * USB 调试的控制中心磁贴 —— 与开发者选项里那个开关**同一个状态位**。
 *
 * ## 为什么磁贴不能自己写
 *
 * `adb_enabled` 在 `Settings.Global` 里，写它要 `WRITE_SECURE_SETTINGS`
 * （signature|privileged），模块 App 没有。所以点击只做两件事：
 * 发一条定向广播给 system_server，由 [com.fr.colorostools.hook.hookers.UsbDebugHook]
 * 代写；然后按用户意图先刷 UI。
 *
 * 系统侧完全不用我们操心（本机 `services.jar` 实证）：
 *
 * ```
 * 写 adb_enabled
 *   → AdbService$AdbSettingsObserver#onChange
 *   → AdbService#setAdbEnabled(Z,B)
 *   → startAdbd() / stopAdbd()
 * ```
 *
 * 也就是说 adbd 启停、开发者选项开关的显示、重启后的持久化，全部由系统自己完成。
 *
 * ## 为什么读可以直接读
 *
 * `Settings.Global.ADB_ENABLED` 带 `@Settings$Readable` 注记，已进
 * `NameValueCache.mReadableFields` 白名单 ⇒ 第三方 App 读取不抛 SecurityException。
 * 因此在开发者选项里手动改过之后，磁贴下拉即刷新，不需要额外的状态镜像通道。
 *
 * ## 两个边界
 *
 * - **需重启一次**：写操作发生在 system_server 的 hook 里，模块刚装好、手机没重启时
 *   广播没人接。点击后会延时回读自校验，状态没变化就提示用户重启（静默失败最难排查）。
 * - **首次开启会弹系统授权框**（「允许 USB 调试吗」），那是 adbd 的正常行为，不是本模块的弹窗。
 */
class UsbDebugTileService : TileService() {

    private companion object {
        const val TAG = "UsbDebugTile"

        /** 点击后回读校验的延时：给 system_server 落库 + observer 回调留出余量 */
        const val VERIFY_DELAY_MS = 600L
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onStartListening() {
        super.onStartListening()
        applyTileState(isAdbEnabled())
    }

    override fun onTileAdded() {
        super.onTileAdded()
        // 此时 tile 往往还没绑定，尽力刷新一次；可靠刷新在 onStartListening
        applyTileState(isAdbEnabled())
    }

    override fun onClick() {
        super.onClick()
        val target = !isAdbEnabled()
        sendCommand(target)
        // 立即刷 UI：system_server 落库有延迟，这里先按用户意图反馈
        applyTileState(target)
        mainHandler.postDelayed({ verifyApplied(target) }, VERIFY_DELAY_MS)
        LogUtils.i(TAG, "onClick", "请求把 USB 调试切到 enabled=$target")
    }

    /**
     * 发定向广播给 system_server。
     *
     * `setPackage("android")` 让广播只投递给 system_server 注册的接收器；
     * 接收器侧还要求发送方持有 [UsbDebugConst.PERMISSION_TILE_CONTROL]（signature），
     * 所以这条链路既不会被别的应用截获，也不能被伪造。
     */
    private fun sendCommand(enabled: Boolean) {
        runCatching {
            val intent = Intent(UsbDebugConst.ACTION_SET_USB_DEBUG).apply {
                setPackage(UsbDebugConst.SYSTEM_SERVER_PKG)
                putExtra(UsbDebugConst.EXTRA_ENABLED, enabled)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
            sendBroadcast(intent)
        }.onFailure {
            LogUtils.e(TAG, "sendCommand", "广播失败：${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /**
     * 回读校验：目标状态没落地就说明 system_server 侧的接收器还没装上（通常是没重启）。
     *
     * 只在「读到的值与期望值不一致」时提示，避免和用户在开发者选项里的手动操作抢戏。
     */
    private fun verifyApplied(target: Boolean) {
        if (isAdbEnabled() == target) return
        LogUtils.w(TAG, "verifyApplied", "adb_enabled 未变为 $target —— system_server 侧钩子可能未生效")
        runCatching {
            Toast.makeText(this, R.string.usb_debug_tile_need_reboot, Toast.LENGTH_LONG).show()
        }
    }

    /** 读系统状态位（`@Readable` 白名单内，正常不会抛异常；仍兜底按「关闭」处理） */
    private fun isAdbEnabled(): Boolean = runCatching {
        Settings.Global.getInt(contentResolver, UsbDebugConst.KEY_ADB_ENABLED, 0) == 1
    }.getOrDefault(false)

    /** 把状态刷到磁贴上（`getQsTile()` 可能为 null，必须每次判空） */
    private fun applyTileState(enabled: Boolean) {
        runCatching {
            val tile = qsTile ?: return
            tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.label = getString(R.string.usb_debug_tile_label)
            tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_usb_debug)
            tile.updateTile()
        }.onFailure {
            LogUtils.w(TAG, "applyTileState", "刷新磁贴失败：${it.message}")
        }
    }
}
