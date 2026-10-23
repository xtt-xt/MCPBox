// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xtt.mcpbox.AppCore
import com.xtt.mcpbox.core.PermAction
import com.xtt.mcpbox.core.PermKey
import com.xtt.mcpbox.core.Rule
import com.xtt.mcpbox.i18n.L

/**
 * 命令规则的编辑页（新增走同一个页面，只是标题不同）。
 *
 * 以前只有「添加」弹窗、没有任何编辑入口：规则写错了只能删掉重建，
 * 想临时停用一条也只能删。现在整页收下五个字段：匹配内容 / 匹配方式 / 动作 / 备注 / 启用。
 *
 * 顺序也在这一页之外解决（列表里拖动）—— 命令规则是**自上而下第一条命中**，
 * 所以顺序本身就是规则的一部分。
 */
@Composable
internal fun CommandRuleEditPage(
    ctx: Context,
    ruleId: String?,
    revision: Int,
    onChanged: () -> Unit,
    onBack: () -> Unit
) {
    val isNew = ruleId == null
    val existing = remember(revision, ruleId) {
        if (ruleId == null) null else AppCore.permissions.commandRules().firstOrNull { it.id == ruleId }
    }
    // 规则在别处被删掉了（比如从备份恢复）：直接退回去，别让人在一个不存在的规则上改
    if (!isNew && existing == null) {
        androidx.compose.runtime.LaunchedEffect(Unit) { onBack() }
        return
    }

    var target by remember(ruleId) { mutableStateOf(existing?.target.orEmpty()) }
    var match by remember(ruleId) { mutableStateOf(existing?.match ?: Rule.MATCH_PREFIX) }
    var action by remember(ruleId) { mutableStateOf(existing?.actionEnum ?: PermAction.ALLOW) }
    var note by remember(ruleId) { mutableStateOf(existing?.note.orEmpty()) }
    var enabled by remember(ruleId) { mutableStateOf(existing?.enabled ?: true) }
    var showDelete by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    ) {
        PageHeader(
            title = L(if (isNew) "添加命令规则" else "编辑命令规则"),
            onBack = onBack
        )

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            Column(Modifier.padding(horizontal = 14.dp)) {
                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it },
                    label = { Text(L("命令，如 pm 或 ^dd if="), fontSize = 12.sp) },
                    singleLine = true,
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(L("备注（只给自己看）"), fontSize = 12.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            GroupLabel(L("匹配方式"))
            CardGroup(
                rows = listOf(
                    RowSpec(
                        title = L("匹配方式"),
                        subtitle = L(matchLabel(match)),
                        subtitleMaxLines = 2,
                        icon = null,
                        content = {
                            // 四个选项一行放不下：拆成两行，免得最后一个被挤出屏幕
                            val lines = MATCH_ORDER.chunked(2)
                            lines.forEachIndexed { i, pair ->
                                Row(
                                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
                                ) {
                                    pair.forEach { m ->
                                        ChoiceChip(
                                            L(matchLabel(m)),
                                            match == m,
                                            MaterialTheme.colorScheme.primary
                                        ) { match = m }
                                    }
                                }
                                if (i < lines.lastIndex) Spacer(Modifier.height(8.dp))
                            }
                        }
                    )
                )
            )

            GroupLabel(L("动作"))
            CardGroup(
                rows = listOf(
                    RowSpec(
                        title = L("命中后怎么做"),
                        subtitle = L("允许 = 直接执行；询问 = 弹审批；拒绝 = 直接挡掉"),
                        subtitleMaxLines = 2,
                        icon = null,
                        content = {
                            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                                PermAction.entries.forEach { a ->
                                    ChoiceChip(L(a.label), action == a, actionColor(a)) { action = a }
                                }
                            }
                        }
                    )
                )
            )

            GroupLabel(L("状态"))
            CardGroup(
                rows = listOf(
                    switchSpec(
                        title = L("启用这条规则"),
                        subtitle = L("关掉只是暂时不参与匹配，规则本身留着，位置也不变"),
                        subtitleMaxLines = 2,
                        icon = null,
                        checked = enabled
                    ) { enabled = it }
                )
            )

            Spacer(Modifier.height(12.dp))
            Text(
                L("例：填「pm」+ 前缀 + 允许 → AI 以后执行 pm 开头的命令就不再问你。"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 18.dp)
            )
            // 删除放在编辑页里：列表上只留「动作」和拖动手柄，一行放得下；
            // 删规则这种破坏性操作也值得先进来看一眼内容再确认。
            if (!isNew) {
                GroupLabel(L("危险操作"))
                CardGroup(
                    rows = listOf(
                        RowSpec(
                            title = L("删除这条规则"),
                            subtitle = L("删掉后这条命令重新回到「执行命令」权限开关的判定"),
                            subtitleMaxLines = 2,
                            icon = Icons.Filled.Delete,
                            iconTint = Sem.bad,
                            subtitleColor = Sem.bad,
                            onClick = { showDelete = true }
                        )
                    )
                )
            }

            Spacer(Modifier.height(16.dp))
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            PillButton(
                L("保存"),
                Modifier.weight(1f),
                color = MaterialTheme.colorScheme.primary
            ) {
                val pattern = target.trim()
                when {
                    pattern.isEmpty() -> toast(ctx, L("请先填写命令"))
                    isNew -> {
                        AppCore.permissions.addRule(
                            PermKey.SHELL.id, pattern, action,
                            type = Rule.TYPE_COMMAND, match = match, note = note.trim()
                        ).let { created ->
                            // 新建时默认是启用的；用户在这里关了就顺手写回去
                            if (!enabled) AppCore.permissions.updateRule(created.copy(enabled = false))
                        }
                        onChanged()
                        onBack()
                    }
                    else -> {
                        val old = existing ?: return@PillButton
                        AppCore.permissions.updateRule(
                            old.copy(
                                pattern = pattern,
                                match = match,
                                action = action.id,
                                note = note.trim(),
                                enabled = enabled
                            )
                        )
                        toast(ctx, L("已保存"))
                        onChanged()
                        onBack()
                    }
                }
            }
        }
    }
    if (showDelete && existing != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDelete = false },
            containerColor = chromeColor(MaterialTheme.colorScheme.surfaceContainerHigh),
            shape = RoundedCornerShape(28.dp),
            title = { Text(L("删除这条规则？"), fontSize = 20.sp) },
            text = { Text(L("删掉之后，这条命令要重新按权限开关判定，可能又会弹审批。"), fontSize = 14.sp) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    AppCore.permissions.removeRule(existing.id)
                    showDelete = false
                    onChanged()
                    onBack()
                }) { Text(L("删除"), color = Sem.bad) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showDelete = false }) {
                    Text(L("取消"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }
}

/** 匹配方式在界面上的固定顺序。 */
internal val MATCH_ORDER = listOf(
    Rule.MATCH_PREFIX,
    Rule.MATCH_EXACT,
    Rule.MATCH_CONTAINS,
    Rule.MATCH_REGEX
)

/** 匹配方式的文案（列表副标题与编辑页共用）。 */
internal fun matchLabel(match: String): String = when (match) {
    Rule.MATCH_EXACT -> "完全匹配"
    Rule.MATCH_CONTAINS -> "包含"
    Rule.MATCH_REGEX -> "正则"
    else -> "前缀"
}
