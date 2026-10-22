package com.fr.colorostools.hook.hookers

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.fr.colorostools.hook.FluidCloudConfig
import com.fr.colorostools.hook.HookEnv
import com.fr.colorostools.hook.SeedlingRule
import com.fr.colorostools.ref.Reflect
import com.fr.colorostools.utils.LogUtils
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.util.regex.Pattern

/**
 * 功能二：添加自定义「剪切板」流体云。
 *
 * 原理（移植参考实现，按 ColorOS 17 实测收敛）：
 *   1. **让系统「认得出」** —— `TokenDetector#readTokens()` 跑完后，往它的
 *      `ruleList`（`ArrayList<RuleVO>`）里追加我们自己的 `RuleVO`。
 *      这样即便复制的是系统内置规则不认识的内容，识别环节也会通过。
 *   2. **自己「造卡片」** —— `Repository#obtainKouLingData(Context, TextContent, Continuation)`
 *      是「联网取卡片数据」那一步。在它执行前劫持：自己造一个 `ServiceKouLingLinkVo`，
 *      用 `continuation.resumeWith(vo)` 把结果直接喂回去，然后返回 `COROUTINE_SUSPENDED`
 *      伪装成「已挂起」，原方法体根本不会执行。
 *   3. **兜底合成** —— `ActionData#parse` 只按「在线检测响应」构建跳转，自定义规则在服务器
 *      没有卡数据 → 返回空 → `intentCheck=false` → 卡片不显示。在返回空、且最近
 *      [INJECT_TTL_MS] 内确实做过自定义 VO 注入时，用注入数据合成一条 TextIntent 塞回去。
 *
 * 相对参考实现的改进：
 *   - **readTokens 钩子加「ruleList 实例身份 + 规则 JSON 指纹」双判**：17 的 readTokens 本身
 *     有 `ruleList != null` 早退（每进程只真正加载一次），参考实现的钩子体却每次检测事件都
 *     重复注入（约 2N 次反射/次）；双判未变化时**零反射直接返回**，云端更新换 list 实例时
 *     又能自动重新注入，不丢规则。
 *   - **VO 不走宿主 Gson**：17 的 `ServiceKouLingLinkVo` 只有 24 参构造器（无无参构造），
 *     直接用 Unsafe 分配 + setter / 字段填充，少一个宿主动态依赖。
 *   - 兜底钩子也**常驻安装 + 运行时门控**（参考实现是安装时门控）：开启功能无需重启宿主。
 *   - 任何一步失败都 `chain.proceed()` 放行原逻辑，最坏情况是「功能不生效」而非崩溃。
 */
object FluidCloudSeedlingHook {

    private const val TAG = "FluidCloudSeedling"

    private const val CLS_TOKEN_DETECTOR = "com.oplus.ointent.token.TokenDetector"
    private const val CLS_EXPRESSION = "com.oplus.ointent.token.Expression"
    private const val CLS_RULE_VO = "com.oplus.ointent.token.RuleVO"
    private const val CLS_ACTION_DATA = "com.oplus.textintent.distribution.bean.ActionData"
    private const val CLS_TEXT_INTENT = "com.oplus.textintent.distribution.bean.TextIntent"
    private const val CLS_COROUTINE_SINGLETONS = "kotlin.coroutines.intrinsics.CoroutineSingletons"
    private const val CLS_CONTINUATION = "kotlin.coroutines.Continuation"

    private const val SIMPLE_TEXT_CONTENT = "TextContent"

    /** 一套「引擎」= 一组同包 DTO（新旧两套 ColorOS 大版本并存；17 只有旧引擎） */
    private data class Engine(
        val tag: String,
        val repoClass: String,
        val voClass: String,
        val platformClass: String,
        val cardIconClass: String,
    )

    private val ENGINES = listOf(
        Engine(
            tag = "textintent(旧)",
            repoClass = "com.oplus.textintent.detect.datasource.Repository",
            voClass = "com.oplus.textintent.network.domain.data.remote.dto.response.ServiceKouLingLinkVo",
            platformClass = "com.oplus.textintent.network.domain.data.remote.dto.response.PlatformBeen",
            cardIconClass = "com.oplus.textintent.network.domain.data.remote.dto.response.CardIcon",
        ),
        Engine(
            tag = "direct(新)",
            repoClass = "com.coloros.direct.command.datasource.Repository",
            voClass = "com.coloros.direct.command.network.bean.response.ServiceKouLingLinkVo",
            platformClass = "com.coloros.direct.command.network.bean.response.PlatformBeen",
            cardIconClass = "com.coloros.direct.command.network.bean.response.CardIcon",
        ),
    )

    private val URL_RE = Pattern.compile("https?://[^\\s\"'<>]+")

    // -------------------------------------------------- 最近一次注入（供 ActionData.parse 兜底合成）

    /** 一次成功 VO 注入的关键数据，用于在 ActionData.parse 返回空时合成 TextIntent */
    private class Injected(
        val deeplink: String,
        val pkg: String,
        val appName: String,
        val icon: String,
        val platformType: Int,
        val rawText: String,
        val at: Long,
    )

    /** 同进程内存标记：注入与 parse 都发生在 text_intent 决策进程内 */
    @Volatile
    private var lastInject: Injected? = null

    /** 注入记录的有效窗口（毫秒）：超过视为过期，不再合成 */
    private const val INJECT_TTL_MS = 10_000L

    // -------------------------------------------------- readTokens 注入的「未变化」双判

    /** 上次完成注入时的 ruleList 实例（云端 updateNewRules 会换新实例 → 自动重新注入） */
    @Volatile
    private var lastListIdentity: Any? = null

    /** 上次完成注入时的规则 JSON 原文（改规则 → 指纹变化 → 重新注入） */
    @Volatile
    private var lastFingerprint: String? = null

    // ------------------------------------------------------------------ 安装

    fun install() {
        var ok = 0
        var total = 0

        // ---- 逻辑注入点：始终安装。规则内容每次现读（3s TTL 缓存），
        //      用户改规则 / 改总开关都不需要重启宿主进程。 ----
        ENGINES.forEach { e ->
            total++
            if (runCatching { installRepoFetch(e) }.getOrDefault(false)) ok++
        }
        total++
        if (runCatching { installTokenRules() }.getOrDefault(false)) ok++

        // ---- 兜底点：含行为代码（parse 返回空时合成 TextIntent），常驻安装 + 运行时门控。 ----
        total++
        if (runCatching { installActionDataFallback() }.getOrDefault(false)) ok++

        if (ok == 0) {
            LogUtils.e(TAG, "install", "逻辑注入点一个都没装上 —— 宿主实现已改变，请看上面每条失败原因", true)
        } else {
            LogUtils.i(TAG, "install", "注入点安装完成 $ok/$total")
        }
    }

    // ---------------------------------------------------- ① Repository 挂起函数

    /**
     * 按**参数形状**找挂起函数，不用方法名：
     *   `(android.content.Context, *TextContent, kotlin.coroutines.Continuation) -> Object`
     *
     * 两个引擎的 Repository 里这样的方法都**恰好只有一个**，所以不会歧义。
     */
    private fun findSuspendFetch(cls: Class<*>): Method? {
        val cont = Reflect.classOrNull(HookEnv.classLoader, CLS_CONTINUATION)
        return cls.declaredMethods.firstOrNull { m ->
            m.parameterCount == 3 &&
                m.returnType == Any::class.java &&
                Context::class.java.isAssignableFrom(m.parameterTypes[0]) &&
                m.parameterTypes[1].simpleName == SIMPLE_TEXT_CONTENT &&
                (cont == null || cont.isAssignableFrom(m.parameterTypes[2]))
        }
    }

    private fun installRepoFetch(e: Engine): Boolean {
        val cls = Reflect.classOrNull(HookEnv.classLoader, e.repoClass)
        if (cls == null) {
            // D 级：17 实测 direct 引擎不存在，这是「引擎未启用」的常态信息而非事件；
            // 总开关开启时也不该每条都刷（每进程安装时会打一次）。
            LogUtils.d(TAG, "installRepoFetch", "类不存在（该引擎未启用，跳过）→ ${e.repoClass}")
            return false
        }
        val m = findSuspendFetch(cls)
        if (m == null) {
            val candidates = cls.declaredMethods
                .filter { it.parameterCount == 3 }
                .joinToString { "${it.name}(${it.parameterTypes.joinToString { p -> p.simpleName }})->${it.returnType.simpleName}" }
            LogUtils.e(TAG, "installRepoFetch", "[${e.tag}] 找不到 (Context, TextContent, Continuation)->Object；同名候选 → [$candidates]", true)
            return false
        }

        LogUtils.i(
            TAG, "installRepoFetch",
            "[${e.tag}] 注入点 ${e.repoClass}#${m.name}(" +
                m.parameterTypes.joinToString { it.simpleName } + ") -> ${m.returnType.simpleName}"
        )

        HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                // 功能关着时直接放行：省掉一次 seedlingRules() 查表 + 一个 tryHijack 调用帧
                if (!FluidCloudConfig.customEnabled()) return chain.proceed()
                val hijacked = runCatching { tryHijack(e, chain) }
                    .onFailure { LogUtils.e(TAG, "repoFetch", "[${e.tag}] 回调异常，放行原逻辑", true) }
                    .getOrNull()
                // 只有确实成功（并且已经 resumeWith 过续体）才短路；否则原样放行
                if (hijacked != null) return hijacked
                return chain.proceed()
            }
        })
        return true
    }

    /** 返回 COROUTINE_SUSPENDED 表示「已接管」；返回 null 表示「未命中/失败，请放行原逻辑」 */
    private fun tryHijack(e: Engine, chain: XposedInterface.Chain): Any? {
        val rules = FluidCloudConfig.customRules()
        if (rules.isEmpty()) return null

        val args = chain.args
        val tc = args.getOrNull(1)
        if (tc == null || tc.javaClass.simpleName != SIMPLE_TEXT_CONTENT) {
            LogUtils.i(TAG, "tryHijack", "[${e.tag}] args[1] 不是 $SIMPLE_TEXT_CONTENT → ${tc?.javaClass?.name}")
            return null
        }
        val cont = args.getOrNull(2)
        if (cont == null) {
            LogUtils.i(TAG, "tryHijack", "[${e.tag}] 第 3 个参数为 null，不是 Continuation，放弃劫持")
            return null
        }

        val content = Reflect.getField(tc, "content") as? String
        val platforms = ecommercePlatforms(tc)
        val rule = rules.firstOrNull { r -> platforms.contains(r.platformType) || regexHit(r, content) }
        if (rule == null) {
            // 只打长度不打原文：剪贴板内容可能含隐私（密码/聊天片段），原文对排障也没有额外价值
            LogUtils.i(TAG, "tryHijack", "[${e.tag}] 未命中自定义规则 (ecommercePlatform=$platforms, contentLen=${content?.length ?: 0})")
            return null
        }
        LogUtils.i(TAG, "tryHijack", "[${e.tag}] 命中规则「${rule.name}」platformType=${rule.platformType}")

        val sentinel = suspendedSentinel()
        if (sentinel == null) {
            LogUtils.e(TAG, "tryHijack", "[${e.tag}] 取不到 COROUTINE_SUSPENDED，放弃劫持", true)
            return null
        }

        val vo = buildVo(e, rule, tc, content) ?: return null

        // 记录注入 —— 必须放在 resumeWith **之前**：
        // resumeWith 会在同一线程同步跑完整条下游链（checkValidity → sendByCommand →
        // ActionData.parse → intentCheck → build），parse 的兜底合成依赖这条记录；
        // 放在 resumeWith 之后等于「下一次复制才能用」（参考实现 v1.3.3 实测教训）。
        lastInject = Injected(
            deeplink = extractUrl(content) ?: content ?: "",
            pkg = rule.appPackages.firstOrNull() ?: "",
            appName = rule.appName,
            icon = rule.largeIcon.ifBlank { rule.smallIcon },
            platformType = rule.platformType,
            rawText = content ?: "",
            at = android.os.SystemClock.elapsedRealtime(),
        )

        // resumeWith 声明在父类 BaseContinuationImpl 上 → 必须向上找
        val resume = Reflect.findMethodDeep(cont.javaClass, "resumeWith", 1)
        if (resume == null) {
            LogUtils.e(TAG, "tryHijack", "[${e.tag}] ${cont.javaClass.name} 上找不到 resumeWith(Object)，放弃劫持", true)
            return null
        }
        Reflect.call(cont, resume, vo)

        LogUtils.i(TAG, "tryHijack", "[${e.tag}] ✅ 已接管，注入自定义流体云：${rule.name} → ${rule.appName}")
        return sentinel
    }

    /** 造一张卡片。任何关键字段缺失都会返回 null 并写清楚原因 */
    private fun buildVo(e: Engine, rule: SeedlingRule, tc: Any, content: String?): Any? {
        val voCls = Reflect.classOrNull(HookEnv.classLoader, e.voClass)
        if (voCls == null) {
            LogUtils.e(TAG, "buildVo", "[${e.tag}] VO 类不存在 → ${e.voClass}", true)
            return null
        }
        val platCls = Reflect.classOrNull(HookEnv.classLoader, e.platformClass)
        val iconCls = Reflect.classOrNull(HookEnv.classLoader, e.cardIconClass)
        if (platCls == null || iconCls == null) {
            LogUtils.e(TAG, "buildVo", "[${e.tag}] PlatformBeen / CardIcon 类不存在 → ${e.platformClass} / ${e.cardIconClass}", true)
            return null
        }

        // CardIcon(String largeIcon, String smallIcon)
        val icon = Reflect.newInstance(
            iconCls,
            listOf(String::class.java, String::class.java),
            rule.largeIcon, rule.smallIcon
        )
        if (icon == null) {
            LogUtils.e(
                TAG, "buildVo",
                "[${e.tag}] CardIcon(String,String) 构造失败；现有构造器 → [" +
                    iconCls.declaredConstructors.joinToString { c ->
                        "(" + c.parameterTypes.joinToString { p -> p.simpleName } + ")"
                    } + "]",
                true,
            )
            return null
        }

        // PlatformBeen(String appName, int type, String cardTitle, String buttonName,
        //              int eventType, CardIcon cardIcon, String downloadIcon, List appPackage)
        val plat = Reflect.newInstance(
            platCls,
            listOf(
                String::class.java,
                java.lang.Integer::class.javaPrimitiveType,
                String::class.java,
                String::class.java,
                java.lang.Integer::class.javaPrimitiveType,
                iconCls,
                String::class.java,
                List::class.java
            ),
            rule.appName, rule.platformType, rule.cardTitle, rule.buttonName,
            rule.eventType, icon, rule.downloadIcon, rule.appPackages
        )
        if (plat == null) {
            LogUtils.e(
                TAG, "buildVo",
                "[${e.tag}] PlatformBeen 构造失败；现有构造器 → [" +
                    platCls.declaredConstructors.joinToString { c ->
                        "(" + c.parameterTypes.joinToString { p -> p.simpleName } + ")"
                    } + "]",
                true,
            )
            return null
        }

        // 17 的 ServiceKouLingLinkVo 只有 24 参构造器 → Unsafe 分配 + setter / 字段填充
        val vo = Reflect.allocateInstance(voCls)
        if (vo == null) {
            LogUtils.e(
                TAG, "buildVo",
                "[${e.tag}] 无法实例化 ${e.voClass}（Unsafe 分配失败）；构造器 → [" +
                    voCls.declaredConstructors.joinToString { c ->
                        "(" + c.parameterTypes.joinToString { p -> p.simpleName } + ")"
                    } + "]",
                true,
            )
            return null
        }

        setOrLog(e.tag, vo, "setDeeplink", extractUrl(content) ?: content ?: "")
        setOrLog(e.tag, vo, "setTextContent", tc)
        setOrLog(e.tag, vo, "setCardIcon", icon)
        setOrLog(e.tag, vo, "setPlatform", plat)
        if (!setOrLog(e.tag, vo, "setTitle", rule.title)) {
            // 极少数版本没有 setTitle，退化为直接写字段
            if (!Reflect.setField(vo, "title", rule.title)) {
                LogUtils.w(TAG, "buildVo", "[${e.tag}] setTitle / 字段 title 都没有，标题会缺失")
            }
        }
        // ---- 防御性填充：Unsafe 分配出的对象所有引用字段都是 null，
        //      下游（getKouLingDataFromNet → ABExperimentManager 等）若直接解引用会 NPE 静默掉卡
        setOrLog(e.tag, vo, "setTracks", ArrayList<Any?>())
        setOrLog(e.tag, vo, "setMediaExps", HashMap<Any?, Any?>())
        setOrLog(e.tag, vo, "setDownloadPanel", 0)
        setOrLog(e.tag, vo, "setExperimentId", "")
        setOrLog(e.tag, vo, "setStyleConfigId", "")
        setOrLog(e.tag, vo, "setCommercialUser", false)

        // ---- 字段体检：把「静默失效」变成「明确报错」 ----
        val required = listOf("deeplink", "platform", "textContent", "cardIcon")
        val missing = required.filter { Reflect.getField(vo, it) == null }
        if (missing.isNotEmpty()) {
            LogUtils.e(TAG, "buildVo", "[${e.tag}] VO 字段体检未通过，缺=$missing —— 宿主数据模型已变，放弃注入", true)
            return null
        }
        return vo
    }

    // ------------------------------------------------------ ② TokenDetector 规则

    private fun installTokenRules(): Boolean {
        val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_TOKEN_DETECTOR)
        if (cls == null) {
            LogUtils.i(TAG, "installTokenRules", "TokenDetector 类不存在（识别引擎已重构？）→ $CLS_TOKEN_DETECTOR")
            return false
        }
        val m = cls.declaredMethods.firstOrNull { it.name == "readTokens" && it.parameterCount == 0 } ?: run {
            LogUtils.e(
                TAG, "installTokenRules",
                "TokenDetector#readTokens() 不存在；现有无参方法 → [" +
                    cls.declaredMethods.filter { it.parameterCount == 0 }.joinToString { it.name } + "]",
                true,
            )
            return false
        }
        LogUtils.i(TAG, "installTokenRules", "注入点 ${cls.name}#readTokens()")

        HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val r = chain.proceed()
                // after：让系统先把内置规则读进来，我们再追加
                runCatching { injectRules(chain.thisObject) }
                    .onFailure { LogUtils.e(TAG, "installTokenRules", "注入 TokenDetector.ruleList 失败", true) }
                return r
            }
        })
        return true
    }

    @Suppress("UNCHECKED_CAST")
    private fun injectRules(owner: Any?) {
        if (owner == null) {
            LogUtils.e(TAG, "injectRules", "readTokens() 是静态方法，拿不到 TokenDetector 实例 → 无法注入 ruleList", true)
            return
        }
        val raw = Reflect.getField(owner, "ruleList")
        val list = raw as? MutableList<Any?>
        if (list == null) {
            // readTokens 早退（ruleList 已加载过）时也会走到这里；没有 list 就没什么可做
            return
        }

        // ---- 「未变化」双判：同一 list 实例 + 同一份规则 JSON → 零反射直接返回。
        //      readTokens 在 17 上每进程只真正加载一次（原版有 ruleList != null 早退），
        //      但钩子体挂在返回后、每次检测事件都会跑到 —— 没有这层判据就会重复注入。
        //      云端 updateNewRules 会换新 list 实例 → 身份变化 → 自动重新注入，不丢规则。
        val fingerprint = HookEnv.settingString(FluidCloudConfig.KEY_CUSTOM_RULES)
        if (list === lastListIdentity && fingerprint == lastFingerprint) return

        val rules = FluidCloudConfig.customRules()
        if (rules.isEmpty()) {
            // 记住当前状态，避免功能关闭时空跑
            lastListIdentity = list
            lastFingerprint = fingerprint
            return
        }

        val exprCls = Reflect.classOrNull(HookEnv.classLoader, CLS_EXPRESSION)
        val rvoCls = Reflect.classOrNull(HookEnv.classLoader, CLS_RULE_VO)
        if (exprCls == null || rvoCls == null) {
            LogUtils.e(TAG, "injectRules", "Expression / RuleVO 类不存在 → $CLS_EXPRESSION / $CLS_RULE_VO", true)
            return
        }

        val existing = list.mapNotNull { e ->
            val pt = Reflect.getField(e, "platformType") ?: return@mapNotNull null
            "$pt|${Reflect.getField(e, "commercialConfigName")}"
        }.toSet()

        var added = 0
        rules.forEach { r ->
            val key = "${r.platformType}|${r.supplier}"
            if (existing.contains(key)) {
                LogUtils.i(TAG, "injectRules", "ruleList 已含 $key，跳过（系统内置已覆盖）")
                return@forEach
            }

            // 自适应构造：Expression 签名可能已不是 (long,String,List)
            val expr = Reflect.newInstanceAdaptive(
                exprCls,
                listOf(
                    "long" to r.name.hashCode().toLong(),   // 名字哈希当表达式 id（对齐参考实现）
                    "String" to r.matchRegex,
                    "List" to r.appPackages
                ),
                failLog = { msg -> LogUtils.e(TAG, "injectRules", "$msg rule=${r.name}", true) },
                infoLog = { msg -> LogUtils.i(TAG, "injectRules", "$msg rule=${r.name}") },
            )
            if (expr == null) return@forEach

            val rvo = Reflect.newInstanceAdaptive(
                rvoCls,
                listOf("int" to r.platformType, "List" to listOf(expr), "String" to r.supplier),
                failLog = { msg -> LogUtils.e(TAG, "injectRules", "$msg rule=${r.name}", true) },
                infoLog = { msg -> LogUtils.i(TAG, "injectRules", "$msg rule=${r.name}") },
            )
            if (rvo == null) return@forEach

            list.add(rvo)
            added++
            LogUtils.i(TAG, "injectRules", "+RuleVO platformType=${r.platformType} supplier=${r.supplier}")
        }

        lastListIdentity = list
        lastFingerprint = fingerprint

        if (added > 0) {
            LogUtils.i(TAG, "injectRules", "✅ ruleList 注入 $added 条自定义规则（当前共 ${list.size} 条）")
        }
    }

    // ------------------------------------------------------ ③ detectInner（仅日志，已省略）

    // ------------------------------------------------------ ④ ActionData.parse 兜底合成

    /**
     * `ActionData#parse(Context)` 返回空时的**兜底合成**。
     *
     * ⚠️ 这是**行为代码，不是诊断** —— parse 只按「在线检测响应」构建跳转，
     * 自定义规则在服务器没有卡数据 → 返回 0 条 → `intentCheck=false` → 卡片不显示。
     * 这里在返回空、且最近 [INJECT_TTL_MS] 内确实做过自定义 VO 注入时，用注入数据合成一条
     * TextIntent 塞回去。
     *
     * 门控是**开关驱动**（[FluidCloudConfig.customEnabled]），详细原因写日志。
     */
    private fun installActionDataFallback(): Boolean {
        val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_ACTION_DATA)
        if (cls == null) {
            LogUtils.i(TAG, "installActionDataFallback", "ActionData 类不存在（跳过）→ $CLS_ACTION_DATA")
            return false
        }
        val m = cls.declaredMethods.firstOrNull { it.name == "parse" && it.parameterCount == 1 }
        if (m == null) {
            LogUtils.i(TAG, "installActionDataFallback", "ActionData#parse 不存在（跳过）→ $CLS_ACTION_DATA")
            return false
        }
        HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val r = chain.proceed()
                runCatching {
                    // 行为兜底：只在功能开启时做
                    if (FluidCloudConfig.customEnabled()) {
                        val list = r as? MutableList<Any?>
                        if (list != null && list.isEmpty()) {
                            val actionText = Reflect.getField(chain.thisObject, "text") as? String
                            val ti = synthesizeTextIntent(actionText)
                            if (ti != null) {
                                list.add(ti)
                                LogUtils.i(TAG, "parse", "[兜底] parse 返回空且存在近期注入 → 已合成 TextIntent")
                            }
                        }
                    }
                }.onFailure { LogUtils.e(TAG, "parse", "兜底合成异常，放行", true) }
                return r
            }
        })
        LogUtils.i(TAG, "installActionDataFallback", "注入点 $CLS_ACTION_DATA#parse(兜底合成)")
        return true
    }

    /**
     * 用最近一次自定义注入的数据合成一条 TextIntent（对齐抖音样本的形状：
     * VIEW dat=<deeplink> pkg=<pkg>，附 appName/icon/platformType）。
     * 超过 TTL、无注入记录、或当前 ActionData.text 与注入内容不匹配时返回 null（完全放行原逻辑）。
     */
    private fun synthesizeTextIntent(actionText: String?): Any? {
        val inj = lastInject ?: return null
        if (android.os.SystemClock.elapsedRealtime() - inj.at > INJECT_TTL_MS) {
            LogUtils.i(TAG, "synthesize", "注入记录已过期（> ${INJECT_TTL_MS / 1000}s），不合成")
            return null
        }
        if (inj.deeplink.isBlank()) return null
        // 内容守卫：只对「确实是我们注入的那条内容」合成，防止 10 秒窗口内
        // 无关文本（如其他应用的检测失败）被误塞入自定义跳转
        if (actionText != null && !actionText.contains(inj.deeplink) && !inj.rawText.contains(actionText)) {
            LogUtils.i(TAG, "synthesize", "ActionData.text 与注入内容不匹配，不合成 (text=${actionText.take(40)})")
            return null
        }
        val tiCls = Reflect.classOrNull(HookEnv.classLoader, CLS_TEXT_INTENT)
        if (tiCls == null) {
            LogUtils.e(TAG, "synthesize", "TextIntent 类不存在", true)
            return null
        }
        val ti = Reflect.newInstance(tiCls, emptyList()) ?: Reflect.allocateInstance(tiCls)
        if (ti == null) {
            LogUtils.e(TAG, "synthesize", "无法实例化 TextIntent（无参构造 + Unsafe 均失败）", true)
            return null
        }
        val intent = buildOpenIntent(HookEnv.context(), inj.pkg, inj.deeplink)
        setOrLog("兜底", ti, "setIntent", intent)
        setOrLog("兜底", ti, "setDeepLink", inj.deeplink)
        if (inj.pkg.isNotBlank()) setOrLog("兜底", ti, "setPackageName", inj.pkg)
        setOrLog("兜底", ti, "setAppName", inj.appName)
        if (inj.icon.isNotBlank()) setOrLog("兜底", ti, "setAppIcon", inj.icon)
        setOrLog("兜底", ti, "setPlatformType", inj.platformType)
        setOrLog("兜底", ti, "setInstalled", true)
        setOrLog("兜底", ti, "setSupport", true)
        setOrLog("兜底", ti, "setSystem", true)
        return ti
    }

    // ------------------------------------------------------------------ 小工具

    private fun ecommercePlatforms(tc: Any): List<Int> {
        val ris = Reflect.getField(tc, "regularInfos") as? List<*> ?: return emptyList()
        return ris.mapNotNull { r ->
            if (r == null) null else Reflect.getField(r, "ecommercePlatform") as? Int
        }
    }

    private fun regexHit(rule: SeedlingRule, content: String?): Boolean {
        if (rule.matchRegex.isBlank() || content.isNullOrBlank()) return false
        return runCatching { Pattern.compile(rule.matchRegex).matcher(content).find() }
            .getOrDefault(false)
    }

    private fun extractUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val m = URL_RE.matcher(text)
        return if (m.find()) m.group() else null
    }

    private fun setOrLog(tag: String, target: Any, setter: String, value: Any?): Boolean {
        val m = Reflect.findMethodDeep(target.javaClass, setter, 1)
        if (m != null) {
            return runCatching { m.invoke(target, value); true }
                .onFailure { LogUtils.e(TAG, "setOrLog", "[$tag] $setter 调用失败", true) }
                .getOrDefault(false)
        }
        // setter 不存在（Kotlin val 或签名变了）→ 直接反射写字段（ART 允许写私有实例字段）
        val field = setter.removePrefix("set")
        val ok = field.isNotEmpty() && Reflect.setField(
            target,
            field.substring(0, 1).lowercase() + field.substring(1),
            value
        )
        if (!ok) LogUtils.w(TAG, "setOrLog", "[$tag] 宿主没有 $setter(1 参) 且字段写入失败，跳过该字段")
        return ok
    }

    /**
     * 合成「打开」意图：先试 `VIEW + deeplink + pkg`（能直接打开 App 内对应页最好）；
     * 若该 App 没注册这个 URL 的 VIEW 接收器，`resolveActivity` 拿不到 → 退化成启动该 App
     * 主界面，保证「点击能打开对应 App」，而不是 `startActivity` 抛
     * `ActivityNotFoundException`。与功能一 rewriteTextIntent 的兜底同策略。
     */
    private fun buildOpenIntent(ctx: Context?, pkg: String, deeplink: String): Intent {
        val view = Intent(Intent.ACTION_VIEW).apply {
            if (deeplink.isNotBlank()) data = Uri.parse(deeplink)
            if (pkg.isNotBlank()) setPackage(pkg)
        }
        if (ctx != null && pkg.isNotBlank()) {
            runCatching {
                if (ctx.packageManager.resolveActivity(view, 0) != null) return view
                val launch = ctx.packageManager.getLaunchIntentForPackage(pkg)
                if (launch != null) {
                    LogUtils.i(TAG, "buildOpenIntent", "$pkg 无法解析 $deeplink（VIEW）→ 退化为启动 ${pkg} 主界面")
                    return launch
                }
            }.onFailure { LogUtils.w(TAG, "buildOpenIntent", "解析/启动意图失败（pkg=$pkg）→ $it") }
        }
        return view
    }

    @Volatile
    private var suspendedCache: Any? = null
    @Volatile
    private var suspendedResolved = false

    /** `kotlin.coroutines.intrinsics.CoroutineSingletons.COROUTINE_SUSPENDED` */
    private fun suspendedSentinel(): Any? {
        if (suspendedResolved) return suspendedCache
        synchronized(this) {
            if (suspendedResolved) return suspendedCache
            val cls = Reflect.classOrNull(HookEnv.classLoader, CLS_COROUTINE_SINGLETONS)
            suspendedCache = if (cls == null) null else Reflect.enumValue(cls, "COROUTINE_SUSPENDED")
            suspendedResolved = true
            LogUtils.d(TAG, "suspendedSentinel", "COROUTINE_SUSPENDED = $suspendedCache")
            return suspendedCache
        }
    }
}
