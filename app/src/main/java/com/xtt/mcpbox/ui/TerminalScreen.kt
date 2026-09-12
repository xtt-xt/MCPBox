// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.ui

import com.xtt.mcpbox.i18n.L
import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xtt.mcpbox.AppCore
import com.xtt.mcpbox.ShizukuHelper
import com.xtt.mcpbox.core.ShellBackends

@Composable
fun TerminalScreen(
    ctx: Context,
    revision: Int,
    onRequestShizuku: () -> Unit,
    onChanged: () -> Unit
) {
    val term = AppCore.terminal
    val text by term.text.collectAsState()
    val running by term.running.collectAsState()
    val backendLabel by term.backendLabel.collectAsState()

    var input by remember { mutableStateOf("") }
    val outScroll = rememberScrollState()

    LaunchedEffect(Unit) {
        if (!running && text.isBlank()) term.start()
    }
    LaunchedEffect(text) {
        runCatching { outScroll.scrollTo(outScroll.maxValue) }
    }

    Column(Modifier.fillMaxSize()) {
        PageHeader(
            title = L("终端"),
            subtitle = L("AI 用 run_shell 执行命令时也会弹窗审批，可以「始终允许」某条命令"),
            actions = { RoundIconButton(Icons.Filled.Clear, L("清屏")) { term.clear() } }
        )

        // 后端 + 状态
        val shizukuGranted = ShizukuHelper.isGranted()
        CardGroup(
            rows = buildList {
                add(
                    dropdownSpec(
                        title = if (running) L("会话运行中") else L("会话未启动"),
                        subtitle = "Shizuku " + ShizukuHelper.statusText(ctx),
                        icon = Icons.Filled.Build,
                        value = backendLabel.ifBlank { L("自动") },
                        options = ShellBackends.all().map { it.label }
                    ) { index ->
                        val launcher = ShellBackends.all().getOrNull(index) ?: return@dropdownSpec
                        toast(ctx, term.start(launcher.id))
                        onChanged()
                    }
                )
                if (!shizukuGranted) {
                    add(
                        RowSpec(
                            title = L("申请 Shizuku 授权"),
                            subtitle = if (!ShizukuHelper.isInstalled(ctx)) L("还没装 Shizuku，先安装并启动它")
                            else L("拿到 ADB shell 身份后 pm / am / dumpsys 才能用"),
                            icon = Icons.Filled.Warning,
                            onClick = onRequestShizuku
                        )
                    )
                }
            }
        )

        Spacer(Modifier.height(10.dp))

        // 输出区（内部滚动，占满剩余空间）
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 14.dp)
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(outScroll)
                    .padding(14.dp)
            ) {
                Text(
                    text.ifBlank { L("（还没有输出。输入命令后回车执行）") },
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 17.sp
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // 输入
        Column(Modifier.padding(horizontal = 14.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text(L("输入命令，回车执行"), fontSize = 13.sp) },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (input.isNotBlank()) {
                        term.send(input)
                        input = ""
                    }
                }),
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RoundIconButton(Icons.Filled.KeyboardArrowUp, L("上一条"), size = 36) {
                            term.previousCommand()?.let { input = it }
                        }
                        Spacer(Modifier.width(4.dp))
                        RoundIconButton(Icons.Filled.Send, L("执行"), tint = MaterialTheme.colorScheme.primary, size = 36) {
                            if (input.isNotBlank()) {
                                term.send(input)
                                input = ""
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }

    }
}
