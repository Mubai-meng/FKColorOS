package com.fr.colorostools.hook.xiaobuswipe

import com.fr.colorostools.hook.HookEnv

/**
 * 下滑快捷键的配置快照（hook 侧读取）。
 *
 * 数据源 = libxposed remote prefs（App 设置页写入、hook 进程读同一份内存镜像），
 * 因此参考模块那套「广播 + 目标进程 prefs 缓存 + revision 比对」在这里完全不需要：
 * [HookEnv.settingString] / [HookEnv.settingInt] 每次读的都是最新内存值，无 IO。
 *
 * 读取频率：每次按键（DOWN）读一次整份快照。字符串键共 26+8 个，全部命中内存 Map，
 * 单次开销在微秒级，按键级频率下可忽略。
 */
internal class SwipeConfig private constructor(
    val enabled: Boolean,
    val thresholdDp: Int,
    val t9ThresholdDp: Int,
    val vibration: Boolean,
    val showLabels: Boolean,
    val showHint: Boolean,
    /** 26 键（小写字母 → 动作 id），只有已绑定的键才有条目 */
    private val qwertyActions: Map<Char, Int>,
    /** 九宫格（数字 2..9 → 动作 id） */
    private val t9Actions: Map<Int, Int>,
    /** 26 键自定义文本（动作 = 输入指定内容时使用），空串表示无 */
    private val qwertyTexts: Map<Char, String>,
    private val t9Texts: Map<Int, String>,
    /**
     * 26 键标签覆盖：只存「非自动」的键 —— 即用户显式设了自定义文字或 [SwipeConst.LABEL_HIDDEN]。
     * 未出现在此表 = 自动（显示动作短名）。
     */
    private val qwertyLabels: Map<Char, String>,
    private val t9Labels: Map<Int, String>,
) {

    /** 是否已绑定至少一个动作（未绑定时整条手势链路直通，零开销） */
    val hasAnyBinding: Boolean = qwertyActions.isNotEmpty() || t9Actions.isNotEmpty() ||
        qwertyTexts.isNotEmpty() || t9Texts.isNotEmpty()

    /** 26 键已绑定键数（诊断日志用） */
    fun bindingCount(): Int = qwertyActions.size

    /** 九宫格已绑定键数（诊断日志用） */
    fun t9BindingCount(): Int = t9Actions.size

    /** 取 26 键某键动作 */
    fun actionForQwerty(c: Char): Int = qwertyActions[c.lowercaseChar()] ?: SwipeConst.A_NONE

    /** 取九宫格某键动作 */
    fun actionForT9(n: Int): Int = t9Actions[n] ?: SwipeConst.A_NONE

    /** 取 26 键自定义文本 */
    fun textForQwerty(c: Char): String = qwertyTexts[c.lowercaseChar()] ?: ""

    /** 取九宫格自定义文本 */
    fun textForT9(n: Int): String = t9Texts[n] ?: ""

    /** 动作中文名（提示气泡用） */
    fun actionName(id: Int): String = ACTION_NAMES_EXTRA[id] ?: "未绑定"

    /** 动作短标签（按键绘制用） */
    fun actionShort(id: Int): String = SwipeConst.ACTION_SHORT[id] ?: ""

    /**
     * 26 键键面副标题的最终文本（空串 = **不绘制**）。
     *
     * 三态解析顺序与参考模块 `Config.labelFor` 一致：
     * 1. 标签 = [SwipeConst.LABEL_HIDDEN] ⇒ 返回空串（用户要求隐藏）
     * 2. 标签非空且非哨兵 ⇒ 返回该自定义文字
     * 3. 否则自动：有动作则动作短名；**无动作返回空串**（不画 `—`，避免整盘都是破折号）
     */
    fun labelForQwerty(c: Char, actionId: Int): String =
        resolveLabel(qwertyLabels[c.lowercaseChar()], actionId)

    /** 九宫格键面副标题的最终文本（空串 = 不绘制），规则同 [labelForQwerty] */
    fun labelForT9(n: Int, actionId: Int): String =
        resolveLabel(t9Labels[n], actionId)

    private fun resolveLabel(custom: String?, actionId: Int): String {
        val label = normalizeLabel(custom)
        if (label == SwipeConst.LABEL_HIDDEN) return ""
        if (label.isNotEmpty()) return label
        // 自动：动作短名。只有「未绑定」才留空 —— 参考模块会给禁用键画「禁用」，
        // 这反而是有用的信息（一眼看出这格下滑被关掉了），所以不能一并抹掉。
        if (actionId == SwipeConst.A_NONE) return ""
        return actionShort(actionId)
    }

    companion object {
        /** [ACTION_NAMES] 之外的兜底（动作 id 与数组下标不连续，数组按 ACTION_IDS 顺序排） */
        private val ACTION_NAMES_EXTRA: Map<Int, String> = SwipeConst.ACTION_IDS
            .zip(SwipeConst.ACTION_NAMES)
            .toMap()

        /** 动作 id 集合（校验非法值：设置侧写入脏值时按未绑定处理） */
        private val VALID = SwipeConst.ACTION_IDS.toSet()

        /** 空配置（功能关闭时复用，避免每次按键都构造） */
        val DISABLED = SwipeConfig(
            enabled = false, thresholdDp = SwipeConst.DEFAULT_THRESHOLD_DP,
            t9ThresholdDp = SwipeConst.DEFAULT_T9_THRESHOLD_DP,
            vibration = true, showLabels = true, showHint = true,
            qwertyActions = emptyMap(), t9Actions = emptyMap(),
            qwertyTexts = emptyMap(), t9Texts = emptyMap(),
            qwertyLabels = emptyMap(), t9Labels = emptyMap(),
        )

        /** 从 remote prefs 读一份快照 */
        fun load(): SwipeConfig {
            if (!HookEnv.settingBoolean(SwipeConst.KEY_ENABLED, false)) return DISABLED

            val qwerty = LinkedHashMap<Char, Int>(8)
            val qwertyTexts = LinkedHashMap<Char, String>(0)
            // 标签覆盖：只收「非自动」的键（自定义文字或隐藏哨兵），未出现的键走自动
            val qwertyLabels = LinkedHashMap<Char, String>(0)
            for (c in SwipeConst.QWERTY_KEYS) {
                // ⚠️ 默认绑定必须在这里兜底，不能只写在设置页的 `default` 参数里：
                // 设置页的 default 只是 UI 展示值，从未落盘 ⇒ prefs 里该键为空串。
                // 若此处按「空串 = 未绑定」处理，DEFAULT_QWERTY 那套 z/x/c/v 就永远不生效，
                // 且 hasAnyBinding=false 会让整条手势链路在第一行静默直通（零日志、零报错）。
                val action = readAction(
                    SwipeConst.actionKeyQwerty(c),
                    SwipeConst.DEFAULT_QWERTY[c] ?: SwipeConst.A_NONE,
                )
                // 自定义文本优先：文本非空即视为「输入指定内容」，与参考模块 assignQwertyText 语义一致
                val text = normalizeText(HookEnv.settingString(SwipeConst.textKeyQwerty(c), ""))
                if (text.isNotEmpty()) {
                    // 文本非空 ⇒ 语义固定为「输入指定内容」，这是设置页文案承诺的行为
                    qwertyTexts[c] = text
                    qwerty[c] = SwipeConst.A_INSERT_TEXT
                } else if (action != SwipeConst.A_NONE) {
                    qwerty[c] = action
                }
                val label = normalizeLabel(HookEnv.settingString(SwipeConst.labelKeyQwerty(c), ""))
                if (label.isNotEmpty()) qwertyLabels[c] = label
            }

            val t9 = LinkedHashMap<Int, Int>(4)
            val t9Texts = LinkedHashMap<Int, String>(0)
            val t9Labels = LinkedHashMap<Int, String>(0)
            for (n in SwipeConst.T9_KEYS) {
                // 九宫格无默认绑定（DEFAULT_T9 全未绑定），传 A_NONE 即「空串 = 未绑定」
                val action = readAction(SwipeConst.actionKeyT9(n), SwipeConst.A_NONE)
                val text = normalizeText(HookEnv.settingString(SwipeConst.textKeyT9(n), ""))
                if (text.isNotEmpty()) {
                    t9Texts[n] = text
                    t9[n] = SwipeConst.A_INSERT_TEXT
                } else if (action != SwipeConst.A_NONE) {
                    t9[n] = action
                }
                val label = normalizeLabel(HookEnv.settingString(SwipeConst.labelKeyT9(n), ""))
                if (label.isNotEmpty()) t9Labels[n] = label
            }

            return SwipeConfig(
                enabled = true,
                thresholdDp = HookEnv.settingInt(SwipeConst.KEY_THRESHOLD_DP, SwipeConst.DEFAULT_THRESHOLD_DP)
                    .coerceIn(SwipeConst.MIN_THRESHOLD_DP, SwipeConst.MAX_THRESHOLD_DP),
                t9ThresholdDp = HookEnv.settingInt(
                    SwipeConst.KEY_T9_THRESHOLD_DP, SwipeConst.DEFAULT_T9_THRESHOLD_DP
                ).coerceIn(SwipeConst.MIN_T9_THRESHOLD_DP, SwipeConst.MAX_T9_THRESHOLD_DP),
                vibration = HookEnv.settingBoolean(SwipeConst.KEY_VIBRATION, true),
                showLabels = HookEnv.settingBoolean(SwipeConst.KEY_SHOW_LABELS, true),
                showHint = HookEnv.settingBoolean(SwipeConst.KEY_SHOW_HINT, true),
                qwertyActions = qwerty,
                t9Actions = t9,
                qwertyTexts = qwertyTexts,
                t9Texts = t9Texts,
                qwertyLabels = qwertyLabels,
                t9Labels = t9Labels,
            )
        }

        /**
         * 读单个动作 id：非法值（脏数据 / 已删动作）一律按未绑定。
         *
         * @param fallback 该键在 prefs 里**没有**值时的默认动作（26 键走 [SwipeConst.DEFAULT_QWERTY] 兜底）。
         *   注意语义：只有「prefs 里不存在该键」才回落 fallback；若用户显式选了「未绑定」（存 "0"），
         *   则按用户的显式选择处理 —— 所以这里必须区分「空串（没存过）」与 "0"（存过且选中未绑定）。
         */
        private fun readAction(key: String, fallback: Int = SwipeConst.A_NONE): Int {
            val raw = HookEnv.settingString(key, "")
            // 空串 = 该键从未写入 ⇒ 用默认绑定（用户没动过这一格）
            if (raw.isEmpty()) return fallback
            val id = raw.toIntOrNull() ?: return fallback
            return if (id in VALID) id else fallback
        }

        /**
         * 标签归一化（与参考模块 `Config.normalizeLabelValue` 等价）：
         * 换行/回车替换成空格、trim、按**码点**限长 [SwipeConst.MAX_LABEL_CODEPOINTS]。
         *
         * 隐藏哨兵 [SwipeConst.LABEL_HIDDEN] 原样返回 —— 它虽然不是用户可见文字，
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

        /** 插入文本归一化：去 NUL、限长（与参考模块 normalizeInsertedText 等价） */
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