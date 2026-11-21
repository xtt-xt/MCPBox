// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xtt.mcpbox.AppCore
import com.xtt.mcpbox.i18n.L
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 旧版记忆库的迁移提示：**打开 App 就弹**，不是进了记忆库才弹。
 *
 * 「稍后」只关掉这一次启动 —— 下次打开还会弹（旧库一天不迁移，就一天不让读写记忆）。
 */
object MemoryMigratePrompt {
    /** 这次启动里用户点过「稍后」。进程重启后自然复位。 */
    var dismissedThisLaunch by mutableStateOf(false)
}

/** 放在 App 根部：只要记忆目录里还躺着旧版 graph.json 就弹窗。 */
@Composable
fun MemoryMigrateHost() {
    var rev by remember { mutableStateOf(AppCore.memory.revision) }
    DisposableEffect(Unit) {
        val l: () -> Unit = { rev = AppCore.memory.revision }
        AppCore.memory.addListener(l)
        onDispose { AppCore.memory.removeListener(l) }
    }
    val legacy = remember(rev) { AppCore.memory.hasLegacy }
    // 迁移完之后把「稍后」清掉，将来真又遇到旧库还能正常弹。
    // 不在组合里直接写状态（会在组合过程中触发重组），放进副作用里。
    androidx.compose.runtime.LaunchedEffect(legacy) {
        if (!legacy) MemoryMigratePrompt.dismissedThisLaunch = false
    }
    if (!legacy) return
    MemoryMigrateWindow(
        visible = !MemoryMigratePrompt.dismissedThisLaunch,
        onDismiss = { MemoryMigratePrompt.dismissedThisLaunch = true },
        onMigrated = {}
    )
}

/**
 * 迁移窗本体：说明 + 「迁移 / 保存旧版记忆 / 稍后」，迁移时换成转圈。
 *
 * 记忆库页面的横幅也复用这一个（visible 由页面自己控制）。
 */
@Composable
fun MemoryMigrateWindow(
    visible: Boolean,
    onDismiss: () -> Unit,
    onMigrated: () -> Unit
) {
    if (!visible) return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var migrating by remember { mutableStateOf(false) }
    val backupPath = remember(visible) { AppCore.memory.legacyBackups().firstOrNull()?.path }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val text = AppCore.memory.legacyText()
            val ok = text != null && runCatching {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            }.isSuccess
            toast(ctx, if (ok) L("旧版记忆已保存") else L("保存失败"))
        }
    }

    if (migrating) {
        androidx.compose.ui.window.Dialog(onDismissRequest = {}) {
            Surface(
                color = chromeColor(MaterialTheme.colorScheme.surfaceContainerHigh),
                shape = RoundedCornerShape(28.dp)
            ) {
                Column(
                    Modifier.padding(horizontal = 28.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.5.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        L("正在迁移记忆…"),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp
                    )
                }
            }
        }
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = chromeColor(MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(28.dp),
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        title = { Text(L("更新新版记忆数据库"), fontSize = 20.sp) },
        text = {
            Column {
                Text(
                    L("记忆库换成了「一条记忆一个 Markdown 文件」的新格式。迁移完成后旧版数据将无法继续使用。"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    L("旧版数据库已自动备份，迁移前的内容不会丢失。"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
                if (!backupPath.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        backupPath,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(Modifier.height(14.dp))
                PillButton(
                    L("迁移"),
                    Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary
                ) {
                    startMigrate(
                        ctx = ctx,
                        scope = scope,
                        onStart = { migrating = true },
                        onEnd = { migrating = false },
                        onDone = {
                            onDismiss()
                            onMigrated()
                        }
                    )
                }
                Spacer(Modifier.height(8.dp))
                PillButton(
                    L("保存旧版记忆"),
                    Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    outlined = true
                ) { saveLauncher.launch("mcpbox-memory-legacy-${stampNow()}.json") }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(L("稍后"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {}
    )
}

private fun startMigrate(
    ctx: Context,
    scope: CoroutineScope,
    onStart: () -> Unit,
    onEnd: () -> Unit,
    onDone: () -> Unit
) {
    onStart()
    scope.launch {
        val res = withContext(Dispatchers.IO) { runCatching { AppCore.memory.migrateLegacy() } }
        onEnd()
        res.fold(
            onSuccess = { r ->
                toast(ctx, L("已迁移 %s 条记忆 · %s 条关联").format(r.notes, r.links))
                onDone()
            },
            onFailure = { toast(ctx, L("迁移失败：%s").format(it.message ?: "")) }
        )
    }
}

private fun stampNow(): String =
    java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
