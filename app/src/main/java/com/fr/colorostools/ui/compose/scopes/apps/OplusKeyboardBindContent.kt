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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
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
 * 26 键 / 九宫格下滑绑定的网格编辑页（原 `app.xiaobuswipe` 界面的移植）。
 *
 * ## 为什么是「草稿 + 保存」而不是「改一格立刻生效」
 * 参考模块的语义是「改完点底部保存」（页头文案即如此承诺）。这不只是观感问题：
 * 本页有「清空 26 键」「清空九宫格映射」这类**批量破坏性操作**，逐格即时写入时
 * 误触一次就不可撤销；攒成草稿后，未点保存前怎么改都不影响线上配置。
 *
 * ## 两种手势各管一件事（与参考模块严格对齐）
 * - **点击 = 设置下滑动作**；选到「输入指定内容」时再弹输入框填要上屏的文字
 * - **长按 = 设置键面标签**（三选一：自动 / 自定义文字 / 隐藏此按键标签）
 *
 * 早期版本把长按做成「编辑自定义文本」，等于把「动作」和「标签」混成一件事，
 * 结果既丢掉了标签的自定义与隐藏能力，也让「已绑定但不想显示标签」无法表达。
 *
 * ## 写值路径（必须走 [ScopeContentScope.state]，不是 sendValue）
 * hook 侧读的是 remote prefs `SettingsPrefs`（见 `HookEnv.PREFS_SETTINGS`），
 * 而 `sendPrefsValue` 写的是 `ModulePrefs` —— 两者是**不同的 prefs 文件**。
 * 所以保存必须逐键调 `state.set(key, value)` 落到 `SettingsPrefs`，
 * hook 侧 `HookEnv.settingString` 才能读到。
 *
 * ## 默认绑定回落（v1.1.42 修复的关键点）
 * 26 键的 `z=全选/x=剪切/c=复制/v=粘贴` 定义在 [SwipeConst.DEFAULT_QWERTY]。
 * prefs 里该键**从未写入**时（空串）必须回落到默认值 —— 与 hook 侧
 * `SwipeConfig.load()` 的回落规则严格一致，否则会出现「界面显示 z=全选，
 * 实际 hook 认为全未绑定，整条手势链路静默直通」的假象。
 */
@Composable
internal fun OplusKeyboardBindContent(
    scope: ScopeContentScope,
    isQwerty: Boolean,
) {
    val ctx = scope.context
    val state = scope.state

    // 键序：26 键按真实键盘三行排布（10 / 9 / 7），九宫格 3x3（1 为不可设置的普通键）
    val grid: List<List<String>> = if (isQwerty) QWERTY_GRID else T9_GRID
    // 可绑定键：26 键全部；九宫格排除 1
    val allKeys: List<String> = grid.flatten().filter { it.isNotEmpty() && it !in T9_LOCKED }

    // ---------------- 草稿状态 ----------------
    // remember 无 key：只在首次进入（或页面被回收重建）时从 prefs 快照一次，
    // 之后的编辑都落在 draft 里，点「保存并应用」才写回 prefs。

    /** 每键的下滑动作（动作 id 的字符串形式） */
    val draft = remember {
        val m = mutableStateMapOf<String, String>()
        for (k in allKeys) m[k] = currentAction(state, k, isQwerty).toString()
        m
    }

    /** 每键「输入指定内容」的文本 */
    val draftText = remember {
        val m = mutableStateMapOf<String, String>()
        for (k in allKeys) m[k] = state.getString(textKeyOf(k, isQwerty), "") ?: ""
        m
    }

    /** 每键的标签覆盖：空串 = 自动，[SwipeConst.LABEL_HIDDEN] = 隐藏，其它 = 自定义文字 */
    val draftLabel = remember {
        val m = mutableStateMapOf<String, String>()
        for (k in allKeys) m[k] = state.getString(labelKeyOf(k, isQwerty), "") ?: ""
        m
    }

    // 保存后的基线：用于「有无未保存修改」提示
    var baseline by remember { mutableStateOf(snapshot(draft, draftText, draftLabel)) }
    val dirty = snapshot(draft, draftText, draftLabel) != baseline

    // 对话框状态
    var actionTarget by remember { mutableStateOf<String?>(null) }
    var insertTextTarget by remember { mutableStateOf<String?>(null) }
    var labelTarget by remember { mutableStateOf<String?>(null) }
    var customLabelTarget by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    /** 把整份草稿写回 prefs（唯一落盘点） */
    fun saveAll() {
        // 只写与 prefs 现值不一致的键：PrefState.set 每次都会 commit + bump（remote prefs 走 Binder），
        // 26 键 × 3 个键 = 78 次全量写会让本页重组 78 次并产生明显卡顿。
        for (k in allKeys) {
            val actionKey = actionKeyOf(k, isQwerty)
            val target = draft[k] ?: SwipeConst.A_NONE.toString()
            if (target != currentAction(state, k, isQwerty).toString()) state.set(actionKey, target)

            val textKey = textKeyOf(k, isQwerty)
            val targetText = draftText[k] ?: ""
            // 文本也一并写：即使当前动作不是「输入指定内容」，残留文本会让 hook 侧
            // 把该键强行解释成 insert-text（那边文本优先），所以清空必须显式写空串。
            if (targetText != (state.getString(textKey, "") ?: "")) state.set(textKey, targetText)

            val labelKey = labelKeyOf(k, isQwerty)
            val targetLabel = draftLabel[k] ?: ""
            if (targetLabel != (state.getString(labelKey, "") ?: "")) state.set(labelKey, targetLabel)
        }
        baseline = snapshot(draft, draftText, draftLabel)
        ctx.showToast(ctx.getString(R.string.oplus_keyboard_saved))
    }

    /** 恢复默认：26 键回到 Z/X/C/V，九宫格全清；文本与标签一并复位 */
    fun resetDefault() {
        for (k in allKeys) {
            val def = if (isQwerty) {
                SwipeConst.DEFAULT_QWERTY[k.firstOrNull()?.lowercaseChar() ?: ' '] ?: SwipeConst.A_NONE
            } else {
                SwipeConst.A_NONE
            }
            draft[k] = def.toString()
            draftText[k] = ""
            draftLabel[k] = ""
        }
        ctx.showToast(ctx.getString(R.string.oplus_keyboard_reset_done))
    }

    /** 清空本页全部映射（动作 + 文本 + 标签） */
    fun clearAll() {
        for (k in allKeys) {
            draft[k] = SwipeConst.A_NONE.toString()
            draftText[k] = ""
            draftLabel[k] = ""
        }
        ctx.showToast(ctx.getString(R.string.oplus_keyboard_clear_done))
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
                            if (isQwerty) R.string.oplus_keyboard_bind_title_qwerty
                            else R.string.oplus_keyboard_bind_title_t9
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = ctx.getString(
                            if (isQwerty) R.string.oplus_keyboard_bind_intro_qwerty
                            else R.string.oplus_keyboard_bind_intro_t9
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
                        grid.forEach { rowKeys ->
                            Row(modifier = Modifier.fillMaxWidth()) {
                                rowKeys.forEach { k ->
                                    Box(modifier = Modifier.weight(1f)) {
                                        if (k in T9_LOCKED) {
                                            // 九宫格的 1：宿主上不可下滑，显示为灰格（对齐参考模块「普通键」）
                                            LockedKeyCell(label = k)
                                        } else {
                                            val actionId =
                                                draft[k]?.toIntOrNull() ?: SwipeConst.A_NONE
                                            KeyCell(
                                                label = k,
                                                letters = if (isQwerty) "" else T9_LETTERS[k] ?: "",
                                                sub = cellLabel(
                                                    labelRaw = draftLabel[k] ?: "",
                                                    actionId = actionId,
                                                    unboundPlaceholder = "—",
                                                ),
                                                bound = actionId != SwipeConst.A_NONE &&
                                                    actionId != SwipeConst.A_DISABLE,
                                                hiddenLabel = (draftLabel[k] ?: "") ==
                                                    SwipeConst.LABEL_HIDDEN,
                                                onClick = { actionTarget = k },
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

    // ---------------- 对话框：选动作 ----------------
    val target = actionTarget
    if (target != null) {
        val selected = draft[target]?.toIntOrNull() ?: SwipeConst.A_NONE
        ActionPickerDialog(
            title = ctx.getString(R.string.oplus_keyboard_dialog_action_title, target.uppercase()),
            selected = selected,
            onPick = { id ->
                draft[target] = id.toString()
                // 选成非「输入指定内容」时清掉残留文本：否则 hook 侧文本优先会把动作覆盖掉
                if (id != SwipeConst.A_INSERT_TEXT && (draftText[target] ?: "").isNotEmpty()) {
                    draftText[target] = ""
                }
                actionTarget = null
                // 选到「输入指定内容」⇒ 紧接着要文本（参考模块同流程）
                if (id == SwipeConst.A_INSERT_TEXT) insertTextTarget = target
            },
            onDismiss = { actionTarget = null },
        )
    }

    // ---------------- 对话框：输入指定内容 ----------------
    val insertTarget = insertTextTarget
    if (insertTarget != null) {
        TextEditorDialog(
            title = ctx.getString(R.string.oplus_keyboard_dialog_text_title, insertTarget.uppercase()),
            help = ctx.getString(R.string.oplus_keyboard_dialog_text_help),
            hint = ctx.getString(R.string.oplus_keyboard_dialog_text_hint),
            value = draftText[insertTarget] ?: "",
            maxCodePoints = SwipeConst.MAX_INSERT_CODEPOINTS,
            maxLines = 4,
            onConfirm = { text ->
                val norm = normalizeByCodePoints(text, SwipeConst.MAX_INSERT_CODEPOINTS)
                if (norm.isEmpty()) {
                    // 参考模块此处直接拒绝：「输入内容不能为空」
                    ctx.showToast(ctx.getString(R.string.oplus_keyboard_insert_empty))
                } else {
                    draftText[insertTarget] = norm
                    draft[insertTarget] = SwipeConst.A_INSERT_TEXT.toString()
                    insertTextTarget = null
                }
            },
            onDismiss = { insertTextTarget = null },
        )
    }

    // ---------------- 对话框：键面标签三态 ----------------
    val labelT = labelTarget
    if (labelT != null) {
        val raw = draftLabel[labelT] ?: ""
        val actionId = draft[labelT]?.toIntOrNull() ?: SwipeConst.A_NONE
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
        LabelPickerDialog(
            title = ctx.getString(R.string.oplus_keyboard_label_dialog_title, labelT.uppercase()),
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
                    0 -> draftLabel[labelT] = ""
                    2 -> draftLabel[labelT] = SwipeConst.LABEL_HIDDEN
                    else -> customLabelTarget = labelT
                }
            },
            onDismiss = { labelTarget = null },
        )
    }

    // ---------------- 对话框：自定义标签文字 ----------------
    val customT = customLabelTarget
    if (customT != null) {
        val cur = draftLabel[customT] ?: ""
        TextEditorDialog(
            title = ctx.getString(R.string.oplus_keyboard_label_custom_title, customT.uppercase()),
            help = null,
            hint = ctx.getString(R.string.oplus_keyboard_label_hint),
            value = if (cur == SwipeConst.LABEL_HIDDEN) "" else cur,
            maxCodePoints = SwipeConst.MAX_LABEL_CODEPOINTS,
            maxLines = 1,
            onConfirm = { text ->
                // 留空 = 恢复自动（参考模块同语义），不是「隐藏」
                draftLabel[customT] = normalizeLabelByCodePoints(text)
                customLabelTarget = null
            },
            onDismiss = { customLabelTarget = null },
        )
    }

    // ---------------- 对话框：清空确认 ----------------
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(ctx.getString(R.string.oplus_keyboard_confirm_title)) },
            text = {
                Text(
                    ctx.getString(
                        if (isQwerty) R.string.oplus_keyboard_clear_qwerty_confirm
                        else R.string.oplus_keyboard_clear_t9_confirm
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    clearAll()
                }) { Text(ctx.getString(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(ctx.getString(android.R.string.cancel))
                }
            },
        )
    }
}

/**
 * 单个键格：主标签（字母/数字）+ 可选字母行（九宫格）+ 副标签（键面标签）。
 *
 * [sub] 已由 [cellLabel] 解析成最终要显示的文字，空串表示**不显示**（用户选了「隐藏标签」）。
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
                            androidx.compose.ui.platform.LocalContext.current
                                .getString(R.string.oplus_keyboard_label_hidden_mark)
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
                    // 参考模块这里写「普通键」：说明为什么这一格不能设置
                    text = androidx.compose.ui.platform.LocalContext.current
                        .getString(R.string.oplus_keyboard_t9_normal_key),
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                )
            }
        }
    }
}

/** 动作单选对话框（26 项，必须可滚动） */
@Composable
private fun ActionPickerDialog(
    title: String,
    selected: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var picked by remember { mutableStateOf(selected) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(SwipeConst.ACTION_IDS.toList()) { id ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { picked = id }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = picked == id,
                            onClick = { picked = id },
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = actionName(id),
                            style = MaterialTheme.typography.bodyMedium,
                        )
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
 * 键面标签三选一（对齐参考模块 `showLabelDialog`）：
 * 自动 / 自定义文字 / 隐藏此按键标签。选中即生效并关闭，行为与那边的 singleChoiceItems 一致。
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
                Text(androidx.compose.ui.platform.LocalContext.current.getString(android.R.string.cancel))
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
    val ctx = androidx.compose.ui.platform.LocalContext.current
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

/** 九宫格键面字母（对齐参考模块 `T9_LETTERS`） */
private val T9_LETTERS: Map<String, String> = mapOf(
    "2" to "ABC", "3" to "DEF", "4" to "GHI", "5" to "JKL",
    "6" to "MNO", "7" to "PQRS", "8" to "TUV", "9" to "WXYZ",
)

private fun actionKeyOf(k: String, isQwerty: Boolean): String =
    if (isQwerty) SwipeConst.actionKeyQwerty(k.first()) else SwipeConst.actionKeyT9(k.toInt())

private fun textKeyOf(k: String, isQwerty: Boolean): String =
    if (isQwerty) SwipeConst.textKeyQwerty(k.first()) else SwipeConst.textKeyT9(k.toInt())

private fun labelKeyOf(k: String, isQwerty: Boolean): String =
    if (isQwerty) SwipeConst.labelKeyQwerty(k.first()) else SwipeConst.labelKeyT9(k.toInt())

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

/**
 * 读当前值（含默认回落），规则必须与 hook 侧 `SwipeConfig.load()` 一致：
 * prefs 空串 ⇒ 26 键回落到 [SwipeConst.DEFAULT_QWERTY]，九宫格回落未绑定。
 */
private fun currentAction(
    state: PrefState,
    k: String,
    isQwerty: Boolean,
): Int {
    val raw = state.getString(actionKeyOf(k, isQwerty), "") ?: ""
    val fallback = if (isQwerty) {
        SwipeConst.DEFAULT_QWERTY[k.first().lowercaseChar()] ?: SwipeConst.A_NONE
    } else {
        SwipeConst.A_NONE
    }
    if (raw.isEmpty()) return fallback
    val id = raw.toIntOrNull() ?: return fallback
    return if (id in SwipeConst.ACTION_IDS) id else fallback
}

/** 草稿快照（用于「有无未保存修改」比较） */
private fun snapshot(
    draft: Map<String, String>,
    draftText: Map<String, String>,
    draftLabel: Map<String, String>,
): String {
    val sb = StringBuilder()
    draft.keys.sorted().forEach { k ->
        sb.append(k).append('=')
            .append(draft[k]).append('|')
            .append(draftText[k]).append('|')
            .append(draftLabel[k]).append(';')
    }
    return sb.toString()
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
