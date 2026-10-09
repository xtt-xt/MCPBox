// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Place
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
    onBack: () -> Unit
) {
    SettingsPageShell(L(kind.title), L(kind.subtitle), onBack) {
        when (kind) {
            RuleKind.PATH -> PathRulesSection(revision = revision, onChanged = onChanged)
            RuleKind.COMMAND -> CommandRulesSection(revision = revision, onChanged = onChanged)
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

@Composable
private fun CommandRulesSection(revision: Int, onChanged: () -> Unit) {
    val cmdRules = remember(revision) { AppCore.permissions.commandRules() }
    var showAdd by remember { mutableStateOf(false) }

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
                cmdRules.forEach { rule ->
                    add(
                        RowSpec(
                            title = rule.target,
                            subtitle = when (rule.match) {
                                Rule.MATCH_EXACT -> L("完全匹配")
                                Rule.MATCH_REGEX -> L("正则匹配")
                                else -> L("前缀匹配")
                            },
                            icon = Icons.Filled.Build,
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
                    title = L("添加命令规则"),
                    subtitle = L("手动给某条命令定允许 / 询问 / 拒绝"),
                    icon = Icons.Filled.Add,
                    onClick = { showAdd = true }
                )
            )
        }
    )

    if (showAdd) {
        AddCommandRuleDialog(
            onDismiss = { showAdd = false },
            onAdd = { pattern, match, action ->
                AppCore.permissions.addRule(
                    PermKey.SHELL.id, pattern, action,
                    type = Rule.TYPE_COMMAND, match = match
                )
                showAdd = false
                onChanged()
            }
        )
    }
}
