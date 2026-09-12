package com.xtt.mcpbox.core

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

enum class ApprovalDecision(val id: String, val label: String) {
    ALLOW_ONCE("allow_once", "允许一次"),
    ALLOW_ALWAYS("allow_always", "始终允许"),
    DENY_ONCE("deny_once", "拒绝"),
    DENY_ALWAYS("deny_always", "始终拒绝"),
    TIMEOUT("timeout", "超时拒绝"),
    CANCELLED("cancelled", "已取消");

    val approved: Boolean get() = this == ALLOW_ONCE || this == ALLOW_ALWAYS
}

data class ApprovalRequest(
    val id: String,
    val perm: PermKey,
    val tool: String,
    val path: String?,
    val summary: String,
    val detail: String?,
    val client: String?,
    val createdAt: Long,
    val timeoutMs: Long,
    val mediaType: String? = null,
    val byteSize: Long? = null,
    /** 命令类审批：终端 / 自定义工具要执行的命令。 */
    val command: String? = null,
    /** 后端：app / root / shizuku。 */
    val backend: String? = null
) {
    /** 「始终允许」会记住的东西：命令取基础命令名，其它取权限本身。 */
    val rememberLabel: String
        get() = command?.let { commandPrefix(it) } ?: perm.title
}

/** UI 钩子：必须立刻返回，之后再调用 [ApprovalCenter.resolve]。 */
interface ApprovalPresenter {
    fun show(request: ApprovalRequest)
    fun dismiss(id: String)
}

class PermissionDeniedException(message: String) : Exception(message)

/**
 * 所有危险操作的闸门：查规则 → 必要时弹窗问用户 → 记住「始终允许/拒绝」。
 */
class ApprovalCenter(
    private val config: Config,
    private val permissions: PermissionStore,
    private val log: EventLog
) {

    @Volatile var presenter: ApprovalPresenter? = null

    /** 无人值守时（测试 / 没挂 UI）的兜底决策器。 */
    @Volatile var headlessResolver: ((ApprovalRequest) -> ApprovalDecision)? = null

    private val pending = ConcurrentHashMap<String, Pending>()
    val listeners = CopyOnWriteArrayList<(List<ApprovalRequest>) -> Unit>()

    private class Pending(
        val request: ApprovalRequest,
        val future: CompletableFuture<ApprovalDecision>
    )

    fun pendingRequests(): List<ApprovalRequest> = pending.values.map { it.request }.sortedBy { it.createdAt }

    private fun notifyPending() {
        val snapshot = pendingRequests()
        listeners.forEach { runCatching { it(snapshot) } }
    }

    fun resolve(id: String, decision: ApprovalDecision): Boolean {
        val item = pending.remove(id) ?: return false
        item.future.complete(decision)
        presenter?.dismiss(id)
        notifyPending()
        return true
    }

    fun cancelAll() {
        pending.keys.toList().forEach { resolve(it, ApprovalDecision.CANCELLED) }
    }

    /** 查权限矩阵；需要用户拍板时会阻塞当前工作线程，被拒绝则抛异常。 */
    fun guard(
        perm: PermKey,
        tool: String,
        path: String?,
        summary: String,
        detail: String? = null,
        client: String? = null,
        mediaType: String? = null,
        byteSize: Long? = null,
        command: String? = null,
        backend: String? = null
    ) {
        val decision = permissions.decide(perm, path, command)
        when (decision.action) {
            PermAction.ALLOW -> return
            PermAction.DENY -> {
                log.add(
                    LogKind.APPROVAL, tool, path, client, ok = false,
                    message = "已拒绝（${decision.source}）" + (command?.let { "：$it" } ?: "")
                )
                throw PermissionDeniedException("权限「${perm.title}」被拒绝（${decision.source}）")
            }
            PermAction.ASK -> ask(
                perm, tool, path, summary, detail, client, decision.source, mediaType, byteSize, command, backend
            )
        }
    }

    private fun ask(
        perm: PermKey,
        tool: String,
        path: String?,
        summary: String,
        detail: String?,
        client: String?,
        source: String,
        mediaType: String?,
        byteSize: Long?,
        command: String?,
        backend: String?
    ) {
        val prefix = command?.let { commandPrefix(it) }
        val fullDetail = buildString {
            if (!detail.isNullOrBlank()) append(detail).append('\n')
            if (prefix != null) {
                append("选「始终允许 / 始终拒绝」会记住这条规则：以 ")
                append('`').append(prefix).append("` 开头的命令")
            }
        }.trim().ifBlank { null }

        val req = ApprovalRequest(
            id = Tokens.newId().take(12),
            perm = perm,
            tool = tool,
            path = path,
            summary = summary,
            detail = fullDetail,
            client = client,
            createdAt = System.currentTimeMillis(),
            timeoutMs = config.approvalTimeoutMs,
            mediaType = mediaType,
            byteSize = byteSize,
            command = command,
            backend = backend
        )
        val future = CompletableFuture<ApprovalDecision>()
        pending[req.id] = Pending(req, future)
        log.add(
            LogKind.APPROVAL, tool, path, client, ok = true,
            message = "等待用户审批：$summary"
        )
        notifyPending()

        val p = presenter
        val resolver = headlessResolver
        if (p == null && resolver == null) {
            pending.remove(req.id)
            notifyPending()
            throw PermissionDeniedException("没有可用的审批界面，「${perm.title}」被自动拒绝")
        }

        var decision: ApprovalDecision? = null
        val worker = kotlin.concurrent.thread(name = "mcp-approver", isDaemon = true) {
            runCatching {
                if (resolver != null) {
                    decision = resolver!!.invoke(req)
                    future.complete(decision)
                } else {
                    p!!.show(req)
                }
            }.onFailure { future.complete(ApprovalDecision.DENY_ONCE) }
        }

        try {
            decision = future.get(req.timeoutMs, TimeUnit.MILLISECONDS)
        } catch (t: TimeoutException) {
            decision = ApprovalDecision.TIMEOUT
            pending.remove(req.id)
            presenter?.dismiss(req.id)
            notifyPending()
        } catch (t: Throwable) {
            decision = ApprovalDecision.DENY_ONCE
        }
        worker.interrupt()

        val finalDecision = decision ?: ApprovalDecision.DENY_ONCE
        when (finalDecision) {
            ApprovalDecision.ALLOW_ALWAYS -> {
                if (prefix != null) {
                    permissions.addCommandRule(prefix, PermAction.ALLOW, note = "来自审批弹窗")
                    log.add(
                        LogKind.APPROVAL, tool, path, client, ok = true,
                        message = "用户选择「始终允许」→ 已添加命令规则：$prefix 开头的命令"
                    )
                } else {
                    permissions.setSwitch(perm, PermAction.ALLOW)
                    log.add(
                        LogKind.APPROVAL, tool, path, client, ok = true,
                        message = "用户选择「始终允许」→ 权限「${perm.title}」已设为允许"
                    )
                }
            }
            ApprovalDecision.DENY_ALWAYS -> {
                if (prefix != null) {
                    permissions.addCommandRule(prefix, PermAction.DENY, note = "来自审批弹窗")
                    log.add(
                        LogKind.APPROVAL, tool, path, client, ok = false,
                        message = "用户选择「始终拒绝」→ 已添加命令规则：$prefix 开头的命令被拒绝"
                    )
                } else {
                    permissions.setSwitch(perm, PermAction.DENY)
                    log.add(
                        LogKind.APPROVAL, tool, path, client, ok = false,
                        message = "用户选择「始终拒绝」→ 权限「${perm.title}」已设为拒绝"
                    )
                }
            }
            ApprovalDecision.ALLOW_ONCE ->
                log.add(LogKind.APPROVAL, tool, path, client, ok = true, message = "用户允许一次")
            ApprovalDecision.DENY_ONCE ->
                log.add(LogKind.APPROVAL, tool, path, client, ok = false, message = "用户拒绝（一次）")
            ApprovalDecision.TIMEOUT ->
                log.add(
                    LogKind.APPROVAL, tool, path, client, ok = false,
                    message = "审批超时（${req.timeoutMs / 1000} 秒无响应），已自动拒绝"
                )
            ApprovalDecision.CANCELLED ->
                log.add(LogKind.APPROVAL, tool, path, client, ok = false, message = "服务已停止，审批取消")
        }

        if (!finalDecision.approved) {
            throw PermissionDeniedException("用户未批准「${perm.title}」（${finalDecision.label}）")
        }
    }
}
