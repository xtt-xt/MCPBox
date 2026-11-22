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
    /** 记忆库。 */
    val memory: MemoryStore,
    /** 内置浏览器（App 里才有 WebView；harness 里可以换成假的）。 */
    val browser: BrowserHub? = null,
    /** 内置工具文案覆盖。 */
    val toolMeta: ToolMetaStore,
    /** 当前会话（URL profile），决定哪些工具包是激活的。 */
    val profile: String = ProfileStore.DEFAULT_ID,
    /** 工具包定义。 */
    val packs: PackStore = PackStore(MemorySettings()),
    /** 各 profile 的激活状态。 */
    val profiles: ProfileStore = ProfileStore(null, Config(MemorySettings())),
    /** 当前全部工具名（不管激活状态），给 manage_pack 校验用。 */
    val allToolNames: Set<String> = emptySet(),
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

    /**
     * 和 [guard] 一样，但 `target` 是**原样的字符串**（不是文件路径）。
     * 浏览器工具用它传 URL：审批卡片上会显示网址，将来也能按域名做规则。
     */
    fun guardTarget(
        perm: PermKey,
        target: String?,
        summary: String,
        detail: String? = null,
        command: String? = null,
        mediaType: String? = null,
        bytes: Long? = null
    ) {
        approval.guard(
            perm = perm,
            tool = tool,
            path = target,
            summary = summary,
            detail = detail,
            client = client,
            command = command,
            mediaType = mediaType,
            byteSize = bytes
        )
    }

    val sizeFormat: (Long) -> String get() = { sandbox.humanSize(it) }
}

class ToolSpec(
    val name: String,
    val title: String,
    val description: String,
    val perm: PermKey,
    val schema: JsonObject,
    /** 插件工具：来自哪个插件；内置工具 / 自定义工具为 null。 */
    val pluginId: String? = null,
    val handler: (CallContext) -> ToolResult
) {
    /** 复制一份并替换文案（内置工具的说明可以被用户改写）。 */
    fun withMeta(title: String, description: String): ToolSpec =
        ToolSpec(
            name = name, title = title, description = description, perm = perm,
            schema = schema, pluginId = pluginId, handler = handler
        )

    /**
     * 给 AI / 网页控制台看的 JSON。
     *
     * 文案在这里统一过一遍 [L]（App 会在启动时注入翻译器）—— 定义处保持中文原文，
     * 于是 App 的界面侧也能用同一份原文去查词表，不用两头维护。
     */
    fun toMcpJson(): JsonObject = jo(
        "name" to name,
        "title" to L(title),
        "description" to L(description),
        "inputSchema" to CoreI18n.translateSchema(schema)
    )

    /** 参数名列表（给 UI 展示用）。 */
    val paramNames: List<String>
        get() = runCatching {
            schema["properties"]?.jsonObject?.keys?.toList() ?: emptyList()
        }.getOrDefault(emptyList())

    val annotations: JsonObject get() = jo(
        "title" to L(title),
        "readOnlyHint" to (perm == PermKey.READ || perm == PermKey.SYSTEM),
        "destructiveHint" to (perm == PermKey.DELETE),
        "idempotentHint" to false,
        "openWorldHint" to false
    )
}

/**
 * 一次定位结果。
 *
 * App 侧从 `LocationManager` 拿到 `Location` 之后转成这个**纯数据**类，
 * mcpcore 里不能 import android，所以两者之间只传它。
 * 可选字段用 null 表示「系统没给这一项」（例如室内定位常常没有海拔和方向）。
 */
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    /** 水平精度（米）；0 或负数表示系统没给。 */
    val accuracy: Float,
    val altitude: Double? = null,
    /** 米/秒。 */
    val speed: Float? = null,
    /** 度，正北为 0。 */
    val bearing: Float? = null,
    /** 定位时刻（系统时间，毫秒）。 */
    val timeMs: Long,
    /** 来源：gps / network / passive / fused… */
    val provider: String = "",
    /** 是不是模拟定位（开发者选项里设的假位置）。 */
    val mock: Boolean = false
)

/** Android side may provide device facts / notifications; optional. */
interface HostInfo {
    fun deviceInfo(): Map<String, Any?>
    fun notify(title: String, message: String): Boolean
    fun clientName(): String? = null

    /**
     * 写系统剪贴板（UI 自动化输入中文时要用：`input text` 只认 ASCII，
     * 非 ASCII 一律走「写剪贴板 → 模拟粘贴」这条路）。
     * 返回 false 表示这端没有剪贴板能力。
     */
    fun setClipboard(text: String): Boolean = false

    /**
     * 取**一次**定位（前台一次性请求，不持续定位、不需要后台定位权限）。
     *
     * @param waitMs 最多等多久；超时就返回最后已知位置，实在没有才返回 null。
     * @param highAccuracy true = 优先 GPS（慢但准），false = 优先网络定位（快但粗）。
     * 没有定位权限 / 系统定位关着 / 桌面端测试模式都返回 null。
     */
    fun locationFix(waitMs: Long, highAccuracy: Boolean): LocationFix? = null

    /** 定位能力与系统状态（是否授权、精确定位、GPS / 网络定位开关）。桌面端返回空表。 */
    fun locationStatus(): Map<String, Any?> = emptyMap()

}
