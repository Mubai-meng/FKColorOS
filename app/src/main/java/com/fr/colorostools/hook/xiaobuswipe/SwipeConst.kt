package com.fr.colorostools.hook.xiaobuswipe

/**
 * 小布输入法「按键下滑快捷键」的共享常量：动作表、键位表、偏好键名、默认绑定。
 *
 * 语义移植自参考模块 `app.xiaobuswipe`（`Config` / `ActionRegistry` / `KeyResolver`），
 * 但**不照搬它的位标志实现**：那边 `FLAG_*` 位与动作 id 混在同一组数字里（反编译常量内联后
 * 全部糊成一片），这里改成「动作 id 独立编号 + 分类函数显式判断」，行为等价、可读性正常。
 *
 * 配置通道与参考模块最大的差别：那边为了跨进程同步自建了 `BroadcastReceiver` +
 * 目标进程内 prefs 缓存（`xiaobu_swipe_module_cache_v1`）；本模块走 libxposed remote prefs
 * （App 写、hook 读同一份内存镜像），**不需要广播与缓存**，每次按键读一次内存值即可。
 */
internal object SwipeConst {

    /** 日志 tag（与模块其余 hook 区分，便于筛日志） */
    const val TAG = "XiaoBuSwipe"

    /** 宿主输入法包名 */
    const val TARGET = "com.oplus.keyboard"

    /** 动作 id：未绑定 */
    const val A_NONE = 0

    // ---------------------------------------------------------------- 动作 id
    //
    // 编号与参考模块一致（便于对照它的逆向产物），语义一一对应。

    const val A_SELECT_ALL = 1
    const val A_CUT = 2
    const val A_COPY = 3
    const val A_PASTE = 4
    const val A_DISABLE = 5          // 禁用该键下滑
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
    const val A_INSERT_TEXT = 20      // 输入指定内容（每键一个文本）
    const val A_NEXT_IME = 21
    const val A_PREV_IME = 22
    const val A_IME_PICKER = 23
    const val A_PASTE_AND_SEND = 24
    const val A_SEND_EXISTING = 25

    /**
     * 动作名（设置页下拉与提示文案共用）。
     *
     * 顺序 = 设置页下拉顺序：常用编辑动作在前，导航类其次，面板/输入法类在后。
     */
    val ACTION_IDS = intArrayOf(
        A_NONE,
        A_SELECT_ALL, A_CUT, A_COPY, A_PASTE,
        A_COPY_ALL, A_CUT_ALL, A_UNDO, A_REDO,
        A_PARAGRAPH_START, A_PARAGRAPH_END,
        A_SELECT_TO_PARAGRAPH_START, A_SELECT_TO_PARAGRAPH_END,
        A_DOCUMENT_START, A_DOCUMENT_END,
        A_SELECT_TO_DOCUMENT_START, A_SELECT_TO_DOCUMENT_END,
        A_OPEN_CLIPBOARD, A_OPEN_QUICK_PHRASE,
        A_INSERT_TEXT,
        A_NEXT_IME, A_PREV_IME, A_IME_PICKER,
        A_PASTE_AND_SEND, A_SEND_EXISTING,
        A_DISABLE,
    )

    /** 动作中文名（设置页下拉显示项） */
    val ACTION_NAMES = arrayOf(
        "未绑定",
        "全选", "剪切", "复制", "粘贴",
        "复制全部", "剪切全部", "撤销", "重做",
        "段首", "段尾",
        "选至段首", "选至段尾",
        "文首", "文尾",
        "选至文首", "选至文尾",
        "剪贴板", "快捷短语",
        "输入指定内容",
        "下一个输入法", "上一个输入法", "选择输入法",
        "粘贴并发送", "发送已有内容",
        "禁用下滑",
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
        A_NEXT_IME to "下个输入",
        A_PREV_IME to "上个输入",
        A_IME_PICKER to "选输入法",
        A_PASTE_AND_SEND to "粘贴发",
        A_SEND_EXISTING to "发送",
        A_DISABLE to "禁用",
    )

    // ---------------------------------------------------------------- 键位

    /** 26 键字母（下拉绑定的键序） */
    val QWERTY_KEYS: List<Char> = ('a'..'z').toList()

    /** 九宫格数字键 2..9 */
    val T9_KEYS: List<Int> = (2..9).toList()

    /**
     * 默认绑定（与参考模块一致）：`z` 全选、`x` 剪切、`c` 复制、`v` 粘贴。
     *
     * 这四个是桌面键盘习惯（Ctrl+Z/X/C/V），用户上手即可用，其余键默认未绑定。
     */
    val DEFAULT_QWERTY: Map<Char, Int> = mapOf(
        'z' to A_SELECT_ALL,
        'x' to A_CUT,
        'c' to A_COPY,
        'v' to A_PASTE,
    )

    // ---------------------------------------------------------------- 偏好键
    //
    // 全部走 SettingsPrefs（remote prefs）；前缀 `xiaobu_swipe_` 与其它功能互不干扰。

    const val KEY_ENABLED = "xiaobu_swipe_enabled"
    const val KEY_THRESHOLD_DP = "xiaobu_swipe_threshold_dp"
    const val KEY_T9_THRESHOLD_DP = "xiaobu_swipe_t9_threshold_dp"
    const val KEY_VIBRATION = "xiaobu_swipe_vibration"
    const val KEY_SHOW_LABELS = "xiaobu_swipe_show_labels"
    const val KEY_SHOW_HINT = "xiaobu_swipe_show_hint"

    /** 单键动作：`xiaobu_swipe_action_q_<c>` / `xiaobu_swipe_action_t9_<n>`（存动作 id 字符串） */
    fun actionKeyQwerty(c: Char): String = "xiaobu_swipe_action_q_$c"
    fun actionKeyT9(n: Int): String = "xiaobu_swipe_action_t9_$n"

    /** 单键自定义文本（动作 = 输入指定内容时使用） */
    fun textKeyQwerty(c: Char): String = "xiaobu_swipe_text_q_$c"
    fun textKeyT9(n: Int): String = "xiaobu_swipe_text_t9_$n"

    /**
     * 单键**标签覆盖**（键面副标题显示什么）。
     *
     * 三状态语义（对齐参考模块 `Config.qwertyLabels` / `LABEL_HIDDEN`）：
     * - 未设置（prefs 无该键 / 空串）⇒ **自动**：显示动作短名，无动作时显示 `—`
     * - [LABEL_HIDDEN] ⇒ **隐藏**该键的标签（动作仍然照常可下滑触发）
     * - 其它非空串 ⇒ **自定义文字**（最多 4 个字符）
     *
     * ⚠️ 标签只影响**绘制**，不影响手势命中与动作执行 —— 隐藏标签不等于禁用下滑，
     * 后者是动作值 [A_DISABLE]，两者是彼此独立的两件事。
     */
    fun labelKeyQwerty(c: Char): String = "xiaobu_swipe_label_q_$c"
    fun labelKeyT9(n: Int): String = "xiaobu_swipe_label_t9_$n"

    /** 标签隐藏哨兵值（与参考模块字面量一致，便于对照逆向产物） */
    const val LABEL_HIDDEN = "__HIDDEN__"

    /** 自定义标签最大字符数（码点；参考模块同样是 4） */
    const val MAX_LABEL_CODEPOINTS = 4

    /** 触发距离默认值（dp）：26 键 12dp、九宫格 20dp（九宫格键更高更密，需更长距离防误触） */
    const val DEFAULT_THRESHOLD_DP = 12
    const val DEFAULT_T9_THRESHOLD_DP = 20

    const val MIN_THRESHOLD_DP = 6
    const val MAX_THRESHOLD_DP = 40
    const val MIN_T9_THRESHOLD_DP = 10
    const val MAX_T9_THRESHOLD_DP = 48

    /** 自定义插入文本长度上限（码点） */
    const val MAX_INSERT_CODEPOINTS = 1000

    /** 提示气泡延迟消失（ms） */
    const val HINT_HIDE_DELAY_MS = 400L

    /** 触发时取消失键触摸的分发延迟（ms） */
    const val HINT_HIDE_AFTER_UP_MS = 120L
}