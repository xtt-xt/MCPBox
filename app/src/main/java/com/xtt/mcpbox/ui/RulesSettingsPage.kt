// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.xtt.mcpbox.AppCore
import com.xtt.mcpbox.core.PermKey
import com.xtt.mcpbox.core.Rule
import com.xtt.mcpbox.i18n.L

/**
 * 规则页管哪一类：[PATH] 目录规则 / [COMMAND] 命令规则。
 *
 * 这两类各是一页（从「终端与命令」点哪一行进哪一页），别混在一页里 ——
 * 否则两个入口点进去看到的页面一模一样，用户会以为点错了。
 */
internal enum class RuleKind(val title: String, val subtitle: String) {
    PATH("路径规则", "给某个目录单独定允许 / 询问 / 拒绝"),
    COMMAND("命令规则", "给某条命令定允许 / 询问 / 拒绝")
}

/**
 * 「规则」子页：设置 → 终端与命令 → 路径规则 / 命令规则。
 *
 * 这两段原来挂在「权限」页里，跟权限开关混在一起；现在挪到这里单独成页
 * （权限页只留权限开关与安全选项）。返回时退到上一级「终端与命令」，不是直接跳回设置首页。
 */
@Composable
internal fun RulesSettingsPage(
    kind: RuleKind,
    revision: Int,
    onChanged: () -> Unit,
    onBack: () -> Unit,
    /** 打开「添加命令规则」子页（只对命令规则有意义）。 */
    onOpenNew: () -> Unit = {},
    /** 打开某条命令规则的编辑子页。 */
    onOpenEdit: (String) -> Unit = {}
) {
    SettingsPageShell(L(kind.title), L(kind.subtitle), onBack) {
        when (kind) {
            RuleKind.PATH -> PathRulesSection(revision = revision, onChanged = onChanged)
            RuleKind.COMMAND -> CommandRulesSection(
                revision = revision,
                onChanged = onChanged,
                onOpenNew = onOpenNew,
                onOpenEdit = onOpenEdit
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/* ------------------------------------------------------------ 路径规则 */

@Composable
private fun PathRulesSection(revision: Int, onChanged: () -> Unit) {
    val rules = remember(revision) { AppCore.permissions.pathRules() }
    var showAdd by remember { mutableStateOf(false) }

    GroupLabel(L("路径规则（%s）").format(rules.size))
    CardGroup(
        rows = buildList {
            if (rules.isEmpty()) {
                add(
                    RowSpec(
                        title = L("给目录单独定规则"),
                        subtitle = L("例：Download 目录免审批；放密码/密钥的目录直接拒绝。最长匹配优先。"),
                        subtitleMaxLines = 3,
                        icon = Icons.Filled.Info
                    )
                )
            } else {
                rules.forEach { rule ->
                    add(
                        RowSpec(
                            title = if (rule.perm == "*") L("全部权限")
                            else L(PermKey.of(rule.perm)?.title ?: rule.perm),
                            subtitle = rule.target.ifBlank { L("（未填写路径）") },
                            icon = Icons.Filled.Place,
                            trailing = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TagPill(L(rule.actionEnum.label), actionColor(rule.actionEnum))
                                    Spacer(Modifier.width(8.dp))
                                    RoundIconButton(
                                        Icons.Filled.Delete, L("删除规则"),
                                        tint = Sem.bad, size = 40
                                    ) {
                                        AppCore.permissions.removeRule(rule.id)
                                        onChanged()
                                    }
                                }
                            }
                        )
                    )
                }
            }
            add(
                RowSpec(
                    title = L("添加路径规则"),
                    subtitle = L("给某个目录单独定允许 / 询问 / 拒绝"),
                    icon = Icons.Filled.Add,
                    onClick = { showAdd = true }
                )
            )
        }
    )

    if (showAdd) {
        AddRuleDialog(
            onDismiss = { showAdd = false },
            onAdd = { perm, path, action ->
                AppCore.permissions.addRule(perm, path, action)
                showAdd = false
                onChanged()
            }
        )
    }
}

/* ------------------------------------------------------------ 命令规则 */

/**
 * 命令规则列表。
 *
 * 与以前不同的三点：
 *  - 点整行进编辑页（新增走同一页，标题不同），不再只有「添加」弹窗；
 *  - 右侧是拖动手柄：长按拿起、跟手移动，其余行自动让出一个空位，松手才落定；
 *  - 停用的规则在列表里灰着（副标题标「已停用」），匹配时直接跳过。
 */
@Composable
private fun CommandRulesSection(
    revision: Int,
    onChanged: () -> Unit,
    onOpenNew: () -> Unit,
    onOpenEdit: (String) -> Unit
) {
    val cmdRules = remember(revision) { AppCore.permissions.commandRules() }

    // ---- 拖动排序 ----
    // 拖动中**不改数据**：只记「从哪一行拿起来的」和手指位移，松手才真正挪位置。
    // 一边拖一边改数据的话，被拖的那行会跟着列表一起换位 —— 视觉上就是「跳一下」而不是跟手。
    var dragIndex by remember { mutableStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    // 每行实测高度：换算「手指移了多远算跨过一行」
    val heights = remember { HashMap<Int, Float>() }

    fun rowHeight(i: Int): Float = heights[i] ?: heights.values.firstOrNull() ?: 0f

    /** 手指现在指到第几行（从 [from] 起，按各行高度累计）。 */
    fun targetIndexFor(from: Int, dy: Float): Int {
        var idx = from
        var rest = dy
        if (rest > 0f) {
            while (idx + 1 < cmdRules.size) {
                val h = rowHeight(idx)
                if (h <= 0f || rest < h) break
                rest -= h
                idx++
            }
        } else if (rest < 0f) {
            while (idx > 0) {
                val h = rowHeight(idx - 1)
                if (h <= 0f || -rest < h) break
                rest += h
                idx--
            }
        }
        return idx
    }

    /** 松手：把这条规则挪到目标位置（和起点相同就什么都不做）。 */
    fun drop() {
        val from = dragIndex
        val to = if (from >= 0) targetIndexFor(from, dragOffset) else -1
        dragIndex = -1
        dragOffset = 0f
        if (from >= 0 && to >= 0 && to != from) {
            AppCore.permissions.moveRuleTo(cmdRules[from].id, to)
            onChanged()
        }
    }

    /**
     * 每一行的位移。
     *
     * - 被拖的那行：直接跟手（translationY = 手指位移），不做动画，并抬到最上层；
     * - 其余行：按目标位置让出一格，130ms 动画滑过去 —— 就是「自动避让出一个空位」。
     *
     * 让位只靠位移、不动数据，所以松手前列表顺序一直不变，不会出现「换位后手指对着另一行」。
     */
    @Composable
    fun shiftModifier(index: Int): Modifier {
        val from = dragIndex
        if (from < 0) return Modifier
        val target = targetIndexFor(from, dragOffset)
        if (index == from) {
            return Modifier
                .zIndex(1f)
                .graphicsLayer {
                    translationY = dragOffset
                    scaleX = 1.01f
                    scaleY = 1.01f
                }
        }
        val h = rowHeight(index)
        val shift = when {
            target > from && index > from && index <= target -> -h
            target < from && index >= target && index < from -> h
            else -> 0f
        }
        val animated by animateFloatAsState(
            targetValue = shift,
            animationSpec = tween(durationMillis = 130),
            label = "ruleRowShift"
        )
        return Modifier.graphicsLayer { translationY = animated }
    }

    GroupLabel(L("命令规则（%s）").format(cmdRules.size))
    CardGroup(
        rows = buildList {
            if (cmdRules.isEmpty()) {
                add(
                    RowSpec(
                        title = L("所有命令都要你点头"),
                        subtitle = L("AI 执行命令时会弹窗；点「记住此命令」就会自动生成一条") +
                            L("按命令名前缀匹配的规则，之后同类命令不再询问。"),
                        subtitleMaxLines = 3,
                        icon = Icons.Filled.Info
                    )
                )
            } else {
                cmdRules.forEachIndexed { index, rule ->
                    add(
                        RowSpec(
                            title = rule.target,
                            subtitle = buildString {
                                if (rule.note.isNotBlank()) append(rule.note).append(" · ")
                                append(L(matchLabel(rule.match)))
                                if (!rule.enabled) append(" · ").append(L("已停用"))
                            },
                            subtitleMaxLines = 2,
                            subtitleColor = if (rule.enabled) null else Sem.warn,
                            icon = Icons.Filled.Build,
                            iconTint = if (rule.enabled) null else Sem.warn,
                            onClick = { onOpenEdit(rule.id) },
                            trailing = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TagPill(L(rule.actionEnum.label), actionColor(rule.actionEnum))
                                    Spacer(Modifier.width(2.dp))
                                    // 拖动手柄：只有这里接手势，行本身照常点进编辑页
                                    Box(
                                        Modifier
                                            .size(width = 30.dp, height = 44.dp)
                                            .clip(RoundedCornerShape(50))
                                            .pointerInput(rule.id) {
                                                detectDragGesturesAfterLongPress(
                                                    onDragStart = {
                                                        dragIndex = index
                                                        dragOffset = 0f
                                                    },
                                                    onDrag = { change, amount ->
                                                        change.consume()
                                                        dragOffset += amount.y
                                                    },
                                                    onDragEnd = { drop() },
                                                    onDragCancel = { drop() }
                                                )
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Filled.Menu,
                                            contentDescription = L("长按拖动排序"),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        )
                    )
                }
            }
            add(
                RowSpec(
                    title = L("添加命令规则"),
                    subtitle = L("手动给某条命令定允许 / 询问 / 拒绝"),
                    icon = Icons.Filled.Add,
                    onClick = onOpenNew
                )
            )
        },
        rowModifier = { index ->
            Modifier
                .onGloballyPositioned { heights[index] = it.size.height.toFloat() }
                .then(shiftModifier(index))
        }
    )
}
