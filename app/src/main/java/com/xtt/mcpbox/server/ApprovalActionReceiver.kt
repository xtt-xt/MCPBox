package com.xtt.mcpbox.server

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.xtt.mcpbox.AppCore
import com.xtt.mcpbox.NotificationHelper
import com.xtt.mcpbox.core.ApprovalDecision
import com.xtt.mcpbox.core.LogKind

/** 通知栏里的「允许 / 拒绝 / 停止服务」按钮。 */
class ApprovalActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_APPROVE = "com.xtt.mcpbox.action.APPROVE"
        const val ACTION_STOP_SERVICE = "com.xtt.mcpbox.action.STOP_SERVICE"
        const val EXTRA_ID = "extra_id"
        const val EXTRA_DECISION = "extra_decision"
    }

    override fun onReceive(context: Context, intent: Intent) {
        AppCore.init(context.applicationContext as Application)
        when (intent.action) {
            ACTION_APPROVE -> {
                val id = intent.getStringExtra(EXTRA_ID) ?: return
                val code = intent.getStringExtra(EXTRA_DECISION)
                val decision = ApprovalDecision.entries.firstOrNull { it.id == code }
                    ?: ApprovalDecision.DENY_ONCE
                val ok = AppCore.approval.resolve(id, decision)
                AppCore.log.add(
                    LogKind.APPROVAL, ok = ok,
                    message = if (ok) "通过通知栏处理了审批请求（${decision.label}）" else "该审批请求已失效"
                )
                NotificationHelper.cancelApproval(context)
            }
            ACTION_STOP_SERVICE -> {
                AppCore.log.add(LogKind.SYSTEM, message = "用户从通知栏停止了服务")
                McpService.stop(context)
            }
        }
    }
}
