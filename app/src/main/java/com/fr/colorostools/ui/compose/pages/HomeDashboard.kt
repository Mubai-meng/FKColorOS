package com.fr.colorostools.ui.compose.pages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fr.colorostools.R
import com.fr.colorostools.ui.compose.components.HomeTopBarActions
import com.fr.colorostools.ui.compose.components.material.MaterialPageScaffold
import com.fr.colorostools.ui.compose.components.material.MaterialTonalCard
import com.fr.colorostools.ui.compose.components.material.materialBottomInset
import com.fr.colorostools.ui.theme.LocalUiMode
import com.fr.colorostools.ui.theme.UiMode
import com.fr.colorostools.utils.DeviceInfoEntry
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Report
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Update
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.CardDefaults as MiuixCardDefaults
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.Text as MiuixText

@Immutable
internal data class HomeDashboardState(
    val moduleActive: Boolean,
    val moduleVersion: String,
    val rootVersion: String? = null,
    val frameworkVersion: String? = null,
    val deviceInfo: List<DeviceInfoEntry>? = null,
    val updateVersion: String? = null,
    val showNotice: Boolean = false,
)

/** 主页只负责呈现快照与转发操作，读取服务和弹窗状态由页面持有。 */
@Composable
internal fun HomeDashboard(
    state: HomeDashboardState,
    onRestart: () -> Unit,
    onAbout: () -> Unit,
    onUpdate: () -> Unit,
    onStatusLongClick: (() -> Unit)?,
    onCopyDeviceInfo: () -> Unit,
    onOpenNotice: () -> Unit,
) {
    val miuix = LocalUiMode.current == UiMode.Miuix
    val listState = rememberLazyListState()
    var detailsExpanded by rememberSaveable { mutableStateOf(false) }
    val colors = homeColors()
    val bottomPadding = materialBottomInset(24.dp)
    MaterialPageScaffold(
        title = stringResource(R.string.app_name),
        actions = { HomeTopBarActions(onRestart = onRestart, onAbout = onAbout) },
    ) { padding ->
        val direction = LocalLayoutDirection.current
        // Miuix 内容在顶栏下滚动；Material 内容从实际顶栏下方开始。
        val outsidePadding = if (miuix) PaddingValues(
            start = padding.calculateStartPadding(direction),
            end = padding.calculateEndPadding(direction),
            bottom = padding.calculateBottomPadding(),
        ) else padding
        Box(
            Modifier.fillMaxSize().padding(outsidePadding).consumeWindowInsets(outsidePadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.widthIn(max = 720.dp).fillMaxSize().then(
                    if (miuix) Modifier.scrollEndHaptic().overScrollVertical() else Modifier,
                ),
                contentPadding = PaddingValues(
                    start = if (miuix) 12.dp else 16.dp,
                    end = if (miuix) 12.dp else 16.dp,
                    top = (if (miuix) padding.calculateTopPadding() else 0.dp) + 12.dp,
                    bottom = bottomPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                overscrollEffect = if (miuix) null else rememberOverscrollEffect(),
            ) {
                item(key = "status") {
                    HomeStatusCard(state, colors, onStatusLongClick)
                }
                if (state.updateVersion != null) {
                    item(key = "update") {
                        HomeActionCard(
                            title = stringResource(R.string.check_update_hint),
                            summary = state.updateVersion,
                            symbol = HomeSymbol.Update,
                            colors = colors,
                            onClick = onUpdate,
                            accent = true,
                        )
                    }
                }
                item(key = "device") {
                    HomeDeviceCard(
                        entries = state.deviceInfo,
                        expanded = detailsExpanded,
                        onExpandedChange = { detailsExpanded = !detailsExpanded },
                        colors = colors,
                        onCopy = onCopyDeviceInfo,
                    )
                }
                if (state.showNotice) {
                    item(key = "notice") {
                        HomeText(
                            text = stringResource(R.string.authorized),
                            role = HomeTextRole.Caption,
                            color = colors.secondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                                .clickable(role = Role.Button, onClick = onOpenNotice)
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeStatusCard(state: HomeDashboardState, colors: HomeColors, onLongClick: (() -> Unit)?) {
    val miuix = LocalUiMode.current == UiMode.Miuix
    if (miuix && state.moduleActive) {
        MiuixActiveStatusCard(state, onLongClick)
        return
    }
    val tonal = state.moduleActive
    val foreground = if (tonal) MaterialTheme.colorScheme.onPrimaryContainer else colors.foreground
    val secondary = if (tonal) foreground else colors.secondary
    val statusStyle = homeStatusTextStyle(headline = true)
    val lineStyle = homeStatusTextStyle()
    HomeCard(
        background = if (tonal) MaterialTheme.colorScheme.primaryContainer else colors.card,
        foreground = foreground,
        onLongClick = onLongClick,
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HomeIcon(
                    symbol = if (state.moduleActive) HomeSymbol.Active else HomeSymbol.Inactive,
                    tint = if (state.moduleActive) (if (tonal) foreground else colors.accent) else colors.error,
                    modifier = Modifier.size(28.dp),
                )
                HomeText(
                    text = stringResource(if (state.moduleActive) R.string.module_isactivated else R.string.module_is_disabled),
                    role = HomeTextRole.Status,
                    color = foreground,
                    modifier = Modifier.weight(1f).semantics { heading() },
                    style = statusStyle,
                )
            }
            Spacer(Modifier.height(2.dp))
            HomeText(state.moduleVersion, HomeTextRole.Body, secondary, style = lineStyle)
            if (!state.moduleActive) {
                Spacer(Modifier.height(2.dp))
                HomeText(
                    stringResource(R.string.home_module_inactive_summary),
                    HomeTextRole.Body,
                    secondary,
                    style = lineStyle,
                )
            }
            Spacer(Modifier.height(2.dp))
            HomeText(
                state.rootVersion ?: stringResource(R.string.loading),
                HomeTextRole.Body,
                secondary,
                style = lineStyle,
            )
            state.frameworkVersion?.let {
                Spacer(Modifier.height(2.dp))
                HomeText(it, HomeTextRole.Body, secondary, style = lineStyle)
            }
        }
    }
}

/** 已激活状态以独立背景和装饰图标呈现，Root 来源与框架版本在卡片内展示。 */
@Composable
private fun MiuixActiveStatusCard(state: HomeDashboardState, onLongClick: (() -> Unit)?) {
    val dynamicColor = MiuixTheme.isDynamicColor
    val dark = when (MiuixTheme.colorSchemeMode) {
        ColorSchemeMode.Dark, ColorSchemeMode.MonetDark -> true
        ColorSchemeMode.Light, ColorSchemeMode.MonetLight -> false
        else -> isSystemInDarkTheme()
    }
    val background = when {
        dynamicColor -> MiuixTheme.colorScheme.secondaryContainer
        dark -> Color(0xFF1A3825)
        else -> Color(0xFFDFFAE4)
    }
    val foreground = MiuixTheme.colorScheme.onSurfaceContainer
    val root = state.rootVersion?.let(::runtimeInfoValue)?.takeUnless { it.isBlank() || it == "—" } ?: "—"
    val framework = state.frameworkVersion?.let(::runtimeInfoValue)
        ?.takeUnless { it.isBlank() || it == "—" }
        ?: "Xposed"
    val textStyle = MiuixTheme.textStyles.main.copy(textDirection = TextDirection.ContentOrLtr)
    MiuixCard(
        modifier = Modifier.fillMaxWidth(),
        colors = MiuixCardDefaults.defaultColors(color = background, contentColor = foreground),
        onLongPress = onLongClick,
        showIndication = onLongClick != null,
    ) {
        Box(Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier.matchParentSize().offset(x = 27.dp, y = 31.dp),
                contentAlignment = Alignment.BottomEnd,
            ) {
                MiuixIcon(
                    imageVector = Icons.Rounded.CheckCircleOutline,
                    contentDescription = null,
                    modifier = Modifier.size(110.dp),
                    tint = if (dynamicColor) MiuixTheme.colorScheme.primary.copy(alpha = 0.8f)
                        else Color(0xFF36D167),
                )
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                MiuixText(
                    text = stringResource(R.string.module_isactivated),
                    modifier = Modifier.fillMaxWidth().padding(end = 80.dp).semantics { heading() },
                    style = textStyle,
                    fontSize = StatusHeadlineSize,
                    fontWeight = FontWeight.SemiBold,
                    color = foreground,
                )
                Spacer(Modifier.height(2.dp))
                MiuixText(
                    text = "${stringResource(R.string.version)}: ${state.moduleVersion}",
                    modifier = Modifier.fillMaxWidth().padding(end = 80.dp),
                    style = textStyle,
                    fontSize = StatusLineSize,
                    color = foreground,
                )
                Spacer(Modifier.height(2.dp))
                MiuixText(
                    text = "${stringResource(R.string.root_source)} $root",
                    modifier = Modifier.fillMaxWidth().padding(end = 80.dp),
                    style = textStyle,
                    fontSize = StatusLineSize,
                    fontWeight = FontWeight.Medium,
                    color = foreground,
                )
                Spacer(Modifier.height(2.dp))
                MiuixText(
                    text = "${stringResource(R.string.framework_version)} $framework",
                    modifier = Modifier.fillMaxWidth().padding(end = 80.dp),
                    style = textStyle,
                    fontSize = StatusLineSize,
                    fontWeight = FontWeight.Medium,
                    color = foreground,
                )
            }
        }
    }
}

/** 版本查询返回带标题的说明，只移除首个字段分隔符之前的标题。 */
private fun runtimeInfoValue(text: String): String {
    val separator = text.indexOfFirst { it == ':' || it == '：' }
    return if (separator < 0) text else text.substring(separator + 1).trim()
}

@Composable
private fun HomeDeviceCard(
    entries: List<DeviceInfoEntry>?,
    expanded: Boolean,
    onExpandedChange: () -> Unit,
    colors: HomeColors,
    onCopy: () -> Unit,
) {
    val primary = entries?.filter { it.labelRes in listOf(R.string.model, R.string.system, R.string.version) }
    val details = entries?.filterNot { it.labelRes in listOf(R.string.model, R.string.system, R.string.version) }
    HomeCard(colors.card, colors.foreground, onLongClick = onCopy) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            if (primary == null) {
                HomeText(stringResource(R.string.home_info_loading), HomeTextRole.Body, colors.secondary)
            } else {
                primary.forEachIndexed { index, entry ->
                    if (index > 0) Spacer(Modifier.height(20.dp))
                    HomeDeviceRow(entry, colors)
                }
            }
            AnimatedVisibility(visible = expanded && !details.isNullOrEmpty()) {
                Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    details.orEmpty().forEach { HomeDeviceRow(it, colors) }
                }
            }
        }
        if (!details.isNullOrEmpty()) {
            val label = stringResource(if (expanded) R.string.home_device_details_hide else R.string.home_device_details_show)
            Row(
                modifier = Modifier.fillMaxWidth()
                    .semantics { stateDescription = label }
                    .clickable(role = Role.Button, onClick = onExpandedChange)
                    .defaultMinSize(minHeight = 48.dp)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                HomeText(label, HomeTextRole.Body, colors.secondary, Modifier.weight(1f))
                HomeIcon(if (expanded) HomeSymbol.Collapse else HomeSymbol.Expand, colors.secondary, Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun HomeDeviceRow(entry: DeviceInfoEntry, colors: HomeColors) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        HomeIcon(
            symbol = when (entry.labelRes) {
                R.string.model -> HomeSymbol.Phone
                R.string.system -> HomeSymbol.System
                R.string.version -> HomeSymbol.Version
                R.string.product -> HomeSymbol.Hardware
                else -> HomeSymbol.Info
            },
            tint = colors.secondary,
            modifier = Modifier.padding(top = 2.dp).size(22.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val label = stringResource(entry.labelRes)
            HomeText(
                if (LocalUiMode.current == UiMode.Miuix) label.trimEnd(':', '：') else label,
                HomeTextRole.Title,
                colors.foreground,
            )
            HomeText(entry.value, HomeTextRole.Body, colors.secondary)
        }
    }
}

@Composable
private fun HomeActionCard(
    title: String,
    summary: String,
    symbol: HomeSymbol,
    colors: HomeColors,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    accent: Boolean = false,
) {
    HomeCard(colors.card, colors.foreground, onClick, onLongClick) {
        Row(
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HomeIcon(symbol, if (accent) colors.accent else colors.foreground, Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                HomeText(title, HomeTextRole.Title, if (accent) colors.accent else colors.foreground)
                HomeText(summary, HomeTextRole.Body, colors.secondary)
            }
            HomeIcon(HomeSymbol.Forward, colors.secondary, Modifier.size(20.dp))
        }
    }
}

@Composable
private fun HomeCard(
    background: Color,
    foreground: Color,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    if (LocalUiMode.current == UiMode.Miuix) {
        MiuixCard(
            modifier = Modifier.fillMaxWidth(),
            colors = MiuixCardDefaults.defaultColors(color = background, contentColor = foreground),
            showIndication = onClick != null || onLongClick != null,
            onClick = onClick,
            onLongPress = onLongClick,
        ) { content() }
    } else {
        MaterialTonalCard(
            modifier = Modifier.fillMaxWidth(),
            containerColor = background,
            contentColor = foreground,
            onClick = onClick,
            onLongClick = onLongClick,
            content = content,
        )
    }
}

private data class HomeColors(val card: Color, val foreground: Color, val secondary: Color, val accent: Color, val error: Color)

@Composable
private fun homeColors(): HomeColors = if (LocalUiMode.current == UiMode.Miuix) {
    with(MiuixTheme.colorScheme) { HomeColors(surfaceContainer, onSurfaceContainer, onSurfaceVariantSummary, primary, error) }
} else {
    with(MaterialTheme.colorScheme) { HomeColors(surfaceBright, onSurface, onSurfaceVariant, primary, error) }
}

private enum class HomeTextRole { Status, Title, Body, Caption }

private val StatusHeadlineSize = 22.sp
private val StatusLineSize = 15.sp

@Composable
private fun homeTextStyle(role: HomeTextRole): TextStyle = if (LocalUiMode.current == UiMode.Miuix) {
    with(MiuixTheme.textStyles) {
        when (role) {
            HomeTextRole.Status -> title3.copy(fontWeight = FontWeight.Medium)
            HomeTextRole.Title -> headline1.copy(fontWeight = FontWeight.Medium)
            HomeTextRole.Body -> body2.copy(fontWeight = FontWeight.Normal)
            HomeTextRole.Caption -> footnote1.copy(fontWeight = FontWeight.Normal)
        }
    }
} else {
    with(MaterialTheme.typography) {
        when (role) {
            HomeTextRole.Status -> titleLarge
            HomeTextRole.Title -> titleMedium
            HomeTextRole.Body -> bodyMedium.copy(fontWeight = FontWeight.Normal)
            HomeTextRole.Caption -> bodySmall
        }
    }
}

@Composable
private fun HomeText(
    text: String,
    role: HomeTextRole,
    color: Color,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
    style: TextStyle? = null,
) {
    val resolved = (style ?: homeTextStyle(role)).copy(textDirection = TextDirection.ContentOrLtr)
    if (LocalUiMode.current == UiMode.Miuix) {
        MiuixText(text = text, style = resolved, color = color, modifier = modifier.fillMaxWidth(), textAlign = textAlign)
    } else {
        Text(text = text, style = resolved, color = color, modifier = modifier.fillMaxWidth(), textAlign = textAlign)
    }
}

/** 状态卡片的文字与 Miuix 已激活卡片共用同一排版节奏：无额外字距、自然行高。 */
@Composable
private fun homeStatusTextStyle(headline: Boolean = false): TextStyle =
    homeTextStyle(if (headline) HomeTextRole.Status else HomeTextRole.Body).copy(
        fontSize = if (headline) StatusHeadlineSize else StatusLineSize,
        letterSpacing = TextUnit.Unspecified,
        lineHeight = TextUnit.Unspecified,
    )

private enum class HomeSymbol { Active, Inactive, Phone, System, Version, Hardware, Info, Update, Forward, Expand, Collapse }

@Composable
private fun HomeIcon(symbol: HomeSymbol, tint: Color, modifier: Modifier) {
    val miuix = LocalUiMode.current == UiMode.Miuix
    val vector: ImageVector = if (miuix) {
        when (symbol) {
            HomeSymbol.Active -> MiuixIcons.Ok
            HomeSymbol.Inactive -> MiuixIcons.Report
            HomeSymbol.Phone -> Icons.Rounded.PhoneAndroid
            HomeSymbol.System -> MiuixIcons.Settings
            HomeSymbol.Version, HomeSymbol.Update -> MiuixIcons.Update
            HomeSymbol.Hardware -> MiuixIcons.Layers
            HomeSymbol.Info -> MiuixIcons.Info
            HomeSymbol.Forward -> if (LocalLayoutDirection.current == LayoutDirection.Rtl)
                MiuixIcons.ChevronBackward else MiuixIcons.ChevronForward
            HomeSymbol.Expand -> Icons.Rounded.ExpandMore
            HomeSymbol.Collapse -> Icons.Rounded.ExpandLess
        }
    } else {
        when (symbol) {
            HomeSymbol.Active -> Icons.Rounded.CheckCircleOutline
            HomeSymbol.Inactive -> Icons.Rounded.WarningAmber
            HomeSymbol.Phone -> Icons.Rounded.PhoneAndroid
            HomeSymbol.System -> Icons.Rounded.Settings
            HomeSymbol.Version -> Icons.Rounded.Update
            HomeSymbol.Hardware -> Icons.Rounded.Memory
            HomeSymbol.Info -> Icons.Rounded.Info
            HomeSymbol.Update -> Icons.Rounded.SystemUpdateAlt
            HomeSymbol.Forward -> Icons.AutoMirrored.Rounded.KeyboardArrowRight
            HomeSymbol.Expand -> Icons.Rounded.ExpandMore
            HomeSymbol.Collapse -> Icons.Rounded.ExpandLess
        }
    }
    if (miuix) MiuixIcon(vector, contentDescription = null, tint = tint, modifier = modifier)
    else Icon(vector, contentDescription = null, tint = tint, modifier = modifier)
}
