package com.fr.colorostools.hook.xiaobuswipe

import android.view.View
import com.fr.colorostools.ref.Reflect
import java.lang.reflect.Field

/**
 * 键盘按键几何与键位解析。
 *
 * ## 宿主数据结构（1.8.35 / 1.9.18 实证，两版结构一致）
 *
 * 键盘视图（`com.oplus.keyboard.input.view.body.r`，继承 `View`）对外暴露两个取键盘模型的方法：
 * - `getSoftKeyboard()` → `com.oplus.keyboard.base.entity.f`（当前键盘模型，T9/26 键共用）
 * - `getNormalSoftKeyboard()` → 同上（兜底）
 *
 * 模型 `f` 里字段 `l` 是 `List<com.oplus.keyboard.base.entity.e>`，每个 `e` 是一个按键定义，
 * 其中与几何/键位相关的字段（源码名 → 混淆名）：
 *
 * | 源码名 | 混淆名 | 含义 |
 * |---|---|---|
 * | `a` | `f13725a` | KeyCode（7..16=数字 0..9，29..54=字母 A..Z，其余见特殊键表） |
 * | `b` | `f13726b` | 左边界 x |
 * | `c` | `f13727c` | 右边界 x |
 * | `d` | `f13728d` | 上边界 y |
 * | `e` | `f13729e` | 下边界 y |
 * | `s` | `s` | 键面文字（数字/字母/符号） |
 *
 * `KeyCode` 枚举实测：`K0(7)` … `K9(16)`、`A(29)` … `Z(54)` —— 恰好 `keyCode - 41` 得数字字符、
 * `keyCode + 68` 得大写字母（29→'A'、54→'Z'）。
 *
 * ## 键身份（v2.0.0 起）
 * 键身份是三选一的字符串，且**与方向无关**（方向由手势层决定）：
 * - 26 键字母：`"a"`..`"z"`（小写单字符）
 * - 九宫格数字：`"2"`..`"9"`（单字符），`t9 = true`
 * - 特殊键：[SwipeConst.SPECIAL_KEYS] 里的 id（`"del"` / `"enter"` / …），长度 > 1
 *
 * ## 为什么反射而不是直接引用
 * 宿主的 `f` / `e` 是混淆内部类，模块编译期无该类；且混淆名随版本变化，故：
 * - **字段名按源码真实名读**（`Reflect.getField` 从 `getDeclaredFields()` 拿到的就是真实名
 *   `l` / `a` / `b` / `c` / `d` / `e` / `s`，jadx 里的 `f13725a` 是它的重命名展示）；
 * - 取不到就整体降级（返回 null / 空），绝不抛给宿主。
 */
internal object KeyGeometry {

    /** 键盘模型里的按键列表字段（源码名 `l`） */
    private const val FIELD_KEYS = "l"

    /** 按键定义字段（源码名） */
    private const val F_KEY_CODE = "a"
    private const val F_LEFT = "b"
    private const val F_RIGHT = "c"
    private const val F_TOP = "d"
    private const val F_BOTTOM = "e"
    private const val F_TEXT = "s"

    /** KeyCode：数字 0 的枚举值（K0=7）—— `keyCode - 7` 得数字字符 */
    private const val KEYCODE_DIGIT_BASE = 7

    /** KeyCode：字母 A 的枚举值（A=29）—— `keyCode + 68` 得字符 'A' */
    private const val KEYCODE_LETTER_BASE = 29
    private const val KEYCODE_LETTER_LAST = 54

    /**
     * 单个按键在视图坐标系里的矩形 + 识别出的键。
     *
     * [key] 是键身份（字母/数字/特殊键 id），[t9] 表示是否九宫格数字键，
     * [display] 是给提示气泡看的显示名（字母大写、特殊键中文名）。
     */
    class Hit(
        val key: String,
        val t9: Boolean,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        val centerX: Float get() = (left + right) * 0.5f
        val width: Int get() = right - left

        /** 是否特殊键（id 长度 > 1 且命中特殊键表） */
        val special: Boolean get() = SwipeConst.isSpecial(key)

        /** 提示气泡用的显示名 */
        val display: String
            get() = when {
                special -> SwipeConst.specialLabel(key)
                t9 -> "九宫格 $key"
                else -> key.uppercase()
            }
    }

    /**
     * 命中测试：给定视图内坐标，返回落在哪个已识别的键上。
     *
     * [t9Mode] 决定数字键是否按九宫格键位解读（26 键布局里的数字行不参与绑定）。
     */
    fun hitAt(view: Any?, x: Float, y: Float, t9Mode: Boolean): Hit? {
        val keys = softKeys(view) ?: return null
        val px = Math.round(x)
        val py = Math.round(y)
        for (k in keys) {
            if (k == null) continue
            val left = intField(k, F_LEFT, Int.MIN_VALUE)
            val right = intField(k, F_RIGHT, Int.MIN_VALUE)
            val top = intField(k, F_TOP, Int.MIN_VALUE)
            val bottom = intField(k, F_BOTTOM, Int.MIN_VALUE)
            if (right <= left || bottom <= top) continue
            if (px < left || px > right || py < top || py > bottom) continue
            val info = keyOf(k, t9Mode) ?: continue
            return Hit(info.key, info.t9, left, top, right, bottom)
        }
        return null
    }

    /**
     * 收集全部可用的键（绘制按键标签 / 查找 Shift 键矩形用）。
     *
     * 返回 `键身份 → 几何`。键身份**不含方向**：26 键是字母、九宫格是数字、特殊键是 id。
     * 注意特殊键可能出现于两种键盘，但绝不会同一个身份在同一键盘里重复（除逗号/句号在
     * 某些布局里同时存在于主键区与符号区 —— 此时后出现的覆盖前者，标签只会画一个，可接受）。
     */
    fun collect(view: Any?, t9Mode: Boolean): Map<String, Hit> {
        val keys = softKeys(view) ?: return emptyMap()
        val out = LinkedHashMap<String, Hit>(keys.size)
        for (k in keys) {
            if (k == null) continue
            val left = intField(k, F_LEFT, Int.MIN_VALUE)
            val right = intField(k, F_RIGHT, Int.MIN_VALUE)
            val top = intField(k, F_TOP, Int.MIN_VALUE)
            val bottom = intField(k, F_BOTTOM, Int.MIN_VALUE)
            if (right <= left || bottom <= top) continue
            val info = keyOf(k, t9Mode) ?: continue
            out[info.key] = Hit(info.key, info.t9, left, top, right, bottom)
        }
        return out
    }

    /** 按键总数（判定键盘几何是否变化，用于缓存失效） */
    fun softKeyCount(view: Any?): Int {
        val list = softKeyList(view) ?: return -1
        return list.size
    }

    /** 按键列表长度（标签缓存键用） */
    fun keyCount(view: Any?) = softKeyCount(view)

    /**
     * 当前键盘是否为九宫格模式。
     *
     * 依据：模型 `f` 的字段 `a` 是 `KeyboardType` 枚举，`T9_PINYIN` / `T9_NUMBER`
     * 的序列化名以 `t9` 开头（实测 `T9_PINYIN("t9")`）。取不到时退回视图类名判断
     * （`T9View` / `T9SeparateView`）。
     */
    fun isT9(view: Any?): Boolean {
        val model = keyboardModel(view) ?: return isT9ByClassName(view)
        val type = runCatching { Reflect.getField(model, "a") }.getOrNull()
        val name = type?.toString() ?: return isT9ByClassName(view)
        return name.startsWith("T9", ignoreCase = true)
    }

    private fun isT9ByClassName(view: Any?): Boolean {
        val name = runCatching { (view as? View)?.javaClass?.name }.getOrNull() ?: return false
        return name.contains("T9", ignoreCase = true) || name.contains("nine", ignoreCase = true)
    }

    /** 手写键盘（不参与滑动手势） */
    fun isHandwriting(view: Any?): Boolean {
        var v = view as? View
        var depth = 0
        while (v != null && depth < 16) {
            val name = v.javaClass.name.lowercase()
            if (name.contains("handwrite") || name.contains("hand_write")) return true
            v = runCatching { v.parent as? View }.getOrNull()
            depth++
        }
        return false
    }

    // ---------------------------------------------------------------- 内部

    /** 取当前键盘模型（`getSoftKeyboard` 优先，`getNormalSoftKeyboard` 兜底） */
    fun keyboardModel(view: Any?): Any? {
        if (view == null) return null
        // ⚠️ 这两个方法声明在键盘视图类自身（public final），但为了兼容继承层次变化用 callDeep
        Reflect.callDeep(view, "getSoftKeyboard")?.let { return it }
        return Reflect.callDeep(view, "getNormalSoftKeyboard")
    }

    /** 取按键列表（模型字段 `l`）；模型本身没有该字段时返回 null */
    private fun softKeyList(view: Any?): List<*>? {
        val model = keyboardModel(view) ?: return null
        return runCatching { Reflect.getField(model, FIELD_KEYS) as? List<*> }.getOrNull()
    }

    private fun softKeys(view: Any?): List<*>? = softKeyList(view)

    /** 单个按键的键身份解析：优先 KeyCode（含特殊键），其次键面文字 */
    private fun keyOf(keyObj: Any, t9Mode: Boolean): KeyInfo? {
        val code = intField(keyObj, F_KEY_CODE, Int.MIN_VALUE)
        fromKeyCode(code, t9Mode)?.let { return it }
        return fromText(Reflect.getField(keyObj, F_TEXT), t9Mode)
    }

    /** 键身份三元组：id + 是否九宫格数字 */
    private class KeyInfo(val key: String, val t9: Boolean)

    /**
     * KeyCode → 键身份。
     *
     * **特殊键优先判定**：宿主给功能键的 value 既有正数（DEL=67、ENTER=66、SPACE=62、
     * CLEAR=28、SYMBOLS1=303）也有负数（SHIFT=-1、SYMBOL=-2、NUMBER=-3、COMMA=-4、
     * PERIOD=-5、LANG=-6、QUESTION=-11、EXCLAMATION=-12）。这些值不与数字/字母区间重叠，
     * 但先判特殊键更稳妥（万一某版本把 67 挪进别的语义）。
     */
    private fun fromKeyCode(code: Int, t9Mode: Boolean): KeyInfo? {
        specialKeyId(code)?.let { return KeyInfo(it, false) }
        if (code in KEYCODE_DIGIT_BASE..(KEYCODE_DIGIT_BASE + 9)) {
            val ch = (code - KEYCODE_DIGIT_BASE + '0'.code).toChar()
            // 26 键布局的数字行不绑定（只有九宫格才把数字当键位）
            if (!t9Mode || ch < '2') return null
            return KeyInfo(ch.toString(), true)
        }
        if (code in KEYCODE_LETTER_BASE..KEYCODE_LETTER_LAST) {
            return KeyInfo((code - KEYCODE_LETTER_BASE + 'a'.code).toChar().toString(), false)
        }
        return null
    }

    /**
     * 宿主 `KeyCode` 枚举 value → 稳定 keyId。
     *
     * 正数取自 1.9.18.17-mkt 的 `KeyCode` 常量，负数为其内置功能键编码。
     * 这份映射是**唯一**的特殊键身份来源，新增特殊键只改这里 + [SwipeConst.SPECIAL_KEYS]。
     */
    private fun specialKeyId(keyCode: Int): String? = when (keyCode) {
        67 -> SwipeConst.K_DEL            // KeyCode.DEL
        66 -> SwipeConst.K_ENTER          // KeyCode.ENTER
        62 -> SwipeConst.K_SPACE          // KeyCode.SPACE
        28 -> SwipeConst.K_CLEAR          // KeyCode.CLEAR（重输）
        303 -> SwipeConst.K_SYMBOLS1      // KeyCode.SYMBOLS1（@# 面板）
        -1 -> SwipeConst.K_SHIFT          // KeyCode.SHIFT
        -2 -> SwipeConst.K_SYMBOL         // KeyCode.SYMBOL（符号）
        -3 -> SwipeConst.K_NUMBER         // KeyCode.NUMBER（123）
        -4 -> SwipeConst.K_COMMA          // KeyCode.COMMA
        -5 -> SwipeConst.K_PERIOD         // KeyCode.PERIOD
        -6 -> SwipeConst.K_LANG           // KeyCode.LANG（拼/英）
        -11 -> SwipeConst.K_QUESTION      // KeyCode.QUESTION
        -12 -> SwipeConst.K_EXCLAMATION   // KeyCode.EXCLAMATION
        else -> null
    }

    /** 键面文字兜底：单字符 `a`..`z` 或九宫格 `2`..`9` */
    private fun fromText(raw: Any?, t9Mode: Boolean): KeyInfo? {
        if (raw == null) return null
        val text = raw.toString().trim().lowercase()
        if (text.length != 1) return null
        val ch = text[0]
        return when {
            ch in 'a'..'z' -> KeyInfo(ch.toString(), false)
            // ⚠️ 26 键的数字行不参与绑定：这里也必须判 t9Mode，否则 26 键顶行数字会被
            // 当成九宫格键命中，导致「在 26 键上把 3 绑成某个动作」这种越界配置生效。
            ch in '2'..'9' && t9Mode -> KeyInfo(ch.toString(), true)
            else -> null
        }
    }

    private fun intField(obj: Any, name: String, fallback: Int): Int {
        val v = Reflect.getField(obj, name)
        return if (v is Number) v.toInt() else fallback
    }

    /** 清空反射缓存（配置切换 / 宿主重建时无需手动调用，保留接口便于排障） */
    fun invalidate() {
        // Reflect 内部自带缓存，此处仅作语义占位：键盘几何随宿主自行刷新，缓存键是 Class+字段名
        runCatching { Field::class.java }
    }
}