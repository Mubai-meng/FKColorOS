package com.fr.colorostools.ui.compose.components.miuix

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 文本输入对话框（对应 material 线 `EditTextPreference` 的 M3 对话框形态）。
 *
 * 使用 `overlay.OverlayDialog`：弹层由根部 Miuix Scaffold 的 popup host 承载
 * （`renderInRootScaffold` 保持默认 true），不使用平台窗口版 `WindowDialog`。
 *
 * 文本状态由对话框内局部持有（每次 [show] 变 true 时以 [value] 重置），确认时由调用方
 * 提交，与 material 基线 `PrefScope.editText`（`var text by remember { mutableStateOf(current) }`
 * + 确认落盘）行为一致；[onValueChange] 提供实时镜像回调。
 *
 * [summary] 不走 `OverlayDialog` 的 summary 槽：`DialogContentLayout` 的 Column 依次测量子项，
 * 长说明（如时钟格式速查表）会吃光剩余高度，把后面的输入框与按钮压成 0 高度（键盘弹出时更明显）。
 * 这里改为自持 Column 内的「可滚动 + 只吃剩余高度」区域，输入框/按钮先测量，永远可见。
 *
 * @param maxLines 输入框最大行数，大于 1 时允许输入换行符（时钟自定义格式用 `\n` 表示双行显示）。
 */
@Composable
fun MiuixPrefTextDialog(
    show: Boolean,
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    summary: String? = null,
    placeholder: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    maxLines: Int = 1,
) {
    OverlayDialog(
        show = show,
        title = title,
        onDismissRequest = onDismiss,
        content = {
            var text by remember(show) { mutableStateOf(value) }
            Column {
                summary?.takeIf { it.isNotEmpty() }?.let {
                    Box(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            text = it,
                            modifier = Modifier.fillMaxWidth(),
                            fontSize = MiuixTheme.textStyles.body1.fontSize,
                            textAlign = TextAlign.Center,
                            color = DialogDefaults.summaryColor(),
                        )
                    }
                }
                TextField(
                    value = text,
                    onValueChange = {
                        text = it
                        onValueChange(it)
                    },
                    modifier = Modifier.padding(bottom = 16.dp),
                    label = placeholder ?: "",
                    useLabelAsPlaceholder = placeholder != null,
                    keyboardOptions = keyboardOptions,
                    maxLines = maxLines,
                )
                Row(horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(
                        text = stringResource(android.R.string.cancel),
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(20.dp))
                    TextButton(
                        text = stringResource(android.R.string.ok),
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        },
    )
}
