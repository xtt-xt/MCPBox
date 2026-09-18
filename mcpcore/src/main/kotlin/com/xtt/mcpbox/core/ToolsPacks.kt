// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 工具包的管理工具。
 *
 * 分成两类：
 *  - **查询/切换**（list_packs / activate_pack / deactivate_pack / reset_packs）
 *    免审批 —— 它们只改变「AI 能看到什么」，不扩大实际能力边界（真正拦人的是权限矩阵）。
 *    这几件事如果每次都要弹窗，AI 根本没法自己按需开包。
 *  - **建包/改包/删包**（manage_pack）走 `tools.manage` 权限，跟自定义工具一个待遇。
 */
object ToolsPacks {

    fun specs(packs: PackStore, profiles: ProfileStore): List<ToolSpec> = listOf(
        listPacks(profiles),
        activatePack(profiles),
        deactivatePack(profiles),
        resetPacks(profiles),
        managePack(packs, profiles)
    )

    // ------------------------------------------------------------ list_packs

    private fun listPacks(profiles: ProfileStore) = ToolSpec(
        name = "list_packs",
        title = "列出工具包",
        description = "列出所有工具包：每个包的用途说明、里面有哪些工具、当前是否已激活。" +
            "**你现在只看到基础包 + 已激活包里的工具**；要用别的工具，先在这里找到对应的包，" +
            "再用 activate_pack 打开。常驻的基础包不用激活。",
        perm = PermKey.SYSTEM,
        schema = Schema.obj(
            mapOf(
                "showTools" to Schema.bool("是否列出每个包里的工具名（默认 true）", true)
            )
        )
    ) { ctx ->
        ctx.guard(PermKey.SYSTEM, null, "列出工具包")
        ToolResult(renderPacks(ctx, ctx.profiles, showTools = ctx.args.boolOr("showTools", true)))
    }

    // -------------------------------------------------------- activate_pack

    private fun activatePack(profiles: ProfileStore) = ToolSpec(
        name = "activate_pack",
        title = "激活工具包",
        description = "激活一个工具包，包里的工具立刻出现在你的工具列表里（客户端可能需要刷新一次 tools/list）。" +
            "用 list_packs 查看所有可用的包。激活纯属「让工具看得见」，不改变任何权限。",
        perm = PermKey.SYSTEM,
        schema = Schema.obj(
            mapOf("pack" to Schema.str("要激活的包 id，例如 file.write、shell、memory")),
            listOf("pack")
        )
    ) { ctx ->
        val id = ctx.args.str("pack")?.trim().orEmpty()
        if (id.isEmpty()) ctx.fail("pack 不能为空")
        ctx.guard(PermKey.SYSTEM, null, "激活工具包 $id")

        val pack = ctx.packs.byId(id, ctx.customTools.tools.map { it.name })
            ?: ctx.fail("没有叫「$id」的包。用 list_packs 看看有哪些。")
        if (pack.tools.isEmpty()) {
            ToolResult("「${pack.title}」是空的，没有工具可以激活。")
        } else {
            val changed = ctx.profiles.activate(ctx.profile, id)
            ToolResult(
                buildString {
                    append(if (changed) "已激活" else "本来就已激活")
                    append("「").append(pack.title).append("」（").append(id).append("）。\n")
                    append("新增可见的工具 ").append(pack.tools.size).append(" 个：\n")
                    pack.tools.forEach { append("  · ").append(it).append('\n') }
                    append("\n注意：大多数 MCP 客户端**只在连接时拉一次工具列表**，之后不会再拉，")
                    append("服务端也无法通知它刷新。所以这些工具通常**要等客户端重新连接（或重启 App）后才能调用**。\n")
                    append("如果你现在就要用，可以直接试着按名字调用 —— 服务端允许，但客户端可能拦下来；")
                    append("被拦了就告诉用户「请重新连接 MCP 服务」，不要反复重试。\n")
                    append("当前会话：").append(ctx.profile)
                }.trimEnd()
            )
        }
    }

    // ------------------------------------------------------ deactivate_pack

    private fun deactivatePack(profiles: ProfileStore) = ToolSpec(
        name = "deactivate_pack",
        title = "停用工具包",
        description = "停用一个工具包，把它的工具从工具列表里收起来（省 token）。" +
            "基础包不能停用。注意：停用后这些工具不再出现在 tools/list 里。",
        perm = PermKey.SYSTEM,
        schema = Schema.obj(
            mapOf("pack" to Schema.str("要停用的包 id")),
            listOf("pack")
        )
    ) { ctx ->
        val id = ctx.args.str("pack")?.trim().orEmpty()
        if (id.isEmpty()) ctx.fail("pack 不能为空")
        if (id == ToolPack.CORE_ID) ctx.fail("基础包不能停用（工具包管理本身就在它里面）")
        ctx.guard(PermKey.SYSTEM, null, "停用工具包 $id")

        val pack = ctx.packs.byId(id, ctx.customTools.tools.map { it.name })
            ?: ctx.fail("没有叫「$id」的包。用 list_packs 看看有哪些。")
        val changed = ctx.profiles.deactivate(ctx.profile, id)
        ToolResult(
            if (changed) "已停用「${pack.title}」。它的 ${pack.tools.size} 个工具不再出现在工具列表里。"
            else "「${pack.title}」本来就没激活。"
        )
    }

    // ----------------------------------------------------------- reset_packs

    private fun resetPacks(profiles: ProfileStore) = ToolSpec(
        name = "reset_packs",
        title = "重置工具包",
        description = "把当前会话的工具包激活状态重置回默认（基础包 + 文件读取 + 记忆库）。" +
            "找不到该用哪个包、或者上一轮开太多包把 token 撑爆了，就用它清一下。",
        perm = PermKey.SYSTEM,
        schema = Schema.obj(emptyMap())
    ) { ctx ->
        ctx.guard(PermKey.SYSTEM, null, "重置工具包")
        ctx.profiles.reset(ctx.profile)
        ToolResult(renderPacks(ctx, ctx.profiles, showTools = false, header = "已重置回默认"))
    }

    // ----------------------------------------------------------- manage_pack

    private fun managePack(packs: PackStore, profiles: ProfileStore) = ToolSpec(
        name = "manage_pack",
        title = "管理工具包",
        description = "新建 / 修改 / 删除**自定义**工具包（内置包不能改，但可以照它新建一个自己的）。" +
            "包就是一组工具的名字，用来把工具分门别类、按需激活。" +
            "字段：id（只允许字母数字点横线下划线，留空自动生成）、title、description、tools（工具名数组）。" +
            "不确定有哪些工具名，可以先 list_packs 看内置包。",
        perm = PermKey.TOOLS,
        schema = Schema.obj(
            mapOf(
                "action" to Schema.str("要做什么", "create", listOf("create", "update", "delete")),
                "id" to Schema.str("包的 id（update / delete 时必填；create 留空自动生成）"),
                "title" to Schema.str("包名（中文也行）"),
                "description" to Schema.str("给 AI 看的说明：写清什么时候该激活这个包"),
                "tools" to jo(
                    "type" to "array",
                    "description" to "工具名列表",
                    "items" to Schema.str("工具名，例如 read_file")
                )
            ),
            listOf("action")
        )
    ) { ctx ->
        val action = ctx.args.str("action")?.trim()?.lowercase().orEmpty()
        val id = ctx.args.str("id")?.trim().orEmpty()
        ctx.guard(PermKey.TOOLS, null, ("管理工具包：$action $id").trim())

        when (action) {
            "create", "update" -> {
                val title = ctx.args.str("title")?.trim().orEmpty()
                val desc = ctx.args.str("description")?.trim().orEmpty()
                val tools = ctx.args.arr("tools")?.mapNotNull { el ->
                    (el as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }
                }.orEmpty()
                if (title.isBlank()) ctx.fail("title 不能为空")
                if (tools.isEmpty()) ctx.fail("tools 不能为空，至少放一个工具名")
                if (action == "update" && id.isEmpty()) ctx.fail("update 需要给 id")

                // 名字不存在也允许（可能是工具被禁用了），只是提醒一下
                val known = ctx.allToolNames
                val unknown = tools.filterNot { it in known }

                val saved = if (action == "create") {
                    packs.add(ToolPack(id = id, title = title, description = desc, tools = tools))
                } else {
                    packs.update(ToolPack(id = id, title = title, description = desc, tools = tools))
                }
                ToolResult(
                    buildString {
                        append(if (action == "create") "已新建" else "已更新")
                        append("工具包「").append(saved.title).append("」\n")
                        append("  id：").append(saved.id).append('\n')
                        append("  工具 ").append(saved.tools.size).append(" 个：")
                        append(saved.tools.joinToString("、")).append('\n')
                        if (unknown.isNotEmpty()) {
                            append("\n注意：这些名字当前不在工具表里（可能拼错了，或者被禁用了）：")
                            append(unknown.joinToString("、")).append('\n')
                        }
                        append("\n用 activate_pack pack=\"").append(saved.id).append("\" 就能激活它。")
                    }.trimEnd()
                )
            }

            "delete" -> {
                if (id.isEmpty()) ctx.fail("delete 需要给 id")
                if (packs.isBuiltin(id)) ctx.fail("「$id」是内置包，不能删")
                val ok = packs.remove(id)
                if (ok) profiles.deactivate(ctx.profile, id)
                ToolResult(if (ok) "已删除工具包「$id」。" else "没有找到自定义包「$id」。")
            }

            else -> ctx.fail("action 只能是 create / update / delete")
        }
    }

    // ---------------------------------------------------------------- 辅助

    /** 某个 profile 下真正可见的工具名（core + 已激活包，去掉禁用项）。 */
    fun visibleNames(
        packs: PackStore,
        profiles: ProfileStore,
        profile: String,
        customToolNames: List<String>,
        disabled: Set<String>,
        memoryEnabled: Boolean,
        aiPackControl: Boolean = true
    ): Set<String> {
        val active = profiles.active(profile)
        val names = LinkedHashSet<String>()
        packs.all(customToolNames).forEach { p ->
            if (p.core || p.id in active) names.addAll(p.tools)
        }
        // 记忆库总开关关掉时，即使 memory 包激活着也不显示
        if (!memoryEnabled) names.removeAll(BuiltinPacks.MEMORY.tools.toSet())
        // 不给 AI 开关包的权限时，包管理工具也一并收起（省约 520 token，
        // 而且它们本来就调不到新工具，留着只会误导 AI 白试）
        if (!aiPackControl) names.removeAll(BuiltinPacks.PACK_TOOLS.toSet())
        names.removeAll(disabled)
        return names
    }

    private fun renderPacks(
        ctx: CallContext,
        profiles: ProfileStore,
        showTools: Boolean,
        header: String? = null
    ): String {
        val customNames = ctx.customTools.tools.map { it.name }
        val active = profiles.active(ctx.profile)
        val all = ctx.packs.all(customNames)
        val visible = visibleNames(
            packs = ctx.packs,
            profiles = profiles,
            profile = ctx.profile,
            customToolNames = customNames,
            disabled = emptySet(),
            memoryEnabled = ctx.config.memoryEnabled,
            aiPackControl = true
        )

        return buildString {
            if (header != null) append(header).append('\n')
            append("工具包 —— 当前会话「").append(ctx.profile).append("」\n")
            append("现在能看到 ").append(visible.size).append(" 个工具\n")

            val on = all.filter { it.core || it.id in active }
            val off = all.filterNot { it.core || it.id in active }

            append("\n● 已激活（").append(on.size).append("）\n")
            on.forEach { append(one(it, showTools)) }
            if (off.isNotEmpty()) {
                append("\n○ 未激活（").append(off.size).append("）—— 需要时用 activate_pack 打开\n")
                off.forEach { append(one(it, showTools = false)) }
            }
            append("\n提示：包决定「工具列表里能看见什么」。停用只是从列表里收起来；")
            append("要在客户端生效，需要让它重新连接（或重启 App）。")
        }.trimEnd()
    }

    private fun one(p: ToolPack, showTools: Boolean): String = buildString {
        append("  · ").append(p.title).append("（").append(p.id).append("）")
        if (p.core) append("  [常驻]")
        append("\n      ").append(p.description.ifBlank { "（没有说明）" }).append('\n')
        if (showTools && p.tools.isNotEmpty()) {
            append("      工具：").append(p.tools.joinToString("、")).append('\n')
        }
    }
}
