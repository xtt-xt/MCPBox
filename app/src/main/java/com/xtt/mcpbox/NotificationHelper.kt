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
            .addAction(0, "停止服务", stopIntent)
            .addAction(0, "打开控制台", consoleIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun updateService(context: Context, title: String, text: String, port: Int, token: String?) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.notify(ID_SERVICE, serviceNotification(context, title, text, port, token)) }
    }

    /** 悬浮窗不可用时的兜底：带操作按钮的高优先级通知。 */
    fun approvalNotification(context: Context, request: ApprovalRequest): Notification {
        fun decision(code: String, label: String, requestCode: Int): NotificationCompat.Action {
            val pi = PendingIntent.getBroadcast(
                context, requestCode,
                Intent(context, ApprovalActionReceiver::class.java)
                    .setAction(ApprovalActionReceiver.ACTION_APPROVE)
                    .putExtra(ApprovalActionReceiver.EXTRA_ID, request.id)
                    .putExtra(ApprovalActionReceiver.EXTRA_DECISION, code),
                flags()
            )
            return NotificationCompat.Action.Builder(0, label, pi).build()
        }
        val detail = buildString {
            append(request.summary)
            request.path?.let { append("\n").append(it) }
            append("\n（").append(request.timeoutMs / 1000).append(" 秒内未处理将自动拒绝）")
        }
        return NotificationCompat.Builder(context, CH_APPROVAL)
            .setSmallIcon(R.drawable.ic_stat_server)
            .setContentTitle("AI 请求「${request.perm.title}」")
            .setContentText(request.summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .addAction(decision(ApprovalDecision.ALLOW_ONCE.id, "允许一次", 10))
            .addAction(decision(ApprovalDecision.ALLOW_ALWAYS.id, "始终允许", 11))
            .addAction(decision(ApprovalDecision.DENY_ONCE.id, "拒绝", 12))
            .build()
    }

    fun postApproval(context: Context, request: ApprovalRequest) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.notify(ID_APPROVAL, approvalNotification(context, request)) }
    }

    fun cancelApproval(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.cancel(ID_APPROVAL) }
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
