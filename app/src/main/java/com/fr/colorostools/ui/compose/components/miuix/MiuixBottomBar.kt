/** Miuix 底栏，支持普通布局及两种悬浮样式。 */
package com.fr.colorostools.ui.compose.components.miuix

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.fr.colorostools.ui.compose.components.miuix.liquid.IosLiquidGlassNavigationBar
import com.fr.colorostools.ui.shell.FloatingBottomBarStyle
import com.fr.colorostools.ui.theme.isInDarkTheme
import top.yukonga.miuix.kmp.basic.BadgedBox
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 标签保留作无障碍名称；两种悬浮样式都只显示图标。 */
data class MiuixBarItem(
    val label: String,
    val icon: ImageVector,
    val badge: (@Composable () -> Unit)? = null,
)

@Composable
fun MiuixBottomBar(
    items: List<MiuixBarItem>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    blurBackdrop: LayerBackdrop?,
    floating: Boolean,
    floatingStyle: FloatingBottomBarStyle = FloatingBottomBarStyle.Miuix,
    modifier: Modifier = Modifier,
) {
    // 外层测量包含系统导航栏和悬浮间距，供页面内容预留正确的底部空间。
    Box(modifier) {
        if (!floating) {
            MiuixBlurredBar(blurBackdrop) {
                NavigationBar(
                    color = if (blurBackdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
                ) {
                    items.forEachIndexed { index, item ->
                        NavigationBarItem(
                            modifier = Modifier.weight(1f),
                            icon = item.icon,
                            label = item.label,
                            selected = selectedIndex == index,
                            onClick = { onSelected(index) },
                            badge = item.badge,
                        )
                    }
                }
            }
        } else if (floatingStyle == FloatingBottomBarStyle.IosLike) {
            IosLiquidGlassNavigationBar(
                items = items.map { NavigationItem(label = it.label, icon = it.icon) },
                selectedIndex = selectedIndex,
                onItemClick = onSelected,
                backdrop = blurBackdrop,
                isBlurActive = blurBackdrop != null,
                isDark = isInDarkTheme,
                showLabels = false,
                badge = { items[it].badge },
            )
        } else {
            val radius = 50.dp
            val isDark = isInDarkTheme
            val highlight = remember(isDark) {
                if (isDark) Highlight.GlassStrokeMiddleDark else Highlight.GlassStrokeMiddleLight
            }
            FloatingNavigationBar(
                modifier = if (blurBackdrop != null) {
                    Modifier.textureBlur(
                        backdrop = blurBackdrop,
                        shape = RoundedCornerShape(radius),
                        blurRadius = 25f,
                        colors = BlurDefaults.blurColors(
                            blendColors = listOf(
                                BlendColorEntry(MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f)),
                            ),
                        ),
                        highlight = highlight,
                    )
                } else Modifier,
                color = if (blurBackdrop != null) Color.Transparent else MiuixTheme.colorScheme.surfaceContainer,
                cornerRadius = radius,
            ) {
                items.forEachIndexed { index, item ->
                    MiuixFloatingNavigationBarItem(
                        item = item,
                        selected = selectedIndex == index,
                        onClick = { onSelected(index) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MiuixFloatingNavigationBarItem(
    item: MiuixBarItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val onSurfaceContainerColor = MiuixTheme.colorScheme.onSurfaceContainer
    val tint = when {
        isPressed -> onSurfaceContainerColor.copy(alpha = if (selected) 0.7f else 0.5f)
        selected -> onSurfaceContainerColor
        else -> onSurfaceContainerColor.copy(alpha = 0.6f)
    }
    Column(
        modifier = Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
                interactionSource = interactionSource,
                indication = null,
            )
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BadgedBox(badge = { item.badge?.invoke() }) {
            Icon(
                modifier = Modifier.size(22.dp),
                imageVector = item.icon,
                contentDescription = item.label,
                tint = tint,
            )
        }
    }
}
