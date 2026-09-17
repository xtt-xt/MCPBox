// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.core

import java.security.SecureRandom
import java.util.UUID

/** Where the file sandbox defaults to when the user configured nothing yet. */
object DefaultRoots {
    /** Overridable so the Android layer can inject Environment.getExternalStorageDirectory(). */
    @Volatile var provider: () -> String = { System.getProperty("user.home") ?: "/" }

    fun primary(): String = provider()

    fun commonSuggestions(): List<String> = listOf(
        primary(),
        "${primary()}/Download",
        "${primary()}/Documents",
        "${primary()}/DCIM",
        "${primary()}/Pictures"
    ).distinct()
}

object Tokens {
    private val ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789".toCharArray()
    private val rnd = SecureRandom()

    fun generateToken(length: Int = 24): String {
        val sb = StringBuilder(length)
        repeat(length) { sb.append(ALPHABET[rnd.nextInt(ALPHABET.size)]) }
        return sb.toString()
    }

    fun newId(): String = UUID.randomUUID().toString()
}

/**
 * Live configuration of the phone MCP server. Values are volatile so HTTP worker
 * threads always observe the latest state pushed from the UI.
 */
class Config(private val src: SettingsSource) {

    object Keys {
        const val PORT = "port"
        const val BIND_ALL = "bind_all"
        const val TOKEN_ENABLED = "token_enabled"
        const val TOKEN = "token"
        const val ROOTS = "roots"
        const val FULL_ACCESS = "full_access"
        const val READ_ONLY = "read_only"
        const val RESPONSE_MODE = "response_mode"
        const val APPROVAL_TIMEOUT = "approval_timeout"
        const val TRASH = "trash"
        const val PERMISSIONS = "permissions_json"
        const val LOG_ENABLED = "log_enabled"
        const val CUSTOM_TOOLS = "custom_tools_json"
        const val PRIVATE_ACCESS = "private_access"
        const val MAX_UPLOAD_MB = "max_upload_mb"
        const val CONSOLE_LOCAL_ONLY = "console_local_only"
        const val CONSOLE_AUTH = "console_auth"
        const val CONSOLE_PASSWORD = "console_password"
        const val DISABLED_TOOLS = "disabled_tools"
        const val TOOL_OVERRIDES = "tool_overrides"
        const val TOOL_META = "tool_meta_json"
        const val MEMORY_ENABLED = "memory_enabled"
        const val SHELL_TIMEOUT = "shell_timeout"
        const val SHELL_PREFERENCE = "shell_preference"
        const val TERMINAL_BACKEND = "terminal_backend"
        const val TERMINAL_CWD = "terminal_cwd"
    }

    object Modes {
        const val AUTO = "auto"
        const val JSON = "json"
        const val SSE = "sse"
    }

    @Volatile var port: Int = 8720
    @Volatile var bindAll: Boolean = true
    @Volatile var tokenEnabled: Boolean = true
    @Volatile var token: String = ""
    @Volatile var roots: List<String> = emptyList()
    @Volatile var fullAccess: Boolean = false
    @Volatile var readOnly: Boolean = false
    @Volatile var responseMode: String = Modes.AUTO
    @Volatile var approvalTimeoutMs: Long = 120_000L
    @Volatile var trashEnabled: Boolean = true
    @Volatile var logEnabled: Boolean = true
    /** Shell / 命令执行 */
    @Volatile var shellTimeoutMs: Long = 60_000L
    /** auto 模式下后端的优先顺序 */
    @Volatile var shellPreference: String = "shizuku,root,app"
    /** 内置终端默认用哪个后端 */
    @Volatile var terminalBackend: String = "auto"
    @Volatile var terminalCwd: String = ""
    /**
     * 应用私有目录（/data/data/<包名> 之类）的开放程度：
     * off = 禁止；read = 只允许读；full = 读写都行。
     * 应用自己没权限读别人家私有目录，所以实际由 root / Shizuku 转发。
     */
    @Volatile var privateAccess: String = PRIVATE_OFF
    /** 网页 / HTTP 上传单次大小上限（MB）。 */
    @Volatile var maxUploadMb: Int = 512
    /** 只允许来自 localhost（127.0.0.1 / ::1）的请求，其它来源一律拒绝。 */
    @Volatile var consoleLocalOnly: Boolean = false
    /** 网页管理界面开密码保护（登录后才能打开）。 */
    @Volatile var consoleAuthEnabled: Boolean = false
    /** 网页登录密码；留空 = 用访问令牌（token）当密码。 */
    @Volatile var consolePassword: String = ""
    /** 被禁用的工具名（逗号分隔）：不出现在 tools/list，也无法调用。 */
    @Volatile var disabledTools: String = ""
    /** 单个工具的权限覆盖：name=allow|ask|deny|follow;...（follow / 空 = 跟随全局权限矩阵）。 */
    @Volatile var toolOverrides: String = ""
    /** 内置工具的文案覆盖（JSON 列表）。 */
    @Volatile var toolMeta: String = ""
    /** 记忆库总开关：关掉后记忆工具在 tools/list 里消失。 */
    @Volatile var memoryEnabled: Boolean = true
    @Volatile var revision: Long = 0

    companion object {
        const val PRIVATE_OFF = "off"
        const val PRIVATE_READ = "read"
        const val PRIVATE_FULL = "full"
    }

    init {
        reload()
        if (token.isBlank()) {
            token = Tokens.generateToken()
            src.putString(Keys.TOKEN, token)
        }
    }

    fun reload() {
        port = src.getInt(Keys.PORT, 8720).coerceIn(1024, 65535)
        bindAll = src.getBoolean(Keys.BIND_ALL, true)
        tokenEnabled = src.getBoolean(Keys.TOKEN_ENABLED, true)
        token = src.getString(Keys.TOKEN, null) ?: ""
        roots = (src.getString(Keys.ROOTS, null) ?: "")
            .split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { listOf(DefaultRoots.primary()) }
        fullAccess = src.getBoolean(Keys.FULL_ACCESS, false)
        readOnly = src.getBoolean(Keys.READ_ONLY, false)
        responseMode = src.getString(Keys.RESPONSE_MODE, Modes.AUTO) ?: Modes.AUTO
        approvalTimeoutMs = src.getLong(Keys.APPROVAL_TIMEOUT, 120_000L).coerceIn(5_000L, 3_600_000L)
        trashEnabled = src.getBoolean(Keys.TRASH, true)
        logEnabled = src.getBoolean(Keys.LOG_ENABLED, true)
        shellTimeoutMs = src.getLong(Keys.SHELL_TIMEOUT, 60_000L).coerceIn(3_000L, 3_600_000L)
        shellPreference = src.getString(Keys.SHELL_PREFERENCE, "shizuku,root,app") ?: "shizuku,root,app"
        terminalBackend = src.getString(Keys.TERMINAL_BACKEND, "auto") ?: "auto"
        terminalCwd = src.getString(Keys.TERMINAL_CWD, "") ?: ""
        privateAccess = src.getString(Keys.PRIVATE_ACCESS, PRIVATE_OFF) ?: PRIVATE_OFF
        maxUploadMb = src.getInt(Keys.MAX_UPLOAD_MB, 512).coerceIn(1, 4096)
        consoleLocalOnly = src.getBoolean(Keys.CONSOLE_LOCAL_ONLY, false)
        consoleAuthEnabled = src.getBoolean(Keys.CONSOLE_AUTH, false)
        consolePassword = src.getString(Keys.CONSOLE_PASSWORD, "") ?: ""
        disabledTools = src.getString(Keys.DISABLED_TOOLS, "") ?: ""
        toolOverrides = src.getString(Keys.TOOL_OVERRIDES, "") ?: ""
        toolMeta = src.getString(Keys.TOOL_META, "") ?: ""
        memoryEnabled = src.getBoolean(Keys.MEMORY_ENABLED, true)
        revision++
    }

    fun save() {
        src.putInt(Keys.PORT, port)
        src.putBoolean(Keys.BIND_ALL, bindAll)
        src.putBoolean(Keys.TOKEN_ENABLED, tokenEnabled)
        src.putString(Keys.TOKEN, token)
        src.putString(Keys.ROOTS, roots.joinToString("\n"))
        src.putBoolean(Keys.FULL_ACCESS, fullAccess)
        src.putBoolean(Keys.READ_ONLY, readOnly)
        src.putString(Keys.RESPONSE_MODE, responseMode)
        src.putLong(Keys.APPROVAL_TIMEOUT, approvalTimeoutMs)
        src.putBoolean(Keys.TRASH, trashEnabled)
        src.putBoolean(Keys.LOG_ENABLED, logEnabled)
        src.putLong(Keys.SHELL_TIMEOUT, shellTimeoutMs)
        src.putString(Keys.SHELL_PREFERENCE, shellPreference)
        src.putString(Keys.TERMINAL_BACKEND, terminalBackend)
        src.putString(Keys.TERMINAL_CWD, terminalCwd)
        src.putString(Keys.PRIVATE_ACCESS, privateAccess)
        src.putInt(Keys.MAX_UPLOAD_MB, maxUploadMb)
        src.putBoolean(Keys.CONSOLE_LOCAL_ONLY, consoleLocalOnly)
        src.putBoolean(Keys.CONSOLE_AUTH, consoleAuthEnabled)
        src.putString(Keys.CONSOLE_PASSWORD, consolePassword)
        src.putString(Keys.DISABLED_TOOLS, disabledTools)
        src.putString(Keys.TOOL_OVERRIDES, toolOverrides)
        src.putString(Keys.TOOL_META, toolMeta)
        src.putBoolean(Keys.MEMORY_ENABLED, memoryEnabled)
        revision++
    }

    fun primaryRoot(): String = roots.firstOrNull() ?: DefaultRoots.primary()

    fun newToken(): String {
        token = Tokens.generateToken()
        src.putString(Keys.TOKEN, token)
        return token
    }
}
