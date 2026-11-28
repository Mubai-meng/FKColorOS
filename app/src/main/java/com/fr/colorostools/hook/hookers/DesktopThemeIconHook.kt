package com.fr.colorostools.hook.hookers

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.widget.ImageView
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import com.fr.colorostools.hook.HookEnv
import com.fr.colorostools.ref.Reflect
import com.fr.colorostools.utils.LogUtils
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 功能：**通知图标改为桌面当前主题图标**（宿主 `com.android.systemui`）。
 *
 * ## 与参考实现（Glyph / coloros-mod）的差异
 *
 * 参考实现挂的是 `IconManager#getIconDescriptor(NotificationEntry, boolean)`，本项目改挂
 * **`StatusBarIconView#getIcon(StatusBarIcon)`**，理由（2026-11-24 逆向 SystemUI 17.99.02 实证）：
 *
 * | 维度 | `IconManager#getIconDescriptor` | `StatusBarIconView#getIcon`（本实现） |
 * |---|---|---|
 * | 缓存短路 | 方法开头命中 `IconPack.mSmallIconDescriptor` 就 early-return，刷新不一定重走 | 无短路，`updateDrawable()` 每次都调 |
 * | 返回值 | `StatusBarIcon`，要克隆再塞回 `preloadedIcon` + `icon` | 直接是要显示的 `Drawable`，换掉即可 |
 * | 判等风险 | `StatusBarIconView#set()` 按 `Icon` 的 resId 判等，相等直接跳过重绘 | 不经过 `set()`，无判等短路 |
 * | 覆盖范围 | 状态栏 | 状态栏 / 悬浮通知 / AOD 通知 / 锁屏状态栏（都走同一个 View 实例） |
 *
 * ## 着色：必须按「不可着色」处理
 *
 * `StatusBarIconView` 默认 `mIsIconColorable = true`，此时 `updateIconColor$1()` 会按通知
 * 前景色给图标打一层 `ColorMatrixColorFilter`（单色遮罩）—— 全彩的桌面图标被这样染一遍
 * 就成了白块。ColorOS 自己的判定在
 * `StatusBarIconControllerExImpl#updateStatusBarIconGrayScale(Drawable, StatusBarIconView, StatusBarNotification)`：
 *
 * ```java
 * boolean z = !OplusNotificationSmallIconUtil.useAppIconForSmallIcon(n)
 *         && OplusContrastColorUtil.getInstance(ctx).isGrayscaleIcon(drawable);
 * view.setTag(R.id.icon_is_grayscale, z);
 * view.setIsIconColorable(z);
 * ```
 *
 * 它靠「像素是不是灰阶」来猜。多数主题图标是全彩的，猜得对；但**单色图标包**会被猜成
 * 灰阶 ⇒ 又被染色。所以这里再挂同一个方法做兜底：只要这条通知确实换成了主题图标，
 * 就把 `icon_is_grayscale` 标 false 并 `setIsIconColorable(false)`，与 ColorOS 原生
 * 「用了应用图标就别再染色」的语义对齐（原生就是 `useAppIconForSmallIcon` 为 true 时判 false）。
 *
 * ## 两条通道，缺一不可（2026-11-24 二次返工）
 *
 * 只挂 `StatusBarIconView#getIcon` **覆盖不到**用户实际会看的界面。SystemUI 17.99.02 里
 * 「通知小图标」有**四条互不相交**的取图路径：
 *
 * | 界面 | 取图路径 | 是否经过 `StatusBarIconView` |
 * |---|---|---|
 * | 状态栏 / 悬浮通知 / AOD 状态栏 | `IconManager#getIconDescriptor` → `StatusBarIconView#getIcon` | 是 |
 * | 通知面板下拉的头部行 | `OplusNotificationHeaderViewWrapperExImp#getBadge` → `getSmallIcon().loadDrawable()` | **否** |
 * | 锁屏通知卡片 / 胶囊 | `CapsuleNotificationDataController` → `icon.loadDrawable()` | **否** |
 *
 * 所以另一个钩子挂在**源头**：`NotificationListener#onNotificationPosted` 里直接把
 * `Notification#smallIcon` 换成主题图标位图。下游所有路径读的都是这个字段，
 * 改一处即全覆盖；原来那条 `getIcon` 通道保留，负责给状态栏做精确尺寸渲染。
 *
 * ## ⚠️ 绝对不要写 extras `oplus_smallicon_use_app_icon`
 *
 * v1.5.14 曾写入该键（想借 ColorOS「smallIcon 就是应用图标」语义免掉灰度判定），
 * 读码后确认是**反向陷阱**：`OplusNotificationEntryExImpl#useAppIconForSmallIcon()` 会把它
 * 缓存成 `int`，而面板头部行 `OplusNotificationHeaderViewWrapperExImp#updateIconForAppIconIfNeeded`
 * 在该值 > 0 时会 `launch` 一个协程调 `NotificationResolverEx#getPackageIconForNotification(sbn)`，
 * 拿 **APK 里的原始启动图标** `setImageDrawable` 覆盖掉我们的主题图标。
 *
 * 着色改由 [hookGrayScale] 在 `updateStatusBarIconGrayScale` 上做定点修正
 * （原生那段是「猜像素」，对全彩主题图标本身就会判 false）。
 */
internal object DesktopThemeIconHook {

    private const val TAG = "DesktopThemeIcon"

    /** 状态栏图标承载 View：`getIcon()` 是 Icon→Drawable 的唯一解析点 */
    private const val CLS_STATUS_BAR_ICON_VIEW = "com.android.systemui.statusbar.StatusBarIconView"

    /** ColorOS 灰度/着色判定实现类（`updateStatusBarIconGrayScale` 的真身） */
    private const val CLS_ICON_CONTROLLER_IMPL =
        "com.oplus.systemui.statusbar.phone.StatusBarIconControllerExImpl"

    /** SystemUI 通知唯一入口：改 `Notification#smallIcon` 源头，覆盖全部界面 */
    private const val CLS_NOTIFICATION_LISTENER = "com.android.systemui.statusbar.NotificationListener"

    /**
     * 通知行（含锁屏卡片）头部图标 wrapper。
     *
     * 通知行左侧的 app 图标由 RemoteViews 渲染，`RemoteViews` 里的 `setImageViewIcon` action
     * 存的是**构建期快照**，改 `Notification#smallIcon` 影响不到它。
     * `onContentUpdated` 是 RemoteViews inflate 完成**之后**的统一回调
     * （`NotificationRowContentBinderImpl#onAsyncInflationFinished` L329-335 实证），
     * 在这里直接给 `mIcon` 换图才能覆盖通知面板行。
     */
    private const val CLS_HEADER_WRAPPER = "com.android.systemui.statusbar.notification.row.wrapper.NotificationHeaderViewWrapper"

    /**
     * ColorOS 在原生 header wrapper 外面套的一层 Ex 实现：`updateIconColor()` 会给
     * `mIcon` 的 drawable 打 `SRC_ATOP` 颜色滤镜（锁屏/暗色壁纸下按对比度染成单色）。
     * 全彩主题图标被染一遍就成白块，看起来跟「没换」一样，所以这里要把滤镜清掉。
     */
    private const val CLS_HEADER_WRAPPER_EX =
        "com.oplus.systemui.statusbar.notification.row.wrapper.OplusNotificationHeaderViewWrapperExImp"

    /**
     * 通知行本体。`setOnKeyguard(boolean)` 是「上/下锁屏」的唯一状态切换点，
     * 在这里补一次图标 —— 锁屏态切换会重排内容，容易把行图标冲回 RemoteViews 里的构建期快照。
     */
    private const val CLS_NOTIFICATION_ROW =
        "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow"

    /**
     * 锁屏胶囊卡片（ColorOS 自有视图，与通知行**完全两套**）。
     * 取图走 `CapsuleNotificationDataController#getIconData`，非系统应用那条分支拿的是
     * `NotificationResolverEx#getPackageIcon`（APK 原始图标），改 `smallIcon` 改不到它。
     */
    private const val CLS_CAPSULE_CARD =
        "com.oplus.systemui.notification.lockscreen.notification.CapsuleNotificationCardView"

    /** 通知行内容容器的三个子视图 getter：折叠 / 展开 / 悬浮（含 ambient） */
    private val ROW_CHILD_GETTERS =
        arrayOf("getContractedChild", "getExpandedChild", "getHeadsUpChild", "getAmbientChild")

    /**
     * `Notification#smallIcon` 的真实字段名。
     *
     * framework `Notification.java` L371：`private Icon mSmallIcon;`（`getSmallIcon()` 只是它的 getter）。
     * **不能**用 `getField("smallIcon")` —— 一是名字不对，二是 `getField` 只查 public 成员，
     * v1.5.15 就是这里抛 `NoSuchFieldException` 导致所有界面都改不成功。
     */
    private const val FIELD_SMALL_ICON = "mSmallIcon"

    /** 「开关」读取结果的复用窗口（ms）：本钩子是高频路径，别每次都读 remote prefs */
    private const val ENABLED_TTL_MS = 300L

    /**
     * 诊断日志的**总量**上限（条）。

     * 诊断一律走 `LogUtils.e`，目的是「关着详细日志也能判断模块有没有注入、钩子有没有命中」；
     * 但取图是每条通知每次刷新都会走的热路径，不加**总量**上限会把日志页刷满
     （前一版是「每个钩子各自前 8 次」，九个钩子加起来最高能写上百条）。
     */
    private const val DIAG_TOTAL_LIMIT = 30

    /** 自检延迟（ms）：等宿主 Application / PackageManager / LauncherApps 都就绪 */
    private const val SELF_CHECK_DELAY_MS = 4_000L

    /** 自检包：宿主自身（无启动 activity，走 ApplicationInfo）+ 常用第三方（有启动 activity） */
    private val SELF_CHECK_PACKAGES = arrayOf("com.android.systemui", "com.tencent.mm")

    @Volatile
    private var enabledCache = false

    @Volatile
    private var enabledAt = 0L

    @Volatile
    private var iconSizeCache = 0

    /** `R.id.icon_is_grayscale` 的运行时 id：-1 未探测，0 不存在 */
    @Volatile
    private var grayTagId = -1

    private val firstHitLogged = AtomicBoolean(false)

    /** 源头通道的首个命中（只打一次，避免 onNotificationPosted 高频刷日志） */
    private val sourceHitLogged = AtomicBoolean(false)

    @Volatile
    private var notifIconSizeCache = 0

    private val disableCleared = AtomicBoolean(false)

    /** 诊断日志累计条数：全功能共用一个总量上限，到顶后所有诊断静默 */
    private val diagSent = AtomicInteger(0)

    /** 本次 install 实际装上的通道名（只用于那条 E 级汇总锚点） */
    private val channels = ArrayList<String>()

    fun install() {
        // 顺序有讲究：先装源头（覆盖面最大），再装状态栏精确渲染、通知行、着色修正
        channels += hookNotificationSource()
        channels += hookStatusBarIconView()
        channels += hookHeaderIcon()
        channels += hookHeaderResolveViews()
        channels += hookHeaderIconColor()
        channels += hookRowKeyguardState()
        channels += hookCapsuleCard()
        channels += hookGrayScale()
        scheduleSelfCheck()
        note(
            "install",
            "桌面主题图标 Hooker 安装完成（开关=${enabled()}）：" +
                "通道 ${if (channels.isNotEmpty()) channels.joinToString("+") else "无"}",
        )
    }

    /**
     * 启动后延迟跑一次取图自检。
     *
     * 「钩子装上了但取不到图」和「钩子没装上」在日志里长得一样（都只有 install 那几行），
     * 而取图链路有四段（反射句柄 / 目标包信息 / loadItemIcon / 位图化），失败时全返回
     * null 且不报错。跑一次自检把这些中间量逐段打出来，一次重启就能定位问题在哪一段。
     */
    private fun scheduleSelfCheck() {
        runCatching {
            Handler(Looper.getMainLooper()).postDelayed({
                val context = HookEnv.context() ?: run {
                    LogUtils.i(TAG, "selfcheck", "拿不到宿主 Context，自检跳过")
                    return@postDelayed
                }
                LogUtils.i(TAG, "selfcheck", "开始自检（开关=${enabled()}）")
                for (pkg in SELF_CHECK_PACKAGES) {
                    runCatching { DesktopThemeIconProvider.diagnose(context, pkg) }
                        .onFailure {
                            LogUtils.e(TAG, "selfcheck", "$pkg 自检异常：${it.javaClass.simpleName}: ${it.message}")
                        }
                }
            }, SELF_CHECK_DELAY_MS)
        }.onFailure {
            LogUtils.w(TAG, "selfcheck", "调度自检失败：${it.javaClass.simpleName}: ${it.message}")
        }
    }

    // ------------------------------------------------------------------ 钩子 0：通知源头

    /**
     * `NotificationListener#onNotificationPosted(StatusBarNotification, RankingMap): void` —— public final。
     *
     * 通知进入 SystemUI 的第一站。这里把 `Notification#smallIcon` 换成主题图标位图，
     * 之后 `IconManager` / `CachingIconView` / `CapsuleNotificationDataController` / AOD
     * 全部自动拿到新图 —— 一条不改数据结构的捷径，覆盖 `StatusBarIconView` 管不到的界面。
     *
     * ⚠️ 必须在 `chain.proceed()` **之前**改：`proceed` 内部就把 sbn 分发进 pipeline 了。
     */
    private fun hookNotificationSource(): String {
        val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_NOTIFICATION_LISTENER)
        if (cls == null) {
            LogUtils.w(TAG, "install", "$CLS_NOTIFICATION_LISTENER 不存在，通知源头通道跳过")
            return ""
        }
        val method = Reflect.findMethodByShape(
            cls = cls,
            name = "onNotificationPosted",
            expect = listOf<Class<*>?>(StatusBarNotification::class.java, null),
            returnType = java.lang.Void.TYPE,
        )
        if (method == null) {
            LogUtils.w(TAG, "install", "onNotificationPosted 未命中，通知源头通道跳过")
            return ""
        }
        runCatching {
            HookEnv.module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    if (enabled()) {
                        runCatching { rewriteSmallIcon(chain.getArg(0) as? StatusBarNotification) }
                            .onFailure {
                                LogUtils.w(TAG, "onPosted", "改写 smallIcon 失败：${it.javaClass.simpleName}: ${it.message}")
                            }
                    }
                    return chain.proceed()
                }
            })
            installNote("已挂 $CLS_NOTIFICATION_LISTENER#onNotificationPosted（通知源头通道）")
        }.onFailure {
            LogUtils.w(TAG, "install", "挂 onNotificationPosted 失败：${it.javaClass.simpleName}: ${it.message}")
            return ""
        }
        return "源头"
    }

    /**
     * 把一条通知的 `smallIcon` 换成桌面主题图标。
     *
     * 换不到（没有启动 activity、包已卸载、非 ColorOS 主题）就原样放行，绝不用别的图顶替。
     */
    private fun rewriteSmallIcon(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        val context = HookEnv.context() ?: run {
            diag("onPosted", pkg) { "$pkg 拿不到 Context，源头通道本次跳过" }
            return
        }
        val bitmap = DesktopThemeIconProvider.resolve(
            context = context,
            pkg = pkg,
            userId = normalizeUserId(sbn.userId),
            sizePx = notificationIconPx(context),
        )
        diag("onPosted", pkg) { "$pkg -> ${if (bitmap != null) "${bitmap.width}px 命中" else "未取到主题图标"}" }
        if (bitmap == null) return
        if (!patchSmallIcon(sbn, bitmap)) return
        if (sourceHitLogged.compareAndSet(false, true)) {
            note("onPosted", "通知源头已改用桌面主题图标（首个命中：$pkg）")
        }
    }

    /**
     * 把主题图标位图写进通知的源字段 `smallIcon`，并标记「这就是应用图标」。
     *
     * 为什么两个通道都要调：新通知由 [rewriteSmallIcon] 在进入 SystemUI 时改；
     * **已存在的通知不会再走 `onNotificationPosted`**（`NotificationListener#onListenerConnected`
     * 对存量通知是直接分发给 handler，不经过该回调），只能靠 [hookStatusBarIconView]
     * 在状态栏取图时顺手补一次 —— 这样面板头部行 / 锁屏卡片随后读 `getSmallIcon()` 才是新图。
     *
     * ⚠️ `Notification#smallIcon` 是 public **字段**，但 Kotlin 见到 `getSmallIcon()` 会把它
     * 合成为只读属性（直接赋值报 "'val' cannot be reassigned"）⇒ 只能反射写字段。
     */
    private fun patchSmallIcon(sbn: StatusBarNotification, bitmap: Bitmap): Boolean {
        val notification = sbn.notification ?: return false
        // ---- 幂等短路（性能关键）----
        // 这条方法在 `StatusBarIconView#getIcon` 里**每次取图都会被调用**，而状态栏刷新极高频。
        // 若不加短路，每次都会：新建 Icon 对象 + 反射调 setSmallIcon 写字段 ⇒ 把一条普通通知
        // 的 `Notification` 反复标记为已变更（下游 `IconManager` 会重复解析/重绘）。
        // 位图来自 LRU 缓存，同一主题下**同一实例**，所以按引用比对即可判定「已经是我们的图」。
        val current = runCatching { notification.smallIcon }.getOrNull()
        if (current != null && isSameBitmap(current, bitmap)) return true

        val icon = Icon.createWithBitmap(bitmap)
        // 首选 framework 的公开 setter（`Notification#setSmallIcon(Icon)`，实现就是一次赋值）。
        // ⚠️ 句柄必须缓存：`getMethod` 在 ART 上是「native 查找 + 克隆 Method 对象」，
        // 且**未命中会构造并填栈一个 NoSuchMethodException**，放在热路径上是纯浪费。
        val setter = setSmallIconMethod(notification.javaClass)
        if (setter != null && runCatching { setter.invoke(notification, icon); true }.getOrDefault(false)) {
            return true
        }
        // 兜底：直接写 `mSmallIcon` 字段，沿父类链找（字段是 private，必须 getDeclaredField）
        var cls: Class<*>? = notification.javaClass
        while (cls != null) {
            val field = runCatching { cls.getDeclaredField(FIELD_SMALL_ICON) }.getOrNull()
            if (field != null) {
                return runCatching {
                    field.isAccessible = true
                    field.set(notification, icon)
                    true
                }.getOrElse {
                    LogUtils.w(TAG, "patchIcon", "写 $FIELD_SMALL_ICON 失败：${it.javaClass.simpleName}: ${it.message}")
                    false
                }
            }
            cls = cls.superclass
        }
        LogUtils.w(TAG, "patchIcon", "找不到 setSmallIcon / $FIELD_SMALL_ICON，无法改写源字段")
        return false
    }

    /**
     * 源头通道的目标尺寸：取宿主 `app_icon_size`（48dp）。
     *
     * 面板头部行 / 锁屏卡片 / AOD 的图标控件各自带 scaleType，会自行等比缩放，
     * 所以这里给一个「足够清晰的源图」即可，不需要像状态栏那样精确对齐 View 尺寸。
     */
    private fun notificationIconPx(context: Context): Int {
        val cached = notifIconSizeCache
        if (cached > 0) return cached
        var px = runCatching {
            context.resources.getDimensionPixelSize(android.R.dimen.app_icon_size)
        }.getOrDefault(0)
        if (px <= 0) px = 144
        val value = px.coerceIn(64, 192)
        notifIconSizeCache = value
        return value
    }

    /**
     * `Icon#getBitmap(): Bitmap` —— **@hide**，stub 里没有，只能反射（句柄缓存）。
     *
     * 用途仅限幂等短路：判断这条通知的 `smallIcon` 是否已经是我们那张位图。
     */
    @Volatile
    private var iconGetBitmapHandle: Method? = null

    @Volatile
    private var iconGetBitmapTried = false

    private fun isSameBitmap(icon: Icon, bitmap: Bitmap): Boolean {
        if (icon.type != Icon.TYPE_BITMAP) return false
        if (!iconGetBitmapTried) {
            synchronized(this) {
                if (!iconGetBitmapTried) {
                    iconGetBitmapHandle = runCatching {
                        Icon::class.java.getMethod("getBitmap").apply { runCatching { isAccessible = true } }
                    }.getOrNull()
                    iconGetBitmapTried = true
                }
            }
        }
        val getter = iconGetBitmapHandle ?: return false
        // 位图来自 LRU 缓存，同一主题下是**同一实例** ⇒ 引用比对即可，不必比像素
        return runCatching { getter.invoke(icon) === bitmap }.getOrDefault(false)
    }

    /** `Notification#setSmallIcon(Icon)` 句柄缓存；null = 该宿主没有（走字段兜底） */
    @Volatile
    private var setSmallIconHandle: Method? = null

    @Volatile
    private var setSmallIconTried = false

    private fun setSmallIconMethod(cls: Class<*>): Method? {
        if (setSmallIconTried) return setSmallIconHandle
        synchronized(this) {
            if (!setSmallIconTried) {
                setSmallIconHandle = runCatching {
                    cls.getMethod("setSmallIcon", Icon::class.java).apply {
                        runCatching { isAccessible = true }
                    }
                }.getOrNull()
                setSmallIconTried = true
            }
        }
        return setSmallIconHandle
    }

    // ------------------------------------------------------------------ 钩子 3：通知行图标

    /**
     * `NotificationHeaderViewWrapper#onContentUpdated(ExpandableNotificationRow): void`。
     *
     * ## 为什么必须单独挂这里
     *
     * 通知行左侧的 app 图标**不是** `StatusBarIconView`，而是 header 里的 `CachingIconView mIcon`，
     * 图由 RemoteViews 渲染 —— `RemoteViews` 里 `setImageViewIcon` action 存的是**构建期快照**，
     * 所以「改 `Notification#smallIcon`」对通知行无效（v1.5.15 实测正是如此：
     * 状态栏变了、列表不变）。
     *
     * `onContentUpdated` 的调用点在 `NotificationRowContentBinderImpl#onAsyncInflationFinished`
     * （L329-335），**在 RemoteViews inflate 完成之后**，此时设图不会被覆盖。
     *
     * 锁屏通知卡片同样是 `ExpandableNotificationRow`，一并覆盖。
     */
    private fun hookHeaderIcon(): String {
        val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_HEADER_WRAPPER)
        if (cls == null) {
            LogUtils.w(TAG, "install", "$CLS_HEADER_WRAPPER 不存在，通知行图标通道跳过")
            return ""
        }
        val method = Reflect.findMethodByShape(
            cls = cls,
            name = "onContentUpdated",
            expect = listOf<Class<*>?>(null),
            returnType = java.lang.Void.TYPE,
        )
        if (method == null) {
            LogUtils.w(TAG, "install", "onContentUpdated 未命中，通知行图标通道跳过")
            return ""
        }
        runCatching {
            HookEnv.module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    if (enabled()) {
                        runCatching { applyHeaderIcon(chain.thisObject, chain.getArg(0)) }
                            .onFailure {
                                LogUtils.w(TAG, "header", "换通知行图标失败：${it.javaClass.simpleName}: ${it.message}")
                            }
                    }
                    return result
                }
            })
            installNote("已挂 $CLS_HEADER_WRAPPER#onContentUpdated（通知行图标通道）")
        }.onFailure {
            LogUtils.w(TAG, "install", "挂 onContentUpdated 失败：${it.javaClass.simpleName}: ${it.message}")
            return ""
        }
        return "通知行"
    }

    /**
     * 给通知行 / 锁屏卡片的头部图标换图。
     *
     * 走反射是因为 `ExpandableNotificationRow` / `NotificationEntry` 都是隐藏类；
     * `mIcon` 是 `NotificationHeaderViewWrapper` 的 **public 字段**（L55 声明，L373 赋值）。
     */
    private fun applyHeaderIcon(wrapper: Any?, row: Any? = null) {
        if (wrapper == null) return
        val iconView = Reflect.getField(wrapper, "mIcon") as? ImageView ?: return

        val context = iconView.context

        // 短路：图已经就是我们铺的主题图标、**且还是当前主题这一张**时，宿主这一轮只是又叠了
        // 滤镜（或压根没改）—— 清掉滤镜就完事。重新 resolve + setImageDrawable 会多一次取图
        // 和一次重绘，而着色钩子挂在每次颜色重算都会走的路径上，不短路就是纯浪费。
        //
        // ⚠️ 必须比对 [DesktopThemeIconProvider.stamp]：换了主题 / 图标包 / 深浅色后印章会变，
        // 那时手上的图已经过期 ——「确实是我们铺的」不等于「还是当前主题」，不能跳过，
        // 否则换主题后已存在的通知会一直停在旧主题的图标上。
        //
        // ⚠️ 宿主的滤镜是打在 **drawable** 上的（`mIcon.getDrawable().setColorFilter(...)`），
        // 不是打在 ImageView 上，所以必须清 drawable 的滤镜；只调 `iconView.clearColorFilter()`
        // 清不掉它。
        val current = iconView.drawable
        if (current is ThemeIconDrawable &&
            current.themeStamp == DesktopThemeIconProvider.stamp(context)
        ) {
            runCatching { current.clearColorFilter() }
            runCatching { iconView.clearColorFilter() }
            return
        }

        // `mRow` 只在短路未命中时才需要 —— 取它要走一次反射缓存查找，
        // 放在短路之前就是热路径上的白开销（`updateIconColor` 每次颜色重算都会进来）。
        val rowObj = row ?: Reflect.getField(wrapper, "mRow") ?: return
        val entry = Reflect.callDeep(rowObj, "getEntry") ?: return
        val sbn = Reflect.callDeep(entry, "getSbn") as? StatusBarNotification ?: return
        val pkg = sbn.packageName ?: return
        val bitmap = DesktopThemeIconProvider.resolve(
            context = context,
            pkg = pkg,
            userId = normalizeUserId(sbn.userId),
            sizePx = headerIconPx(context),
        )
        diag("header", pkg) { "$pkg -> ${if (bitmap != null) "${bitmap.width}px 命中" else "未取到主题图标"}" }
        if (bitmap == null) return
        iconView.clearColorFilter()
        iconView.setImageDrawable(ThemeIconDrawable(context.resources, bitmap, stampOf(context)))
    }

    /**
     * 通知行头部图标的目标尺寸。
     *
     * 宿主用 `resolveHeaderIconContentSizePx()` 算尺寸，本质是 `app_icon_size`（48dp）；
     * 这里直接取同一个 dimen，ImageView 自己按 scaleType 缩放，给足分辨率即可。
     */
    private fun headerIconPx(context: Context): Int = notificationIconPx(context)

    // ---------------------------------------------- 钩子 4：header 视图重解析

    /**
     * `NotificationHeaderViewWrapper#resolveHeaderViews(): void` —— public final。
     *
     * `onContentUpdated` 只在「RemoteViews 异步 inflate 完成」那一刻回调；而 ColorOS 在
     * 分组折叠/展开、锁屏态切换、布局重排时会重新走 `resolveHeaderViews()`
     * （`mIcon = mView.findViewById(android.R.id.icon)`），这一步**不回调** `onContentUpdated`。
     * 在重抓完 header 之后补一次图，避免「只在某些状态下又被换回原始图标」。
     */
    private fun hookHeaderResolveViews(): String {
        val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_HEADER_WRAPPER)
        if (cls == null) {
            LogUtils.w(TAG, "install", "$CLS_HEADER_WRAPPER 不存在，header 重解析通道跳过")
            return ""
        }
        val method = runCatching {
            cls.declaredMethods.firstOrNull {
                it.name == "resolveHeaderViews" && it.parameterCount == 0 &&
                    it.returnType == java.lang.Void.TYPE
            }
        }.getOrNull()
        if (method == null) {
            LogUtils.w(TAG, "install", "resolveHeaderViews 未命中，header 重解析通道跳过")
            return ""
        }
        runCatching {
            HookEnv.module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    if (enabled()) {
                        runCatching {
                            applyHeaderIcon(chain.thisObject)
                        }.onFailure {
                            LogUtils.w(TAG, "header", "重解析补图失败：${it.javaClass.simpleName}: ${it.message}")
                        }
                    }
                    return result
                }
            })
            installNote("已挂 $CLS_HEADER_WRAPPER#resolveHeaderViews（header 重解析通道）")
        }.onFailure {
            LogUtils.w(TAG, "install", "挂 resolveHeaderViews 失败：${it.javaClass.simpleName}: ${it.message}")
            return ""
        }
        return "重解析"
    }

    // ---------------------------------------------- 钩子 4b：header 着色还原

    /**
     * `OplusNotificationHeaderViewWrapperExImp#updateIconColor(): boolean`。
     *
     * 宿主在这里判断「要不要按通知前景色给图标叠单色遮罩」，然后
     * `mIcon.getDrawable().setColorFilter(i, PorterDuff.Mode.SRC_ATOP)`。
     * 判定靠 `isGrayscaleOplus` 猜像素，全彩主题图标通常能猜对，但锁屏态/暗色壁纸下
     * 会按对比度取值，把图标染成单色 —— 视觉上等同「没换图」。
     *
     * 只要这条通知确实换成了主题图标（[DesktopThemeIconProvider.isResolved]），
     * 就在 `proceed()` 之后把图重新铺一遍并清掉滤镜。

     * [applyHeaderIcon] 内部带短路：图已经铺对时只清滤镜、不重新取图重铺，
     * 所以这个钩子留在颜色重算的热路径上是安全的。
     */
    private fun hookHeaderIconColor(): String {
        val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_HEADER_WRAPPER_EX)
        if (cls == null) {
            LogUtils.i(TAG, "install", "$CLS_HEADER_WRAPPER_EX 不存在，header 着色还原跳过")
            return ""
        }
        val method = Reflect.findMethodByShape(
            cls = cls,
            name = "updateIconColor",
            expect = emptyList(),
            returnType = Boolean::class.javaPrimitiveType,
        )
        if (method == null) {
            LogUtils.i(TAG, "install", "updateIconColor 未命中，header 着色还原跳过")
            return ""
        }
        runCatching {
            HookEnv.module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    if (enabled()) {
                        runCatching {
                            applyHeaderIcon(Reflect.callDeep(chain.thisObject, "getBase"))
                        }.onFailure {
                            LogUtils.w(TAG, "header", "着色还原失败：${it.javaClass.simpleName}: ${it.message}")
                        }
                    }
                    return result
                }
            })
            installNote("已挂 $CLS_HEADER_WRAPPER_EX#updateIconColor（header 着色还原）")
        }.onFailure {
            LogUtils.w(TAG, "install", "挂 updateIconColor 失败：${it.javaClass.simpleName}: ${it.message}")
            return ""
        }
        return "着色还原"
    }

    // ---------------------------------------------- 钩子 5：锁屏行补图

    /**
     * `ExpandableNotificationRow#setOnKeyguard(boolean): void`。
     *
     * 上锁是「行内容重排」的触发点：`onExpansionChanged` → `notifyHeightChanged`，
     * 期间颜色/头像/角标都会被重算，图标可能被冲回构建期快照。这里在 `proceed()` **之后**
     * 按行再刷一次，并且顺手把「窗口内所有图标视图」打出来（受 [DIAG_TOTAL_LIMIT]
     * 总量约束，与其它通道共用一个额度），用来定位锁屏卡片到底渲染在哪棵视图树上。
     */
    private fun hookRowKeyguardState(): String {
        val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_NOTIFICATION_ROW)
        if (cls == null) {
            LogUtils.w(TAG, "install", "$CLS_NOTIFICATION_ROW 不存在，锁屏补图通道跳过")
            return ""
        }
        val method = Reflect.findMethodByShape(
            cls = cls,
            name = "setOnKeyguard",
            expect = listOf<Class<*>?>(Boolean::class.javaPrimitiveType),
            returnType = java.lang.Void.TYPE,
        )
        if (method == null) {
            LogUtils.w(TAG, "install", "setOnKeyguard 未命中，锁屏补图通道跳过")
            return ""
        }
        runCatching {
            HookEnv.module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    if (chain.getArg(0) as? Boolean == true && enabled()) {
                        runCatching { onRowLocked(chain.thisObject) }
                            .onFailure {
                                LogUtils.w(TAG, "kg", "锁屏补图失败：${it.javaClass.simpleName}: ${it.message}")
                            }
                    }
                    return result
                }
            })
            installNote("已挂 $CLS_NOTIFICATION_ROW#setOnKeyguard（锁屏补图通道）")
        }.onFailure {
            LogUtils.w(TAG, "install", "挂 setOnKeyguard 失败：${it.javaClass.simpleName}: ${it.message}")
            return ""
        }
        return "锁屏"
    }

    private fun onRowLocked(row: Any?) {
        diag("kg", "enter") { "锁屏：进入补图" }
        dumpIconViews(row)
        val applied = applyRowIcons(row)
        diag("kg", "apply") { "按行补图${if (applied) "成功" else "未命中/跳过"}" }
        // 再补一帧：锁屏切换里有一部分重排是 post 出去的（动画/落位），
        // 同步补完仍可能被后续的一次 RemoteViews 重放盖掉。
        val rowView = row as? View ?: return
        runCatching { rowView.post { runCatching { applyRowIcons(row) } } }
    }

    /**
     * 给一条通知行的**所有内容子视图**换图（折叠 / 展开 / 悬浮三种 layout 各有一个图标控件）。
     *
     * 只认 `CachingIconView`：通知行里 `android.R.id.icon` 可能落在别的控件上（媒体封面等），
     * 那种情况一律不动，避免把封面图换成应用图标。
     */
    private fun applyRowIcons(row: Any?): Boolean {
        if (row == null) return false
        val rowView = row as? View ?: return false
        val context = runCatching { rowView.context }.getOrNull() ?: return false
        val entry = Reflect.callDeep(row, "getEntry") ?: return false
        val sbn = Reflect.callDeep(entry, "getSbn") as? StatusBarNotification ?: return false
        val pkg = sbn.packageName ?: return false
        val bitmap = DesktopThemeIconProvider.resolve(
            context = context,
            pkg = pkg,
            userId = normalizeUserId(sbn.userId),
            sizePx = headerIconPx(context),
        ) ?: return false
        val layout = Reflect.callDeep(row, "getPrivateLayout")
        val holders = ArrayList<View>(4)
        (layout as? View)?.let { holders.add(it) }
        for (getter in ROW_CHILD_GETTERS) {
            runCatching { Reflect.callDeep(layout, getter) as? View }.getOrNull()?.let { holders.add(it) }
        }
        var applied = false
        val stamp = stampOf(context)
        for (holder in holders) {
            val iconView = runCatching {
                holder.findViewById<View>(android.R.id.icon) as? ImageView
            }.getOrNull() ?: continue
            if (!isCachingIcon(iconView)) continue
            // 每个 View 一份独立 Drawable 实例（位图仍共享，零额外内存）：宿主会给
            // 各视图的 drawable 分别打颜色滤镜，共享一个实例会互相污染。
            runCatching {
                iconView.clearColorFilter()
                iconView.setImageDrawable(ThemeIconDrawable(context.resources, bitmap, stamp))
                applied = true
            }
        }
        return applied
    }

    /**
     * 诊断：把窗口内所有「图标视图」的名字 / id / 图类型打出来（受 [DIAG_TOTAL_LIMIT] 约束）。
     *
     * 锁屏卡片到底复用通知行、还是另有一棵视图树，靠这段日志一次就能分辨：
     * 若窗口里只出现一个 `CachingIconView` 且图是我们放的 `BitmapDrawable`，
     * 说明问题在「之后又被覆盖」；若出现第二棵树且图是别的类型，说明有独立取图路径。
     *
     * 受 [DIAG_TOTAL_LIMIT] 总量约束（与其它通道共用一个额度），避免锁屏一次切换
     * 就刷掉大半条额度。
     */
    private fun dumpIconViews(row: Any?) {
        // 额度用尽后**连遍历都不做**：这段是纯诊断，而锁屏切换要遍历整棵视图树
        // 且每个命中节点都要 `getResourceEntryName`（走 Resources 查表）。
        if (!diagAvailable()) return
        val rowView = row as? View ?: return
        val root = runCatching { rowView.rootView }.getOrNull() ?: return
        val sb = StringBuilder()
        fun walk(v: View, depth: Int) {
            if (depth > 26 || sb.length > 1400) return
            val name = v.javaClass.name
            if (name.endsWith("CachingIconView") || name.endsWith("RowIconView") ||
                name.endsWith("IconImageView")
            ) {
                val drawable = (v as? ImageView)?.drawable
                val bitmapW = (drawable as? BitmapDrawable)?.bitmap?.width
                sb.append("\n  ").append(v.javaClass.simpleName)
                    .append(" id=").append(idName(v))
                    .append(" drawable=").append(drawable?.javaClass?.simpleName ?: "null")
                    .append("/").append(bitmapW ?: drawable?.intrinsicWidth ?: -1)
                    .append(" view=").append(v.width).append('x').append(v.height)
                    .append(" 父=").append(parentChain(v))
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
        }
        runCatching { walk(root, 0) }
        diag("kg", "dump") { "窗口内图标视图清单$sb" }
    }

    /** 祖先链（最近 3 层），用来判断这个图标控件挂在哪棵视图树上 */
    private fun parentChain(view: View): String {
        val sb = StringBuilder()
        var p = view.parent
        var depth = 0
        while (p is View && depth < 3) {
            if (depth > 0) sb.append('<')
            sb.append(p.javaClass.simpleName)
            p = p.parent
            depth++
        }
        return sb.toString()
    }

    private fun idName(view: View): String = runCatching {
        if (view.id == View.NO_ID) "no_id"
        else view.resources.getResourceEntryName(view.id)
    }.getOrDefault(view.id.toString())

    /** 是否是 `com.android.internal.widget.CachingIconView` 及其子类（通知行图标控件的基类） */
    private fun isCachingIcon(view: View): Boolean {
        var cls: Class<*>? = view.javaClass
        while (cls != null) {
            if (cls.name == "com.android.internal.widget.CachingIconView") return true
            cls = cls.superclass
        }
        return false
    }

    // ---------------------------------------------- 钩子 6：锁屏胶囊

    /**
     * `CapsuleNotificationCardView#bind(CapsuleNotificationInnerData): void`。
     *
     * 胶囊卡的取图在 `CapsuleNotificationDataController#getIconData` 里分了两条：
     * 系统应用取 `notification.smallIcon`，**非系统应用取 `NotificationResolverEx#getPackageIcon`**
     * （APK 里的原始启动图标）。后者改 `smallIcon` 影响不到，所以在卡片绑定完成后
     * 直接给图标控件换图。
     */
    private fun hookCapsuleCard(): String {
        val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_CAPSULE_CARD)
        if (cls == null) {
            LogUtils.w(TAG, "install", "$CLS_CAPSULE_CARD 不存在，锁屏胶囊通道跳过")
            return ""
        }
        val method = Reflect.findMethodByShape(
            cls = cls,
            name = "bind",
            expect = listOf<Class<*>?>(null),
            returnType = java.lang.Void.TYPE,
        )
        if (method == null) {
            LogUtils.w(TAG, "install", "CapsuleNotificationCardView#bind 未命中，锁屏胶囊通道跳过")
            return ""
        }
        runCatching {
            HookEnv.module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    if (enabled()) {
                        runCatching { applyCapsuleIcon(chain.thisObject, chain.getArg(0)) }
                            .onFailure {
                                LogUtils.w(TAG, "capsule", "胶囊换图失败：${it.javaClass.simpleName}: ${it.message}")
                            }
                    }
                    return result
                }
            })
            installNote("已挂 $CLS_CAPSULE_CARD#bind（锁屏胶囊通道）")
        }.onFailure {
            LogUtils.w(TAG, "install", "挂 CapsuleNotificationCardView#bind 失败：${it.javaClass.simpleName}: ${it.message}")
            return ""
        }
        return "胶囊"
    }

    private fun applyCapsuleIcon(card: Any?, inner: Any?) {
        if (card == null || inner == null) return
        val iconData = Reflect.callDeep(inner, "getIconData") ?: return
        val pkg = Reflect.callDeep(iconData, "getPackageName") as? String ?: return
        val iconView = Reflect.callDeep(card, "getIcon") as? ImageView ?: return
        val context = runCatching { iconView.context }.getOrNull() ?: return
        val bitmap = DesktopThemeIconProvider.resolve(
            context = context,
            pkg = pkg,
            userId = 0,
            sizePx = headerIconPx(context),
        ) ?: return
        diag("capsule", pkg) { "$pkg -> ${bitmap.width}px 命中" }
        runCatching {
            iconView.clearColorFilter()
            iconView.setImageDrawable(ThemeIconDrawable(context.resources, bitmap, stampOf(context)))
        }
    }

    // ------------------------------------------------------------------ 钩子 1

    /**
     * 本模块产出的图标 Drawable。

     * 单独建一个子类只为两件事：

     * 1. **识别「这个图是我们写的」**。`StatusBarIconView#updateDrawable` 会把 `getIcon()`
     *    的返回值写进 `StatusBarIcon#preloadedIcon`（`if (preloadedIcon == null) preloadedIcon = icon`），
     *    之后 `getIcon` 就走 `preloadedIcon != null` 分支、不再重新取图。
     *    开关关闭时必须能把这份残留清掉，否则图标会一直停在主题图标上（要重启 SystemUI 才好）。
     *    用 `is BitmapDrawable` 判定不行 —— 原生 preloadedIcon 本身通常就是 BitmapDrawable。
     * 2. 通知行 / 锁屏卡片的图标也用它，作为 [applyHeaderIcon] 的短路判据：
     *    图已经铺对了就不必再解析 + 重设。
     *
     * [themeStamp] 是它所属主题配置的印章。判据必须同时比对印章：光「是我们铺的」不足以
     * 说明它还是**当前**主题的图（换主题后旧图就过期了），所以印章不一致时一律重新解析。
     */
    private class ThemeIconDrawable(
        resources: Resources,
        bitmap: Bitmap,
        val themeStamp: Long,
    ) : BitmapDrawable(resources, bitmap)

    /**
     * `StatusBarIconView#getIcon(StatusBarIcon): Drawable` —— public final。
     *
     * 参数类型 `com.android.internal.statusbar.StatusBarIcon` 是隐藏类，模块里无法直接引用，
     * 所以用 `expect = listOf(null)`（任意单参数）+ 返回 `Drawable` 定位。
     */
    private fun hookStatusBarIconView(): String {
        val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_STATUS_BAR_ICON_VIEW)
        if (cls == null) {
            LogUtils.w(TAG, "install", "$CLS_STATUS_BAR_ICON_VIEW 不存在，跳过图标替换通道")
            return ""
        }
        val method = Reflect.findMethodByShape(
            cls = cls,
            name = "getIcon",
            expect = listOf<Class<*>?>(null),
            returnType = Drawable::class.java,
        )
        if (method == null) {
            LogUtils.w(TAG, "install", "getIcon(StatusBarIcon) 未命中，跳过图标替换通道")
            return ""
        }
        runCatching {
            HookEnv.module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val statusBarIcon = chain.getArg(0)
                    // 开关关闭时，若这个 StatusBarIcon 的 preloadedIcon 还留着上次我们写进去的
                    // 主题图标，必须先清空 —— 否则原方法走 `preloadedIcon != null` 分支，
                    // 关掉开关后图标仍旧是主题图标（重启 SystemUI 才会好）。
                    if (!enabled()) {
                        clearOwnPreload(statusBarIcon)
                        return chain.proceed()
                    }
                    val view = chain.thisObject as? View
                    val context = view?.let { runCatching { it.context }.getOrNull() }
                    if (view != null && context != null) {
                        val size = iconSizePx(context, view)
                        // ---- 短路：preload 已经是「本模块铺的、属于当前主题、且尺寸也对」的图 ----
                        // 此时原方法走 `preloadedIcon != null` 分支，返回同一张图的一份副本
                        // （`constantState.newDrawable().mutate()` 只新建 Drawable，位图共享），
                        // 直接交给它即可，省掉一次 resolve。
                        //
                        // ⚠️ 必须连**尺寸**一起比对：宿主只在 `preloadedIcon == null` 时才回填
                        // （`updateDrawable`：`if (preloadedIcon == null) preloadedIcon = icon`），
                        // 一旦首帧（View 还没布局、`width == 0`）把 20dp 那张写进去，此后
                        // 尺寸再也不会更新 —— 图标会一直偏小。加上这一半判据即可自愈。
                        val pre = Reflect.getField(statusBarIcon, "preloadedIcon")
                        if (pre is ThemeIconDrawable && pre.themeStamp == DesktopThemeIconProvider.stamp(context) &&
                            pre.bitmap?.width == size
                        ) {
                            return chain.proceed()
                        }
                        // ---- 主路径：先算我们的图，拿到了就**不调原方法** ----
                        // 这是本钩子最值钱的一处优化。原方法每次都会把 `statusBarIcon.icon`
                        // 解码成 Drawable，再 `getValue(status_bar_icon_scale_factor)` +
                        // `getDimensionPixelSize()` + `DrawableSize#downscaleToSize()`
                        // ——后者会**新分配一块目标尺寸的位图并整块 draw 一次**。而钩子返回值
                        // 就是最终结果（原方法尾部那段缩放作用在它自己的返回值上，不会再来缩一次），
                        // 所以那次解码 + 缩放完全被我们的产物取代：状态栏刷新有多频繁就白做多少次。
                        // 取不到主题图标时才回退 `chain.proceed()`，行为与原先一致。
                        val sbn = Reflect.getField(view, "mNotification") as? StatusBarNotification
                        val pkg = sbn?.packageName
                        if (pkg != null) {
                            val bitmap = DesktopThemeIconProvider.resolve(
                                context = context,
                                pkg = pkg,
                                userId = normalizeUserId(sbn.userId),
                                sizePx = size,
                            )
                            diag("getIcon", pkg) { "$pkg -> ${if (bitmap != null) "${bitmap.width}px 命中" else "未取到主题图标"}" }
                            if (bitmap != null) {
                                if (firstHitLogged.compareAndSet(false, true)) {
                                    note("getIcon", "已用桌面主题图标替换状态栏图标（首个命中：$pkg）")
                                }
                                // 顺手把源字段也换掉：存量通知不会再走 onNotificationPosted，
                                // 面板头部行 / 锁屏卡片读的就是 notification.smallIcon
                                runCatching { patchSmallIcon(sbn, bitmap) }
                                val drawable = ThemeIconDrawable(context.resources, bitmap, stampOf(context))
                                // ⚠️ 必须把新图写回 `preloadedIcon`，理由见上面那条短路的注释：
                                // 不写回的话换主题 / 尺寸变化后 preload 会一直停在旧的那张上。
                                runCatching { Reflect.setField(statusBarIcon, "preloadedIcon", drawable) }
                                return drawable
                            }
                        }
                    }
                    return chain.proceed()
                }
            })
            installNote("已挂 $CLS_STATUS_BAR_ICON_VIEW#getIcon")
        }.onFailure {
            LogUtils.w(TAG, "install", "挂 getIcon 失败：${it.javaClass.simpleName}: ${it.message}")
            return ""
        }
        return "状态栏"
    }

    // ------------------------------------------------------------------ 钩子 2

    /**
     * `StatusBarIconControllerExImpl#updateStatusBarIconGrayScale(Drawable, StatusBarIconView, StatusBarNotification): void`
     *
     * 参数 2 是具体类 `StatusBarIconView`（模块可见），但为容忍 OTA 改继承关系，
     * 这里用 `null` 通配该位，只约束首尾。
     */
    private fun hookGrayScale(): String {
        val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_ICON_CONTROLLER_IMPL)
        if (cls == null) {
            LogUtils.w(TAG, "install", "$CLS_ICON_CONTROLLER_IMPL 不存在，跳过着色修正通道")
            return ""
        }
        val method = Reflect.findMethodByShape(
            cls = cls,
            name = "updateStatusBarIconGrayScale",
            expect = listOf<Class<*>?>(
                Drawable::class.java,
                null,
                StatusBarNotification::class.java,
            ),
            returnType = java.lang.Void.TYPE,
        )
        if (method == null) {
            LogUtils.w(TAG, "install", "updateStatusBarIconGrayScale 未命中，跳过着色修正通道")
            return ""
        }
        runCatching {
            HookEnv.module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    if (!enabled()) return result
                    val view = chain.getArg(1) as? View ?: return result
                    val sbn = chain.getArg(2) as? StatusBarNotification ?: return result
                    val pkg = sbn.packageName ?: return result
                    // 只有「这条通知确实换成了主题图标」才强制全彩，别影响没换的通知
                    if (!DesktopThemeIconProvider.isResolved(pkg, normalizeUserId(sbn.userId))) {
                        return result
                    }
                    forceFullColor(view)
                    return result
                }
            })
            LogUtils.i(TAG, "install", "已挂 $CLS_ICON_CONTROLLER_IMPL#updateStatusBarIconGrayScale")
        }.onFailure {
            LogUtils.w(TAG, "install", "挂 updateStatusBarIconGrayScale 失败：${it.javaClass.simpleName}: ${it.message}")
            return ""
        }
        return "着色修正"
    }

    /** 标记为「不可着色」：清颜色滤镜，并同步 `icon_is_grayscale` 标签（面板侧会读它） */
    private fun forceFullColor(view: View) {
        runCatching { Reflect.callDeep(view, "setIsIconColorable", false) }
        val id = grayscaleTagId(view.context)
        if (id != 0) runCatching { view.setTag(id, false) }
    }

    /** 清掉本模块上次写进 `StatusBarIcon#preloadedIcon` 的主题图标（开关关闭时用） */
    private fun clearOwnPreload(statusBarIcon: Any?) {
        if (statusBarIcon == null) return
        if (Reflect.getField(statusBarIcon, "preloadedIcon") !is ThemeIconDrawable) return
        Reflect.setField(statusBarIcon, "preloadedIcon", null)
        if (disableCleared.compareAndSet(false, true)) {
            LogUtils.i(TAG, "getIcon", "开关已关闭，已清除残留的主题图标 preload（图标恢复原生）")
        }
    }

    /**
     * 当前主题配置的印章（取自 [DesktopThemeIconProvider.stamp]，带 300ms 快照复用）。
     *
     * 铺图时写进 [ThemeIconDrawable]，供 [applyHeaderIcon] 判断手上那张图是否已过期。
     */
    private fun stampOf(context: Context): Long = DesktopThemeIconProvider.stamp(context)

    // ------------------------------------------------------------------ 诊断出口

    /**
     * 诊断去重键（`通道|包名`）。

     * 只有总量上限是不够的：2026-11-28 真机日志里，一条常驻通知（com.fr.husi）
     * 在 4 秒内刷了 25 次命中，把 [DIAG_TOTAL_LIMIT] 的 30 条额度吃光，
     * 之后真出问题时**一条诊断都没有**。去重后同样的额度能覆盖 30 个不同场景。
     */
    private val diagSeen: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * 受 [DIAG_TOTAL_LIMIT] 总量上限保护的诊断输出。

     * **级别是 INFO，不是 ERROR**：这里打的是「命中 / 未命中」这类正常路径信息，不是故障。
     * 之前用 `LogUtils.e`，结果 LSPosed 日志页满屏红色 E —— 2026-11-28 的日志里
     * 52 条 E 全是本模块的命中诊断，**没有一条是异常**，把真正的错误淹没了。
     * 「模块有没有注入 / 钩子有没有装上」的穿透锚点由 [note] 承担，那才是 ERROR。
     *
     * [key] 用于去重：同一通道的同一个包只记第一次（见 [diagSeen]）。
     *
     * [msg] 用 **内联 lambda** 而不是 `String`：原来消息在调用点的实参求值时就拼好了，
     * 额度用尽后每次 `onNotificationPosted` / 每次取图仍照拼一遍（StringBuilder + toString）。
     * 内联后 lambda 被展开进函数体，额度不够或已去重时**连字符串都不构造**。
     */
    private inline fun diag(method: String, key: String, msg: () -> String) {
        // 先判再自增：额度用尽后这条路径要彻底静默，别再碰原子变量
        if (diagSent.get() > DIAG_TOTAL_LIMIT) return
        // 去重放在额度自增之前：重复命中不该消耗额度
        if (!diagSeen.add("$method|$key")) return
        val n = diagSent.incrementAndGet()
        if (n > DIAG_TOTAL_LIMIT) return
        LogUtils.i(TAG, method, "[$n] ${msg()}")
    }

    /** 诊断额度是否还有余量（供「先做重活再打日志」的调用点提前退出） */
    private fun diagAvailable(): Boolean = diagSent.get() <= DIAG_TOTAL_LIMIT

    /**
     * 结论性日志（**不计入**诊断上限，WARN 级 + 强制输出）。

     * 只用于「本功能已经装上 / 已经命中」这类单次事件：量是常数级的，
     * 且正是排查「模块有没有注入」时要看到的那几条，所以不受总量约束。
     *
     * **级别是 WARN 而不是 ERROR**：这些是正常路径的里程碑（装上了 / 首个命中），
     * 不是故障。用 ERROR 会让 LSPosed 日志页把正常状态显示成红色错误，
     * 真故障反而被淹。走 [LogUtils.anchor] 保证「详细日志」关着也可见。
     */
    private fun note(method: String, msg: String) {
        LogUtils.anchor(TAG, method, msg)
    }

    /**
     * 通道安装明细（**INFO**）。

     * 一条通道装没装上，从 [note] 那条「安装完成」汇总里看不出来；但它也不该是 ERROR ——
     * 装上 8 条通道不该在日志页刷出 8 条红色错误。详细日志开着时看明细，关着时看汇总。
     */
    private fun installNote(msg: String) = LogUtils.i(TAG, "install", msg)

    // ------------------------------------------------------------------ 取值

    private fun enabled(): Boolean {
        val now = SystemClock.uptimeMillis()
        // enabledAt != 0 才认缓存：设备刚开机 300ms 内 uptimeMillis 本身很小，
        // 不判就会把「还没读过」误当成「缓存有效」而返回默认 false。
        if (enabledAt != 0L && now - enabledAt < ENABLED_TTL_MS) return enabledCache
        val value = HookEnv.settingBoolean(DesktopThemeIconConfig.KEY_ENABLED, false)
        enabledCache = value
        enabledAt = now
        return value
    }

    /**
     * 目标像素尺寸：优先用图标 View 的实际宽高。
     *
     * `StatusBarIconView` 的 scaleType 是 `CENTER`（构造器里写死，只有 `FIXED_SPACE` 形状
     * 才临时换 `FIT_CENTER`）—— **不缩放**，所以位图比 View 大就会被裁、比 View 小就显小。
     * 因此尺寸必须跟 View 一致。
     *
     * 首帧（`getIcon` 早于布局）`width` 还是 0，回退宿主自己的 `status_bar_icon_size` dimen
     * （SystemUI 17.99.02 = 20dp），再兜底 `app_icon_size`，最后写死 72px。
     *
     * ⚠️ 与宿主自带的 `DrawableSize#downscaleToSize` **不叠加**：钩子返回值就是最终结果，
     * 原方法尾部那段缩放作用在它自己的返回值上，不会再来缩一次。所以这里必须
     * 直接给到 View 尺寸 —— 给大了会被 `CENTER` 的 scaleType 裁掉。
     */
    private fun iconSizePx(context: Context, view: View): Int {
        val viewSize = maxOf(view.width, view.height)
        if (viewSize > 0) return viewSize.coerceIn(48, 192)
        val cached = iconSizeCache
        if (cached > 0) return cached
        val resources = context.resources
        var px = runCatching {
            val id = resources.getIdentifier("status_bar_icon_size", "dimen", "com.android.systemui")
            if (id != 0) resources.getDimensionPixelSize(id) else 0
        }.getOrDefault(0)
        if (px <= 0) {
            px = runCatching { resources.getDimensionPixelSize(android.R.dimen.app_icon_size) }.getOrDefault(0)
        }
        if (px <= 0) px = 72
        val value = px.coerceIn(48, 192)
        iconSizeCache = value
        return value
    }

    /** `R.id.icon_is_grayscale`（`com.android.systemui` 的 id），进程内只查一次 */
    private fun grayscaleTagId(context: Context): Int {
        val cached = grayTagId
        if (cached >= 0) return cached
        val id = runCatching {
            context.resources.getIdentifier("icon_is_grayscale", "id", "com.android.systemui")
        }.getOrDefault(0)
        grayTagId = id
        return id
    }

    /** `StatusBarNotification#getUserId()` 在无显式用户时返回 USER_NULL(-1)，按主用户处理 */
    private fun normalizeUserId(raw: Int): Int = if (raw >= 0) raw else 0
}

/**
 * 「通知图标使用桌面主题图标」开关（App 设置页写入、hook 侧读取，两边同一份 remote prefs）。
 *
 * 落在「系统界面」功能页（宿主 `com.android.systemui`），与锁屏通知下移同页。
 */
internal object DesktopThemeIconConfig {
    const val KEY_ENABLED = "desktop_theme_icon_enabled"
}
