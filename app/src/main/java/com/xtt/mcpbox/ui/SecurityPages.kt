// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.ui

import android.content.Context
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xtt.mcpbox.AppCore
import com.xtt.mcpbox.core.ApprovalPresentation
import com.xtt.mcpbox.core.LogKind
import com.xtt.mcpbox.core.PermAction
import com.xtt.mcpbox.core.PermKey
import com.xtt.mcpbox.core.PermPreset
import com.xtt.mcpbox.i18n.L

/**
 * 「权限」tab 的五个子页：工具权限 / 文件范围 / 命令与执行 / 审批 / 连接与访问。
 *
 * 为什么都搬进来：这些本来就是同一件事的四面 —— 「谁能用这台手机做什么」。
 * 以前审批在设置→安全、命令规则在设置→终端与命令、SSH 又单开一页，
 * 想调一件事要跨三个页面，加和减对不上就从这里开始。
 *
 * 页面本身**不管返回和转场**：栈、预测性返回、滚动位置都在 [PermissionScreen] 里。
 */

/* ------------------------------------------------------------ 分组 */

/**
 * 9 个权限键按「文件 / 系统 / 网络」分三组。
 *
 * 每组内部把**默认就是允许**的排在后面：`fs.read`、`system.info`、`memory`
 * 平时几乎不用动，和 `shell.exec`、`fs.delete` 平铺在一起看不出轻重。
 */
private val PermGroups: List<Pair<String, List<PermKey>>> = listOf(
    "文件" to listOf(PermKey.WRITE, PermKey.DELETE, PermKey.READ),
    "系统" to listOf(PermKey.SHELL, PermKey.UI, PermKey.TOOLS, PermKey.SYSTEM, PermKey.MEMORY),
    "网络" to listOf(PermKey.BROWSER)
)

/** 顺手把权限值和标签拼成「写入 询问 · 删除 询问」这种摘要。 */
internal fun permSummary(vararg keys: PermKey): String {
    val snap = AppCore.permissions.snapshot()
    return keys.joinToString(" · ") { key ->
        L(key.title) + " " + L((snap[key.id] ?: key.default).label)
    }
}

/* ------------------------------------------------------------ 工具权限 */

@Composable
internal fun ToolPermPage(
    ctx: Context,
    revision: Int,
    onChanged: () -> Unit,
    onOpenPack: (String?) -> Unit,
    scroll: ScrollState,
    onBack: () -> Unit
) {
    val preset = remember(revision) { AppCore.permissions.preset() }
    val switches = remember(revision) { AppCore.permissions.snapshot() }
    // 权限开关里哪一行的下拉展开了（点整行 = 点右边那个胶囊）
    var openPerm by remember { mutableStateOf<PermKey?>(null) }

    SettingsPageShell(
        title = L("工具权限"),
        subtitle = L("AI 能做哪些事。选「询问」时每次都要你点头。"),
        onBack = onBack,
        scroll = scroll
    ) {
        // 预设锁定态要在子页里也能看见，否则会觉得「下拉点不动是坏了」
        if (preset.locked) {
            CardGroup(
                listOf(
                    RowSpec(
                        title = L("当前是「%s」预设").format(L(preset.label)),
                        subtitle = L("所有开关都被统一并锁定；回上一页切到「自定义」才能逐个改。"),
                        subtitleMaxLines = 2,
                        icon = Icons.Filled.Star
                    )
                )
            )
        }

        PermGroups.forEach { (group, keys) ->
            GroupLabel(L(group))
            CardGroup(
                rows = keys.map { key ->
                    val current = switches[key.id] ?: key.default
                    RowSpec(
                        title = L(key.title),
                        subtitle = L(key.desc),
                        subtitleMaxLines = 2,
                        icon = permIcon(key),
                        onClick = if (preset.locked) null else ({ openPerm = key }),
                        trailing = {
                            if (preset.locked) {
                                // 锁定态只报值、不给改
                                OutlineTag(L(current.label), actionColor(current))
                            } else {
                                PillDropdown(
                                    value = L(current.label),
                                    options = PermAction.entries.map { L(it.label) },
                                    expanded = openPerm == key,
                                    // 打开和关闭都要接：只处理「关闭」会让胶囊自己那次点击被吞掉
                                    onExpandedChange = { open -> openPerm = if (open) key else null }
                                ) { index ->
                                    openPerm = null
                                    AppCore.permissions.setSwitch(key, PermAction.entries[index])
                                    onChanged()
                                }
                            }
                        }
                    )
                }
            )
        }

        // 单个工具的覆盖在「设置 → AI → 工具管理」里：这里只说明，不重复造控件
        GroupLabel(L("单个工具"))
        CardGroup(
            listOf(
                RowSpec(
                    title = L("工具单独覆盖"),
                    subtitle = L("在上面这套之外，每个工具还能单独设成跟随 / 允许 / 询问 / 拒绝（设置 → AI → 工具管理）。"),
                    subtitleMaxLines = 3,
                    icon = Icons.Filled.Info
                )
            )
        )

        // 工具包：会话级的工具子集 + 会话状态自动重置
        PacksSection(ctx = ctx, revision = revision, onChanged = onChanged, onOpenPack = onOpenPack)
    }
}

/* ------------------------------------------------------------ 文件范围 */

@Composable
internal fun FileScopePage(
    ctx: Context,
    revision: Int,
    onChanged: () -> Unit,
    onOpenRules: () -> Unit,
    scroll: ScrollState,
    onBack: () -> Unit
) {
    var showAddRoot by remember { mutableStateOf(false) }
    val privateMode = AppCore.config.privateAccess

    SettingsPageShell(
        title = L("文件范围"),
        subtitle = L("AI 和 SSH 都只在这几个目录里活动"),
        onBack = onBack,
        scroll = scroll
    ) {
        GroupLabel(L("允许访问的目录"))
        CardGroup(
            rows = buildList {
                AppCore.config.roots.forEachIndexed { index, root ->
                    add(
                        RowSpec(
                            title = root,
                            subtitle = if (index == 0) L("默认目录（相对路径基于它，SSH 的工作目录也是它）") else null,
                            subtitleMaxLines = 2,
                            icon = Icons.Filled.List,
                            trailing = {
                                RoundIconButton(Icons.Filled.Delete, L("移除"), tint = Sem.bad, size = 40) {
                                    if (AppCore.config.roots.size <= 1) {
                                        toast(ctx, L("至少要保留一个目录"))
                                    } else {
                                        AppCore.config.roots = AppCore.config.roots.filterNot { it == root }
                                        AppCore.saveConfig()
                                        onChanged()
                                    }
                                }
                            }
                        )
                    )
                }
                add(
                    RowSpec(
                        title = L("添加目录"),
                        subtitle = L("让 AI 能访问这个目录（相对路径基于第一个目录）"),
                        subtitleMaxLines = 2,
                        icon = Icons.Filled.Add,
                        onClick = { showAddRoot = true }
                    )
                )
            }
        )

        GroupLabel(L("路径规则"))
        CardGroup(
            listOf(
                RowSpec(
                    title = L("路径规则"),
                    subtitle = L("%s 条 · 给某个目录单独定允许 / 询问 / 拒绝，比上面整套开关优先")
                        .format(remember(revision) { AppCore.permissions.pathRules().size }),
                    subtitleMaxLines = 2,
                    icon = Icons.Filled.Place,
                    onClick = onOpenRules
                )
            )
        )

        GroupLabel(L("应用私有目录"))
        CardGroup(
            listOf(
                dropdownSpec(
                    title = L("私有目录访问"),
                    subtitle = when (privateMode) {
                        "read" -> L("只能读 /data/data 里的内容")
                        "full" -> L("读、写、删都可以（危险）")
                        else -> L("已禁止，AI 看不到应用私有数据")
                    },
                    icon = Icons.Filled.Lock,
                    options = listOf(L("禁止"), L("只读"), L("可读写")),
                    selectedIndex = listOf("off", "read", "full").indexOf(privateMode).coerceAtLeast(0)
                ) { index ->
                    AppCore.config.privateAccess = listOf("off", "read", "full")[index]
                    AppCore.saveConfig()
                    onChanged()
                },
                RowSpec(
                    title = L("怎么生效"),
                    subtitle = L("应用自己读不了别家私有目录，所以开启后会通过 ") +
                        AppCore.server.bridge.privilegedLabel +
                        L(" 转发；想只放开某一个应用，可以在上面加一条路径规则（例：/data/data/包名）。"),
                    subtitleMaxLines = 3,
                    icon = Icons.Filled.Info
                )
            )
        )
    }

    if (showAddRoot) {
        AddRootDialog(
            onDismiss = { showAddRoot = false },
            onAdd = { dir ->
                val list = AppCore.config.roots.toMutableList()
                if (list.none { it.trim() == dir }) list.add(dir)
                AppCore.config.roots = list
                AppCore.saveConfig()
                showAddRoot = false
                onChanged()
            }
        )
    }
}

/* ------------------------------------------------------------ 命令与执行 */

@Composable
internal fun ShellPolicyPage(
    revision: Int,
    onChanged: () -> Unit,
    onOpenRules: () -> Unit,
    /** 跳到「工具权限」子页（命令开关在那一页里）。 */
    onJumpPerm: () -> Unit,
    scroll: ScrollState,
    onBack: () -> Unit
) {
    val shellUnlimited = AppCore.config.shellTimeoutMs <= 0L
    var shellTimeoutState by remember(revision) {
        mutableStateOf(
            (AppCore.config.shellTimeoutMs / 1000).takeIf { it > 0L }
                ?: AppCore.prefs.shellTimeoutLastSec
        )
    }
    val prefIndex = listOf("shizuku,root,app", "root,shizuku,app", "app")
        .indexOf(AppCore.config.shellPreference).coerceAtLeast(0)

    SettingsPageShell(
        title = L("命令与执行"),
        subtitle = L("能不能跑、用什么身份跑、跑多久"),
        onBack = onBack,
        scroll = scroll
    ) {
        GroupLabel(L("执行"))
        CardGroup(
            listOf(
                // 权限开关本身在「工具权限」里，这里只报值 + 给个跳转，免得同一控件出现两次
                RowSpec(
                    title = L("命令执行权限"),
                    subtitle = L("当前：%s（点一下去改）").format(L(permSummary(PermKey.SHELL))),
                    icon = Icons.Filled.Lock,
                    onClick = onJumpPerm
                ),
                dropdownSpec(
                    title = L("执行身份"),
                    subtitle = L("依次尝试，取第一个可用的；Shizuku 要先去「终端」页授权"),
                    icon = Icons.Filled.Share,
                    options = listOf(L("Shizuku→Root→应用"), L("Root→Shizuku→应用"), L("只用应用沙箱")),
                    selectedIndex = prefIndex
                ) { index ->
                    AppCore.config.shellPreference =
                        listOf("shizuku,root,app", "root,shizuku,app", "app")[index]
                    AppCore.saveConfig()
                    onChanged()
                },
                RowSpec(
                    title = L("命令规则"),
                    subtitle = L("%s 条 · 给命令定允许 / 询问 / 拒绝，按顺序第一条命中生效")
                        .format(remember(revision) { AppCore.permissions.commandRules().size }),
                    subtitleMaxLines = 2,
                    icon = Icons.Filled.Build,
                    onClick = onOpenRules
                )
            )
        )

        GroupLabel(L("超时"))
        CardGroup(
            listOf(
                switchSpec(
                    title = L("命令不限时"),
                    subtitle = L("打开后命令会一直跑到自己结束，不会中途被掐断"),
                    subtitleMaxLines = 2,
                    icon = Icons.Filled.Warning,
                    checked = shellUnlimited
                ) { on ->
                    if (on) {
                        if (AppCore.config.shellTimeoutMs > 0L) {
                            AppCore.prefs.shellTimeoutLastSec = AppCore.config.shellTimeoutMs / 1000
                        }
                        AppCore.config.shellTimeoutMs = 0L
                    } else {
                        shellTimeoutState = AppCore.prefs.shellTimeoutLastSec.coerceIn(10L, 300L)
                        AppCore.config.shellTimeoutMs = shellTimeoutState * 1000
                    }
                    AppCore.saveConfig()
                    onChanged()
                },
                RowSpec(
                    title = L("默认超时"),
                    subtitle = L("%s 秒 · AI 和 SSH 执行命令时的上限").format(shellTimeoutState),
                    visible = !shellUnlimited,
                    content = {
                        Slider(
                            value = shellTimeoutState.toFloat(),
                            onValueChange = { shellTimeoutState = it.toLong() },
                            onValueChangeFinished = {
                                AppCore.config.shellTimeoutMs = shellTimeoutState * 1000
                                AppCore.prefs.shellTimeoutLastSec = shellTimeoutState
                                AppCore.saveConfig()
                                onChanged()
                            },
                            valueRange = 10f..300f,
                            steps = 28
                        )
                    }
                )
            )
        )
    }
}

/* ------------------------------------------------------------ 审批 */

@Composable
internal fun ApprovalPolicyPage(
    revision: Int,
    onChanged: () -> Unit,
    scroll: ScrollState,
    onBack: () -> Unit
) {
    val approvalUnlimited = AppCore.config.approvalTimeoutMs <= 0L
    // 「不限时」时 timeoutMs = 0，滑块要拿上次的有限值来显示，否则会落到范围外面
    var timeoutState by remember(revision) {
        mutableStateOf(
            (AppCore.config.approvalTimeoutMs / 1000).takeIf { it > 0L }
                ?: AppCore.prefs.approvalTimeoutLastSec
        )
    }
    val presentation = remember(revision) {
        ApprovalPresentation.of(AppCore.prefs.approvalPresentation)
    }

    SettingsPageShell(
        title = L("审批"),
        subtitle = L("AI 和 SSH 请求权限时怎么问你"),
        onBack = onBack,
        scroll = scroll
    ) {
        GroupLabel(L("方式"))
        CardGroup(
            listOf(
                dropdownSpec(
                    title = L("审批方式"),
                    subtitle = when (presentation) {
                        ApprovalPresentation.OVERLAY -> L("请求权限时弹悬浮窗；没权限时自动改用通知栏")
                        ApprovalPresentation.NOTIFY -> L("只发通知栏提醒（带允许 / 拒绝按钮），不弹悬浮窗")
                    },
                    icon = Icons.Filled.Notifications,
                    options = listOf(L("悬浮窗"), L("通知栏")),
                    selectedIndex = if (presentation == ApprovalPresentation.NOTIFY) 1 else 0
                ) { index ->
                    AppCore.prefs.approvalPresentation =
                        if (index == 1) ApprovalPresentation.NOTIFY.id else ApprovalPresentation.OVERLAY.id
                    AppCore.log.add(
                        LogKind.SYSTEM,
                        message = L("审批方式改为：%s").format(L(if (index == 1) "通知栏" else "悬浮窗"))
                    )
                    onChanged()
                }
            )
        )

        GroupLabel(L("时限"))
        CardGroup(
            listOf(
                switchSpec(
                    title = L("审批不限时"),
                    subtitle = L("打开后弹窗不会自动消失，AI 会一直等你答复"),
                    subtitleMaxLines = 2,
                    icon = Icons.Filled.Warning,
                    checked = approvalUnlimited
                ) { on ->
                    if (on) {
                        if (AppCore.config.approvalTimeoutMs > 0L) {
                            AppCore.prefs.approvalTimeoutLastSec =
                                AppCore.config.approvalTimeoutMs / 1000
                        }
                        AppCore.config.approvalTimeoutMs = 0L
                    } else {
                        timeoutState = AppCore.prefs.approvalTimeoutLastSec.coerceIn(15L, 600L)
                        AppCore.config.approvalTimeoutMs = timeoutState * 1000
                    }
                    AppCore.saveConfig()
                    onChanged()
                },
                RowSpec(
                    title = L("审批超时"),
                    subtitle = L("%s 秒 · 超时自动拒绝").format(timeoutState),
                    visible = !approvalUnlimited,
                    content = {
                        Slider(
                            value = timeoutState.toFloat(),
                            onValueChange = { timeoutState = it.toLong() },
                            onValueChangeFinished = {
                                AppCore.config.approvalTimeoutMs = timeoutState * 1000
                                AppCore.prefs.approvalTimeoutLastSec = timeoutState
                                AppCore.saveConfig()
                                onChanged()
                            },
                            valueRange = 15f..600f,
                            steps = 38
                        )
                    }
                ),
                RowSpec(
                    title = L("说明"),
                    subtitle = L("审批只认「这一次连接」：SSH 在握手时认证一次，之后每个写操作和各条命令分别算一次。"),
                    subtitleMaxLines = 3,
                    icon = Icons.Filled.Info
                )
            )
        )
    }
}

/* ------------------------------------------------------------ 连接与访问 */

@Composable
internal fun AccessPage(
    ctx: Context,
    revision: Int,
    onChanged: () -> Unit,
    onOpenSsh: () -> Unit,
    scroll: ScrollState,
    onBack: () -> Unit
) {
    var showToken by remember { mutableStateOf(false) }
    val sshEnabled = remember(revision) { AppCore.config.sshEnabled }
    val sshRunning = remember(revision) { AppCore.ssh.isRunning }

    SettingsPageShell(
        title = L("连接与访问"),
        subtitle = L("外部程序怎么连进来"),
        onBack = onBack,
        scroll = scroll
    ) {
        GroupLabel(L("SSH / SFTP"))
        CardGroup(
            listOf(
                RowSpec(
                    title = L("SSH 服务"),
                    subtitle = when {
                        !sshEnabled -> L("已关闭 · 端口 %s").format(AppCore.config.sshPort)
                        sshRunning -> L("正在监听 · 端口 %s").format(AppCore.config.sshPort)
                        else -> L("已开启，但没在监听 · 端口 %s").format(AppCore.config.sshPort)
                    },
                    icon = Icons.Filled.Person,
                    onClick = onOpenSsh
                )
            )
        )

        GroupLabel(L("访问令牌"))
        CardGroup(
            listOf(
                switchSpec(
                    title = L("启用访问令牌"),
                    subtitle = if (AppCore.config.tokenEnabled) L("客户端需要带 token 才能连接")
                    else L("任何设备都能连（不推荐）"),
                    icon = Icons.Filled.Lock,
                    checked = AppCore.config.tokenEnabled
                ) {
                    AppCore.config.tokenEnabled = it
                    AppCore.saveConfig()
                    onChanged()
                }
            )
        )
        Spacer(Modifier.height(7.dp))
        CardColumn {
            CardBox {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(L("访问令牌"), color = MaterialTheme.colorScheme.onSurface, fontSize = 15.5.sp)
                        Text(
                            if (showToken) AppCore.config.token else "•".repeat(14),
                            color = if (showToken) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    RowIconButton(
                        onClick = { showToken = !showToken },
                        icon = Icons.Filled.Star,
                        desc = if (showToken) L("隐藏") else L("显示")
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(
                        L("复制"), Modifier.weight(1f), outlined = true,
                        color = MaterialTheme.colorScheme.primary, compact = true
                    ) {
                        copyText(ctx, AppCore.config.token, L("令牌已复制"))
                    }
                    PillButton(
                        L("重置"), Modifier.weight(1f), outlined = true, color = Sem.warn, compact = true
                    ) {
                        AppCore.config.newToken()
                        AppCore.saveConfig()
                        onChanged()
                        toast(ctx, L("已生成新令牌，旧配置需要更新"))
                    }
                }
            }
        }

        GroupLabel(L("文件进出通道"))
        CardGroup(
            listOf(
                RowSpec(
                    title = L("网页上传 / 下载"),
                    subtitle = L("浏览器打开 http://127.0.0.1:%s/upload 就能往手机传文件；").format(AppCore.config.port) +
                        L("外部程序也能用 POST /upload、GET /download（要带 token）"),
                    subtitleMaxLines = 3,
                    icon = Icons.Filled.Share
                )
            )
        )
    }
}
