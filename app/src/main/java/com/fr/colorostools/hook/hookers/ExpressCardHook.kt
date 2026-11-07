package com.fr.colorostools.hook.hookers

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import com.fr.colorostools.hook.HookEnv
import com.fr.colorostools.ref.Reflect
import com.fr.colorostools.utils.LogUtils
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.net.URI
import java.net.URLDecoder
import java.util.Locale
import java.util.WeakHashMap

/**
 * 功能：**快递不跳小程序** —— 快递卡片直接在快应用内查询，不再跳到微信/支付宝/淘宝等小程序。
 *
 * 移植来源：ONextBox（GPL-3.0）`lsp/ExpressCardHooker.kt` + `lsp/ExpressCardRules.kt` +
 * `lsp/ExpressQuerySession.kt`，按其原有逻辑重写为本地模块的 hook 风格。
 *
 * ## 开关语义（与上游一致：关着就不装钩子）
 *
 * 上游 `ExpressCardHooker.hook()` 的第一行是
 * `if (packageName !in packages || (!enabled() && restored == null)) return` ——
 * **开关关闭时一个钩子都不挂**，进程里零开销。这里原样保留这个语义。
 *
 * 钩子内部仍留一次 `enabled()` 判断（上游同样保留）：应对「装上以后又把开关关掉」，
 * 那次调用立即回退到原生行为。
 *
 * ⚠️ 与上游的唯一差别不在钩子侧，而在**热重载**：上游有整套 `HotReloadLifecycle`，
 * 开关改动后由框架重载模块、重新跑一遍 `hook()`；本模块没有实现热重载
 * （`module.prop` 虽声明 `autoHotReload=true`，但未覆写 `onHotReloading`，
 * 框架默认拒绝重载），所以**改完开关要重启宿主应用**才生效 ——
 * 功能页右上角有「重启作用域应用」按钮可直接重启三个宿主。
 *
 * ## 问题链路
 *
 * 快递卡片（流体云 / 小布建议 / 负一屏）点开后，快应用运行时会带着 `hap://app/...` 目的地
 * 跳转到宿主 App：可能是另一个快应用（查快递的小程序版），也可能是微信 SDK 的卡片跳转。
 * 这些跳转在逻辑上属于「同一个快递查询」，但每一步都要重新拉起第三方，体验断裂。
 *
 * ## 三个宿主、各管一段
 *
 * | 宿主 | 挂什么 | 作用 |
 * |---|---|---|
 * | `com.nearme.instant.platform`（快应用服务） | 全部 | Intent 改写 + hapjs 包探测屏蔽 + 搜索框自动填单号并回车 + 键盘抑制 + 微信 SDK 拦截 |
 * | `com.android.launcher`（系统桌面） | startActivity 改写 | 从卡片/浮层点进来时把目的地换成应用内快递页 |
 * | `com.coloros.assistantscreen`（小布建议） | startActivity 改写 | 同上 |
 *
 * 只有平台进程带 hapjs 运行时（`org.hapjs.*`），所以包探测、搜索框、键盘三组只在它里面挂。
 *
 * ## 目的地改写规则（`ExpressCardRules.redirect`）
 *
 * 目标永远是 `hap://app/com.nearme.quickapp.express/pages/expressList`
 * （拿到单号时是 `.../expressSearch?expressNo=...&waybillno=...`），并且：
 * - 平台进程内：保留原 Intent 形状，只换 `data` 并**把包名钉在平台自己**（`setPackage`），
 *   等于「让快应用运行时自己处理这个 hap 深链」，不经过外部 App；
 * - 桌面 / 小布进程内：改成 `ACTION_VIEW` + 平台包名 + `NEW_TASK`，把用户送回平台。
 *
 * ## 关于「自动查快递」
 *
 * 改写只是把用户送到快递查询页；卡片本身带着单号，但页面打开时输入框是空的。
 * 所以平台进程里额外做两件事：
 *
 * 1. `View#onAttachedToWindow` 命中 `EditText` 时，若会话里有待填单号，
 *    就等到输入框可见可用、且内容确实等于单号时**派发一次 ENTER**，并收起键盘；
 * 2. `InputMethodManager#showSoftInput` / `toggleSoftInput` 在会话抑制期内直接吞掉
 *    —— 单号是代码自己填的，弹键盘只会挡住结果。
 *
 * 会话（[ExpressQuerySession]）只在**页面确实是快递页**时才算数，且带 6 秒 deadline：
 * 超过就放弃，避免用户后来手动打开任意快应用时被莫名回车。
 *
 * ## 覆盖边界
 *
 * - 只处理 `hap://` scheme、host=`app`、路径首段是 `com.nearme.quickapp.express` 的深链；
 * - 微信 SDK 只拦「卡片场景」（`sceneid=card` 或在卡片 SDK 栈里）且带单号/快递包名的请求，
 *   登录、支付、分享一律放行；
 * - 包探测屏蔽只影响**快递页上下文内**（`inExpressPage`）对七个电商/快递 App 的探测，
 *   页面外仍如实返回 —— 否则会把整个快应用运行时的包探测搞坏。
 */
internal object ExpressCardHook {

    private const val TAG = "ExpressCard"

    /** 「快递不跳小程序」开关 */
    const val KEY_NO_MINI_PROGRAM = "express_no_mini_program"

    // ------------------------------------------------------------ 常量

    const val PLATFORM = HookEnv.PKG_QUICK_APP
    const val LAUNCHER = HookEnv.PKG_LAUNCHER
    const val ASSISTANT = HookEnv.PKG_ASSISTANT_SCREEN
    const val EXPRESS = "com.nearme.quickapp.express"

    /** 本功能覆盖的三个宿主 */
    val packages = setOf(PLATFORM, LAUNCHER, ASSISTANT)

    /** 快应用运行时（hapjs）的分发 Activity：卡片深链先进这里 */
    private const val DISPATCHER =
        "com.nearme.instant.platform.dispatch.activity.HapDispatcherActivity"

    private const val BASE = "hap://app/$EXPRESS/pages/"

    /** 单号在 URL 里的私有标记（只在本模块内部流转，不会被宿主理解） */
    private const val MARKER = "__nomini"

    /** 卡片 Intent 里可能出现单号的 extra key */
    private val callerKeys = setOf("HAP_PACKAGE", "EXTRA_CALLING_QUICK_PKG", "EXTRA_CALLING_PKG")

    /**
     * 快递页内要「装作未安装」的 App。
     *
     * 页面用这些包做「有没有装对应快递/电商 App」的探测来决定是否外跳；
     * 屏蔽后它只能留在快应用内查询。
     */
    private val hiddenPackages = setOf(
        "com.tencent.mm", "com.eg.android.AlipayGphone", "com.taobao.taobao",
        "com.cainiao.wireless", "com.jingdong.app.mall", "com.sf.activity", "com.fcbox.hiveconsumer",
    )

    /** 单号形状：纯数字 10~24 位，或「两字母 + 9~20 位数字」（顺丰/京东等） */
    private val waybill = Regex("(?:[0-9]{10,24}|[A-Za-z]{2}[0-9]{9,20})")

    /** extra key 里暗示「这是单号」的片段 */
    private val waybillHints = listOf("waybill", "express", "mail", "tracking", "billno", "bill_no", "kuaidi")

    /** 微信卡片消息里以 `key=value` 形式出现的单号 */
    private val waybillInText = Regex(
        "(?:expressNo|mailNo|waybillNo|trackingNo|billNo)=([A-Za-z0-9]{10,24})(?=[&#\\s]|$)",
        RegexOption.IGNORE_CASE,
    )

    /** 微信 `sendReq` 请求对象里可能带路径的字段 */
    private val requestFields = listOf("path", "extData", "messageExt", "extInfo")

    // ------------------------------------------------------------ 进程内会话

    /** 待自动提交的查询（页面已打开、单号已知、等待输入框就绪） */
    private data class Pending(val number: String, val generation: Long, val deadline: Long)

    private val query = ExpressQuerySession()

    // ------------------------------------------------------------ 安装

    fun install(pkg: String) {
        if (pkg !in packages) return
        // 与上游一致：开关关闭时一个钩子都不装（上游 hook() 首行的 !enabled() 判断）。
        // 代价是改开关后要重启宿主应用；收益是关着的时候宿主进程里零钩子、零判断开销。
        if (!enabled()) {
            LogUtils.i(TAG, "install", "开关关闭，不安装钩子（宿主 $pkg）")
            return
        }
        hookActivityStarts()
        if (pkg == PLATFORM) {
            hookActivityQueries()
            hookDispatcher()
            hookPackageQueries()
            hookWechat()
            hookSearchInput()
            hookKeyboard()
        }
        LogUtils.i(TAG, "install", "快递不跳小程序已装（宿主 $pkg）")
    }

    private fun enabled(): Boolean = HookEnv.settingBoolean(KEY_NO_MINI_PROGRAM, false)

    // ------------------------------------------------------------ startActivity 改写

    /**
     * 拦截 `ContextWrapper` / `Activity` 的 `startActivity(ForResult)`。
     *
     * 两个类都挂：`Activity` 覆写了 `ContextWrapper` 的同名方法，只挂一层会漏掉另一层调用点。
     */
    private fun hookActivityStarts() {
        for (className in listOf(ContextWrapper::class.java.name, Activity::class.java.name)) {
            installMethods(className, { method ->
                method.name in setOf("startActivity", "startActivityForResult") &&
                    method.returnType == Void.TYPE &&
                    method.parameterCount in 1..3 &&
                    method.parameterTypes.firstOrNull() == Intent::class.java
            }) { chain ->
                if (!enabled()) return@installMethods chain.proceed()
                val intent = chain.args.firstOrNull() as? Intent ?: return@installMethods chain.proceed()
                val replacement = rewrite(intent) ?: return@installMethods chain.proceed()
                chain.proceed(arrayOf<Any?>(replacement))
            }
        }
    }

    /**
     * 把快递相关 Intent 改写成「在快应用内查快递」。
     *
     * @return 改写后的 Intent；null = 不改写，调用方原样放行
     */
    private fun rewrite(intent: Intent): Intent? = runCatching {
        val destination = redirect(intent.action, intent.dataString, stringExtras(intent))
            ?: return null
        val current = HookEnv.currentPkg
        if (current == PLATFORM) {
            // 平台进程内：保留原 Intent 形状（action / extras / flags），只换 data 并钉住包名，
            // 让快应用运行时自己处理这个 hap 深链 —— 不经过任何外部 App。
            val replacement = Intent(intent).apply {
                component = null
                selector = null
                data = Uri.parse(destination)
                setPackage(PLATFORM)
            }
            query.receive(destination, SystemClock.uptimeMillis())
            replacement
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse(destination))
                .setPackage(PLATFORM)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }.getOrNull()

    /** 卡片分发 Activity：`onCreate` 取 `activity.intent`，`onNewIntent` 取参数 0 */
    private fun hookDispatcher() {
        installMethods(DISPATCHER, { method ->
            (method.name == "onCreate" &&
                method.parameterTypes.contentEquals(arrayOf(Bundle::class.java))) ||
                (method.name == "onNewIntent" &&
                    method.parameterTypes.contentEquals(arrayOf(Intent::class.java)))
        }) { chain ->
            if (!enabled()) return@installMethods chain.proceed()
            val activity = chain.thisObject as? Activity ?: return@installMethods chain.proceed()
            val incoming = if (chain.executable.name == "onCreate") {
                activity.intent
            } else {
                chain.args.firstOrNull() as? Intent
            }
            val intent = incoming?.let { rewrite(it) ?: it }
            // 无论是否改写都要吃一次会话：原生快递页也可能是直达的
            query.receive(intent?.dataString, SystemClock.uptimeMillis())
            if (intent != null) activity.intent = intent
            if (chain.executable.name == "onNewIntent" && intent != null) {
                chain.proceed(arrayOf<Any?>(intent))
            } else {
                chain.proceed()
            }
        }
    }

    /**
     * 所有 Activity 的 `onCreate` / `onNewIntent`：只为记录「当前页面是不是快递页」。
     *
     * 会话的 `inExpress` 全靠这里刷新 —— 用户离开快递页后，包探测屏蔽与键盘抑制必须立刻失效。
     */
    private fun hookActivityQueries() {
        installMethods(Activity::class.java.name, { method ->
            (method.name == "onCreate" &&
                method.parameterTypes.contentEquals(arrayOf(Bundle::class.java))) ||
                (method.name == "onNewIntent" &&
                    method.parameterTypes.contentEquals(arrayOf(Intent::class.java)))
        }) { chain ->
            if (enabled()) {
                runCatching {
                    val activity = chain.thisObject as? Activity
                    val intent = if (chain.executable.name == "onCreate") {
                        activity?.intent
                    } else {
                        chain.args.firstOrNull() as? Intent
                    }
                    query.receive(intent?.dataString, SystemClock.uptimeMillis())
                }
            }
            chain.proceed()
        }
    }

    // ------------------------------------------------------------ hapjs 包探测屏蔽

    /**
     * 让快递页「以为」那七个电商/快递 App 都没装。
     *
     * 三个探测点覆盖 hapjs 的三种问法：
     * - `DefaultNativePackageProviderImpl#hasPackageInstalled` —— 单包查询；
     * - `DefaultNativePackageProviderImpl#packagesInstalled` —— 批量查询（返回 list，需过滤出新 list）；
     * - `PackageUtils#getPackageInfo` —— 要 PackageInfo 时返回 null。
     *
     * 全部要求 `inExpressPage` 为真：页面外必须如实返回，否则会把整个快应用运行时的包探测搞坏。
     */
    private fun hookPackageQueries() {
        installMethods("org.hapjs.pm.DefaultNativePackageProviderImpl", { method ->
            method.name == "hasPackageInstalled" && method.returnType == Boolean::class.javaPrimitiveType &&
                method.parameterCount == 2 && String::class.java in method.parameterTypes
        }) { chain ->
            val name = chain.args.firstOrNull { it is String } as? String
            if (enabled() && inExpressPage(chain) && name in hiddenPackages) false else chain.proceed()
        }

        installMethods("org.hapjs.pm.DefaultNativePackageProviderImpl", { method ->
            method.name == "packagesInstalled" && method.parameterCount == 2 &&
                (List::class.java.isAssignableFrom(method.returnType) ||
                    method.returnType == Any::class.java)
        }) { chain ->
            val result = chain.proceed()
            if (!enabled() || !inExpressPage(chain) || result !is List<*>) return@installMethods result
            // 宿主可能返回 unmodifiable 集合 ⇒ 一律新建，不原地删
            if (result.none { it is String && it in hiddenPackages }) return@installMethods result
            result.filterNot { it is String && it in hiddenPackages }
        }

        installMethods("org.hapjs.common.utils.PackageUtils", { method ->
            method.name == "getPackageInfo" && method.returnType == PackageInfo::class.java &&
                method.parameterCount == 3 && String::class.java in method.parameterTypes
        }) { chain ->
            val name = chain.args.firstOrNull { it is String } as? String
            if (enabled() && inExpressPage(chain) && name in hiddenPackages) null else chain.proceed()
        }
    }

    /**
     * 当前调用是否发生在快递页上下文里。
     *
     * 先沿 `ContextWrapper.baseContext` 往上找 `Activity`（最多 8 层，防环），
     * 找到就用它的 `intent.dataString` 判定 —— 这是最准的；找不到才退回会话缓存的 `inExpress`。
     */
    private fun inExpressPage(chain: XposedInterface.Chain): Boolean {
        var context = chain.args.firstOrNull { it is Context } as? Context
        repeat(8) {
            if (context is Activity) return isExpressUri(context.intent?.dataString)
            val wrapper = context as? ContextWrapper ?: return query.inExpress
            val base = wrapper.baseContext
            if (base === context) return query.inExpress
            context = base
        }
        return query.inExpress
    }

    // ------------------------------------------------------------ 微信卡片

    /**
     * 微信 SDK 的卡片跳转。
     *
     * 只拦「卡片场景」并且能从请求体里解析出单号的请求；登录 / 支付 / 分享全部放行
     * （它们是同一个 `sendReq`，靠 `sceneid=card`、卡片 SDK 调用栈与单号三者区分）。
     */
    private fun hookWechat() {
        for (className in listOf(
            "com.tencent.mm.opensdk.openapi.BaseWXApiImplV10",
            "com.tencent.mm.opensdk.openapi.WXApiImplV10",
        )) {
            installMethods(className, { method ->
                method.name == "sendReq" &&
                    method.returnType == Boolean::class.javaPrimitiveType &&
                    method.parameterCount == 1
            }) { chain ->
                if (!enabled()) return@installMethods chain.proceed()
                val request = chain.args.firstOrNull() ?: return@installMethods chain.proceed()
                val fields = requestFields.mapNotNull { name ->
                    (runCatching { Reflect.getField(request, name) }.getOrNull() as? String)
                        ?.let { name to it }
                }.toMap()
                // 卡片 SDK 的调用栈特征：请求不是用户主动发起的分享/登录
                val fromCard = Throwable().stackTrace.any {
                    it.className.contains("hapjs.card") ||
                        it.className.contains("oplus.card") ||
                        it.className.contains("pantanal")
                }
                val destination = wechatDestination(fields, fromCard)
                    ?: return@installMethods chain.proceed()
                val context = HookEnv.context() ?: return@installMethods chain.proceed()
                val redirected = runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(destination))
                            .setPackage(PLATFORM)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }.isSuccess
                // 已经自己启动过一次 ⇒ 吞掉 SDK 的原始请求，否则会同时拉起微信
                if (redirected) false else chain.proceed()
            }
        }
    }

    // ------------------------------------------------------------ 自动填单号 + 回车

    /**
     * 输入框上屏后自动填单号并回车。
     *
     * 触发点选 `View#onAttachedToWindow`：快递搜索页的 `EditText` 一定是刚 attach 的，
     * 而 attach 之后宿主的初始化（清空、设 hint）才跑完 —— 这里只「排队」，真正的填写在
     * [scheduleSearch] 的轮询里，等 `input.text` 真的等于单号才回车。
     */
    private fun hookSearchInput() {
        installMethods(View::class.java.name, {
            it.name == "onAttachedToWindow" && it.parameterCount == 0
        }) { chain ->
            val result = chain.proceed()
            if (!enabled()) return@installMethods result
            val input = chain.thisObject as? EditText ?: return@installMethods result
            val pending = query.pending(SystemClock.uptimeMillis()) ?: return@installMethods result
            scheduleSearch(input, pending)
            result
        }
    }

    /** 每个 EditText 记一次已排队 + 一个轮询 Runnable（WeakHashMap：视图销毁即释放） */
    private val scheduledInputs = WeakHashMap<EditText, Long>()
    private val callbacks = WeakHashMap<EditText, Runnable>()

    private fun scheduleSearch(input: EditText, pending: Pending) {
        // 同一代会话不重复排队
        if (scheduledInputs[input] == pending.generation) return
        scheduledInputs[input] = pending.generation
        callbacks.remove(input)?.let(input::removeCallbacks)

        val callback = object : Runnable {
            override fun run() {
                val now = SystemClock.uptimeMillis()
                if (!enabled() || !query.matches(pending, now) || !input.isAttachedToWindow) {
                    callbacks.remove(input)
                    return
                }
                query.suppressIme(pending, now)
                if (input.isShown && input.isEnabled && input.text?.toString() == pending.number) {
                    runCatching {
                        input.dispatchKeyEvent(
                            KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0),
                        )
                        input.dispatchKeyEvent(
                            KeyEvent(now, now + 40, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0),
                        )
                        (input.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                            ?.hideSoftInputFromWindow(input.windowToken, 0)
                    }.onFailure {
                        LogUtils.w(TAG, "search", "提交快递查询失败：${it.javaClass.simpleName}: ${it.message}")
                    }
                    query.complete(pending, now)
                    callbacks.remove(input)
                    return
                }
                input.postDelayed(this, 100L)
            }
        }
        callbacks[input] = callback
        input.postDelayed(callback, 20L)
    }

    /**
     * 抑制软键盘弹出。
     *
     * 单号是代码填的，键盘弹出来只会挡住查询结果，还会让人以为需要手动输入。
     * 只在会话的抑制期内生效（进入快递搜索页后 ~2 秒），范围极小。
     */
    private fun hookKeyboard() {
        installMethods(InputMethodManager::class.java.name, { method ->
            val show = method.name == "showSoftInput" &&
                method.parameterTypes.firstOrNull() == View::class.java
            val toggle = method.name == "toggleSoftInput" ||
                method.name == "toggleSoftInputFromWindow"
            (show || toggle) &&
                method.returnType in setOf(Boolean::class.javaPrimitiveType, Void.TYPE)
        }) { chain ->
            if (enabled() && query.imeSuppressed(SystemClock.uptimeMillis())) {
                // boolean 返回 false = 没弹；void 返回 null = 什么都不做
                val isBoolean = (chain.executable as? Method)?.returnType == Boolean::class.javaPrimitiveType
                if (isBoolean) false else null
            } else {
                chain.proceed()
            }
        }
    }

    // ------------------------------------------------------------ 规则

    /**
     * 判定并生成改写目的地；null = 不该改。
     *
     * 分两路：
     * - **非平台宿主**（桌面 / 小布）：只认「快递卡片传进来的」Intent ——
     *   extra 里带 `HAP_PACKAGE=com.nearme.quickapp.express` 之类，或 `EXTRA_FROM` 含该包名，
     *   否则一律不动（桌面里其它 startActivity 千千万，绝不能误伤）；
     * - **平台宿主**：只认 `VIEW` + `hap://app/...` 且能解析出单号的深链 ——
     *   没有单号的普通 hap 跳转保持原生（那些是正常的快应用间跳转）。
     */
    private fun redirect(action: String?, data: String?, extras: Map<String, String>): String? {
        val current = HookEnv.currentPkg ?: return null
        if (current !in packages) return null
        val uri = uri(data)
        // 已经是本模块自己的快递页 ⇒ 不再改（防递归）
        if (uri != null && isExpressUri(uri)) return null

        if (current != PLATFORM) {
            val fromExpress = callerKeys.any { extras[it] == EXPRESS } ||
                extras["EXTRA_FROM"]?.contains(EXPRESS) == true
            if (!fromExpress) return null
            val parameters = uri?.let(::queryParams).orEmpty()
            return destination(findTracking(parameters) ?: findTracking(extras))
        }

        if (action != Intent.ACTION_VIEW || uri == null) return null
        if (uri.scheme != "hap" || uri.host != "app") return null
        if (uri.path.isNullOrEmpty() || uri.path == "/") return null
        val parameters = queryParams(uri)
        val tracking = trackingNumber(parameters["waybillno"])
            ?: trackingNumber(parameters["expressNo"])
            ?: return null
        return destination(tracking)
    }

    /** 微信卡片请求 → 快递页目的地；null = 不是卡片，放行 */
    private fun wechatDestination(fields: Map<String, String>, fromCardSdk: Boolean): String? {
        val values = fields.values
        val cardRequest = fromCardSdk || values.any { it.contains("sceneid=card", ignoreCase = true) }
        if (!cardRequest) return null
        val tracking = values.firstNotNullOfOrNull { text ->
            waybillInText.findAll(text).firstNotNullOfOrNull { trackingNumber(it.groupValues[1]) }
        }
        // 卡片里既没单号也不含快递包名 ⇒ 是别的卡片（登录 / 支付 / 分享），必须放行
        if (tracking == null && values.none { it.contains(EXPRESS) }) return null
        return destination(tracking)
    }

    /** 有单号 → 直达搜索页；没单号 → 快递列表页 */
    private fun destination(number: String?): String {
        val tracking = trackingNumber(number) ?: return "${BASE}expressList"
        return "${BASE}expressSearch?expressNo=$tracking&waybillno=$tracking&$MARKER=$tracking"
    }

    /**
     * 从一堆 key/value 里挑出像单号的那个。
     *
     * key 名必须命中 [waybillHints]（waybill / express / mail / tracking / billno / kuaidi），
     * 避免把 `orderId`、`phone` 之类纯数字串当成单号。
     */
    private fun findTracking(values: Map<String, String>): String? =
        values.entries.firstNotNullOfOrNull { (key, value) ->
            val name = key.lowercase(Locale.ROOT)
            if (waybillHints.any(name::contains)) trackingNumber(value) else null
        }

    private fun trackingNumber(value: String?): String? = value?.trim()?.takeIf(waybill::matches)

    private fun isExpressUri(text: String?): Boolean = uri(text)?.let(::isExpressUri) == true

    private fun isExpressUri(uri: URI): Boolean =
        uri.scheme == "hap" && uri.host == "app" &&
            uri.path?.split('/')?.getOrNull(1) == EXPRESS

    private fun uri(text: String?): URI? =
        text?.let { runCatching { URI(it) }.getOrNull() }

    private fun queryParams(uri: URI): Map<String, String> =
        uri.rawQuery.orEmpty().split('&').mapNotNull { part ->
            val separator = part.indexOf('=')
            if (separator < 0) return@mapNotNull null
            runCatching {
                URLDecoder.decode(part.take(separator), "UTF-8") to
                    URLDecoder.decode(part.substring(separator + 1), "UTF-8")
            }.getOrNull()
        }.toMap()

    @Suppress("DEPRECATION")
    private fun stringExtras(intent: Intent): Map<String, String> = runCatching {
        val extras = intent.extras ?: return emptyMap()
        extras.keySet().mapNotNull { key ->
            (extras.get(key) as? String)?.let { key to it }
        }.toMap()
    }.getOrDefault(emptyMap())

    // ------------------------------------------------------------ 挂钩子

    /**
     * 在宿主类 [className] 的全部 `declaredMethods` 里按 [matches] 挂钩子。
     *
     * 类不存在只记一行 W（不同快应用引擎版本可能没有某组类），不影响其它组。
     */
    private fun installMethods(
        className: String,
        matches: (Method) -> Boolean,
        intercept: (XposedInterface.Chain) -> Any?,
    ) {
        val target = Reflect.classOrNull(HookEnv.classLoader, className)
        if (target == null) {
            LogUtils.w(TAG, "install", "$className 不存在，该组跳过")
            return
        }
        var hooked = 0
        target.declaredMethods.filter(matches).forEach { method ->
            runCatching {
                method.isAccessible = true
                HookEnv.module.hook(method).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? = intercept(chain)
                })
                hooked++
            }.onFailure {
                LogUtils.w(
                    TAG, "install", "挂 $className#${method.name} 失败：" +
                        "${it.javaClass.simpleName}: ${it.message}",
                )
            }
        }
        if (hooked == 0) {
            LogUtils.w(TAG, "install", "$className 里没有匹配方法，该组跳过")
        }
    }

    // ------------------------------------------------------------ 会话

    /**
     * 快递查询会话：记录「当前在不在快递页」与「待自动提交的单号」。
     *
     * 6 秒 deadline 是必须的 —— 用户可能只是想看看快递列表，或者页面加载慢；
     * 超时后自动提交就得放弃，否则会在用户后来打开别的快应用时莫名回车。
     * 键盘抑制期更短（2 秒），只覆盖「页面刚打开、输入框刚 attach」这一段。
     */
    private class ExpressQuerySession {

        @Volatile
        var inExpress: Boolean = false
            private set

        @Volatile
        private var pending: Pending? = null

        @Volatile
        private var suppressImeUntil = 0L

        private var generation = 0L

        @Synchronized
        fun receive(data: String?, now: Long) {
            inExpress = isExpressUri(data)
            val number = pendingTrackingNumber(data)
            generation++
            pending = number?.let { Pending(it, generation, now + 6_000L) }
            suppressImeUntil = if (number != null) now + 2_000L else 0L
        }

        fun pending(now: Long): Pending? = pending?.takeIf { now < it.deadline && inExpress }

        fun matches(request: Pending, now: Long): Boolean = pending(now)?.generation == request.generation

        @Synchronized
        fun suppressIme(request: Pending, now: Long) {
            if (matches(request, now)) suppressImeUntil = minOf(now + 2_000L, request.deadline)
        }

        @Synchronized
        fun complete(request: Pending, now: Long) {
            if (!matches(request, now)) return
            pending = null
            suppressImeUntil = now + 20L
        }

        fun imeSuppressed(now: Long): Boolean = inExpress && now < suppressImeUntil

        /** 只有「快递搜索页 + 带我们自己的标记单号」才算待提交查询 */
        private fun pendingTrackingNumber(text: String?): String? {
            val uri = uri(text) ?: return null
            if (!isExpressUri(uri) || uri.path != "/$EXPRESS/pages/expressSearch") return null
            return trackingNumber(queryParams(uri)[MARKER])
        }
    }
}
