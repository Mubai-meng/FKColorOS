package com.fr.colorostools.ui.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fr.colorostools.ui.compose.components.material.LocalShellBottomInset
import com.fr.colorostools.ui.compose.components.material.MaterialItem
import com.fr.colorostools.ui.compose.components.material.MaterialListItem
import com.fr.colorostools.ui.compose.components.material.materialBottomInset
import com.fr.colorostools.ui.shell.LocalCategoryBadgeColors
import com.fr.colorostools.ui.theme.LocalUiMode
import com.fr.colorostools.ui.theme.UiMode
import com.fr.colorostools.utils.AppIconCache
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Text as MiuixText

/**
 * 功能行摘要最多两行：摘要是「该页前三个功能标题」，一行装不下三个就已截断成省略号，
 * 两行能完整露出前三个标题；再长也没有信息增量，仍按省略号收尾。
 */
private const val FUNCTION_SUMMARY_MAX_LINES = 2

@Immutable
internal data class FunctionRow(
    val pageKey: String,
    val title: String,
    val summary: String?,
    /** null = 分类页（应用相关/状态栏相关/…），渲染 [categoryBadges] 徽标而不是 App 图标 */
    val packName: String?,
)

/** 应用式功能列表：稳定行标识、48dp 图标，单行标题与最多两行摘要。 */
@Composable
internal fun FunctionList(
    rows: List<FunctionRow>,
    padding: PaddingValues,
    onOpenPage: (pageKey: String, title: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isMiuix = LocalUiMode.current == UiMode.Miuix
    val direction = LocalLayoutDirection.current
    val listState = rememberLazyListState()
    // Miuix 的内容滚到顶栏下方；Material 的列表视口位于顶栏下方。
    val listModifier = if (isMiuix) {
        modifier.fillMaxSize().scrollEndHaptic().overScrollVertical()
    } else {
        modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
    }
    val contentPadding = if (isMiuix) {
        PaddingValues(
            start = padding.calculateStartPadding(direction) + 12.dp,
            end = padding.calculateEndPadding(direction) + 12.dp,
            top = padding.calculateTopPadding() + 6.dp,
            bottom = padding.calculateBottomPadding() + materialBottomInset(),
        )
    } else {
        PaddingValues(
            start = 16.dp,
            end = 16.dp,
            bottom = 16.dp + LocalShellBottomInset.current,
        )
    }
    LazyColumn(
        state = listState,
        modifier = listModifier,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(if (isMiuix) 12.dp else 2.dp),
        overscrollEffect = if (isMiuix) null else rememberOverscrollEffect(),
    ) {
        itemsIndexed(
            items = rows,
            key = { _, row -> row.pageKey },
            contentType = { _, _ -> "function" },
        ) { index, row ->
            val onClick = { onOpenPage(row.pageKey, row.title) }
            if (isMiuix) {
                MiuixFunctionRow(row, onClick)
            } else {
                MaterialItem(index = index, count = rows.size) {
                    MaterialListItem(
                        onClick = onClick,
                        headlineContent = {
                            Text(row.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = row.summary?.let { summary ->
                            {
                                Text(
                                    text = summary,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = FUNCTION_SUMMARY_MAX_LINES,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                        leadingContent = { FunctionRowIcon(row) },
                        trailingContent = {
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MiuixFunctionRow(row: FunctionRow, onClick: () -> Unit) {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    MiuixCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        showIndication = true,
        insideMargin = PaddingValues(start = 10.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FunctionRowIcon(row)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                MiuixText(
                    text = row.title,
                    style = MiuixTheme.textStyles.main,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                row.summary?.let { summary ->
                    MiuixText(
                        text = summary,
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = FUNCTION_SUMMARY_MAX_LINES,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Image(
                imageVector = MiuixIcons.Basic.ArrowRight,
                contentDescription = null,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(width = 10.dp, height = 16.dp)
                    .graphicsLayer { if (isRtl) scaleX = -1f },
                colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceVariantActions),
            )
        }
    }
}

@Composable
private fun FunctionRowIcon(row: FunctionRow) {
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        val packName = row.packName
        if (packName == null) {
            CategoryBadgeIcon(categoryBadges[row.pageKey])
        } else {
            AppIcon(packName)
        }
    }
}

@Composable
private fun AppIcon(packName: String) {
    val context = LocalContext.current
    val size = with(LocalDensity.current) { 48.dp.roundToPx() }
    val iconState = remember(context, packName, size) {
        AppIconCache.state(context, packName, size)
    }
    val icon = iconState.value
    val alpha = remember(iconState) { Animatable(if (icon != null) 1f else 0f) }
    LaunchedEffect(icon) {
        if (icon != null && alpha.value < 1f) alpha.animateTo(1f, tween(150))
    }
    if (icon != null) {
        Image(
            bitmap = icon,
            contentDescription = null,
            modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value },
        )
    }
}

/**
 * 分类徽标外框：槽位是 48dp，但应用图标的位图自带透明留白（自适应图标可见内容约 72/108），
 * 实心方块铺满 48dp 会显得比 App 图标大一圈，故缩到 42dp。
 */
private val CategoryBadgeSize = 42.dp

/** 徽标内部图形：沿用 42dp × 2/3 的比例（与应用图标可见内容的占比一致） */
private val CategoryBadgeGlyphSize = 28.dp

/** 徽标圆角：随外框等比缩放 */
private val CategoryBadgeCornerSize = 10.dp

/**
 * 分类徽标：圆角对齐应用图标的可见裁切，整体尺寸与应用图标一致。
 *
 * 默认用 [CategoryBadge] 的固定色（多彩）；关闭「多彩分类图标」后退化成主题色单色徽标。
 */
@Composable
private fun CategoryBadgeIcon(badge: CategoryBadge?) {
    val colorful = LocalCategoryBadgeColors.current
    val colorScheme = MaterialTheme.colorScheme
    val background = if (colorful) badge?.color ?: Color(0xFF607D8B) else colorScheme.secondaryContainer
    val foreground = if (colorful) Color.White else colorScheme.onSecondaryContainer
    Box(
        modifier = Modifier
            .size(CategoryBadgeSize)
            .clip(RoundedCornerShape(CategoryBadgeCornerSize))
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = badge?.icon ?: Icons.Filled.Apps,
            contentDescription = null,
            tint = foreground,
            modifier = Modifier.size(CategoryBadgeGlyphSize),
        )
    }
}
