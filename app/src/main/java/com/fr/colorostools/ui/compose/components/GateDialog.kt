package com.fr.colorostools.ui.compose.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.fr.colorostools.ui.theme.LocalUiMode
import com.fr.colorostools.ui.theme.UiMode
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog

/**
 * 启动阻塞门对话框的规格：无 root / 系统不支持
 *
 * @param title 标题；无 root 时不显示标题
 * @param message 正文
 * @param neutralLabel 中性按钮文案；非空时显示，点击只关闭对话框（系统不支持时提供的「忽略」）
 */
data class GateDialogSpec(
    val title: String? = null,
    val message: String,
    val neutralLabel: String? = null,
)

/**
 * 启动阻塞门对话框（不可取消）：Material / Miuix 双线实现
 *
 * 迁移前这几处是 [com.fr.colorostools.ui.activity.MainActivity] 里唯一的 View 层
 * `androidx.appcompat.app.AlertDialog` —— P5「Compose 化 + 删 Material View 依赖」时
 * 它们是 Activity 生命周期里的阻塞门、没被搬进 Compose，才留在 View 层，
 * 用着 AppCompat 默认的 alertDialogTheme，与应用其余对话框完全脱节。
 *
 * 现在改为与应用其余对话框一致的 `UiMode` 双线：
 * Miuix 线走 `overlay.OverlayDialog`，它由根部 Miuix Scaffold 的 popup host 承载，
 * 因此必须组合在 [com.fr.colorostools.ui.compose.MainShell] 内；
 * Material 线走 material3 `AlertDialog`。
 *
 * 关闭语义对齐迁移前的 `setCancelable(false)`：返回键与点击外部都不关闭
 * （Material 线靠 [DialogProperties]，Miuix 线的 `onDismissRequest` 传空实现）。
 * 迁移前的 `setOnDismissListener { exitModule() }` 只可能在「由确定按钮触发 dismiss」时生效，
 * 而确定按钮已经 `exitProcess(0)`，因此这里不再保留。
 *
 * 历史：这里还曾承载「检测到模块未激活」阻塞门，2026-10-15 按用户要求移除
 * （未激活也应能正常打开模块，主页状态卡置灰提示即可）。
 *
 * @param onConfirm 确定按钮：由调用方决定是否退出进程
 * @param onNeutral 中性按钮：只关闭对话框
 */
@Composable
fun GateDialog(
    spec: GateDialogSpec,
    onConfirm: () -> Unit,
    onNeutral: () -> Unit,
) {
    val title = spec.title
    val neutralLabel = spec.neutralLabel
    when (LocalUiMode.current) {
        UiMode.Miuix -> OverlayDialog(
            show = true,
            title = title,
            summary = spec.message,
            onDismissRequest = {},
            content = {
                Row {
                    neutralLabel?.let { label ->
                        MiuixTextButton(
                            text = label,
                            onClick = onNeutral,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(20.dp))
                    }
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
            onDismissRequest = {},
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
            ),
            title = title?.let { { Text(it) } },
            text = { Text(spec.message) },
            confirmButton = {
                TextButton(onClick = onConfirm) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = neutralLabel?.let { label ->
                {
                    TextButton(onClick = onNeutral) {
                        Text(label)
                    }
                }
            },
        )
    }
}
