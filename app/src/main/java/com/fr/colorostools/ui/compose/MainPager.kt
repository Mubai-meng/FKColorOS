package com.fr.colorostools.ui.compose

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.fr.colorostools.ui.shell.LocalPagerInterceptionMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.utils.PagerGestureNestedScrollConnection
import top.yukonga.miuix.kmp.utils.PagerInterceptionMode
import top.yukonga.miuix.kmp.utils.PagerNavigationSpringSpec
import top.yukonga.miuix.kmp.utils.pagerGestureOverride
import top.yukonga.miuix.kmp.utils.springAnimateToPage

/**
 * 底栏目标与实际分页位置的协调器。
 * 切页任务可被后续点击或拖动打断，选中项随最终落定页面同步。
 */
@Stable
internal class MainPagerState(
    val pagerState: PagerState,
    private val coroutineScope: CoroutineScope,
) {
    var selectedPage by mutableIntStateOf(pagerState.currentPage)
        private set

    var isNavigating by mutableStateOf(false)
        private set

    private var navigationJob: Job? = null

    fun animateToPage(target: Int) {
        if (target !in 0 until pagerState.pageCount || target == selectedPage) return
        navigationJob?.cancel()
        selectedPage = target
        isNavigating = true
        // 先登记任务再启动，确保立即完成或取消时也能正确清理自己的状态。
        val job = coroutineScope.launch(start = CoroutineStart.LAZY) {
            val currentJob = coroutineContext.job
            try {
                pagerState.springAnimateToPage(target)
            } finally {
                if (navigationJob == currentJob) {
                    isNavigating = false
                    selectedPage = pagerState.currentPage
                }
            }
        }
        navigationJob = job
        job.start()
    }

    fun syncPage() {
        if (!isNavigating) selectedPage = pagerState.currentPage
    }
}

@Composable
internal fun rememberMainPagerState(pagerState: PagerState): MainPagerState {
    val scope = rememberCoroutineScope()
    return remember(pagerState, scope) { MainPagerState(pagerState, scope) }
}

/** 根页面返回只切回主页；二级栈或阻塞弹层出现时交还返回处理权。 */
@Composable
internal fun MainPagerBackHandler(state: MainPagerState, enabled: Boolean) {
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = enabled && state.selectedPage != MainTab.Home.ordinal,
        onBackCompleted = { state.animateToPage(MainTab.Home.ordinal) },
    )
}

/** 两套外观共用同一个分页与手势实现，底栏仅负责选择目标页。 */
@Composable
internal fun MainPager(
    state: MainPagerState,
    modifier: Modifier = Modifier,
    gesturesEnabled: Boolean = true,
    gestureMode: Int = LocalPagerInterceptionMode.current,
    pageKey: (Int) -> Any = { it },
    content: @Composable (Int) -> Unit,
) {
    val pager = state.pagerState
    val mode = PagerInterceptionMode.entries.getOrElse(gestureMode) {
        PagerInterceptionMode.CrossAxisInterceptor
    }
    val intercept = mode == PagerInterceptionMode.CrossAxisInterceptor
    var contentReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // 首屏完成一帧后再保留其他页，避免首次绘制同时加载所有页面。
        withFrameNanos { }
        withFrameNanos { }
        contentReady = true
    }
    LaunchedEffect(pager.currentPage) { state.syncPage() }

    HorizontalPager(
        state = pager,
        modifier = modifier
            .fillMaxSize()
            .pagerGestureOverride(pagerState = pager, mode = mode, enabled = gesturesEnabled),
        key = pageKey,
        beyondViewportPageCount = if (contentReady) (pager.pageCount - 1).coerceAtLeast(0) else 0,
        overscrollEffect = null,
        userScrollEnabled = gesturesEnabled && !intercept,
        pageNestedScrollConnection = if (intercept) {
            PagerGestureNestedScrollConnection
        } else {
            PagerDefaults.pageNestedScrollConnection(pager, Orientation.Horizontal)
        },
        flingBehavior = PagerDefaults.flingBehavior(pager, snapAnimationSpec = PagerNavigationSpringSpec),
    ) { page ->
        if (contentReady || page == pager.settledPage) content(page)
    }
}
