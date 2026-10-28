package com.fr.colorostools.hook.xiaobuswipe

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.inputmethodservice.InputMethodService
import com.fr.colorostools.utils.LogUtils
import java.io.File

/**
 * 宿主输入法剪贴板历史的最新条目（只读）。
 *
 * ## 为什么直接读库
 *
 * 宿主自己的「粘贴」按钮只给纯文本，而小布输入法的剪贴板历史里**图片/视频条目**是
 * `content://` URI，想粘到编辑器只能：
 * 1. 把 URI 写回系统剪贴板，再触发原生粘贴（宿主/编辑器据此走 media 粘贴链路）；
 * 2. 或 `InputConnection#commitContent`。
 *
 * 拿不到宿主内部 API 时，直接只读打开它的 Room 数据库最直接。
 *
 * ## 库结构（1.8.35.17 实证）
 *
 * - 数据库名 `KeyBoardDatabase`（`Room.databaseBuilder(..., "KeyBoardDatabase")`）
 * - 表 `tab_clipboard_data`：
 *   `clipboard_id / clipboard_type / clipboard_text / clipboard_preview_text /
 *    clipboard_uri / clipboard_time / clipboard_mime_types`
 *
 * 以 `clipboard_time DESC LIMIT 1` 取最新一条。
 *
 * ## 安全性
 * - 只读打开（`OPEN_READONLY`），绝不写入宿主的库（写坏用户剪贴板历史不可接受）；
 * - 全程 `runCatching`，任何异常都只记一行日志并返回 null（动作层自动退回系统剪贴板）。
 */
internal object ImeClipboard {

    private const val TAG = SwipeConst.TAG
    private const val DB_NAME = "KeyBoardDatabase"
    private const val TABLE = "tab_clipboard_data"

    /** 条目类型（宿主 `clipboard_type` 字段实测取值：1=文本 2=图片 3=视频，0=未知） */
    private const val TYPE_TEXT = 1
    private const val TYPE_IMAGE = 2
    private const val TYPE_VIDEO = 3

    /** 剪贴板条目快照 */
    class Entry(
        val type: Int,
        val text: String,
        val uri: String,
        val mimeTypes: String,
    ) {
        val isMedia: Boolean
            get() = type == TYPE_IMAGE || type == TYPE_VIDEO ||
                mimeTypes.startsWith("image/") || mimeTypes.startsWith("video/")

        /** 可直接插入的纯文本；富媒体 / 同步占位 / URI 型条目返回空串 */
        val plainText: String
            get() {
                if (isMedia || text.isEmpty()) return ""
                val trimmed = text.trim()
                // `[wtsync:` 是跨端同步的占位文本，粘出来是垃圾
                if (trimmed.startsWith("[wtsync:")) return ""
                if (uri.startsWith("content://") || uri.startsWith("file://")) return ""
                return text
            }

        /** URI 型条目解析为 Uri（仅 content:// file:// 接受） */
        fun uriOrNull(): Uri? {
            val u = uri.trim()
            if (!u.startsWith("content://") && !u.startsWith("file://")) return null
            return runCatching { Uri.parse(u) }.getOrNull()
        }
    }

    /**
     * 读最新一条。返回 null = 库不存在 / 表不存在 / 无记录 / 读取失败。
     *
     * 每次粘贴读一次（按键级频率），用 `OPEN_READONLY` 不建 WAL，开销可接受；
     * 不使用 `SQLiteDatabase.openDatabase` 的默认模式，避免在宿主库目录留下 `-wal` 文件。
     */
    fun latest(ime: InputMethodService?): Entry? {
        if (ime == null) return null
        return runCatching {
            val dataDir = ime.applicationInfo?.dataDir ?: return null
            if (dataDir.isEmpty()) return null
            val file = File(dataDir, "databases/$DB_NAME")
            if (!file.exists()) {
                LogUtils.d(TAG, "clipboard", "宿主剪贴板库不存在：${file.absolutePath}")
                return null
            }
            val db = SQLiteDatabase.openDatabase(
                file.absolutePath, null, SQLiteDatabase.OPEN_READONLY
            )
            db.use { database ->
                database.rawQuery(
                    "SELECT clipboard_type, clipboard_text, clipboard_uri, clipboard_mime_types " +
                        "FROM $TABLE ORDER BY clipboard_time DESC LIMIT 1",
                    null
                ).use { c ->
                    if (!c.moveToFirst()) return null
                    val type = c.getInt(0)
                    val text = if (c.isNull(1)) "" else c.getString(1)
                    val uri = if (c.isNull(2)) "" else c.getString(2)
                    val mime = if (c.isNull(3)) "" else c.getString(3)
                    Entry(type, text, uri, mime)
                }
            }
        }.getOrElse {
            LogUtils.d(TAG, "clipboard", "读宿主剪贴板历史失败：${it.javaClass.simpleName}: ${it.message}")
            null
        }
    }
}