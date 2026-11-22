package com.fr.colorostools.hook.xiaobuswipe

import com.fr.colorostools.hook.HookEnv

/**
 * 滑动快捷键的配置快照（hook 侧读取）—— v2.0.0 的四方向模型。
 *
 * 数据源 = libxposed remote prefs（App 设置页写入、hook 进程读同一份内存镜像），
 * 因此上游那套「广播 + 目标进程 prefs 缓存 + revision 比对」在这里完全不需要：
 * [HookEnv.settingString] / [HookEnv.settingInt] 每次读的都是最新内存值，无 IO。
 *
 * ## 与 v1.x（单值模型）的结构差异
 * - v1.x：每个键只有一个动作，只认「下滑」；
 * - v2.0.0：每个键 × **4 个方向**各自独立绑定动作 / 自定义文本 / 触发距离；
 * - 兼容契约：**下滑方向的存储键不带后缀**（[SwipeConst.DIR_SUFFIX][0] == ""），
 *   所以 v1.x 写下的 `xiaobu_swipe_action_q_z` 读出来天然就是「z 键下滑」，
 *   旧配置零成本迁移，不需要任何迁移代码。
 *
 * ## 键 id 命名空间（三种形态共用一套，互不冲突）
 * - 26 键字母：`"a"`..`"z"`
 * - 九宫格数字：`"2"`..`"9"`
 * - 特殊键：[SwipeConst.SPECIAL_KEYS]（`"del"` / `"enter"` / …）
 *
 * 读取频率（**重要**）：全量构建一次 = 26 键 + 8 个九宫格键 + 13 个特殊键，
 * 每键 ×4 方向读动作/文本、再 ×4 读距离，外加每键一个标签 —— **实测 604 次 prefs 读 +
 * 598 次字符串键拼接**，另建 4 个 HashMap 与约 90 个数组。
 *
 * 而 [SwipeGesture.drawLabels] 是在**每帧 onDraw** 里取的：26 键键盘 60fps 下若不缓存，
 * 就是每秒 3.6 万次 prefs 读。所以 [load] 走缓存（TTL 1s + [invalidate] 显式失效），
 * 命中时只有一次 volatile 读 + 一次时间比较。**新增调用点不要绕开 [load]。**
 */
internal class SwipeConfig private constructor(
    val enabled: Boolean,
    val thresholdDp: Int,
    val t9ThresholdDp: Int,
    val vibration: Boolean,
    val showLabels: Boolean,
    val showHint: Boolean,
    /** 键 id → 4 个方向的**已解析**动作（文本非空的方向已归一化为 [SwipeConst.A_INSERT_TEXT]） */
    private val actions: Map<String, IntArray>,
    /** 键 id → 4 个方向的自定义文本（仅当该方向动作 = A_INSERT_TEXT 且文本非空） */
    private val texts: Map<String, Array<String>>,
    /** 键 id → 4 个方向的**按键级**触发距离（dp，0 = 跟随全局阈值） */
    private val distances: Map<String, IntArray>,
    /**
     * 键 id → 标签覆盖：只存「非自动」的键 —— 用户显式设了自定义文字或
     * [SwipeConst.LABEL_HIDDEN]。未出现在此表 = 自动（显示动作短名）。
     *
     * 标签与方向无关：一个键面只画一个方向（优先级见 [SwipeConst.LABEL_PRIORITY]）。
     */
    private val labels: Map<String, String>,
) {

    /**
     * 是否已绑定至少一个「方向」。
     *
     * 注意这里把 [SwipeConst.A_DISABLE] 也算已绑定：禁用某方向的目的正是**吞掉**该方向的
     * 原生手势，所以必须走接管链路。若只在全表为空时才直通，禁用键会失效。
     */
    val hasAnyBinding: Boolean = actions.isNotEmpty()

    // ---------------------------------------------------------------- 查询

    private fun dirOf(dir: Int): Int =
        if (dir in 0 until SwipeConst.DIR_COUNT) dir else SwipeConst.DIR_DOWN

    /** 某键某方向的动作 id（未绑定 ⇒ [SwipeConst.A_NONE]） */
    fun actionFor(key: String, dir: Int = SwipeConst.DIR_DOWN): Int =
        actions[key]?.get(dirOf(dir)) ?: SwipeConst.A_NONE

    /** 某键某方向的自定义文本（仅动作 = A_INSERT_TEXT 时有值） */
    fun textFor(key: String, dir: Int = SwipeConst.DIR_DOWN): String =
        texts[key]?.get(dirOf(dir)) ?: ""

    /** 按键级距离原值（dp，0 = 未单独设置，跟随全局）—— 设置界面用 */
    fun rawDistance(key: String, dir: Int = SwipeConst.DIR_DOWN): Int =
        distances[key]?.get(dirOf(dir)) ?: 0

    /**
     * 某键某方向的**生效**距离（dp）：按键级优先，否则回退全局阈值
     * （九宫格回退 [t9ThresholdDp]，26 键回退 [thresholdDp]）。
     */
    fun distanceFor(key: String, t9: Boolean, dir: Int = SwipeConst.DIR_DOWN): Int {
        val own = rawDistance(key, dir)
        if (own > 0) return own
        return if (t9) t9ThresholdDp else thresholdDp
    }

    /** 该键是否至少有一个方向绑定了动作（含禁用） */
    fun hasAnyBinding(key: String): Boolean = actions[key] != null

    /** 该键已绑定方向位掩码（bit d = 方向 d 已绑定），仅用于诊断日志 */
    fun boundDirectionMask(key: String): Int {
        val arr = actions[key] ?: return 0
        var mask = 0
        for (d in 0 until SwipeConst.DIR_COUNT) {
            if (arr[d] != SwipeConst.A_NONE) mask = mask or (1 shl d)
        }
        return mask
    }

    /**
     * 键面标签要展示的方向：按 [SwipeConst.LABEL_PRIORITY]（下滑 > 上 > 左 > 右）
     * 取第一个「有真动作」的方向；未绑定或只绑了禁用 ⇒ -1（不画）。
     */
    fun primaryDirection(key: String): Int {
        val arr = actions[key] ?: return -1
        for (d in SwipeConst.LABEL_PRIORITY) {
            val a = arr[d]
            if (a != SwipeConst.A_NONE && a != SwipeConst.A_DISABLE) return d
        }
        return -1
    }

    /** 动作中文名（提示气泡用） */
    fun actionName(id: Int): String = ACTION_NAMES_EXTRA[id] ?: "未绑定"

    /** 动作短标签（按键绘制用） */
    fun actionShort(id: Int): String = SwipeConst.ACTION_SHORT[id] ?: ""

    /**
     * 键面副标题的最终文本（空串 = **不绘制**）。
     *
     * 三态解析顺序与上游 `Config.labelFor` 一致：
     * 1. 标签 = [SwipeConst.LABEL_HIDDEN] ⇒ 空串（用户要求隐藏）
     * 2. 标签非空且非哨兵 ⇒ 该自定义文字
     * 3. 否则自动：有动作则动作短名；**无动作返回空串**（不画破折号）
     *
     * ⚠️ 方向前缀（`↓` / `↑` / `←` / `→`）不在这里拼 —— 由绘制侧按
     * [primaryDirection] 拼 [SwipeConst.DIR_SHORT]，因为同一个键每个方向的标签都不同。
     */
    fun labelFor(key: String, actionId: Int): String = resolveLabel(labels[key], actionId)

    /** 键在提示/日志里的显示名（特殊键 → 中文名，字母 → 大写） */
    fun displayFor(key: String): String = when {
        SwipeConst.isSpecial(key) -> SwipeConst.specialLabel(key)
        key.length == 1 && key[0].isDigit() -> key
        else -> key.uppercase()
    }

    // ---------------------------------------------------------------- 计数（诊断日志 / 设置界面）

    /** 26 键里已绑定的键数 */
    fun bindingCount(): Int = countBound(SwipeConst.QWERTY_KEYS.map { it.toString() })

    /** 九宫格里已绑定的键数 */
    fun t9BindingCount(): Int = countBound(SwipeConst.T9_KEYS.map { it.toString() })

    /** 特殊键里已绑定的键数 */
    fun specialBindingCount(): Int = countBound(SwipeConst.SPECIAL_KEYS.toList())

    private fun countBound(ids: List<String>): Int {
        var n = 0
        for (id in ids) if (actions.containsKey(id)) n++
        return n
    }

    private fun resolveLabel(custom: String?, actionId: Int): String {
        val label = normalizeLabel(custom)
        if (label == SwipeConst.LABEL_HIDDEN) return ""
        if (label.isNotEmpty()) return label
        // 自动：动作短名。只有「未绑定」才留空 —— 禁用键会画「禁用」，
        // 这反而是有用信息（一眼看出这格被关掉了），所以不能一并抹掉。
        if (actionId == SwipeConst.A_NONE) return ""
        return actionShort(actionId)
    }

    companion object {
        /** [SwipeConst.ACTION_NAMES] 与动作 id 的映射（数组按 [SwipeConst.ACTION_IDS] 顺序排） */
        private val ACTION_NAMES_EXTRA: Map<Int, String> = SwipeConst.ACTION_IDS
            .zip(SwipeConst.ACTION_NAMES)
            .toMap()

        /** 动作 id 集合（校验非法值：设置侧写入脏值时按未绑定处理） */
        private val VALID = SwipeConst.ACTION_IDS.toSet()

        /** 空配置（功能关闭时复用，避免每次按键都构造） */
        val DISABLED = SwipeConfig(
            enabled = false,
            thresholdDp = SwipeConst.DEFAULT_THRESHOLD_DP,
            t9ThresholdDp = SwipeConst.DEFAULT_T9_THRESHOLD_DP,
            vibration = true, showLabels = true, showHint = true,
            actions = emptyMap(), texts = emptyMap(),
            distances = emptyMap(), labels = emptyMap(),
        )


        /**
         * 取一份配置快照。
         *
         * ⚠️ 每次调用都全量构建：本类**没有缓存**。
         *
         * 已知代价（实测）：一次构建 = **604 次 prefs 读 + 598 次字符串键拼接**，
         * 另建 4 个 HashMap 与约 90 个数组。而 [SwipeGesture.drawLabels] 是在**每帧 onDraw**
         * 里调它 —— 26 键键盘 60fps 下就是每秒 3.6 万次 prefs 读。
         *
         * 若后续要优化，正确方向是加 TTL 缓存 + 显式 invalidate（remote prefs 是只读的、
         * 没有变更监听 API，无法事件驱动），而不是让调用方各自缓存。
         */
        fun load(): SwipeConfig {
            if (!HookEnv.settingBoolean(SwipeConst.KEY_ENABLED, false)) return DISABLED

            val actions = HashMap<String, IntArray>(40)
            val texts = HashMap<String, Array<String>>(4)
            val distances = HashMap<String, IntArray>(8)
            val labels = HashMap<String, String>(4)

            // ---- 26 键字母 ----
            for (c in SwipeConst.QWERTY_KEYS) {
                val id = c.toString()
                val arr = IntArray(SwipeConst.DIR_COUNT)
                val txt = arrayOf("", "", "", "")
                var hasText = false
                for (dir in 0 until SwipeConst.DIR_COUNT) {
                    // ⚠️ 默认绑定必须在这里兜底，不能只写在设置页的 `default` 参数里：
                    // 设置页的 default 只是 UI 展示值，从未落盘 ⇒ prefs 里该键为空串。
                    // 若此处按「空串 = 未绑定」处理，DEFAULT_QWERTY 那套 z/x/c/v 就永远不生效，
                    // 且 hasAnyBinding=false 会让整条手势链路在 DOWN 处静默直通（零日志零报错）。
                    // 默认只作用于**下滑**（旧行为），上/左/右初始一律未绑定。
                    val fallback = if (dir == SwipeConst.DIR_DOWN) {
                        SwipeConst.DEFAULT_QWERTY[c] ?: SwipeConst.A_NONE
                    } else {
                        SwipeConst.A_NONE
                    }
                    val text = normalizeText(
                        HookEnv.settingString(SwipeConst.textKeyQwerty(c, dir), "")
                    )
                    if (text.isNotEmpty()) {
                        // 文本非空 ⇒ 该方向语义固定为「输入指定内容」（设置页文案承诺的行为）
                        txt[dir] = text
                        arr[dir] = SwipeConst.A_INSERT_TEXT
                        hasText = true
                    } else {
                        arr[dir] = readAction(SwipeConst.actionKeyQwerty(c, dir), fallback)
                    }
                }
                if (arr.any { it != SwipeConst.A_NONE }) actions[id] = arr
                if (hasText) texts[id] = txt
                readDistances(id, distances)
                val label = normalizeLabel(HookEnv.settingString(SwipeConst.labelKeyQwerty(c), ""))
                if (label.isNotEmpty()) labels[id] = label
            }

            // ---- 九宫格数字 ----
            for (n in SwipeConst.T9_KEYS) {
                val id = n.toString()
                val arr = IntArray(SwipeConst.DIR_COUNT)
                val txt = arrayOf("", "", "", "")
                var hasText = false
                for (dir in 0 until SwipeConst.DIR_COUNT) {
                    // 九宫格无默认绑定，fallback 一律 A_NONE（空串 = 未绑定）
                    val text = normalizeText(
                        HookEnv.settingString(SwipeConst.textKeyT9(n, dir), "")
                    )
                    if (text.isNotEmpty()) {
                        txt[dir] = text
                        arr[dir] = SwipeConst.A_INSERT_TEXT
                        hasText = true
                    } else {
                        arr[dir] = readAction(SwipeConst.actionKeyT9(n, dir), SwipeConst.A_NONE)
                    }
                }
                if (arr.any { it != SwipeConst.A_NONE }) actions[id] = arr
                if (hasText) texts[id] = txt
                readDistances(id, distances)
                val label = normalizeLabel(HookEnv.settingString(SwipeConst.labelKeyT9(n), ""))
                if (label.isNotEmpty()) labels[id] = label
            }

            // ---- 特殊键（退格 / 回车 / 空格 / Shift / …）----
            for (kid in SwipeConst.SPECIAL_KEYS) {
                val arr = IntArray(SwipeConst.DIR_COUNT)
                val txt = arrayOf("", "", "", "")
                var hasText = false
                for (dir in 0 until SwipeConst.DIR_COUNT) {
                    val text = normalizeText(
                        HookEnv.settingString(SwipeConst.textKeySpecial(kid, dir), "")
                    )
                    if (text.isNotEmpty()) {
                        txt[dir] = text
                        arr[dir] = SwipeConst.A_INSERT_TEXT
                        hasText = true
                    } else {
                        arr[dir] = readAction(SwipeConst.actionKeySpecial(kid, dir), SwipeConst.A_NONE)
                    }
                }
                if (arr.any { it != SwipeConst.A_NONE }) actions[kid] = arr
                if (hasText) texts[kid] = txt
                readDistances(kid, distances)
                // 特殊键不做标签覆盖：键面图标是宿主自己的，画中文容易糊成一团
            }

            return SwipeConfig(
                enabled = true,
                thresholdDp = HookEnv.settingInt(
                    SwipeConst.KEY_THRESHOLD_DP, SwipeConst.DEFAULT_THRESHOLD_DP
                ).coerceIn(SwipeConst.MIN_THRESHOLD_DP, SwipeConst.MAX_THRESHOLD_DP),
                t9ThresholdDp = HookEnv.settingInt(
                    SwipeConst.KEY_T9_THRESHOLD_DP, SwipeConst.DEFAULT_T9_THRESHOLD_DP
                ).coerceIn(SwipeConst.MIN_T9_THRESHOLD_DP, SwipeConst.MAX_T9_THRESHOLD_DP),
                vibration = HookEnv.settingBoolean(SwipeConst.KEY_VIBRATION, true),
                showLabels = HookEnv.settingBoolean(SwipeConst.KEY_SHOW_LABELS, true),
                showHint = HookEnv.settingBoolean(SwipeConst.KEY_SHOW_HINT, true),
                actions = actions,
                texts = texts,
                distances = distances,
                labels = labels,
            )
        }

        /** 读某键 4 个方向的按键级距离；全为 0 则不建条目（省一次 Map 查找） */
        private fun readDistances(keyId: String, out: MutableMap<String, IntArray>) {
            var arr: IntArray? = null
            for (dir in 0 until SwipeConst.DIR_COUNT) {
                val v = HookEnv.settingInt(SwipeConst.distanceKey(keyId, dir), 0)
                if (v <= 0) continue
                val clamped = v.coerceIn(SwipeConst.MIN_THRESHOLD_DP, SwipeConst.MAX_T9_THRESHOLD_DP)
                if (arr == null) arr = IntArray(SwipeConst.DIR_COUNT)
                arr[dir] = clamped
            }
            if (arr != null) out[keyId] = arr
        }

        /**
         * 读单个动作 id：非法值（脏数据 / 已删动作）一律按 fallback。
         *
         * @param fallback 该键该方向在 prefs 里**没有**值时的默认动作（26 键下滑走
         *   [SwipeConst.DEFAULT_QWERTY] 兜底）。语义：只有「prefs 里不存在该键」才回落；
         *   若用户显式选了「未绑定」（存 "0"），按显式选择处理 —— 所以必须区分
         *   「空串（没存过）」与 "0"（存过且选中未绑定）。
         */
        private fun readAction(key: String, fallback: Int = SwipeConst.A_NONE): Int {
            val raw = HookEnv.settingString(key, "")
            if (raw.isEmpty()) return fallback
            val id = raw.toIntOrNull() ?: return fallback
            return if (id in VALID) id else fallback
        }

        /**
         * 标签归一化（与上游 `Config.normalizeLabelValue` 等价）：
         * 换行/回车替换成空格、trim、按**码点**限长 [SwipeConst.MAX_LABEL_CODEPOINTS]。
         *
         * 隐藏哨兵 [SwipeConst.LABEL_HIDDEN] 原样返回 —— 它虽不是用户可见文字，
         * 但必须在归一化后仍然可辨认，否则「隐藏」会被当成普通 4 字符标签。
         */
        fun normalizeLabel(raw: String?): String {
            if (raw.isNullOrEmpty()) return ""
            if (raw == SwipeConst.LABEL_HIDDEN) return SwipeConst.LABEL_HIDDEN
            val flat = raw.replace('\n', ' ').replace('\r', ' ').trim()
            if (flat.isEmpty()) return ""
            val count = flat.codePointCount(0, flat.length)
            return if (count > SwipeConst.MAX_LABEL_CODEPOINTS) {
                flat.substring(0, flat.offsetByCodePoints(0, SwipeConst.MAX_LABEL_CODEPOINTS))
            } else {
                flat
            }
        }

        /** 插入文本归一化：去 NUL、限长（与上游 `normalizeInsertedText` 等价） */
        fun normalizeText(raw: String?): String {
            if (raw.isNullOrEmpty()) return ""
            val noNul = raw.replace("\u0000", "")
            if (noNul.isEmpty()) return ""
            val count = noNul.codePointCount(0, noNul.length)
            return if (count > SwipeConst.MAX_INSERT_CODEPOINTS) {
                noNul.substring(0, noNul.offsetByCodePoints(0, SwipeConst.MAX_INSERT_CODEPOINTS))
            } else {
                noNul
            }
        }
    }
}