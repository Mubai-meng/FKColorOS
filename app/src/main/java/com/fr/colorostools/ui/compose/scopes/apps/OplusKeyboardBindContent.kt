package com.fr.colorostools.ui.compose.scopes.apps

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fr.colorostools.R
import com.fr.colorostools.hook.xiaobuswipe.SwipeConst
import com.fr.colorostools.ui.compose.components.LocalScopeTopInset
import com.fr.colorostools.ui.compose.components.material.LocalShellBottomInset
import com.fr.colorostools.ui.compose.scopes.ScopeContentScope
import com.fr.colorostools.utils.PrefState
import com.fr.colorostools.utils.showToast

/**
 * 逐键绑定页（v2.0.0 四方向模型）—— 一个页面同时承载「主键网格」与「其它按键」：
 *
 * | 入口 | 主键网格 | 其它按键 |
 * |---|---|---|
 * | `OplusKeyboardBindContent(isQwerty = true)` | 26 键字母 a–z | QWERTY 形态可见的特殊键 |
 * | `OplusKeyboardBindContent(isQwerty = false)` | 九宫格 2–9 | 九宫格形态可见的特殊键 |
 *
 * 早期移植版把特殊键单独做成两个子页（`oplus_keyboard_special_qwerty` / `_special_t9`），
 * 同一宿主在功能树里出现四个入口，而「其它按键」与字母原本属于同一件事，改一次要来回跳页。
 * 对齐上游 v2.0.0 后合并：**一个键位页 = 该键盘形态下的全部可绑定键**。
 *
 * ## 交互（对齐上游 `app.xiaobuswipe` v2.0.0 的 `MainActivity`）
 * 1. **点击按键** → 先弹「选择滑动方向」（上/下/左/右 + 每方向当前绑定）
 * 2. 选完方向 → 弹「选择动作」；选到「输入指定内容」再填文本
 * 3. **长按方向项** → 单独设置该方向的触发距离（0 = 跟随全局）
 * 4. **长按按键** → 设置键面标签（自动 / 自定义 / 隐藏）；特殊键不支持（键面图标是宿主的）
 *
 * ## 草稿 + 保存（不是「改一格立刻生效」）
 * 页头文案承诺「改完点底部保存」。这不只是观感：本页有「清空 26 键」这类批量破坏性操作，
 * 逐格即时写入时误触一次不可撤销；攒成草稿后，未保存前怎么改都不影响线上配置。
 *
 * ## 写值路径（必须走 [ScopeContentScope.state]，不是 sendValue）
 * hook 侧读的是 remote prefs `SettingsPrefs`（见 `HookEnv.PREFS_SETTINGS`），
 * 而 `sendPrefsValue` 写的是 `ModulePrefs` —— **两个不同的 prefs 文件**。
 * 所以保存必须逐键逐方向调 `state.set(key, value)` 落到 `SettingsPrefs`。
 *
 * ## 方向后缀 = 旧配置零成本迁移的唯一契约
 * 下滑方向存储键**不带后缀**（[SwipeConst.DIR_SUFFIX]`[0] == ""`），上/左/右加
 * `@up`/`@left`/`@right`。所以 v1.x 写下的 `xiaobu_swipe_action_q_z` 读出来天然就是
 * 「z 键下滑」，老用户升级后既有绑定原样保留在下滑方向，无需任何迁移代码。
 *
 * ## 默认绑定回落（26 键 z/x/c/v）
 * prefs 里该键该方向**从未写入**（空串）时必须回落到 [SwipeConst.DEFAULT_QWERTY]
 * ——与 hook 侧 `SwipeConfig.load()` 的规则严格一致，否则会出现「界面显示 z=全选、
 * 实际 hook 认为全未绑定、整条手势链路静默直通」的假象。只作用于**下滑**方向。
 */
@Composable
internal fun OplusKeyboardBindContent(
    scope: ScopeContentScope,
    isQwerty: Boolean,
) {
    val ctx = scope.context
    val state = scope.state

    // 主键：26 键排成真实三行（10/9/7）、九宫格 3x3（1 不可设）
    val primaryKeys: List<String> = remember(isQwerty) { buildPrimaryKeys(isQwerty) }
    // 其它按键：该键盘形态下可见的特殊键，2 列卡片渲染
    val specialKeys: List<String> = remember(isQwerty) {
        SwipeConst.SPECIAL_KEYS.filter {
            if (isQwerty) SwipeConst.specialVisibleInQwerty(it) else SwipeConst.specialVisibleInT9(it)
        }
    }
    // 草稿/保存覆盖全部可绑定键；两个子列表只决定分区渲染
    val keys: List<String> = remember(primaryKeys, specialKeys) { primaryKeys + specialKeys }

    // ---------------- 草稿状态 ----------------
    // remember(keys)：只在首次进入（或页面被回收重建）时从 prefs 快照一次，
    // 之后的编辑都落在 draft 里，点「保存并应用」才写回 prefs。

    val draft: BindDraft = remember(keys) { buildDraft(state, keys, isQwerty) }

    // 保存后的基线：用于「有无未保存修改」提示
    var baseline by remember { mutableStateOf(snapshotOf(draft, keys)) }
    val dirty = snapshotOf(draft, keys) != baseline

    // 对话框状态（每个对话框 `key to dir` 记录目标，null = 不显示）
    var dirTarget by remember { mutableStateOf<String?>(null) }
    var actionTarget by remember { mutableStateOf<DirRef?>(null) }
    var insertTarget by remember { mutableStateOf<DirRef?>(null) }
    var distTarget by remember { mutableStateOf<DirRef?>(null) }
    var labelTarget by remember { mutableStateOf<String?>(null) }
    var customLabelTarget by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    fun setAction(k: String, dir: Int, id: Int) {
        val cur = draft.actions[k] ?: IntArray(SwipeConst.DIR_COUNT)
        draft.actions[k] = cur.copyOf().also { it[dir] = id }
    }

    fun setText(k: String, dir: Int, text: String) {
        val cur = draft.texts[k] ?: Array(SwipeConst.DIR_COUNT) { "" }
        draft.texts[k] = cur.copyOf().also { it[dir] = text }
    }

    fun setDist(k: String, dir: Int, dp: Int) {
        val cur = draft.dists[k] ?: IntArray(SwipeConst.DIR_COUNT)
        draft.dists[k] = cur.copyOf().also { it[dir] = dp }
    }

    fun actionsOf(k: String): IntArray = draft.actions[k] ?: IntArray(SwipeConst.DIR_COUNT)

    /** 把整份草稿写回 prefs（唯一落盘点） */
    fun saveAll() {
        // 只写与 prefs 现值不一致的键：PrefState.set 每次都 commit + bump（remote prefs 走 Binder），
        // (26+13) × 4 方向 × 3 类键 ≈ 数百次全量写会让本页反复重组并明显卡顿。
        for (k in keys) {
            val acts = draft.actions[k] ?: continue
            val txts = draft.texts[k]
            val dists = draft.dists[k]

            for (dir in 0 until SwipeConst.DIR_COUNT) {
                val ak = actionKeyOf(k, isQwerty, dir)
                val rawAction = state.getString(ak, "") ?: ""
                val target = acts[dir]
                val fallback = fallbackOf(k, isQwerty, dir)
                val textValue = txts?.get(dir) ?: ""

                // 未显式设置过的 26 键下滑方向（z/x/c/v）保持「不写」：
                // 这样 DEFAULT_QWERTY 仍是唯一真相源，将来默认值调整还能继续生效。
                // 反之要写 —— 包括用户显式选了「未绑定」（写 "0" 覆盖掉默认回落）。
                val skip = rawAction.isEmpty() && target == fallback && textValue.isEmpty()
                if (!skip && target.toString() != rawAction) state.set(ak, target.toString())

                val tk = textKeyOf(k, isQwerty, dir)
                // 文本也一并写：即使当前动作不是「输入指定内容」，残留文本会让 hook 侧
                // 把该方向强行解释成 insert-text（那边文本优先），所以清空必须显式写空串。
                if (textValue != (state.getString(tk, "") ?: "")) state.set(tk, textValue)

                val dk = distanceKeyOf(k, isQwerty, dir)
                val distValue = dists?.get(dir) ?: 0
                if (distValue != state.getInt(dk, 0)) state.set(dk, distValue)
            }

            // 标签只属于主键（特殊键的键面图标由宿主绘制，没有标签可改）
            if (!SwipeConst.isSpecial(k)) {
                val lk = labelKeyOf(k, isQwerty)
                val targetLabel = draft.labels[k] ?: ""
                if (targetLabel != (state.getString(lk, "") ?: "")) state.set(lk, targetLabel)
            }
        }
        baseline = snapshotOf(draft, keys)
        ctx.showToast(ctx.getString(R.string.oplus_keyboard_saved))
    }

    /**
     * 恢复默认：26 键下滑回到 Z/X/C/V（其余方向/距离/文本/标签一并复位）。
     *
     * 只动**主键网格** —— 上游 `restoreQwertyDefaults` 同样不碰特殊键，
     * 那些键一旦被绑过就带「屏蔽原生手势」的副作用，不应被一键复位悄悄改掉。
     */
    fun resetDefault() {
        for (k in primaryKeys) {
            val arr = IntArray(SwipeConst.DIR_COUNT)
            arr[SwipeConst.DIR_DOWN] = if (isQwerty) {
                SwipeConst.DEFAULT_QWERTY[k.firstOrNull()?.lowercaseChar() ?: ' '] ?: SwipeConst.A_NONE
            } else {
                SwipeConst.A_NONE
            }
            draft.actions[k] = arr
            draft.texts[k] = Array(SwipeConst.DIR_COUNT) { "" }
            draft.dists[k] = IntArray(SwipeConst.DIR_COUNT)
            draft.labels[k] = ""
        }
        ctx.showToast(ctx.getString(R.string.oplus_keyboard_reset_done))
    }

    /** 清空本页主键网格全部映射（4 方向 × 动作/文本/距离，外加标签） */
    fun clearAll() {
        for (k in primaryKeys) {
            draft.actions[k] = IntArray(SwipeConst.DIR_COUNT)
            draft.texts[k] = Array(SwipeConst.DIR_COUNT) { "" }
            draft.dists[k] = IntArray(SwipeConst.DIR_COUNT)
            draft.labels[k] = ""
        }
        ctx.showToast(ctx.getString(R.string.oplus_keyboard_clear_done))
    }

    /** 解除某键 4 个方向的全部绑定与距离（不动标签：标签是显示偏好，不是绑定） */
    fun clearKey(k: String) {
        draft.actions[k] = IntArray(SwipeConst.DIR_COUNT)
        draft.texts[k] = Array(SwipeConst.DIR_COUNT) { "" }
        draft.dists[k] = IntArray(SwipeConst.DIR_COUNT)
        ctx.showToast(ctx.getString(R.string.oplus_keyboard_cleared_key, displayName(ctx, k, isQwerty)))
    }

    // ---------------- 布局：内容可滚动 + 底栏固定 ----------------
    Column(modifier = Modifier.fillMaxSize()) {
        // 内容区：吃剩余高度，内部滚动（底栏因此恒在屏底，不随内容滚走）
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(
                // Miuix 线要把内容顶到模糊顶栏之下；material 线该值为 0
                top = LocalScopeTopInset.current + 8.dp,
                start = 12.dp,
                end = 12.dp,
                bottom = 8.dp,
            ),
        ) {
            item(key = "header") {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                    Text(
                        text = ctx.getString(
                            if (isQwerty) {
                                R.string.oplus_keyboard_bind_title_qwerty
                            } else {
                                R.string.oplus_keyboard_bind_title_t9
                            }
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = ctx.getString(
                            if (isQwerty) {
                                R.string.oplus_keyboard_bind_intro_qwerty
                            } else {
                                R.string.oplus_keyboard_bind_intro_t9
                            }
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }

            item(key = "grid") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        (if (isQwerty) QWERTY_GRID else T9_GRID).forEach { rowKeys ->
                            Row(modifier = Modifier.fillMaxWidth()) {
                                rowKeys.forEach { k ->
                                    Box(modifier = Modifier.weight(1f)) {
                                        if (k in T9_LOCKED) {
                                            // 九宫格的 1：宿主上它没有可绑定的手势，显示为灰格
                                            LockedKeyCell(label = k)
                                        } else {
                                            val acts = actionsOf(k)
                                            val pd = primaryDirOf(acts)
                                            val actionId = if (pd >= 0) {
                                                acts[pd]
                                            } else {
                                                SwipeConst.A_NONE
                                            }
                                            KeyCell(
                                                label = k,
                                                letters = if (isQwerty) "" else T9_LETTERS[k] ?: "",
                                                sub = cellLabel(
                                                    labelRaw = draft.labels[k] ?: "",
                                                    actionId = actionId,
                                                    unboundPlaceholder = "—",
                                                ),
                                                bound = acts.any {
                                                    it != SwipeConst.A_NONE &&
                                                        it != SwipeConst.A_DISABLE
                                                },
                                                hiddenLabel = (draft.labels[k] ?: "") ==
                                                    SwipeConst.LABEL_HIDDEN,
                                                onClick = { dirTarget = k },
                                                onLongClick = { labelTarget = k },
                                            )
                                        }
                                    }
                                }
                                // 补齐到 10 列，让短行的键宽与长行一致（真实键盘的错位排布）
                                if (isQwerty) {
                                    repeat(10 - rowKeys.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // 其它按键：与主键网格同页（对齐上游 `buildSpecialKeyCard`），2 列卡片
            item(key = "special") {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                    Text(
                        text = ctx.getString(
                            if (isQwerty) {
                                R.string.oplus_keyboard_special_title_qwerty
                            } else {
                                R.string.oplus_keyboard_special_title_t9
                            }
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = ctx.getString(
                            if (isQwerty) {
                                R.string.oplus_keyboard_special_intro_qwerty
                            } else {
                                R.string.oplus_keyboard_special_intro_t9
                            }
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            specialKeys.chunked(2).forEach { rowKeys ->
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    rowKeys.forEach { k ->
                                        Box(modifier = Modifier.weight(1f)) {
                                            val acts = actionsOf(k)
                                            val summary = specialSummary(acts)
                                            SpecialKeyCell(
                                                glyph = specialGlyph(k),
                                                name = SwipeConst.specialLabel(k),
                                                summary = if (summary.isEmpty()) {
                                                    ctx.getString(R.string.oplus_keyboard_special_unbound)
                                                } else {
                                                    summary
                                                },
                                                bound = acts.any {
                                                    it != SwipeConst.A_NONE && it != SwipeConst.A_DISABLE
                                                },
                                                disabledOnly = acts.any { it == SwipeConst.A_DISABLE } &&
                                                    acts.none {
                                                        it != SwipeConst.A_NONE &&
                                                            it != SwipeConst.A_DISABLE
                                                    },
                                                onClick = { dirTarget = k },
                                            )
                                        }
                                    }
                                    // 奇数个键时补一个空位，让卡片宽度保持一致
                                    if (rowKeys.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }

            // 批量操作：独占一张卡（破坏性操作，视觉上与逐键编辑分开）
            item(key = "bulk") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column {
                        if (isQwerty) {
                            Row(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                                TextButton(
                                    onClick = { resetDefault() },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(ctx.getString(R.string.oplus_keyboard_reset_default))
                                }
                                TextButton(
                                    onClick = { confirmClear = true },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error,
                                    ),
                                ) {
                                    Text(ctx.getString(R.string.oplus_keyboard_clear_qwerty))
                                }
                            }
                        } else {
                            TextButton(
                                onClick = { confirmClear = true },
                                modifier = Modifier.fillMaxWidth().padding(8.dp),
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error,
                                ),
                            ) {
                                Text(ctx.getString(R.string.oplus_keyboard_clear_t9))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        // ---------------- 固定底栏 ----------------
        // 必须在底部留出宿主悬浮胶囊占位：Miuix 骨架的 contentWindowInsets 只含水平方向、
        // 已消费导航栏 inset，胶囊是浮在内容之上的（LocalBottomBarPresent=true ⇒ materialBottomInset
        // 的 navInset 归零，只剩 LocalShellBottomInset 这一份）。不留这份 padding，底栏会被胶囊压住。
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = LocalShellBottomInset.current),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Button(
                        onClick = { saveAll() },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(24.dp),
                    ) {
                        Text(ctx.getString(R.string.oplus_keyboard_save_apply))
                    }
                    if (dirty) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = ctx.getString(R.string.oplus_keyboard_unsaved),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }

    // ---------------- 对话框 1：选择滑动方向 ----------------
    val dirK = dirTarget
    if (dirK != null) {
        val acts = actionsOf(dirK)
        val globalDp = globalThresholdDp(state, isQwerty)
        DirectionPickerDialog(
            title = ctx.getString(
                R.string.oplus_keyboard_dir_title,
                displayName(ctx, dirK, isQwerty),
            ),
            items = SwipeConst.DIR_DISPLAY_ORDER.map { dir ->
                val a = acts[dir]
                val rawDist = draft.dists[dirK]?.get(dir) ?: 0
                val name = if (a == SwipeConst.A_NONE) {
                    actionName(SwipeConst.A_NONE)
                } else {
                    SwipeConst.ACTION_SHORT[a] ?: ""
                }
                DirItem(
                    dir = dir,
                    text = buildString {
                        append(SwipeConst.DIR_SHORT[dir]).append(' ')
                        append(SwipeConst.DIR_NAMES[dir]).append(" · ")
                        append(name)
                        if (rawDist > 0) append(" · ").append(rawDist).append("dp")
                    },
                    bound = a != SwipeConst.A_NONE,
                )
            },
            globalDp = globalDp,
            onPick = { dir ->
                dirTarget = null
                actionTarget = DirRef(dirK, dir)
            },
            onLongPick = { dir ->
                // 长按方向项 = 单独设置该方向触发距离（上游同交互）
                dirTarget = null
                distTarget = DirRef(dirK, dir)
            },
            onUnbind = {
                dirTarget = null
                clearKey(dirK)
            },
            onDismiss = { dirTarget = null },
        )
    }

    // ---------------- 对话框 2：选择动作 ----------------
    val actTarget = actionTarget
    if (actTarget != null) {
        val selected = actionsOf(actTarget.key).get(actTarget.dir)
        ActionPickerDialog(
            title = ctx.getString(
                R.string.oplus_keyboard_dialog_action_title,
                displayName(ctx, actTarget.key, isQwerty),
                SwipeConst.DIR_NAMES[actTarget.dir],
            ),
            selected = selected,
            onPick = { id ->
                setAction(actTarget.key, actTarget.dir, id)
                actionTarget = null
                if (id == SwipeConst.A_INSERT_TEXT) {
                    insertTarget = actTarget
                } else {
                    // 选成非「输入指定内容」时清掉残留文本：
                    // 否则 hook 侧文本优先会把刚选的动作覆盖掉
                    setText(actTarget.key, actTarget.dir, "")
                }
            },
            onDismiss = { actionTarget = null },
        )
    }

    // ---------------- 对话框 3：输入指定内容 ----------------
    val insTarget = insertTarget
    if (insTarget != null) {
        TextEditorDialog(
            title = ctx.getString(
                R.string.oplus_keyboard_dialog_text_title,
                displayName(ctx, insTarget.key, isQwerty),
                SwipeConst.DIR_NAMES[insTarget.dir],
            ),
            help = null,
            hint = ctx.getString(R.string.oplus_keyboard_dialog_text_hint),
            value = draft.texts[insTarget.key]?.get(insTarget.dir) ?: "",
            maxCodePoints = SwipeConst.MAX_INSERT_CODEPOINTS,
            maxLines = 8,
            onConfirm = { text ->
                val norm = normalizeByCodePoints(text, SwipeConst.MAX_INSERT_CODEPOINTS)
                if (norm.isEmpty()) {
                    // 上游此处直接拒绝：「输入内容不能为空」
                    ctx.showToast(ctx.getString(R.string.oplus_keyboard_insert_empty))
                } else {
                    setText(insTarget.key, insTarget.dir, norm)
                    setAction(insTarget.key, insTarget.dir, SwipeConst.A_INSERT_TEXT)
                    insertTarget = null
                }
            },
            onDismiss = {
                // 没填文本就退出 ⇒ 该方向回落未绑定，避免留下「选了插入文本却无内容」的空壳
                if ((draft.texts[insTarget.key]?.get(insTarget.dir) ?: "").isEmpty()) {
                    setAction(insTarget.key, insTarget.dir, SwipeConst.A_NONE)
                }
                insertTarget = null
            },
        )
    }

    // ---------------- 对话框 4：按方向设触发距离 ----------------
    val distRef = distTarget
    if (distRef != null) {
        val cur = draft.dists[distRef.key]?.get(distRef.dir) ?: 0
        DirectionDistanceDialog(
            title = ctx.getString(
                R.string.oplus_keyboard_dir_distance_title,
                displayName(ctx, distRef.key, isQwerty),
                SwipeConst.DIR_NAMES[distRef.dir],
            ),
            initial = cur,
            globalDp = globalThresholdDp(state, isQwerty),
            minDp = if (isQwerty) SwipeConst.MIN_THRESHOLD_DP else SwipeConst.MIN_T9_THRESHOLD_DP,
            maxDp = if (isQwerty) SwipeConst.MAX_THRESHOLD_DP else SwipeConst.MAX_T9_THRESHOLD_DP,
            onSave = { dp ->
                setDist(distRef.key, distRef.dir, dp)
                distTarget = null
                // 设完距离回到方向选择：用户多半还想接着选动作
                dirTarget = distRef.key
            },
            onUseGlobal = {
                setDist(distRef.key, distRef.dir, 0)
                distTarget = null
                dirTarget = distRef.key
            },
            onDismiss = {
                distTarget = null
                dirTarget = distRef.key
            },
        )
    }

    // ---------------- 对话框 5：键面标签三态（特殊键不支持） ----------------
    val labelT = labelTarget
    if (labelT != null && !SwipeConst.isSpecial(labelT)) {
        val raw = draft.labels[labelT] ?: ""
        val pd = primaryDirOf(actionsOf(labelT))
        val actionId = if (pd >= 0) actionsOf(labelT)[pd] else SwipeConst.A_NONE
        val autoShort = if (actionId == SwipeConst.A_NONE || actionId == SwipeConst.A_DISABLE) {
            ""
        } else {
            SwipeConst.ACTION_SHORT[actionId] ?: ""
        }
        val mode = when {
            raw == SwipeConst.LABEL_HIDDEN -> 2
            raw.isNotEmpty() -> 1
            else -> 0
        }
        val labelTitle = ctx.getString(
            // 上游：「A 键显示标签」/「2 / ABC 显示标签」（九宫格键名后多一个空格）
            if (isQwerty) {
                R.string.oplus_keyboard_label_dialog_title
            } else {
                R.string.oplus_keyboard_label_dialog_title_t9
            },
            labelTitleOf(ctx, labelT, isQwerty),
        )
        LabelPickerDialog(
            title = labelTitle,
            autoText = if (autoShort.isEmpty()) {
                ctx.getString(R.string.oplus_keyboard_label_auto_none)
            } else {
                ctx.getString(R.string.oplus_keyboard_label_auto, autoShort)
            },
            customText = if (mode == 1) {
                ctx.getString(R.string.oplus_keyboard_label_custom_current, raw)
            } else {
                ctx.getString(R.string.oplus_keyboard_label_custom)
            },
            hiddenText = ctx.getString(R.string.oplus_keyboard_label_hide),
            mode = mode,
            onPick = { picked ->
                labelTarget = null
                when (picked) {
                    0 -> draft.labels[labelT] = ""
                    2 -> draft.labels[labelT] = SwipeConst.LABEL_HIDDEN
                    else -> customLabelTarget = labelT
                }
            },
            onDismiss = { labelTarget = null },
        )
    }

    // ---------------- 对话框 6：自定义标签文字 ----------------
    val customT = customLabelTarget
    if (customT != null && !SwipeConst.isSpecial(customT)) {
        val cur = draft.labels[customT] ?: ""
        TextEditorDialog(
            title = ctx.getString(
                // 上游把「A 键显示标签」整串再拼「· 自定义」，逐字对齐
                R.string.oplus_keyboard_label_custom_title,
                ctx.getString(
                    if (isQwerty) {
                        R.string.oplus_keyboard_label_dialog_title
                    } else {
                        R.string.oplus_keyboard_label_dialog_title_t9
                    },
                    labelTitleOf(ctx, customT, isQwerty),
                ),
            ),
            help = null,
            hint = ctx.getString(R.string.oplus_keyboard_label_hint),
            value = if (cur == SwipeConst.LABEL_HIDDEN) "" else cur,
            maxCodePoints = SwipeConst.MAX_LABEL_CODEPOINTS,
            maxLines = 1,
            onConfirm = { text ->
                // 留空 = 恢复自动（上游同语义），不是「隐藏」
                draft.labels[customT] = normalizeLabelByCodePoints(text)
                customLabelTarget = null
            },
            onDismiss = { customLabelTarget = null },
        )
    }

    // ---------------- 对话框 7：清空确认 ----------------
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = {
                Text(
                    ctx.getString(
                        if (isQwerty) {
                            R.string.oplus_keyboard_clear_qwerty_title
                        } else {
                            R.string.oplus_keyboard_clear_t9_title
                        }
                    )
                )
            },
            text = {
                Text(
                    ctx.getString(
                        if (isQwerty) {
                            R.string.oplus_keyboard_clear_qwerty_confirm
                        } else {
                            R.string.oplus_keyboard_clear_t9_confirm
                        }
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    clearAll()
                }) { Text(ctx.getString(R.string.oplus_keyboard_clear_confirm_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(ctx.getString(android.R.string.cancel))
                }
            },
        )
    }
}

// ================================================================ 单元格

/**
 * 单个键格：主标签（字母/数字）+ 可选字母行（九宫格）+ 副标签（键面标签）。
 *
 * [sub] 已由 [cellLabel] 解析成最终要显示的文字，空串表示**不显示**（用户选了「隐藏标签」）。
 *
 * ⚠️ 副标签**不拼方向箭头**（`↓全选` 这种）：26 键一格的宽度只有约 35dp，
 * 拼上箭头必然被截断成「↓全…」，比不显示方向更糟。方向信息放在点击后的
 * 方向选择对话框里（上游注释同样理由）。
 */
@Composable
private fun KeyCell(
    label: String,
    letters: String,
    sub: String,
    bound: Boolean,
    hiddenLabel: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .padding(2.dp)
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(10.dp),
        color = if (bound) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = label.uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (bound) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (letters.isNotEmpty()) {
                    Text(
                        text = letters,
                        fontSize = 9.sp,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
                if (sub.isNotEmpty() || hiddenLabel) {
                    Text(
                        // 隐藏标签时给一个淡色小字标记，否则用户看不出「这格为什么没字」
                        text = if (hiddenLabel) {
                            LocalContext.current.getString(R.string.oplus_keyboard_label_hidden_mark)
                        } else {
                            sub
                        },
                        fontSize = 10.sp,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        color = if (bound) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                    )
                }
            }
        }
    }
}

/** 不可绑定的键格（九宫格的 `1`）：灰格、不可点击 */
@Composable
private fun LockedKeyCell(label: String) {
    Surface(
        modifier = Modifier
            .padding(2.dp)
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.4f),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                )
                Text(
                    // 上游这里写「普通键」：说明为什么这一格不能设置
                    text = LocalContext.current.getString(R.string.oplus_keyboard_t9_normal_key),
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                )
            }
        }
    }
}

/**
 * 特殊键卡片（2 列网格）：左侧 glyph、右侧名称 + 已绑定方向摘要。
 *
 * 与 26 键格的差别是**不显示动作短名，而是「方向 + 短名」的组合**——
 * 特殊键的原生手势被屏蔽这件事必须让用户看得见，一行摘要能塞下两个方向。
 */
@Composable
private fun SpecialKeyCell(
    glyph: String,
    name: String,
    summary: String,
    bound: Boolean,
    disabledOnly: Boolean,
    onClick: () -> Unit,
) {
    val fg = when {
        disabledOnly -> MaterialTheme.colorScheme.error
        bound -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = Modifier
            .padding(3.dp)
            .fillMaxWidth()
            .height(64.dp),
        shape = RoundedCornerShape(12.dp),
        color = when {
            disabledOnly -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
            bound -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
    ) {
        Box(
            modifier = Modifier.fillMaxSize().clickable(onClick = onClick),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = glyph,
                    // 文本型 glyph（换行/空格/符号/重输/拼英）比单个符号长，要缩字号才不挤
                    fontSize = if (glyph.length > 1 && !glyph.startsWith("@")) 12.sp else 19.sp,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    color = fg,
                    modifier = Modifier.width(34.dp),
                )
                Spacer(Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = name,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = fg,
                    )
                    Text(
                        text = summary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ================================================================ 对话框

/** 方向选择项（[text] 已拼好「↓ 下滑 · 全选 · 20dp」这类文案） */
private data class DirItem(val dir: Int, val text: String, val bound: Boolean)

/** 「某个键的某个方向」引用 */
private data class DirRef(val key: String, val dir: Int)

/**
 * 方向选择对话框（对齐上游 `showDirectionPicker`）。
 *
 * - 点击某方向 ⇒ 进入该方向的动作选择
 * - **长按某方向** ⇒ 单独设置该方向触发距离
 * - 底部「解除绑定」⇒ 一次性清掉该键 4 个方向的动作/文本/距离
 */
@Composable
private fun DirectionPickerDialog(
    title: String,
    items: List<DirItem>,
    globalDp: Int,
    onPick: (Int) -> Unit,
    onLongPick: (Int) -> Unit,
    onUnbind: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                items.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { onPick(item.dir) },
                                onLongClick = { onLongPick(item.dir) },
                            )
                            .padding(vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = item.text,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (item.bound) FontWeight.Medium else FontWeight.Normal,
                            color = if (item.bound) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = onUnbind,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(ctx.getString(R.string.oplus_keyboard_dir_clear))
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(ctx.getString(android.R.string.cancel))
            }
        },
    )
}

/**
 * 动作单选对话框（对齐上游 `showActionPickerDialog`）：带搜索框 + 双列网格。
 *
 * 30 个动作在单列里要滚很久，双列 + 搜索能把「找粘贴」压缩到两次点击以内。
 */
@Composable
private fun ActionPickerDialog(
    title: String,
    selected: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var picked by remember { mutableStateOf(selected) }
    var query by remember { mutableStateOf("") }
    val ctx = LocalContext.current

    val ids = remember(query) {
        if (query.isBlank()) {
            SwipeConst.ACTION_IDS.toList()
        } else {
            SwipeConst.ACTION_IDS.filter { actionName(it).contains(query.trim(), ignoreCase = true) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(ctx.getString(R.string.oplus_keyboard_search_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                if (ids.isEmpty()) {
                    Text(
                        text = ctx.getString(R.string.oplus_keyboard_search_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                        items(ids.chunked(2), key = { row -> row.joinToString(",") }) { row ->
                            Row(modifier = Modifier.fillMaxWidth()) {
                                row.forEach { id ->
                                    Box(modifier = Modifier.weight(1f)) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { picked = id }
                                                .padding(vertical = 6.dp, horizontal = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            RadioButton(
                                                selected = picked == id,
                                                onClick = { picked = id },
                                            )
                                            Text(
                                                text = actionName(id),
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = if (picked == id) {
                                                    FontWeight.Bold
                                                } else {
                                                    FontWeight.Normal
                                                },
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(picked) }) {
                Text(ctx.getString(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(ctx.getString(android.R.string.cancel))
            }
        },
    )
}

/**
 * 键面标签三选一（对齐上游 `showLabelDialog`）：
 * 自动 / 自定义文字 / 隐藏此按键标签。选中即生效并关闭，行为与其 singleChoiceItems 一致。
 */
@Composable
private fun LabelPickerDialog(
    title: String,
    autoText: String,
    customText: String,
    hiddenText: String,
    mode: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = listOf(autoText, customText, hiddenText)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { i, text ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(i) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = mode == i, onClick = { onPick(i) })
                        Spacer(Modifier.width(4.dp))
                        Text(text = text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(LocalContext.current.getString(android.R.string.cancel))
            }
        },
    )
}

/**
 * 按方向设置触发距离（对齐上游 `showDirectionDistanceDialog`）。
 *
 * 语义：0 = 跟随全局阈值（值域/默认值随 26 键、九宫格不同）；
 * 非 0 = 该键该方向专用距离，优先于全局。
 */
@Composable
private fun DirectionDistanceDialog(
    title: String,
    initial: Int,
    globalDp: Int,
    minDp: Int,
    maxDp: Int,
    onSave: (Int) -> Unit,
    onUseGlobal: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 未单独设置时，滑块初值落在全局值上（用户看到的就是「当前生效距离」）
    var distDp by remember { mutableStateOf(if (initial > 0) initial else globalDp) }
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 单行数值标签（对齐上游：「N dp」+ 未单独设置时后缀「（当前跟随全局）」）
                Text(
                    text = if (initial > 0) {
                        ctx.getString(R.string.oplus_keyboard_dp_value, distDp)
                    } else {
                        ctx.getString(R.string.oplus_keyboard_dir_distance_following, distDp)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Slider(
                    value = distDp.toFloat(),
                    onValueChange = { distDp = it.toInt().coerceIn(minDp, maxDp) },
                    valueRange = minDp.toFloat()..maxDp.toFloat(),
                )
                TextButton(onClick = onUseGlobal) {
                    Text(ctx.getString(R.string.oplus_keyboard_dir_distance_global))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(distDp) }) {
                Text(ctx.getString(R.string.oplus_keyboard_dialog_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(ctx.getString(android.R.string.cancel))
            }
        },
    )
}

/** 文本编辑对话框（自定义标签 / 输入指定内容共用，仅行数与限长不同） */
@Composable
private fun TextEditorDialog(
    title: String,
    help: String?,
    hint: String,
    value: String,
    maxCodePoints: Int,
    maxLines: Int,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(value) }
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (!help.isNullOrEmpty()) {
                    Text(
                        help,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = normalizeByCodePoints(it, maxCodePoints) },
                    placeholder = { Text(hint) },
                    maxLines = maxLines,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) {
                Text(ctx.getString(R.string.oplus_keyboard_dialog_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(ctx.getString(android.R.string.cancel))
            }
        },
    )
}

// ================================================================ 状态

/**
 * 一份草稿：键 id → 4 方向的动作 / 文本 / 距离，外加（仅普通键）标签覆盖。
 *
 * 三个 [SnapshotStateMap] 的值都是**变长数组**，Compose 的 equals 对数组是引用比较，
 * 所以每次修改必须赋一个 `copyOf()` 出来的新数组（见调用处的 setAction/setText/setDist），
 * 就地改元素不会触发重组。
 */
private class BindDraft(
    val actions: SnapshotStateMap<String, IntArray>,
    val texts: SnapshotStateMap<String, Array<String>>,
    val dists: SnapshotStateMap<String, IntArray>,
    val labels: SnapshotStateMap<String, String>,
)

/** 从 prefs 读一份草稿（回落规则必须与 hook 侧 `SwipeConfig.load()` 一致） */
private fun buildDraft(
    state: PrefState,
    keys: List<String>,
    isQwerty: Boolean,
): BindDraft {
    val actions = mutableStateMapOf<String, IntArray>()
    val texts = mutableStateMapOf<String, Array<String>>()
    val dists = mutableStateMapOf<String, IntArray>()
    val labels = mutableStateMapOf<String, String>()

    for (k in keys) {
        val a = IntArray(SwipeConst.DIR_COUNT)
        val t = Array(SwipeConst.DIR_COUNT) { "" }
        val d = IntArray(SwipeConst.DIR_COUNT)
        for (dir in 0 until SwipeConst.DIR_COUNT) {
            val rawText = state.getString(textKeyOf(k, isQwerty, dir), "") ?: ""
            // 与 hook 侧一致：文本非空 ⇒ 该方向语义固定为「输入指定内容」
            if (normalizeByCodePoints(rawText, SwipeConst.MAX_INSERT_CODEPOINTS).isNotEmpty()) {
                t[dir] = rawText
                a[dir] = SwipeConst.A_INSERT_TEXT
            } else {
                a[dir] = currentAction(state, k, isQwerty, dir)
            }
            d[dir] = state.getInt(distanceKeyOf(k, isQwerty, dir), 0)
                .coerceAtLeast(0)
        }
        actions[k] = a
        texts[k] = t
        dists[k] = d
        if (!SwipeConst.isSpecial(k)) labels[k] = state.getString(labelKeyOf(k, isQwerty), "") ?: ""
    }
    return BindDraft(actions, texts, dists, labels)
}

/** 草稿快照（用于「有无未保存修改」比较） */
private fun snapshotOf(draft: BindDraft, keys: List<String>): String {
    val sb = StringBuilder(keys.size * 40)
    for (k in keys) {
        sb.append(k).append('=')
        val a = draft.actions[k]
        for (dir in 0 until SwipeConst.DIR_COUNT) sb.append(a?.get(dir) ?: 0).append('/')
        sb.append('|')
        val t = draft.texts[k]
        for (dir in 0 until SwipeConst.DIR_COUNT) sb.append(t?.get(dir) ?: "").append('/')
        sb.append('|')
        val d = draft.dists[k]
        for (dir in 0 until SwipeConst.DIR_COUNT) sb.append(d?.get(dir) ?: 0).append('/')
        sb.append('|').append(draft.labels[k] ?: "").append(';')
    }
    return sb.toString()
}

// ================================================================ 工具

/** 26 键网格（10 / 9 / 7） */
private val QWERTY_GRID: List<List<String>> = listOf(
    listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
    listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
    listOf("z", "x", "c", "v", "b", "n", "m"),
)

/** 九宫格 3x3：`1` 显示但不可绑定（宿主上它是普通键），其余 2–9 可绑定 */
private val T9_GRID: List<List<String>> = listOf(
    listOf("1", "2", "3"),
    listOf("4", "5", "6"),
    listOf("7", "8", "9"),
)

/** 不可绑定的键（九宫格的 1）。渲染为禁用态灰格，占位保持 3x3 排布 */
private val T9_LOCKED: Set<String> = setOf("1")

/** 九宫格键面字母（对齐上游 `T9_LETTERS`） */
private val T9_LETTERS: Map<String, String> = mapOf(
    "2" to "ABC", "3" to "DEF", "4" to "GHI", "5" to "JKL",
    "6" to "MNO", "7" to "PQRS", "8" to "TUV", "9" to "WXYZ",
)

/** 本页主键网格的键 id 列表（26 键字母 / 九宫格 2–9） */
private fun buildPrimaryKeys(isQwerty: Boolean): List<String> =
    (if (isQwerty) QWERTY_GRID else T9_GRID).flatten()
        .filter { it.isNotEmpty() && it !in T9_LOCKED }

/** 特殊键的 glyph（与 [SwipeConst.SPECIAL_KEYS] 同下标） */
private fun specialGlyph(keyId: String): String {
    val i = SwipeConst.specialIndex(keyId)
    return if (i >= 0) SwipeConst.SPECIAL_GLYPHS[i] else keyId
}

/** 动作键名（三种键 id 形态 × 方向后缀） */
private fun actionKeyOf(k: String, isQwerty: Boolean, dir: Int): String = when {
    SwipeConst.isSpecial(k) -> SwipeConst.actionKeySpecial(k, dir)
    isQwerty -> SwipeConst.actionKeyQwerty(k.first(), dir)
    else -> SwipeConst.actionKeyT9(k.toInt(), dir)
}

/** 文本键名 */
private fun textKeyOf(k: String, isQwerty: Boolean, dir: Int): String = when {
    SwipeConst.isSpecial(k) -> SwipeConst.textKeySpecial(k, dir)
    isQwerty -> SwipeConst.textKeyQwerty(k.first(), dir)
    else -> SwipeConst.textKeyT9(k.toInt(), dir)
}

/**
 * 按键级距离键名。
 *
 * 26 键用小写字母作 keyId（与 hook 侧 `SwipeConfig.readDistances(keyId)` 一致：
 * 那边遍历的是 `QWERTY_KEYS` 的 `Char.toString()`，即小写）。
 */
private fun distanceKeyOf(k: String, isQwerty: Boolean, dir: Int): String = when {
    SwipeConst.isSpecial(k) -> SwipeConst.distanceKey(k, dir)
    isQwerty -> SwipeConst.distanceKey(k.lowercase(), dir)
    else -> SwipeConst.distanceKey(k, dir)
}

/** 标签键名（与方向无关） */
private fun labelKeyOf(k: String, isQwerty: Boolean): String =
    if (isQwerty) SwipeConst.labelKeyQwerty(k.first()) else SwipeConst.labelKeyT9(k.toInt())

/** 该键该方向「prefs 无值」时的默认动作（只有 26 键下滑有非空默认） */
private fun fallbackOf(k: String, isQwerty: Boolean, dir: Int): Int =
    if (!SwipeConst.isSpecial(k) && isQwerty && dir == SwipeConst.DIR_DOWN) {
        SwipeConst.DEFAULT_QWERTY[k.firstOrNull()?.lowercaseChar() ?: ' '] ?: SwipeConst.A_NONE
    } else {
        SwipeConst.A_NONE
    }

/**
 * 读当前值（含默认回落），规则必须与 hook 侧 `SwipeConfig.load()` 一致：
 * prefs 空串 ⇒ 26 键下滑回落到 [SwipeConst.DEFAULT_QWERTY]，其余回落未绑定；
 * 存了 "0" 就是显式未绑定（不能用默认值盖掉）。
 */
private fun currentAction(
    state: PrefState,
    k: String,
    isQwerty: Boolean,
    dir: Int,
): Int {
    val raw = state.getString(actionKeyOf(k, isQwerty, dir), "") ?: ""
    val fallback = fallbackOf(k, isQwerty, dir)
    if (raw.isEmpty()) return fallback
    val id = raw.toIntOrNull() ?: return fallback
    return if (id in SwipeConst.ACTION_IDS) id else fallback
}

/** 全局触发距离（26 键 / 九宫格各自的全局阈值），用于方向对话框的注释与距离对话框初值 */
private fun globalThresholdDp(state: PrefState, isQwerty: Boolean): Int =
    if (isQwerty) {
        state.getInt(SwipeConst.KEY_THRESHOLD_DP, SwipeConst.DEFAULT_THRESHOLD_DP)
    } else {
        state.getInt(SwipeConst.KEY_T9_THRESHOLD_DP, SwipeConst.DEFAULT_T9_THRESHOLD_DP)
    }

/**
 * 键面标签要展示的方向：按 [SwipeConst.LABEL_PRIORITY]（下滑 > 上 > 左 > 右）取第一个
 * 「有真动作」的方向；未绑定或只绑了禁用 ⇒ -1（不画/显示占位）。
 *
 * 与 hook 侧 `SwipeConfig.primaryDirection` 同一套优先级 —— 界面预览与键面实绘必须一致，
 * 否则用户会看到「设置页显示全选、键盘上却是空白」。
 */
private fun primaryDirOf(actions: IntArray): Int {
    for (d in SwipeConst.LABEL_PRIORITY) {
        val a = actions[d]
        if (a != SwipeConst.A_NONE && a != SwipeConst.A_DISABLE) return d
    }
    return -1
}

/** 特殊键卡片的摘要：最多两个方向的「方向符号 + 动作短名」，更多则补省略号 */
private fun specialSummary(actions: IntArray): String {
    val parts = ArrayList<String>(2)
    var more = false
    for (dir in 0 until SwipeConst.DIR_COUNT) {
        val a = actions[dir]
        if (a == SwipeConst.A_NONE) continue
        if (parts.size >= 2) {
            more = true
            break
        }
        val short = if (a == SwipeConst.A_DISABLE) {
            SwipeConst.ACTION_SHORT[SwipeConst.A_DISABLE] ?: ""
        } else {
            SwipeConst.ACTION_SHORT[a] ?: ""
        }
        parts += SwipeConst.DIR_SHORT[dir] + short
    }
    if (parts.isEmpty()) return ""
    return parts.joinToString(" ") + if (more) " …" else ""
}

/**
 * 键面副标签的最终文本，规则与 hook 侧 `SwipeConfig.resolveLabel` 严格一致：
 * 隐藏 ⇒ 空串；自定义 ⇒ 原文；否则自动取动作短名；未绑定则给占位（界面里用 `—`，
 * hook 侧绘制时则直接不画）。
 */
private fun cellLabel(labelRaw: String, actionId: Int, unboundPlaceholder: String): String = when {
    labelRaw == SwipeConst.LABEL_HIDDEN -> ""
    labelRaw.isNotEmpty() -> labelRaw
    actionId == SwipeConst.A_NONE -> unboundPlaceholder
    else -> SwipeConst.ACTION_SHORT[actionId] ?: ""
}

/** 键在界面上的显示名：26 键 → 「A 键」、九宫格 → 数字、特殊键 → 中文名 */
private fun displayName(
    ctx: android.content.Context,
    k: String,
    isQwerty: Boolean,
): String = when {
    SwipeConst.isSpecial(k) -> SwipeConst.specialLabel(k)
    isQwerty -> ctx.getString(R.string.oplus_keyboard_key_display, k.uppercase())
    else -> ctx.getString(R.string.oplus_keyboard_t9_key_display, k)
}

/**
 * 标签对话框标题里的键名（对齐上游 `describeKey` 之外的例外）：
 * 26 键 → 「A 键」，九宫格 → 「2 / ABC」（上游 `Config.t9Label`），特殊键无标签。
 */
private fun labelTitleOf(
    ctx: android.content.Context,
    k: String,
    isQwerty: Boolean,
): String = if (isQwerty) {
    ctx.getString(R.string.oplus_keyboard_key_display, k.uppercase())
} else {
    ctx.getString(R.string.oplus_keyboard_t9_label_display, k, T9_LETTERS[k] ?: "")
}

private fun actionName(id: Int): String {
    val idx = SwipeConst.ACTION_IDS.indexOf(id)
    return if (idx >= 0 && idx < SwipeConst.ACTION_NAMES.size) {
        SwipeConst.ACTION_NAMES[idx]
    } else {
        SwipeConst.ACTION_NAMES[0]
    }
}

/** 按码点截断（与 hook 侧 `SwipeConfig.normalizeText` 同规则，但保留 NUL 由调用方决定） */
private fun normalizeByCodePoints(raw: String, max: Int): String {
    val noNul = raw.replace("\u0000", "")
    val count = noNul.codePointCount(0, noNul.length)
    return if (count > max) noNul.substring(0, noNul.offsetByCodePoints(0, max)) else noNul
}

/**
 * 标签归一化，规则与 hook 侧 `SwipeConfig.normalizeLabel` 完全一致：
 * 换行/回车换空格、trim、按码点截断；隐藏哨兵原样保留（否则「隐藏」会被截成普通文字）。
 */
private fun normalizeLabelByCodePoints(raw: String): String {
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