// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.ui

import com.xtt.mcpbox.i18n.L
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
import androidx.compose.material.icons.filled.Build
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xtt.mcpbox.AndroidHost
import com.xtt.mcpbox.AppCore
import com.xtt.mcpbox.NetUtil
import com.xtt.mcpbox.privilegedLauncher
import kotlinx.coroutines.delay
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
    onAutoGrant: () -> Unit,
    onOpenPermissions: () -> Unit,
    scrollTopTick: Int = 0
) {
    val scroll = rememberScrollState()
    // 双击底栏「首页」：回到顶部
    NavReselectEffect(scrollTopTick) { scroll.animateScrollTo(0) }
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(scroll)
            .padding(bottom = 20.dp)
    ) {
        PageHeader(
            title = L("MCP 文件盒"),
            actions = {
                RoundIconButton(Icons.Filled.Refresh, L("重启服务")) { onRestartService() }
            }
        )

        GroupLabel(L("服务"))
        CardGroup(
            rows = buildList {
                add(
                    RowSpec(
                        title = if (status.running) L("服务运行中") else L("服务已停止"),
                        subtitle = if (status.running) L("已运行 %s · %s 个会话").format(uptimeLabel(status.uptimeMs), status.sessions)
                        else L("打开后同一 Wi-Fi 都能连"),
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
                            title = L("启动失败"),
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
            GroupLabel(L("连接地址"))
            val token = if (status.tokenEnabled) status.token else null
            CardGroup(
                NetUtil.endpoints(status.port, token, preferLan = true).map { ep ->
                    RowSpec(
                        title = L(ep.label),
                        subtitle = "${ep.host}:${status.port}" +
                            if (status.tokenEnabled) L(" · 含令牌") else "",
                        icon = Icons.Filled.Share,
                        onClick = { copyText(ctx, ep.url, L("连接地址已复制")) },
                        trailing = {
                            PillButton(L("复制"), outlined = true, color = MaterialTheme.colorScheme.primary, compact = true) {
                                copyText(ctx, ep.url, L("连接地址已复制"))
                            }
                        }
                    )
                } + RowSpec(
                    title = L("网页控制台"),
                    subtitle = L("在浏览器里试工具、处理审批"),
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
            GroupLabel(L("待审批（%s）").format(pending.size))
            CardColumn {
                pending.forEach { req -> PendingCard(req) }
            }
        }

        // 环境检查：只列还没拿到的项；有 Root / Shizuku 时下面多一行「一键补齐」
        EnvCheckGroup(host = host, onPermNeed = onPermNeed, onAutoGrant = onAutoGrant)

        GroupLabel(L("运行统计"))
        CardColumn {
            CardBox {
                Row(Modifier.fillMaxWidth()) {
                    StatCell(L("请求"), status.total.toString(), Modifier.weight(1f))
                    StatCell(L("允许"), status.ok.toString(), Modifier.weight(1f))
                    StatCell(L("失败"), status.failed.toString(), Modifier.weight(1f))
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth()) {
                    StatCell(L("审批"), status.approvals.toString(), Modifier.weight(1f))
                    StatCell(L("拒绝"), status.denied.toString(), Modifier.weight(1f))
                    StatCell(L("在线会话"), status.sessions.toString(), Modifier.weight(1f))
                }
            }
        }

        GroupLabel(L("接入 AI 客户端"))
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
            TagPill(L(req.perm.title), permColor(req.perm.id))
            Spacer(Modifier.width(8.dp))
            Text(
                req.tool,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.weight(1f))
            Text(L("剩余 %s 秒").format((req.createdAt + req.timeoutMs - System.currentTimeMillis()) / 1000),
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
                L("允许一次"), Modifier.weight(1f), color = Sem.ok,
                contentColor = androidx.compose.ui.graphics.Color(0xFF06210D), compact = true
            ) {
                AppCore.approval.resolve(req.id, ApprovalDecision.ALLOW_ONCE)
            }
            PillButton(
                L("始终允许"), Modifier.weight(1f), outlined = true, color = Sem.ok, compact = true
            ) {
                AppCore.approval.resolve(req.id, ApprovalDecision.ALLOW_ALWAYS)
            }
            PillButton(
                L("拒绝"), Modifier.weight(1f), outlined = true, color = Sem.bad, compact = true
            ) {
                AppCore.approval.resolve(req.id, ApprovalDecision.DENY_ONCE)
            }
        }
    }
}

/**
 * 环境检查：只列**还没拿到的**项，一项都不缺时整组消失。
 *
 * 这里自己每秒钟校一次状态（不依赖首页那个 status 轮询）：从系统设置页回来、
 * 或者刚被「一键补齐 / 进入 App 时自动补齐」开掉时，卡片立刻跟着变。
 * 有 Root / Shizuku（[privilegedLauncher]）时多给一行「一键补齐」。
 */
@Composable
private fun EnvCheckGroup(
    host: AndroidHost,
    onPermNeed: (PermNeed) -> Unit,
    onAutoGrant: () -> Unit
) {
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick++
        }
    }
    val rows = remember(tick) {
        listOfNotNull(
            if (!host.hasAllFilesAccess())
                healthSpec(L("文件访问权限"), L("AI 才能读写手机文件"), Icons.Filled.List, PermNeed.STORAGE, onPermNeed)
            else null,
            if (!host.canDrawOverlays())
                healthSpec(L("悬浮窗权限"), L("审批弹窗显示在所有应用之上"), Icons.Filled.Lock, PermNeed.OVERLAY, onPermNeed)
            else null,
            if (!host.hasNotificationPermission())
                healthSpec(L("通知权限"), L("显示运行状态与审批提醒"), Icons.Filled.Notifications, PermNeed.NOTIFICATION, onPermNeed)
            else null,
            if (!host.isIgnoringBatteryOptimizations())
                healthSpec(L("忽略电池优化"), L("防止后台被系统清掉"), Icons.Filled.Warning, PermNeed.BATTERY, onPermNeed)
            else null
        )
    }
    if (rows.isEmpty()) return
    // 特权后端没必要每秒探一次（Shizuku ping + su 路径检查），10 秒一次够了
    val privileged = remember(tick / 10) { privilegedLauncher() != null }
    GroupLabel(L("环境检查"))
    CardGroup(
        rows + listOfNotNull(
            if (!privileged) null else RowSpec(
                title = L("一键补齐"),
                subtitle = L("用 Root / Shizuku 把上面缺的直接开掉（通知权限要自己在弹窗里点）"),
                subtitleMaxLines = 2,
                icon = Icons.Filled.Build,
                onClick = onAutoGrant,
                trailing = {
                    PillButton(
                        L("补齐"), outlined = true,
                        color = MaterialTheme.colorScheme.primary, compact = true
                    ) { onAutoGrant() }
                }
            )
        )
    )
}

/** 环境检查的一行：只有「还没授权」的项才会出现在列表里，所以这行永远是待办的样子。 */
private fun healthSpec(
    title: String,
    desc: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    need: PermNeed,
    onPermNeed: (PermNeed) -> Unit
): RowSpec = RowSpec(
    title = title,
    subtitle = L("%s（未授权）").format(desc),
    icon = icon,
    onClick = { onPermNeed(need) },
    trailing = {
        PillButton(L("授权"), outlined = true, color = MaterialTheme.colorScheme.primary, compact = true) { onPermNeed(need) }
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
            L("把这个填进支持 MCP 的客户端"),
            color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp
        )
        Spacer(Modifier.height(4.dp))
        Text(
            L("Cherry Studio / 支持 Streamable HTTP 的客户端；Claude Desktop 用 npx -y mcp-remote <地址>"),
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp
        )
        Spacer(Modifier.height(12.dp))
        CodeBlock(snippet)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(L("复制配置"), Modifier.weight(1f)) { copyText(ctx, snippet, L("MCP 配置已复制")) }
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
