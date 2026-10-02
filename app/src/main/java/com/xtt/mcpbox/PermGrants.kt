// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox

import com.xtt.mcpbox.core.AutoGrant
import com.xtt.mcpbox.core.CommandLauncher
import com.xtt.mcpbox.core.LogKind
import com.xtt.mcpbox.core.ShellBackends
import com.xtt.mcpbox.core.ShellRunner
import com.xtt.mcpbox.i18n.L
import com.xtt.mcpbox.ui.PermNeed

/**
 * 系统权限的**静默补齐**：有 Root / Shizuku 就直接开，开不了再让用户走系统页 / 弹窗。
 *
 * 两条路：
 *  · 手点 —— 首页「环境检查」里每行的「授权」，或整组下面的「一键补齐」；
 *  · 自动 —— 进入 App（[autoGrantMissingPermissions]，由 MainActivity 在 onStart 触发），
 *    只在后台默默把能开的开掉，**不弹任何窗、不跳任何页面**。
 *
 * 能静默的只有 [AutoGrant.SILENT] 那三项（appops + 电池白名单）。
 * 通知是运行时权限，`pm grant` 会把正在运行的自己杀掉重启，所以永远不自动碰。
 */

/** [PermNeed] ↔ mcpcore 里的 AutoGrant id。 */
fun PermNeed.grantId(): String = when (this) {
    PermNeed.STORAGE -> AutoGrant.STORAGE
    PermNeed.OVERLAY -> AutoGrant.OVERLAY
    PermNeed.NOTIFICATION -> AutoGrant.NOTIFICATION
    PermNeed.BATTERY -> AutoGrant.BATTERY
}

/** 权限名（提示 / 日志用）。 */
fun permNeedLabel(need: PermNeed): String = when (need) {
    PermNeed.STORAGE -> L("文件访问权限")
    PermNeed.OVERLAY -> L("悬浮窗权限")
    PermNeed.NOTIFICATION -> L("通知权限")
    PermNeed.BATTERY -> L("忽略电池优化")
}

/** id → 名字（提示 / 日志用）。 */
fun autoGrantLabel(id: String): String {
    val need = PermNeed.entries.firstOrNull { it.grantId() == id } ?: return id
    return permNeedLabel(need)
}

/** 若干项拼成一句话：`文件访问权限、悬浮窗权限`。 */
fun autoGrantJoin(ids: Collection<String>): String = ids.joinToString(L("、")) { autoGrantLabel(it) }

/** 现在能用的特权后端：Shizuku 优先（已授权才算可用，不会弹任何窗），其次 root。 */
fun privilegedLauncher(): CommandLauncher? {
    val avail = ShellBackends.available()
    return avail.firstOrNull { it.id == "shizuku" } ?: avail.firstOrNull { it.id == "root" }
}

/** 一项系统权限现在是不是真的拿到了（补完复查用的也是它）。 */
fun permNeedSatisfied(need: PermNeed): Boolean {
    val h = AppCore.host
    return when (need) {
        PermNeed.STORAGE -> h.hasAllFilesAccess()
        PermNeed.OVERLAY -> h.canDrawOverlays()
        PermNeed.NOTIFICATION -> h.hasNotificationPermission()
        PermNeed.BATTERY -> h.isIgnoringBatteryOptimizations()
    }
}

/**
 * 现在还缺哪几项（只看**能静默补**的：文件访问 → 悬浮窗 → 电池）。
 *
 * 注意这里没算通知：它永远走系统弹窗，首页那行还是让用户自己点。
 */
fun missingGrantableNeeds(): List<PermNeed> =
    listOf(PermNeed.STORAGE, PermNeed.OVERLAY, PermNeed.BATTERY).filterNot { permNeedSatisfied(it) }

/**
 * 试着用特权后端把一项直接开掉。返回**开完复查确实拿到了**没有。
 *
 * 不用命令的退出码当结果：有些 ROM 上 `appops set` 返回 0 但根本没生效，
 * 所以复查系统里的真实状态（[permNeedSatisfied]）才算数。
 */
fun grantSilently(need: PermNeed): Boolean {
    if (!AutoGrant.isSilent(need.grantId())) return false
    val cmd = AutoGrant.commandFor(need.grantId(), AppCore.app.packageName) ?: return false
    val launcher = privilegedLauncher() ?: return false
    runCatching { ShellRunner().run(launcher, cmd, timeoutMs = 20_000) }
    return permNeedSatisfied(need)
}

/** 最近一次自动补齐补上的项（界面/调试看，不持久化）。 */
@Volatile
var lastAutoGranted: List<PermNeed> = emptyList()
    private set

/** 本进程里已经试过但没成功过的「后端 + 项」：不再反复试，免得每次切回前台都弹一次授权框。 */
private val blockedAttempts: MutableSet<String> =
    java.util.Collections.synchronizedSet(mutableSetOf<String>())

/** 手动「一键补齐」前清一次：用户点了就是要再试一遍。 */
fun resetBlockedAttempts() {
    blockedAttempts.clear()
}

/**
 * 自动补齐：一次把「缺的 + 能静默补的」都开掉，返回实际补上的项。
 *
 * 什么时候动手：只在**已经有可用特权后端**时（Shizuku 已授权 / root 可用）；
 * 两者都没有就直接返回空 —— 不弹窗、不报错，用户走首页那套系统流程。
 *
 * 日志只在真的补上 / 真的失败时才写，免得每次切回前台刷屏。
 * 会开 shell，**必须在后台线程调用**。
 */
fun autoGrantMissingPermissions(): List<PermNeed> {
    val launcher = privilegedLauncher() ?: return emptyList()
    val missing = missingGrantableNeeds()
    if (missing.isEmpty()) return emptyList()
    if (!AutoGrant.shouldRun(AppCore.app.packageName, missing.map { it.grantId() })) return emptyList()

    val done = ArrayList<PermNeed>()
    for (need in missing) {
        val key = "${launcher.id}:${need.grantId()}"
        if (key in blockedAttempts) continue
        if (grantSilently(need)) {
            done += need
            blockedAttempts.remove(key)
            AppCore.log.add(
                LogKind.SYSTEM,
                message = L("已自动补齐权限：%s").format(permNeedLabel(need))
            )
        } else {
            // 同一次运行里不再重复试同样一招（root 被拒时尤其明显：每次都弹授权框很烦）
            blockedAttempts += key
            AppCore.log.add(
                LogKind.SYSTEM, ok = false,
                message = L("自动补齐权限失败：%s").format(permNeedLabel(need))
            )
        }
    }
    if (done.isNotEmpty()) lastAutoGranted = done
    return done
}

/** 手动「一键补齐」的结果文案（不在这里弹 Toast，交给调用方决定怎么显示）。 */
fun autoGrantResultText(done: List<PermNeed>, launcher: CommandLauncher?): String = when {
    done.isNotEmpty() -> L("已补齐：%s").format(autoGrantJoin(done.map { it.grantId() }))
    launcher == null -> L("需要 Root 或 Shizuku 才能直接补权限")
    else -> L("没有能自动补的项（通知权限要自己在弹窗里点）")
}
