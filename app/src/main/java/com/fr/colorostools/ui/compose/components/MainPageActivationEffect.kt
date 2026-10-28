package com.fr.colorostools.ui.compose.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/** 预组合页面不启动业务加载；数据首次展示时加载，恢复前台仅刷新当前页。 */
@Composable
internal fun MainPageActivationEffect(
    isCurrentPage: Boolean,
    onFirstActive: () -> Unit,
    onResume: () -> Unit,
) {
    // 与页面 remember 数据保持相同生命周期，重建后仍需要重新加载。
    var activated by remember { mutableStateOf(false) }
    val current by rememberUpdatedState(isCurrentPage)
    val firstActive by rememberUpdatedState(onFirstActive)
    val resume by rememberUpdatedState(onResume)

    LaunchedEffect(isCurrentPage) {
        if (isCurrentPage && !activated) {
            activated = true
            firstActive()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (activated && current) resume()
    }
}
