// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * 工具包：一组工具的集合，用来控制 **AI 在 `tools/list` 里看到什么**。
 *
 * 为什么要它：工具定义是每一轮请求都要重复带给模型的，39 个工具 ≈ 7200 token。
 * 拆成包之后，默认只暴露 core + 文件读取 + 记忆库，剩下的等 AI 自己按需激活。
 *
 * **包只影响可见性，不影响 `tools/call`** ——
 * 调任何存在的工具都允许（照常走权限矩阵）。
 * 这样即使客户端不支持 `notifications/tools/list_changed`，也不会出现
 * 「激活了却调不到」的死锁，省 token 的目的照样达到，安全性也不损失。
 */
@Serializable
data class ToolPack(
    val id: String = "",
    /** UI 上显示的名字。 */
    val title: String = "",
    /** **给 AI 看的**说明：写清「什么时候该激活这个包」。 */
    val description: String = "",
    /** 包里的工具名。一个工具可以同时属于多个包（取并集）。 */
    val tools: List<String> = emptyList(),
    /** 内置包不可删除。 */
    val builtin: Boolean = false,
    /** core 包常驻，不可停用，也不出现在 play 列表里。 */
    val core: Boolean = false,
    /** 出厂默认就激活。 */
    val defaultActive: Boolean = false
) {
    companion object {
        const val CORE_ID = "core"
        /** 用户自建工具自动归属的包。 */
        const val MY_TOOLS_ID = "my.tools"
    }
}

object BuiltinPacks {

    val CORE = ToolPack(
        id = ToolPack.CORE_ID,
        title = "基础",
        description = "服务器状态、设备信息、通知、访问令牌，以及工具包的查询与切换。永远可用。",
        tools = listOf(
            // 原有基础能力
            "server_info", "get_device_info", "notify_user", "get_token",
            // 包管理（必须常驻，否则 AI 没法自己激活别的包）
            "list_packs", "activate_pack", "deactivate_pack", "reset_packs", "manage_pack"
        ),
        builtin = true,
        core = true,
        defaultActive = true
    )

    val FILE_READ = ToolPack(
        id = "file.read",
        title = "文件读取",
        description = "列目录、看目录树、读文本、看图片、按名/按内容搜文件、算哈希、看存储占用。" +
            "要查看手机上的文件时激活它。",
        tools = listOf(
            "list_dir", "directory_tree", "file_info", "read_file", "read_image",
            "search_files", "file_hash", "storage_info"
        ),
        builtin = true,
        defaultActive = true
    )

    val MEMORY = ToolPack(
        id = "memory",
        title = "记忆库",
        description = "长期记忆（知识图谱）：建实体、连关系、加观察、搜索、读全图、看统计。" +
            "想把事实记下来以后还用得上时激活它；只想看文件不必激活。",
        tools = listOf(
            "create_entities", "create_relations", "add_observations",
            "delete_entities", "delete_relations", "delete_observations",
            "read_graph", "search_nodes", "open_nodes", "memory_stats"
        ),
        builtin = true,
        defaultActive = true
    )

    val FILE_WRITE = ToolPack(
        id = "file.write",
        title = "文件写入",
        description = "写文件、改文件、建目录、复制、移动、删除（先放回收站）、回收站还原与清空。" +
            "要**修改**手机上的文件时激活它。",
        tools = listOf(
            "write_file", "edit_file", "make_dir", "copy_path", "move_path",
            "delete_path", "list_trash", "restore_trash", "empty_trash"
        ),
        builtin = true,
        defaultActive = false
    )

    val SHELL = ToolPack(
        id = "shell",
        title = "命令与自定义工具",
        description = "执行终端命令、查看 Shell 环境，以及新建/修改/导入导出自定义工具。" +
            "要跑命令或给自己造新工具时激活它。",
        tools = listOf(
            "run_shell", "shell_info",
            "create_custom_tool", "update_custom_tool", "delete_custom_tool",
            "list_custom_tools", "export_custom_tools", "import_custom_tools"
        ),
        builtin = true,
        defaultActive = false
    )

    val ALL: List<ToolPack> = listOf(CORE, FILE_READ, MEMORY, FILE_WRITE, SHELL)

    fun byId(id: String): ToolPack? = ALL.firstOrNull { it.id == id }

    /** 「我的工具」包：内容跟随用户的自定义工具，动态生成。 */
    fun myTools(toolNames: List<String>): ToolPack = ToolPack(
        id = ToolPack.MY_TOOLS_ID,
        title = "我的工具",
        description = "用户自己定义的工具。需要时激活。",
        tools = toolNames,
        builtin = true,
        defaultActive = false
    )

    /** 出厂默认激活的包 id。 */
    fun defaults(): Set<String> = ALL.filter { it.defaultActive }.map { it.id }.toSet()
}

/**
 * 工具包的存储：内置包 + 用户自建包。
 * 自建包存进配置的 JSON（跟自定义工具一样走 SettingsSource）。
 */
class PackStore(private val src: SettingsSource) {

    /** 用户自建的包（内置包不存这里）。 */
    @Volatile var custom: List<ToolPack> = emptyList()
        private set

    init { load() }

    fun load() {
        val raw = src.getString(Config.Keys.TOOL_PACKS, null)
        if (raw.isNullOrBlank()) {
            custom = emptyList()
            return
        }
        custom = runCatching {
            J.decodeFromJsonElement(ListSerializer(ToolPack.serializer()), J.parseToJsonElement(raw))
        }.getOrElse { emptyList() }
        // 内置 id 不允许被自建包占用（避免遮掉 core）
        custom = custom.filterNot { it.id in BuiltinPacks.ALL.map { p -> p.id } }
    }

    private fun persist() {
        val el = J.encodeToJsonElement(ListSerializer(ToolPack.serializer()), custom)
        src.putString(Config.Keys.TOOL_PACKS, el.toString())
    }

    /** 全部包：内置 + 「我的工具」+ 自建。customToolNames 用于动态生成「我的工具」。 */
    fun all(customToolNames: List<String> = emptyList()): List<ToolPack> =
        BuiltinPacks.ALL + listOf(BuiltinPacks.myTools(customToolNames)) + custom

    fun byId(id: String, customToolNames: List<String> = emptyList()): ToolPack? =
        all(customToolNames).firstOrNull { it.id == id }

    fun isBuiltin(id: String): Boolean =
        id in BuiltinPacks.ALL.map { it.id } || id == ToolPack.MY_TOOLS_ID

    fun add(pack: ToolPack): ToolPack {
        validate(pack)
        val created = pack.copy(
            id = pack.id.ifBlank { Tokens.newId().take(8) },
            builtin = false,
            core = false
        )
        if (isBuiltin(created.id)) throw ToolFailure("「${created.id}」是内置包的名字，换一个吧")
        if (custom.any { it.id == created.id }) throw ToolFailure("已经有一个叫「${created.id}」的包了")
        custom = custom + created
        persist()
        return created
    }

    fun update(pack: ToolPack): ToolPack {
        validate(pack)
        val existing = custom.firstOrNull { it.id == pack.id }
            ?: throw ToolFailure(
                if (isBuiltin(pack.id)) "内置包不能改，但可以用 manage_pack 新建一个自己的包"
                else "找不到包：${pack.id}"
            )
        val merged = pack.copy(id = existing.id, builtin = false, core = false)
        custom = custom.map { if (it.id == existing.id) merged else it }
        persist()
        return merged
    }

    fun remove(id: String): Boolean {
        if (isBuiltin(id)) throw ToolFailure("「$id」是内置包，不能删")
        val existed = custom.any { it.id == id }
        if (existed) {
            custom = custom.filterNot { it.id == id }
            persist()
        }
        return existed
    }

    private fun validate(pack: ToolPack) {
        if (pack.title.isBlank()) throw ToolFailure("包名不能为空")
        if (pack.title.length > 60) throw ToolFailure("包名太长（最多 60 字）")
        if (pack.description.length > 1200) throw ToolFailure("包说明太长（最多 1200 字）")
        if (pack.id.length > 48) throw ToolFailure("包 id 太长（最多 48 字）")
        if (!pack.id.matches(Regex("^[a-zA-Z0-9._\\-]*$"))) {
            throw ToolFailure("包 id 只能用字母、数字、点、横线和下划线")
        }
    }
}
