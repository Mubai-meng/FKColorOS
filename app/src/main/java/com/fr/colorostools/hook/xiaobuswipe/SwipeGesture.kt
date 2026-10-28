package com.fr.colorostools.hook.xiaobuswipe

import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import com.fr.colorostools.utils.LogUtils
import java.lang.ref.WeakReference

/**
 * 按键下滑手势识别 + 按键标签绘制 + 触发提示气泡。
 *
 * ## 手势判定（移植自参考模块 `MainHook#interceptKeyboardTouch`）
 *
 * 单指按住某个已绑定的键往下滑，超过阈值即触发；触发时给原触摸序列补一个 `ACTION_CANCEL`，
 * 让宿主键盘认为这次点击被取消（否则松手时会照常上屏一个字符）。
 *
 * 判定细节：
 * - **DOWN 时**：命中键 + 该键有绑定 ⇒ 记录起点与阈值；`chain.proceed()` 照常下发（键盘要画按压态）。
 * - **MOVE 时**：纵向位移达到阈值且横向位移不超过容差 ⇒ 触发：
 *   1. 记录 triggered（后续 MOVE/UP 直接吞掉返回 true，不再下发）；
 *   2. `proceedWithCancel`：用 ACTION_CANCEL 副本走一次原方法，清掉宿主的按压状态；
 *   3. 触感反馈（可关）+ 执行动作 + 显示提示气泡。
 * - **UP/CANCEL**：清状态、隐藏气泡。
 * - 多指触摸一律直通（双指输入、手写等不参与）。
 *
 * ## 横向容差
 * 26 键：`max(阈值×2.5, 纵向位移×1.2)`；九宫格：`max(阈值×0.9, 纵向位移×0.75)`。
 * 九宫格键更窄更高、下滑操作更"竖"，容差相应收紧（与参考模块一致）。
 *
 * ## 已触发后吞事件的意义
 * 触发瞬间已经给宿主补发了 CANCEL，宿主不会再收到后续事件；若我们仍把 MOVE 下发，
 * 宿主的按键状态机可能重新走「按下→抬起」而上屏字符。所以 triggered 期间必须吞掉。
 */
internal object SwipeGesture {

    private const val TAG = SwipeConst.TAG

    /** 提示气泡预览阈值：滑到 `阈值×0.8` 就先显示"当前键 下滑：动作"（未触发状态） */
    private const val HINT_PREVIEW_RATIO = 0.8f

    /** 触发提示气泡停留时长 */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 跟踪器状态（单指手势，一个视图一份） */
    private class Tracker {
        var active = false
        var viewRef: WeakReference<View> = WeakReference(null)
        var downX = 0f
        var downY = 0f
        var thresholdPx = 1f
        var key: String? = null
        var action = SwipeConst.A_NONE
        var t9 = false
        var triggered = false

        fun begin(v: View, x: Float, y: Float) {
            viewRef = WeakReference(v)
            downX = x
            downY = y
            thresholdPx = 1f
            key = null
            action = SwipeConst.A_NONE
            t9 = false
            triggered = false
            active = true
        }

        fun matches(v: View): Boolean = active && viewRef.get() === v

        fun clear() {
            active = false
            key = null
            action = SwipeConst.A_NONE
            t9 = false
            triggered = false
        }
    }

    private val tracker = Tracker()

    /** 当前键盘视图（提示气泡挂它的窗口根） */
    private var keyboardViewRef: WeakReference<View> = WeakReference(null)

    /** 提示气泡 */
    private var hintViewRef: WeakReference<android.widget.TextView> = WeakReference(null)
    private var hintRootRef: WeakReference<View> = WeakReference(null)
    private var hintHideTask: Runnable? = null

    /** 键盘类型缓存（视图 → 是否九宫格 / 是否手写），键盘模式切换时靠类名或模型判断，这里按视图缓存 */
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

    /** 一次性日志标记（触发键、标签绘制等只在首occurence 记录，避免刷屏） */
    private val loggedOnce = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // ================================================================ 手势入口

    /**
     * 触摸事件处理（挂在 `View#dispatchTouchEvent` 上）。
     *
     * 返回值语义**严格二态**：
     * - `null` = 本次完全不接管，调用方必须自己 `chain.proceed()`（本方法内部**没有**调用过 proceed）；
     * - 非 null = 最终返回值，调用方原样返回（本方法内部已按需调用过 proceed）。
     *
     * 内部所有「已调用 proceed」的路径统一走 [proceeded]，把返回值规整成非 null 的 Boolean
     * （`dispatchTouchEvent` 恒返回 boolean，理论上不会为 null；万一为 null 按 `false` 处理，
     * 绝不回到 null 让调用方二次 proceed —— 同一个 Chain 重复 proceed 行为未定义）。
     */
    fun onTouch(chain: io.github.libxposed.api.XposedInterface.Chain, view: View, event: MotionEvent): Boolean? {
        val config = SwipeConfig.load()
        if (!config.enabled || !config.hasAnyBinding) {
            // 功能关闭 / 无任何绑定：清掉残留状态后直通（零开销路径）
            if (tracker.active) tracker.clear()
            // 诊断日志（一次性）：这条路径以前完全静默，导致「开关开了但下滑没反应」
            // 无法从日志区分是「钩子没装」还是「一个键都没绑定」。key 里带上两个状态位，
            // 状态变化时会重新打一条，不刷屏。
            logOnce("skip:${config.enabled}:${config.hasAnyBinding}") {
                if (!config.enabled) {
                    "手势直通：下滑开关未开启"
                } else {
                    "手势直通：所有键均未绑定动作（26 键 ${config.bindingCount()} 个 / 九宫格 ${config.t9BindingCount()} 个）"
                }
            }
            return null
        }

        val actionMasked = event.actionMasked
        val pointerCount = event.pointerCount

        // 手写键盘：不参与下滑（笔迹会与手势冲突），顺手把气泡收掉
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

        // 非 DOWN 且既无绑定也无跟踪状态：直通（快速滑动等会让 MOVE 先于 DOWN 到达）
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
            }
            return true
        }

        if ((actionMasked == MotionEvent.ACTION_MOVE || actionMasked == MotionEvent.ACTION_UP) &&
            tracker.action != SwipeConst.A_NONE
        ) {
            return onMove(chain, view, event, actionMasked)
        }

        // 其余情况：下发并处理 up/cancel 收尾
        val r = proceeded(chain)
        if (actionMasked == MotionEvent.ACTION_UP || actionMasked == MotionEvent.ACTION_CANCEL) {
            tracker.clear()
            hideHint(SwipeConst.HINT_HIDE_AFTER_UP_MS)
        }
        return r
    }

    /** DOWN：命中键 → 记录绑定与阈值；无绑定按键直接清掉跟踪 */
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
        val action = if (hit == null) SwipeConst.A_NONE else actionOf(config, hit)
        val thresholdDp = if (state.t9) config.t9ThresholdDp else config.thresholdDp
        var thresholdPx = dp(view, thresholdDp).toFloat()
        // 阈值不能超过键盘高度一半（否则永远滑不到）
        val h = view.height
        if (h > 0) thresholdPx = minOf(thresholdPx, h * 0.5f)

        tracker.key = hit?.key
        tracker.action = action
        tracker.t9 = state.t9
        tracker.thresholdPx = thresholdPx.coerceAtLeast(1f)

        if (action == SwipeConst.A_NONE) {
            tracker.clear()
        } else {
            logOnce("bind:${hit?.key}") {
                "按键 ${hit?.key}（t9=${state.t9}）绑定动作 ${config.actionName(action)}，" +
                    "阈值 ${thresholdDp}dp / ${thresholdPx.toInt()}px，键盘高 $h"
            }
            // 标签可能因键盘重排而失效，先失效一次
            invalidateLabelCacheIfNeeded(view)
        }
        return passed
    }

    /** MOVE / UP：达到阈值即触发 */
    private fun onMove(
        chain: io.github.libxposed.api.XposedInterface.Chain,
        view: View,
        event: MotionEvent,
        actionMasked: Int,
    ): Boolean? {
        val dx = event.x - tracker.downX
        val dy = event.y - tracker.downY
        val threshold = tracker.thresholdPx
        val tolerance = if (tracker.t9) {
            maxOf(threshold * 0.9f, 0.75f * dy)
        } else {
            maxOf(threshold * 2.5f, 1.2f * dy)
        }

        if (actionMasked == MotionEvent.ACTION_MOVE) {
            if (dy >= threshold * HINT_PREVIEW_RATIO && dy < threshold && kotlin.math.abs(dx) <= tolerance) {
                // 预览：还没到触发线，先告诉用户松手会执行什么
                val key = tracker.key
                val act = tracker.action
                view.post { showHint(view, key, act, triggered = false) }
            } else if (dy < threshold * HINT_PREVIEW_RATIO) {
                view.post { hideHint(0) }
            }
        }

        if (dy >= threshold && kotlin.math.abs(dx) <= tolerance) {
            return trigger(chain, view, event, actionMasked)
        }

        val r = proceeded(chain)
        if (actionMasked == MotionEvent.ACTION_UP) {
            tracker.clear()
            view.post { hideHint(SwipeConst.HINT_HIDE_AFTER_UP_MS) }
        }
        return r
    }

    /** 触发：补发 CANCEL → 触感 → 动作 → 提示 */
    private fun trigger(
        chain: io.github.libxposed.api.XposedInterface.Chain,
        view: View,
        event: MotionEvent,
        actionMasked: Int,
    ): Boolean {
        val action = tracker.action
        val key = tracker.key ?: ""
        val t9 = tracker.t9
        tracker.triggered = true

        proceededCancel(chain, event)

        val config = SwipeConfig.load()
        if (config.vibration) {
            runCatching { view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS) }
        }

        view.post { showHint(view, key, action, triggered = true) }
        view.post {
            runCatching {
                EditorActions.perform(view, key, t9, action, config)
            }.onFailure {
                LogUtils.e(TAG, "trigger", "动作执行异常：${it.javaClass.simpleName}: ${it.message}")
            }
        }

        if (actionMasked == MotionEvent.ACTION_UP) tracker.clear()
        return true
    }

    /**
     * 给宿主补发一个 `ACTION_CANCEL`。
     *
     * 不能直接 `chain.proceed()`（那会把这次滑动当成点击上屏），也不能只 `return true`（宿主
     * 的按压态不会被清除，可能出现整键残留高亮）。用副本事件走一次原方法再回收，是唯一干净的做法。
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

    /** 键盘运行态（九宫格 / 手写），按视图缓存 —— 键盘模式切换时视图类或软键列表会变 */
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

    /** 某键的绑定动作（自定义文本优先已在 SwipeConfig 归一化） */
    private fun actionOf(config: SwipeConfig, hit: KeyGeometry.Hit): Int {
        return if (hit.t9) {
            config.actionForT9(hit.key.toIntOrNull() ?: 0)
        } else {
            config.actionForQwerty(hit.key.firstOrNull() ?: ' ')
        }
    }

    // ================================================================ 按键标签绘制

    /**
     * 在键盘视图上绘制各键的绑定短标签（`onDraw` 后置调用）。
     *
     * 参考模块用「软键几何中心 + 底部内缩」定位标签：标签画在键面右下偏上，避开键字母本身。
     * 颜色按深浅色切换（夜间浅字+深影，日间深字+浅影），保证在任何键底色上可读。
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
            val action = if (hit.t9) {
                config.actionForT9(key.toIntOrNull() ?: 0)
            } else {
                config.actionForQwerty(key.firstOrNull() ?: ' ')
            }
            // 标签三态解析（自动=动作短名 / 自定义文字 / 隐藏）统一交给 SwipeConfig，
            // 空串即「不绘制」：既覆盖「未绑定且未设标签」，也覆盖用户显式选的「隐藏标签」。
            // ⚠️ 必须先解析再判空：未绑定但设了自定义标签的键**仍然要画**（参考模块同行为）。
            val text = if (hit.t9) {
                config.labelForT9(key.toIntOrNull() ?: 0, action)
            } else {
                config.labelForQwerty(key.firstOrNull() ?: ' ', action)
            }
            if (text.isEmpty()) continue
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
        if (labelViewRef.get() === view && labelWidth == view.width && labelHeight == view.height) return
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
            // 夜间：浅字 + 深影（深色键底上可读）
            labelPaint.color = 0xEEF5F5F5.toInt()
            labelPaint.setShadowLayer(maxOf(1f, density), 0f, 0f, 0xD9000000.toInt())
        } else {
            // 日间：深字 + 浅影（浅色键底上可读）
            labelPaint.color = 0xC4555A62.toInt()
            labelPaint.setShadowLayer(maxOf(1f, density), 0f, 0f, 0xE7FFFFFF.toInt())
        }
    }

    private fun drawLabel(canvas: Canvas, text: String, hit: KeyGeometry.Hit, view: View) {
        if (text.isEmpty()) return
        val base = labelPaint.textSize
        // 长标签缩字，避免压到相邻键
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
     * 显示触发提示。
     *
     * 气泡挂在键盘所在窗口的根 FrameLayout 上（绝对定位，不参与布局测量），
     * 位置贴在按下的键上方，避免遮挡键帽。
     */
    private fun showHint(view: View, key: String?, action: Int, triggered: Boolean) {
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
        val text = when {
            triggered && action == SwipeConst.A_DISABLE -> "✓ 已禁用下滑"
            triggered -> "✓ ${config.actionName(action)}"
            else -> "${key?.uppercase() ?: "当前键"} 下滑：${config.actionName(action)}"
        }
        tv.text = text
        positionHint(view, tv)
        tv.alpha = 1f
        tv.visibility = View.VISIBLE
        if (triggered) hideHint(SwipeConst.HINT_HIDE_DELAY_MS)
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
     * `View#dispatchTouchEvent` 声明返回 boolean，`proceed()` 理论上不会给 null；
     * 但 Chain 的实现可能包装过返回值。这里兜成 `false`，是为了不让 `null` 传回调用方
     * （调用方约定 `null` = 「我没 proceed 过，你 proceed」，语义会撞车）。
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