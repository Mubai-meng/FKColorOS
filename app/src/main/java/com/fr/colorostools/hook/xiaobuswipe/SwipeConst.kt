package com.fr.colorostools.hook.xiaobuswipe

/**
 * 小布输入法「按键滑动快捷键」的共享常量：动作表、键位表、方向表、特殊键表、偏好键名、默认绑定。
 *
 * 语义移植自上游 `app.xiaobuswipe` v2.0.0（`Config` / `ActionRegistry` / `KeyResolver` /
 * `ConfigCodec`），但**不照搬它的位标志实现**：上游 `FLAG_*` 位与动作 id 混在同一组数字里
 * （反编译常量内联后全部糊成一片），这里改成「动作 id 独立编号 + 分类函数显式判断」，
 * 行为等价、可读性正常。
 *
 * 配置通道与上游最大的差别：上游为了跨进程同步自建了 `BroadcastReceiver` +
 * 目标进程内 prefs 缓存（`xiaobu_swipe_module_cache_v1`）；本模块走 libxposed remote prefs
 * （App 写、hook 读同一份内存镜像），**不需要广播与缓存**，每次按键读一次内存值即可。
 *
 * ## v2.0.0 的结构性变化（本文件的对齐点）
 * 1. 单动作模型 → **四方向模型**：每个键 × 每个方向各自独立绑定动作/文本/触发距离；
 * 2. 新增 **13 个特殊键**（退格/回车/空格/Shift/符号/123/逗号/句号/拼英/重输/@#/问号/感叹号），
 *    它们自带原生手势，需要按方向屏蔽；
 * 3. 新增动作 `A_TOGGLE_CAPS`（大小写模式切换）；
 * 4. 九宫格触发距离上限由 48dp 放宽到 200dp；
 * 5. 方向后缀机制：**下滑沿用旧键名（无后缀）**，上/左/右加 `@up` / `@left` / `@right`，
 *    因此 v1.x 的历史配置天然就是「全部落在下滑方向」，无需额外迁移代码。
 */
internal object SwipeConst {

    /** 日志 tag（与模块其余 hook 区分，便于筛日志） */
    const val TAG = "XiaoBuSwipe"

    /** 宿主输入法包名 */
    const val TARGET = "com.oplus.keyboard"

    // ---------------------------------------------------------------- 动作 id
    //
    // 编号与上游一致（便于对照它的逆向产物），语义一一对应。

    const val A_NONE = 0

    const val A_SELECT_ALL = 1
    const val A_CUT = 2
    const val A_COPY = 3
    const val A_PASTE = 4
    const val A_DISABLE = 5          // 禁用该键该方向（同时屏蔽宿主原生同方向手势）
    const val A_PARAGRAPH_START = 6
    const val A_PARAGRAPH_END = 7
    const val A_SELECT_TO_PARAGRAPH_START = 8
    const val A_SELECT_TO_PARAGRAPH_END = 9
    const val A_OPEN_CLIPBOARD = 10   // 打开原生剪贴板面板
    const val A_OPEN_QUICK_PHRASE = 11 // 打开原生快捷短语面板
    const val A_COPY_ALL = 12
    const val A_CUT_ALL = 13
    const val A_UNDO = 14
    const val A_REDO = 15
    const val A_DOCUMENT_START = 16
    const val A_DOCUMENT_END = 17
    const val A_SELECT_TO_DOCUMENT_START = 18
    const val A_SELECT_TO_DOCUMENT_END = 19
    const val A_INSERT_TEXT = 20      // 输入指定内容（每键每方向一个文本）
    const val A_NEXT_IME = 21
    const val A_PREV_IME = 22
    const val A_IME_PICKER = 23
    const val A_PASTE_AND_SEND = 24
    const val A_SEND_EXISTING = 25
    /** 大小写模式切换：在大写锁定与小写之间切换（内部仍走宿主的 ShiftState 状态机） */
    const val A_TOGGLE_CAPS = 26

    /**
     * 动作名（设置页下拉与提示文案共用）。
     *
     * 顺序 = 设置页下拉顺序：常用编辑动作在前，导航类其次，面板/特殊键在后。
     * 与上游 `ActionRegistry.MENU_ORDER` 一致（大小写切换前移进常用区）。
     */
    val ACTION_IDS = intArrayOf(
        A_NONE,
        A_SELECT_ALL, A_CUT, A_COPY, A_PASTE,
        A_COPY_ALL, A_CUT_ALL,
        A_PARAGRAPH_START, A_PARAGRAPH_END,
        A_SELECT_TO_PARAGRAPH_START, A_SELECT_TO_PARAGRAPH_END,
        A_DOCUMENT_START, A_DOCUMENT_END,
        A_SELECT_TO_DOCUMENT_START, A_SELECT_TO_DOCUMENT_END,
        A_OPEN_CLIPBOARD, A_OPEN_QUICK_PHRASE,
        A_UNDO, A_REDO,
        A_TOGGLE_CAPS,
        A_INSERT_TEXT,
        A_NEXT_IME, A_PREV_IME, A_IME_PICKER,
        A_PASTE_AND_SEND, A_SEND_EXISTING,
        A_DISABLE,
    )

    /** 动作中文名（设置页下拉显示项） */
    val ACTION_NAMES = arrayOf(
        "未绑定",
        "全选", "剪切", "复制", "粘贴",
        "复制全部", "剪切全部",
        "段首", "段尾",
        "选至段首", "选至段尾",
        "文首", "文尾",
        "选至文首", "选至文尾",
        "剪贴板", "快捷短语",
        "撤销", "重做",
        "大小写切换",
        "输入指定内容",
        "下一个输入法", "上一个输入法", "选择输入法",
        "粘贴并发送", "发送已有内容",
        "禁用该方向",
    )

    /** 动作短标签（画在按键上；键宽有限，最多两个汉字） */
    val ACTION_SHORT = mapOf(
        A_NONE to "",
        A_SELECT_ALL to "全选",
        A_CUT to "剪切",
        A_COPY to "复制",
        A_PASTE to "粘贴",
        A_COPY_ALL to "全复制",
        A_CUT_ALL to "全剪切",
        A_UNDO to "撤销",
        A_REDO to "重做",
        A_PARAGRAPH_START to "段首",
        A_PARAGRAPH_END to "段尾",
        A_SELECT_TO_PARAGRAPH_START to "选前",
        A_SELECT_TO_PARAGRAPH_END to "选后",
        A_DOCUMENT_START to "文首",
        A_DOCUMENT_END to "文尾",
        A_SELECT_TO_DOCUMENT_START to "选文首",
        A_SELECT_TO_DOCUMENT_END to "选文尾",
        A_OPEN_CLIPBOARD to "剪贴",
        A_OPEN_QUICK_PHRASE to "快捷",
        A_INSERT_TEXT to "文本",
        A_TOGGLE_CAPS to "大小写",
        A_NEXT_IME to "下个输入",
        A_PREV_IME to "上个输入",
        A_IME_PICKER to "选输入法",
        A_PASTE_AND_SEND to "粘贴发",
        A_SEND_EXISTING to "发送",
        A_DISABLE to "禁用",
    )

    // ---------------------------------------------------------------- 方向
    //
    // 方向索引固定，序列化后缀见 [DIR_SUFFIX]。**下滑 == 0 == 无后缀**，
    // 这是 v1.x 配置零成本兼容的唯一关键：旧键名读出来天然就是下滑方向的绑定。

    const val DIR_DOWN = 0
    const val DIR_UP = 1
    const val DIR_LEFT = 2
    const val DIR_RIGHT = 3
    const val DIR_COUNT = 4

    /** 方向选择列表的展示顺序：上、下、左、右 */
    val DIR_DISPLAY_ORDER = intArrayOf(DIR_UP, DIR_DOWN, DIR_LEFT, DIR_RIGHT)

    /** 下滑沿用旧存储键（无后缀）以兼容历史配置 */
    val DIR_SUFFIX = arrayOf("", "@up", "@left", "@right")

    val DIR_NAMES = arrayOf("下滑", "上滑", "左滑", "右滑")

    val DIR_SHORT = arrayOf("↓", "↑", "←", "→")

    /** 键面标签只展示一个方向时的优先级：下滑优先，其次上/左/右（与上游一致） */
    val LABEL_PRIORITY = intArrayOf(DIR_DOWN, DIR_UP, DIR_LEFT, DIR_RIGHT)

    // ---------------------------------------------------------------- 键位

    /** 26 键字母（下拉绑定的键序） */
    val QWERTY_KEYS: List<Char> = ('a'..'z').toList()

    /** 九宫格数字键 2..9 */
    val T9_KEYS: List<Int> = (2..9).toList()

    /**
     * 默认绑定（与上游一致）：`z` 全选、`x` 剪切、`c` 复制、`v` 粘贴。
     *
     * 这四个是桌面键盘习惯（Ctrl+Z/X/C/V），用户上手即可用，其余键默认未绑定。
     * ⚠️ 只作用于**下滑**方向，且必须在**读取侧**兜底（见 [SwipeConfig.load]）。
     */
    val DEFAULT_QWERTY: Map<Char, Int> = mapOf(
        'z' to A_SELECT_ALL,
        'x' to A_CUT,
        'c' to A_COPY,
        'v' to A_PASTE,
    )

    // ---------------------------------------------------------------- 特殊键
    //
    // 非字母/数字键：宿主自带原生手势（退格上滑删整段、空格左右移光标等），
    // 用户一旦在这些键上绑定动作，该方向必须整段吞掉事件以屏蔽原生行为。

    const val K_DEL = "del"
    const val K_ENTER = "enter"
    const val K_SPACE = "space"
    const val K_SHIFT = "shift"
    const val K_SYMBOL = "symbol"
    const val K_NUMBER = "number"
    const val K_COMMA = "comma"
    const val K_PERIOD = "period"
    const val K_LANG = "lang"
    const val K_CLEAR = "clear"
    const val K_SYMBOLS1 = "symbols1"
    const val K_QUESTION = "question"
    const val K_EXCLAMATION = "exclamation"

    /** 顺序即设置界面展示顺序（与上游 `SPECIAL_KEYS` 一致） */
    val SPECIAL_KEYS = arrayOf(
        K_DEL, K_ENTER, K_SPACE, K_SHIFT, K_SYMBOL, K_NUMBER,
        K_COMMA, K_PERIOD, K_LANG, K_CLEAR, K_SYMBOLS1,
        K_QUESTION, K_EXCLAMATION,
    )

    /** 设置页里给用户看的中文名 */
    val SPECIAL_LABELS = arrayOf(
        "退格键", "回车键", "空格键", "大小写切换键", "符号键", "123 键",
        "逗号键", "句号键", "拼/英键", "重输键", "@# 键",
        "问号键", "感叹号键",
    )

    /** 键面图标（纯文本），尽量对齐小布键面实际显示 */
    val SPECIAL_GLYPHS = arrayOf(
        "⌫", "换行", "空格", "⇧", "符号", "123",
        "，", "。", "拼/英", "重输", "@#",
        "？", "！",
    )

    /** 该键出现在哪种键盘里：0=两种都有，1=仅 26 键，2=仅九宫格 */
    const val MODE_BOTH = 0
    const val MODE_QWERTY = 1
    const val MODE_T9 = 2

    val SPECIAL_MODES = intArrayOf(
        MODE_BOTH,      // del
        MODE_BOTH,      // enter
        MODE_BOTH,      // space
        MODE_QWERTY,    // shift
        MODE_BOTH,      // symbol
        MODE_BOTH,      // number
        MODE_BOTH,      // comma
        MODE_BOTH,      // period
        MODE_BOTH,      // lang
        MODE_T9,        // clear（重输）
        MODE_T9,        // symbols1（@#）
        MODE_T9,        // question
        MODE_T9,        // exclamation
    )

    /** 特殊键 id → 数组下标；非特殊键返回 -1 */
    fun specialIndex(keyId: String?): Int {
        if (keyId == null) return -1
        for (i in SPECIAL_KEYS.indices) if (SPECIAL_KEYS[i] == keyId) return i
        return -1
    }

    fun isSpecial(keyId: String?): Boolean = specialIndex(keyId) >= 0

    /** 该特殊键 id 是否可能出现在九宫格里 */
    fun specialVisibleInT9(keyId: String): Boolean {
        val i = specialIndex(keyId)
        return i >= 0 && SPECIAL_MODES[i] != MODE_QWERTY
    }

    /** 该特殊键 id 是否可能出现在 26 键里 */
    fun specialVisibleInQwerty(keyId: String): Boolean {
        val i = specialIndex(keyId)
        return i >= 0 && SPECIAL_MODES[i] != MODE_T9
    }

    /** 特殊键在界面/日志里显示的名字 */
    fun specialLabel(keyId: String): String {
        val i = specialIndex(keyId)
        return if (i >= 0) SPECIAL_LABELS[i] else keyId
    }

    // ---------------------------------------------------------------- 偏好键
    //
    // 全部走 SettingsPrefs（remote prefs）；前缀 `xiaobu_swipe_` 与其它功能互不干扰。
    //
    // ⚠️ 方向后缀规则是本模块与 v1.x 的**唯一兼容契约**：下滑 = 空后缀 = 旧键名。
    // 上/左/右 = `@up` / `@left` / `@right`，旧版本程序读不到也不会误读。

    const val KEY_ENABLED = "xiaobu_swipe_enabled"
    const val KEY_THRESHOLD_DP = "xiaobu_swipe_threshold_dp"
    const val KEY_T9_THRESHOLD_DP = "xiaobu_swipe_t9_threshold_dp"
    const val KEY_VIBRATION = "xiaobu_swipe_vibration"
    const val KEY_SHOW_LABELS = "xiaobu_swipe_show_labels"
    const val KEY_SHOW_HINT = "xiaobu_swipe_show_hint"

    private fun suffix(dir: Int): String =
        DIR_SUFFIX[(dir.takeIf { it in 0 until DIR_COUNT } ?: DIR_DOWN)]

    /** 26 键某方向的动作键：`xiaobu_swipe_action_q_<c>[@dir]`（存动作 id 字符串） */
    fun actionKeyQwerty(c: Char, dir: Int = DIR_DOWN): String =
        "xiaobu_swipe_action_q_$c${suffix(dir)}"

    /** 九宫格某方向的动作键：`xiaobu_swipe_action_t9_<n>[@dir]` */
    fun actionKeyT9(n: Int, dir: Int = DIR_DOWN): String =
        "xiaobu_swipe_action_t9_$n${suffix(dir)}"

    /**
     * 特殊键某方向的动作键：`xiaobu_swipe_special_<id>[@dir]`。
     *
     * 与上游 `special_<id><suffix>` 前缀不同（本模块统一加 `xiaobu_swipe_` 前缀防撞），
     * 但结构同构：id 在中间、方向后缀在末尾。
     */
    fun actionKeySpecial(id: String, dir: Int = DIR_DOWN): String =
        "xiaobu_swipe_special_$id${suffix(dir)}"

    /** 单键自定义文本（动作 = 输入指定内容时使用） */
    fun textKeyQwerty(c: Char, dir: Int = DIR_DOWN): String =
        "xiaobu_swipe_text_q_$c${suffix(dir)}"

    fun textKeyT9(n: Int, dir: Int = DIR_DOWN): String =
        "xiaobu_swipe_text_t9_$n${suffix(dir)}"

    fun textKeySpecial(id: String, dir: Int = DIR_DOWN): String =
        "xiaobu_swipe_special_text_$id${suffix(dir)}"

    /**
     * 按键级触发距离：`xiaobu_swipe_dist_<keyId>[@dir]`（单位 dp，0 = 跟随全局）。
     *
     * keyId 三种形态共用一套命名空间：字母 `a`..`z`、数字 `2`..`9`、特殊键 `del`/`enter`/…
     */
    fun distanceKey(keyId: String, dir: Int = DIR_DOWN): String =
        "xiaobu_swipe_dist_$keyId${suffix(dir)}"

    /**
     * 单键**标签覆盖**（键面副标题显示什么）。标签与方向无关（一个键只画一个方向）。
     *
     * 三状态语义（对齐上游 `Config.normalizeLabelValue` / `LABEL_HIDDEN`）：
     * - 未设置（prefs 无该键 / 空串）⇒ **自动**：显示动作短名
     * - [LABEL_HIDDEN] ⇒ **隐藏**该键的标签（动作仍然照常可滑动触发）
     * - 其它非空串 ⇒ **自定义文字**（最多 4 个字符）
     *
     * ⚠️ 标签只影响**绘制**，不影响手势命中与动作执行 —— 隐藏标签不等于禁用，
     * 后者是动作值 [A_DISABLE]，两者是彼此独立的两件事。
     */
    fun labelKeyQwerty(c: Char): String = "xiaobu_swipe_label_q_$c"
    fun labelKeyT9(n: Int): String = "xiaobu_swipe_label_t9_$n"

    /** 标签隐藏哨兵值（与上游字面量一致，便于对照逆向产物） */
    const val LABEL_HIDDEN = "__HIDDEN__"

    /** 自定义标签最大字符数（码点；上游同样是 4） */
    const val MAX_LABEL_CODEPOINTS = 4

    /** 触发距离默认值（dp）：26 键 12dp、九宫格 20dp（九宫格键更高更密，需更长距离防误触） */
    const val DEFAULT_THRESHOLD_DP = 12
    const val DEFAULT_T9_THRESHOLD_DP = 20

    const val MIN_THRESHOLD_DP = 6
    const val MAX_THRESHOLD_DP = 40
    const val MIN_T9_THRESHOLD_DP = 10
    /** v2.0.0 起九宫格上限放宽到 200dp（九宫格单键高得多，用户可能想设更长距离） */
    const val MAX_T9_THRESHOLD_DP = 200

    /** 自定义插入文本长度上限（码点） */
    const val MAX_INSERT_CODEPOINTS = 1000

    /** 点击容差（dp）：位移小于它仍视为点击，手势交还宿主 */
    const val SLOP_DP = 8

    /** 纵向触发距离的物理上限（键盘高度占比），否则手指滑出键盘也到不了 */
    const val MAX_THRESHOLD_RATIO_VERTICAL = 0.6f

    /** 横向触发距离的物理上限（键盘宽度占比） */
    const val MAX_THRESHOLD_RATIO_HORIZONTAL = 0.4f

    /** 方向判定：横向位移需超过纵向的 1.25 倍才判左右，否则按纵向处理（抑制下滑时的横向抖动） */
    const val HORIZONTAL_DOMINANCE = 1.25f

    /** 提示气泡延迟消失（ms） */
    const val HINT_HIDE_DELAY_MS = 400L

    /** 触发时取消失键触摸的分发延迟（ms） */
    const val HINT_HIDE_AFTER_UP_MS = 120L
}