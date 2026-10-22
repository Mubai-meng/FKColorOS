package com.fr.colorostools.ui.compose.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fr.colorostools.R
import com.fr.colorostools.ui.theme.LocalUiMode
import com.fr.colorostools.ui.theme.UiMode
import com.fr.colorostools.utils.ScopeGuard
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog

/**
 * 作用域门禁对话框：功能开关打开前检测到缺失作用域时弹出（双线实现，写法对齐 [GateDialog]）。
 *
 * 两个按钮：
 *  - 取消：关闭对话框，不执行任何动作（调用方的开关保持原状）
 *  - 确定：向框架提交勾选请求（框架会再弹一次它自己的授权确认），成功后调用方继续开启功能
 *
 * @param missing 缺失的作用域包名（按功能声明顺序）
 * @param onConfirm 确定：提交作用域请求
 * @param onDismiss 取消 / 返回键 / 点击外部
 */
@Composable
fun ScopeGateDialog(
    missing: List<String>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // 包名 → 应用标签（副标题显示包名，便于用户对照 LSPosed 里的条目）
    val body = remember(missing) {
        val lines = missing.joinToString("\n") { pkg ->
            val label = ScopeGuard.label(context, pkg)
            if (label == pkg) "• $pkg" else "• $label\n    $pkg"
        }
        context.getString(R.string.scope_gate_message) + "\n\n" + lines
    }

    when (LocalUiMode.current) {
        UiMode.Miuix -> OverlayDialog(
            show = true,
            title = stringResource(R.string.scope_gate_title),
            summary = body,
            onDismissRequest = onDismiss,
            content = {
                Row {
                    MiuixTextButton(
                        text = stringResource(android.R.string.cancel),
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(20.dp))
                    MiuixTextButton(
                        text = stringResource(android.R.string.ok),
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            },
        )

        UiMode.Material -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.scope_gate_title)) },
            text = { Text(body) },
            confirmButton = {
                TextButton(onClick = onConfirm) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}
