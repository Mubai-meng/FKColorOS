package com.fr.colorostools.hook.xiaobuswipe

import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import com.fr.colorostools.utils.LogUtils
import java.lang.ref.WeakReference

/**
 * 按键滑动手势识别 + 按键标签绘制 + 触发提示气泡 —— v2.0.0 的四方向模型。
 *
 * ## 手势判定（对齐上游 `MainHook#interceptKeyboardTouch`）
 *
 * 单指按住某个已绑定的键往某个方向滑，超过该方向的阈值即触发；触发时给原触摸序列补一个
 * `ACTION_CANCEL`，让宿主键盘认为这次点击被取消（否则松手时会照常上屏一个字符）。
 *
 * 判定流程：
 * 1. **DOWN**：命中键 + 该键**任一方向**有绑定 ⇒ 记录起点、逐方向动作与阈值；
 *    `chain.proceed()` 照常下发（键盘要画按压态）。
 * 2. **MOVE/UP**：
 *    - 还没锁定方向时：位移 < [SwipeConst.SLOP_DP] ⇒ 认作点击，直通宿主；
 *      否则先 [resolveDirection] 定方向，该方向未绑定 ⇒ 直通；已绑定 ⇒ **锁定**该方向。
 *    - 锁定后**一律吞事件**（`return true`）：这是关键 —— 宿主的特殊键（退格上滑删整段、
 *      空格左右移光标）自带原生手势，不吞的话会和我们的动作同时发生。
 *    - 按方向取位移（横向看 `|dx|`、纵向看 `|dy|`）与该方向阈值比较：
 *      `≥ 0.8×阈值` 先出预览气泡，`≥ 阈值` 触发。
 * 3. **触发**：补发 CANCEL 清按压态 → 触感 → 提示"✓ 动作" → 执行动作
 *    （[SwipeConst.A_DISABLE] 只吞不执行）。
 *
 * ## 方向判定
 * 横向位移必须超过纵向的 [SwipeConst.HORIZONTAL_DOMINANCE] 倍才判左右，否则按纵向；
 * 纵向再按 `deltaY > 0` 分下滑 / 上滑。这样轻微斜着下滑不会被误判成左/右滑。
 *
 * ## 已触发后吞事件的意义
 * 触发瞬间已经给宿主补发了 CANCEL，宿主不会再收到后续事件；若我们仍把 MOVE 下发，
 * 宿主的按键状态机可能重新走「按下→抬起」而上屏字符。所以 triggered 期间必须吞掉。
 */
internal object SwipeGesture {

    private const val TAG = SwipeConst.TAG

    /** 提示气泡预览阈值：滑到 `阈值×0.8` 就先显示"当前键 方向：动作"（未触发状态） */
    private const val HINT_PREVIEW_RATIO = 0.8f

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * 合成触摸标记：**必须在 [onTouch] 最开头早退**。
     *
     * [EditorActions] 的「大小写切换」会直接 `dispatchTouchEvent` 派发一对合成的
     * DOWN/UP 到键盘视图 —— 它同样会经过本 hook。若不早退，合成点击会被当成真实用户手势
     * （连点两次 Shift 就可能触发别人绑在 Shift 上的滑动动作，递归到动作执行里）。
     */
    @Volatile
    var synthesizing: Boolean = false
        private set

    /** 在合成触摸期间执行 [block]（内部派发全程被 [onTouch] 忽略） */
    inline fun <T> withSynthetic(block: () -> T): T {
        val prev = synthesizing
        synthesizing = true
        try {
            return block()
        } finally {
            synthesizing = prev
        }
    }

    /** 跟踪器状态（单指手势，一个视图一份） */
    private class Tracker {
        var active = false
        var viewRef: WeakReference<View> = WeakReference(null)
        var downX = 0f
        var downY = 0f
        /** 4 个方向的动作（未绑定 = [SwipeConst.A_NONE]） */
        val actions = IntArray(SwipeConst.DIR_COUNT)
        /** 4 个方向的阈值（px，已按物理上限夹过） */
        val thresholds = FloatArray(SwipeConst.DIR_COUNT)
        var slopPx = 1f
        var key: String? = null
        /** 提示气泡的显示名（特殊键 → 中文名，字母 → 大写） */
        var keyDisplay: String = ""
        var t9 = false
        /** 该键是否有任一方向绑定（没有就直接直通，不进锁定逻辑） */
        var anyBinding = false
        var locked = false
        var dir = SwipeConst.DIR_DOWN
        var triggered = false

        fun begin(v: View, x: Float, y: Float) {
            viewRef = WeakReference(v)
            downX = x
            downY = y
            for (d in 0 until SwipeConst.DIR_COUNT) {
                actions[d] = SwipeConst.A_NONE
                thresholds[d] = 1f
            }
            slopPx = 1f
            key = null
            keyDisplay = ""
            t9 = false
            anyBinding = false
            locked = false
            dir = SwipeConst.DIR_DOWN
            triggered = false
            active = true
        }

        fun matches(v: View): Boolean = active && viewRef.get() === v

        fun lock(direction: Int) {
            locked = true
            dir = direction
        }

        fun clear() {
            active = false
            key = null
            keyDisplay = ""
            t9 = false
            anyBinding = false
            locked = false
            dir = SwipeConst.DIR_DOWN
            triggered = false
            for (d in 0 until SwipeConst.DIR_COUNT) actions[d] = SwipeConst.A_NONE
        }
    }

    private val tracker = Tracker()

    /** 当前键盘视图（提示气泡挂它的窗口根） */
    private var keyboardViewRef: WeakReference<View> = WeakReference(null)

    /** 提示气泡 */
    private var hintViewRef: WeakReference<android.widget.TextView> = WeakReference(null)
    private var hintRootRef: WeakReference<View> = WeakReference(null)
    private var hintHideTask: Runnable? = null

    /** 键盘类型缓存（视图 → 是否九宫格 / 是否手写） */
    private var cachedViewRef: WeakReference<View> = WeakReference(null)
    private var cachedT9 = false
    private var cachedHandwriting = false

    /** 键盘标签绘制的几何缓存 */
    private var labelViewRef: WeakReference<View> = WeakReference(null)
    private var labelKeys: Map<String, KeyGeometry.Hit> = emptyMap()
    private var labelWidth = -1
    private var labelHeight = -1
    private var labelKeyCount = -1
    /** 标签绘制用的 Paint（复用，避免 onDraw 里反复 new） */
    private val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }
    private var labelPaintSignature = Long.MIN_VALUE

    /** 一次性日志标记（避免刷屏） */
    private val loggedOnce = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // ================================================================ 手势入口

    /**
     * 触摸事件处理（挂在 `View#dispatchTouchEvent` 上）。
     *
     * 返回值语义**严格二态**：
     * - `null` = 本次完全不接管，调用方必须自己 `chain.proceed()`（本方法内部**没有**调用过 proceed）；
     * - 非 null = 最终返回值，调用方原样返回（本方法内部已按需调用过 proceed）。
     */
    fun onTouch(chain: io.github.libxposed.api.XposedInterface.Chain, view: View, event: MotionEvent): Boolean? {
        // 合成触摸（大小写切换派发的）一律不参与手势判定
        if (synthesizing) return null

        val config = SwipeConfig.load()
        if (!config.enabled || !config.hasAnyBinding) {
            if (tracker.active) tracker.clear()
            logOnce("skip:${config.enabled}:${config.hasAnyBinding}") {
                if (!config.enabled) {
                    "手势直通：滑动开关未开启"
                } else {
                    "手势直通：所有键均未绑定动作（26 键 ${config.bindingCount()} 个 / " +
                        "九宫格 ${config.t9BindingCount()} 个 / 特殊键 ${config.specialBindingCount()} 个）"
                }
            }
            return null
        }

        val actionMasked = event.actionMasked
        val pointerCount = event.pointerCount

        // 手写键盘：不参与滑动（笔迹会与手势冲突），顺手把气泡收掉
        val state = runtimeState(view)
        if (state.handwriting) {
            if (actionMasked == MotionEvent.ACTION_DOWN || actionMasked == MotionEvent.ACTION_UP) {
                hideHint(0)
            }
            if (tracker.matches(view)) tracker.clear()
            return null
        }

        // 多指：直通，但要把单指状态清掉
        if (pointerCount != 1) {
            if (actionMasked == MotionEvent.ACTION_UP || actionMasked == MotionEvent.ACTION_CANCEL) {
                tracker.clear()
                hideHint(0)
            }
            return null
        }

        // 非 DOWN 且不是我们正在跟踪的那个视图：直通（快速滑动等会让 MOVE 先于 DOWN 到达）
        if (actionMasked != MotionEvent.ACTION_DOWN && !tracker.matches(view)) {
            return null
        }

        if (actionMasked == MotionEvent.ACTION_DOWN) {
            return onDown(chain, view, event, state, config)
        }

        if (!tracker.matches(view)) return null

        // 已触发：吞掉后续事件，等 UP/CANCEL 复位
        if (tracker.triggered) {
            if (actionMasked == MotionEvent.ACTION_UP || actionMasked == MotionEvent.ACTION_CANCEL) {
                tracker.clear()
                hideHint(SwipeConst.HINT_HIDE_AFTER_UP_MS)
            }
            return true
        }

        if (actionMasked == MotionEvent.ACTION_MOVE || actionMasked == MotionEvent.ACTION_UP ||
            actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            return onMove(chain, view, event, actionMasked, config)
        }

        // 其余（ACTION_POINTER_* 等，单指下不会出现）：下发并处理收尾
        val r = proceeded(chain)
        if (actionMasked == MotionEvent.ACTION_UP || actionMasked == MotionEvent.ACTION_CANCEL) {
            tracker.clear()
            hideHint(SwipeConst.HINT_HIDE_AFTER_UP_MS)
        }
        return r
    }

    /** DOWN：命中键 → 逐方向记录动作与阈值；无任何绑定则清掉跟踪、直通 */
    private fun onDown(
        chain: io.github.libxposed.api.XposedInterface.Chain,
        view: View,
        event: MotionEvent,
        state: KeyboardState,
        config: SwipeConfig,
    ): Boolean? {
        keyboardViewRef = WeakReference(view)
        tracker.begin(view, event.x, event.y)

        // 先下发：宿主需要这次 DOWN 来绘制按压态与启动长按计时
        val passed = proceeded(chain)

        val hit = KeyGeometry.hitAt(view, event.x, event.y, state.t9)
        if (hit == null || !config.hasAnyBinding(hit.key)) {
            tracker.clear()
            return passed
        }

        tracker.key = hit.key
        tracker.keyDisplay = hit.display
        tracker.t9 = hit.t9
        tracker.anyBinding = true
        tracker.slopPx = dp(view, SwipeConst.SLOP_DP).toFloat().coerceAtLeast(1f)

        val width = view.width
        val height = view.height
        var anyAction = false
        for (d in 0 until SwipeConst.DIR_COUNT) {
            val action = config.actionFor(hit.key, d)
            tracker.actions[d] = action
            if (action != SwipeConst.A_NONE) anyAction = true
            var px = dp(view, config.distanceFor(hit.key, hit.t9, d)).toFloat()
            // 物理上限：纵向不超键盘高 60%、横向不超宽 40%，否则手指根本滑不到
            val horizontal = d == SwipeConst.DIR_LEFT || d == SwipeConst.DIR_RIGHT
            if (horizontal) {
                if (width > 0) px = minOf(px, width * SwipeConst.MAX_THRESHOLD_RATIO_HORIZONTAL)
            } else {
                if (height > 0) px = minOf(px, height * SwipeConst.MAX_THRESHOLD_RATIO_VERTICAL)
            }
            tracker.thresholds[d] = px.coerceAtLeast(1f)
        }

        if (!anyAction) {
            tracker.clear()
            return passed
        }

        logOnce("bind:${hit.key}") {
            val sb = StringBuilder()
            for (d in 0 until SwipeConst.DIR_COUNT) {
                val a = tracker.actions[d]
                if (a == SwipeConst.A_NONE) continue
                if (sb.isNotEmpty()) sb.append('、')
                sb.append(SwipeConst.DIR_NAMES[d]).append('=').append(config.actionName(a))
                    .append("(${tracker.thresholds[d].toInt()}px)")
            }
            "按键 ${hit.key}（t9=${hit.t9}）绑定：$sb；键盘 ${width}x$height"
        }
        // 标签可能因键盘重排而失效，先失效一次
        invalidateLabelCacheIfNeeded(view)
        return passed
    }

    /** MOVE / UP / CANCEL：先定方向并锁定，再按该方向位移判定触发 */
    private fun onMove(
        chain: io.github.libxposed.api.XposedInterface.Chain,
        view: View,
        event: MotionEvent,
        actionMasked: Int,
        config: SwipeConfig,
    ): Boolean {
        val dx = event.x - tracker.downX
        val dy = event.y - tracker.downY
        val absX = kotlin.math.abs(dx)
        val absY = kotlin.math.abs(dy)

        if (!tracker.locked) {
            // 位移小于点击容差 ⇒ 认作点击，交还宿主（否则轻点也会被吞，键就按不出来了）
            val hypot = kotlin.math.hypot(absX, absY)
            if (hypot < tracker.slopPx) return finishOrProceed(chain, actionMasked)

            val dir = resolveDirection(dx, dy)
            if (tracker.actions[dir] == SwipeConst.A_NONE) {
                // 该方向没绑定 —— 但别的方向有绑定，仍要直通让宿主处理自己的原生手势
                return finishOrProceed(chain, actionMasked)
            }
            tracker.lock(dir)
            logOnce("lock:${tracker.key}:$dir") {
                "按键 ${tracker.keyDisplay} 锁定方向 ${SwipeConst.DIR_NAMES[dir]}，" +
                    "动作 ${config.actionName(tracker.actions[dir])}，" +
                    "阈值 ${tracker.thresholds[dir].toInt()}px"
            }
        }

        val dir = tracker.dir
        val horizontal = dir == SwipeConst.DIR_LEFT || dir == SwipeConst.DIR_RIGHT
        val distance = if (horizontal) absX else absY
        val threshold = tracker.thresholds[dir]

        if (actionMasked == MotionEvent.ACTION_MOVE) {
            if (distance >= threshold * HINT_PREVIEW_RATIO && distance < threshold) {
                // 预览：还没到触发线，先告诉用户松手会执行什么
                val key = tracker.key
                val display = tracker.keyDisplay
                val act = tracker.actions[dir]
                view.post { showHint(view, key, display, dir, act, executed = false) }
            } else if (distance < threshold * HINT_PREVIEW_RATIO) {
                view.post { hideHint(0) }
            }
        }

        if (distance >= threshold) {
            return trigger(chain, view, event, actionMasked, dir)
        }

        // 已锁定方向但未达阈值：**继续吞事件**（屏蔽宿主该方向的原生手势），
        // UP/CANCEL 时清状态收尾 —— 此时不会执行动作，也不会补上屏字符（用户滑了一段又松手）。
        if (actionMasked == MotionEvent.ACTION_UP || actionMasked == MotionEvent.ACTION_CANCEL) {
            tracker.clear()
            view.post { hideHint(SwipeConst.HINT_HIDE_AFTER_UP_MS) }
        }
        return true
    }

    /**
     * 方向判定：横向位移需超过纵向的 [SwipeConst.HORIZONTAL_DOMINANCE] 倍才判左右，
     * 否则按纵向；纵向 `deltaY > 0` 为下滑、`< 0` 为上滑。
     */
    private fun resolveDirection(dx: Float, dy: Float): Int {
        val absX = kotlin.math.abs(dx)
        val absY = kotlin.math.abs(dy)
        return if (absX > absY * SwipeConst.HORIZONTAL_DOMINANCE) {
            if (dx > 0) SwipeConst.DIR_RIGHT else SwipeConst.DIR_LEFT
        } else {
            if (dy > 0) SwipeConst.DIR_DOWN else SwipeConst.DIR_UP
        }
    }

    /** 触发：补发 CANCEL → 触感 → 提示 → 动作（[SwipeConst.A_DISABLE] 只吞不执行） */
    private fun trigger(
        chain: io.github.libxposed.api.XposedInterface.Chain,
        view: View,
        event: MotionEvent,
        actionMasked: Int,
        dir: Int,
    ): Boolean {
        val action = tracker.actions[dir]
        val key = tracker.key ?: ""
        val display = tracker.keyDisplay
        val t9 = tracker.t9
        tracker.triggered = true
        // 已触发：气泡换成「✓ 动作」的已执行态，预览标识复位（退回区间不应再显示预览）

        proceededCancel(chain, event)

        val config = SwipeConfig.load()
        if (config.vibration) {
            runCatching { view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP) }
        }

        view.post { showHint(view, key, display, dir, action, executed = true) }
        if (action != SwipeConst.A_DISABLE) {
            view.post {
                runCatching {
                    EditorActions.perform(view, key, t9, action, dir, config)
                }.onFailure {
                    LogUtils.e(TAG, "trigger", "动作执行异常：${it.javaClass.simpleName}: ${it.message}")
                }
            }
        }

        if (actionMasked == MotionEvent.ACTION_UP) {
            tracker.clear()
            view.post { hideHint(SwipeConst.HINT_HIDE_DELAY_MS) }
        }
        return true
    }

    /** 未锁定 / 不该接管：下发事件，UP/CANCEL 收尾 */
    private fun finishOrProceed(
        chain: io.github.libxposed.api.XposedInterface.Chain,
        actionMasked: Int,
    ): Boolean {
        val r = proceeded(chain)
        if (actionMasked == MotionEvent.ACTION_UP || actionMasked == MotionEvent.ACTION_CANCEL) {
            tracker.clear()
            hideHint(SwipeConst.HINT_HIDE_AFTER_UP_MS)
        }
        return r
    }

    /**
     * 给宿主补发一个 `ACTION_CANCEL`。
     *
     * 不能直接 `chain.proceed()`（那会把这次滑动当成点击上屏），也不能只 `return true`
     * （宿主的按压态不会被清除，可能出现整键残留高亮）。用副本事件走一次原方法再回收，
     * 是唯一干净的做法。
     */
    private fun proceededCancel(chain: io.github.libxposed.api.XposedInterface.Chain, event: MotionEvent) {
        // 副本：obtain 会复制全部指针信息，改 action 后不影响原事件
        val cancel = MotionEvent.obtain(event).apply {
            action = MotionEvent.ACTION_CANCEL
        }
        try {
            val args = chain.args.toMutableList()
            args[0] = cancel
            chain.proceed(args.toTypedArray())
        } catch (t: Throwable) {
            LogUtils.w(TAG, "cancel", "补发 CANCEL 失败：${t.javaClass.simpleName}")
        } finally {
            cancel.recycle()
        }
    }

    // ================================================================ 键盘状态

    /** 键盘运行态（九宫格 / 手写），按视图缓存 */
    private class KeyboardState(val t9: Boolean, val handwriting: Boolean)

    private fun runtimeState(view: View): KeyboardState {
        val cached = cachedViewRef.get()
        if (cached === view) return KeyboardState(cachedT9, cachedHandwriting)
        val handwriting = KeyGeometry.isHandwriting(view)
        val t9 = if (handwriting) false else KeyGeometry.isT9(view)
        cachedViewRef = WeakReference(view)
        cachedT9 = t9
        cachedHandwriting = handwriting
        return KeyboardState(t9, handwriting)
    }

    // ================================================================ 按键标签绘制

    /**
     * 在键盘视图上绘制各键的绑定短标签（`onDraw` 后置调用）。
     *
     * 一个键面只画**一个**方向的标签（按 [SwipeConst.LABEL_PRIORITY] 取优先级最高的已绑定方向），
     * 文本 = 方向箭头（`↓` / `↑` / `←` / `→`）+ 动作短名，例如 `↓全选`、`↑撤销`。
     * 画在键面底部偏上，避开键字母本身；夜间浅字+深影 / 日间深字+浅影。
     */
    fun drawLabels(view: View, canvas: Canvas) {
        val config = SwipeConfig.load()
        if (!config.enabled || !config.showLabels || !config.hasAnyBinding) return
        if (ImeRuntime.editorPassword) return
        val state = runtimeState(view)
        if (state.handwriting) return
        if (view.width <= 0 || view.height <= 0) return

        ensureLabelCache(view, state.t9)
        if (labelKeys.isEmpty()) return
        preparePaint(view)

        var drawn = 0
        for ((key, hit) in labelKeys) {
            // 优先级最高的已绑定方向（无绑定或只绑了禁用 ⇒ -1，不带箭头）
            val dir = config.primaryDirection(key)
            val action = if (dir < 0) SwipeConst.A_NONE else config.actionFor(key, dir)
            // 标签三态解析（自动=动作短名 / 自定义文字 / 隐藏）交给 SwipeConfig，
            // 空串即「不绘制」。⚠️ 必须先解析再判空：未绑定但设了自定义标签的键**仍然要画**。
            val label = config.labelFor(key, action)
            if (label.isEmpty()) continue
            val text = if (dir >= 0) SwipeConst.DIR_SHORT[dir] + label else label
            drawLabel(canvas, text, hit, view)
            drawn++
        }
        if (drawn > 0) {
            logOnce("labels") {
                "已绘制 $drawn 个按键标签（${view.width}x${view.height}，t9=${state.t9}）"
            }
        }
    }

    private fun ensureLabelCache(view: View, t9: Boolean) {
        val sameView = labelViewRef.get() === view
        val sameGeometry = labelWidth == view.width && labelHeight == view.height
        if (sameView && sameGeometry && labelKeys.isNotEmpty()) return
        val keys = runCatching { KeyGeometry.collect(view, t9) }.getOrDefault(emptyMap())
        if (keys.isEmpty()) return
        labelViewRef = WeakReference(view)
        labelKeys = keys
        labelWidth = view.width
        labelHeight = view.height
        labelKeyCount = keys.size
        logOnce("label_geometry:${view.width}x${view.height}:${keys.size}") {
            "标签几何就绪：${keys.size} 键，t9=$t9，尺寸 ${view.width}x${view.height}"
        }
    }

    private fun invalidateLabelCacheIfNeeded(view: View) {
        if (labelViewRef.get() === view &&
            labelWidth == view.width && labelHeight == view.height
        ) return
        labelKeys = emptyMap()
        labelPaintSignature = Long.MIN_VALUE
    }

    private fun preparePaint(view: View) {
        // 标签是键面上的图形文字，不该跟随系统字体缩放 → 用 density 而非 scaledDensity
        val density = view.resources.displayMetrics.density
        // 字号：随键盘高度缩放，5.8dp..8dp 之间（键高越大字越大）
        val textSize = maxOf(5.8f * density, minOf(8.0f * density, (view.height / 4.0f) * 0.145f))
        val night = ImeRuntime.nightMode == 0x20
        val signature = (view.width.toLong() shl 40) xor (view.height.toLong() shl 16) xor
            java.lang.Float.floatToIntBits(textSize).toLong() xor (if (night) 1L else 0L)
        if (signature == labelPaintSignature) return
        labelPaintSignature = signature
        labelPaint.textSize = textSize
        if (night) {
            labelPaint.color = 0xEEF5F5F5.toInt()
            labelPaint.setShadowLayer(maxOf(1f, density), 0f, 0f, 0xD9000000.toInt())
        } else {
            labelPaint.color = 0xC4555A62.toInt()
            labelPaint.setShadowLayer(maxOf(1f, density), 0f, 0f, 0xE7FFFFFF.toInt())
        }
    }

    private fun drawLabel(canvas: Canvas, text: String, hit: KeyGeometry.Hit, view: View) {
        if (text.isEmpty()) return
        val base = labelPaint.textSize
        // 长标签缩字，避免压到相邻键（方向箭头会占一个字符位，所以阈值放宽到 4）
        val scale = when {
            text.length >= 4 -> 0.78f
            text.length == 3 -> 0.88f
            else -> 1f
        }
        labelPaint.textSize = base * scale
        val x = hit.centerX
        val y = hit.bottom - maxOf(dp(view, 1).toFloat(), (hit.bottom - hit.top) * 0.045f) -
            labelPaint.fontMetrics.descent
        canvas.drawText(text, x, y, labelPaint)
        labelPaint.textSize = base
    }

    // ================================================================ 提示气泡

    /**
     * 显示触发提示（挂在键盘窗口根上的绝对定位 TextView）。
     *
     * - 预览（未触发）：`Z ↓全选`（键名 + 方向箭头 + 动作名）
     * - 已触发：`✓ 全选`；禁用方向时 `✓ 已禁用下滑`
     */
    private fun showHint(
        view: View,
        key: String?,
        display: String,
        dir: Int,
        action: Int,
        executed: Boolean,
    ) {
        val config = SwipeConfig.load()
        if (!config.showHint || action == SwipeConst.A_NONE) {
            hideHint(0)
            return
        }
        if (ImeRuntime.editorPassword) {
            hideHint(0)
            return
        }
        val tv = resolveHintView(view) ?: return
        hintHideTask?.let { tv.removeCallbacks(it) }
        val dirName = SwipeConst.DIR_NAMES[dir]
        val text = when {
            executed && action == SwipeConst.A_DISABLE -> "✓ 已禁用$dirName"
            executed -> "✓ ${config.actionName(action)}"
            else -> {
                val who = display.ifEmpty { key?.uppercase().orEmpty().ifEmpty { "当前键" } }
                "$who ${SwipeConst.DIR_SHORT[dir]}$dirName：${config.actionName(action)}"
            }
        }
        tv.text = text
        positionHint(view, tv)
        tv.alpha = 1f
        tv.visibility = View.VISIBLE
        if (executed) hideHint(SwipeConst.HINT_HIDE_DELAY_MS)
    }

    /**
     * 取（或创建）提示气泡：
     * 优先用 IME 窗口的 DecorView（正是键盘窗口的根），否则用键盘视图自己的根。
     */
    private fun resolveHintView(view: View): android.widget.TextView? {
        val ime = ImeRuntime.ime()
        // InputMethodService#getWindow() -> Dialog；Dialog#getWindow() -> android.view.Window；
        // Window#getDecorView() -> DecorView。注意 Dialog 本身没有 decorView。
        val imeRoot = runCatching { ime?.window?.window?.decorView }.getOrNull()
        val root: View? = runCatching {
            if (imeRoot is android.widget.FrameLayout && view.windowToken != null &&
                view.windowToken == imeRoot.windowToken
            ) {
                imeRoot
            } else {
                view.rootView
            }
        }.getOrNull()
        val frame = root as? android.widget.FrameLayout ?: return null

        val existing = hintViewRef.get()
        if (hintRootRef.get() === frame && existing != null && existing.parent === frame) return existing

        clearHint()
        val tv = android.widget.TextView(view.context).apply {
            setTextColor(0xF2FFFFFF.toInt())
            textSize = 11f
            gravity = android.view.Gravity.CENTER
            isSingleLine = true
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(view, 4), dp(view, 1), dp(view, 4), dp(view, 1))
            isClickable = false
            isFocusable = false
            isFocusableInTouchMode = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            elevation = dp(view, 10).toFloat()
            visibility = View.GONE
            background = null
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setShadowLayer(dp(view, 2).toFloat(), 0f, dp(view, 1).toFloat(), 0xD0000000.toInt())
        }
        frame.addView(tv, android.widget.FrameLayout.LayoutParams(-2, -2, android.view.Gravity.TOP or android.view.Gravity.START))
        hintRootRef = WeakReference(frame)
        hintViewRef = WeakReference(tv)
        return tv
    }

    /** 定位气泡到按下键的上方（夹在窗口内，不越界） */
    private fun positionHint(view: View, tv: android.widget.TextView) {
        val root = hintRootRef.get() as? android.widget.FrameLayout ?: return
        val lp = (tv.layoutParams as? android.widget.FrameLayout.LayoutParams)
            ?: android.widget.FrameLayout.LayoutParams(-2, -2).also { tv.layoutParams = it }
        lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        lp.bottomMargin = 0
        runCatching {
            val rw = root.width
            val rh = root.height
            if (rw <= 0 || rh <= 0) {
                tv.visibility = View.GONE
                return
            }
            val rootLoc = IntArray(2).also { root.getLocationInWindow(it) }
            val viewLoc = IntArray(2).also { view.getLocationInWindow(it) }
            val offsetX = viewLoc[0] - rootLoc[0]
            val offsetY = viewLoc[1] - rootLoc[1]
            val viewW = maxOf(1, view.width)
            val pad = dp(view, 3)
            val maxWidth = maxOf(dp(view, 92), minOf((rw * 0.52f).toInt(), (viewW * 0.56f).toInt()))
            tv.maxWidth = maxWidth
            tv.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(
                    maxWidth, android.view.View.MeasureSpec.AT_MOST
                ),
                android.view.View.MeasureSpec.makeMeasureSpec(
                    rh, android.view.View.MeasureSpec.AT_MOST
                )
            )
            val w = maxOf(dp(view, 64), tv.measuredWidth)
            val h = maxOf(dp(view, 17), tv.measuredHeight)
            // 竖直：优先贴在键盘区域顶部之上（相对根），否则贴根底部
            val kbTop = offsetY
            val top = if (kbTop - h - pad >= pad) {
                kbTop - h - pad
            } else {
                (rh - h - pad).coerceAtLeast(pad)
            }
            val left = (offsetX + (viewW - w) / 2).coerceAtLeast(pad)
            lp.leftMargin = left.coerceAtMost((rw - w - pad).coerceAtLeast(pad))
            lp.topMargin = top
            tv.layoutParams = lp
            tv.bringToFront()
        }.onFailure {
            tv.visibility = View.GONE
            LogUtils.w(TAG, "hint", "定位气泡失败：${it.javaClass.simpleName}")
        }
    }

    private fun hideHint(delayMs: Long) {
        val tv = hintViewRef.get() ?: return
        hintHideTask?.let { tv.removeCallbacks(it) }
        val task = Runnable {
            if (hintViewRef.get() === tv) tv.visibility = View.GONE
        }
        hintHideTask = task
        if (delayMs <= 0) {
            mainHandler.removeCallbacks(task)
            task.run()
        } else {
            mainHandler.postDelayed(task, delayMs)
        }
    }

    /** 彻底移除气泡（换 IME 实例 / 键盘重建时） */
    private fun clearHint() {
        hintHideTask = null
        mainHandler.removeCallbacksAndMessages(null)
        val tv = hintViewRef.get()
        if (tv != null) {
            runCatching {
                (tv.parent as? android.view.ViewGroup)?.removeView(tv)
            }
        }
        hintViewRef = WeakReference(null)
        hintRootRef = WeakReference(null)
    }

    // ================================================================ 生命周期

    /** 换 IME 实例：键盘视图与气泡全部失效 */
    fun onImeChanged() {
        tracker.clear()
        clearHint()
        cachedViewRef = WeakReference(null)
        labelViewRef = WeakReference(null)
        labelKeys = emptyMap()
        labelPaintSignature = Long.MIN_VALUE
        loggedOnce.clear()
    }

    /** 配置变化（设置页改动后无需重启，标签在下次绘制时自动重算） */
    fun onConfigMaybeChanged() {
        // 设置可能改过 → 标签画笔签名失效，迫使下次绘制重新算字号与配色
        labelPaintSignature = Long.MIN_VALUE
        loggedOnce.clear()
        keyboardViewRef.get()?.postInvalidate()
    }

    /** 重装时重置 */
    fun reset() {
        tracker.clear()
        clearHint()
        cachedViewRef = WeakReference(null)
        labelViewRef = WeakReference(null)
        labelKeys = emptyMap()
        labelPaintSignature = Long.MIN_VALUE
        loggedOnce.clear()
        mainHandler.removeCallbacksAndMessages(null)
    }

    // ================================================================ 工具

    /**
     * 统一的下发封装：`chain.proceed()` 后把返回值规整成非 null 的 Boolean。
     *
     * 兜成 `false` 是为了不让 `null` 传回调用方（调用方约定 `null` = 「我没 proceed 过，
     * 你 proceed」，语义会撞车；同一个 Chain 重复 proceed 行为未定义）。
     */
    private fun proceeded(chain: io.github.libxposed.api.XposedInterface.Chain): Boolean {
        val r = chain.proceed()
        return r as? Boolean ?: false
    }

    private fun dp(view: View, value: Int): Int =
        maxOf(1, Math.round(value * view.resources.displayMetrics.density))

    private inline fun logOnce(key: String, message: () -> String) {
        if (loggedOnce.add(key)) LogUtils.i(TAG, "gesture", message())
    }
}