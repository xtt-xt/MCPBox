// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xtt.mcpbox.AndroidHost
import com.xtt.mcpbox.AppCore
import com.xtt.mcpbox.NetUtil
import com.xtt.mcpbox.core.ApprovalDecision
import com.xtt.mcpbox.core.ApprovalRequest
import com.xtt.mcpbox.core.McpServer

enum class PermNeed { STORAGE, OVERLAY, NOTIFICATION, BATTERY }

@Composable
fun HomeScreen(
    ctx: Context,
    status: McpServer.ServerStatus,
    pending: List<ApprovalRequest>,
    host: AndroidHost,
    onToggleService: (Boolean) -> Unit,
    onRestartService: () -> Unit,
    onPermNeed: (PermNeed) -> Unit,
    onOpenPermissions: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 20.dp)
    ) {
        PageHeader(
            title = "MCP 文件盒",
            subtitle = if (status.running) {
                "运行中 · 端口 ${status.port} · ${status.toolCount} 个工具"
            } else {
                "服务未运行 · 打开下面的开关让 AI 连进来"
            },
            actions = {
                RoundIconButton(Icons.Filled.Refresh, "重启服务") { onRestartService() }
            }
        )

        GroupLabel("服务")
        CardGroup(
            rows = buildList {
                add(
                    RowSpec(
                        title = if (status.running) "服务运行中" else "服务已停止",
                        subtitle = if (status.running) "已运行 ${status.uptimeText} · ${status.sessions} 个会话"
                        else "打开后同一 Wi-Fi 都能连",
                        icon = Icons.Filled.PlayArrow,
                        onClick = { onToggleService(!status.running) },
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StatusDot(
                                    if (status.running) MaterialTheme.colorScheme.tertiary
                                    else MaterialTheme.colorScheme.error,
                                    9
                                )
                                Spacer(Modifier.width(12.dp))
                                AppSwitch(status.running) { onToggleService(it) }
                            }
                        }
                    )
                )
                status.lastError?.let {
                    add(
                        RowSpec(
                            title = "启动失败",
                            subtitle = it,
                            subtitleMaxLines = 2,
                            icon = Icons.Filled.Warning,
                            iconTint = MaterialTheme.colorScheme.error
                        )
                    )
                }
            }
        )

        if (status.running) {
            GroupLabel("连接地址")
            val token = if (status.tokenEnabled) status.token else null
            CardGroup(
                NetUtil.endpoints(status.port, token, preferLan = true).map { ep ->
                    RowSpec(
                        title = ep.label,
                        subtitle = "${ep.host}:${status.port}" +
                            if (status.tokenEnabled) " · 含令牌" else "",
                        icon = Icons.Filled.Share,
                        onClick = { copyText(ctx, ep.url, "连接地址已复制") },
                        trailing = {
                            PillButton("复制", outlined = true, color = MaterialTheme.colorScheme.primary, compact = true) {
                                copyText(ctx, ep.url, "连接地址已复制")
                            }
                        }
                    )
                } + RowSpec(
                    title = "网页控制台",
                    subtitle = "在浏览器里试工具、处理审批",
                    icon = Icons.Filled.Star,
                    onClick = {
                        openUrl(
                            ctx,
                            NetUtil.consoleUrl(
                                "127.0.0.1", status.port,
                                if (status.tokenEnabled) status.token else null
                            )
                        )
                    },
                    trailing = { Chevron() }
                )
            )
        }

        if (pending.isNotEmpty()) {
            GroupLabel("待审批（${pending.size}）")
            CardColumn {
                pending.forEach { req -> PendingCard(req) }
            }
        }

        GroupLabel("环境检查")
        CardGroup(
            listOf(
                healthSpec(
                    "文件访问权限", host.hasAllFilesAccess(), "AI 才能读写手机文件",
                    Icons.Filled.List, PermNeed.STORAGE, onPermNeed
                ),
                healthSpec(
                    "悬浮窗权限", host.canDrawOverlays(), "审批弹窗显示在所有应用之上",
                    Icons.Filled.Lock, PermNeed.OVERLAY, onPermNeed
                ),
                healthSpec(
                    "通知权限", host.hasNotificationPermission(), "显示运行状态与审批提醒",
                    Icons.Filled.Notifications, PermNeed.NOTIFICATION, onPermNeed
                ),
                healthSpec(
                    "忽略电池优化", host.isIgnoringBatteryOptimizations(), "防止后台被系统清掉",
                    Icons.Filled.Warning, PermNeed.BATTERY, onPermNeed
                )
            )
        )

        GroupLabel("运行统计")
        CardColumn {
            CardBox {
                Row(Modifier.fillMaxWidth()) {
                    StatCell("请求", status.total.toString(), Modifier.weight(1f))
                    StatCell("允许", status.ok.toString(), Modifier.weight(1f))
                    StatCell("失败", status.failed.toString(), Modifier.weight(1f))
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth()) {
                    StatCell("审批", status.approvals.toString(), Modifier.weight(1f))
                    StatCell("拒绝", status.denied.toString(), Modifier.weight(1f))
                    StatCell("在线会话", status.sessions.toString(), Modifier.weight(1f))
                }
            }
        }

        GroupLabel("接入 AI 客户端")
        CardColumn {
            GuideCard(ctx, status)
        }
    }
}

@Composable
private fun Chevron() {
    Icon(
        Icons.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun PendingCard(req: ApprovalRequest) {
    CardBox(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TagPill(req.perm.title, permColor(req.perm.id))
            Spacer(Modifier.width(8.dp))
            Text(
                req.tool,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.weight(1f))
            Text("剩余 ${((req.createdAt + req.timeoutMs - System.currentTimeMillis()) / 1000).coerceAtLeast(0)} 秒",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        Text(
            req.summary,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 15.sp,
            modifier = Modifier.padding(top = 10.dp)
        )
        req.path?.let {
            Text(
                it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
                fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp)
            )
        }
        Row(
            Modifier.padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PillButton(
                "允许一次", Modifier.weight(1f), color = Sem.ok,
                contentColor = androidx.compose.ui.graphics.Color(0xFF06210D), compact = true
            ) {
                AppCore.approval.resolve(req.id, ApprovalDecision.ALLOW_ONCE)
            }
            PillButton(
                "始终允许", Modifier.weight(1f), outlined = true, color = Sem.ok, compact = true
            ) {
                AppCore.approval.resolve(req.id, ApprovalDecision.ALLOW_ALWAYS)
            }
            PillButton(
                "拒绝", Modifier.weight(1f), outlined = true, color = Sem.bad, compact = true
            ) {
                AppCore.approval.resolve(req.id, ApprovalDecision.DENY_ONCE)
            }
        }
    }
}

/** 环境检查的一行（状态用右侧标签/按钮表达，图标统一走主题色）。 */
private fun healthSpec(
    title: String,
    ok: Boolean,
    desc: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    need: PermNeed,
    onPermNeed: (PermNeed) -> Unit
): RowSpec = RowSpec(
    title = title,
    subtitle = if (ok) desc else "$desc（未授权）",
    icon = icon,
    onClick = if (ok) null else ({ onPermNeed(need) }),
    trailing = {
        if (ok) {
            OutlineTag("已就绪", MaterialTheme.colorScheme.tertiary)
        } else {
            PillButton("授权", outlined = true, color = MaterialTheme.colorScheme.primary, compact = true) { onPermNeed(need) }
        }
    }
)

@Composable
private fun GuideCard(ctx: Context, status: McpServer.ServerStatus) {
    val token = if (status.tokenEnabled) status.token else null
    val ip = NetUtil.localIpv4() ?: "127.0.0.1"
    val url = NetUtil.buildUrl("http", ip, status.port.coerceAtLeast(8720), token)
    val snippet = """
{
  "mcpServers": {
    "phone-files": {
      "type": "http",
      "url": "$url"
    }
  }
}
""".trimIndent()
    CardBox {
        Text(
            "把这个填进支持 MCP 的客户端",
            color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Cherry Studio / 支持 Streamable HTTP 的客户端；Claude Desktop 用 npx -y mcp-remote <地址>",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp
        )
        Spacer(Modifier.height(12.dp))
        CodeBlock(snippet)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("复制配置", Modifier.weight(1f)) { copyText(ctx, snippet, "MCP 配置已复制") }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Info, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "读取默认允许；写入/删除会弹窗问你，可以选「始终允许」不再打扰。",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp
            )
        }
    }
}

fun permColor(permId: String): Color = when (permId) {
    "fs.read" -> Sem.info
    "fs.write" -> Sem.warn
    "fs.delete" -> Sem.bad
    else -> Color(0xFFC9C4D0)
}

fun openUrl(ctx: Context, url: String) {
    runCatching {
        ctx.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
