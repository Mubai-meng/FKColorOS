package com.fr.colorostools.ui.shell

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.fr.colorostools.utils.SettingsPrefs
import com.fr.colorostools.utils.getBoolean
import com.fr.colorostools.utils.getFloat
import com.fr.colorostools.utils.getInt
import com.fr.colorostools.utils.getString

/** 页面切换手势，对应 miuix `PagerInterceptionMode` 序号。 */
const val PagerModeNative = 0
const val PagerModeCrossAxis = 1
const val PagerModeIosLike = 2

/** 界面缩放下限 / 上限。 */
const val PageScaleMin = 0.8f
const val PageScaleMax = 1.1f

/** 模块描述行数下限 / 上限。 */
const val ModuleLinesMin = 1
const val ModuleLinesMax = 5

/** 悬浮底栏样式及其持久化值。 */
enum class FloatingBottomBarStyle(val value: String) {
    Miuix("miuix"),
    IosLike("ios_like"),
}

/**
 * 外壳行为偏好，包含导航、手势及布局设置。
 *
 * 偏好键及默认值：
 * enable_navigation_badge = true、enable_predictive_back = true、enable_swipe_dismiss = true、
 * pager_interception_mode = 1、page_scale = 1.0f、module_description_max_lines = 4、
 * enable_blur = false、enable_floating_bottom_bar = false、floating_bottom_bar_style = miuix、
 * enable_category_badge_colors = true。
 *
 * enable_predictive_back 默认开启；
 * targetSdk 已提升到 37（>= 33），系统默认即为应用派发预测性返回，故改为「默认开启、可手动关闭」
 * 的 opt-out 语义。
 */
@Immutable
data class ShellSettings(
    val navigationBadge: Boolean = true,
    val predictiveBack: Boolean = true,
    val swipeDismiss: Boolean = true,
    val pagerInterceptionMode: Int = PagerModeCrossAxis,
    val pageScale: Float = 1.0f,
    val moduleDescriptionMaxLines: Int = 4,
    /** Miuix 顶栏 / 底栏及 Material 悬浮底栏背景模糊（需 isRenderEffectSupported()，API 31+） */
    val enableBlur: Boolean = false,
    /** 只显示图标的悬浮底栏，适用于两种外观。 */
    val enableFloatingBottomBar: Boolean = false,
    /** 两种样式均由 enableBlur 控制模糊效果。 */
    val floatingBottomBarStyle: FloatingBottomBarStyle = FloatingBottomBarStyle.Miuix,
    /** 功能页分类行（应用相关 / 状态栏相关 / …）使用多彩徽标；关闭则退化成主题色单色徽标。 */
    val categoryBadgeColors: Boolean = true,
)

object ShellSettingsController {
    const val KEY_NAVIGATION_BADGE = "enable_navigation_badge"
    const val KEY_PREDICTIVE_BACK = "enable_predictive_back"
    const val KEY_SWIPE_DISMISS = "enable_swipe_dismiss"
    const val KEY_PAGER_MODE = "pager_interception_mode"
    const val KEY_PAGE_SCALE = "page_scale"
    const val KEY_MODULE_LINES = "module_description_max_lines"
    const val KEY_ENABLE_BLUR = "enable_blur"
    const val KEY_FLOATING_BOTTOM_BAR = "enable_floating_bottom_bar"
    const val KEY_FLOATING_BOTTOM_BAR_STYLE = "floating_bottom_bar_style"
    const val KEY_CATEGORY_BADGE_COLORS = "enable_category_badge_colors"
    // 旧液态玻璃开关仅用于兼容已有偏好。
    const val KEY_FLOATING_BOTTOM_BAR_BLUR = "enable_floating_bottom_bar_blur"

    fun get(context: Context): ShellSettings = ShellSettings(
        navigationBadge = context.getBoolean(SettingsPrefs, KEY_NAVIGATION_BADGE, true),
        predictiveBack = context.getBoolean(SettingsPrefs, KEY_PREDICTIVE_BACK, true),
        swipeDismiss = context.getBoolean(SettingsPrefs, KEY_SWIPE_DISMISS, true),
        pagerInterceptionMode = context.getInt(SettingsPrefs, KEY_PAGER_MODE, PagerModeCrossAxis)
            .coerceIn(PagerModeNative, PagerModeIosLike),
        pageScale = context.getFloat(SettingsPrefs, KEY_PAGE_SCALE, 1.0f)
            .coerceIn(PageScaleMin, PageScaleMax),
        moduleDescriptionMaxLines = context.getInt(SettingsPrefs, KEY_MODULE_LINES, 4)
            .coerceIn(ModuleLinesMin, ModuleLinesMax),
        enableBlur = context.getBoolean(SettingsPrefs, KEY_ENABLE_BLUR, false),
        enableFloatingBottomBar = context.getBoolean(SettingsPrefs, KEY_FLOATING_BOTTOM_BAR, false),
        floatingBottomBarStyle = floatingBottomBarStyle(context),
        categoryBadgeColors = context.getBoolean(SettingsPrefs, KEY_CATEGORY_BADGE_COLORS, true),
    )

    private fun floatingBottomBarStyle(context: Context): FloatingBottomBarStyle {
        val stored = context.getString(SettingsPrefs, KEY_FLOATING_BOTTOM_BAR_STYLE)
        if (stored.isNotEmpty()) {
            return FloatingBottomBarStyle.entries.firstOrNull { it.value == stored }
                ?: FloatingBottomBarStyle.Miuix
        }
        return if (context.getBoolean(SettingsPrefs, KEY_FLOATING_BOTTOM_BAR_BLUR, false)) {
            FloatingBottomBarStyle.IosLike
        } else {
            FloatingBottomBarStyle.Miuix
        }
    }
}

/** 底部导航角标开关 */
val LocalEnableNavigationBadge = staticCompositionLocalOf { true }

/** 底栏背景模糊开关（Miuix 顶栏使用库默认样式）。 */
val LocalEnableBlur = staticCompositionLocalOf { false }

/** 悬浮底栏开关。 */
val LocalEnableFloatingBottomBar = staticCompositionLocalOf { false }

/** 当前悬浮底栏样式。 */
val LocalFloatingBottomBarStyle = staticCompositionLocalOf { FloatingBottomBarStyle.Miuix }

/** 功能页分类行多彩图标开关（关闭时用主题色单色徽标）。 */
val LocalCategoryBadgeColors = staticCompositionLocalOf { true }

/** 边缘横移返回手势开关 */
val LocalEnableSwipeDismiss = staticCompositionLocalOf { true }

/** 五个一级页面共用的分页手势模式。 */
val LocalPagerInterceptionMode = staticCompositionLocalOf { PagerModeCrossAxis }

/** 模块描述最大行数 */
val LocalModuleDescriptionMaxLines = staticCompositionLocalOf { 4 }

/**
 * 底部导航角标数据源。
 *
 * 由主页更新检查的结果驱动：发现新版本时在「主页」tab 上显示角标。
 */
@Stable
object ShellBadgeState {
    var updateAvailable by mutableStateOf(false)
}
