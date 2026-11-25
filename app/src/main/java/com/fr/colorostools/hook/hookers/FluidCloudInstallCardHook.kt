package com.fr.colorostools.hook.hookers

import android.content.Context
import com.fr.colorostools.hook.FluidCloudConfig
import com.fr.colorostools.hook.HookEnv
import com.fr.colorostools.ref.Reflect
import com.fr.colorostools.utils.LogUtils
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 功能三：拦截「复制链接后未安装应用」的安装提示流体云。
 *
 * 现象：复制一条指向未安装 App 的链接，ColorOS 会在胶囊/卡片区弹「安装“XX”查看」，
 * 按钮文案「安装」或「前往一加软件商店安装」。本功能只掐掉这一类卡片，
 * 其余流体云（音乐、导航、外卖、自定义规则卡等）一概不动。
 *
 * ## 为什么没有原生开关（4 个候选全部实测排除）
 * | 候选 | 实际用途 |
 * |---|---|
 * | `Settings.Secure.app_recommend_switch` | 小程序/应用推荐（[com.oplus.apprecommend.utils.AppRecommendSwitchStorage]） |
 * | `Settings.Secure.mini_program_switch_state` | 同上，小程序推荐 |
 * | `Settings.Secure.ums_allowed_apps`（`CardClientInitUtils.QUERY_UMS_SETTING_KEY`） | UMS 白名单 |
 * | `ABExperimentManager.getDownloadMode()` | **服务端下发**（唯一写入方 `CommandHandler#handleCommand` ← DTO `getDownloadPanel()`），且 `==1` 只是换成「下载面板」，不是抑制 |
 * ⇒ 只能 hook。
 *
 * ## 挂点选择（实测签名，从上游到下游的可行性梯队）
 * 1. `SeedlingSender#sendByCommand(Context, ServiceKouLingLinkVo, TextContent, boolean, Continuation)`
 *    —— 2972 行类里的 suspend 巨型协程，要复刻「遍历候选包判 isInstalled + 造卡」全流程，耦合最高。**弃**。
 * 2. `CardInfo.Companion#buildAppDownloadInfo(Context, ServiceKouLingLinkVo)` —— 只生成「前往商店」
 *    文案/图标，返回 null **不抑制卡**（下游 `install_card_title` / `install_card_context` 兜底）。**单独不够**。
 * 3. `Seedling.Builder#build` / `Seedling#show` —— 只覆盖 Seedling 通道，且 build 里已有曝光埋点。次优。
 * 4. **`CardManager#send(Context, MetisData)`** —— 所有流体云卡的全局唯一出口
 *    （全项目仅 `MetisData#send` 一处调用它，而 `MetisData#send` 又是唯一真正投递的动作）。
 *    `metisData.getCardInfo()` 结构化可读 ⇒ 判定后直接短路，卡**根本不会被构造**：
 *    不触发 `updateIntelligentDataCM` 的 IPC，也不会打 `onTriggerFluidCloud` 曝光埋点。**主方案**。
 *
 * ## 判定为什么不用硬编码文案
 * 参考实现（FK C17 的 `rewriteCardBusinessData`）要解析
 * `businessData → eventMessage → text → textContent/platform` **4 层嵌套 Json**，
 * 再用硬编码中文 `"去安装"/"安装"/"前往一加软件商店安装"/"商店安装"` 做 AND 匹配 ——
 * 文案一升级、Json 结构一调就静默失效。
 *
 * 这里改读 `CardInfo` 的结构化字段，并且**文案不做字面量**，而是运行时从宿主 resources
 * 取真正在用的字符串（`getIdentifier(basename, "string", "com.oplus.metis")`，
 * 兼容 basename 写法与完整 r 名写法）——多语言 / 跨版本只要求 string key 名不变，
 * 而 key 名比文案稳定得多。
 *
 * 三个命中条件（任一成立即拦）：
 * - **A `install_card_context`**：副标题 = 「安装后可在应用内打开」。这是**唯一**两个 `i==0`
 *   建卡路径（`SeedlingSender#sendByCommand` 与 `SeedlingSender#send`）都会写进来的字段，
 *   用于 `i3==0` 分支，且只在未安装时出现。**主判据**。
 * - **B `install_card_title` 模板**：卡片主标题 = 「安装“%1$s”查看」（模板串去掉 `%1$s`
 *   取前后缀比对）。注意 `getDownloadMode()==1` 时走「下载面板」分支不写这条，
 *   所以 A/B 必须 OR 而不是 AND。
 * - **C 按钮文案成对**：`buttonText == bt_text_install`（「安装」）
 *   或 `== bt_text_install_retry`（「重新安装」）。**必须成对判** ——
 *   已安装场景的按钮是 `bt_open`（「打开」），而 `SeedlingSender#send` 那条入口在
 *   「全都未安装」时**不会**写 `install_card_title`，只靠按钮区分 ⇒ C 单用虽偏宽，
 *   但与 B 成对后能覆盖这条入口。
 *
 * 「前往 OPPO/一加/Realme 软件商店安装」是 `AppDownloadInfo.buttonText`（按钮上的文案），
 * 落在 `CardInfo.buttonText` 里，同样被 C 覆盖。
 *
 * ## 安全性
 * - 每条判据都要求 `CardInfo` 非空、字段非空；全空 ⇒ 不动（宁漏不误伤）。
 * - `CardInfo` 的 getter 内部无 `h.j()` 非空断言（断言只在 setter 侧），可安全反射调用。
 * - 任何反射失败 / 异常都 `chain.proceed()` 放行原逻辑，最坏结果是「功能不生效」。
 * - 逻辑注入点**常驻安装 + 运行时门控**（[FluidCloudConfig.installCardBlockEnabled]），
 *   开关即时生效，无需重启宿主。
 *
 * ## 作用域
 * 只需 `com.oplus.metis` 一个包。Manifest 实测 `TextSeedlingCardWidgetNewProvider` /
 * `TextSeedlingCardWidgetProviderImpl` / `TextIntentActionProviderImpl` /
 * `TextIntentWorkerService` / `RouterActivity(Impl)` 全部 `android:process=":text_intent"`，
 * `Seedling` / `MetisData` / `CardManager` / `SeedlingSender` 也都在该进程；
 * Xposed 按包名授权天然覆盖同包所有进程，无需额外声明（SystemUI 只渲染已构造好的卡，不参与）。
 */
object FluidCloudInstallCardHook {

    private const val TAG = "FluidCloudInstallCard"

    private const val CLS_CARD_MANAGER = "com.oplus.textintent.pantanal.CardManager"
    private const val CLS_METIS_DATA = "com.oplus.textintent.pantanal.bean.MetisData"
    private const val CLS_CARD_INFO = "com.oplus.textintent.pantanal.bean.CardInfo"

    /** 宿主包名 —— `getIdentifier` 需要一个包名去解析 string key */
    private const val HOST_PKG = HookEnv.PKG_METIS

    // ---------------- 判据用到的宿主 string key ----------------
    private const val KEY_INSTALL_CONTEXT = "install_card_context"
    private const val KEY_INSTALL_TITLE = "install_card_title"
    private const val KEY_BT_INSTALL = "bt_text_install"
    private const val KEY_BT_INSTALL_RETRY = "bt_text_install_retry"

    /** `install_card_title` 的占位符（`"安装“%1$s”查看"` 里的 `%1$s`） */
    private const val TITLE_PLACEHOLDER = "%1\$s"

    /**
     * 宿主资源串缓存。宿主 resources 在进程内稳定，取值只做一次；
     * 用 [NO_CACHE] 表示「这个 key 在宿主里不存在」（避免每张卡都重复 getIdentifier）。
     */
    private const val NO_CACHE = "\u0000MISSING"

    private val cache = HashMap<String, String>()

    private val installed = AtomicBoolean(false)

    // ---------------------------------------------------------------- 安装

    fun install() {
        if (!installed.compareAndSet(false, true)) {
            LogUtils.i(TAG, "install", "已安装过，跳过")
            return
        }
        val loader = HookEnv.classLoader
        val cm = Reflect.classOrNull(loader, CLS_CARD_MANAGER)
        if (cm == null) {
            LogUtils.i(TAG, "install", "宿主无 $CLS_CARD_MANAGER（不是本卡管线版本），跳过")
            return
        }
        // send(Context, MetisData) —— 必须精确形状：同类里另有大量 updateDataCM / 属性方法
        val m = Reflect.findMethodByShape(
            cm, "send",
            listOf(Context::class.java, Reflect.classOrNull(loader, CLS_METIS_DATA)),
            Void.TYPE,
        )
        if (m == null) {
            LogUtils.e(TAG, "install", "$CLS_CARD_MANAGER#send(Context, MetisData)V 找不到，功能不可用", true)
            return
        }
        runCatching { m.isAccessible = true }
        LogUtils.i(
            TAG, "install",
            "注入点 ${m.declaringClass.name}#send(${m.parameterTypes.joinToString { it.simpleName }})" +
                " -> ${m.returnType.simpleName}"
        )
        HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                // 关着时零反射直接放行
                if (!FluidCloudConfig.installCardBlockEnabled()) return chain.proceed()
                // `chain.args` 是 `getArgs()` 的语法糖，而接口明确返回 **不可变副本**
                // （每次调用都新建 List）；`getArg(1)` 是单元素直取，热路径零分配。
                val blocked = runCatching { isInstallPromptCard(chain.getArg(1)) }
                    .onFailure { LogUtils.e(TAG, "send", "判定异常，放行原逻辑", true) }
                    .getOrDefault(false)
                if (!blocked) return chain.proceed()
                // 方法返回 void ⇒ 返回 null 等于「什么都不做」，卡不会被构造
                return null
            }
        })
    }

    // ---------------------------------------------------------------- 判定

    /**
     * 是不是「未安装应用的安装提示卡」。
     *
     * @param metisData 目标方法第 2 个实参（`MetisData`）；类型不匹配则返回 false 放行
     */
    private fun isInstallPromptCard(metisData: Any?): Boolean {
        if (metisData == null) return false
        // MetisData#getCardInfo() —— CardInfo 为 null 的是「退场/清理」包（isExited=true），不能拦
        val cardInfo = Reflect.callByName(metisData, "getCardInfo") ?: return false

        val ctx = HookEnv.context() ?: return false

        // ⚠️ 取值顺序 = **判据代价顺序**，命中即返回，不做无谓反射。
        // 每条 `callByName` 都是一次 ConcurrentHashMap 查找 + 一个缓存键对象分配，
        // 攒满 5 个（原来是无条件全取）在「每张流体云卡都要过一遍」的路径上没必要。
        // 顺序依据：A（副标题）是主判据、且 `install_card_context` 只为安装卡写入；
        // B（主标题模板）覆盖 downloadMode==1 分支；C（按钮）最宽、放最后兜底。

        // ---- A：副标题 = install_card_context（「安装后可在应用内打开」）
        val subTitle = getStringField(cardInfo, "getCardSubTitle")
        if (matchResource(ctx, subTitle, KEY_INSTALL_CONTEXT)) {
            return hit(getStringField(cardInfo, "getCardTitle"), subTitle, null, "A:install_card_context")
        }

        // ---- B：主标题命中 install_card_title 模板（「安装“%1\$s”查看」）
        val title = getStringField(cardInfo, "getCardTitle")
        if (matchTemplate(ctx, title, KEY_INSTALL_TITLE)) {
            return hit(title, subTitle, null, "B:install_card_title")
        }

        // ---- C：按钮文案是「安装」/「重新安装」（已安装时是「打开」bt_open，不会命中）
        val buttonText = getStringField(cardInfo, "getButtonText")
        if (isInstallButton(ctx, buttonText)) {
            return hit(title, subTitle, buttonText, "C:button_install")
        }

        // 三条都不命中 ⇒ 放行。原来还有「5 个字段全空则不动」的兜底判断：
        // 上面三条各自都对 null/空串短路（`isNullOrBlank`），全空必然全部不命中，
        // 结果等价，那段额外反射可以省掉。
        return false
    }

    private fun hit(title: String?, subTitle: String?, buttonText: String?, rule: String): Boolean {
        // 详细日志关着时 LogUtils.i 会直接丢弃，但**消息字符串在实参求值时就拼好了** ——
        // 先判开关，省掉每次拦截都要做的三次字符串拼接。
        if (LogUtils.isEnabled()) {
            LogUtils.i(
                TAG, "isInstallPromptCard",
                "拦截安装提示卡 [$rule] title=$title sub=$subTitle btn=$buttonText"
            )
        }
        return true
    }

    /** 整个字段等于宿主该 string key 的当前文案 */
    private fun matchResource(ctx: Context, value: String?, key: String): Boolean {
        if (value.isNullOrBlank()) return false
        val host = hostString(ctx, key) ?: return false
        return value == host
    }

    /**
     * 模板比对：宿主串形如 `安装“%1$s”查看`，取占位符前后缀，
     * 要求 [value] 以 `prefix` 开头、以 `suffix` 结尾且中间非空（= 确实带了应用名）。
     * 占位符不存在时退化为整串相等。
     */
    private fun matchTemplate(ctx: Context, value: String?, key: String): Boolean {
        if (value.isNullOrBlank()) return false
        val split = templateSplit(ctx, key) ?: return false
        val prefix = split.first
        val suffix = split.second
        if (prefix == null) {
            // 宿主串里没有占位符 ⇒ 退化为整串相等
            return value == suffix
        }
        if (!value.startsWith(prefix) || !value.endsWith(suffix)) return false
        // 前后缀之外必须有内容（避免 "安装“”查看" 这种空名误命中）
        return value.length > prefix.length + suffix.length
    }

    /**
     * `install_card_title` 按占位符切分的 `(前缀, 后缀)` 缓存。
     *
     * 三态约定：**外层 null** = 宿主串取不到（不判）；
     * `first == null` = 宿主串没有占位符（比对整串，`second` 即原文）。
     * 宿主 resources 进程内不变，切一次就够。
     */
    private var templateSplitCache: Pair<String?, String>? = null
    private var templateSplitLoaded = false

    private fun templateSplit(ctx: Context, key: String): Pair<String?, String>? {
        if (templateSplitLoaded) return templateSplitCache
        val host = hostString(ctx, key) ?: return null
        val at = host.indexOf(TITLE_PLACEHOLDER)
        val value = if (at < 0) {
            null to host
        } else {
            host.substring(0, at) to host.substring(at + TITLE_PLACEHOLDER.length)
        }
        templateSplitCache = value
        templateSplitLoaded = true
        return value
    }

    /** 按钮文案是「安装」或「重新安装」 */
    private fun isInstallButton(ctx: Context, buttonText: String?): Boolean {
        if (buttonText.isNullOrBlank()) return false
        val install = hostString(ctx, KEY_BT_INSTALL)
        if (install != null && buttonText == install) return true
        val retry = hostString(ctx, KEY_BT_INSTALL_RETRY)
        return retry != null && buttonText == retry
    }

    /**
     * 从宿主 resources 取 string 的当前文案（带进程内缓存）。
     *
     * `getIdentifier` 两种写法都试：先 basename 形态，再完整 `包名:string/key` 形态 ——
     * 后者是 ColorOS 静态 `R.string` 的实际资源名，前者依赖 aapt 的 basename 解析，
     * 两种在不同版本上谁生效并不固定，都试一遍最稳。
     */
    private fun hostString(ctx: Context, key: String): String? {
        cache[key]?.let { return if (it == NO_CACHE) null else it }
        val res = runCatching { ctx.resources }.getOrNull()
            ?: runCatching { ctx.applicationContext?.resources }.getOrNull()
        if (res == null) return null
        val id = runCatching {
            val direct = res.getIdentifier(key, "string", HOST_PKG)
            if (direct != 0) direct else res.getIdentifier("$HOST_PKG:string/$key", null, null)
        }.getOrNull() ?: 0
        if (id == 0) {
            cache[key] = NO_CACHE
            LogUtils.w(TAG, "hostString", "宿主资源里找不到 string/$key（宿主版本可能已改 key 名）")
            return null
        }
        val str = runCatching { res.getString(id) }.getOrNull()
        if (str.isNullOrEmpty()) {
            cache[key] = NO_CACHE
            return null
        }
        cache[key] = str
        return str
    }

    /** 读字符串 getter；不存在 / 异常 / 空串一律返回 null */
    private fun getStringField(target: Any, getter: String): String? =
        runCatching { Reflect.callByName(target, getter) as? String }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
}