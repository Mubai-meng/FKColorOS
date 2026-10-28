package com.fr.colorostools.tile

import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.fr.colorostools.R
import com.fr.colorostools.hook.FakeScreenConst
import com.fr.colorostools.utils.LogUtils

/**
 * 熄屏挂机的控制中心磁贴 —— **纯功能开关**。
 *
 * ## 语义（改过一次，别改回去）
 *
 * | 磁贴 | 含义 | 屏幕反应 |
 * |---|---|---|
 * | 打开 | 熄屏挂机**待命** | **毫无反应**。下一次按电源键才进入假熄屏 |
 * | 关闭 | 取消待命 | **毫无反应**。电源键立刻回归系统原生（含真锁屏） |
 *
 * 早期版本是「点磁贴立刻熄屏」，用户反馈这不是想要的：磁贴应当是一个功能开关，
 * 而不是一个「立即执行」按钮 —— 在控制中心误触就会黑屏，且想熄屏时还得专门回来点它。
 *
 * 现在熄屏**只由电源键触发**（见 `FakeScreenHook#onPowerPress`），磁贴只决定
 * 「电源键要不要被接管」。
 *
 * ## 职责
 *
 * 只做「转发 + 显示」，不含任何熄屏逻辑：
 * - 点击 → 向 system_server 发**定向广播**（带目标待命状态），由 `FakeScreenHook` 记录；
 * - 显示 → 读 `Settings.Global`（hook 侧写入）刷新开关态。
 *
 * 之所以不在磁贴进程直接改显示电源模式：`SurfaceControl#setDisplayPowerMode` 需要
 * system_server 的 display token 与权限，普通 App 进程拿不到。
 *
 * ## 状态同步的两个时机
 *
 * `getQsTile()` 只在服务处于监听状态时非 null，因此**每次调用都重新取 + 判空**，不缓存：
 * - [onStartListening]：下拉控制中心时可靠刷新（磁贴被添加时 [onTileAdded] 往往尚未绑定，
 *   所以真正的刷新放这里）；
 * - [onClick]：点击后立刻按目标状态刷新，不等 system_server 落库回读（避免视觉延迟）。
 */
class FakeScreenTileService : TileService() {

    private companion object {
        const val TAG = "FakeScreenTile"
    }

    override fun onStartListening() {
        super.onStartListening()
        applyTileState(FakeScreenConst.isArmed(this))
    }

    override fun onTileAdded() {
        super.onTileAdded()
        // 此时 tile 通常还没绑定，尽力刷新一次；真正的刷新在 onStartListening
        applyTileState(FakeScreenConst.isArmed(this))
    }

    override fun onClick() {
        super.onClick()
        val target = !FakeScreenConst.isArmed(this)
        sendArmed(target)
        // 立即刷 UI：system_server 落库有延迟，这里按用户意图先反馈
        applyTileState(target)
        LogUtils.i(TAG, "onClick", "请求切换到 armed=$target（不触发熄屏）")
    }

    /**
     * 发定向广播给 system_server。
     *
     * `setPackage("android")` 是必需的：不加的话这是一条隐式广播，
     * system_server 注册的接收器默认可收到，但加上包名可避免被其它应用监听/劫持。
     * `FLAG_RECEIVER_FOREGROUND` 让接收队列优先处理，点击反馈更跟手。
     */
    private fun sendArmed(armed: Boolean) {
        runCatching {
            val intent = Intent(FakeScreenConst.ACTION_SET_ARMED).apply {
                setPackage(FakeScreenConst.SYSTEM_SERVER_PKG)
                putExtra(FakeScreenConst.EXTRA_ARMED, armed)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
            sendBroadcast(intent)
        }.onFailure {
            LogUtils.e(TAG, "sendArmed", "广播失败：${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /** 把待命状态刷到磁贴上（`getQsTile()` 可能为 null，必须每次判空） */
    private fun applyTileState(armed: Boolean) {
        runCatching {
            val tile = qsTile ?: return
            tile.state = if (armed) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.label = getString(R.string.fake_screen_tile_label)
            // 图标跟着待命态走：亮起 = 「下一次按电源键会假熄屏」
            tile.icon = Icon.createWithResource(
                this,
                if (armed) R.drawable.ic_tile_fake_screen_on else R.drawable.ic_tile_fake_screen_off,
            )
            tile.updateTile()
        }.onFailure {
            LogUtils.w(TAG, "applyTileState", "刷新磁贴失败：${it.message}")
        }
    }

    /**
     * 便于「磁贴列表」页展示的服务描述（当前未使用，保留给后续按需扩展）。
     */
    @Suppress("unused")
    private fun selfComponent(): ComponentName = ComponentName(this, FakeScreenTileService::class.java)
}