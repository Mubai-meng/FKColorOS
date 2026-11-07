package com.fr.colorostools.ui.compose.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fr.colorostools.R
import com.fr.colorostools.ui.compose.components.material.MaterialPageScaffold

/**
 * 日志页 —— **占位**，页面规划中。
 *
 * 模块日志已全部改投 LSPosed 框架日志（`LSPosed → 日志` 页面），本页不再是日志阅读器：
 * 原先的 MediaStore 读取、级别筛选、复制、分享四个顶栏动作与文件通道一并下线
 * （见 [com.fr.colorostools.utils.LogUtils] 类文档）。
 *
 * 保留本页是因为底栏仍是五个标签，直接抽掉会改变导航结构；等日志页重新规划后再实现。
 */
@Composable
fun LogPage() {
    MaterialPageScaffold(
        title = stringResource(R.string.nav_log),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.log_placeholder),
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}