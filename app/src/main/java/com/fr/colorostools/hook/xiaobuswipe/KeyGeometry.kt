package com.fr.colorostools.hook.xiaobuswipe

import android.view.View
import com.fr.colorostools.ref.Reflect
import java.lang.reflect.Field

/**
 * 键盘按键几何与键位解析。
 *
 * ## 宿主数据结构（1.8.35.17 实证）
 *
 * 键盘视图（`com.oplus.keyboard.input.view.body.r`，继承 `View`）对外暴露两个取键盘模型的方法：
 * - `getSoftKeyboard()` → `com.oplus.keyboard.base.entity.f`（当前键盘模型，T9/26 键共用）
 * - `getNormalSoftKeyboard()` → 同上（兜底）
 *
 * 模型 `f` 里 `f13749l`（源码字段名 `l`）是 `List<com.oplus.keyboard.base.entity.e>`，每个 `e` 是一个
 * 按键定义，其中与几何/键位相关的字段（源码名 → 混淆名）：
 *
 * | 源码名 | 混淆名 | 含义 |
 * |---|---|---|
 * | `a` | `f13725a` | KeyCode（7..16=数字 0..9，29..54=字母 A..Z） |
 * | `b` | `f13726b` | 左边界 x |
 * | `c` | `f13727c` | 右边界 x |
 * | `d` | `f13728d` | 上边界 y |
 * | `e` | `f13729e` | 下边界 y |
 * | `s` | `s` | 键面文字（数字/字母/符号） |
 *
 * `KeyCode` 枚举实测：`K0(7)` … `K9(16)`、`A(29)` … `Z(54)` —— 恰好 `keyCode - 41` 得数字字符、
 * `keyCode + 68` 得大写字母（29→'A'、54→'Z'），这也是参考模块的换算方式，本机反编译核对一致。
 *
 * ## 为什么反射而不是直接引用
 * 宿主的 `f` / `e` 是混淆内部类，模块编译期无该类；且混淆名随版本变化，故：
 * - **字段名按源码真实名读**（`Reflect.findField` 从 `getDeclaredFields()` 拿到的就是真实名
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

    /** KeyCode：数字 0 的枚举值（K0=7）—— `keyCode - 41` 得字符 '0' */
    private const val KEYCODE_DIGIT_BASE = 7

    /** KeyCode：字母 A 的枚举值（A=29）—— `keyCode + 68` 得字符 'A' */
    private const val KEYCODE_LETTER_BASE = 29
    private const val KEYCODE_LETTER_LAST = 54

    /** 单个按键在视图坐标系里的矩形 + 识别出的键 */
    class Hit(val key: String, val t9: Boolean, val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val centerX: Float get() = (left + right) * 0.5f
        val width: Int get() = right - left
    }

    /**
     * 命中测试：给定视图内坐标，返回落在哪个已绑定的键上。
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
            val key = keyOf(k, t9Mode) ?: continue
            return Hit(key.first, key.second, left, top, right, bottom)
        }
        return null
    }

    /**
     * 收集全部可用的键（绘制按键标签用）：只保留几何有效且能解析出键名的。
     *
     * 返回 `键标识 → 几何`，键标识对 26 键是小写字母、九宫格是数字（与配置键一致）。
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
            val key = keyOf(k, t9Mode) ?: continue
            out[key.first] = Hit(key.first, key.second, left, top, right, bottom)
        }
        return out
    }

    /** 按键总数（判定键盘几何是否变化，用于缓存失效） */
    fun softKeyCount(view: Any?): Int {
        val list = softKeyList(view) ?: return -1
        return list.size
    }

    /**
     * 当前键盘是否为九宫格模式。
     *
     * 依据：模型 `f` 的 `f13738a`（源码名 `a`）是 `KeyboardType` 枚举，`T9_PINYIN` / `T9_NUMBER`
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

    /** 手写键盘（不参与下滑手势） */
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

    /** 单个按键的键名解析：优先 KeyCode，其次键面文字 */
    private fun keyOf(keyObj: Any, t9Mode: Boolean): Pair<String, Boolean>? {
        val code = intField(keyObj, F_KEY_CODE, Int.MIN_VALUE)
        fromKeyCode(code, t9Mode)?.let { return it }
        return fromText(Reflect.getField(keyObj, F_TEXT))
    }

    private fun fromKeyCode(code: Int, t9Mode: Boolean): Pair<String, Boolean>? {
        if (code in KEYCODE_DIGIT_BASE..16) {
            val ch = (code + 41).toChar()
            // 26 键布局的数字行不绑定（只有九宫格才把数字当键位）
            if (!t9Mode || ch < '2') return null
            return ch.toString() to true
        }
        if (code in KEYCODE_LETTER_BASE..KEYCODE_LETTER_LAST) {
            return (code + 68).toChar().lowercaseChar().toString() to false
        }
        return null
    }

    private fun fromText(raw: Any?): Pair<String, Boolean>? {
        if (raw == null) return null
        val text = raw.toString().trim().lowercase()
        if (text.length != 1) return null
        val ch = text[0]
        return when {
            ch in 'a'..'z' -> ch.toString() to false
            ch in '2'..'9' -> ch.toString() to true
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