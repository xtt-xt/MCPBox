// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.xml.sax.InputSource
import java.io.File
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * UI 自动化：把手机屏幕当接口用。
 *
 * 所有动作最终都是几条系统命令（`screencap` / `uiautomator dump` / `input`），
 * 但都要求 **shell 身份**（Shell / Root / Shizuku），应用自身 UID 做不了 ——
 * 所以这里统一要求特权后端，拿不到就直接报错说明原因，而不是静默失败。
 *
 * 临时文件统一放在 `<主根目录>/.MCPBox/ui/` 下：shell 能写，AI 也能在文件页里看到。
 */
object ToolsUi {

    private val runner = ShellRunner()

    /** 临时目录名（跟回收站 .MCPBox/trash 放在一起）。 */
    private const val TMP_DIR = ".MCPBox/ui"
    private const val SHOT = "screen.png"

    /** 控件树落在 /data/local/tmp：shell / root 都好写，也不占用户存储。 */
    private const val DUMP_REMOTE = "/data/local/tmp/mcpbox_ui.xml"

    // ------------------------------------------------------------ 屏幕结构模型

    /** uiautomator dump 出来的一个节点。 */
    data class UiNode(
        val text: String,
        val desc: String,
        val id: String,
        val cls: String,
        val pkg: String,
        val left: Int, val top: Int, val right: Int, val bottom: Int,
        val clickable: Boolean,
        val longClickable: Boolean,
        val scrollable: Boolean,
        val checkable: Boolean,
        val checked: Boolean,
        val editable: Boolean,
        val enabled: Boolean,
        val focused: Boolean,
        val selected: Boolean
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
        val centerX: Int get() = (left + right) / 2
        val centerY: Int get() = (top + bottom) / 2
        val visible: Boolean get() = width > 0 && height > 0

        /** 能看见的文字（text 优先，其次 content-desc）。 */
        val label: String get() = text.ifBlank { desc }

        val shortClass: String get() = cls.substringAfterLast('.')

        /** 能操作的元素。 */
        val interactive: Boolean
            get() = clickable || longClickable || scrollable || checkable || editable

        fun kind(): String = when {
            editable -> "输入框"
            clickable -> "可点"
            scrollable -> "可滚动"
            checkable -> "可勾选"
            enabled -> "文本"
            else -> "不可用"
        }

        /** 给 AI 看的一行（紧凑但信息全）。 */
        fun line(index: Int): String = buildString {
            append('[').append(index).append("] ").append(kind()).append(' ')
            if (label.isNotBlank()) append('"').append(label.replace('\n', ' ').take(60)).append("\" ")
            if (id.isNotBlank()) append(id).append(' ')
            append('(').append(centerX).append(',').append(centerY).append(')')
            append(L(" 范围[")).append(left).append(',').append(top).append("][")
                .append(right).append(',').append(bottom).append(']')
            if (cls.isNotBlank()) append(' ').append(shortClass)
            if (checked) append(L(" 已勾选"))
            if (!enabled) append(L(" 已禁用"))
        }

        fun toJson(index: Int): JsonObject = jo(
            "i" to index,
            "text" to text.ifBlank { null },
            "desc" to desc.ifBlank { null },
            "id" to id.ifBlank { null },
            "class" to cls.ifBlank { null },
            "package" to pkg.ifBlank { null },
            "center" to listOf(centerX, centerY),
            "bounds" to listOf(left, top, right, bottom),
            "clickable" to clickable,
            "longClickable" to longClickable,
            "scrollable" to scrollable,
            "editable" to editable,
            "checked" to (if (checkable) checked else null),
            "enabled" to enabled
        )
    }

    /** 一次 dump 的结果。 */
    private class Snapshot(
        val nodes: List<UiNode>,
        val rotation: Int,
        val screenWidth: Int,
        val screenHeight: Int
    )

    // ------------------------------------------------------------------ 工具清单

    fun specs(): List<ToolSpec> = listOf(
        screenshot(),
        dump(),
        tap(),
        swipe(),
        input(),
        key(),
        launch(),
        waitFor()
    )

    private const val BACKEND_DESC = "执行后端（auto / shizuku / root）"

    // ---------------------------------------------------------------- 基础设施

    /**
     * 挑一个**特权**后端（Shizuku / Root）。
     * 应用沙箱后端做不了截屏和注入事件，所以这里不接受它。
     *
     * 默认顺序是 **root 优先**：实测 `uiautomator`（读界面结构）在 Shizuku 后端下会
     * 静默失败（rc=0、无输出、无文件 —— app_process 在那个上下文里起不来），
     * 只有 root 能稳定跑到。显式传 `backend` 参数时按用户说的来。
     */
    private fun pickLauncher(ctx: CallContext, pref: String): CommandLauncher {
        val avail = ShellBackends.available().filter { it.id != "app" }
        if (avail.isEmpty()) {
            ctx.fail(
                L("UI 自动化需要 Root 或 Shizuku 身份：截屏、读取界面结构、模拟点击都要系统权限，\n") +
                    L("应用自身 UID 做不到。请先打开 Shizuku 授权（App 的「终端」页），或者用已 root 的设备。")
            )
        }
        if (pref != "auto") avail.firstOrNull { it.id == pref }?.let { return it }
        avail.firstOrNull { it.id == "root" }?.let { return it }
        ctx.config.shellPreference.split(',').forEach { id ->
            avail.firstOrNull { it.id == id.trim() }?.let { return it }
        }
        return avail.first()
    }

    /** 执行一条 UI 命令（带审批 + 镜像到终端页 + 写日志）。 */
    private fun exec(
        ctx: CallContext,
        launcher: CommandLauncher,
        command: String,
        summary: String,
        detail: String? = null,
        timeoutMs: Long = 30_000
    ): ShellResult {
        ctx.guard(
            perm = PermKey.UI,
            path = null,
            summary = summary,
            detail = buildString {
                append(L("后端：%s（%s）").format(L(launcher.label), launcher.uidLabel))
                if (!detail.isNullOrBlank()) append('\n').append(detail)
            },
            command = command,
            backend = launcher.id
        )
        ShellMirror.emit("\n[UI] \$ $command\n")
        val result = runner.run(launcher, command, null, timeoutMs)
        ShellMirror.emit(
            buildString {
                append(result.stdout)
                if (result.stderr.isNotBlank()) append(result.stderr)
                if (result.timedOut) append("\n" + L("[超时，已中断]") + "\n")
            }
        )
        ctx.log.add(
            LogKind.REQUEST, tool = ctx.tool, client = ctx.client, ok = result.ok,
            message = (
                if (result.ok) L("UI 动作成功")
                else L("UI 动作失败（退出码 %s）").format(result.exitCode)
            ) + "：" + summary,
            durationMs = result.durationMs
        )
        return result
    }

    private fun tmpDir(ctx: CallContext): File = File(ctx.sandbox.primaryRoot(), TMP_DIR)

    private fun tmpFile(ctx: CallContext, name: String): File {
        val dir = tmpDir(ctx)
        ctx.bridge.mkdirs(dir)
        return File(dir, name)
    }

    /** `wm size` → 屏幕坐标空间（input 用的就是这套坐标）。 */
    private fun screenSize(launcher: CommandLauncher): Pair<Int, Int> {
        val out = runner.run(launcher, "wm size", null, 15_000).stdout
        // 有 Override size 就用它（改过分辨率的机器上，input 认的是 Override）
        val all = Regex("""(\w+) size:\s*(\d+)x(\d+)""").findAll(out).toList()
        val pick = all.lastOrNull { it.groupValues[1] == "Physical" } ?: all.lastOrNull()
        return pick?.let { it.groupValues[2].toInt() to it.groupValues[3].toInt() } ?: (0 to 0)
    }

    /** 当前前台应用（包名 + activity）。 */
    private fun foreground(launcher: CommandLauncher): String {
        val out = runner.run(
            launcher,
            "dumpsys window displays 2>/dev/null | grep -m1 -E 'mCurrentFocus|mFocusedApp'",
            null, 15_000
        ).stdout
        val m = Regex("""([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)""").find(out) ?: return out.trim()
        return m.value
    }

    /** 解析 uiautomator dump 的 XML。 */
    private fun parseSnapshot(xml: String, w: Int, h: Int): Snapshot {
        val factory = DocumentBuilderFactory.newInstance().apply {
            // 只解析本地字符串，关掉外部实体
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val rotation = doc.documentElement?.getAttribute("rotation")?.toIntOrNull() ?: 0
        val list = ArrayList<UiNode>()
        val elements = doc.getElementsByTagName("node")
        for (i in 0 until elements.length) {
            val e = elements.item(i)
            fun attr(name: String): String = e.attributes?.getNamedItem(name)?.nodeValue ?: ""
            fun flag(name: String): Boolean = attr(name) == "true"
            val bounds = attr("bounds")
            val m = BOUNDS.find(bounds) ?: continue
            val node = UiNode(
                text = attr("text"),
                desc = attr("content-desc"),
                id = attr("resource-id"),
                cls = attr("class"),
                pkg = attr("package"),
                left = m.groupValues[1].toIntOrNull() ?: 0,
                top = m.groupValues[2].toIntOrNull() ?: 0,
                right = m.groupValues[3].toIntOrNull() ?: 0,
                bottom = m.groupValues[4].toIntOrNull() ?: 0,
                clickable = flag("clickable"),
                longClickable = flag("long-clickable"),
                scrollable = flag("scrollable"),
                checkable = flag("checkable"),
                checked = flag("checked"),
                editable = EDITABLE_HINT.containsMatchIn(attr("class")),
                enabled = attr("enabled").ifBlank { "true" } == "true",
                focused = flag("focused"),
                selected = flag("selected")
            )
            list.add(node)
        }
        return Snapshot(list, rotation, w, h)
    }

    private val BOUNDS = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")

    /** 能接收文字的控件类型。 */
    private val EDITABLE_HINT = Regex("""(EditText|AutoCompleteTextView|SearchView|MultiAutoCompleteTextView)""")

    /** 抓一次屏幕结构（自动挑 dump 参数、重试、读回 XML、解析）。 */
    private fun snapshot(ctx: CallContext, launcher: CommandLauncher): Snapshot {
        val xml = dumpXml(ctx, launcher)
        val (w, h) = screenSize(launcher)
        return parseSnapshot(xml, w, h)
    }

    /** uiautomator 失败时，把它的原话翻译成能照着做的建议。 */
    private fun dumpFailureHelp(err: String): String {
        val idle = err.contains("idle state", ignoreCase = true)
        val head = if (err.isBlank()) "uiautomator 没有产出任何内容。" else "uiautomator 报错：\n  $err\n"
        return head + when {
            idle ->
                "\n**原因**：当前前台界面上有**停不下来的动画**（比如聊天页的「正在输入 / 思考中」转圈、播放器进度），" +
                    "uiautomator 会一直等界面静止，等不到就放弃。这是它的老毛病，没有开关能跳过。\n\n" +
                    "可以这样做：\n" +
                    "  · 用 ui_screenshot 看一眼画面，直接用坐标 ui_tap（最常用）\n" +
                    "  · 把界面切到静止的一页（比如返回上一级）再 ui_dump\n" +
                    "  · 在系统「开发者选项」里把动画缩放关掉（只对系统动画有效，App 自绘的无效）"

            else ->
                "\n可能的原因：\n" +
                    "  · 刚刚连续 dump 过（uiautomator 不能短时间并发，隔几秒再试）\n" +
                    "  · 当前是自绘界面（游戏、视频、部分系统页面），根本没有控件树 —— " +
                    "这种只能用 ui_screenshot + ui_tap 走坐标"
        }
    }

    /**
     * dump 落文件再读回来。
     * 会在**所有可用的特权后端**上试（某个后端跑不动 uiautomator 时自动换一个），
     * 界面停不下来（idle state）就直接放弃 —— 那种情况重试没有意义。
     */
    private fun dumpXml(ctx: CallContext, launcher: CommandLauncher): String {
        val others = ShellBackends.available().filter { it.id != "app" && it.id != launcher.id }
        var lastError = ""
        var sawIdle = false
        outer@ for (l in listOf(launcher) + others) {
            // 先不带 --compressed：实测某些 ROM 上 --compressed 反而更容易失败
            for (flag in listOf("", "--compressed ")) {
                val cmd = "uiautomator dump $flag$DUMP_REMOTE 2>&1; cat $DUMP_REMOTE 2>/dev/null"
                val res = runner.run(l, cmd, null, 25_000, maxOutput = 16 * 1024 * 1024)
                val text = res.stdout
                val idx = text.indexOf("<hierarchy")
                if (idx >= 0) {
                    if (l.id != launcher.id) {
                        ShellMirror.emit("\n" + L("[UI] 换用「%s」后端读到了界面结构").format(L(l.label)) + "\n")
                        ctx.log.add(
                            LogKind.SYSTEM, tool = ctx.tool, client = ctx.client,
                            message = L("UI 后端回退：%s 跑不动 uiautomator，改用 %s")
                                .format(L(launcher.label), L(l.label))
                        )
                    }
                    val start = text.lastIndexOf("<?xml", idx).takeIf { it >= 0 } ?: idx
                    return text.substring(start)
                }
                val err = text.lineSequence()
                    .filter { it.isNotBlank() && !it.startsWith("UI hierchary") }
                    .joinToString(" ").trim().take(300)
                if (err.isNotEmpty()) {
                    lastError = err
                    if (err.contains("idle state", ignoreCase = true)) sawIdle = true
                }
            }
            if (sawIdle) break@outer
            Thread.sleep(300)
        }
        if (lastError.isNotEmpty()) ShellMirror.emit("\n[UI] 读取界面结构失败：$lastError\n")
        ctx.fail(dumpFailureHelp(lastError))
    }

    /** 过滤出要展示的节点。 */
    private fun pickNodes(
        snap: Snapshot,
        onlyInteractive: Boolean,
        filter: String?,
        maxNodes: Int
    ): List<UiNode> {
        var seq = snap.nodes.asSequence().filter { it.visible || it.interactive }
        if (onlyInteractive) seq = seq.filter { it.interactive && it.enabled && (it.width > 0 || it.clickable) }
        if (!filter.isNullOrBlank()) {
            val f = filter.lowercase()
            seq = seq.filter { n ->
                n.text.lowercase().contains(f) || n.desc.lowercase().contains(f) || n.id.lowercase().contains(f)
            }
        }
        return seq.take(maxNodes).toList()
    }

    /** 按文字 / 描述 / id 找一个节点。 */
    private fun locate(
        ctx: CallContext,
        nodes: List<UiNode>,
        text: String?,
        desc: String?,
        id: String?,
        index: Int
    ): UiNode {
        val key = text ?: desc ?: id ?: ctx.fail(L("没有给出定位条件"))
        val field: (UiNode) -> String = when {
            text != null -> { n -> n.text }
            desc != null -> { n -> n.desc }
            else -> { n -> n.id }
        }
        val target = key.trim()
        val all = nodes.filter { it.visible }
        // 精确优先，其次包含
        val exact = all.filter { field(it).equals(target, ignoreCase = true) }
        val fuzzy = all.filter { field(it).contains(target, ignoreCase = true) }
        val hits = exact.ifEmpty { fuzzy }.filter { it.interactive || it.clickable }.ifEmpty {
            exact.ifEmpty { fuzzy }
        }
        if (hits.isEmpty()) {
            val near = all.filter { it.label.isNotBlank() }
                .take(12).joinToString("\n") { "  ・" + it.label.take(30) }
            ctx.fail(
                L("没找到「%s」。当前屏幕上能看到的文字：\n").format(target) +
                    near.ifBlank { L("  （没有可读文字，可能是自绘界面或还没加载完）") } +
                    L("\n\n可以先用 ui_dump 看看结构，或者直接用 ui_tap 传 x / y 坐标。")
            )
        }
        if (hits.size > 1 && index < 0) {
            val lines = hits.take(8).mapIndexed { i, n -> "  [$i] ${n.line(i)}" }.joinToString("\n")
            ctx.fail(
                L("「%s」匹配到 %s 个元素，请用 index 指定要哪一个：\n%s")
                    .format(target, hits.size, lines)
            )
        }
        return hits.getOrElse(index.coerceAtLeast(0)) { hits.first() }
    }

    /** ASCII → 定长数字码；供 input text 转义用。 */
    private fun asciiOnly(s: String): Boolean = s.all { it.code in 32..126 }

    // ----------------------------------------------------------- ui_screenshot

    private fun screenshot() = ToolSpec(
        name = "ui_screenshot",
        title = "截屏",
        description = "截一张当前屏幕的图，直接把图片给模型看（同时说明屏幕坐标空间，方便配合 ui_tap 点击）。" +
            "需要 Root 或 Shizuku。看界面长什么样、确认上一步操作的结果时用它。",
        perm = PermKey.UI,
        schema = Schema.obj(
            mapOf(
                "path" to Schema.str("保存到哪个文件（可选）。默认存到 主根目录/.MCPBox/ui/screen.png"),
                "maxBytes" to Schema.int("图片大小上限（字节）", 8_000_000, 10_000, 32_000_000),
                "backend" to Schema.str(BACKEND_DESC, "auto", listOf("auto", "shizuku", "root"))
            )
        )
    ) { ctx ->
        val launcher = pickLauncher(ctx, ctx.args.strOr("backend", "auto"))
        val target = ctx.args.str("path")?.takeIf { it.isNotBlank() }
            ?.let { ctx.path("path") } ?: tmpFile(ctx, SHOT)
        ctx.bridge.mkdirs(target.parentFile ?: tmpDir(ctx))
        val quoted = FileBridge.shellQuote(target.path)
        val res = exec(
            ctx, launcher,
            "screencap -p $quoted",
            summary = "截屏 → ${target.name}",
            detail = target.path,
            timeoutMs = 30_000
        )
        if (!res.ok) {
            ctx.fail(
                L("截屏失败（退出码 %s）：\n").format(res.exitCode) +
                    (res.stderr + res.stdout).trim().take(400)
            )
        }
        val maxBytes = ctx.args.intOr("maxBytes", 8_000_000)
        val bytes = ctx.bridge.readBytes(target, maxBytes.toLong())
            ?: ctx.fail(
                L("截屏命令执行了，但读不到图片：%s\n（应用自己没有权限读它，而且没有可用的 root / Shizuku）")
                    .format(target.path)
            )
        if (bytes.isEmpty()) ctx.fail(L("截出来的图片是空的：%s").format(target.path))
        val (sw, sh) = screenSize(launcher)
        val (pw, ph) = pngSize(bytes) ?: (0 to 0)
        val scale = if (pw > 0 && sw > 0) sw.toDouble() / pw else 1.0
        val b64 = java.util.Base64.getEncoder().encodeToString(bytes)
        val note = buildString {
            append(L("截屏：%s\n").format(target.path))
            append(L("图片尺寸：%sx%s　屏幕坐标空间：%sx%s\n").format(pw, ph, sw, sh))
            if (scale != 1.0 && sw > 0) {
                append(
                    L("注意：图片被缩放过了，点坐标要按屏幕空间算（乘 %s），ui_dump 给的坐标已经是屏幕空间。\n")
                        .format("%.3f".format(scale))
                )
            }
            append(L("前端：")).append(foreground(launcher))
        }
        ToolResult(
            text = note,
            extraContent = listOf(jo("type" to "image", "data" to b64, "mimeType" to "image/png"))
        )
    }

    /** 读 PNG 头拿尺寸，用于判断截图有没有被缩放。 */
    private fun pngSize(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 24) return null
        if (bytes[0] != 0x89.toByte() || bytes[1] != 'P'.code.toByte() ||
            bytes[2] != 'N'.code.toByte() || bytes[3] != 'G'.code.toByte()
        ) return null
        fun be(o: Int): Int =
            ((bytes[o].toInt() and 0xFF) shl 24) or ((bytes[o + 1].toInt() and 0xFF) shl 16) or
                ((bytes[o + 2].toInt() and 0xFF) shl 8) or (bytes[o + 3].toInt() and 0xFF)
        return be(16) to be(20)
    }

    // ---------------------------------------------------------------- ui_dump

    private fun dump() = ToolSpec(
        name = "ui_dump",
        title = "读取界面结构",
        description = "读出当前屏幕上的控件树（哪些元素能点、在哪里、什么文字），" +
            "返回每个元素的中心坐标，可以直接拿去 ui_tap / ui_swipe。" +
            "比截图更适合让 AI 决定「点哪里」，也省 token。需要 Root 或 Shizuku。",
        perm = PermKey.UI,
        schema = Schema.obj(
            mapOf(
                "format" to Schema.str("输出格式", "text", listOf("text", "json")),
                "onlyInteractive" to Schema.bool("只列可交互元素（可点 / 可输入 / 可滚动），默认 true", true),
                "filter" to Schema.str("只显示文字 / 描述 / id 里包含这个词的元素"),
                "maxNodes" to Schema.int("最多返回多少个元素", 120, 1, 1000),
                "raw" to Schema.bool("返回 uiautomator 的原始 XML（调试用，会被截断）", false),
                "backend" to Schema.str(BACKEND_DESC, "auto", listOf("auto", "shizuku", "root"))
            )
        )
    ) { ctx ->
        val launcher = pickLauncher(ctx, ctx.args.strOr("backend", "auto"))
        val snap = snapshot(ctx, launcher)
        ctx.guard(PermKey.UI, null, L("读取界面结构"), L("后端：%s").format(L(launcher.label)))

        if (ctx.args.boolOr("raw", false)) {
            val xml = dumpXml(ctx, launcher)
            return@ToolSpec ToolResult(
                L("原始 XML（%s 字符，已截断到 20000）\n----\n").format(xml.length) +
                    xml.take(20_000)
            )
        }

        val nodes = pickNodes(
            snap,
            ctx.args.boolOr("onlyInteractive", true),
            ctx.args.str("filter"),
            ctx.args.intOr("maxNodes", 120)
        )
        val fg = foreground(launcher)
        val head = buildString {
            append(L("屏幕 %sx%s（rotation=%s）").format(snap.screenWidth, snap.screenHeight, snap.rotation))
            append(L(" · 前台 %s · 结构里共 %s 个节点，下面是 %s 个\n")
                .format(fg, snap.nodes.size, nodes.size))
        }

        if (ctx.args.strOr("format", "text") == "json") {
            return@ToolSpec ToolResult(
                head + L("（坐标是屏幕空间，可直接用于 ui_tap）\n") +
                    JsonArray(nodes.mapIndexed { i, n -> n.toJson(i) }).toString()
            )
        }

        if (nodes.isEmpty()) {
            return@ToolSpec ToolResult(
                head + L("（没有匹配的可交互元素）\n") +
                    L("可能是：界面还在加载 / 是自绘界面（游戏、视频）。可以用 ui_screenshot 看一眼，") +
                    L("或者用 onlyInteractive=false 看看全部节点。")
            )
        }
        val body = nodes.mapIndexed { i, n -> n.line(i) }.joinToString("\n")
        ToolResult(
            head + L("格式：[序号] 类型 \"文字\" id (中心x,中心y) 范围[左,上][右,下] 类名\n") +
                L("用法：ui_tap 传 x/y 点坐标，或者传 text / desc / id 让我自动找；") +
                L("滚动用 ui_swipe 的 direction。\n----\n") + body
        )
    }

    // ----------------------------------------------------------------- ui_tap

    private fun tap() = ToolSpec(
        name = "ui_tap",
        title = "点击屏幕",
        description = "点一下屏幕上的某个位置。可以给坐标（x / y），" +
            "也可以给文字 / 描述 / id 让它自己去找元素（会先读一次界面结构），" +
            "匹配到多个时返回候选列表，再用 index 指定。需要 Root 或 Shizuku。",
        perm = PermKey.UI,
        schema = Schema.obj(
            mapOf(
                "x" to Schema.int("横坐标（和 y 一起用时按坐标点）", null, 0, 20_000),
                "y" to Schema.int("纵坐标", null, 0, 20_000),
                "text" to Schema.str("按元素文字定位，如：发送、确定"),
                "desc" to Schema.str("按元素的 content-desc（无障碍描述）定位"),
                "id" to Schema.str("按 resource-id 定位，如：com.x:id/btn"),
                "index" to Schema.int("匹配到多个时选第几个（从 0 开始）", -1, -1, 200),
                "longPress" to Schema.bool("长按（约 800ms）", false),
                "backend" to Schema.str(BACKEND_DESC, "auto", listOf("auto", "shizuku", "root"))
            )
        )
    ) { ctx ->
        val launcher = pickLauncher(ctx, ctx.args.strOr("backend", "auto"))
        val x = ctx.args.intOrNull("x")
        val y = ctx.args.intOrNull("y")
        val longPress = ctx.args.boolOr("longPress", false)

        var note: String
        val px: Int
        val py: Int
        if (x != null && y != null) {
            px = x; py = y
            note = "按坐标 ($px, $py)"
        } else {
            val snap = snapshot(ctx, launcher)
            val node = locate(
                ctx, snap.nodes,
                ctx.args.str("text"), ctx.args.str("desc"), ctx.args.str("id"),
                ctx.args.intOr("index", -1)
            )
            px = node.centerX; py = node.centerY
            note = L("定位到「%s」→ 点 (%s, %s)")
                .format(node.label.ifBlank { node.shortClass }, px, py)
        }

        val cmd = if (longPress) {
            "input swipe $px $py $px $py 800"
        } else {
            "input tap $px $py"
        }
        val res = exec(
            ctx, launcher, cmd,
            summary = (if (longPress) L("长按 ") else L("点击 ")) + note,
            timeoutMs = 20_000
        )
        if (!res.ok) {
            ctx.fail(
                L("点击命令失败（退出码 %s）：\n").format(res.exitCode) +
                    (res.stderr + res.stdout).trim().take(400)
            )
        }
        ToolResult(L("%s\n（已通过 %s 执行：%s）").format(note, L(launcher.label), cmd))
    }

    // --------------------------------------------------------------- ui_swipe

    private fun swipe() = ToolSpec(
        name = "ui_swipe",
        title = "滑动 / 滚动",
        description = "滑动屏幕：可以给方向（up / down / left / right，表示「内容往哪边滚」），" +
            "也可以给起止坐标。翻页、滚动列表、下拉刷新都用它。需要 Root 或 Shizuku。",
        perm = PermKey.UI,
        schema = Schema.obj(
            mapOf(
                "direction" to Schema.str(
                    "方向：up=内容上滚（手指从下往上滑，看后面的内容）, down=回滚, left / right=左右滑",
                    null, listOf("up", "down", "left", "right")
                ),
                "distance" to Schema.int("滑动距离（像素），默认屏幕的三分之一", 0, 0, 20_000),
                "fromX" to Schema.int("起点 x（默认屏幕中心）", null, 0, 20_000),
                "fromY" to Schema.int("起点 y（默认屏幕中心）", null, 0, 20_000),
                "x1" to Schema.int("起点 x（给了就用坐标模式）", null, 0, 20_000),
                "y1" to Schema.int("起点 y", null, 0, 20_000),
                "x2" to Schema.int("终点 x", null, 0, 20_000),
                "y2" to Schema.int("终点 y", null, 0, 20_000),
                "durationMs" to Schema.int("滑动耗时（毫秒），越大越慢", 300, 30, 5_000),
                "repeat" to Schema.int("重复几次（连续滚动时有用）", 1, 1, 30),
                "backend" to Schema.str(BACKEND_DESC, "auto", listOf("auto", "shizuku", "root"))
            )
        )
    ) { ctx ->
        val launcher = pickLauncher(ctx, ctx.args.strOr("backend", "auto"))
        val duration = ctx.args.intOr("durationMs", 300)
        val repeat = ctx.args.intOr("repeat", 1)

        val x1 = ctx.args.intOrNull("x1")
        val y1 = ctx.args.intOrNull("y1")
        val x2 = ctx.args.intOrNull("x2")
        val y2 = ctx.args.intOrNull("y2")

        val (w, h) = screenSize(launcher)
        val fromX = ctx.args.intOrNull("fromX") ?: (w / 2)
        val fromY = ctx.args.intOrNull("fromY") ?: (h / 2)
        val dist = ctx.args.intOr("distance", 0).takeIf { it > 0 } ?: (if (h > 0) h / 3 else 400)

        val sx: Int
        val sy: Int
        val ex: Int
        val ey: Int
        val label: String
        if (x1 != null && y1 != null && x2 != null && y2 != null) {
            sx = x1; sy = y1; ex = x2; ey = y2
            label = L("从 (%s,%s) 滑到 (%s,%s)").format(sx, sy, ex, ey)
        } else {
            when (ctx.args.str("direction")?.lowercase()) {
                "up" -> {
                    sx = fromX; sy = fromY; ex = fromX; ey = fromY - dist
                    label = L("内容上滚 %s px").format(dist)
                }
                "down" -> {
                    sx = fromX; sy = fromY; ex = fromX; ey = fromY + dist
                    label = L("内容下滚 %s px").format(dist)
                }
                "left" -> {
                    sx = fromX; sy = fromY; ex = fromX - dist; ey = fromY
                    label = L("向左滑 %s px").format(dist)
                }
                "right" -> {
                    sx = fromX; sy = fromY; ex = fromX + dist; ey = fromY
                    label = L("向右滑 %s px").format(dist)
                }
                else -> ctx.fail(L("请给 direction（up / down / left / right），或者给全 x1 y1 x2 y2 四个坐标"))
            }
        }

        val cmd = buildString {
            repeat(repeat.coerceIn(1, 30)) {
                if (isNotEmpty()) append("; ")
                append("input swipe $sx $sy $ex $ey $duration")
            }
        }
        val res = exec(
            ctx, launcher, cmd,
            summary = L("滑动：%s").format(label),
            timeoutMs = 20_000 + duration.toLong() * repeat
        )
        if (!res.ok) {
            ctx.fail(
                L("滑动命令失败（退出码 %s）：\n").format(res.exitCode) +
                    (res.stderr + res.stdout).trim().take(400)
            )
        }
        ToolResult(L("%s（重复 %s 次，每个 %sms）").format(label, repeat, duration))
    }

    // --------------------------------------------------------------- ui_input

    private fun input() = ToolSpec(
        name = "ui_input",
        title = "输入文字",
        description = "往当前焦点输入框里打字。英文数字直接键入；**中文等非 ASCII 字符**会自动改成" +
            "「写剪贴板 + 模拟粘贴」的方式（所以要先点一下输入框让它获得焦点，用 ui_tap）。" +
            "需要 Root 或 Shizuku。",
        perm = PermKey.UI,
        schema = Schema.obj(
            mapOf(
                "text" to Schema.str("要输入的文字（支持中文）"),
                "clear" to Schema.bool("先清空输入框（先按 80 次退格，够清掉一般长度的内容）", false),
                "paste" to Schema.bool("强制走剪贴板粘贴（默认只对非 ASCII 自动用）", false),
                "submit" to Schema.bool("输入完再按一次回车", false),
                "backend" to Schema.str(BACKEND_DESC, "auto", listOf("auto", "shizuku", "root"))
            ),
            listOf("text")
        )
    ) { ctx ->
        val launcher = pickLauncher(ctx, ctx.args.strOr("backend", "auto"))
        val text = ctx.args.str("text") ?: ctx.fail(L("缺少 text"))
        if (text.isEmpty()) ctx.fail(L("text 不能为空"))

        val cmds = ArrayList<String>()
        if (ctx.args.boolOr("clear", false)) {
            cmds.add("input keyevent 123")                       // MOVE_END
            cmds.add("input keyevent " + List(80) { "67" }.joinToString(" "))  // DEL × 80
        }

        val usePaste = ctx.args.boolOr("paste", false) || !asciiOnly(text)
        val how: String
        if (usePaste) {
            val ok = ctx.host?.setClipboard(text) ?: false
            if (!ok) {
                ctx.fail(
                    L("这段文字含非 ASCII 字符（中文等），安卓的 `input text` 输不进去，需要走剪贴板粘贴，") +
                        L("但当前拿不到剪贴板能力。\n可以用 ui_input 只输英文数字，") +
                        L("或者用 run_shell 配合别的输入法方案。")
                )
            }
            how = L("剪贴板 + 粘贴（KEYCODE_PASTE）")
            cmds.add("input keyevent 279")                       // PASTE
        } else {
            how = L("直接键入（input text）")
            cmds.add("input text " + FileBridge.shellQuote(text.replace(" ", "%s")))
        }
        if (ctx.args.boolOr("submit", false)) cmds.add("input keyevent 66")

        val joined = cmds.joinToString("; ")
        val res = exec(
            ctx, launcher, joined,
            summary = L("输入文字：%s%s（%s）")
                .format(text.take(40), if (text.length > 40) "…" else "", how),
            timeoutMs = 40_000
        )
        if (!res.ok) {
            ctx.fail(
                L("输入失败（退出码 %s）：\n").format(res.exitCode) +
                    (res.stderr + res.stdout).trim().take(400)
            )
        }
        ToolResult(
            L("已输入 %s 个字符（%s）\n").format(text.length, how) +
                (if (usePaste) {
                    L("提示：粘贴前输入框必须有焦点，且文字确实进了剪贴板；如果没生效，先 ui_tap 点一下输入框再试。\n")
                } else "") +
                (if (ctx.args.boolOr("submit", false)) L("已按回车提交。\n") else "")
        )
    }

    // ----------------------------------------------------------------- ui_key

    private fun key() = ToolSpec(
        name = "ui_key",
        title = "按键",
        description = "按系统按键：back（返回）、home、enter、recent（最近任务）、delete、tab、escape、" +
            "上下左右、音量、power、wakeup（唤醒）等。也可以直接给数字键值（如 4）。需要 Root 或 Shizuku。",
        perm = PermKey.UI,
        schema = Schema.obj(
            mapOf(
                "key" to Schema.str(
                    "按键名或键值。常用：" + KEYS.keys.joinToString(" / "),
                    null, null
                ),
                "repeat" to Schema.int("按几次", 1, 1, 50),
                "backend" to Schema.str(BACKEND_DESC, "auto", listOf("auto", "shizuku", "root"))
            ),
            listOf("key")
        )
    ) { ctx ->
        val launcher = pickLauncher(ctx, ctx.args.strOr("backend", "auto"))
        val raw = ctx.args.str("key")?.trim().orEmpty()
        if (raw.isEmpty()) ctx.fail(L("缺少 key"))
        val code = raw.toIntOrNull()
            ?: KEYS[raw.lowercase().replace('-', '_').replace(" ", "_")]
            ?: ctx.fail(
                L("不认识的按键：%s\n可用：").format(raw) +
                    KEYS.keys.joinToString(" / ") +
                    L("，或者直接给数字键值（如 4 = 返回）")
            )
        val repeat = ctx.args.intOr("repeat", 1).coerceIn(1, 50)
        val cmd = "input keyevent " + List(repeat) { code.toString() }.joinToString(" ")
        val res = exec(
            ctx, launcher, cmd,
            summary = L("按键：%s（%s）%s")
                .format(raw, code, if (repeat > 1) " ×$repeat" else ""),
            timeoutMs = 20_000
        )
        if (!res.ok) {
            ctx.fail(
                L("按键失败（退出码 %s）：\n").format(res.exitCode) +
                    (res.stderr + res.stdout).trim().take(400)
            )
        }
        val fg = runCatching { foreground(launcher) }.getOrDefault("")
        ToolResult(
            L("已按 %s（keycode %s）%s\n当前前台：%s")
                .format(raw, code, if (repeat > 1) L(" %s 次").format(repeat) else "", fg)
        )
    }

    /** 常用按键 → keycode。名字跟着 Android 的 KEYCODE_ 常量走。 */
    private val KEYS: Map<String, Int> = linkedMapOf(
        "back" to 4,
        "home" to 3,
        "enter" to 66,
        "recent" to 187,
        "appswitch" to 187,
        "menu" to 82,
        "delete" to 67,
        "del" to 67,
        "tab" to 61,
        "escape" to 111,
        "space" to 62,
        "up" to 19,
        "down" to 20,
        "left" to 21,
        "right" to 22,
        "move_end" to 123,
        "move_home" to 122,
        "page_up" to 92,
        "page_down" to 93,
        "volume_up" to 24,
        "volume_down" to 25,
        "mute" to 164,
        "power" to 26,
        "wakeup" to 224,
        "sleep" to 223,
        "camera" to 27,
        "search" to 84,
        "play_pause" to 85,
        "notification" to 83
    )

    // -------------------------------------------------------------- ui_launch

    private fun launch() = ToolSpec(
        name = "ui_launch",
        title = "启动应用 / 看前台",
        description = "action=app 启动指定包名的应用；action=home 回桌面；" +
            "action=current 看当前前台是哪个应用；action=list 列出已安装的第三方应用（找包名用）。" +
            "需要 Root 或 Shizuku。",
        perm = PermKey.UI,
        schema = Schema.obj(
            mapOf(
                "action" to Schema.str("要做什么", "app", listOf("app", "home", "current", "list")),
                "package" to Schema.str("应用包名（action=app 时必填），如 com.tencent.mm"),
                "backend" to Schema.str(BACKEND_DESC, "auto", listOf("auto", "shizuku", "root"))
            ),
            listOf("action")
        )
    ) { ctx ->
        val launcher = pickLauncher(ctx, ctx.args.strOr("backend", "auto"))
        when (val action = ctx.args.strOr("action", "app")) {
            "home" -> {
                val res = exec(ctx, launcher, "input keyevent 3", summary = "回桌面", timeoutMs = 20_000)
                if (!res.ok) {
                    ctx.fail(L("回桌面失败：\n") + (res.stderr + res.stdout).trim().take(300))
                }
                ToolResult(L("已回到桌面"))
            }

            "current" -> {
                ctx.guard(PermKey.UI, null, L("查看前台应用"), L("后端：%s").format(L(launcher.label)))
                val fg = foreground(launcher)
                val act = runner.run(
                    launcher,
                    "dumpsys activity activities 2>/dev/null | grep -m1 -E 'ResumedActivity|topResumedActivity'",
                    null, 15_000
                ).stdout.trim()
                ToolResult(
                    L("当前前台：%s\n").format(fg) +
                        (if (act.isNotBlank()) act else L("（拿不到 activity 信息）"))
                )
            }

            "list" -> {
                ctx.guard(PermKey.UI, null, L("列出第三方应用"), L("后端：%s").format(L(launcher.label)))
                val out = runner.run(launcher, "pm list packages -3", null, 30_000).stdout
                val pkgs = out.lineSequence().map { it.removePrefix("package:").trim() }
                    .filter { it.isNotBlank() }.sorted().toList()
                ToolResult(
                    L("第三方应用 %s 个：\n").format(pkgs.size) +
                        pkgs.take(200).joinToString("\n").ifBlank { L("（没有查到）") } +
                        if (pkgs.size > 200) L("\n… 还有 %s 个").format(pkgs.size - 200) else ""
                )
            }

            "app" -> {
                val pkg = ctx.args.str("package")?.trim().orEmpty()
                if (pkg.isBlank()) {
                    ctx.fail(L("action=app 需要给 package（包名）。不知道包名可以先用 action=list 查。"))
                }
                if (!PKG_NAME.matches(pkg)) {
                    ctx.fail(L("包名格式不对：%s（应该是 com.xxx.yyy 这种）").format(pkg))
                }
                val cmd = "monkey -p $pkg -c android.intent.category.LAUNCHER 1 2>&1 | tail -2"
                val res = exec(
                    ctx, launcher, cmd,
                    summary = L("启动应用 %s").format(pkg),
                    timeoutMs = 30_000
                )
                val out = (res.stdout + res.stderr).trim()
                val ok = out.contains("Events injected: 1") || !out.contains("No activities found")
                if (!ok) {
                    ctx.fail(
                        L("启动 %s 失败：\n").format(pkg) + out.take(400) +
                            L("\n\n可能这个包名不存在，或者它没有可启动的界面（是纯后台服务）。")
                    )
                }
                Thread.sleep(400)
                val fg = runCatching { foreground(launcher) }.getOrDefault("")
                ToolResult(
                    L("已启动 %s\n当前前台：%s\n\n提示：启动后稍等一下再 ui_dump，界面可能还在加载。")
                        .format(pkg, fg)
                )
            }

            else -> ctx.fail(L("不认识的 action：%s（可用 app / home / current / list）").format(action))
        }
    }

    private val PKG_NAME = Regex("""^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$""")

    // ---------------------------------------------------------------- ui_wait

    private fun waitFor() = ToolSpec(
        name = "ui_wait",
        title = "等元素出现",
        description = "轮询界面结构，等某个文字 / 描述 / id 出现（或消失）再继续 —— " +
            "点击之后界面要加载时用它，比固定 sleep 稳。需要 Root 或 Shizuku。",
        perm = PermKey.UI,
        schema = Schema.obj(
            mapOf(
                "text" to Schema.str("等这个文字出现"),
                "desc" to Schema.str("等这个描述出现"),
                "id" to Schema.str("等这个 resource-id 出现"),
                "disappear" to Schema.bool("反过来：等它消失", false),
                "timeoutMs" to Schema.int("最多等多久（毫秒）", 8_000, 500, 120_000),
                "intervalMs" to Schema.int("每次检查间隔（毫秒）", 500, 200, 5_000),
                "backend" to Schema.str(BACKEND_DESC, "auto", listOf("auto", "shizuku", "root"))
            )
        )
    ) { ctx ->
        val launcher = pickLauncher(ctx, ctx.args.strOr("backend", "auto"))
        val text = ctx.args.str("text")
        val desc = ctx.args.str("desc")
        val id = ctx.args.str("id")
        if (text == null && desc == null && id == null) {
            ctx.fail(L("至少要给 text / desc / id 之一"))
        }
        val disappear = ctx.args.boolOr("disappear", false)
        val timeout = ctx.args.longOr("timeoutMs", 8_000).coerceIn(500L, 120_000L)
        val interval = ctx.args.longOr("intervalMs", 500).coerceIn(200L, 5_000L)
        val what = text ?: desc ?: id!!
        ctx.guard(
            PermKey.UI, null,
            L("等待「%s」%s").format(what, if (disappear) L("消失") else L("出现")),
            L("后端：%s").format(L(launcher.label))
        )

        val started = System.currentTimeMillis()
        var rounds = 0
        var last: UiNode? = null
        while (System.currentTimeMillis() - started < timeout) {
            rounds++
            val hit = runCatching {
                val snap = snapshot(ctx, launcher)
                snap.nodes.firstOrNull { n ->
                    n.visible && (
                        (text != null && n.text.contains(text, true)) ||
                            (desc != null && n.desc.contains(desc, true)) ||
                            (id != null && n.id.contains(id, true))
                        )
                }
            }.getOrNull()
            if (hit != null) last = hit
            val present = hit != null
            if (present != disappear) {
                val took = System.currentTimeMillis() - started
                return@ToolSpec ToolResult(
                    L("「%s」已%s（等了 %sms，查了 %s 次）\n").format(
                        what,
                        if (disappear) L("消失") else L("出现"),
                        took,
                        rounds
                    ) +
                        (
                            last?.let {
                                L("位置：中心(%s,%s) 范围[%s,%s][%s,%s]\n").format(
                                    it.centerX, it.centerY, it.left, it.top, it.right, it.bottom
                                )
                            } ?: ""
                        )
                )
            }
            Thread.sleep(interval)
        }
        ctx.fail(
            L("等了 %sms，「%s」还是%s。\n")
                .format(timeout, what, if (disappear) L("在") else L("没出现")) +
                L("可以用 ui_dump 看看当前屏幕上到底有什么（或者 ui_screenshot 看画面）。")
        )
    }
}

/** 参数里没给、或者给了非数字 → null（用来区分「没传」和「传了 0」）。 */
private fun JsonObject.intOrNull(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
