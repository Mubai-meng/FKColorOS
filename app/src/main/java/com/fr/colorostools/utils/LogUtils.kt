package com.fr.colorostools.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import org.lsposed.lsparanoid.Obfuscate
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 统一日志出口。
 *
 * **总开关 [enable]**（设置页「启用模块日志」）：
 * - 开启 —— 三条通道全部输出；
 * - 关闭 —— **不输出任何常规日志**，仅把 `ERROR` 级别投递到框架日志（LSPosed → 日志页），便于排障。
 *
 * 三条通道：
 * 1. **logcat** —— `android.util.Log`，tag 固定为 [globalTag]（`ColorOSTools`），
 *    用 `adb logcat -s ColorOSTools` 或任何日志应用查看。
 * 2. **框架日志** —— 由 hook 进程的模块入口注入 [frameworkSink]（即 `XposedInterface.log`），
 *    输出会出现在 **LSPosed → 日志** 页面。App 进程未注入，恒为 null。
 * 3. **文件** —— 经 **MediaStore.Downloads** 写入
 *    `Download/ColorOSTools/logs/ColorOSTools_yyyy-MM-dd.log`（懒初始化）。
 *    2026-10-16 真机实证：ColorOS 17 分区存储下 File API 写 `/sdcard/Download` 必失败
 *    （含 uid=1000 系统进程与普通 uid 宿主进程），故改走 MediaStore —— 自己 insert 的
 *    Downloads 条目无需任何存储权限即可持续追加；失败时静默永久降级，不影响调用方。
 */
@Suppress("MemberVisibilityCanBePrivate")
@Obfuscate
object LogUtils {
    const val globalTag = "ColorOSTools"

    /**
     * 模块日志总开关（设置页「启用模块日志」）。
     * - 开启：logcat / 框架日志 / 文件 三条通道全部输出；
     * - 关闭：不输出任何常规日志，仅把 `ERROR` 级别投递到框架日志（LSPosed → 日志页）。
     */
    @Volatile
    var enable: Boolean = false

    /**
     * hook 进程内用于**动态**读取总开关的回调（走 remote prefs 的内存镜像，开销极低）。
     * App 进程不设置；hook 进程由 `HookEnv.attach` 注入，使设置页改动无需重启即可生效。
     */
    @Volatile
    var enableProvider: (() -> Boolean)? = null

    /**
     * 框架日志通道：hook 进程内由模块入口（`MainHook.onModuleLoaded`）注入
     * `XposedInterface.log(priority, tag, msg)`，使日志出现在 LSPosed 的「日志」页面。
     */
    var frameworkSink: ((priority: Int, tag: String, message: String) -> Unit)? = null

    private const val LOG_DIR_RELATIVE = "ColorOSTools/logs"
    private const val RETENTION_DAYS = 7

    /**
     * 文件通道用的 Context（App 进程由 MyApplication 注入、hook 进程由 HookEnv.attach 注入）。
     * MediaStore 需要 ContentResolver；未注入时文件通道静默不可用（其余通道不受影响）。
     */
    @Volatile
    var appContext: Context? = null

    private var logStream: OutputStream? = null
    private var logStreamDay: String = ""
    private var fileSinkDisabled = false

    /**
     * 初始化文件通道（可重复调用，懒执行）。MediaStore 方案无需任何存储权限；
     * 插入/追加失败时静默降级。
     */
    @Synchronized
    fun initFileSink() {
        if (fileSinkDisabled) return
        if (appContext == null) {
            // context 未注入（极端早期）：不降级，等注入后 emit 会再次触发
            return
        }
        runCatching { streamForToday() }
            .onFailure { noteFileSinkBroken("初始化失败 ${it.javaClass.simpleName}: ${it.message}") }
    }

    /**
     * 文件通道损坏（插入/追加失败…）：关句柄、**永久降级**为仅 logcat/框架日志，
     * 并向框架日志报一条 **ERROR** —— 总开关关闭时错误也照样进 LSPosed 日志页，便于一眼看出
     * 「日志没落盘」是被这里拦掉的。
     */
    @Synchronized
    private fun noteFileSinkBroken(reason: String) {
        fileSinkDisabled = true
        runCatching { logStream?.close() }
        logStream = null
        e("LogUtils", "fileSink", "文件通道不可用，已降级为仅 logcat/框架日志 -> $reason")
    }

    /** 日志位置描述（未初始化时为 null；诊断用） */
    val logDirPath: String? get() = if (logStream != null) "Download/$LOG_DIR_RELATIVE" else null

    private fun fileName(day: String) = "ColorOSTools_$day.log"

    /**
     * 日期/时间格式化器：**ThreadLocal 复用**。
     *
     * 原实现是 `private val dayFmt get() = SimpleDateFormat(...)` —— 每次访问都 new 一个，
     * 加上 emit 里每条日志再 new 一个时间戳格式化器，主线程路径上每条日志两次 SDF 构造。
     * SimpleDateFormat 非线程安全，故每线程一份；单线程内 format/parse 交替使用是安全的。
     */
    private val dayFmtLocal = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    }
    private val timeFmtLocal = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    }
    private val dayFmt: SimpleDateFormat get() = dayFmtLocal.get()!!
    private val timeFmt: SimpleDateFormat get() = timeFmtLocal.get()!!

    /**
     * 取当天日志的追加流（MediaStore.Downloads）。
     *
     * - 先按文件名查当天条目（**跨进程复用**：App 进程与 hook 进程交替写同一份，
     *   避免 MediaStore 自动改名产生 `xxx (1).log` 副本）；
     * - 查不到则 insert（IS_PENDING=1 → 开追加流 → IS_PENDING=0 立即可见）；
     * - 顺带清理 [RETENTION_DAYS] 天前的旧文件。
     */
    @Synchronized
    private fun streamForToday(): OutputStream? {
        val ctx = appContext ?: return null
        val day = dayFmt.format(Date())
        logStream?.let { if (logStreamDay == day) return it }
        val resolver = ctx.contentResolver
        val proj = arrayOf(MediaStore.MediaColumns._ID)
        val sel = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val existingId = runCatching {
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, proj, sel,
                arrayOf(fileName(day), "%$LOG_DIR_RELATIVE%"), null,
            )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        }.getOrNull()
        val uri = existingId
            ?.let { MediaStore.Downloads.EXTERNAL_CONTENT_URI.buildUpon().appendPath(it.toString()).build() }
            ?: runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName(day))
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/" + LOG_DIR_RELATIVE,
                    )
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            }.getOrNull() ?: error("MediaStore insert 失败")
        val out = resolver.openOutputStream(uri, "wa") ?: error("openOutputStream 失败")
        runCatching {
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        }
        pruneOldDownloads(resolver, day)
        runCatching { logStream?.close() }
        logStream = BufferedOutputStream(out)
        logStreamDay = day
        return logStream
    }

    /** 只保留最近 [RETENTION_DAYS] 天的日志文件（按 DATE_ADDED 删） */
    private fun pruneOldDownloads(resolver: android.content.ContentResolver, today: String) {
        runCatching {
            val cutoffMs = dayFmt.parse(today)?.time ?: return
            val keepFromSec = (cutoffMs - (RETENTION_DAYS - 1) * 24L * 60 * 60 * 1000) / 1000
            val sel = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND " +
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? AND " +
                "${MediaStore.MediaColumns.DATE_ADDED} < ?"
            resolver.delete(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, sel,
                arrayOf("%$LOG_DIR_RELATIVE%", "ColorOSTools_%", keepFromSec.toString()),
            )
        }
    }

    // ------------------------------------------------------------------ 文件通道异步写盘

    /**
     * 文件通道**异步化**：原实现每条日志都在**主线程** `out.write` + `out.flush()`，
     * 而 flush 到 MediaStore 的 fd 是一次 FUSE write syscall（ColorOS 上 200~500µs）。
     * 所有 hook 点都在主线程打日志 —— 键盘弹出（setInputView）、桌面菜单渲染等路径被无谓拖慢。
     *
     * 现在常规日志只做主线程内的字符串拼装 + 入队（微秒级），由专用线程串行落盘；
     * **ERROR 仍同步写**（崩溃前最后几条必须落盘，排查价值高于这点开销）。
     * 队列满时丢弃最旧一条，保证内存不膨胀。
     */
    private const val FILE_QUEUE_CAP = 2048
    private val fileQueue = java.util.concurrent.LinkedBlockingQueue<String>(FILE_QUEUE_CAP)

    @Volatile
    private var writerStarted = false

    private fun startFileWriter() {
        if (writerStarted) return
        synchronized(fileQueue) {
            if (writerStarted) return
            writerStarted = true
            Thread({
                while (true) {
                    val line = try {
                        fileQueue.take()
                    } catch (e: InterruptedException) {
                        break
                    }
                    if (fileSinkDisabled) continue
                    runCatching {
                        val out = streamForToday() ?: return@runCatching
                        out.write(line.toByteArray())
                        out.flush()
                    }.onFailure {
                        noteFileSinkBroken("写入失败 ${it.javaClass.simpleName}: ${it.message}")
                    }
                }
            }, "ColorOSTools-LogWriter").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }.start()
        }
    }

    /** 常规日志入队（非阻塞，不碰磁盘） */
    private fun enqueueFileLine(line: String) {
        if (fileSinkDisabled) return
        if (!fileQueue.offer(line)) {
            fileQueue.poll()
            fileQueue.offer(line)
        }
        startFileWriter()
    }

    /** 同步写一条（仅 ERROR 级：崩溃前最后几条必须落盘） */
    private fun writeFileNow(line: String) {
        if (fileSinkDisabled) return
        runCatching {
            if (logStream == null) initFileSink()
            val out = streamForToday() ?: return@runCatching
            out.write(line.toByteArray())
            out.flush()
        }.onFailure { noteFileSinkBroken("写入失败 ${it.javaClass.simpleName}: ${it.message}") }
    }

    private fun levelName(priority: Int): String = when (priority) {
        Log.VERBOSE -> "V"
        Log.DEBUG -> "D"
        Log.INFO -> "I"
        Log.WARN -> "W"
        Log.ERROR -> "E"
        else -> "?"
    }

    /** 当前总开关状态：hook 进程优先取动态 provider；App 进程用 [enable] 快照 */
    private fun loggingEnabled(): Boolean = enableProvider?.invoke() ?: enable

    /**
     * 日志开关状态（供**热点路径**提前判断，跳过昂贵的实参构造）。
     *
     * 为什么需要：Kotlin 的字符串模板 / `String.format` 是在**实参求值时**执行的，
     * 与日志开关无关 —— 日志关着也照样付出拼接和格式化的开销。热点路径（键盘每次弹出、
     * 桌面菜单每次渲染）应先判这个再构造消息。
     */
    fun isEnabled(): Boolean = loggingEnabled()

    private fun emit(priority: Int, tag: String, method: String, msg: String, send: Boolean) {
        // 调用方显式关掉这条（高频路径的可选日志）：连字符串都不拼
        if (!send) return
        val text = "$tag: $method -> $msg"

        // 总开关关闭：不输出任何常规日志，仅把错误投递到 logcat + 框架日志（LSPosed → 日志页）
        if (!loggingEnabled()) {
            if (priority >= Log.ERROR) {
                Log.println(priority, globalTag, text)
                frameworkSink?.let { sink -> runCatching { sink(priority, globalTag, text) } }
            }
            return
        }

        // 1) logcat
        Log.println(priority, globalTag, text)
        // 2) 框架日志（LSPosed → 日志页）
        frameworkSink?.let { sink -> runCatching { sink(priority, globalTag, text) } }
        // 3) 文件（best-effort；懒初始化，写失败永久降级）。ERROR 同步、其余入队异步落盘
        if (!fileSinkDisabled) {
            val line = "${timeFmt.format(Date())} ${levelName(priority)} $text\n"
            if (priority >= Log.ERROR) writeFileNow(line) else enqueueFileLine(line)
        }
    }

    // `send` 默认值**不再**取 [enable] 快照：hook 进程的 enable 只在 HookEnv.attach 时设一次，
    // 用它做默认值会让「运行中在设置页打开日志开关」对 hook 进程无效（enableProvider 只喂给了
    // loggingEnabled()，管不到 send）—— 必须重启宿主才生效。现在统一由 emit 内的
    // loggingEnabled() 判定，开关改动即时生效；调用方显式传 false 仍可关掉单条。
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
