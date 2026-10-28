package com.fr.colorostools.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.fr.colorostools.ui.shell.LocalEnableSwipeDismiss
import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavController
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection

@Serializable
sealed interface AppRoute : NavKey

@Serializable
data object MainRoute : AppRoute

@Serializable
data object ThemeRoute : AppRoute

@Serializable
data object SearchRoute : AppRoute

@Serializable
data class ScopeRoute(
    val pageKey: String,
    val title: String = "",
    val scrollKey: String = "",
    val scrollPosition: Int = -1,
) : AppRoute

/** miuix-nav requires unique keys; repeated taps must not push the same page twice. */
internal fun NavController.navigateTo(route: AppRoute) {
    if (route !in backStack) push(route)
}

/** One page stack for both appearances; transitions and back gestures belong to miuix-nav. */
@Composable
internal fun AppNavDisplay(
    navController: NavController,
    modifier: Modifier = Modifier,
    mainContent: @Composable () -> Unit,
    themeContent: @Composable () -> Unit,
    searchContent: @Composable () -> Unit,
    scopeContent: @Composable (ScopeRoute) -> Unit,
) {
    // Read outside the entry builder so changing the preference updates entry metadata.
    val swipeDismiss = when {
        !LocalEnableSwipeDismiss.current -> NavSwipeDirection.None
        LocalLayoutDirection.current == LayoutDirection.Rtl -> NavSwipeDirection.RightToLeft
        else -> NavSwipeDirection.LeftToRight
    }
    NavDisplay(
        navController = navController,
        modifier = modifier,
        effects = NavDisplayEffects(cornerClipRadius = rememberNavSystemCornerRadius()),
    ) {
        entry<MainRoute> { mainContent() }
        entry<ThemeRoute>(swipeDismiss = swipeDismiss) { themeContent() }
        entry<SearchRoute>(swipeDismiss = swipeDismiss) { searchContent() }
        entry<ScopeRoute>(swipeDismiss = swipeDismiss) { route -> scopeContent(route) }
    }
}
