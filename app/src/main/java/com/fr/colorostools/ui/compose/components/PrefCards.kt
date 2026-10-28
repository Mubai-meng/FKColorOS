package com.fr.colorostools.ui.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.fr.colorostools.ui.compose.components.material.MaterialItem
import com.fr.colorostools.ui.compose.components.material.MaterialListItem
import com.fr.colorostools.ui.compose.components.material.MaterialPrefCardScope
import com.fr.colorostools.ui.compose.components.material.MaterialSwitchItem
import com.fr.colorostools.ui.compose.components.miuix.MiuixListItem
import com.fr.colorostools.ui.compose.components.miuix.MiuixPrefCardScope
import com.fr.colorostools.ui.compose.components.miuix.MiuixPrefItem
import com.fr.colorostools.ui.compose.components.miuix.MiuixSwitchItem
import com.fr.colorostools.ui.theme.LocalUiMode
import com.fr.colorostools.ui.theme.UiMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon

/**
 * 卡片行 / 卡原语，支持分段卡片与独立卡片。
 *
 * **「设置区块」的唯一 API 是 `PrefScope` DSL**（`group()` / `category()` / `switch()` / `click()` / `list()` /
 * `custom()`，见 `ui/compose/components/PrefScope.kt`）—— 所有标准设置页与作用域页都走它。
 * 本文件只保留 `custom()` 逃生舱（无法走 DSL 的自定义控件里的单行 / 单卡）所需的原始行 / 卡原语：
 * [PrefGroup] + [PrefRow] + [PrefSwitchRow]（组内行，形状由所属 [PrefGroup] 决定）、
 * [PrefCard] + [PrefSwitchCard]（不属于任何分段组的独立卡片）、[PrefIcon]（行首图标）、
 * [PrefColorPreview]（行尾颜色预览色块）。
 *
 * UI 线分派：material 线用 `Segmented*` 原语 + `MaterialTheme`；Miuix 线用 `miuix/MiuixPref*`
 * 原语 + `MiuixTheme`；分派开关统一读 [LocalUiMode]。
 */

/**
 * 分段卡片组：组内连续条目合并成一张连续卡片
 * （首条上端、尾条下端 16dp 外圆角，组内条目不设圆角与间距，显隐带弹性过渡）。
 *
 * 组容器由 [PrefCardScope] 的两个实现分派：material 线走 `MaterialGroup` 动态重放
 * （保留弹性显隐过渡），Miuix 线走 `MiuixPrefGroup`（首条 12dp 组间距 + 卡片圆角）。
 *
 * 用法：
 * ```
 * PrefGroup {
 *     item { PrefSwitchRow("标题", checked = a, onCheckedChange = { ... }) }
 *     item { PrefSwitchRow("标题2", checked = b, onCheckedChange = { ... }) }
 * }
 * ```
 */
@Composable
fun PrefGroup(
    modifier: Modifier = Modifier,
    content: PrefCardScope.() -> Unit,
) {
    val miuix = LocalUiMode.current == UiMode.Miuix
    val scope: PrefCardScope = if (miuix) MiuixPrefCardScope() else MaterialPrefCardScope()
    content(scope)
    scope.render(preferMiuix = miuix, modifier = modifier)
}

/** 分段卡片组内的普通条目（形状由所属 [PrefGroup] 决定） */
@Composable
fun PrefRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    if (LocalUiMode.current == UiMode.Miuix) {
        // Miuix 行自带 insideMargin(16dp)：不再套 material 线的外层 padding
        val endActions: (@Composable RowScope.() -> Unit)? = trailing?.let { content ->
            { content() }
        }
        MiuixListItem(
            title = title,
            modifier = modifier,
            summary = summary,
            onClick = onClick,
            onLongClick = onLongClick,
            enabled = enabled,
            startAction = leading,
            endActions = endActions,
        )
    } else {
        MaterialListItem(
            modifier = modifier,
            onClick = onClick,
            onLongClick = onLongClick,
            enabled = enabled,
            headlineContent = { Text(title) },
            supportingContent = summary?.let { { Text(it) } },
            leadingContent = leading,
            trailingContent = trailing,
        )
    }
}

/** 设置入口的单色图标，使用当前外观的中性前景色和默认图标尺寸。 */
@Composable
fun PrefIcon(icon: ImageVector) {
    if (LocalUiMode.current == UiMode.Miuix) {
        MiuixIcon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.padding(end = 6.dp),
            tint = MiuixTheme.colorScheme.onBackground,
        )
    } else {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 行尾颜色预览的边长：与行尾图片缩略图 [PrefImagePreview] 的行高同取 40dp */
private val ColorPreviewSize = 40.dp

/** 行尾颜色预览的圆角：正方形 + 6dp 圆角（与 [PrefImagePreview] 的圆角一致） */
private val ColorPreviewCornerRadius = 6.dp

/**
 * 行尾颜色预览色块：正方形 + 圆角，用于颜色偏好条目右侧。
 *
 * 形态对齐 [PrefImagePreview]：同一 40dp 边长、同一 6dp 圆角，只是没有按图片比例拉宽，
 * 因此是正方形 + 圆角而不是圆点、胶囊或宽扁的矩形。
 *
 * 描边不能取主题色的 `outline`：主题色本身可以选到白色，此时列表行与弹层都是浅色，
 * 白色描边等于没有描边，而这两个颜色偏好的默认值正是 `#FFFFFFFF`，色块会整块看不见。
 */
@Composable
fun PrefColorPreview(
    color: Color,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(ColorPreviewCornerRadius)

    Box(
        modifier
            .size(ColorPreviewSize)
            .clip(shape)
            .background(color)
            .border(1.dp, contrastBorder(color), shape)
    )
}

/**
 * 与 [color] 形成对比的描边色（[PrefColorPreview] 与颜色选择器的预览色块共用）。
 *
 * 只按颜色自身取反、不跟主题色走：浅色用深描边，深色用浅描边；半透明色一律用深描边，
 * 因为它底下透出的是浅色内容（选择器里是浅灰棋盘格）。这样无论主题色与所选颜色怎么组合，
 * 色块本体与轮廓至少有一个看得清 —— 「白色主题色 + 白色颜色」原先是两者都看不见的组合。
 */
internal fun contrastBorder(color: Color): Color =
    if (color.alpha < 0.5f || color.luminance() > 0.5f) {
        Color.Black.copy(alpha = 0.28f)
    } else {
        Color.White.copy(alpha = 0.38f)
    }

/** 分段卡片组内的开关条目：整行点击切换，尾部为 Expressive 开关 */
@Composable
fun PrefSwitchRow(
    title: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    if (LocalUiMode.current == UiMode.Miuix) {
        // 触感由 MiuixSwitchItem 内部补 VirtualKey（SwitchPreference 本身无触感）
        MiuixSwitchItem(
            title = title,
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            summary = summary,
            startAction = leading,
            enabled = enabled,
        )
    } else {
        Box(modifier = modifier) {
            MaterialSwitchItem(
                title = title,
                summary = summary,
                checked = checked,
                enabled = enabled,
                leading = leading,
                onCheckedChange = onCheckedChange,
            )
        }
    }
}

/**
 * `custom()` 逃生舱里行内容的标准水平内缩包装，与 DSL 的 switch()/list()/click() 等分派一致：
 * material 线套 16dp 内缩（material 列表无水平 contentPadding，行需自带）；
 * Miuix 线不套（MiuixListItem/MiuixSwitchItem 自带 insideMargin(16dp) + 列表级 12dp 已对齐，
 * 再叠一层会右移 16dp，与 switch/list/slider 形成割裂感）。
 */
@Composable
fun PrefRowInset(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(if (LocalUiMode.current == UiMode.Miuix) Modifier else Modifier.padding(horizontal = 16.dp)),
        content = content,
    )
}

/**
 * 独立卡片（不属于任何分段组时使用）。
 * 外观与主题页一致：Material（surfaceBright 容器 + 16dp 外圆角）/
 * Miuix `MiuixPrefItem`（16dp 外圆角 + 12dp 水平内缩）。
 */
@Composable
fun PrefCard(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    if (LocalUiMode.current == UiMode.Miuix) {
        MiuixPrefItem(index = 0, count = 1) {
            PrefRow(
                title = title,
                modifier = modifier,
                summary = summary,
                onClick = onClick,
                onLongClick = onLongClick,
                enabled = enabled,
                trailing = trailing,
            )
        }
    } else {
        MaterialItem(index = 0, count = 1) {
            PrefRow(
                title = title,
                modifier = modifier,
                summary = summary,
                onClick = onClick,
                onLongClick = onLongClick,
                enabled = enabled,
                trailing = trailing,
            )
        }
    }
}

/** 独立开关卡片（不属于任何分段组时使用） */
@Composable
fun PrefSwitchCard(
    title: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    if (LocalUiMode.current == UiMode.Miuix) {
        MiuixPrefItem(index = 0, count = 1) {
            PrefSwitchRow(
                title = title,
                checked = checked,
                modifier = modifier,
                summary = summary,
                enabled = enabled,
                onCheckedChange = onCheckedChange,
            )
        }
    } else {
        MaterialItem(index = 0, count = 1) {
            PrefSwitchRow(
                title = title,
                checked = checked,
                modifier = modifier,
                summary = summary,
                enabled = enabled,
                onCheckedChange = onCheckedChange,
            )
        }
    }
}
