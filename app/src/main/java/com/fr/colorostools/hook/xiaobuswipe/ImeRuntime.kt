package com.fr.colorostools.hook.xiaobuswipe

import android.view.inputmethod.EditorInfo
import android.inputmethodservice.InputMethodService
import com.fr.colorostools.hook.HookEnv
import com.fr.colorostools.utils.LogUtils
import java.lang.ref.WeakReference

/**
 * 输入法服务运行时状态：当前 IME 实例、当前输入框是否密码、当前选区、主题深浅。
 *
 * 这些状态在手势识别与动作执行里到处要用（密码框禁手势、选区决定段落/文档动作起点、
 * 深浅色决定按键标签颜色），统一放这里单点维护。
 *
 * 移植来源：参考模块 `MainHook` 的 `imeRef` / `currentSelectionStart` / `currentSelectionEnd` /
 * `currentEditorPassword` / `liveNightMode` 五个字段及其 `capture*` 系列方法。
 */
internal object ImeRuntime {

    private const val TAG = SwipeConst.TAG

    /** 当前 IME 实例（弱引用：输入法可能被系统重建，不能强持有） */
    @Volatile
    private var imeRef: WeakReference<InputMethodService>? = null

    /** 当前输入框是否密码类型（密码框一律不响应下滑） */
    @Volatile
    var editorPassword: Boolean = false
        private set

    /**
     * 当前选区（来自 onUpdateSelection 或动作执行后自维护）。
     *
     * -1 = 未知。已知时可直接用 `getTextBeforeCursor` 取段落上下文（比 surroundText 兼容性更好）。
     */
    @Volatile
    var selectionStart: Int = -1
        private set

    @Volatile
    var selectionEnd: Int = -1
        private set

    /** 当前深浅：16=日间 / 32=夜间 / 0=未知（UiMode 的 night 位） */
    @Volatile
    var nightMode: Int = 0
        private set

    // ---------------------------------------------------------------- 采集

    /** 捕获 IME 实例（InputMethodService#onCreate / onStartInput 时调用） */
    fun captureIme(ime: InputMethodService) {
        val current = imeRef?.get()
        if (current !== ime) {
            // 换了 IME 实例 ⇒ 键盘视图与提示气泡全部失效，交回给 [SwipeGesture] 清理
            SwipeGesture.onImeChanged()
        }
        imeRef = WeakReference(ime)
        refreshNightMode(ime.resources.configuration.uiMode)
    }

    /** 捕获输入框类型：密码框不下发手势 */
    fun captureStartInput(info: EditorInfo?) {
        selectionStart = -1
        selectionEnd = -1
        editorPassword = info != null && isPassword(info.inputType)
    }

    /** 跟踪选区（onUpdateSelection 回调） */
    fun captureSelection(start: Int, end: Int) {
        if (start < 0 || end < 0) return
        selectionStart = start
        selectionEnd = end
    }

    /** 动作执行改过选区后同步，避免下一次段落动作基于旧值 */
    fun updateSelection(start: Int, end: Int) {
        selectionStart = start
        selectionEnd = end
    }

    /** 刷新深浅模式（Application / IME 的 onConfigurationChanged 时调用） */
    fun refreshNightMode(uiMode: Int) {
        val nightBits = uiMode and 0x30
        if (nightBits == 0x10 || nightBits == 0x20) nightMode = nightBits
    }

    /** 当前 IME 实例（可能为 null：尚未捕获到） */
    fun ime(): InputMethodService? = imeRef?.get()

    /**
     * 从任意 View 反查 IME 实例。
     *
     * 兜底意义：`onCreate` 早于宿主初始化键盘视图，某些机型上弱引用可能已被回收；
     * 从 View 的 Context 链往上找 ContextWrapper 是参考模块的做法，实测同样可靠。
     */
    fun findImeFrom(view: android.view.View?): InputMethodService? {
        var ctx: android.content.Context? = runCatching { view?.context }.getOrNull()
        var depth = 0
        while (ctx != null && depth < 12) {
            if (ctx is InputMethodService) return ctx
            val base = runCatching { (ctx as? android.content.ContextWrapper)?.baseContext }.getOrNull()
            if (base == null || base === ctx) return null
            ctx = base
            depth++
        }
        return null
    }

    /** 取当前 IME，必要时从 [view] 反查并回填引用 */
    fun requireIme(view: android.view.View?): InputMethodService? {
        ime()?.let { return it }
        val found = findImeFrom(view) ?: return null
        imeRef = WeakReference(found)
        return found
    }

    /**
     * 判断 inputType 是否密码类。
     *
     * 与参考模块 `isPassword` 逐位等价，但用常量名而非魔术数字：
     * - 文本类（`TYPE_CLASS_TEXT`）的 `TYPE_TEXT_VARIATION_PASSWORD` / `VISIBLE_PASSWORD` / `WEB_PASSWORD`
     * - 数字类（`TYPE_CLASS_NUMBER`）的 `TYPE_NUMBER_VARIATION_PASSWORD`
     */
    fun isPassword(inputType: Int): Boolean {
        val cls = inputType and 0xF
        val variation = inputType and 0xFF0
        return when (cls) {
            0x1 -> variation == 0x80 || variation == 0x90 || variation == 0xE0
            0x2 -> variation == 0x10
            else -> false
        }
    }

    /** 日志辅助：只在首次进入某状态时打一行，避免高频刷日志 */
    fun logStateOnce(key: String, message: () -> String) {
        if (logged.add(key)) LogUtils.i(TAG, "state", message())
    }

    private val logged = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 清空一次性日志标记（配置切换时调用，便于复测） */
    fun resetLogFlags() {
        logged.clear()
    }

    /** 重置（进程内重装钩子时） */
    fun reset() {
        imeRef = null
        editorPassword = false
        selectionStart = -1
        selectionEnd = -1
        nightMode = 0
        resetLogFlags()
        LogUtils.i(HookEnv.currentPkg ?: "?", "ImeRuntime", "reset")
    }
}