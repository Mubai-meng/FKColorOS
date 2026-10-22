package com.fr.colorostools.hook

import com.fr.colorostools.utils.LogUtils
import org.json.JSONArray
import org.json.JSONObject

/**
 * 流体云两功能的规则模型（App 设置页与 hook 进程共用同一份定义与 JSON 格式）。
 *
 * 存储位置：`SettingsPrefs`（remote prefs）——
 *   - App 侧 `Context.putString(SettingsPrefs, KEY, json)` 写入；
 *   - hook 侧 [com.fr.colorostools.hook.HookEnv.settingString] 读取，同源无需额外同步。
 */

/** 功能一「替换流体云目标」：一条「源 App → 平替 App」替换规则 */
data class DeepLinkRule(
    val enable: Boolean = true,
    val sourceAppName: String = "",
    val sourcePackageName: String = "",
    val newAppName: String = "",
    val newPackageName: String = "",
)

/**
 * 功能二「自定义流体云」：一条剪切板规则（正则命中 → 出自定义卡片）。
 *
 * 字段对齐宿主 VO（`ServiceKouLingLinkVo` / `PlatformBeen` / `CardIcon`），
 * 命名与原版 FKColorOS 的 `SeedlingRule` 保持一致，便于预设与规则互相导入。
 */
data class SeedlingRule(
    val enable: Boolean = true,
    val uuid: String = "",
    val name: String = "",
    /** 平台类型；系统内置用 <10000，自定义规则约定 ≥10000（唯一） */
    val platformType: Int = 0,
    /** 供应商标识（原版内置规则用 "CPS"） */
    val supplier: String = "CPS",
    /** 命中剪切板内容的正则；空串表示只按 platformType 判定 */
    val matchRegex: String = "",
    val appName: String = "",
    val appPackages: List<String> = emptyList(),
    val cardTitle: String = "",
    val buttonName: String = "打开",
    val title: String = "",
    val smallIcon: String = "",
    val largeIcon: String = "",
    val downloadIcon: String = "",
    val eventType: Int = 0,
)

/** 规则 JSON 序列化 / 反序列化（列表 UI 与 hook 消费端共用） */
object FluidCloudRules {

    // ---------------- 功能一 ----------------

    fun deeplinkFromJson(s: String?): List<DeepLinkRule> {
        if (s.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                DeepLinkRule(
                    enable = o.optBoolean("enable", true),
                    sourceAppName = o.optString("sourceAppName", ""),
                    sourcePackageName = o.optString("sourcePackageName", ""),
                    newAppName = o.optString("newAppName", ""),
                    newPackageName = o.optString("newPackageName", ""),
                )
            }
        } catch (t: Throwable) {
            LogUtils.w("FluidCloudRules", "deeplinkFromJson", "解析失败 → $t")
            emptyList()
        }
    }

    fun deeplinkToJson(rules: List<DeepLinkRule>): String {
        val arr = JSONArray()
        rules.forEach { r ->
            arr.put(
                JSONObject()
                    .put("enable", r.enable)
                    .put("sourceAppName", r.sourceAppName)
                    .put("sourcePackageName", r.sourcePackageName)
                    .put("newAppName", r.newAppName)
                    .put("newPackageName", r.newPackageName)
            )
        }
        return arr.toString()
    }

    // ---------------- 功能二 ----------------

    fun seedlingFromJson(s: String?): List<SeedlingRule> {
        if (s.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val pkgs = mutableListOf<String>()
                o.optJSONArray("appPackages")?.let { a ->
                    for (k in 0 until a.length()) a.optString(k).takeIf { it.isNotBlank() }?.let { pkgs += it }
                }
                SeedlingRule(
                    enable = o.optBoolean("enable", true),
                    uuid = o.optString("uuid", ""),
                    name = o.optString("name", ""),
                    platformType = o.optInt("platformType", 0),
                    supplier = o.optString("supplier", "CPS"),
                    matchRegex = o.optString("matchRegex", ""),
                    appName = o.optString("appName", ""),
                    appPackages = pkgs,
                    cardTitle = o.optString("cardTitle", ""),
                    buttonName = o.optString("buttonName", "打开"),
                    title = o.optString("title", ""),
                    smallIcon = o.optString("smallIcon", ""),
                    largeIcon = o.optString("largeIcon", ""),
                    downloadIcon = o.optString("downloadIcon", ""),
                    eventType = o.optInt("eventType", 0),
                )
            }
            // 不按 enable 过滤：列表 UI 要展示并编辑全部已存规则；生效过滤由 hook 消费端做
        } catch (t: Throwable) {
            LogUtils.w("FluidCloudRules", "seedlingFromJson", "解析失败 → $t")
            emptyList()
        }
    }

    fun seedlingToJson(rules: List<SeedlingRule>): String {
        val arr = JSONArray()
        rules.forEach { r ->
            val pkgs = JSONArray()
            r.appPackages.forEach { pkgs.put(it) }
            arr.put(
                JSONObject()
                    .put("enable", r.enable)
                    .put("uuid", r.uuid)
                    .put("name", r.name)
                    .put("platformType", r.platformType)
                    .put("supplier", r.supplier)
                    .put("matchRegex", r.matchRegex)
                    .put("appName", r.appName)
                    .put("appPackages", pkgs)
                    .put("cardTitle", r.cardTitle)
                    .put("buttonName", r.buttonName)
                    .put("title", r.title)
                    .put("smallIcon", r.smallIcon)
                    .put("largeIcon", r.largeIcon)
                    .put("downloadIcon", r.downloadIcon)
                    .put("eventType", r.eventType)
            )
        }
        return arr.toString()
    }
}

/**
 * hook 进程侧的开关 / 规则读取（带 TTL 缓存）。
 *
 * - 布尔开关直读 remote prefs（内存镜像，每次检测事件读一次的成本可忽略）；
 * - 规则 JSON 解析有成本，做 3s TTL 缓存 —— 用户改规则后最迟 3s 生效，无需重启宿主；
 * - 关闭时返回空列表：功能本身即被「数据驱动」地关掉，钩子体再做一次布尔早退。
 */
internal object FluidCloudConfig {

    private const val RULE_TTL_MS = 3000L

    // ---------------- 功能一 ----------------

    private var replaceCache: List<DeepLinkRule> = emptyList()
    private var replaceAt = 0L

    fun replaceEnabled(): Boolean =
        HookEnv.settingBoolean(KEY_REPLACE_ENABLED, false)

    /** 生效规则：启用中且源/目标包名都非空 */
    fun replaceRules(): List<DeepLinkRule> {
        val now = System.currentTimeMillis()
        if (now - replaceAt < RULE_TTL_MS) return replaceCache
        replaceCache = if (!replaceEnabled()) {
            emptyList()
        } else {
            FluidCloudRules.deeplinkFromJson(HookEnv.settingString(KEY_REPLACE_RULES))
                .filter { it.enable && it.sourcePackageName.isNotBlank() && it.newPackageName.isNotBlank() }
        }
        replaceAt = now
        return replaceCache
    }

    // ---------------- 功能二 ----------------

    private var customCache: List<SeedlingRule> = emptyList()
    private var customAt = 0L

    fun customEnabled(): Boolean =
        HookEnv.settingBoolean(KEY_CUSTOM_ENABLED, false)

    /** 生效规则：启用中且 platformType 非 0 */
    fun customRules(): List<SeedlingRule> {
        val now = System.currentTimeMillis()
        if (now - customAt < RULE_TTL_MS) return customCache
        customCache = if (!customEnabled()) {
            emptyList()
        } else {
            FluidCloudRules.seedlingFromJson(HookEnv.settingString(KEY_CUSTOM_RULES))
                .filter { it.enable && it.platformType != 0 }
        }
        customAt = now
        return customCache
    }

    // ---------------- 键名（App 侧写入、hook 侧读取，两边一致） ----------------

    const val KEY_REPLACE_ENABLED = "fluid_replace_enabled"
    const val KEY_REPLACE_RULES = "fluid_replace_rules"
    const val KEY_CUSTOM_ENABLED = "fluid_custom_enabled"
    const val KEY_CUSTOM_RULES = "fluid_custom_rules"
}

/**
 * 预设规则库（对齐原版 FKColorOS 1.12.10）。
 *
 * 功能一内置示例 Bilibili→PiliPalaX；功能二 15 条 quicklink 预设
 * （原版预设库不含哔哩哔哩 —— 系统原生已支持，避免「添加任意预设多出一条哔哩哔哩」）。
 */
object FluidCloudPresets {

    data class DeeplinkPreset(
        val labelSource: String,
        val labelTarget: String,
        val sourceAppName: String,
        val sourcePackageName: String,
        val newAppName: String,
        val newPackageName: String,
    ) {
        val label: String get() = "$labelSource>$labelTarget"
    }

    val DEEPLINK: List<DeeplinkPreset> = listOf(
        DeeplinkPreset(
            labelSource = "Bilibili", labelTarget = "PiliPalaX",
            sourceAppName = "哔哩哔哩", sourcePackageName = "tv.danmaku.bili",
            newAppName = "PiliPalaX", newPackageName = "com.orz12.PiliPalaX",
        )
    )

    fun deeplinkPresetRule(p: DeeplinkPreset): DeepLinkRule = DeepLinkRule(
        enable = true,
        sourceAppName = p.sourceAppName,
        sourcePackageName = p.sourcePackageName,
        newAppName = p.newAppName,
        newPackageName = p.newPackageName,
    )

    data class SeedlingPreset(
        val name: String,
        val uuid: String,
        val regex: String,
        val appName: String,
        val appPackages: List<String>,
        val cardTitle: String,
        val buttonName: String,
        val title: String,
        val largeIcon: String,
    )

    val SEEDLING: List<SeedlingPreset> = listOf(
        SeedlingPreset(
            "携程旅行", "C1T2R3I4-P567-890B-CDEF-GHIJKLMN0123",
            "([.\\s\\S]*?https?:\\/\\/(?:[\\w-]+\\.)*(?:ctrip\\.com|trip\\.com)(?:\\/[^\\s]*)?[.\\s\\S]*)",
            "携程旅行", listOf("ctrip.android.view"),
            "点击打开 携程旅行 APP", "打开", "携程旅行", "https://www.ctrip.com/favicon.ico"
        ),
        SeedlingPreset(
            "大众点评", "D1Z2D3P4-I567-890B-CDEF-GHIJKLMN0123",
            "([.\\s\\S]*?https?:\\/\\/(?:[\\w-]+\\.)*dianping\\.com(?:\\/[^\\s]*)?[.\\s\\S]*)",
            "大众点评", listOf("com.dianping.v1"),
            "点击打开 大众点评 APP", "打开", "大众点评", "https://www.dianping.com/favicon.ico"
        ),
        SeedlingPreset(
            "什么值得买", "S1M2Z3D4-M567-890B-CDEF-GHIJKLMN0123",
            "([.\\s\\S]*?https?:\\/\\/(?:[\\w-]+\\.)*smzdm\\.com(?:\\/[^\\s]*)?[.\\s\\S]*)",
            "什么值得买", listOf("com.smzdm.client.android"),
            "点击打开 什么值得买", "打开", "什么值得买", "https://www.smzdm.com/favicon.ico"
        ),
        SeedlingPreset(
            "Steam", "S1T2E3A4-M567-890B-CDEF-GHIJKLMN0123",
            "([.\\s\\S]*?https?:\\/\\/(?:[\\w-]+\\.)*steampowered\\.com(?:\\/[^\\s]*)?[.\\s\\S]*)",
            "Steam", listOf("com.valvesoftware.android.steam.community"),
            "点击打开 Steam", "打开", "Steam", "https://store.steampowered.com/favicon.ico"
        ),
        SeedlingPreset(
            "GitHub", "G1I2T3H4-U567-890B-CDEF-GHIJKLMN0123",
            "([.\\s\\S]*?https?:\\/\\/(?:[\\w-]+\\.)*github\\.com(?:\\/[^\\s]*)?[.\\s\\S]*)",
            "GitHub", listOf("com.github.android"),
            "点击打开 GitHub", "打开", "GitHub", "https://github.githubassets.com/favicons/favicon.svg"
        ),
        SeedlingPreset(
            "知乎", "Z1H2I3H4-U567-890B-CDEF-GHIJKLMN0123",
            "([.\\s\\S]*?https?:\\/\\/(?:[\\w-]+\\.)*zhihu\\.com(?:\\/[^\\s]*)?[.\\s\\S]*)",
            "知乎", listOf("com.zhihu.android"),
            "点击打开 知乎", "打开", "知乎", "https://static.zhihu.com/heifetz/favicon.ico"
        ),
        SeedlingPreset(
            "YouTube", "Y1O2U3T4-U567-890B-CDEF-GHIJKLMN0123",
            "([.\\s\\S]*?https?:\\/\\/(?:[\\w-]+\\.)*(?:youtube\\.com|youtu\\.be)(?:\\/[^\\s]*)?[.\\s\\S]*)",
            "YouTube", listOf("com.google.android.youtube"),
            "点击打开 YouTube", "打开", "YouTube",
            "https://www.youtube.com/s/desktop/6b6c3f39/img/favicon_144x144.png"
        ),
        SeedlingPreset(
            "TapTap", "T1A2P3T4-A567-890B-CDEF-GHIJKLMN0123",
            "([.\\s\\S]*?https?:\\/\\/(?:[\\w-]+\\.)*tap\\.cn(?:\\/[^\\s]*)?[.\\s\\S]*)",
            "TapTap", listOf("com.taptap"),
            "点击打开 TapTap", "打开", "TapTap", "https://www.taptap.cn/favicon.ico"
        ),
        SeedlingPreset(
            "Apple Music", "A1P2P3L4-E567-890M-USIC-ABCDEFGH0123",
            "([.\\s\\S]*?https?:\\/\\/(?:[\\w-]+\\.)*music\\.apple\\.com(?:\\/[^\\s]*)?[.\\s\\S]*)",
            "Apple Music", listOf("com.apple.android.music"),
            "点击打开 Apple Music", "打开", "Apple Music", "https://music.apple.com/favicon.ico"
        ),
        SeedlingPreset(
            "QooApp", "Q1O2O3A4-PP56-7890-BCDE-FGHIJKLMNO12",
            "([.\\s\\S]*?https?:\\/\\/(?:[\\w-]+\\.)*qoo-app\\.com(?:\\/[^\\s]*)?[.\\s\\S]*)",
            "QooApp", listOf("com.qooapp.qoohelper"),
            "点击打开 QooApp", "打开", "QooApp", "https://apps.qoo-app.com/favicon.ico"
        ),
        SeedlingPreset(
            "E-Hentai", "E1H2N3T4-A567-890B-CDEF-GHIJKLMN0123",
            "([.\\s\\S]*?https?:\\/\\/(?:[\\w-]+\\.)*e-hentai\\.org(?:\\/[^\\s]*)?[.\\s\\S]*)",
            "E-Hentai", listOf("moe.tarsin.ehviewer"),
            "点击打开 E-Hentai APP", "打开", "E-Hentai", "https://e-hentai.org/favicon.ico"
        ),
        SeedlingPreset(
            "Pixiv", "P1X2V3A4-RT56-7890-BCDE-FGHIJKLMNO12",
            "([.\\s\\S]*?https?:\\/\\/(?:[\\w-]+\\.)*(?:pixiv\\.net|pixivision\\.me|pixivision\\.net)(?:\\/[^\\s]*)?[.\\s\\S]*)",
            "Pixiv", listOf("ceui.lisa.pixiv"),
            "点击打开 Pixiv APP", "打开", "Pixiv", "https://www.pixiv.net/favicon.ico"
        ),
        SeedlingPreset(
            "Home Assistant", "A1B2C3D4-E5F6-7890-GHIJ-KLMNOPQRSTUV",
            "([.\\s\\S]*?https?:\\/\\/(?:www|my)\\.home-assistant\\.io(?:\\/[^\\s]*)?[.\\s\\S]*)",
            "Home Assistant", listOf("io.homeassistant.companion.android"),
            "点击打开 Home Assistant APP", "打开", "Home Assistant",
            "https://img0.baidu.com/it/u=3247099463,2654053770&fm=253&fmt=auto&app=138&f=JPEG?w=200&h=200"
        ),
        SeedlingPreset(
            "酷家乐", "87654321-DCBA-1234-HGFE-98765LKJIHGF",
            "([.\\s\\S]*?https?:\\/\\/(?:[\\w-]+\\.)*kujiale\\.cn(?:\\/[^\\s]*)?[.\\s\\S]*)",
            "酷家乐", listOf("com.qunhe.designer"),
            "点击打开 酷家乐 APP", "打开", "酷家乐",
            "https://p1.ssl.qhmsg.com/t01d2c1dbba1760efa1.jpg"
        ),
        SeedlingPreset(
            "VVEX", "6CA58498-650E-4FC0-ACED-41A19FD951A5",
            "([.\\s\\S]*?https?:\\/\\/(?:[\\w-]+\\.)*v2ex\\.com(?:\\/[^\\s]*)?[.\\s\\S]*)",
            "VVEX", listOf("com.guozhigq.vvex"),
            "点击打开VVEX APP", "打开", "VVEX",
            "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcTDIC_3TOq99TGfNsoVYTYvOgH7ADhmu8-ljw&s"
        )
    )

    /** 预设 → 规则。platformType 从 10001 起自增，保证唯一且 ≥10000（原版要求） */
    fun seedlingPresetRule(p: SeedlingPreset, index: Int): SeedlingRule = SeedlingRule(
        enable = true,
        uuid = p.uuid,
        name = p.name,
        platformType = 10001 + index,
        supplier = "CPS",
        matchRegex = p.regex,
        appName = p.appName,
        appPackages = p.appPackages,
        cardTitle = p.cardTitle,
        buttonName = p.buttonName,
        title = p.title,
        smallIcon = p.largeIcon,
        largeIcon = p.largeIcon,
        downloadIcon = "",
        eventType = 0
    )
}
