// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xtt.mcpbox.AppCore
import com.xtt.mcpbox.UpdateChecker
import com.xtt.mcpbox.core.ServerMeta
import com.xtt.mcpbox.core.ShellBackends
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 开源依赖清单（鸣谢）。 */
private data class Credit(val name: String, val license: String, val url: String)

private val CREDITS = listOf(
    Credit("AndroidX / Jetpack Compose", "Apache-2.0", "https://developer.android.com/jetpack/androidx"),
    Credit("Kotlin & kotlinx.coroutines", "Apache-2.0", "https://kotlinlang.org"),
    Credit("Material Components (HCT 取色算法)", "Apache-2.0", "https://github.com/material-components/material-components-android"),
    Credit("Material Color Utilities", "Apache-2.0", "https://github.com/material-foundation/material-color-utilities"),
    Credit("Shizuku", "Apache-2.0", "https://github.com/RikkaApps/Shizuku-API")
)

@Composable
fun AboutScreen(
    ctx: Context,
    revision: Int,
    onChanged: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler(enabled = true) { onBack() }

    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<UpdateChecker.Result?>(null) }
    val version = ServerMeta.version

    fun runCheck() {
        if (checking) return
        checking = true
        result = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { UpdateChecker.check() }
            result = r
            checking = false
            AppCore.prefs.lastUpdateCheck = UpdateChecker.today()
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
    ) {
        PageHeader(
            title = "关于",
            subtitle = "v$version · ${AppCore.deviceLabel()}",
            actions = { RoundIconButton(Icons.Filled.ArrowBack, "返回", onClick = onBack) }
        )

        // ------------------------------------------------ 应用信息
        Spacer(Modifier.height(6.dp))
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier
                    .size(84.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(
                        ctx.resources.getIdentifier(
                            "ic_launcher_foreground", "drawable", ctx.packageName
                        )
                    ),
                    contentDescription = null,
                    modifier = Modifier.size(72.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "MCP 文件盒",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 22.sp
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "v$version（${ServerMeta.PROTOCOL}）",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "把手机变成一台 MCP 文件服务器",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.5.sp
            )
        }

        // ------------------------------------------------ 开发者 / 更新
        GroupLabel("开发者")
        CardGroup(
            listOf(
                RowSpec(
                    title = "xtt",
                    subtitle = "个人项目 · 使用 GPL-3.0 许可",
                    icon = Icons.Filled.Star
                )
            )
        )

        GroupLabel("更新")
        CardGroup(
            listOf(
                RowSpec(
                    title = if (checking) "正在检查…" else "检查更新",
                    subtitle = when (val r = result) {
                        is UpdateChecker.Result.Newer -> "发现新版本 ${r.info.tag}（当前 v$version）"
                        UpdateChecker.Result.UpToDate -> "已经是最新版啦"
                        is UpdateChecker.Result.Failed -> r.reason
                        null -> "当前版本 v$version"
                    },
                    subtitleMaxLines = 3,
                    icon = Icons.Filled.Refresh,
                    onClick = { runCheck() },
                    trailing = {
                        PillButton(
                            if (checking) "检查中" else "检查",
                            outlined = true,
                            compact = true,
                            color = MaterialTheme.colorScheme.primary
                        ) { runCheck() }
                    }
                ),
                switchSpec(
                    title = "每天自动检查一次",
                    subtitle = "打开 App 时（当天还没查过）自动看一眼有没有新版本",
                    subtitleMaxLines = 2,
                    icon = Icons.Filled.Refresh,
                    checked = AppCore.prefs.updateCheckDaily
                ) { on ->
                    AppCore.prefs.updateCheckDaily = on
                    onChanged()
                },
                (result as? UpdateChecker.Result.Newer)?.let { newer ->
                    RowSpec(
                        title = "去下载 ${newer.info.tag}",
                        subtitle = newer.info.url,
                        subtitleMaxLines = 1,
                        icon = Icons.Filled.Share,
                        onClick = { openUrl(ctx, newer.info.url) }
                    )
                },
                (result as? UpdateChecker.Result.Failed)?.let {
                    RowSpec(
                        title = "手动打开 Releases 页面",
                        subtitle = UpdateChecker.RELEASES_URL,
                        subtitleMaxLines = 1,
                        icon = Icons.Filled.Share,
                        onClick = { openUrl(ctx, UpdateChecker.RELEASES_URL) }
                    )
                }
            ).filterNotNull()
        )

        // ------------------------------------------------ 开源
        GroupLabel("开源")
        CardGroup(
            listOf(
                RowSpec(
                    title = "GitHub 仓库",
                    subtitle = UpdateChecker.REPO_URL,
                    subtitleMaxLines = 1,
                    icon = Icons.Filled.Share,
                    onClick = { openUrl(ctx, UpdateChecker.REPO_URL) }
                ),
                RowSpec(
                    title = "许可证",
                    subtitle = "GNU General Public License v3.0",
                    icon = Icons.Filled.Info,
                    onClick = { openUrl(ctx, "https://www.gnu.org/licenses/gpl-3.0.html") }
                )
            )
        )

        GroupLabel("开源鸣谢")
        CardGroup(
            CREDITS.map { credit ->
                RowSpec(
                    title = credit.name,
                    subtitle = credit.license,
                    subtitleMaxLines = 1,
                    icon = Icons.Filled.Info,
                    onClick = { openUrl(ctx, credit.url) }
                )
            }
        )
        Spacer(Modifier.height(8.dp))
        Column(Modifier.padding(horizontal = 22.dp)) {
            Text(
                "感谢这些开源项目，MCP 文件盒才能做得这么轻。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }

        // ------------------------------------------------ 运行环境
        GroupLabel("运行环境")
        val status = AppCore.server.status()
        CardColumn {
            CardBox {
                KeyValue("版本", "v$version")
                KeyValue("设备", status.device)
                KeyValue("工具数量", "${status.toolCount} 个（自定义 ${status.customToolCount}）")
                KeyValue("MCP 协议", ServerMeta.PROTOCOL)
                KeyValue(
                    "Shell 后端",
                    ShellBackends.available().joinToString("、") { it.label }.ifBlank { "仅文件操作" }
                )
                KeyValue("服务状态", if (status.running) "运行中（端口 ${status.port}）" else "已停止")
                KeyValue("允许目录", status.roots.joinToString("、"))
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}
