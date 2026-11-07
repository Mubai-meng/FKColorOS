package com.fr.colorostools.utils

import android.util.Log
import org.lsposed.lsparanoid.Obfuscate

/**
 * 统一日志出口：**全部输出到 LSPosed 框架日志**（LSPosed → 日志 页面）。
 *
 * 单一开关「启用详细日志」[enable]（设置页）：
 * - **开启** —— 所有级别（V/D/I/W/E）都投递到 LSPosed 日志页；
 * - **关闭** —— 只投递 `ERROR`，其余级别直接丢弃（连消息字符串都不拼）。
 *
 * 唯一通道 [frameworkSink]：由模块入口（`MainHook.onModuleLoaded`）注入
 * `XposedInterface.log(priority, tag, msg)`。
 *
 * ⚠️ [frameworkSink] 只在 **hook 进程**可用；App 本体的进程（界面 / 磁贴 / 服务）
 * 不是注入进程，拿不到 `XposedInterface`，此时退回 `android.util.Log`（logcat），
 * 以免 App 侧日志彻底蒸发。hook 进程的日志严格只走 LSPosed，不重复写 logcat。
 *
 * 历史说明：早期版本还有「文件通道」（MediaStore 写入
 * `Download/ColorOSTools/logs/`），随日志页下线一并移除 —— 分区存储下经
 * MediaStore 落盘要跨进程复用条目、异步队列、7 天清理，维护成本远高于收益。
 */
@Suppress("MemberVisibilityCanBePrivate")
@Obfuscate
object LogUtils {
    /**
     * 日志 tag：LSPosed 日志页的正文前缀。
     *
     * 取值 = App 名（`res/values/strings.xml` 的 `app_name`），使日志页里
     * 「标题（模块名）→ 正文（tag）」保持一致，不出现「FKColorOS 却写 ColorOSTools」的错位。
     */
    const val globalTag = "FKColorOS"

    /**
     * 「启用详细日志」开关。
     * - 开启：所有级别输出到 LSPosed 日志页；
     * - 关闭：只有 `ERROR` 输出到 LSPosed 日志页。
     */
    @Volatile
    var enable: Boolean = false

    /**
     * hook 进程内用于**动态**读取开关的回调（走 remote prefs 的内存镜像，开销极低）。
     * App 进程不设置；hook 进程由 `HookEnv.attach` 注入，使设置页改动无需重启宿主即可生效。
     */
    @Volatile
    var enableProvider: (() -> Boolean)? = null

    /**
     * 框架日志通道：hook 进程内由模块入口（`MainHook.onModuleLoaded`）注入
     * `XposedInterface.log(priority, tag, msg)`，输出即出现在 LSPosed 的「日志」页面。
     * App 进程未注入，恒为 null。
     */
    var frameworkSink: ((priority: Int, tag: String, message: String) -> Unit)? = null

    /** 当前开关状态：hook 进程优先取动态 provider；App 进程用 [enable] 快照 */
    private fun verboseEnabled(): Boolean = enableProvider?.invoke() ?: enable

    /**
     * 详细日志是否开启（供**热点路径**提前判断，跳过昂贵的实参构造）。
     *
     * Kotlin 的字符串模板 / `String.format` 在**实参求值时**执行，与开关无关 ——
     * 日志关着也照样付出拼接开销。高频路径（键盘每次弹出、桌面菜单每次渲染）
     * 应先判这个再构造消息。
     */
    fun isEnabled(): Boolean = verboseEnabled()

    /**
     * @param send 调用方显式关掉这一条（高频路径的可选日志）。传 `false` 时连消息字符串都不拼。
     */
    private fun emit(priority: Int, tag: String, method: String, msg: String, send: Boolean) {
        if (!send) return
        // 关闭详细日志：只放行 ERROR，其余级别在拼字符串之前就丢弃
        if (priority < Log.ERROR && !verboseEnabled()) return

        val text = "$tag: $method -> $msg"
        val sink = frameworkSink
        val delivered = sink != null && runCatching { sink(priority, globalTag, text) }.isSuccess
        // 框架通道不可用（App 进程，或框架侧异常）：退回 logcat，避免日志彻底消失
        if (!delivered) Log.println(priority, globalTag, text)
    }

    fun d(tag: String, method: String, msg: String, send: Boolean = true) =
        emit(Log.DEBUG, tag, method, msg, send)

    fun e(tag: String, method: String, msg: String, send: Boolean = true) =
        emit(Log.ERROR, tag, method, msg, send)

    fun i(tag: String, method: String, msg: String, send: Boolean = true) =
        emit(Log.INFO, tag, method, msg, send)

    fun v(tag: String, method: String, msg: String, send: Boolean = true) =
        emit(Log.VERBOSE, tag, method, msg, send)

    fun w(tag: String, method: String, msg: String, send: Boolean = true) =
        emit(Log.WARN, tag, method, msg, send)
}