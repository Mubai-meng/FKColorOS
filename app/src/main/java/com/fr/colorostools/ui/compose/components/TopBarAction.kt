package com.fr.colorostools.ui.compose.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import com.fr.colorostools.ui.theme.LocalUiMode
import com.fr.colorostools.ui.theme.UiMode
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.menu.OverlayIconDropdownMenu
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 顶栏动作键与「更多」溢出菜单的唯一下发点。
 *
 * 调用点不再自己写 material3 的 IconButton/DropdownMenu，分派统一读 [LocalUiMode]
 * （与 PrefCards.kt / PrefScope.kt 同一套惯例），两条线各自成对：
 *  - Material 线：material3 [IconButton]（48dp 触摸区）+ [DropdownMenu]；
 *  - Miuix 线：库内 [MiuixIconButton]（40dp 触摸区、带 squircle 按压态）+
 *    [OverlayIconDropdownMenu]（库内弹层，展开/收起带 ContextClick 触感、选中带 Confirm 触感）。
 *
 * Miuix 线的溢出弹层必须走库内弹层：material3 的 DropdownMenu 是独立 Popup 窗口，
 * 既不进根部 Miuix Scaffold 的 popup host，也没有 miuix 的 squircle 背板与触感。
 */

/**
 * 溢出菜单的一项。
 *
 * @param label 已解析好的条目文案（调用点用 stringResource 解析后传入）。
 * @param onClick 条目点击；Miuix 线由库内弹层在触发 Confirm 触感后回调。
 */
@Immutable
class TopBarOverflowItem(
    val label: String,
    val onClick: () -> Unit,
)

/**
 * 顶栏动作键。
 *
 * @param onClick 点击回调。
 * @param contentDescription 无障碍描述。
 * @param painter 图标：资源图用 painterResource，矢量图用 rememberVectorPainter。
 */
@Composable
fun TopBarActionButton(
    onClick: () -> Unit,
    contentDescription: String?,
    painter: Painter,
    modifier: Modifier = Modifier,
) {
    when (LocalUiMode.current) {
        UiMode.Miuix -> MiuixIconButton(onClick = onClick, modifier = modifier) {
            MiuixIcon(
                painter = painter,
                contentDescription = contentDescription,
                tint = MiuixTheme.colorScheme.onBackground,
            )
        }

        UiMode.Material -> IconButton(onClick = onClick, modifier = modifier) {
            Icon(painter, contentDescription)
        }
    }
}

/**
 * 顶栏「更多」溢出菜单。
 *
 * @param items 条目列表，顺序即展示顺序；为空时调用点不应渲染本组件。
 * @param contentDescription 动作键的无障碍描述（通常是「更多」）。
 */
@Composable
fun TopBarOverflowMenu(
    items: List<TopBarOverflowItem>,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    when (LocalUiMode.current) {
        UiMode.Miuix -> OverlayIconDropdownMenu(
            entries = listOf(
                DropdownEntry(
                    items = items.map { DropdownItem(text = it.label, onClick = it.onClick) },
                ),
            ),
            modifier = modifier,
            content = {
                MiuixIcon(
                    imageVector = MiuixIcons.More,
                    contentDescription = contentDescription,
                    tint = MiuixTheme.colorScheme.onBackground,
                )
            },
        )

        UiMode.Material -> {
            var expanded by remember { mutableStateOf(false) }
            Box(modifier = modifier) {
                IconButton(onClick = { expanded = true }) {
                    Icon(Icons.Filled.MoreHoriz, contentDescription)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    items.forEach { item ->
                        DropdownMenuItem(
                            text = { Text(item.label) },
                            onClick = {
                                expanded = false
                                item.onClick()
                            },
                        )
                    }
                }
            }
        }
    }
}
