package com.fr.colorostools.hook.xiaobuswipe

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.view.inputmethod.InputMethodManager
import android.inputmethodservice.InputMethodService
import android.view.inputmethod.SurroundingText
import com.fr.colorostools.ref.Reflect
import com.fr.colorostools.utils.LogUtils

/**
 * 编辑器动作执行器：把「动作 id」落到 `InputConnection` / `InputMethodService` 上。
 *
 * 移植来源：参考模块 `MainHook#performAction` 及其下游 `performMenuAction` / `performPaste` /
 * `performDocumentAction` / `performParagraphAction` / `performCompoundAction` /
 * `pasteMediaEntry` / `sendViaEditorAction` 等约 700 行实现。
 *
 * ## 分层策略（每一步独立降级，任一环失败都不抛给宿主）
 *
 * | 层级 | 手段 | 说明 |
 * |---|---|---|
 * | 1 | `InputConnection#performContextMenuAction` | 宿主支持时最干净（编辑器自己处理全选/复制/粘贴） |
 * | 2 | `InputMethodService#performContextMenuAction` | 宿主 IME 自实现的菜单动作（实测存在） |
 * | 3 | 直接操作选区 / 剪贴板 | 上面的都拒绝时自行实现（如全选 = setSelection(0,len)） |
 *
 * ## 密码框
 * 密码框只放行不需要读取/修改文本的动作（列表见 [SwipeGesture]）。本类入口也会复查一次，
 * 因为 `performAction` 可能在异步（`view.post`）里执行，期间输入框可能已切换。
 */
internal object EditorActions {

    private const val TAG = SwipeConst.TAG

    /** 段落/文档动作读取上下文的最大字符数（与参考模块一致） */
    private const val CONTEXT_CHARS = 65536

    /**
     * ShiftState 状态机类（1.9.18 / 1.8.35 两版同名）。
     *
     * 静态字段：`R` = 引擎侧状态、`T` = 键面显示态；值语义 `0=LOWER_CASE / 1=UPPER_CASE /
     * 2=UPPER_LOCK_CASE`。名字必须是 **dex 真实名**（jadx 里可能显示成 `y` 之外的别名）。
     */
    private const val CLS_SHIFT_STATE = "com.oplus.keyboard.input.manager.y"

    /** ShiftState 写入口：`q(int)` 写显示态、`r(int)` 写引擎态并通知 Kernel */
    private const val CLS_SHIFT_MANAGER = "com.oplus.keyboard.input.manager.r"

    /** 剪贴板面板：延迟打开（原生面板经由事件总线 post，需等键盘布局落定） */
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 执行动作。
     *
     * @param view 键盘视图（取 Context / 反查 IME / 定位原生面板容器 / 合成 Shift 点击）
     * @param key  触发的键身份（26 键小写字母 / 九宫格数字 / 特殊键 id），用于「输入指定内容」取文本
     * @param t9   该键是否九宫格数字键
     * @param action 动作 id
     * @param dir  触发的方向（[SwipeConst.DIR_DOWN] 等），「输入指定内容」按方向取文本
     * @return true = 已执行；false = 拒绝或失败
     */
    fun perform(
        view: android.view.View?,
        key: String,
        t9: Boolean,
        action: Int,
        dir: Int,
        config: SwipeConfig,
    ): Boolean {
        if (action == SwipeConst.A_NONE || action == SwipeConst.A_DISABLE) return false
        val ime = ImeRuntime.requireIme(view) ?: run {
            LogUtils.w(TAG, "perform", "无 IME 实例，动作 ${config.actionName(action)} 放弃")
            return false
        }

        return runCatching {
            // 输入法切换类动作与编辑器状态无关，先处理（密码框也放行）
            if (action == SwipeConst.A_NEXT_IME || action == SwipeConst.A_PREV_IME ||
                action == SwipeConst.A_IME_PICKER
            ) {
                return performImeAction(ime, action)
            }

            // 大小写切换：也不读写编辑器文本，密码框放行；且必须排在密码检查之前
            // （上游同样是这个顺序 —— 密码框里切大小写是正常需求）
            if (action == SwipeConst.A_TOGGLE_CAPS) {
                val ok = toggleCapsLock(view, ime)
                LogUtils.i(TAG, "perform", "大小写切换=$ok（键 $key ${SwipeConst.DIR_NAMES[dir]}）")
                return ok
            }

            val info = runCatching { ime.currentInputEditorInfo }.getOrNull()
            if (info != null && ImeRuntime.isPassword(info.inputType)) {
                // 密码框：仅「自己输入文本」与「打开剪贴板面板」可用（不读取、不改写既有文本）
                if (action != SwipeConst.A_INSERT_TEXT && action != SwipeConst.A_OPEN_CLIPBOARD) {
                    LogUtils.i(TAG, "perform", "密码框，动作 ${config.actionName(action)} 跳过")
                    return false
                }
            }

            // 原生面板类：走宿主自己的剪贴板 / 快捷短语入口
            if (action == SwipeConst.A_OPEN_CLIPBOARD || action == SwipeConst.A_OPEN_QUICK_PHRASE) {
                val phrase = action == SwipeConst.A_OPEN_QUICK_PHRASE
                val ok = NativePanel.open(view, phrase)
                if (!ok) LogUtils.w(TAG, "perform", "原生面板打开失败（phrase=$phrase）")
                return ok
            }

            val ic = runCatching { ime.currentInputConnection }.getOrNull() ?: run {
                LogUtils.w(TAG, "perform", "无 InputConnection，动作 ${config.actionName(action)} 放弃")
                return false
            }

            val ok = when {
                action == SwipeConst.A_INSERT_TEXT -> insertText(ic, config, key, dir)
                action == SwipeConst.A_PASTE -> paste(ime, ic)
                action == SwipeConst.A_PASTE_AND_SEND -> pasteAndSend(ime, ic)
                action == SwipeConst.A_SEND_EXISTING -> sendViaEditorAction(ime, ic)
                action == SwipeConst.A_COPY_ALL -> compound(ime, view, ic, copy = true)
                action == SwipeConst.A_CUT_ALL -> compound(ime, view, ic, copy = false)
                action in SwipeConst.A_PARAGRAPH_START..SwipeConst.A_SELECT_TO_PARAGRAPH_END ->
                    paragraph(ic, action)
                action in SwipeConst.A_DOCUMENT_START..SwipeConst.A_SELECT_TO_DOCUMENT_END ->
                    document(ic, action)
                else -> menuAction(ime, ic, menuIdFor(action))
            }
            LogUtils.i(TAG, "perform", "动作 ${config.actionName(action)}（键 $key ${SwipeConst.DIR_NAMES[dir]} t9=$t9）成功=$ok")
            ok
        }.getOrElse {
            LogUtils.e(TAG, "perform", "动作 ${config.actionName(action)} 异常：${it.javaClass.simpleName}: ${it.message}")
            false
        }
    }

    // ---------------------------------------------------------------- 基础动作

    /** `android.R.id.*` 动作 id 映射（未列出的返回 0 = 不支持） */
    private fun menuIdFor(action: Int): Int = when (action) {
        SwipeConst.A_SELECT_ALL -> android.R.id.selectAll
        SwipeConst.A_CUT -> android.R.id.cut
        SwipeConst.A_COPY -> android.R.id.copy
        SwipeConst.A_PASTE -> android.R.id.paste
        SwipeConst.A_UNDO -> android.R.id.undo
        SwipeConst.A_REDO -> android.R.id.redo
        else -> 0
    }

    /**
     * 三级降级的菜单动作。
     *
     * 注意 `InputConnection#performContextMenuAction` 在部分编辑器里恒返回 false（View 自绘的
     * EditText 有时只认 `onTextContextMenuItem`），所以必须保留第二、三级。
     */
    private fun menuAction(ime: InputMethodService, ic: InputConnection, menuId: Int): Boolean {
        if (menuId == 0) return false
        runCatching { if (ic.performContextMenuAction(menuId)) return true }

        // 宿主 IME 自己的 performContextMenuAction(int)（实测存在，public）
        runCatching {
            val m = Reflect.findMethodDeep(ime.javaClass, "performContextMenuAction", 1)
            if (m != null) {
                m.isAccessible = true
                val r = m.invoke(ime, menuId)
                if (r !is Boolean || r) return true
            }
        }

        return fallbackMenuAction(ime, ic, menuId)
    }

    /**
     * 兜底实现：不走编辑器菜单，直接操作选区与剪贴板。
     *
     * - 全选：`setSelection(0, 全文长度)`
     * - 复制/剪切：读选中文本 → 写剪贴板（剪切再删）
     * - 粘贴：读剪贴板纯文本 → `commitText`
     */
    private fun fallbackMenuAction(ime: InputMethodService, ic: InputConnection, menuId: Int): Boolean {
        return when (menuId) {
            android.R.id.selectAll -> {
                val full = fullText(ic) ?: return false
                full.text?.let { ic.setSelection(0, full.startOffset + it.length) } ?: false
            }

            android.R.id.copy, android.R.id.cut -> {
                val selected = selectedTextOf(ic)
                if (selected.isNullOrEmpty()) return false
                val cm = ime.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
                cm.setPrimaryClip(ClipData.newPlainText("text", selected))
                if (menuId == android.R.id.copy) {
                    true
                } else {
                    runCatching { ic.commitText("", 1) }.getOrDefault(false)
                }
            }

            android.R.id.paste -> {
                val cm = ime.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
                if (!cm.hasPrimaryClip()) return false
                val clip = cm.primaryClip ?: return false
                if (clip.itemCount == 0) return false
                // 富媒体剪贴板交给原生粘贴（走 commitContent 链路，见 pasteMediaEntry）
                val desc = cm.primaryClipDescription
                if (desc != null && (desc.hasMimeType("image/*") || desc.hasMimeType("video/*"))) return false
                val text = runCatching { clip.getItemAt(0).coerceToText(ime) }.getOrNull() ?: return false
                runCatching { ic.commitText(text, 1) }.getOrDefault(false)
            }

            else -> false
        }
    }

    /** 自己输入指定文本（按方向取文本：同一个键四个方向可以各存一段） */
    private fun insertText(ic: InputConnection, config: SwipeConfig, key: String, dir: Int): Boolean {
        val text = config.textFor(key, dir)
        if (text.isEmpty()) return false
        runCatching { ic.finishComposingText() }
        return runCatching { ic.commitText(text, 1) }.getOrDefault(false)
    }

    // ---------------------------------------------------------------- 大小写切换

    /** 已发出合成的 Shift 点击（防重入：动作在 view.post 里执行，连续触发可能重叠） */
    @Volatile
    private var capsBusy = false

    /**
     * 大小写模式切换：合成一次（或两次）真实 Shift 点击，走宿主完整链路
     * （键面刷新 + 引擎大写状态同步）。
     *
     * 语义与上游 `toggleCapsLock` 一致：
     * - 当前**已锁定**（ShiftState == 2）⇒ 点一次回到小写；
     * - 当前非锁定（小写 / 临时大写）⇒ 点两次，小布据此进入 `UPPER_LOCK_CASE` 持续大写
     *   （两次之间间隔 150ms；单击只得到「临时大写」，下一个字符打完就掉）。
     *
     * 找不到 Shift 键矩形（符号布局、九宫格某些模式）⇒ 退化为直接写状态机，
     * 只保证引擎状态正确，键面可能不刷新。
     */
    private fun toggleCapsLock(view: android.view.View?, ime: InputMethodService?): Boolean {
        if (capsBusy) return false
        // 键盘视图优先用传入的；传入为 null 时退回 IME 窗口装饰视图（Shift 键不一定在根上，
        // 但 findKeyRect 走的是键盘模型的键几何，只要视图能取到模型就行）
        val keyboard = view ?: runCatching { ime?.window?.window?.decorView }.getOrNull() ?: return false
        capsBusy = true
        try {
            val rect = findKeyRect(keyboard, SwipeConst.K_SHIFT)
            if (rect == null) {
                LogUtils.w(TAG, "caps", "未找到 Shift 键矩形，退化为直接写状态机")
                return setShiftStateDirectly(keyboard, -1)
            }
            val current = readShiftState(keyboard)
            val locked = current == 2
            val x = (rect[0] + rect[2]) * 0.5f
            val y = (rect[1] + rect[3]) * 0.5f
            // 合成触摸必须被 SwipeGesture 忽略，否则连点两次 Shift 会触发别人绑在 Shift 上的动作
            SwipeGesture.withSynthetic {
                val now = android.os.SystemClock.uptimeMillis()
                // 第 1 次：临到大写；第 2 次：小布据此进入 UPPER_LOCK_CASE（持续锁定）
                sendSyntheticTap(keyboard, x, y, now)
                if (!locked) sendSyntheticTap(keyboard, x, y, now + 150L)
            }
            LogUtils.i(TAG, "caps", "Shift 合成点击 from=$current lock=${!locked}")
            return true
        } catch (t: Throwable) {
            LogUtils.e(TAG, "caps", "大小写切换失败：${t.javaClass.simpleName}: ${t.message}")
            return false
        } finally {
            capsBusy = false
        }
    }

    /**
     * 读当前 ShiftState。
     *
     * 小布的点击路径调 `input.manager.r#q(int)` 写 `y.T`，状态机另有一套 `y.R`，
     * 两者可能不一致 ⇒ 取「任一为 2（`UPPER_LOCK_CASE`）即视为已锁定」，
     * 避免已锁定时又叠加两次点击（那会回到小写，用户会觉得「点了没反应」）。
     */
    private fun readShiftState(keyboard: android.view.View): Int {
        return runCatching {
            val cls = Reflect.classOrNull(keyboard.javaClass.classLoader, CLS_SHIFT_STATE) ?: return 0
            val r = readStaticInt(cls, "R")
            val t = readStaticInt(cls, "T")
            if (r == 2 || t == 2) 2 else maxOf(r, t)
        }.getOrDefault(0)
    }

    private fun readStaticInt(cls: Class<*>, name: String): Int = runCatching {
        val f = cls.getDeclaredField(name)
        f.isAccessible = true
        f.getInt(null)
    }.getOrDefault(0)

    private fun sendSyntheticTap(keyboard: android.view.View, x: Float, y: Float, downTime: Long) {
        val down = android.view.MotionEvent.obtain(downTime, downTime, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
        val up = android.view.MotionEvent.obtain(downTime, downTime + 40L, android.view.MotionEvent.ACTION_UP, x, y, 0)
        try {
            keyboard.dispatchTouchEvent(down)
            keyboard.dispatchTouchEvent(up)
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    /** 在键盘模型的按键列表里找指定键 id 的矩形 `[left, top, right, bottom]` */
    private fun findKeyRect(keyboard: android.view.View, keyId: String): FloatArray? {
        val t9 = KeyGeometry.isT9(keyboard)
        val keys = runCatching { KeyGeometry.collect(keyboard, t9) }.getOrNull() ?: return null
        val hit = keys[keyId] ?: return null
        return floatArrayOf(hit.left.toFloat(), hit.top.toFloat(), hit.right.toFloat(), hit.bottom.toFloat())
    }

    /**
     * 直接写 ShiftState：同时调 `input.manager.r#q(int)`（显示态）与 `r#r(int)`（引擎态 + Kernel），
     * 覆盖两套状态字段。`target < 0` = 按当前 `y.R` 取反。
     *
     * ⚠️ 这两个方法都是 `input.manager.r` 的**静态**方法（上游 `Method#invoke(null, ...)`），
     * 用 [Reflect.findMethod]（只查 declaredMethods）即可，不需要 deep。
     */
    private fun setShiftStateDirectly(keyboard: android.view.View, target: Int): Boolean {
        return runCatching {
            val loader = keyboard.javaClass.classLoader
            val stateCls = Reflect.classOrNull(loader, CLS_SHIFT_STATE) ?: return false
            val current = readStaticInt(stateCls, "R")
            val desired = if (target >= 0) target else if (current == 2) 0 else 2

            val manager = Reflect.classOrNull(loader, CLS_SHIFT_MANAGER) ?: return false
            val show = Reflect.findMethod(manager, "q", 1) ?: return false
            show.isAccessible = true
            show.invoke(null, desired)
            val state = Reflect.findMethod(manager, "r", 1) ?: return false
            state.isAccessible = true
            state.invoke(null, desired)
            LogUtils.i(TAG, "caps", "ShiftState 直写 $current -> $desired")
            true
        }.getOrDefault(false)
    }

    // ---------------------------------------------------------------- 粘贴

    /**
     * 粘贴：优先宿主输入法自己的剪贴板历史（含图片/视频），否则退回系统剪贴板菜单粘贴。
     *
     * 语义与参考模块 `performPaste` 一致：
     * 1. 系统剪贴板是富媒体 → 直接走原生粘贴；
     * 2. 宿主历史最新条目是富媒体 → 写回系统剪贴板后原生粘贴（失败再退 commitContent）；
     * 3. 宿主历史最新条目是文本 → 直接 commitText（绕过编辑器对粘贴的限制）；
     * 4. 都没有 → 原生 `android.R.id.paste`。
     */
    private fun paste(ime: InputMethodService, ic: InputConnection): Boolean {
        if (systemClipboardIsMedia(ime)) {
            val ok = nativePaste(ime, ic) || sendPasteKeyEvent(ic)
            LogUtils.i(TAG, "paste", "系统剪贴板富媒体，原生粘贴=$ok")
            return ok
        }

        val entry = ImeClipboard.latest(ime)
        if (entry != null && entry.isMedia) {
            val uri = entry.uriOrNull()
            if (uri != null) {
                val reused = systemClipboardMatches(ime, uri) || writeUriToClipboard(ime, uri)
                if (reused) {
                    runCatching { ic.finishComposingText() }
                    if (nativePaste(ime, ic) || sendPasteKeyEvent(ic)) {
                        LogUtils.i(TAG, "paste", "宿主历史富媒体已粘贴 uri=$uri")
                        return true
                    }
                }
                if (commitContent(ic, uri, entry.mimeTypes)) {
                    LogUtils.i(TAG, "paste", "宿主历史富媒体经 commitContent 粘贴 uri=$uri")
                    return true
                }
            }
            LogUtils.w(TAG, "paste", "宿主历史富媒体粘贴失败，退回原生")
        } else {
            val text = entry?.plainText ?: ""
            if (text.isNotEmpty()) {
                runCatching { ic.finishComposingText() }
                val ok = runCatching { ic.commitText(text, 1) }.getOrDefault(false)
                LogUtils.i(TAG, "paste", "宿主历史文本粘贴=$ok 长度=${text.length}")
                if (ok) return true
            }
        }

        runCatching { ic.finishComposingText() }
        return nativePaste(ime, ic) || sendPasteKeyEvent(ic)
    }

    private fun nativePaste(ime: InputMethodService, ic: InputConnection): Boolean {
        return runCatching { menuAction(ime, ic, android.R.id.paste) }.getOrDefault(false)
    }

    /** 剪贴板媒体兜底：KeyEvent 279 = KEYCODE_PASTE */
    private fun sendPasteKeyEvent(ic: InputConnection): Boolean {
        return runCatching {
            val down = ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PASTE))
            val up = ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_PASTE))
            LogUtils.i(TAG, "paste", "粘贴按键 down=$down up=$up")
            down || up
        }.getOrDefault(false)
    }

    private fun commitContent(ic: InputConnection, uri: Uri, mimeTypes: String?): Boolean {
        val mime = mimeTypes?.split(",")?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: "image/*"
        return runCatching {
            ic.commitContent(
                InputContentInfo(uri, ClipDescription("clipboard", arrayOf(mime)), null),
                1,
                null
            )
        }.getOrDefault(false)
    }

    private fun systemClipboardIsMedia(ime: InputMethodService): Boolean {
        return runCatching {
            val cm = ime.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
            if (!cm.hasPrimaryClip()) return false
            val desc = cm.primaryClipDescription ?: return false
            desc.hasMimeType("image/*") || desc.hasMimeType("video/*")
        }.getOrDefault(false)
    }

    private fun systemClipboardMatches(ime: InputMethodService, uri: Uri): Boolean =
        runCatching {
            val cm = ime.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
            val clip = cm.primaryClip ?: return false
            clip.itemCount > 0 && uri == clip.getItemAt(0).uri
        }.getOrDefault(false)

    private fun writeUriToClipboard(ime: InputMethodService, uri: Uri): Boolean = runCatching {
        val cm = ime.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        cm.setPrimaryClip(ClipData.newUri(ime.contentResolver, "clip", uri))
        true
    }.getOrDefault(false)

    // ---------------------------------------------------------------- 粘贴并发送

    /**
     * 粘贴并发送：先粘贴（可能是富媒体），再延时 350ms 触发编辑器发送动作。
     *
     * 富媒体粘贴后编辑器往往需要一小段时间完成插入，立刻 performEditorAction 会丢消息，
     * 350ms 是参考模块实测稳定的值，沿用。
     */
    private fun pasteAndSend(ime: InputMethodService, ic: InputConnection): Boolean {
        val pasted = paste(ime, ic)
        if (!pasted) {
            LogUtils.i(TAG, "pasteAndSend", "粘贴失败，放弃发送")
            return false
        }
        mainHandler.postDelayed({
            runCatching {
                val fresh = ime.currentInputConnection
                if (fresh != null) sendViaEditorAction(ime, fresh)
            }.onFailure { LogUtils.w(TAG, "pasteAndSend", "延时发送失败：${it.message}") }
        }, 350L)
        return true
    }

    /**
     * 触发编辑器发送。
     *
     * 顺序：先试 `IME_ACTION_SEND`(4)，再试输入框自己的 action，最后试 `IME_ACTION_NONE`。
     * 部分输入框只认 `imeOptions` 里的 action（如 `IME_ACTION_SEARCH`），所以三步都要走。
     */
    private fun sendViaEditorAction(ime: InputMethodService, ic: InputConnection): Boolean {
        val info: EditorInfo? = runCatching { ime.currentInputEditorInfo }.getOrNull()
        val options = info?.imeOptions ?: 0
        val own = options and 0xFF

        var ok = runCatching { ic.performEditorAction(EditorInfo.IME_ACTION_SEND) }.getOrDefault(false)
        if (!ok && own != EditorInfo.IME_ACTION_SEND) {
            ok = runCatching { ic.performEditorAction(own) }.getOrDefault(false)
        }
        if (!ok) {
            ok = runCatching { ic.performEditorAction(EditorInfo.IME_ACTION_NONE) }.getOrDefault(false)
        }
        LogUtils.i(TAG, "send", "发送=$ok imeOptions=0x${Integer.toHexString(options)}")
        return ok
    }

    // ---------------------------------------------------------------- 段落 / 文档

    /**
     * 段落动作（段首/段尾/选至段首/选至段尾）。
     *
     * 起点 = 当前选区；距离用光标前/后的上下文计算（换行符位置），因此不依赖编辑器给出全文。
     */
    private fun paragraph(ic: InputConnection, action: Int): Boolean {
        runCatching { ic.finishComposingText() }
        val snapshot = editorSnapshot(ic) ?: return false
        var start = snapshot.left - distanceToLineStart(snapshot.before)
        var end = snapshot.right + distanceToLineEnd(snapshot.after)
        when (action) {
            SwipeConst.A_PARAGRAPH_START -> end = start
            SwipeConst.A_PARAGRAPH_END -> start = end
            SwipeConst.A_SELECT_TO_PARAGRAPH_START -> end = snapshot.right
            SwipeConst.A_SELECT_TO_PARAGRAPH_END -> start = snapshot.left
        }
        val ok = runCatching { ic.setSelection(start, end) }.getOrDefault(false)
        if (ok) ImeRuntime.updateSelection(start, end)
        return ok
    }

    /**
     * 文档动作（文首/文尾/选至文首/选至文尾）。
     *
     * 文档末尾需要全文长度：优先 `getExtractedText`（全量），拿不到就把末尾按「当前已知最远位置」处理。
     */
    private fun document(ic: InputConnection, action: Int): Boolean {
        runCatching { ic.finishComposingText() }
        val snapshot = editorSnapshot(ic) ?: return false
        var docEnd = snapshot.right
        if (action == SwipeConst.A_DOCUMENT_END || action == SwipeConst.A_SELECT_TO_DOCUMENT_END) {
            docEnd = documentEnd(ic)
            // 全文长度拿不到（-1）时只能以当前光标为末尾，不如不做
            if (docEnd < 0) return false
        }
        val left = snapshot.left.coerceAtLeast(0)
        val right = snapshot.right.coerceAtLeast(0)
        val (start, end) = when (action) {
            SwipeConst.A_DOCUMENT_START -> 0 to 0
            SwipeConst.A_DOCUMENT_END -> docEnd to docEnd
            SwipeConst.A_SELECT_TO_DOCUMENT_START -> 0 to left
            else -> right to docEnd
        }
        val ok = runCatching { ic.setSelection(start, end) }.getOrDefault(false)
        if (ok) ImeRuntime.updateSelection(start, end)
        return ok
    }

    /**
     * 复制全部 / 剪切全部：先全选再执行复制/剪切，最后恢复原选区（复制时保留全选无用）。
     *
     * 恢复选区的意义：剪切会改变文档长度，恢复原坐标可能越界，`setSelection` 失败时由编辑器自己
     * 收敛，属可接受行为（与参考模块一致）。
     */
    private fun compound(ime: InputMethodService, view: android.view.View?, ic: InputConnection, copy: Boolean): Boolean {
        runCatching { ic.finishComposingText() }
        val snapshot = editorSnapshot(ic)
        // 已经是全选状态（无前后文）就没必要再走一遍
        if (snapshot != null && snapshot.left == snapshot.right &&
            snapshot.before.isEmpty() && snapshot.after.isEmpty()
        ) {
            return false
        }
        if (!menuAction(ime, ic, android.R.id.selectAll)) return false
        val handler = view?.handler ?: mainHandler
        val delay = 32L
        val copyMode = copy
        handler.postDelayed({
            runCatching {
                val fresh = ImeRuntime.requireIme(view)?.currentInputConnection ?: return@runCatching
                val ok = menuAction(
                    ImeRuntime.requireIme(view) ?: return@runCatching,
                    fresh,
                    if (copyMode) android.R.id.copy else android.R.id.cut,
                )
                if (!ok) LogUtils.w(TAG, "compound", "全选后${if (copyMode) "复制" else "剪切"}失败")
                // 复制后把选区还原（否则用户会看到整段被选中）
                if (copyMode && snapshot != null) {
                    runCatching {
                        if (fresh.setSelection(snapshot.left, snapshot.right)) {
                            ImeRuntime.updateSelection(snapshot.left, snapshot.right)
                        }
                    }
                }
            }
        }, delay)
        return true
    }

    // ---------------------------------------------------------------- 编辑器上下文

    /** 编辑器快照：已知选区 + 前后上下文（三种取法降级） */
    private class Snapshot(
        val left: Int,
        val right: Int,
        val before: String,
        val after: String,
    )

    /**
     * 取编辑器快照。
     *
     * 三级降级（与参考模块等价）：
     * 1. `getSurroundingText`（API 31+，最准，直接带 offset）
     * 2. 「已知选区 + `getTextBeforeCursor` / `getTextAfterCursor`」
     * 3. `getExtractedText`（全量文本，最后手段）
     */
    private fun editorSnapshot(ic: InputConnection): Snapshot? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                val st: SurroundingText? = ic.getSurroundingText(CONTEXT_CHARS, CONTEXT_CHARS, 0)
                val text = st?.text?.toString()
                if (st != null && text != null) {
                    val selStart = st.selectionStart.coerceIn(0, text.length)
                    val selEnd = st.selectionEnd.coerceIn(0, text.length)
                    val lo = minOf(selStart, selEnd)
                    val hi = maxOf(selStart, selEnd)
                    val offset = st.offset.coerceAtLeast(0)
                    return Snapshot(
                        offset + lo, offset + hi,
                        text.substring(0, lo), text.substring(hi),
                    )
                }
            }.onFailure { LogUtils.d(TAG, "snapshot", "surroundingText 不可用：${it.message}") }
        }

        val knownStart = ImeRuntime.selectionStart
        val knownEnd = ImeRuntime.selectionEnd
        if (knownStart >= 0 && knownEnd >= 0) {
            runCatching {
                val before = ic.getTextBeforeCursor(CONTEXT_CHARS, 0)?.toString()
                val after = ic.getTextAfterCursor(CONTEXT_CHARS, 0)?.toString()
                if (before != null && after != null) {
                    return Snapshot(minOf(knownStart, knownEnd), maxOf(knownStart, knownEnd), before, after)
                }
            }.onFailure { LogUtils.d(TAG, "snapshot", "cursor context 不可用：${it.message}") }
        }

        return runCatching {
            val req = ExtractedTextRequest().apply {
                hintMaxChars = CONTEXT_CHARS
                hintMaxLines = 4096
            }
            val et = ic.getExtractedText(req, 0) ?: return null
            val text = et.text?.toString() ?: return null
            val selStart = et.selectionStart.coerceIn(0, text.length)
            val selEnd = et.selectionEnd.coerceIn(0, text.length)
            val lo = minOf(selStart, selEnd)
            val hi = maxOf(selStart, selEnd)
            val offset = et.startOffset.coerceAtLeast(0)
            Snapshot(offset + lo, offset + hi, text.substring(0, lo), text.substring(hi))
        }.getOrNull()
    }

    /** 全文（取文尾用） */
    private fun fullText(ic: InputConnection): ExtractedText? = runCatching {
        val req = ExtractedTextRequest().apply {
            hintMaxChars = 1_000_000
            hintMaxLines = 100_000
        }
        ic.getExtractedText(req, 0)
    }.getOrNull()

    /** 文档末尾绝对下标；-1 = 拿不到全文（`startOffset > 0` 说明只拿到片段，长度不可靠） */
    private fun documentEnd(ic: InputConnection): Int {
        val full = fullText(ic) ?: return -1
        val text = full.text ?: return -1
        if (full.startOffset > 0) return -1
        return text.length
    }

    /** 光标前上下文里距离行首的字符数 */
    private fun distanceToLineStart(before: String): Int {
        for (i in before.length - 1 downTo 0) {
            if (isLineBreak(before[i])) return before.length - i - 1
        }
        return before.length
    }

    /** 光标后上下文里距离行尾的字符数 */
    private fun distanceToLineEnd(after: String): Int {
        for (i in after.indices) {
            if (isLineBreak(after[i])) return i
        }
        return after.length
    }

    /** 换行符：`\n` `\r` 以及 Unicode 行分隔符 LS/PS */
    private fun isLineBreak(c: Char): Boolean = c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029'

    /** 输入法切换类动作 */
    private fun performImeAction(ime: InputMethodService, action: Int): Boolean = when (action) {
        SwipeConst.A_NEXT_IME -> runCatching { ime.switchToNextInputMethod(false) }.getOrDefault(false)
        SwipeConst.A_PREV_IME -> runCatching { ime.switchToPreviousInputMethod() }.getOrDefault(false)
        SwipeConst.A_IME_PICKER -> runCatching {
            val imm = ime.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showInputMethodPicker()
            imm != null
        }.getOrDefault(false)
        else -> false
    }

    /** 从宿主剪贴板数据库读最新条目（富媒体粘贴用），详见 [ImeClipboard] */
    fun imeClipboardLatest(ime: InputMethodService) = ImeClipboard.latest(ime)

    /**
     * 读当前选中的文本（`InputConnection#getSelectedText()`）。
     *
     * 为什么用反射：该方法在 `android.view.inputmethod.InputConnection` 上是 API 9 起的公开方法，
     * 但本工程 `hidden-api-stub` 模块提供了同名包的桩类，编译期解析到桩接口就会报
     * `Unresolved reference 'selectedText'`。反射读取既能绕开这个编译期冲突，
     * 也能兼容个别 ROM 把该方法改成默认实现返回 null 的情况。
     */
    private fun selectedTextOf(ic: InputConnection?): CharSequence? = runCatching {
        Reflect.callByName(ic, "getSelectedText") as? CharSequence
    }.getOrNull()

    /** 清空一次性日志（配置切换时） */
    fun reset() {
        mainHandler.removeCallbacksAndMessages(null)
    }
}