/*
 * FKColorOS 页面外壳：Material 与 Miuix 分别使用各自原生的可折叠大标题。
 */
package com.fr.colorostools.ui.compose.components.material

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.fr.colorostools.ui.theme.LocalUiMode
import com.fr.colorostools.ui.theme.UiMode
// Miuix 页面骨架使用的库组件。
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.TopAppBar as MiuixTopAppBar
import top.yukonga.miuix.kmp.basic.TopAppBarState as MiuixTopAppBarState
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState as rememberMiuixTopAppBarState

private data class HeaderScrollPosition(val collapsedFraction: Float, val contentOffset: Float)

/**
 * 宿主是否已经提供底部导航栏（MainShell 的 NavigationBar 已消费系统导航栏 inset）。
 * 为 true 时 [ExpressiveList] 不再重复叠加导航栏内边距，避免列表底部多出一段空白。
 */
val LocalBottomBarPresent = staticCompositionLocalOf { false }

/**
 * 悬浮底栏的高度（由 MainShell 提供）：内容列表在常规底部内边距之外再预留这么多，
 * 使条目可以滑到悬浮胶囊之下（模糊/玻璃效果因此有可采样的内容），同时最后一条不会被永久遮挡。
 */
val LocalShellBottomInset = staticCompositionLocalOf { 0.dp }

/**
 * 内容列表底部内边距的统一公式：调用点的常规基准量 + 系统导航栏 / caption bar
 * （宿主底栏未消费 inset 时叠加）+ 悬浮胶囊底栏占位（[LocalShellBottomInset]）。
 *
 * 供 [ExpressiveList]、[MiuixExpressiveList] 以及自带 LazyColumn 的页面
 * （作用域特殊页的 Miuix 布局，见 `ScopePageSpec.contentMiuix`）复用同一份取值：
 * 各自硬编码 8/16dp 会让最后一条在悬浮底栏开启时被胶囊永久遮挡。
 */
@Composable
fun materialBottomInset(base: Dp = 16.dp): Dp {
    val navBars = WindowInsets.navigationBars.asPaddingValues()
    val captionBar = WindowInsets.captionBar.asPaddingValues()
    // 宿主已有底部导航栏（已消费导航栏 inset）时不再重复叠加，避免底部空白
    val navInset = if (LocalBottomBarPresent.current) {
        0.dp
    } else {
        navBars.calculateBottomPadding() + captionBar.calculateBottomPadding()
    }
    return base + navInset + LocalShellBottomInset.current
}

/**
 * 统一页面外壳（无返回键时传 onBack = null，用于底部导航主页面）。
 */
@Composable
fun MaterialPageScaffold(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    // 切换外观时把同一份页面内容在两条骨架之间「搬移」（movable content）：页面自身的
    // remember 状态（列表滚动位置、页内临时状态）随内容走，只有外侧骨架被替换。
    // rememberUpdatedState 防止固化首次组合的 lambda（否则页内回调会引用过期取值）。
    val currentContent = rememberUpdatedState(content)
    val movableContent = remember {
        movableContentOf<PaddingValues> { innerPadding -> currentContent.value(innerPadding) }
    }
    val uiMode = LocalUiMode.current
    val materialState = rememberTopAppBarState()
    val miuixState = rememberMiuixTopAppBarState()
    var previousUiMode by remember { mutableStateOf(uiMode) }
    var pendingHeaderPosition by remember(uiMode) {
        mutableStateOf(
            when {
                previousUiMode == uiMode -> null
                previousUiMode == UiMode.Miuix -> HeaderScrollPosition(miuixState.collapsedFraction, miuixState.contentOffset)
                else -> HeaderScrollPosition(materialState.collapsedFraction, materialState.contentOffset)
            }
        )
    }
    SideEffect { previousUiMode = uiMode }
    // 两套顶栏各自保留原生状态；目标顶栏测量后再按折叠比例换算，避免重置标题。
    val restoreHeaderPosition = Modifier.onSizeChanged {
        val position = pendingHeaderPosition ?: return@onSizeChanged
        val limit = if (uiMode == UiMode.Miuix) miuixState.heightOffsetLimit else materialState.heightOffsetLimit
        if (!limit.isFinite() || limit == -Float.MAX_VALUE) return@onSizeChanged
        val offset = limit * position.collapsedFraction.coerceIn(0f, 1f)
        if (uiMode == UiMode.Miuix) {
            miuixState.heightOffset = offset
            miuixState.contentOffset = position.contentOffset
        } else {
            materialState.heightOffset = offset
            materialState.contentOffset = position.contentOffset
        }
        pendingHeaderPosition = null
    }
    // 两条外观线分别使用各自的原生顶栏组件与滚动行为。
    if (uiMode == UiMode.Miuix) {
        MiuixPageScaffold(
            title = title,
            modifier = modifier,
            topAppBarState = miuixState,
            topAppBarModifier = restoreHeaderPosition,
            onBack = onBack,
            actions = actions,
            floatingActionButton = floatingActionButton,
            bottomBar = bottomBar,
            content = movableContent,
        )
        return
    }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(materialState)
    MaterialScaffold(
        modifier = modifier,
        topBar = {
            LargeFlexibleTopAppBar(
                modifier = restoreHeaderPosition,
                navigationIcon = {
                    if (onBack != null) MaterialTopBarBackButton(onClick = onBack)
                },
                title = { Text(title) },
                actions = actions,
                colors = materialTopAppBarColors(),
                scrollBehavior = scrollBehavior,
                windowInsets = WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                ),
            )
        },
        floatingActionButton = floatingActionButton,
        bottomBar = bottomBar,
        contentWindowInsets = if (LocalBottomBarPresent.current) {
            WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
        } else {
            WindowInsets.safeDrawing
        },
    ) { innerPadding ->
        Box(Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)) {
            movableContent(innerPadding)
        }
    }
}

/**
 * Miuix 原生大标题与默认滚动行为。内容通过同一条嵌套滚动连接驱动折叠，
 * Scaffold 的实际顶栏高度继续由调用点作为内容内边距消费。
 * 弹层仍使用全应用唯一的根部 popup host。
 */
@Composable
private fun MiuixPageScaffold(
    title: String,
    topAppBarState: MiuixTopAppBarState,
    topAppBarModifier: Modifier,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior(state = topAppBarState)
    Scaffold(
        modifier = modifier,
        topBar = {
            MiuixTopAppBar(
                modifier = topAppBarModifier,
                title = title,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    if (onBack != null) MiuixTopBarBackButton(onClick = onBack)
                },
                actions = actions,
            )
        },
        floatingActionButton = floatingActionButton,
        bottomBar = bottomBar,
        // 空槽位：弹层冒泡到根部 Miuix Scaffold 的默认 popupHost（各页不得自装 host）
        popupHost = {},
        // 合并系统栏和屏幕开孔的安全边距。
        contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout).only(
            WindowInsetsSides.Horizontal
        ),
    ) { innerPadding ->
        Box(Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)) {
            content(innerPadding)
        }
    }
}

/** Miuix 返回按钮，RTL 时图标水平镜像。 */
@Composable
private fun MiuixTopBarBackButton(onClick: () -> Unit) {
    val layoutDirection = LocalLayoutDirection.current
    IconButton(onClick = onClick) {
        Icon(
            modifier = Modifier.graphicsLayer {
                if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
            },
            imageVector = MiuixIcons.Back,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onBackground,
        )
    }
}

