// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

data class ToolResult(
    val text: String,
    val ok: Boolean = true,
    /** Extra MCP content blocks (e.g. base64 images). */
    val extraContent: List<JsonObject> = emptyList()
)

class ToolFailure(message: String) : Exception(message)

/** Everything a tool handler needs. */
class CallContext(
    val tool: String,
    val args: JsonObject,
    val client: String?,
    val config: Config,
    val sandbox: PathSandbox,
    val permissions: PermissionStore,
    val approval: ApprovalCenter,
    val log: EventLog,
    val trash: TrashManager,
    val host: HostInfo?,
    val customTools: CustomToolStore,
    /** 本地读不到时走 root / Shizuku 的文件桥。 */
    val bridge: FileBridge,
    val startedAt: Long = System.currentTimeMillis()
) {

    fun str(key: String, default: String? = null): String? = args.str(key) ?: default

    fun rawPath(key: String = "path"): String? = args.str(key)

    fun path(key: String = "path", default: String? = null, mustExist: Boolean = false): File =
        sandbox.resolve(args.str(key) ?: default, mustExist)

    fun optionalPath(key: String, mustExist: Boolean = false): File? {
        val v = args.str(key) ?: return null
        return sandbox.resolve(v, mustExist)
    }

    /** Gate one operation behind the permission matrix (may pop the approval overlay). */
    fun guard(
        perm: PermKey,
        path: File?,
        summary: String,
        detail: String? = null,
        bytes: Long? = null,
        mediaType: String? = null,
        command: String? = null,
        backend: String? = null
    ) {
        approval.guard(
            perm = perm,
            tool = tool,
            path = path?.path,
            summary = summary,
            detail = detail,
            client = client,
            mediaType = mediaType,
            byteSize = bytes,
            command = command,
            backend = backend
        )
    }

    fun fail(message: String): Nothing = throw ToolFailure(message)

    val sizeFormat: (Long) -> String get() = { sandbox.humanSize(it) }
}

class ToolSpec(
    val name: String,
    val title: String,
    val description: String,
    val perm: PermKey,
    val schema: JsonObject,
    val handler: (CallContext) -> ToolResult
) {
    fun toMcpJson(): JsonObject = jo(
        "name" to name,
        "title" to title,
        "description" to description,
        "inputSchema" to schema
    )

    /** 参数名列表（给 UI 展示用）。 */
    val paramNames: List<String>
        get() = runCatching {
            schema["properties"]?.jsonObject?.keys?.toList() ?: emptyList()
        }.getOrDefault(emptyList())

    val annotations: JsonObject get() = jo(
        "title" to title,
        "readOnlyHint" to (perm == PermKey.READ || perm == PermKey.SYSTEM),
        "destructiveHint" to (perm == PermKey.DELETE),
        "idempotentHint" to false,
        "openWorldHint" to false
    )
}

/** Android side may provide device facts / notifications; optional. */
interface HostInfo {
    fun deviceInfo(): Map<String, Any?>
    fun notify(title: String, message: String): Boolean
    fun clientName(): String? = null
}
