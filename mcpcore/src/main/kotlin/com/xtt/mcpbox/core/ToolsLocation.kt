// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.core

/**
 * 位置工具包（`location`）：让 AI 知道「这台手机现在在哪」。
 *
 * 只做**取一次位置**，不做持续 / 后台定位：
 *  · 一次性请求走 `LocationManager.requestLocationUpdates` + 立即 `removeUpdates`，
 *    不需要 `ACCESS_BACKGROUND_LOCATION`，也不用给前台服务加 location 类型；
 *  · 拿不到就老实报错（没授权、没开定位、室内没信号），不假装成功。
 *
 * 坐标属于隐私，所以权限键 `location` 出厂是**询问**：每次取位置先在手机上点一次同意；
 * 系统定位权限是第二道闸门：没给时直接报错并告诉他去哪儿开（运行时权限只能由
 * App 界面申请，工具调用跑在服务里，顺手弹框那条路会把整个服务器拖死）。
 */
object ToolsLocation {

    fun specs(): List<ToolSpec> = listOf(getLocation(), locationStatus())

    // ---------------------------------------------------------------- get_location

    private fun getLocation() = ToolSpec(
        name = "get_location",
        title = "获取位置",
        description = "取一次手机的当前位置：经纬度、精度、海拔、速度、方向、定位方式与时间。" +
            "只取一次，不会持续定位，也不会在后台跟踪。" +
            "需要系统定位权限；权限没给、或者系统定位关着时会直接告诉你原因。",
        perm = PermKey.LOCATION,
        schema = Schema.obj(
            mapOf(
                "timeout_ms" to Schema.int("最多等多久（毫秒），默认 8000", 8000, 1000, 60000),
                "high_accuracy" to Schema.bool(
                    "优先用 GPS 精确定位（更准、更慢、更耗电）；关掉则优先网络定位（快但只到街区级）",
                    true
                )
            )
        )
    ) { ctx ->
        val timeout = ctx.args.intOr("timeout_ms", 8000).coerceIn(1000, 60000)
        val high = ctx.args.boolOr("high_accuracy", true)
        val host = ctx.host
            ?: ctx.fail(L("当前运行环境没有定位能力（桌面端测试模式）"))

        // 系统这一侧先探一遍：定位总开关都没开的话，问权限也是白问，直接说清楚。
        val status = host.locationStatus()
        if (status.isEmpty()) ctx.fail(L("当前运行环境没有定位能力（桌面端测试模式）"))
        if (status["定位总开关"] == false || status["定位总开关"] == "false") {
            ctx.fail(
                L("手机的定位开关是关着的：到 系统设置 → 位置信息 里打开，再调用一次。")
            )
        }

        // 先过 MCPBox 自己的审批（「AI 要读位置，同不同意」），再谈系统那一层。
        ctx.guard(
            PermKey.LOCATION, null,
            L("读取当前位置（%s）").format(if (high) L("优先 GPS") else L("优先网络定位"))
        )

        // 取位置：App 在前台时真去请求一次，在后台就用手上那份缓存
        // （见 AndroidHost.locationFix 的说明）。
        val fix = host.locationFix(timeout.toLong(), high) ?: run {
            // 失败要分清是「权限还没给」还是「就是没信号」，两种处理方式完全不同
            val after = host.locationStatus()
            if (!granted(after)) {
                ctx.fail(
                    buildString {
                        append(L("定位权限还没给，读不到位置。")).append('\n')
                        append(
                            L("到 系统设置 → 应用 → MCP 文件盒 → 权限 → 位置 里选「使用应用时允许」，并打开「精确位置」。")
                        ).append('\n')
                        append(L("给完权限回来再调用一次就行。"))
                    }
                )
            }
            ctx.fail(
                buildString {
                    append(L("等了 %s 秒也没拿到定位。").format(timeout / 1000)).append('\n')
                    append(L("  · 室内、地下车库、电梯里常常收不到 GPS 信号")).append('\n')
                    append(L("  · 可以试试把 timeout_ms 调大，或者把 high_accuracy 关掉走网络定位")).append('\n')
                    append(L("  · 也确认一下 设置 → 位置信息 里的「位置服务」是开着的"))
                }
            )
        }

        ToolResult(render(fix, System.currentTimeMillis()))
    }

    // -------------------------------------------------------------- location_status

    private fun locationStatus() = ToolSpec(
        name = "location_status",
        title = "定位状态",
        description = "看定位能不能用：系统权限给没给、是不是精确定位、GPS 与网络定位开没开、有没有模拟定位。" +
            "取不到位置时先用它排查。不读坐标，所以不需要定位权限。",
        perm = PermKey.SYSTEM,
        schema = Schema.obj(emptyMap())
    ) { ctx ->
        ctx.guard(PermKey.SYSTEM, null, L("查看定位状态"))
        val host = ctx.host
            ?: ctx.fail(L("当前运行环境没有定位能力（桌面端测试模式）"))
        val st = host.locationStatus()
        if (st.isEmpty()) ctx.fail(L("当前运行环境没有定位能力（桌面端测试模式）"))

        val sb = StringBuilder()
        sb.append(L("定位状态")).append('\n')
        st.forEach { (k, v) ->
            sb.append("  ").append(L(k)).append(L("：")).append(yesNo(v)).append('\n')
        }
        sb.append('\n')
        when {
            st["已授权"] == false || st["已授权"] == "false" ->
                sb.append(
                    L("→ 还没授权：去 系统设置 → 应用 → MCP 文件盒 → 权限 → 位置 里允许（选「使用应用时允许」，精确位置也打开）。")
                )
            st["定位总开关"] == false || st["定位总开关"] == "false" ->
                sb.append(L("→ 系统定位是关的：到 系统设置 → 位置信息 里打开。"))
            else ->
                sb.append(L("→ 可以取位置：调用 get_location 试试。"))
        }
        ToolResult(sb.toString())
    }

    // ------------------------------------------------------------------ 渲染

    /** status 里的「已授权」可能是布尔也可能是字符串，两种都认。 */
    private fun granted(status: Map<String, Any?>): Boolean =
        status["已授权"] == true || status["已授权"] == "true"

    /** 布尔值统一成「是 / 否」，跟界面里其它开关的口径一致。 */
    private fun yesNo(v: Any?): String = when (v) {
        true -> L("是")
        false -> L("否")
        null -> L("未知")
        else -> v.toString()
    }

    private fun render(fix: LocationFix, now: Long): String {
        val sb = StringBuilder()
        sb.append(L("当前位置")).append('\n')
        // 一行纯数字，方便直接粘进地图 / 其它工具
        sb.append("  ").append(L("坐标：")).append(fmt(fix.latitude, 6)).append(", ")
            .append(fmt(fix.longitude, 6)).append('\n')
        sb.append("  ").append(L("纬度：")).append(fmt(fix.latitude, 6)).append('\n')
        sb.append("  ").append(L("经度：")).append(fmt(fix.longitude, 6)).append('\n')
        if (fix.accuracy > 0f) {
            sb.append("  ").append(L("精度：约 ±%s 米").format(fix.accuracy.toInt())).append('\n')
        }
        // 海拔 0 基本是「这一项没值」的占位，写出来只会误导（室内定位常见）
        fix.altitude?.takeIf { it != 0.0 }?.let {
            sb.append("  ").append(L("海拔：%s 米").format(fmt(it, 1))).append('\n')
        }
        fix.speed?.let {
            sb.append("  ").append(L("速度：%s 米/秒").format(fmt(it.toDouble(), 1))).append('\n')
        }
        fix.bearing?.let {
            sb.append("  ").append(L("方向：%s°（正北 0°）").format(fmt(it.toDouble(), 0))).append('\n')
        }
        sb.append("  ").append(L("定位方式：")).append(
            when {
                // 标着 gps 但精度只有百米级：说明卫星信号很差，实际多半是网络定位兜底的结果。
                // 不点明的话就是「GPS 卫星定位 ±300 米」这种自相矛盾的读数。
                fix.provider == "gps" && fix.accuracy > 100f ->
                    L("GPS 卫星定位（本次精度较差，接近网络定位）")
                else -> providerLabel(fix.provider)
            }
        ).append('\n')
        val ago = ((now - fix.timeMs) / 1000).coerceAtLeast(0)
        sb.append("  ").append(L("定位时间：%s（%s前）").format(stamp(fix.timeMs), humanAgo(ago))).append('\n')
        // 超过 5 分钟就不是「刚拿到的位置」了，得让调用方知道：这是缓存
        if (ago > 300) {
            sb.append("  ").append(
                L("注意：这不是刚取的实时位置，是系统缓存的最后一次定位。") +
                    L("MCP 文件盒在后台时系统不投递新位置，把 App 切到前台再调用一次才能拿到实时的。")
            ).append('\n')
        }
        if (fix.mock) {
            sb.append('\n').append(L("注意：这是「模拟位置」（开发者选项里设的假坐标），不是真实位置。"))
        }
        sb.append('\n').append(
            L("说明：经纬度是 WGS-84 / GCJ-02 之间的原始值，国内地图显示时会有偏移；" +
                "经纬度转地址需要联网的第三方服务，本工具不做。")
        )
        return sb.toString()
    }

    private fun fmt(v: Double, digits: Int): String {
        val rounded = Math.pow(10.0, digits.toDouble())
        val n = Math.round(v * rounded) / rounded
        return if (digits == 0) n.toLong().toString()
        else String.format(java.util.Locale.US, "%.${digits}f", n)
    }

    /** 定位来源的中文说法。 */
    private fun providerLabel(id: String): String = when (id) {
        "gps" -> L("GPS 卫星定位")
        "network" -> L("网络定位（基站 / Wi-Fi）")
        "passive" -> L("被动定位（别的应用刚取过）")
        "fused" -> L("系统融合定位")
        "" -> L("未知")
        else -> id
    }

    /** 把「多少秒前」写成 12 秒 / 3 分钟 / 2 小时，别让人读一堆数字。 */
    private fun humanAgo(seconds: Long): String = when {
        seconds < 60 -> L("%s 秒").format(seconds)
        seconds < 3600 -> L("%s 分钟").format(seconds / 60)
        seconds < 86400 -> L("%s 小时").format(seconds / 3600)
        else -> L("%s 天").format(seconds / 86400)
    }

    private fun stamp(ms: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        return sdf.format(java.util.Date(ms))
    }
}
