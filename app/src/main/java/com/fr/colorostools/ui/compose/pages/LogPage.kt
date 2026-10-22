package com.fr.colorostools.ui.compose.pages

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fr.colorostools.R
import com.fr.colorostools.ui.compose.components.TopBarActionButton
import com.fr.colorostools.ui.compose.components.TopBarOverflowItem
import com.fr.colorostools.ui.compose.components.TopBarOverflowMenu
import com.fr.colorostools.ui.compose.components.material.MaterialPageScaffold
import com.fr.colorostools.utils.FileUtils
import com.fr.colorostools.utils.copyStr
import com.fr.colorostools.utils.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 日志列表最多渲染的行数（超大文件时只显示末尾部分，避免列表与内存压力） */
private const val MAX_LOG_LINES = 3000

/**
 * Logs 页：展示模块日志文件。
 *
 * 日志由 [com.fr.colorostools.utils.LogUtils] 写入 `Download/ColorOSTools/logs/`，
 * 按天分文件（`ColorOSTools_yyyy-MM-dd.log`）、同日追加、保留最近 7 天。
 *
 * 顶栏四个动作（与改版前一致）：刷新 / 级别筛选 / 复制全文 / 分享日志文件。
 */
@Composable
fun LogPage() {
    val context = LocalContext.current
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var logUri by remember { mutableStateOf<Uri?>(null) }
    var reloadTick by remember { mutableStateOf(0) }
    var filter by remember { mutableStateOf(LogLevelFilter.All) }

    // 定时自动重读：框架绑定 / hook 侧写入都发生在页面打开之后，只读一次会误显示「暂无日志」
    LaunchedEffect(reloadTick) {
        while (true) {
            val result = withContext(Dispatchers.IO) { readLatestLog(context) }
            logUri = result.first
            lines = result.second
            delay(2000)
        }
    }

    val shown = remember(lines, filter) { filter.apply(lines) }

    MaterialPageScaffold(
        title = stringResource(R.string.nav_log),
        actions = {
            // 1) 刷新
            TopBarActionButton(
                onClick = { reloadTick++ },
                contentDescription = stringResource(R.string.log_refresh),
                painter = painterResource(R.drawable.ic_baseline_refresh_24),
            )
            // 2) 级别筛选（溢出菜单）
            TopBarOverflowMenu(
                items = LogLevelFilter.entries.map { level ->
                    TopBarOverflowItem(
                        label = level.label(context),
                        onClick = { filter = level },
                    )
                },
                contentDescription = stringResource(R.string.log_filter),
            )
            // 3) 复制全文
            TopBarActionButton(
                onClick = {
                    val text = shown.joinToString("\n")
                    if (text.isBlank()) {
                        context.showToast(context.getString(R.string.log_empty))
                    } else {
                        context.copyStr(text)
                        context.showToast(context.getString(R.string.log_copied))
                    }
                },
                contentDescription = stringResource(R.string.log_copy),
                painter = painterResource(R.drawable.ic_baseline_save_24),
            )
            // 4) 分享日志文件
            TopBarActionButton(
                onClick = { shareLogFile(context, logUri) },
                contentDescription = stringResource(R.string.log_share),
                painter = painterResource(R.drawable.baseline_share_24),
            )
        },
    ) { padding ->
        if (shown.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource(R.string.log_empty),
                    fontSize = 15.sp,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.log_empty_hint),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp,
                )
                // hook 进程（宿主 uid）写入的日志条目，本进程读取需要「所有文件访问权限」
                if (logUri == null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.log_need_permission),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { FileUtils.checkRWPermission(context) }) {
                        Text(text = stringResource(R.string.log_grant_permission))
                    }
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(shown) { line ->
                    Text(
                        text = line,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 1.dp),
                    )
                }
            }
        }
    }
}

/** 日志级别筛选（按行内的 `V/D/I/W/E` 标记过滤） */
private enum class LogLevelFilter {
    All, Error, Warn, Info, Debug;

    fun label(context: Context): String = context.getString(
        when (this) {
            All -> R.string.log_filter_all
            Error -> R.string.log_filter_error
            Warn -> R.string.log_filter_warn
            Info -> R.string.log_filter_info
            Debug -> R.string.log_filter_debug
        }
    )

    fun apply(lines: List<String>): List<String> = when (this) {
        All -> lines
        Error -> lines.filter { it.contains(" E ") }
        Warn -> lines.filter { it.contains(" W ") || it.contains(" E ") }
        Info -> lines.filter { it.contains(" I ") || it.contains(" W ") || it.contains(" E ") }
        Debug -> lines.filterNot { it.contains(" V ") }
    }
}

/**
 * 读取 `Download/ColorOSTools/logs` 下最新一份日志（MediaStore 查询，_ID 最大 = 最新插入）；
 * 返回 (Uri, 行列表)。ColorOS 17 分区存储下 File API 读 /sdcard/Download 会失败，必须走 MediaStore。
 */
private fun readLatestLog(context: Context): Pair<Uri?, List<String>> {
    val proj = arrayOf(MediaStore.MediaColumns._ID)
    val sel = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? AND " +
        "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
    val uri = runCatching {
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, proj, sel,
            arrayOf("ColorOSTools_%", "%ColorOSTools/logs%"), "${MediaStore.MediaColumns._ID} DESC",
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
    }.getOrNull()?.let {
        MediaStore.Downloads.EXTERNAL_CONTENT_URI.buildUpon().appendPath(it.toString()).build()
    } ?: return null to emptyList()
    val text = runCatching {
        context.contentResolver.openInputStream(uri)?.bufferedReader().use { it?.readText() }
    }.getOrNull() ?: return uri to listOf("read failed")
    val all = text.split('\n')
    val tail = if (all.size > MAX_LOG_LINES) all.subList(all.size - MAX_LOG_LINES, all.size) else all
    return uri to tail
}

/** 分享当前日志文件（直接分享 MediaStore Uri，临时授权读权限） */
private fun shareLogFile(context: Context, uri: Uri?) {
    if (uri == null) {
        context.showToast(context.getString(R.string.log_empty))
        return
    }
    runCatching {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure {
        context.showToast(context.getString(R.string.log_share_failed))
    }
}
