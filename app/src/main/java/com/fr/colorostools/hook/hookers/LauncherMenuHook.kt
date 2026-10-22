package com.fr.colorostools.hook.hookers

import android.content.Context
import android.graphics.drawable.Drawable
import android.widget.AdapterView
import com.fr.colorostools.hook.HookEnv
import com.fr.colorostools.ref.Reflect
import com.fr.colorostools.utils.LogUtils
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Proxy
import java.util.WeakHashMap

/**
 * 功能：系统桌面「App 图标长按菜单」定制。
 *
 * 移植自 fkfluidcloud「功能七」（`LauncherMenuHook` v1.6.16+），宿主 `com.android.launcher`
 * （Launcher3 的 ColorOS 深度改造版，实测 17.3.12 / ColorOS 17 / Android 17 / OnePlus 15）。
 * 方案评估（含与上游 11 钩子实现的逐项对比）：`docs/桌面长按菜单移植-方案评估.md`。
 *
 * ## 与上游实现的结构差异（为什么只要 3 个钩子）
 *
 * 上游用 11 个钩子（6× `isEnabled` 逐类 + `populateAndShow` + `currentVisibleDeepShortcutLimit`
 * + `segments` + `applyShortcutInfoCompat` + `resolveShortcutLabel`）。逆向 17.3.12 后确认：
 *
 * 1. **`ShortcutDataBuilder#buildPopupData(View)` 是全部菜单数据的唯一总装点**
 *    （`PopupMenuPresenter#showForIcon` :654）—— 一级系统项 / 深链列表 / 堆叠组在返回前全部定型；
 * 2. **宿主自己对 `systemShortcutsIn` 逐项问 `isEnabled()`**
 *    （`COUIPopupListWindowContainer#populateAndShow` :1729/:1901、堆叠组 :1825）——
 *    在数据层删掉实例等价于「被禁用」，无需钩 `isEnabled()` 本身；
 * 3. **`segments()` 是渲染与点击的公共上游**（`refreshList→buildDisplayList` 与
 *    点击分发 `b#h()` 都从它取，且点击时实时重调）—— 换深链段 `onClick` 即换点击行为，
 *    改段内 entry 的 coui 行模型（`g`）字段即改渲染数据；
 * 4. 异步回填（`PopupPopulator.createUpdateRunnable → updateShortcutInfo →
 *    `applyShortcutInfoCompat`）会用 `convertAppShortcutWithIcon` **新建 item**（文案/图标
 *    复原为深链原名）→ 但随后 `refreshList()` 重调 `segments()`，本钩子的收起态改写
 *    **自动重放** —— 不需要钩 `applyShortcutInfoCompat`，也不需要
 *    `resolveShortcutLabel` + ThreadLocal 跨钩子传 id。
 *
 * ⇒ 本实现：**钩子 A（buildPopupData，数据层）+ 钩子 B（segments，段层）+ 钩子 C（limit）**。
 *
 * ## 「快捷方式」折叠 = 复用宿主原生二级面板（2026-10-16 v1.0.24 重构）
 *
 * 早期版本自建了「窗口撑高 + 错峰淡入」动画，实测生硬（窗口尺寸一帧跳变、180ms 淡入太快）。
 * 逆向发现宿主自带一套成熟的折叠展开机制（「更多功能」MordFunctions 用的就是它）：
 *
 * - coui 行模型 `g` 的 `r`（可用）+ `t`（子项行模型列表）两个字段 ⇒
 *   适配器**自动渲染展开箭头**（`MixedPopupListAdapter` :1203 `coui_list_expandable_indicator`）；
 * - 点击后宿主 `b#h()` 自动检查可展开性并调 `b#S(pos, view)`：
 *   弹出**二级面板**（第二个 RoundFrameLayout + 独立适配器），带动画
 *   **`cf.l` 按压进度交接**（从手指按下的那行边界展开到目标面板，ColorOS 原生手感）、
 *   父行高亮、再点父行/点外面自动收起（`COUIPopupMenuRootView#G#t()`）。
 *
 * ⇒ 本功能的折叠只做几件数据层小事，动画与交互全部白嫖原生：
 *
 * 1. **折叠行 = 克隆首项行模型**：generic 文案「快捷方式」（4 字，与原生「更多功能」对齐的
 *    实测宽度下限——6 字在二级面板里会折行）+ 更多功能图标 + `r=true` + `t=[全量深链的 g]`；
 *    克隆而非直改首项——首项自身还要以原名/原图标出现在二级面板里（同一个 g 对象
 *    没法在两个列表里显示两种文案）；
 * 2. 合成只含折叠行的 `DeepShortcutEntry`（不污染宿主 entry），深链段 `onClick` 换成
 *    **压制启动**代理——原生 onClick 会先启动第一个深链，压掉后宿主 `b#h()` 才走得到展开检查；
 * 3. `t` **每次渲染重抓**：异步回填 `applyShortcutInfoCompat` 逐项替换 mDeepShortcuts
 *    （换新的带图标 g），旧引用会让二级面板里的行丢图标。
 *
 * ### 二级面板子项点击：`F` 字段换 delegate（唯一需要接管的地方）
 *
 * 宿主二级面板的子项点击（`b#g()` → 窗口的 `F` 监听器）**写死指向 MordFunctions**
 * （`COUIPopupListWindowContainer` 的匿名 lambda：遍历 mSystemShortcuts 找 MordFunctions，
 * 点 `moreSystemShortcutList.get(i)`）——深链走这条路会点到「更多功能」的第 i 项甚至静默无操作。
 *
 * ⇒ 首次点折叠行时把窗口的 `F` 换成模块侧的分发器（字段赋值，**不是钩子**）：
 * 展开行是我们的折叠行（`Q` 位与 `foldParentG` 同一对象）⇒ 按 position 直接下标取全量深链、
 * 调**原生段 onClick** 启动 + 关窗；否则（原生「更多功能」面板）⇒
 * 委托给换装前保存的原生分发器，行为与 stock 完全一致。
 *
 * ## 三个钩子
 *
 * ```
 * ① ShortcutDataBuilder#buildPopupData(View) 后置         —— 每次长按 1 次
 *     按项隐藏：systemShortcutsIn 删 6 类实例 + MordFunctions 双列表同步删
 *              （mMoreSystemShortcutList 与 popupListItems 构造时同步 add，下标一一对应）
 *     平铺：    MordFunctions → 摊开成 mMoreSystemShortcutList 的孩子（去重判据 = 宿主 isEnabled）
 *     隐藏深链：shortcutInfoList.clear()（宿主 z16 判空跳过整个深链分组）
 *     折叠：   不动数据（保留全量，走原生 limit 机制）；顺带一次性快照 10 个开关
 * ② BasePopupListWindowContainer#segments() 后置          —— 每次 refreshList 1 次
 *     折叠：    克隆首项行模型作折叠行（r/t/m/k 字段，真名见 [rewriteSegments] 的 JADX
 *              花名警告）+ 合成折叠行条目 + 段 onClick 换压制代理 + t 每次渲染重抓
 *               （⚠️ 拦截器必须 return 改写结果——早期版本丢弃返回值导致段替换从未生效）
 * ③ COUIPopupListWindowContainer#currentVisibleDeepShortcutLimit() 后置
 *     折叠开启 ∧ 深链 > 1 ⇒ 返回 1（原生 take(limit) 只渲染第一个深链 = 折叠行）
 * ```
 *
 * ## 兜底原则
 *
 * 每一处独立 `runCatching`；类/方法/字段找不到只记 W 日志后跳过；**任何一步失败都原样放行**。
 * 取不到子项行模型时放弃折叠（保持原生：点击启动第一个深链），绝不制造「点了没反应」的死行。
 * 桌面崩了用户很难自救（会直接回到锁屏/黑屏），所以宁可不生效。
 */
internal object LauncherMenuHook {

    private const val TAG = "LauncherMenu"

    /** 数据总装器（钩子 A 的宿主类） */
    private const val CLS_SHORTCUT_DATA_BUILDER = "com.android.launcher3.popup.model.ShortcutDataBuilder"

    /** 弹出容器（MVP 重构后的最终落地类；钩子 C 的宿主类） */
    private const val CLS_POPUP = "com.android.launcher3.popup.view.COUIPopupListWindowContainer"

    /** 弹窗容器基类：`segments()` / `close(boolean)` 只在此声明，子类未覆写 */
    private const val CLS_POPUP_BASE = "com.android.launcher3.popup.view.BasePopupListWindowContainer"

    /** 深链段模型（Kotlin data class：`entries: List<PopupMenuEntry>` + `onClick: Function2`） */
    private const val CLS_SEGMENT = "com.android.launcher3.popup.model.PopupMenuSegment"

    /** 深链条目模型（合成折叠行条目用；构造器 `(g, WorkspaceItemInfo, ShortcutInfo, boolean)`） */
    private const val CLS_DEEP_ENTRY = "com.android.launcher3.popup.model.DeepShortcutEntry"

    /**
     * 构造 `PopupMenuSegment` 时要传的 `onClick` 接口名。
     *
     * ⚠️ **必须按名字从宿主类加载器加载**：桌面进程自带一份 kotlin-stdlib，模块也自带一份，
     * 两个 `kotlin.jvm.functions.Function2` 是不同的 Class 对象 —— 模块自己的
     * `object : Function2` 传给桌面构造器会抛「argument 2 has type ...Function2, got ...」
     * （上游 v1.6.11 实测），压制段永远建不起来。
     */
    private const val CLS_FUNCTION2 = "kotlin.jvm.functions.Function2"

    /** 「更多功能」容器类简单名（平铺时要把它摊开成自己的孩子） */
    private const val SIMPLE_MORE = "MordFunctions"

    /** 深链条目模型的简单名（判段用；全名 com.android.launcher3.popup.model.DeepShortcutEntry） */
    private const val SIMPLE_DEEP_ENTRY = "DeepShortcutEntry"

    /**
     * 6 个可按项隐藏的菜单项 —— **顺序必须与 [LauncherMenuConfig] 的 6 个 hide 键一致**：
     * 隐私加锁 / 关闭隐私加锁 / 应用信息 / 分享 / 编辑 / 服务卡片。
     *
     * 括号内为宿主里的实际内部类名（`OplusBaseSystemShortcut` 的静态内部类）→ 对应资源串：
     *   PrivacyLock(`privacy_lock_new`) / ClosePrivacyLock(`close_privacy_lock_new`) /
     *   OplusAppInfo(`app_info_drop_target_label_oplus`) / AppShare(`share_action`) /
     *   AppEdit(`app_edit`) / CardAdd(`card_add` = 「服务卡片」）
     */
    private val ITEM_CLASS_SIMPLE = arrayOf(
        "PrivacyLock",
        "ClosePrivacyLock",
        "OplusAppInfo",
        "AppShare",
        "AppEdit",
        "CardAdd",
    )

    /**
     * 折叠入口的文案（收起态一级行 + 二级面板头部）。
     *
     * 取 4 字是与原生「更多功能」对齐的**实测下限**：v1.0.25 用 6 字「应用快捷方式」
     * 在二级面板（比一级窄）里折成两行；4 字两边都单行。
     */
    private const val LABEL_COLLAPSED = "快捷方式"

    // ------------------------------------------------------------------ 状态

    /**
     * 每次弹窗的开关快照 —— 在钩子 A（buildPopupData，每次长按恰好 1 次）一次性读好，
     * 钩子 B/C 直接读快照：热路径（segments 每次 refreshList 都跑）零 prefs 读取。
     *
     * 桌面弹窗是单例（`BasePopupListWindowContainer.currentOpenPopup` WeakReference 机制，
     * `populateAndShow` :1838-1843），进程级单例不会串。
     */
    @Volatile
    private var cfg: LauncherMenuConfig.MenuCfg? = null

    /** 桌面「更多功能」那份 `SystemShortcut`（折叠行图标来源；钩子 A 改写前留存） */
    @Volatile
    private var moreFunctionsShortcut: Any? = null

    // ------------------------------------------------- 原生二级面板的折叠上下文
    //
    // 由钩子 B 每次渲染刷新到最新对象（异步回填会换新 g/entry），
    // 供二级面板点击分发器（[subClickRouter]）在点击时使用。单弹窗单例，不会串。

    /** 折叠行的 coui 行模型（`g`，克隆体）——二级面板「是不是我们的」判据（对象同一性） */
    @Volatile
    private var foldParentG: Any? = null

    /** 全量深链（`mDeepShortcuts`）：二级面板子项点击按 position 直接下标取 */
    @Volatile
    private var foldSubEntries: List<*>? = null

    /** 深链段**原生** onClick（Function2）——子项点击时调它启动对应深链 */
    @Volatile
    private var foldOrigOnClick: Any? = null

    /** 本次弹窗的容器（关窗用） */
    @Volatile
    private var foldContainer: Any? = null

    /**
     * 容器 → (首项 entry, 折叠行克隆)。entry 没换就复用同一个克隆 ⇒ 克隆对象身份
     * 跨渲染稳定，二级面板点开后 [isMySubmenuOpen] 的同一性比对才可靠。
     * WeakHashMap：弹窗销毁后容器被回收，表里不留垃圾。
     */
    private val foldCloneCache = WeakHashMap<Any, Pair<Any, Any>>()

    /** 换装前保存的原生 `F` 二级面板分发器（「更多功能」等原生面板委托给它） */
    @Volatile
    private var subClickOrigF: Any? = null

    /**
     * 二级面板子项点击分发器（模块侧 Kotlin 对象，`AdapterView.OnItemClickListener`
     * 是 android.jar 里的接口，无需动态代理）。
     *
     * 装到弹窗窗口的 `F` 字段上（见 [ensureSubClickRouting]）：
     * - 展开行是我们的折叠行 ⇒ 启动对应深链 + 关窗；
     * - 否则（原生「更多功能」面板）⇒ 委托原生分发器，行为与 stock 一致。
     */
    private val subClickRouter = AdapterView.OnItemClickListener { _, view, position, _ ->
        runCatching {
            val container = foldContainer
            val win = container?.let { popupListWindow(it) }
            if (win != null && isMySubmenuOpen(win)) {
                // position 就是 t（= 全量深链）的下标：紧凑模式宿主已把头部行 -1 掉，
                // 非紧凑模式没有头部行 —— 两种形态都直接下标取
                val entry = foldSubEntries?.getOrNull(position)
                val onClick = foldOrigOnClick
                if (entry != null && onClick != null) {
                    Reflect.callDeep(onClick, "invoke", entry, view)
                } else {
                    LogUtils.w(TAG, "subClickRouter", "折叠子项越界或丢失原生 onClick（position=$position）")
                }
                closePopup(container)
            } else {
                (subClickOrigF as? AdapterView.OnItemClickListener)
                    ?.onItemClick(null, view, position, 0L)
            }
        }.onFailure { LogUtils.w(TAG, "subClickRouter", "分发失败：${it.message}") }
    }

    // ------------------------------------------------------------------ 安装

    fun install() {
        hookBuildPopupData()
        hookSegments()
        hookFoldLimit()
    }

    // -------------------------------------------------- ① buildPopupData（数据层）

    /**
     * 钩子 A：`ShortcutDataBuilder#buildPopupData(View)` 后置。
     *
     * 按**名字 + 形状**定位（1 参 View、返回 PopupMenuData）—— 不依赖混淆后的方法名细节，
     * 宿主再改名/换签名也能按形状命中或明确跳过。
     */
    private fun hookBuildPopupData() {
        runCatching {
            val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_SHORTCUT_DATA_BUILDER) ?: run {
                LogUtils.w(TAG, "install", "$CLS_SHORTCUT_DATA_BUILDER 不存在，长按菜单定制全部不生效")
                return
            }
            val m = cls.declaredMethods.firstOrNull {
                it.name == "buildPopupData" &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0].simpleName == "View" &&
                    it.returnType.simpleName == "PopupMenuData"
            } ?: run {
                val cands = cls.declaredMethods.joinToString { mm -> "${mm.name}(${mm.parameterTypes.size})" }
                LogUtils.w(TAG, "install", "找不到 buildPopupData(View)→PopupMenuData；候选=[$cands]")
                return
            }
            m.isAccessible = true
            HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val r = chain.proceed()
                    runCatching { rewritePopupData(r) }
                        .onFailure { LogUtils.w(TAG, "buildPopupData", "改写失败（已放行）：${it.message}") }
                    return r
                }
            })
            LogUtils.i(TAG, "install", "已挂 $CLS_SHORTCUT_DATA_BUILDER#${m.name}（数据层：按项隐藏/平铺/隐藏深链）")
        }.onFailure { LogUtils.w(TAG, "install", "挂 buildPopupData 失败：${it.message}") }
    }

    /**
     * 就地改写 [PopupMenuData]（字段 final 换不掉，但两个列表 getter 返回宿主自己的
     * ArrayList 引用 → 原地改内容即可生效）。
     *
     * 顺序有讲究：**先按项隐藏（一级 + 更多子列表 + 堆叠组），再平铺** —— 平铺摊开的是
     * 已过滤干净的子列表，被隐藏的项不会从「更多」里复活。
     */
    private fun rewritePopupData(data: Any?) {
        if (data == null) return

        // 每次弹窗一次性快照 10 个开关（remote prefs 内存读，无 IO）
        val c = LauncherMenuConfig.read()
        cfg = c
        if (!c.enabled) return

        val sysIn = Reflect.callByName(data, "getSystemShortcutsIn") as? MutableList<Any?>

        // ① 留一份「更多功能」实例（折叠行图标 + 平铺的数据源）。
        //    必须在任何改写之前抓 —— 平铺会把 MordFunctions 从列表里摘掉。
        //    每次弹窗都刷新（不同图标的 itemInfo 不同；但 icon resId 是构造常量，跨实例一致）。
        if (sysIn != null) {
            for (e in sysIn) {
                if (e != null && e.javaClass.simpleName == SIMPLE_MORE) {
                    moreFunctionsShortcut = e
                    break
                }
            }
        }

        // ② 按项隐藏：一级删实例 + 「更多」双列表同步删 + 堆叠组删
        if (c.hideAny) {
            if (sysIn != null) {
                val before = sysIn.size
                sysIn.removeAll { e -> e != null && itemHideIndex(e.javaClass.simpleName, c.hideFlags) >= 0 }
                val removed = before - sysIn.size
                if (removed > 0) LogUtils.i(TAG, "rewrite", "按项隐藏：一级移除 $removed 项")
            }
            moreFunctionsShortcut?.let { more ->
                val n = hideMoreKids(more, c.hideFlags)
                if (n > 0) LogUtils.i(TAG, "rewrite", "按项隐藏：「更多功能」子菜单移除 $n 项")
            }
            (Reflect.callByName(data, "getShortcutsInStackGroup") as? MutableList<Any?>)
                ?.removeAll { e -> e != null && itemHideIndex(e.javaClass.simpleName, c.hideFlags) >= 0 }
        }

        // ③ 平铺二级菜单：把「更多功能」摊开成它自己的孩子
        if (c.flat && sysIn != null) {
            flattenMore(sysIn)
        }

        // ④ 隐藏深链：清空列表（宿主据此跳过整个深链分组）
        if (c.hideDeep) {
            val deep = Reflect.callByName(data, "getShortcutInfoList") as? MutableList<*>
            if (deep != null && deep.isNotEmpty()) {
                val n = deep.size
                deep.clear()
                LogUtils.i(TAG, "rewrite", "隐藏应用自定义快捷方式 x$n（PopupMenuData 列表已清空）")
            }
        }
    }

    /** 类简单名 → 6 个隐藏开关里的下标；不需要隐藏返回 -1 */
    private fun itemHideIndex(simpleName: String, flags: BooleanArray): Int {
        val i = ITEM_CLASS_SIMPLE.indexOf(simpleName)
        if (i < 0) return -1
        return if (flags.getOrNull(i) == true) i else -1
    }

    /**
     * 「更多功能」子菜单按项隐藏：`mMoreSystemShortcutList`（SystemShortcut 实例，平铺数据源）
     * 与 `popupListItems`（coui 行模型，子菜单渲染源）**同步删**。
     *
     * ⚠️ 两个列表在 `MordFunctions` 构造函数里同一个 `if (isEnabled())` 块内同步 add
     * （OplusBaseSystemShortcut.java:2527-2560 实证）—— 下标一一对应。
     * 若某个版本不再同步（size 不等），**整体跳过**：宁可这一层不生效，也不少删/错删
     * （错删会让 coui 渲染与点击映射错位）。
     *
     * @return 移除的子项数；-1 = 下标对应关系失效，未处理
     */
    private fun hideMoreKids(more: Any, flags: BooleanArray): Int {
        val kids = Reflect.getField(more, "mMoreSystemShortcutList") as? MutableList<Any?> ?: return -1
        val items = Reflect.getField(more, "popupListItems") as? MutableList<Any?> ?: return -1
        if (kids.size != items.size) {
            LogUtils.w(TAG, "hideMoreKids", "子列表 size 不一致（kids=${kids.size}, items=${items.size}），跳过「更多」按项隐藏")
            return -1
        }
        var removed = 0
        // 从后往前删，保下标对应
        for (i in kids.indices.reversed()) {
            val k = kids[i] ?: continue
            if (itemHideIndex(k.javaClass.simpleName, flags) >= 0) {
                kids.removeAt(i)
                items.removeAt(i)
                removed++
            }
        }
        return removed
    }

    /**
     * 平铺：把 `MordFunctions` 从一级列表换成它自己的孩子（`mMoreSystemShortcutList`）。
     *
     * ⚠️⚠️ **摊开前必须去重，且判据是「宿主自己的 `isEnabled()`」，不能只看类名**（上游教训）：
     * `Launcher#getSupportedShortcuts()` 把 `APP_INFO`/`APP_SHARE`/`APP_EDIT`/`PRIVACY_LOCK`/
     * `CLOSE_PRIVACY_LOCK` 与 `MORE_FUNCIONS` **同时**注册到一级；`MordFunctions` 构造时又把
     * 前五个各造一份塞进子列表（只放 `isEnabled()==true` 的）→ 直接摊开必重复。
     * 但这五个类的 `isEnabled()` 头一句都是
     * `if (!mMoreShortcut && mItemInfo.mAddShortcutCount > 0) return false;`
     * ⇒ **有自定义快捷方式的 App（微信/支付宝…），一级那份是禁用的**（宿主 populate 时会过滤掉），
     * 唯一可用的恰恰是「更多」里那份（`mMoreShortcut=true`）。
     * 只看类名去重 ⇒ 把唯一可用的那份砍掉，实测「应用详情/编辑/分享消失，只剩卸载」。
     *
     * ⇒ 正确判据：**只有一级里同名项 `isEnabled()` 真为 true**，摊开的那份才算重复。
     * `isEnabled()` 反射取不到就不算「可用」，保留摊开那份 —— 宁可多一项，也不丢功能项。
     *
     * ⚠️ 摊开**不能动子项的 `mMoreShortcut`**：宿主 `AppEdit.isEnabled()` /
     * `AppShare.isEnabled()` 形如 `if (!mMoreShortcut && mItemInfo.mAddShortcutCount > 0) return false;`
     * —— 置回 false 会让宿主认为这是「一级位置的编辑/分享」，而这两个项恰恰只在更多菜单里
     * 才允许出现 → 被 `populateAndShow` 的 `isEnabled()` 过滤掉。
     */
    private fun flattenMore(sysIn: MutableList<Any?>) {
        // liveLevel1 必须在删除/平铺**之前**的一级列表上算
        val liveLevel1 = HashSet<String>(sysIn.size * 2)
        for (e in sysIn) {
            if (e == null || e.javaClass.simpleName == SIMPLE_MORE) continue
            if (Reflect.callByName(e, "isEnabled") == true) {
                liveLevel1.add(e.javaClass.name)
            }
        }
        val rebuilt = ArrayList<Any?>(sysIn.size + 4)
        val addedKid = HashSet<String>(8)
        val deduped = ArrayList<String>(4)
        var flattened = 0
        for (e in sysIn) {
            if (e == null) continue
            if (e.javaClass.simpleName != SIMPLE_MORE) {
                rebuilt.add(e)
                continue
            }
            flattened++
            val kids = Reflect.getField(e, "mMoreSystemShortcutList") as? Collection<*>
            if (kids.isNullOrEmpty()) {
                // 子项全被禁用/隐藏 → 「更多功能」本身变成空壳，直接丢掉
                continue
            }
            for (k in kids) {
                if (k == null) continue
                val cn = k.javaClass.name
                // 一级已有「同类且可用」的项 → 摊开这份是重复；摊开列表内部也顺带去重
                if (liveLevel1.contains(cn) || !addedKid.add(cn)) {
                    deduped.add(k.javaClass.simpleName)
                    continue
                }
                rebuilt.add(k)
            }
        }
        if (flattened > 0) {
            sysIn.clear()
            sysIn.addAll(rebuilt)
            LogUtils.i(
                TAG, "flattenMore",
                "平铺「更多功能」x$flattened → 一级项 ${sysIn.size} → ${rebuilt.size}" +
                    (if (deduped.isEmpty()) "" else "（去重丢弃 ${deduped.joinToString("/")}）"),
            )
        }
    }

    // -------------------------------------------------- ② segments（段层 · 折叠）

    /**
     * 钩子 B：`BasePopupListWindowContainer#segments()`（父类声明、子类未覆写）后置。
     *
     * `segments()` 的返回值同时喂三处：
     * - `refreshList() → buildDisplayList()` → 渲染；
     * - 点击分发 `b#h()`（按累加下标定位段，再取 `segment.onClick`；**点击时实时重调**）；
     * - `b#h()` 的展开检查取数据列表（真名 `n`）`get(pos)` 的行模型判「可展开」。
     *
     * ⚠️ **拦截器必须 return 改写结果**：早期版本 `runCatching { rewriteSegments(...) }` 后
     * 照返原列表，段替换从未生效（点击直接启动深链）—— 原地突变（改 g 字段）才不需要返回值。
     */
    private fun hookSegments() {
        runCatching {
            val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_POPUP_BASE) ?: run {
                LogUtils.w(TAG, "install", "$CLS_POPUP_BASE 不存在，折叠不生效（其余功能不受影响）")
                return
            }
            val m = cls.declaredMethods.firstOrNull {
                it.name == "segments" && it.parameterCount == 0
            } ?: run {
                LogUtils.w(TAG, "install", "父类未声明 segments()，折叠不生效")
                return
            }
            m.isAccessible = true
            HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val r = chain.proceed()
                    return try {
                        rewriteSegments(chain.thisObject, r)
                    } catch (t: Throwable) {
                        LogUtils.w(TAG, "segments", "段改写失败（已放行）：${t.message}")
                        r
                    }
                }
            })
            LogUtils.i(TAG, "install", "已挂 $CLS_POPUP_BASE#segments()（段层：折叠入口/原生二级面板）")
        }.onFailure { LogUtils.w(TAG, "install", "挂 segments 失败：${it.message}") }
    }

    /**
     * 段改写总入口（折叠开 ∧ 总开关开 ∧ 深链 > 1 时才动手）：
     * 1. **折叠行 = 克隆首项行模型**（generic 文案「快捷方式」+ 更多功能图标 + 可展开标记
     *    `r=true`/`t=[全量深链的 g]`）⇒ 宿主适配器自动画展开箭头、`b#h()` 点击后自动
     *    `S()` 弹原生二级面板；
     *    为什么是克隆而不是直接改首项：首项自身还要以**原名/原图标**出现在二级面板里
     *    （v1.0.25 直接把首项改 generic 标签 ⇒ 展开后第一个快捷方式"消失"了），
     *    同一个 g 对象没法在两个列表里显示两种文案；
     * 2. 合成一个只包折叠行的 `DeepShortcutEntry`（不污染宿主的 entry），深链段整体换成
     *    **压制启动**段（否则原生 onClick 先启动第一个深链，走不到展开检查）；
     * 3. 刷新折叠上下文（[foldParentG]/[foldSubEntries]/[foldOrigOnClick]/[foldContainer]）。
     *
     * ⚠️ `t`（子项列表）**每次渲染都重抓**：异步回填 `applyShortcutInfoCompat` 会逐项替换
     * `mDeepShortcuts`（换新的带图标 g）—— 只在首项换新时重抓会漏掉 1..N-1 项，
     * 二级面板攥着一串没图标的旧 g（v1.0.25 事故：展开后左边图标全没）。
     * 代价 = 每次渲染多 N 次 `getItem()` + 1 次 setField，可忽略。
     *
     * ⚠️⚠️ 字段真名是单字母 `r`/`t`/`m`/`k`（dexdump 实证，classes2.dex）—— JADX 反编译显示为
     * `f24294r`/`f24296t`/`f24289m`/`f24287k` 是它的**花名**，直接拿花名反射必静默失败
     * （v1.0.24 事故：点击折叠行无反应，就是因为 setField("f24294r") 从未生效）。
     * 窗口类 b 的数据列表真名是 `n`（JADX 显示 f24250n）。一律以 dexdump 为准。
     */
    private fun rewriteSegments(container: Any, segments: Any?): Any? {
        val c = cfg ?: return segments
        if (!c.enabled || !c.fold) return segments
        val list = segments as? List<*> ?: return segments

        val deepIdx = list.indexOfFirst { seg ->
            val es = Reflect.callByName(seg, "getEntries") as? List<*>
            es != null && es.any { it != null && it.javaClass.simpleName == SIMPLE_DEEP_ENTRY }
        }
        if (deepIdx < 0) return segments
        val deepSeg = list[deepIdx] ?: return segments

        val deep = Reflect.getField(container, "mDeepShortcuts") as? List<*>
        if (deep == null || deep.size <= 1) return segments

        // 子项 = **全量**深链的当前行模型（含首项：展开后每个快捷方式都要能点开，
        // v1.0.25 只放 1..N-1 ⇒ 首项被 generic 标签吞掉，"少了一个快捷方式"）
        val subItems = ArrayList<Any?>(deep.size)
        for (e in deep) {
            val g = e?.let { Reflect.callByName(it, "getItem") } ?: continue
            subItems.add(g)
        }
        if (subItems.isEmpty()) {
            // 拿不到子项行模型 ⇒ 不折叠：保持原生（点击启动第一个深链），绝不造死行
            LogUtils.w(TAG, "rewriteSegments", "深链 ${deep.size} 个但取不到行模型，放弃折叠")
            return segments
        }

        val srcEntry = deep[0] ?: return segments
        val srcG = Reflect.callByName(srcEntry, "getItem") ?: return segments
        // 折叠行克隆（按容器缓存；回填写入新 entry 才重建 ⇒ 对象身份跨渲染稳定，
        // 二级面板点开后的 `isMySubmenuOpen` 身份比对才可靠）
        val foldG = obtainFoldClone(container, srcEntry, srcG) ?: return segments
        Reflect.setField(foldG, "r", true)
        Reflect.setField(foldG, "t", subItems)
        Reflect.setField(foldG, "m", LABEL_COLLAPSED)
        moreFunctionsIcon(container)?.let { Reflect.setField(foldG, "k", it) }
        // 防呆：customView / rightIcon 置空（深链行模型本就是 null；若别的来源带了 View，
        // 克隆和原型会被加进两个行层级触发"已存在父视图"）
        Reflect.setField(foldG, "g", null)
        Reflect.setField(foldG, "h", null)

        // 合成折叠行条目（宿主 entry 不碰）+ 压制段（entries 只含折叠行 ⇒ 显示/点击映射一致）
        val foldEntry = buildFoldEntry(foldG) ?: return segments
        val newSeg = buildSuppressSegment(container, listOf(foldEntry)) ?: return segments

        // 刷新折叠上下文（点击分发器要用）
        foldParentG = foldG
        foldSubEntries = deep
        foldOrigOnClick = Reflect.callByName(deepSeg, "getOnClick")
        foldContainer = container

        val out = ArrayList<Any?>(list.size)
        out.addAll(list)
        out[deepIdx] = newSeg
        return out
    }

    /**
     * 取（按需新建）折叠行的克隆行模型。
     *
     * 克隆 = 复制首项行模型全部字段后由调用方覆盖 `r`/`t`/`m`/`k`。
     * 缓存键 = 容器，值 = (首项 entry, 克隆)：entry 没换（回填没跑）就复用同一个克隆 ——
     * 对象身份稳定，二级面板点开后即使发生重渲染，[isMySubmenuOpen] 的同一性比对仍成立。
     */
    private fun obtainFoldClone(container: Any, srcEntry: Any, srcG: Any): Any? {
        foldCloneCache[container]?.let { (entry, clone) ->
            if (entry === srcEntry) return clone
        }
        val clone = runCatching {
            val gCls = srcG.javaClass
            val ctor = gCls.declaredConstructors.firstOrNull { it.parameterCount == 0 }
                ?: return@runCatching null
            ctor.isAccessible = true
            val g = ctor.newInstance()
            for (f in gCls.declaredFields) {
                f.isAccessible = true
                f.set(g, f.get(srcG))
            }
            g
        }.getOrElse {
            LogUtils.w(TAG, "obtainFoldClone", "克隆行模型失败：${it.message}")
            null
        }
        if (clone != null) {
            foldCloneCache[container] = srcEntry to clone
            LogUtils.i(TAG, "obtainFoldClone", "已新建折叠行（克隆首项行模型：generic 标签 + 可展开）")
        }
        return clone
    }

    /**
     * 合成只包折叠行的 `DeepShortcutEntry`：`(g, null, null, false)` —— 与宿主
     * `populateAndShow` :1886 构造占位深链条目同款（null wii/si 是宿主自己的用法）。
     * ready=false 无所谓：原生 onClick 已被压制段换掉，没人会读它。
     */
    private fun buildFoldEntry(foldG: Any): Any? = runCatching {
        val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_DEEP_ENTRY) ?: return@runCatching null
        val ctor = cls.declaredConstructors.firstOrNull { it.parameterCount == 4 }?.also {
            it.isAccessible = true
        } ?: return@runCatching null
        ctor.newInstance(foldG, null, null, false)
    }.getOrElse {
        LogUtils.w(TAG, "buildFoldEntry", "合成折叠行条目失败：${it.message}")
        null
    }

    /**
     * 桌面「更多功能」那张图（`SystemShortcut#getIconResId()` → `mIconResId`）。
     *
     * ⚠️ `getIconResId()` 声明在**父类** `SystemShortcut`（`MordFunctions` 没覆写，
     * dex 实证：`SystemShortcut#getIconResId()` 只 `iget mIconResId`）⇒ 只能 `callDeep`
     * （`callByName` 只查 declaredMethods，恒 null）。
     * 另：开了平铺后 MordFunctions 已被摘掉 ⇒ 用钩子 A 留存的实例。
     */
    private fun moreFunctionsIcon(container: Any): Drawable? = runCatching {
        val ctx = Reflect.getField(container, "mLauncher") as? Context ?: return null
        var sc = moreFunctionsShortcut
        if (sc == null) {
            val sys = Reflect.getField(container, "mSystemShortcuts") as? List<*>
            if (sys != null) {
                for (e in sys) {
                    if (e == null) continue
                    val s = Reflect.callByName(e, "getShortcut") ?: continue
                    if (s.javaClass.simpleName == SIMPLE_MORE) {
                        sc = s
                        break
                    }
                }
            }
        }
        if (sc == null) return null
        val resId = Reflect.callDeep(sc, "getIconResId") as? Int
        if (resId == null || resId == 0) return null
        ctx.getDrawable(resId)
    }.getOrNull()

    /**
     * 构造「压制启动」深链段：entries 原样复用（渲染/点击映射不变），
     * onClick 换成动态代理 —— 被点时不启动第一个深链，只确保二级面板分发已接管，
     * 让宿主 `b#h()` 接着跑展开检查 → `S()` 原生二级面板。
     *
     * ⚠️ 双 ClassLoader：Function2 必须用**桌面进程那份**（见 [CLS_FUNCTION2]）。
     */
    private fun buildSuppressSegment(container: Any, entries: List<*>?): Any? {
        if (entries == null) return null
        return runCatching {
            val segCls = Reflect.classOrNull(HookEnv.classLoader, CLS_SEGMENT) ?: return@runCatching null
            val ctor = segCls.declaredConstructors.firstOrNull { it.parameterCount == 2 }?.also {
                it.isAccessible = true
            } ?: return@runCatching null
            val fn2 = Reflect.classOrNull(HookEnv.classLoader, CLS_FUNCTION2) ?: return@runCatching null
            val onClick = Proxy.newProxyInstance(HookEnv.classLoader, arrayOf(fn2)) { _, method, _ ->
                when (method.name) {
                    "invoke" -> {
                        runCatching { ensureSubClickRouting(container) }
                            .onFailure { LogUtils.w(TAG, "foldClick", "接管二级面板分发失败：${it.message}") }
                        null
                    }
                    "toString" -> "$TAG: fold-suppress"
                    "hashCode" -> System.identityHashCode(container)
                    "equals" -> false
                    else -> null
                }
            }
            ctor.newInstance(entries, onClick)
        }.getOrElse {
            LogUtils.w(TAG, "buildSuppressSegment", "构造压制段失败：${it.message}")
            null
        }
    }

    // --------------------------------------- 原生二级面板 · 点击分发接管（字段换装）

    /** 取弹窗窗口（coui `com.coui.appcompat.poplist.b` 实例；`E`/`F`/`Q`/`n` 都在它身上） */
    private fun popupListWindow(container: Any): Any? = runCatching {
        Reflect.call(container, Reflect.findMethodDeep(container.javaClass, "getMPopupListWindow", 0))
    }.getOrNull()

    /**
     * 把弹窗窗口的 `F`（二级面板子项点击监听器）换成模块侧分发器。
     *
     * 时机：首次点折叠行（压制代理的 invoke 里）—— 此刻 `populateAndShow` 早已把原生
     * `F` 装好，换装前的它被存进 [subClickOrigF] 供原生面板委托。
     * 幂等：`F` 已是我们的分发器就直接返回。
     */
    private fun ensureSubClickRouting(container: Any) {
        val win = popupListWindow(container) ?: run {
            LogUtils.w(TAG, "ensureSubClickRouting", "取不到弹窗窗口，二级面板点击分发未接管")
            return
        }
        if (Reflect.getField(win, "F") === subClickRouter) return
        subClickOrigF = Reflect.getField(win, "F")
        Reflect.setField(win, "F", subClickRouter)
        LogUtils.i(TAG, "ensureSubClickRouting", "已接管二级面板点击分发（原生分发器委托保留）")
    }

    /**
     * 当前展开的二级面板是不是我们的折叠行：窗口 `Q`（展开位）指到的一级行模型
     * 与 [foldParentG] 是同一对象 ⇒ 是。
     *
     * 原生「更多功能」面板展开时 `Q` 指的是 MordFunctions 的行模型 ⇒ 否（委托原生）。
     *
     * ⚠️ 数据列表字段真名是 `n`（dexdump 实证；JADX 显示 f24250n 是花名，见 [rewriteSegments] 处的警告）。
     */
    private fun isMySubmenuOpen(win: Any): Boolean {
        val q = Reflect.getField(win, "Q") as? Int ?: return false
        if (q < 0) return false
        val data = Reflect.getField(win, "n") as? List<*> ?: return false
        val parentG = data.getOrNull(q) ?: return false
        return parentG === foldParentG
    }

    /** 关窗（与原生 `handleSubMenuItemClick` 一致：点完子项就关） */
    private fun closePopup(container: Any?) {
        if (container == null) return
        val m = Reflect.findMethodDeep(container.javaClass, "close", 1) ?: return
        Reflect.call(container, m, true)
    }

    // -------------------------------------------------- ③ currentVisibleDeepShortcutLimit

    /**
     * 钩子 C：`COUIPopupListWindowContainer#currentVisibleDeepShortcutLimit()` 后置。
     *
     * 17 原生就有「深链可见数量」机制：`segments()` 里对 `mDeepShortcuts` 做
     * `take(limit)`，而子类覆写的 `currentVisibleDeepShortcutLimit()` 直接返回
     * `Integer.MAX_VALUE`（全部显示）。折叠只要把返回值改成 1，原生就只渲染第一个
     * 深链项（= 折叠行）—— 不碰任何 view，滚动/重绑全部由宿主自理。
     */
    private fun hookFoldLimit() {
        runCatching {
            val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_POPUP) ?: run {
                LogUtils.w(TAG, "install", "$CLS_POPUP 不存在，折叠不生效（其余功能不受影响）")
                return
            }
            val m = cls.declaredMethods.firstOrNull {
                it.name == "currentVisibleDeepShortcutLimit" &&
                    it.parameterCount == 0 &&
                    it.returnType == Int::class.javaPrimitiveType
            } ?: run {
                LogUtils.w(TAG, "install", "子类未声明 currentVisibleDeepShortcutLimit，折叠不生效")
                return
            }
            m.isAccessible = true
            HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val c = cfg
                    if (c == null || !c.enabled || !c.fold) return chain.proceed()
                    val deep = Reflect.getField(chain.thisObject, "mDeepShortcuts") as? List<*>
                    if (deep != null && deep.size > 1) {
                        LogUtils.i(TAG, "foldLimit", "折叠生效：深链 ${deep.size} 个 → 只显示 1 行（展开走原生二级面板）")
                        return 1
                    }
                    return chain.proceed()
                }
            })
            LogUtils.i(TAG, "install", "已挂 $CLS_POPUP#currentVisibleDeepShortcutLimit()（折叠 = 原生 limit）")
        }.onFailure { LogUtils.w(TAG, "install", "挂 currentVisibleDeepShortcutLimit 失败：${it.message}") }
    }
}

/**
 * 长按菜单定制的开关（App 设置页写入、hook 侧读取，两边同一份 remote prefs）。
 *
 * 两层门控（与上游 fkfluidcloud 一致）：
 * - 功能总开关 [KEY_ENABLED] 关闭 ⇒ 10 个细分开关一律不生效（值保留）；
 * - 批量隐藏 [KEY_HIDE_ALL] 开启 ⇒ 6 个按项开关按「全部隐藏」生效。
 *
 * [read] 由钩子 A 每次弹窗读一次并快照，热路径钩子体不再碰 prefs。
 */
internal object LauncherMenuConfig {

    const val KEY_ENABLED = "launcher_menu_enabled"
    const val KEY_FLAT = "launcher_menu_flat"
    const val KEY_FOLD_DEEP = "launcher_menu_fold_deep"
    const val KEY_HIDE_DEEP = "launcher_menu_hide_deep"
    const val KEY_HIDE_ALL = "launcher_menu_hide_all"
    const val KEY_HIDE_PRIVACY_LOCK = "launcher_menu_hide_privacy_lock"
    const val KEY_HIDE_CLOSE_PRIVACY_LOCK = "launcher_menu_hide_close_privacy_lock"
    const val KEY_HIDE_APP_INFO = "launcher_menu_hide_app_info"
    const val KEY_HIDE_SHARE = "launcher_menu_hide_share"
    const val KEY_HIDE_EDIT = "launcher_menu_hide_edit"
    const val KEY_HIDE_SERVICE_CARD = "launcher_menu_hide_service_card"

    /** 每次弹窗的开关快照 */
    class MenuCfg(
        val enabled: Boolean,
        val flat: Boolean,
        val fold: Boolean,
        val hideDeep: Boolean,
        /** 6 个按项隐藏开关，顺序与 [LauncherMenuHook] 的 6 个类一致 */
        val hideFlags: BooleanArray,
    ) {
        /** 6 项任一开启（或批量隐藏） */
        val hideAny: Boolean get() = hideFlags.any { it }
    }

    /** 总开关关闭的快照（钩子体见到它直接放行） */
    private val OFF = MenuCfg(false, false, false, false, BooleanArray(6))

    /**
     * 一次性读全部开关。remote prefs 是内存镜像（无 IO）；拿不到时按「关闭」处理 ——
     * 功能不生效，但绝不因为读不到配置就把桌面菜单改坏。
     */
    fun read(): MenuCfg {
        val enabled = HookEnv.settingBoolean(KEY_ENABLED, false)
        if (!enabled) return OFF
        val hideAll = HookEnv.settingBoolean(KEY_HIDE_ALL, false)
        val flags = if (hideAll) {
            BooleanArray(6) { true }
        } else {
            booleanArrayOf(
                HookEnv.settingBoolean(KEY_HIDE_PRIVACY_LOCK, false),
                HookEnv.settingBoolean(KEY_HIDE_CLOSE_PRIVACY_LOCK, false),
                HookEnv.settingBoolean(KEY_HIDE_APP_INFO, false),
                HookEnv.settingBoolean(KEY_HIDE_SHARE, false),
                HookEnv.settingBoolean(KEY_HIDE_EDIT, false),
                HookEnv.settingBoolean(KEY_HIDE_SERVICE_CARD, false),
            )
        }
        return MenuCfg(
            enabled = true,
            flat = HookEnv.settingBoolean(KEY_FLAT, false),
            fold = HookEnv.settingBoolean(KEY_FOLD_DEEP, false),
            hideDeep = HookEnv.settingBoolean(KEY_HIDE_DEEP, false),
            hideFlags = flags,
        )
    }
}
