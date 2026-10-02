// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.xtt.mcpbox.core.ApprovalDecision
import com.xtt.mcpbox.core.ApprovalRequest
import com.xtt.mcpbox.i18n.L
import com.xtt.mcpbox.server.ApprovalActionReceiver
import com.xtt.mcpbox.ui.MainActivity

object NotificationHelper {

    const val CH_SERVICE = "mcp_service"
    const val CH_APPROVAL = "mcp_approval"
    const val CH_MESSAGE = "mcp_message"

    const val ID_SERVICE = 1001
    const val ID_APPROVAL = 1002
    private const val ID_MESSAGE = 2001

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, context.getString(R.string.notif_channel_service), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.notif_channel_service_desc)
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_APPROVAL, context.getString(R.string.notif_channel_approval), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.notif_channel_approval_desc)
                enableVibration(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_MESSAGE, context.getString(R.string.notif_channel_message), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.notif_channel_message_desc)
            }
        )
    }

    private fun flags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 1,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        flags()
    )

    /** 前台服务常驻通知。 */
    fun serviceNotification(
        context: Context,
        title: String,
        text: String,
        port: Int,
        token: String?
    ): Notification {
        val stopIntent = PendingIntent.getBroadcast(
            context, 2,
            Intent(context, ApprovalActionReceiver::class.java)
                .setAction(ApprovalActionReceiver.ACTION_STOP_SERVICE),
            flags()
        )
        val consoleIntent = PendingIntent.getActivity(
            context, 3,
            Intent(Intent.ACTION_VIEW, Uri.parse(NetUtil.consoleUrl("127.0.0.1", port, token)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            flags()
        )
        return NotificationCompat.Builder(context, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_server)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context))
            .addAction(0, L("停止服务"), stopIntent)
            .addAction(0, L("打开控制台"), consoleIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun updateService(context: Context, title: String, text: String, port: Int, token: String?) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.notify(ID_SERVICE, serviceNotification(context, title, text, port, token)) }
    }

    /** 悬浮窗不可用 / 用户选了「通知栏」时的审批通知：带「允许一次 / 始终允许 / 拒绝」按钮。 */
    fun approvalNotification(context: Context, request: ApprovalRequest): Notification {
        // 按钮的 PendingIntent 也按请求分开：requestCode 相同的话，后一条请求会把
        // 前一条的 extras 顶掉（FLAG_UPDATE_CURRENT），老大按到的是新请求的决定。
        val codeBase = approvalNotificationId(request.id) * 4
        fun decision(code: String, label: String, offset: Int): NotificationCompat.Action {
            val pi = PendingIntent.getBroadcast(
                context, codeBase + offset,
                Intent(context, ApprovalActionReceiver::class.java)
                    .setAction(ApprovalActionReceiver.ACTION_APPROVE)
                    .putExtra(ApprovalActionReceiver.EXTRA_ID, request.id)
                    .putExtra(ApprovalActionReceiver.EXTRA_DECISION, code),
                flags()
            )
            return NotificationCompat.Action.Builder(0, label, pi).build()
        }
        // 同时有多条在等：这条通知上顺带说一句还有几条，免得以为只有它
        val others = runCatching {
            AppCore.approval.pendingRequests().count { it.id != request.id }
        }.getOrDefault(0)
        val detail = buildString {
            append(request.summary)
            request.path?.let { append("\n").append(it) }
            request.command?.let {
                val shown = if (it.length > 300) it.take(300) + " …" else it
                append("\n").append(shown)
            }
            append(L("\n（%s 秒内未处理将自动拒绝）").format(request.timeoutMs / 1000))
            if (others > 0) append(L("\n还有 %s 条待审批").format(others))
        }
        return NotificationCompat.Builder(context, CH_APPROVAL)
            .setSmallIcon(R.drawable.ic_stat_server)
            .setContentTitle(L("AI 请求「%s」").format(L(request.perm.title)))
            .setContentText(request.summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .addAction(decision(ApprovalDecision.ALLOW_ONCE.id, L("允许一次"), 0))
            .addAction(decision(ApprovalDecision.ALLOW_ALWAYS.id, L("始终允许"), 1))
            .addAction(decision(ApprovalDecision.DENY_ONCE.id, L("拒绝"), 2))
            .build()
    }

    /**
     * 每条审批一条独立通知：id 从 [ID_APPROVAL] 往后按请求 id 散列取。
     * 早先所有审批共用一个 id —— 同时来两条时后一条会把前一条顶掉，
     * 通知栏模式（用户主动选的）下这会让其中一条彻底看不见。
     */
    fun approvalNotificationId(requestId: String): Int =
        ID_APPROVAL + 1 + Math.floorMod(requestId.hashCode(), 200)

    fun postApproval(context: Context, request: ApprovalRequest) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.notify(approvalNotificationId(request.id), approvalNotification(context, request)) }
    }

    fun cancelApproval(context: Context, requestId: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.cancel(approvalNotificationId(requestId)) }
    }

    /** AI 通过 notify_user 工具发来的消息。 */
    fun aiMessage(context: Context, title: String, message: String) {
        val n = NotificationCompat.Builder(context, CH_MESSAGE)
            .setSmallIcon(R.drawable.ic_stat_server)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.notify(ID_MESSAGE, n) }
    }

    fun cancelService(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.cancel(ID_SERVICE) }
    }
}
