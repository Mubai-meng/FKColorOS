package com.fr.colorostools.hook.hookers

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageItemInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.os.UserHandle
import android.util.LruCache
import com.fr.colorostools.hook.HookEnv
import com.fr.colorostools.ref.Reflect
import com.fr.colorostools.utils.LogUtils
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 「桌面当前主题图标」解析器（宿主 `com.android.systemui`）。
 *
 * ## 拿到的到底是什么
 *
 * ColorOS 桌面上的图标并不是 APK 里的 `ic_launcher`，而是**主题包**渲染出来的：
 * 内核入口是 framework 里的隐藏类 `android.app.UxIconPackageManagerExt`，它的
 * `loadItemIcon(PackageItemInfo, ApplicationInfo, boolean): Drawable` 会依次尝试
 *
 * 1. 花式图标（`FancyIconLoaderHelper`）；
 * 2. 用户自定义图标（`/data/oplus/uxicons/choose/<pkg>.png`，联系人特判 `dialer_<pkg>.png`）；
 * 3. 主题图标包 / 当前主题样式（`OplusUXIconLoadHelper` → `OplusUXIconLoaderImpl#loadUxIcon`）；
 * 4. 全都不命中才回退 APK 原始 `ic_launcher`。
 *
 * 也就是说**调一次 `loadItemIcon` 就同时覆盖了「主题图标」「自定义图标」两种情况**，
 * 这正是「图标来源跟随桌面当前主题图标」要的语义，无需自己再判断主题模式
 * （矩形 / 圆形 / 暗色补偿都由 ColorOS 内部处理）。
 *
 * ## 为什么必须传 `ActivityInfo` 而不是 `ApplicationInfo`
 *
 * 桌面是按 **activity 维度**套主题图标的（同一个包的不同入口可以有不同图标）。
 * `loadItemIcon` 第一个参数是 `PackageItemInfo`，传 `ApplicationInfo` 只会拿到
 * 应用级默认图标 ⇒ 与桌面显示不一致。所以优先用 `LauncherApps#getActivityList`
 * 拿到真实的启动 `ActivityInfo`，取不到才回退 `ApplicationInfo`。
 *
 * ## 缓存
 *
 * 状态栏图标刷新是高频路径（每条通知每次更新都会问），而 `loadItemIcon` 是
 * 「反射 + 主题包查表 + 位图化」的重活。这里用两层：
 *
 * - **配置快照**（[ThemeSnapshot]）300ms 内复用 —— 换主题 / 切深浅色后最多 300ms 生效；
 * - **位图 LRU**（4MiB）键 = 包名 + 用户 + 目标尺寸 + 配置快照 —— 配置一变键就变，
 *   天然失效，**不需要 TTL 也不会拿到旧主题的图**。
 */
internal object DesktopThemeIconProvider {

    private const val TAG = "DesktopThemeIcon"

    /** framework 里的隐藏入口类（ColorOS 专属） */
    private const val CLS_UX_ICON_PM_EXT = "android.app.UxIconPackageManagerExt"

    /** 透明判定阈值：alpha 不超过它算「空白」，用于裁掉图标四周的自适应边距 */
    private const val ALPHA_THRESHOLD = 8

    /** 位图缓存上限（KiB） */
    private const val CACHE_KIB = 4 * 1024

    /** 配置快照复用窗口（ms） */
    private const val SNAPSHOT_TTL_MS = 300L

    /**
     * 「解析失败」缓存的存活时间（ms）。
     *
     * 不能永久缓存：宿主刚起动那几秒 `LauncherApps` / `PackageManager` 可能还没就绪，
     * 那时的失败是**暂时的**。永久记住会让整个运行期都不再重试（v1.5.14 实测踩中）。
     */
    private const val MISS_TTL_MS = 8_000L

    /** 渲染尺寸夹取范围（过小看不清、过大白占内存） */
    private const val SIZE_MIN = 48
    private const val SIZE_MAX = 192

    /**
     * `(PackageItemInfo, ApplicationInfo)` 缓存的存活时间（ms）。
     *
     * 这两者与「主题」无关（只跟包本身有关），但 [resolve] 的位图缓存键含主题印章 ⇒
     * 一换主题/图标包/深浅色，**所有包**的位图键全变、全部 miss。若不单独缓存这一层，
     * 换一次主题就是「每个通知包各两次跨进程 binder」（`getApplicationInfo` +
     * `LauncherApps#getActivityList`）的续航尖峰。带 TTL 是为了让包升级/卸载后能自愈。
     */
    private const val ITEM_INFO_TTL_MS = 60_000L

    /**
     * 裁掉空白后内容占画布的比例。
     *
     * 取 0.9 而不是 1.0：状态栏图标旁边还有信号/WiFi 等系统图标，桌面图标若顶满
     * view 边缘会显得「更大一号」。留 5% 单边边距视觉上刚好齐平。
     */
    private const val CONTENT_RATIO = 0.9f

    private val PAINT = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    // ------------------------------------------------------------------ 缓存

    private data class CacheKey(
        val pkg: String,
        val userId: Int,
        val sizePx: Int,
        val uiMode: Int,
        val themeChanged: Long,
        val themeChangedFlags: Long,
        val uxIconConfig: Long,
    )

    private val bitmapCache = object : LruCache<CacheKey, Bitmap>(CACHE_KIB) {
        override fun sizeOf(key: CacheKey, value: Bitmap): Int =
            ((value.allocationByteCount + 1023) / 1024).coerceAtLeast(1)
    }

    /** 已成功解析出主题图标的 `pkg|userId`，供着色修正快速判定（不做反射） */
    private val resolvedKeys = object : LruCache<String, Boolean>(512) {}

    /**
     * 「解析失败」缓存：值 = 失败时刻（`uptimeMillis`），超过 [MISS_TTL_MS] 自动重试。
     *
     * 缓存是必要的 —— `getIcon` 是每条通知每次刷新都会走的热路径，若不缓存 miss，
     * 任何一个拿不到主题图标的包都会每次重跑「反射 + 主题包查表 + 位图化」一整套。
     * 但**必须带 TTL**：宿主冷启动头几秒服务没就绪时的失败是暂时的，
     * 永久缓存会让功能整个运行期都不生效。
     */
    private val missCache = object : LruCache<CacheKey, Long>(256) {}

    /** `pkg|userId` → `(PackageItemInfo, ApplicationInfo)`，见 [ITEM_INFO_TTL_MS] */
    private val itemInfoCache = object : LruCache<String, ItemInfoEntry>(128) {}

    private class ItemInfoEntry(
        val at: Long,
        val pair: Pair<PackageItemInfo, ApplicationInfo>,
    )

    /**
     * `LauncherApps` 服务句柄。
     *
     * `Context#getSystemService` 每次都要过 `SystemServiceRegistry` 的 map（还要做类型
     * 转换与缓存查表）；取图是热路径，句柄缓存住即可。**失败时不缓存** —— 宿主刚起动时
     * 服务可能还没注册，缓存 null 会让整个运行期都取不到（[missCache] 的 8s 重试也就白做了）。
     */
    @Volatile
    private var launcherAppsCache: LauncherApps? = null

    // ------------------------------------------------------------------ 对外

    /**
     * 解析 [pkg] 的桌面主题图标位图。
     *
     * 返回 null 表示「拿不到主题图标」——调用方应保留宿主原始图标，**不要**用别的图顶替。
     */
    fun resolve(context: Context, pkg: String, userId: Int, sizePx: Int): Bitmap? {
        if (pkg.isEmpty()) return null
        val size = sizePx.coerceIn(SIZE_MIN, SIZE_MAX)
        val snapshot = snapshot(context)
        val key = CacheKey(
            pkg = pkg,
            userId = userId,
            sizePx = size,
            uiMode = snapshot.uiMode,
            themeChanged = snapshot.themeChanged,
            themeChangedFlags = snapshot.themeChangedFlags,
            uxIconConfig = snapshot.uxIconConfig,
        )
        bitmapCache.get(key)?.let { return it }
        val missAt = missCache.get(key)
        if (missAt != null && SystemClock.uptimeMillis() - missAt < MISS_TTL_MS) return null
        val bitmap = loadBitmap(context, pkg, userId, size) ?: run {
            missCache.put(key, SystemClock.uptimeMillis())
            return null
        }
        bitmapCache.put(key, bitmap)
        resolvedKeys.put(resolvedKey(pkg, userId), true)
        return bitmap
    }

    /** 该包在该用户下是否有可用的主题图标（只看解析记录，零反射，热路径友好） */
    fun isResolved(pkg: String, userId: Int): Boolean =
        resolvedKeys.get(resolvedKey(pkg, userId)) == true

    /**
     * 当前主题 / 图标包 / 深浅色配置的印章。
     *
     * [DesktopThemeIconHook] 铺图时把它写进自己的 Drawable，之后判断「View 上这张图
     * 是否还是当前主题的图」就靠比对印章：只有「确实是本模块铺的」**且**「印章未变」
     * 才能跳过重新解析。少了印章这一半，换主题后已存在的通知会因为「图是我们铺的」
     * 而被短路挡掉，一直停在旧主题的图标上。
     *
     * 取值复用 [snapshot]（300ms 内读缓存），所以这个调用在热路径上也很便宜。
     */
    fun stamp(context: Context): Long {
        val s = snapshot(context)
        var h = 1125899906842597L
        h = h * 31 + s.uiMode.toLong()
        h = h * 31 + s.themeChanged
        h = h * 31 + s.themeChangedFlags
        h = h * 31 + s.uxIconConfig
        return h
    }

    fun clearCache() {
        bitmapCache.evictAll()
        missCache.evictAll()
        resolvedKeys.evictAll()
        itemInfoCache.evictAll()
        snapshotCache = null
        snapshotAt = 0L
    }

    private fun resolvedKey(pkg: String, userId: Int): String = "$pkg|$userId"

    // ------------------------------------------------------------------ 取图

    private fun loadBitmap(context: Context, pkg: String, userId: Int, sizePx: Int): Bitmap? {
        val api = uxApi() ?: return null
        val resolved = resolveItemInfo(context, pkg, userId) ?: return null
        val drawable = invokeLoad(api, context, resolved.first, resolved.second) ?: return null
        return runCatching { drawable.toIconBitmap(sizePx) }.getOrElse {
            LogUtils.w(TAG, "resolve", "位图化失败（$pkg）：${it.javaClass.simpleName}: ${it.message}")
            null
        }
    }

    /**
     * 取 `(PackageItemInfo, ApplicationInfo)` 二元组。
     *
     * 优先 `LauncherApps#getActivityList` 拿启动 activity（桌面按 activity 套图标），
     * 拿不到再回退 `ApplicationInfo`。`userId` 为 -1（USER_NULL）时按 0 处理。
     *
     * ## ⚠️ 必须补齐 `icon`（v1.5.15 修，这是「功能完全不生效」的根因）
     *
     * `PackageManager#getActivityInfo` 返回的 `ActivityInfo.icon`，对**绝大多数应用是 0** ——
     * 图标写在 `<application android:icon>` 上，activity 自己不声明。而
     * `OplusUXIconLoaderImpl#loadUxIcon` 有这么一句：
     *
     * ```java
     * if (resId == 0 && !isThemeIconPack()) {
     *     IconDebugUtils.getIconEvent().handled("LOAD_UX_ICON_FAILED: resId is 0");
     *     return null;   // ← 直接放弃，连原始图标都不回退
     * }
     * ```
     *
     * 且 `UxIconPackageManagerExt#loadItemIcon` 对 `loadItemIconWithoutEdit` 的返回值
     * **不做 null 兜底**，一路 null 到我们手上 ⇒ 有启动 activity 的应用（微信、QQ 等，
     * 恰好是用户真正会看通知的那些）全部失败，而没有启动 activity 的包（如 SystemUI 自身）
     * 走 `ApplicationInfo` 反而成功 —— 这正是「只有 com.android.systemui 命中」的原因。
     *
     * 所以这里把 `icon` 从 `ApplicationInfo` 补到 `ActivityInfo` 上：
     * `activityName` 保留（桌面按 activity 维度套图），`icon` 不再是 0。
     */
    private fun resolveItemInfo(
        context: Context,
        pkg: String,
        userId: Int,
    ): Pair<PackageItemInfo, ApplicationInfo>? {
        val cacheKey = "$pkg|$userId"
        val now = SystemClock.uptimeMillis()
        itemInfoCache.get(cacheKey)?.let { entry ->
            if (now - entry.at < ITEM_INFO_TTL_MS) return entry.pair
        }
        val pm = context.packageManager
        val appInfo = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return null
        val launcherActivity = launcherActivityOf(context, pm, pkg, userId)
        val item: PackageItemInfo = launcherActivity ?: appInfo
        // 见上方注释：ActivityInfo 的 icon 默认为 0，不补就整条链路返回 null
        if (item.icon == 0) item.icon = appInfo.icon
        val pair = item to appInfo
        itemInfoCache.put(cacheKey, ItemInfoEntry(now, pair))
        return pair
    }

    /**
     * 取启动 `ActivityInfo`。
     *
     * 优先 `LauncherActivityInfo#getActivityInfo()` —— 它由 `LauncherApps` 生成，
     * `icon` 已被填充为有效值；`pm.getActivityInfo` 是次选（icon 常为 0，靠上层补齐）。
     */
    private fun launcherActivityOf(
        context: Context,
        pm: PackageManager,
        pkg: String,
        userId: Int,
    ): ActivityInfo? = runCatching {
        val launcherApps = launcherAppsOf(context) ?: return@runCatching null
        val activity = launcherApps.getActivityList(pkg, userHandleOf(userId)).firstOrNull()
            ?: return@runCatching null
        runCatching { activity.getActivityInfo() }.getOrNull()
            ?: runCatching { pm.getActivityInfo(activity.componentName, 0) }.getOrNull()
    }.getOrNull()

    private fun launcherAppsOf(context: Context): LauncherApps? {
        launcherAppsCache?.let { return it }
        val value = runCatching { context.getSystemService(LauncherApps::class.java) }.getOrNull()
            ?: return null
        launcherAppsCache = value
        return value
    }

    // ------------------------------------------------------------------ 自检

    /**
     * 启动自检：把一条包名走完整链路，逐步打 INFO 级日志。
     *
     * 只在安装完成后由 [DesktopThemeIconHook] 调一次。**为什么需要它**：钩子装没装上、
     * 开关读没读到，都能从日志看出来；但「取图为什么失败」在运行期无法区分是
     * `getActivityList` 空、`icon == 0`、`loadUxIcon` 返回 null 还是位图化失败。
     * 逐段打点后，一次重启就能定位，不必反复让用户抓日志。
     */
    fun diagnose(context: Context, pkg: String) {
        val api = uxApi()
        LogUtils.i(TAG, "diagnose", "[$pkg] ① 反射句柄 api=${api != null} loadItemIcon=${api?.loadItemIcon != null}")
        if (api == null) return
        val pm = context.packageManager
        val appInfo = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
        LogUtils.i(TAG, "diagnose", "[$pkg] ② appInfo=${appInfo != null} appIcon=${appInfo?.icon}")
        if (appInfo == null) return
        val launcherCount = runCatching {
            context.getSystemService(LauncherApps::class.java)
                ?.getActivityList(pkg, userHandleOf(0))?.size ?: -1
        }.getOrElse { -it.javaClass.simpleName.hashCode() }
        val pair = resolveItemInfo(context, pkg, 0)
        if (pair == null) {
            LogUtils.i(TAG, "diagnose", "[$pkg] ③ resolveItemInfo=null（包不可见？）")
            return
        }
        val item = pair.first
        LogUtils.i(
            TAG, "diagnose",
            "[$pkg] ③ launcherActivities=$launcherCount item=${item.javaClass.simpleName} " +
                "itemIcon=${item.icon} activityName=${item.name}",
        )
        val drawable = runCatching { invokeLoad(api, context, item, pair.second) }.getOrNull()
        LogUtils.i(
            TAG, "diagnose",
            "[$pkg] ④ loadItemIcon -> ${drawable?.javaClass?.simpleName} " +
                "intrinsic=${drawable?.intrinsicWidth}x${drawable?.intrinsicHeight}",
        )
        val bitmap = resolve(context, pkg, 0, 144)
        LogUtils.i(TAG, "diagnose", "[$pkg] ⑤ resolve -> ${bitmap?.width}x${bitmap?.height}")
    }

    /**
     * `UserHandle#of(int)`：public API，但本项目用的 hidden-api-stub 里没有它，
     * 且它本身在部分 ROM 上也被标记为 hidden ⇒ 反射取，取不到退回当前进程用户。
     */
    private val userHandleOfMethod: Method? by lazy {
        runCatching {
            Reflect.classOrNull(HookEnv.classLoader, "android.os.UserHandle")
                ?.getMethod("of", Int::class.javaPrimitiveType!!)
        }.getOrNull()
    }

    private fun userHandleOf(userId: Int): UserHandle =
        runCatching { (userHandleOfMethod?.invoke(null, userId) as? UserHandle) }.getOrNull()
            ?: android.os.Process.myUserHandle()

    /**
     * `UxIconPackageManagerExt` 实例缓存。
     *
     * 该对象构造体只做两次字段赋值（`mPackageManager` / `mContext`），**没有任何每次调用
     * 的可变状态**，因此可以按 Context 长期复用 —— 省掉每次取图都反射走一遍构造
     * （`Constructor#newInstance` 要过访问检查 + 参数打包，单次 ~10µs 量级，
     * 但通知栏一次刷新可能触发十几个包）。
     *
     * 持有的是 Context 强引用：缓存上限 2 个，且只在 Context 更换时淘汰，
     * 不会因为多 Context 而泄漏。构造失败**不缓存**（`managerTried` 不置位），
     * 避免早于 framework 就绪时把 null 永久固化。
     */
    private val managerLock = Any()

    @Volatile
    private var managerCtx: Context? = null

    @Volatile
    private var managerObj: Any? = null

    private fun uxManager(api: UxIconApi, context: Context): Any? {
        val cached = managerObj
        if (cached != null && managerCtx === context) return cached
        synchronized(managerLock) {
            val again = managerObj
            if (again != null && managerCtx === context) return again
            val created = runCatching { api.constructor.newInstance(context.packageManager, context) }
                .getOrElse {
                    LogUtils.w(
                        TAG, "resolve",
                        "构造 $CLS_UX_ICON_PM_EXT 失败：${it.javaClass.simpleName}: ${it.message}",
                    )
                    return null
                }
            managerCtx = context
            managerObj = created
            return created
        }
    }

    private fun invokeLoad(
        api: UxIconApi,
        context: Context,
        itemInfo: PackageItemInfo,
        appInfo: ApplicationInfo,
    ): Drawable? {
        val manager = uxManager(api, context) ?: return null
        api.loadItemIcon?.let { m -> callDrawable(m, manager, itemInfo, appInfo)?.let { return it } }
        api.loadItemIconWithoutEdit?.let { m -> callDrawable(m, manager, itemInfo, appInfo)?.let { return it } }
        return null
    }

    private fun callDrawable(
        method: Method,
        receiver: Any,
        itemInfo: PackageItemInfo,
        appInfo: ApplicationInfo,
    ): Drawable? = runCatching {
        (method.invoke(receiver, itemInfo, appInfo, true) as? Drawable)?.mutate()
    }.getOrElse {
        LogUtils.w(TAG, "resolve", "调用 ${method.name} 失败：${it.javaClass.simpleName}: ${it.message}")
        null
    }

    // ------------------------------------------------------------------ 反射句柄

    private class UxIconApi(
        val constructor: Constructor<*>,
        val loadItemIcon: Method?,
        val loadItemIconWithoutEdit: Method?,
    )

    private val apiLock = Any()

    @Volatile
    private var apiCache: UxIconApi? = null

    @Volatile
    private var apiTried = false

    private fun uxApi(): UxIconApi? {
        if (apiTried) return apiCache
        synchronized(apiLock) {
            if (!apiTried) {
                apiCache = buildApi()
                apiTried = true
            }
        }
        return apiCache
    }

    private fun buildApi(): UxIconApi? {
        val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_UX_ICON_PM_EXT)
        if (cls == null) {
            LogUtils.w(TAG, "install", "未找到 $CLS_UX_ICON_PM_EXT（非 ColorOS？），主题图标功能不可用")
            return null
        }
        val constructor = cls.declaredConstructors.firstOrNull { c ->
            val types = c.parameterTypes
            types.size == 2 &&
                types[0] == PackageManager::class.java &&
                types[1] == Context::class.java
        }?.apply { isAccessible = true }
        if (constructor == null) {
            LogUtils.w(
                TAG, "install",
                "构造函数签名不匹配（期望 (PackageManager, Context)）；实际 → " +
                    cls.declaredConstructors.joinToString { "(" + it.parameterTypes.joinToString { p -> p.simpleName } + ")" },
            )
            return null
        }
        val loadItemIcon = findLoadMethod(cls, "loadItemIcon")
        val loadItemIconWithoutEdit = findLoadMethod(cls, "loadItemIconWithoutEdit")
        if (loadItemIcon == null && loadItemIconWithoutEdit == null) {
            LogUtils.w(TAG, "install", "loadItemIcon / loadItemIconWithoutEdit 都未命中，主题图标功能不可用")
            return null
        }
        LogUtils.i(
            TAG, "install",
            "主题图标接口就绪：loadItemIcon=${loadItemIcon != null} loadItemIconWithoutEdit=${loadItemIconWithoutEdit != null}",
        )
        return UxIconApi(constructor, loadItemIcon, loadItemIconWithoutEdit)
    }

    private fun findLoadMethod(cls: Class<*>, name: String): Method? = Reflect.findMethodByShape(
        cls = cls,
        name = name,
        expect = listOf(PackageItemInfo::class.java, ApplicationInfo::class.java, java.lang.Boolean.TYPE),
        returnType = Drawable::class.java,
    )

    // ------------------------------------------------------------------ 配置快照

    private data class ThemeSnapshot(
        val uiMode: Int,
        val themeChanged: Long,
        val themeChangedFlags: Long,
        val uxIconConfig: Long,
    )

    @Volatile
    private var snapshotCache: ThemeSnapshot? = null

    @Volatile
    private var snapshotAt = 0L

    // -------- 反射句柄缓存（snapshot 每 300ms 就要跑一次，句柄不能每次重找） --------

    /**
     * 句柄缓存的哨兵。`getMethod` / `getDeclaredField` 在 ART 上每次都是
     * 「native 查找 + 克隆对象」，**未命中还要构造并填栈一个异常**，所以
     * 「找不到」这件事本身也值得缓存。
     */
    private val handleMiss = Any()

    private val handleLock = Any()

    /** `Configuration#getOplusExtraConfiguration()` 的 `Method`（或 [handleMiss]） */
    private var extraAccessorHandle: Any? = null
    private var extraAccessorLoaded = false

    /** `Configuration#mOplusExtraConfiguration` 的 `Field`（或 [handleMiss]） */
    private var extraFieldHandle: Any? = null
    private var extraFieldLoaded = false

    /** extra 对象所属类；换类（OTA）时清空 [extraLongHandles] */
    private var extraLongOwner: Class<*>? = null

    /** `字段名 -> (Field?, Method?)`，extra 类固定，索引一次即可 */
    private val extraLongHandles = HashMap<String, Pair<Field?, Method?>>()

    /**
     * 读 `Configuration#getOplusExtraConfiguration()` 里的主题字段。
     *
     * 这几项一起变化才代表「主题/图标包/深浅色」换了，所以拿它们当缓存键。
     * 全部读不到也不是错误（部分 ROM 会裁字段），只是缓存键退化成常数。
     */
    private fun snapshot(context: Context): ThemeSnapshot {
        val now = SystemClock.uptimeMillis()
        snapshotCache?.let { if (now - snapshotAt < SNAPSHOT_TTL_MS) return it }
        val configuration = runCatching { context.resources.configuration }.getOrNull()
        val extra = configuration?.let { extraConfiguration(it) }
        val value = ThemeSnapshot(
            uiMode = (configuration?.uiMode ?: 0) and Configuration.UI_MODE_NIGHT_MASK,
            themeChanged = extra?.longMember("mThemeChanged", "getThemeChanged") ?: 0L,
            themeChangedFlags = extra?.longMember("mThemeChangedFlags", "getThemeChangedFlags") ?: 0L,
            uxIconConfig = extra?.longMember("mUxIconConfig", "getUxIconConfig") ?: -1L,
        )
        snapshotCache = value
        snapshotAt = now
        return value
    }

    private fun extraConfiguration(configuration: Configuration): Any? {
        accessorOf(configuration)?.let { accessor ->
            runCatching { accessor.invoke(configuration) }.getOrNull()?.let { return it }
        }
        return runCatching {
            fieldOf(configuration)?.get(configuration)
        }.getOrNull()
    }

    /** `getOplusExtraConfiguration()` 句柄（只解析一次） */
    private fun accessorOf(configuration: Configuration): Method? {
        if (extraAccessorLoaded) return extraAccessorHandle as? Method
        synchronized(handleLock) {
            if (!extraAccessorLoaded) {
                val found = runCatching {
                    configuration.javaClass.getMethod("getOplusExtraConfiguration")
                }.getOrNull()?.takeIf {
                    !Modifier.isStatic(it.modifiers) && it.returnType != Void.TYPE
                }?.apply { runCatching { isAccessible = true } }
                extraAccessorHandle = found ?: handleMiss
                extraAccessorLoaded = true
            }
        }
        return extraAccessorHandle as? Method
    }

    /** `mOplusExtraConfiguration` 字段句柄（只解析一次） */
    private fun fieldOf(configuration: Configuration): Field? {
        if (extraFieldLoaded) return extraFieldHandle as? Field
        synchronized(handleLock) {
            if (!extraFieldLoaded) {
                val found = runCatching {
                    configuration.javaClass.getDeclaredField("mOplusExtraConfiguration")
                        .apply { isAccessible = true }
                }.getOrNull()
                extraFieldHandle = found ?: handleMiss
                extraFieldLoaded = true
            }
        }
        return extraFieldHandle as? Field
    }

    private fun Any.longMember(fieldName: String, getterName: String): Long? {
        val (field, getter) = longHandles(javaClass, fieldName, getterName)
        field?.let { f ->
            runCatching { (f.get(this) as? Number)?.toLong() }.getOrNull()?.let { return it }
        }
        getter?.let { g ->
            return runCatching { (g.invoke(this) as? Number)?.toLong() }.getOrNull()
        }
        return null
    }

    private fun longHandles(
        cls: Class<*>,
        fieldName: String,
        getterName: String,
    ): Pair<Field?, Method?> {
        synchronized(handleLock) {
            if (extraLongOwner !== cls) {
                extraLongHandles.clear()
                extraLongOwner = cls
            }
            extraLongHandles[fieldName]?.let { return it }
            val field = runCatching {
                cls.getDeclaredField(fieldName).apply { isAccessible = true }
            }.getOrNull()
            val getter = runCatching { cls.getMethod(getterName) }.getOrNull()?.apply {
                runCatching { isAccessible = true }
            }
            val pair = field to getter
            extraLongHandles[fieldName] = pair
            return pair
        }
    }

    // ------------------------------------------------------------------ 位图化

    /**
     * Drawable → 正方形位图。
     *
     * 两步：先按目标尺寸渲染到整块画布，再**裁掉四周全透明区域后居中铺满**。
     * 第二步是必要的：桌面图标（`AdaptiveIconDrawable`）自带约 1/6 的自适应边距，
     * 直接原样缩到状态栏那 20dp 里会显得比旁边的系统图标小一圈。裁掉空白再按
     * [CONTENT_RATIO] 铺回，视觉尺寸才和系统图标对齐、又不至于顶到边。
     */
    private fun Drawable.toIconBitmap(targetPx: Int): Bitmap {
        val size = targetPx.coerceIn(SIZE_MIN, SIZE_MAX)
        val full = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val savedBounds = Rect(bounds)
        runCatching {
            setBounds(0, 0, size, size)
            draw(Canvas(full))
        }
        runCatching { bounds = savedBounds }
        val visible = full.visibleBounds() ?: return full

        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val content = (size * CONTENT_RATIO).coerceAtLeast(1f)
        val scale = minOf(
            content / visible.width().toFloat(),
            content / visible.height().toFloat(),
        )
        val drawWidth = visible.width() * scale
        val drawHeight = visible.height() * scale
        val left = (size - drawWidth) / 2f
        val top = (size - drawHeight) / 2f
        Canvas(out).drawBitmap(full, visible, RectF(left, top, left + drawWidth, top + drawHeight), PAINT)
        // `full` 只是中间画布，画完就没用了 —— 主动回收，别把它留给 GC 慢慢等
        // （192×192 ARGB_8888 = 147 KiB 原生内存，一次通知刷新可能连做十几个包）
        runCatching { full.recycle() }
        return out
    }

    /**
     * 可见像素包围盒；全透明返回 null。
     *
     * 用 `getPixels` 一次性取整块（别写双重循环 `getPixel` —— 那是 JNI 调用 × 像素数）。
     * 扫描缓冲**跨调用复用**：原来每次 `IntArray(w*h)` = 147 KiB 分配，
     * 现在是零分配（只在上限尺寸变大时重建一次）。
     */
    private val scanLock = Any()

    private var scanBuffer = IntArray(0)

    private fun Bitmap.visibleBounds(): Rect? {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return null
        val pixels = synchronized(scanLock) {
            if (scanBuffer.size < w * h) scanBuffer = IntArray(w * h)
            scanBuffer
        }
        runCatching { getPixels(pixels, 0, w, 0, 0, w, h) }.getOrElse { return null }
        var left = w
        var top = h
        var right = -1
        var bottom = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if ((pixels[row + x] ushr 24) <= ALPHA_THRESHOLD) continue
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        if (right < left || bottom < top) return null
        return Rect(left, top, right + 1, bottom + 1)
    }
}
