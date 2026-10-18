// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
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
    LINE("line", "线性极简", ".ui.IconDefault", R.drawable.art_icon_line, "白底线框，最轻"),
    FLAT("flat", "扁平", ".ui.IconFlat", R.drawable.art_icon_flat, "纯色块，无描边无阴影"),
    GRADIENT("gradient", "渐变", ".ui.IconGradient", R.drawable.art_icon_gradient, "双色渐变加顶部反光"),
    GLASS("glass", "液体玻璃", ".ui.IconGlass", R.drawable.art_icon_glass, "彩色模糊底加磨砂玻璃"),
    DARK_GLASS("darkglass", "暗色玻璃", ".ui.IconDarkGlass", R.drawable.art_icon_darkglass, "深色底加冷光玻璃"),
    SOFT("soft", "新拟物", ".ui.IconSoft", R.drawable.art_icon_soft, "同色系柔光加软阴影"),
    DRAWER("drawer", "档案抽屉", ".ui.IconDrawer", R.drawable.art_icon_drawer, "拉出式抽屉，最厚实");

    companion object {
        /** 认不出的 id 一律回落到默认（[LINE]）。 */
        fun of(id: String?): AppIcon = entries.firstOrNull { it.id == id } ?: LINE

        /** 现在生效的那一个（读偏好）。 */
        fun current(): AppIcon = of(AppCore.prefs.appIcon)

        /**
         * 真正去改组件开关：目标那个 ENABLED、其余 DISABLED。
         *
         * **顺序很关键（踩过坑）**：必须先 ENABLE 目标、再 DISABLE 别的。
         * 之前是「按枚举顺序逐个设」，`setComponentEnabledSetting` **不带 DONT_KILL_APP 时
         * 第一个 DISABLE 就会把进程杀掉**，后面的 ENABLE 根本没机会执行 ——
         * 结果是「一个 LAUNCHER 组件都不剩」：桌面图标变成系统默认图、点了也进不去 App，
         * 看着就是「切换没效果 / 图标坏了」。所以现在：
         *   ① 所有设置都带 `DONT_KILL_APP`，任何时刻都至少有一个可用入口；
         *   ② 先开目标、再关其余；
         *   ③ 最后要重启的话，由我们自己排一个「重新拉起」的闹钟再退出进程（[restartApp]）。
         */
        fun apply(ctx: Context, target: AppIcon, restart: Boolean = true) {
            val pm = ctx.packageManager
            fun set(icon: AppIcon, enabled: Boolean) {
                runCatching {
                    pm.setComponentEnabledSetting(
                        ComponentName(ctx, ctx.packageName + icon.alias),
                        if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                }
            }
            set(target, true)
            entries.filter { it != target }.forEach { set(it, false) }
            if (restart) restartApp(ctx, target)
        }

        /**
         * 自愈：确保「恰好有一个」LAUNCHER 组件是开着的。
         *
         * 老版本切图标时可能把进程杀在半路，留下一个都没开的状态（桌面图标成了系统默认图、
         * 点进去也没反应）。这里每次启动都按偏好对齐一遍；组件开关本身用 `DONT_KILL_APP`，
         * 不会影响正在跑的服务。
         */
        fun ensureConsistent(ctx: Context) {
            val pm = ctx.packageManager
            val want = current()
            // 先确认目标开着，再确认其余都关着
            val enabled = entries.filter { icon ->
                runCatching {
                    pm.getComponentEnabledSetting(ComponentName(ctx, ctx.packageName + icon.alias))
                }.getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) !=
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            if (enabled.size == 1 && enabled.first() == want) return
            fun set(icon: AppIcon, on: Boolean) {
                runCatching {
                    pm.setComponentEnabledSetting(
                        ComponentName(ctx, ctx.packageName + icon.alias),
                        if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                }
            }
            set(want, true)
            entries.filter { it != want }.forEach { set(it, false) }
        }

        /**
         * 重启自己：先排一个 0.6 秒后「重新打开 App」的闹钟，再退出进程。
         *
         * 为什么不用 `setComponentEnabledSetting` 的「不带 DONT_KILL_APP」去让系统杀：
         * 那样是**同步**杀的，控制流回不来，也没法保证图标状态已经写完。
         * 这里组件状态早就落好了，退出只是为了让启动器立刻刷新图标缓存。
         * 闹钟万一被系统拦掉也没关系 —— 桌面上的新图标此时已经能正常点了。
         */
        private fun restartApp(ctx: Context, target: AppIcon) {
            runCatching {
                val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                    .setComponent(ComponentName(ctx, ctx.packageName + target.alias))
                    .addFlags(
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                    )
                val pi = android.app.PendingIntent.getActivity(
                    ctx, 0, intent,
                    android.app.PendingIntent.FLAG_IMMUTABLE or
                        android.app.PendingIntent.FLAG_CANCEL_CURRENT
                )
                val am = ctx.getSystemService(android.app.AlarmManager::class.java)
                // 用 setAndAllowWhileIdle（不需要精确闹钟权限），几秒内会拉起
                am.setAndAllowWhileIdle(
                    android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    android.os.SystemClock.elapsedRealtime() + 600L,
                    pi
                )
            }
            // 让 toast 先显示出来，再退出
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                android.os.Process.killProcess(android.os.Process.myPid())
            }, 400L)
        }
    }
}

/**
 * 画一个图标图案（设置页的缩略图、关于页顶上那个大图标）。
 *
 * 为什么只吃**位图**（`@drawable/art_icon_*`）：`painterResource` 遇到 adaptive-icon XML 会抛
 * `Only VectorDrawables and rasterized asset types are supported`（API 26+ 上 mipmap 解析到的就是
 * 自适应图标 XML），点进「应用图标」页直接闪退。这里只取 BitmapDrawable，再兜一层 runCatching。
 *
 * 尺寸口径和桌面一致：adaptive icon 的可见区是中心 72/108，图案已经按这个缩过（`ic_bg_*`），
 * 所以这里直接按整张图画出来就不会「比实物大一圈」。
 */
@Composable
fun AppIconArt(
    @DrawableRes res: Int,
    size: Dp,
    corner: Dp = 12.dp,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    val bitmap = remember(res) {
        runCatching {
            (ctx.getDrawable(res) as? android.graphics.drawable.BitmapDrawable)?.bitmap?.asImageBitmap()
        }.getOrNull()
    }
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center
    ) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
