package com.fr.colorostools.hook.hookers

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.fr.colorostools.hook.DeepLinkRule
import com.fr.colorostools.hook.FluidCloudConfig
import com.fr.colorostools.hook.HookEnv
import com.fr.colorostools.ref.Reflect
import com.fr.colorostools.utils.LogUtils
import io.github.libxposed.api.XposedInterface

/**
 * 功能一：替换部分 DeepLink 流体云的目标 App。
 *
 * 注入点（ColorOS 17 / metis 实测）：
 *   ★ `com.oplus.textintent.manager.helper.CommandHandler#checkValidity(vo, textContent)`
 *     —— 决定卡片是「打开卡」还是「提示安装原 App」的那一层。必须**在 parseIntent 之前**
 *        改写 VO（安装卡在那之后才生成），所以这里是第一处改写点。
 *   ★ `com.oplus.textintent.distribution.bean.DeepLinkData#parseIntent(Context) -> TextIntent`
 *     —— 口令卡片跳转目标（包名 / 应用名 / Intent）的真正构建点。返回后**就地改写**
 *        TextIntent 的 packageName / appName / intent。
 *
 * 改写策略（对齐参考实现并按其结论收敛）：
 *   1. VO 分支：`PlatformBeen` **就地写字段** appName / appPackage（参考实现实测：
 *      「宿主 Gson 序列化→改 JSON→反序列化」与就地写字段效果等价，而 Gson 在 metis 里
 *      常被 R8 删掉，反而多一个动态依赖）；
 *   2. TextIntent 分支：改**已建好的 Intent 的包名**（并清掉显式 component），保留原始
 *      data / action，不需要知道 deeplink 字段名；
 *   3. `regularInfos[].pkgInfos[].pkgName` 同步改写，保证各层一致；
 *   4. 平替 App 未注册原 scheme 时退化为「打开平替 App 主界面」，避免点了没反应。
 *
 * 门控：钩子**常驻安装**，钩子体第一行按总开关早退 —— 改开关 / 改规则都无需重启宿主。
 */
object FluidCloudReplaceHook {

    private const val TAG = "FluidCloudReplace"

    private const val VO_SIMPLE_NAME = "ServiceKouLingLinkVo"

    /** 卡片数据改写点（新旧两套引擎各一份类名，哪套存在挂哪套） */
    private const val CLS_CMD_HANDLER = "com.oplus.textintent.manager.helper.CommandHandler"
    private const val CLS_CMD_HANDLER_DIRECT = "com.coloros.direct.command.helper.CommandHandler"

    /** 深链跳转目标构建点 */
    private const val CLS_DEEPLINK_DATA = "com.oplus.textintent.distribution.bean.DeepLinkData"
    private const val CLS_DEEPLINK_DATA_DIRECT = "com.coloros.direct.command.bean.DeepLinkData"

    fun install() {
        var ok = 0
        runCatching { if (installValidityRewrite()) ok++ }
            .onFailure { LogUtils.e(TAG, "install", "checkValidity 改写点安装失败", true) }
        runCatching { if (installDeepLinkIntent()) ok++ }
            .onFailure { LogUtils.e(TAG, "install", "parseIntent 改写点安装失败", true) }
        if (ok == 0) {
            LogUtils.e(TAG, "install", "注入点一个都没装上（宿主实现已改变？见上方失败原因）", true)
        } else {
            LogUtils.i(TAG, "install", "注入点安装完成 $ok/2")
        }
    }

    // -------------------------------------------- ★ 卡片数据改写（安装卡 → 打开卡）

    /**
     * 卡片是「打开卡」还是「提示安装原 App」，由宿主依据 **VO 里的平台包名 / 安装状态**决定，
     * 且这个决定发生在 `DeepLinkData#parseIntent` **之前**。只改 TextIntent 会让卡片「能跳」
     * 却仍是安装卡，所以必须先在这里改写 VO。
     */
    private fun installValidityRewrite(): Boolean {
        val clsName = listOf(CLS_CMD_HANDLER, CLS_CMD_HANDLER_DIRECT).firstOrNull { name ->
            Reflect.classOrNull(HookEnv.classLoader, name) != null
        }
        if (clsName == null) {
            LogUtils.i(TAG, "installValidityRewrite", "CommandHandler 类不存在（跳过）→ $CLS_CMD_HANDLER")
            return false
        }
        val cls = Reflect.classOrNull(HookEnv.classLoader, clsName)!!
        // 按名字取（dexsig 确认就叫 checkValidity；17 上是 private，不影响 hook）
        val m = cls.declaredMethods.firstOrNull { it.name == "checkValidity" } ?: run {
            LogUtils.e(
                TAG, "installValidityRewrite",
                "$clsName 上没有 checkValidity；现有方法 → [" +
                    cls.declaredMethods.joinToString { "${it.name}/${it.parameterCount}" } + "]",
                true,
            )
            return false
        }
        HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                // 功能关着直接放行：省掉一次参数扫描 + 一个 rewrite() 调用帧
                if (!FluidCloudConfig.replaceEnabled()) return chain.proceed()
                runCatching {
                    val vo = chain.args.firstOrNull { it?.javaClass?.simpleName == VO_SIMPLE_NAME }
                    if (vo != null) rewrite(vo) else LogUtils.d(TAG, "checkValidity", "参数里没有 $VO_SIMPLE_NAME")
                }.onFailure { LogUtils.e(TAG, "checkValidity", "改写异常，放行", true) }
                return chain.proceed()
            }
        })
        LogUtils.i(TAG, "installValidityRewrite", "Hook 已安装 ✓ $clsName#checkValidity")
        return true
    }

    // ---------------------------------------------------- ★ 口令卡片跳转目标改写

    /** hook `DeepLinkData#parseIntent(Context)`，返回后就地改写 TextIntent */
    private fun installDeepLinkIntent(): Boolean {
        val clsName = listOf(CLS_DEEPLINK_DATA, CLS_DEEPLINK_DATA_DIRECT).firstOrNull { name ->
            Reflect.classOrNull(HookEnv.classLoader, name) != null
        }
        if (clsName == null) {
            LogUtils.i(TAG, "installDeepLinkIntent", "DeepLinkData 类不存在（跳过）→ $CLS_DEEPLINK_DATA")
            return false
        }
        val cls = Reflect.classOrNull(HookEnv.classLoader, clsName)!!
        val m = cls.declaredMethods.firstOrNull { it.name == "parseIntent" } ?: run {
            LogUtils.e(
                TAG, "installDeepLinkIntent",
                "$clsName 上没有 parseIntent；现有方法 → [" +
                    cls.declaredMethods.joinToString { "${it.name}/${it.parameterCount}" } + "]",
                true,
            )
            return false
        }
        HookEnv.module.hook(m).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val r = chain.proceed()
                runCatching { rewriteTextIntent(chain.thisObject, r, chain.args.getOrNull(0)) }
                    .onFailure { LogUtils.e(TAG, "parseIntent", "改写异常，放行", true) }
                return r
            }
        })
        LogUtils.i(TAG, "installDeepLinkIntent", "Hook 已安装 ✓ $clsName#${m.name}")
        return true
    }

    /**
     * 就地改写 `DeepLinkData#parseIntent` 返回的 TextIntent：
     * 把跳转目标换成规则里的平替 App（保留原始 deeplink / action）。
     */
    private fun rewriteTextIntent(data: Any, ti: Any?, ctx: Any?) {
        if (ti == null) return
        if (ti.javaClass.simpleName != "TextIntent") {
            LogUtils.i(TAG, "parseIntent", "返回值不是 TextIntent → ${ti.javaClass.name}")
            return
        }
        val rules = FluidCloudConfig.replaceRules()
        if (rules.isEmpty()) return

        val srcPkg = Reflect.getField(data, "pkg") as? String
        val srcName = Reflect.getField(data, "name") as? String
        val link = (Reflect.callByName(ti, "getDeepLink") as? String)
            ?: (Reflect.getField(ti, "deepLink") as? String)

        // checkValidity 那一层已经改写过 VO.platform，这里看到的 pkg 往往**已经是新包名**，
        // 所以既要按「源」匹配，也要按「平替」匹配，否则第二处改写会被跳过。
        val rule = rules.firstOrNull { r ->
            (srcPkg != null && (srcPkg.contains(r.sourcePackageName) || srcPkg == r.newPackageName)) ||
                (srcName != null && (srcName == r.sourceAppName || srcName == r.newAppName))
        }
        if (rule == null) {
            LogUtils.i(TAG, "parseIntent", "未命中替换规则（pkg=$srcPkg name=$srcName）")
            return
        }

        val targetPkg = rule.newPackageName
        val installed = isInstalledWith(ctx, targetPkg)

        // ① TextIntent 自身展示字段
        Reflect.callByName(ti, "setPackageName", targetPkg)
        Reflect.callByName(ti, "setAppName", rule.newAppName)
        if (installed) {
            Reflect.callByName(ti, "setInstalled", true)
        } else {
            LogUtils.w(TAG, "parseIntent", "没检测到 $targetPkg 已安装 → 卡片可能被宿主丢弃")
        }

        // ② 跳转 Intent：优先改**已建好的那个**（不必知道 deeplink 来自哪个字段），
        //    必须清掉显式 component —— 否则仍会跳到旧 App。
        val old = Reflect.callByName(ti, "getIntent") as? Intent
        if (old != null) {
            old.component = null
            old.setPackage(targetPkg)
            if (old.action == null) old.action = Intent.ACTION_VIEW
            if (old.data == null && !link.isNullOrBlank()) old.data = Uri.parse(link)
        } else {
            val fresh = Intent(Intent.ACTION_VIEW)
            if (!link.isNullOrBlank()) fresh.data = Uri.parse(link)
            fresh.setPackage(targetPkg)
            Reflect.callByName(ti, "setIntent", fresh)
        }

        // ③ 兜底：平替 App 未必注册了原 App 的私有 scheme
        //    （典型：`qklink://` 只有夸克浏览器认，夸克网盘认不了）。
        //    解析不到就退化为**打开平替 App 主界面**，避免点了没反应。
        val c = ctx as? Context
        if (c != null) {
            val cur = Reflect.callByName(ti, "getIntent") as? Intent
            if (cur != null && c.packageManager.resolveActivity(cur, 0) == null) {
                val launch = c.packageManager.getLaunchIntentForPackage(targetPkg)
                if (launch != null) {
                    Reflect.callByName(ti, "setIntent", launch)
                    LogUtils.i(TAG, "parseIntent", "平替 App 无法解析原 deeplink → 改为启动 ${rule.newAppName} 主界面")
                } else {
                    LogUtils.w(TAG, "parseIntent", "平替 App 既解析不了 deeplink 也没有启动入口，点击可能无反应")
                }
            }
        }

        LogUtils.i(TAG, "parseIntent", "✅ 跳转目标：$targetPkg | ${srcName ?: "?"} → ${rule.newAppName} | installed=$installed")
    }

    // ------------------------------------------------------------------ 改写

    private fun rewrite(vo: Any) {
        val rules = FluidCloudConfig.replaceRules()
        if (rules.isEmpty()) return

        val platform = Reflect.getField(vo, "platform")
        if (platform == null) {
            LogUtils.w(TAG, "checkValidity", "vo.platform 为 null，无法匹配（宿主数据未填充？）")
            return
        }

        val appName = Reflect.getField(platform, "appName") as? String
        val rawPackages = Reflect.getField(platform, "appPackage") as? List<*>
        val packages = rawPackages?.map { it?.toString().orEmpty() } ?: emptyList()

        for (rule in rules) {
            val pkgHit = packages.any { it.contains(rule.sourcePackageName) }
            val nameHit = rule.sourceAppName.isNotBlank() && appName == rule.sourceAppName
            if (!pkgHit && !nameHit) continue
            applyRule(vo, platform, rule, packages, appName)
        }
    }

    private fun applyRule(
        vo: Any,
        platform: Any,
        rule: DeepLinkRule,
        packages: List<String>,
        oldAppName: String?,
    ) {
        // 新包名列表：命中源包名的那一项换成平替，其余保持
        val newPackages = ArrayList<Any?>()
        if (packages.isEmpty()) {
            newPackages.add(rule.newPackageName)
        } else {
            packages.forEach { p ->
                newPackages.add(if (p.contains(rule.sourcePackageName)) rule.newPackageName else p)
            }
        }

        // 就地改写 platform 字段（参考实现实测：与 Gson 重建效果等价）
        val nameOk = Reflect.setField(platform, "appName", rule.newAppName)
        val pkgOk = Reflect.setField(platform, "appPackage", newPackages)
        if (!nameOk && !pkgOk) {
            LogUtils.e(TAG, "checkValidity", "platform 字段写入失败，放弃改写（${platform.javaClass.name}）", true)
            return
        }
        LogUtils.i(TAG, "checkValidity", "就地改写 platform appName=$nameOk appPackage=$pkgOk → $newPackages")

        rewriteRegularInfos(vo, rule)

        LogUtils.i(TAG, "checkValidity", "✅ ${rule.sourcePackageName} → ${rule.newPackageName} | appName: $oldAppName → ${rule.newAppName}")
    }

    /** 同步改写 TextContent.regularInfos[].pkgInfos[].pkgName，保证各层一致 */
    private fun rewriteRegularInfos(vo: Any, rule: DeepLinkRule) {
        val tc = Reflect.getField(vo, "textContent") ?: return
        val ris = Reflect.getField(tc, "regularInfos") as? List<*> ?: return
        if (ris.isEmpty()) return

        var changed = false
        val out = ris.map { r ->
            if (r == null) return@map null
            val pkgs = Reflect.getField(r, "pkgInfos") as? List<*> ?: return@map r
            val hit = pkgs.any { p ->
                (Reflect.getField(p, "pkgName") as? String)?.contains(rule.sourcePackageName) == true
            }
            if (!hit) return@map r

            val newPkgs = pkgs.map { p ->
                val name = Reflect.getField(p, "pkgName") as? String
                if (name != null && name.contains(rule.sourcePackageName)) {
                    Reflect.newInstance(
                        p?.javaClass,
                        listOf(String::class.java, java.lang.Boolean::class.javaPrimitiveType),
                        rule.newPackageName,
                        isInstalled(rule.newPackageName)
                    ) ?: p
                } else {
                    p
                }
            }

            // data class 的 copy(...)（按形状定位，参数顺序随版本可能漂移）
            val copied = Reflect.call(
                r,
                Reflect.findMethodByShape(
                    r.javaClass, "copy",
                    listOf(
                        java.lang.Long::class.javaPrimitiveType,
                        String::class.java, String::class.java,
                        java.lang.Integer::class.javaPrimitiveType,
                        String::class.java, List::class.java
                    )
                ),
                Reflect.getField(r, "regularId"),
                Reflect.getField(r, "regular"),
                Reflect.getField(r, "command"),
                Reflect.getField(r, "ecommercePlatform"),
                Reflect.getField(r, "commercialConfigName"),
                newPkgs
            )
            if (copied != null) changed = true
            copied ?: r
        }

        if (changed) {
            Reflect.callByName(tc, "setRegularInfos", out)
            LogUtils.i(TAG, "checkValidity", "regularInfos 已同步改写")
        }
    }

    private fun isInstalled(pkg: String): Boolean = isInstalledWith(null, pkg)

    /** 优先用调用方传出来的 Context（parseIntent 的第一个参数），否则退回 HookEnv.context() */
    private fun isInstalledWith(ctx: Any?, pkg: String): Boolean {
        val c = (ctx as? Context) ?: HookEnv.context() ?: return false
        return try {
            c.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (t: Throwable) {
            false
        }
    }
}
