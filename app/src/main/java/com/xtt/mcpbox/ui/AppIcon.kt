// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes
import com.xtt.mcpbox.AppCore
import com.xtt.mcpbox.R

/**
 * 可切换的应用图标。
 *
 * **Android 的图标只能靠组件切换**：一个 App 在桌面上出现哪个图标，取决于「哪个组件带着
 * MAIN/LAUNCHER 意图过滤器」。所以每种图标都是一个 `activity-alias`（见 AndroidManifest.xml），
 * 切换 = 把所有 alias 里目标那个设成 ENABLED、其余设成 DISABLED（[apply]）。
 *
 * 两条注意：
 *  · `MainActivity` 自己**不带** LAUNCHER 过滤器 —— 否则桌面上会同时出现两个图标。
 *    用 `am start -n com.xtt.mcpbox/.MainActivity` 这种**显式组件**方式启动不受影响。
 *  · [alias] 必须和清单里的 `android:name` 一字不差。改名字 = 老用户桌面上那个图标会消失。
 *
 * 图标资源是 432px 的方形整图（`mipmap-xxxhdpi/ic_icon_*.png`），在 `mipmap-anydpi-v26/ic_icon_*.xml`
 * 里做成自适应图标：整张图放在 **background** 层、前景留空 —— 图案只占画布 65%，
 * 遮罩（圆形 / 方圆 / 圆角矩形）切不到内容，也不需要再被前景安全区缩一次。
 */
enum class AppIcon(
    val id: String,
    val label: String,
    /** 清单里那个 activity-alias 的类名（必须完全一致）。 */
    val alias: String,
    /** 设置页里显示的缩略图（和真正的图标是同一张）。 */
    @DrawableRes val art: Int,
    /** 设置页里的补充说明。 */
    val note: String
) {
    LINE("line", "线性极简", ".ui.IconDefault", R.mipmap.ic_icon_line, "白底线框，最轻"),
    FLAT("flat", "扁平", ".ui.IconFlat", R.mipmap.ic_icon_flat, "纯色块，无描边无阴影"),
    GRADIENT("gradient", "渐变", ".ui.IconGradient", R.mipmap.ic_icon_gradient, "双色渐变加顶部反光"),
    GLASS("glass", "液体玻璃", ".ui.IconGlass", R.mipmap.ic_icon_glass, "彩色模糊底加磨砂玻璃"),
    DARK_GLASS("darkglass", "暗色玻璃", ".ui.IconDarkGlass", R.mipmap.ic_icon_darkglass, "深色底加冷光玻璃"),
    SOFT("soft", "新拟物", ".ui.IconSoft", R.mipmap.ic_icon_soft, "同色系柔光加软阴影"),
    DRAWER("drawer", "档案抽屉", ".ui.IconDrawer", R.mipmap.ic_icon_drawer, "拉出式抽屉，最厚实");

    companion object {
        /** 认不出的 id 一律回落到默认（[LINE]）。 */
        fun of(id: String?): AppIcon = entries.firstOrNull { it.id == id } ?: LINE

        /** 现在生效的那一个（读偏好）。 */
        fun current(): AppIcon = of(AppCore.prefs.appIcon)

        /**
         * 真正去改组件开关：目标那个 ENABLED、其余 DISABLED。
         *
         * 用 `DONT_KILL_APP`：桌面上那个图标要等系统把 launcher 的数据刷一遍才换过来，
         * 但 App 本身不用被杀（杀进程等于把正在跑的 MCP 服务一起带走）。
         */
        fun apply(ctx: Context, target: AppIcon) {
            val pm = ctx.packageManager
            entries.forEach { icon ->
                val state = if (icon == target) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                }
                runCatching {
                    pm.setComponentEnabledSetting(
                        ComponentName(ctx, ctx.packageName + icon.alias),
                        state,
                        PackageManager.DONT_KILL_APP
                    )
                }
            }
        }
    }
}
