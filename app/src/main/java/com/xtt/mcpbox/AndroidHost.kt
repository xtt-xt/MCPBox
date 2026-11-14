// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox

import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import com.xtt.mcpbox.core.Config
import com.xtt.mcpbox.core.HostInfo
import com.xtt.mcpbox.core.LocationFix
import com.xtt.mcpbox.core.ServerMeta
import com.xtt.mcpbox.core.ShellRunner
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 给工具用的设备信息 / 通知能力。 */
class AndroidHost(private val context: Context, private val config: Config) : HostInfo {

    override fun deviceInfo(): Map<String, Any?> {
        val root = File(config.primaryRoot())
        val stat = runCatching { StatFs(root.absolutePath) }.getOrNull()
        val total = stat?.let { it.blockCountLong * it.blockSizeLong } ?: 0L
        val free = stat?.let { it.availableBlocksLong * it.blockSizeLong } ?: 0L
        return mapOf(
            "型号" to AppCore.deviceLabel(),
            "品牌" to Build.BRAND,
            "Android 版本" to Build.VERSION.RELEASE,
            "SDK" to Build.VERSION.SDK_INT,
            "ABI" to Build.SUPPORTED_ABIS.firstOrNull(),
            // 完整版本号 v1.0.0-42（versionName + versionCode），只给 1.0.0 看不出是第几版
            "App 版本" to ServerMeta.fullVersion,
            "主根目录" to config.primaryRoot(),
            "根目录可用" to human(free),
            "根目录总计" to human(total),
            "外部存储可用" to human(runCatching { Environment.getExternalStorageDirectory().usableSpace }.getOrDefault(0L)),
            "电量" to batteryPercent()
        )
    }

    override fun notify(title: String, message: String): Boolean {
        return try {
            NotificationHelper.aiMessage(context, title, message)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 写系统剪贴板。UI 自动化输入中文时用：`input text` 只认 ASCII，
     * 非 ASCII 走「写剪贴板 → input keyevent 279（粘贴）」。
     * 写剪贴板不受 Android 10+ 的后台读取限制，粘贴由前台应用执行。
     */
    override fun setClipboard(text: String): Boolean {
        var ok = false
        val job = {
            ok = runCatching {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("mcpbox-ui", text))
                true
            }.getOrDefault(false)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) job() else {
            val latch = java.util.concurrent.CountDownLatch(1)
            Handler(Looper.getMainLooper()).post { job(); latch.countDown() }
            latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        }
        return ok
    }

    private fun batteryPercent(): String {
        return runCatching {
            val intent: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) "${level * 100 / scale}%" else "未知"
        }.getOrDefault("未知")
    }

    private fun human(bytes: Long): String = when {
        bytes <= 0 -> "未知"
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024)
        else -> String.format("%.2f GB", bytes / 1024.0 / 1024 / 1024)
    }

    /** 通知权限是否已经拿到（Android 13+ 需要运行时申请）。 */
    fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun canDrawOverlays(): Boolean =
        Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(context)

    fun hasAllFilesAccess(): Boolean {
        if (Build.VERSION.SDK_INT < 30) {
            return context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        return runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
    }

    fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(false)
    }

    /* ------------------------------------------------------------------ 定位 */

    /**
     * 系统定位权限：**粗略**给了就能用网络定位，**精确**才有 GPS。
     *
     * 这两个都是运行时权限，`pm grant` 会把进程杀掉重启，所以不在「自动补齐」里 ——
     * 只能用户自己点（首页的取位置工具会在报错里告诉他去哪开）。
     */
    fun hasLocationPermission(): Boolean =
        context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    fun hasFineLocationPermission(): Boolean =
        context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun locationManager(): LocationManager? =
        runCatching {
            context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        }.getOrNull()

    private fun providerOn(lm: LocationManager, id: String): Boolean =
        runCatching { lm.isProviderEnabled(id) }.getOrDefault(false)

    override fun locationStatus(): Map<String, Any?> {
        val lm = locationManager() ?: return emptyMap()
        val gps = providerOn(lm, LocationManager.GPS_PROVIDER)
        val net = providerOn(lm, LocationManager.NETWORK_PROVIDER)
        return mapOf(
            "已授权" to hasLocationPermission(),
            "精确定位" to hasFineLocationPermission(),
            "定位总开关" to (gps || net),
            "GPS" to gps,
            "网络定位" to net,
            "有定位硬件" to runCatching {
                context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LOCATION)
            }.getOrDefault(false)
        )
    }

    /**
     * 取**一次**位置。
     *
     * 这里踩过一个很深的坑，结论写在这儿别再走回头路：
     *  · 「使用应用时允许」的定位权限**只在 App 有可见界面时才投递位置**。
     *    App 在后台（只有前台服务）时，`requestLocationUpdates` 会「注册成功」，
     *    但一个点都收不到，logcat 里只有 `LocationManagerService: noteOp denied`；
     *  · 想靠拉起一个透明 Activity 绕过去也不行：后台启动 Activity 会被系统挡，
     *    而且工具线程在那干等会把整个 HTTP 服务器拖住（accept 队列堆满）。
     *
     * 所以分两条路：
     *  · App 本人有界面（用户正开着 MCPBox）→ 真的请求一次实时位置，最多等 [waitMs]；
     *  · App 在后台 → 只读系统的「最后已知位置」缓存，多旧都返回，时间戳一起报出去，
     *    由调用方（工具文案）告诉用户「这是几分钟前的」。
     */
    override fun locationFix(waitMs: Long, highAccuracy: Boolean): LocationFix? {
        if (!hasLocationPermission()) return null

        // 先用「打开 App 时顺手取的那一份」：这是本 App 自己取的，
        // 比系统缓存新鲜得多，而且不受后台限制影响。
        val warm = LocationWarmer.latest()
        if (warm != null) {
            val age = LocationWarmer.latestAgeMs()
            // 高精度要求时，太粗的那份不用（±500 米以上基本没意义）
            val usable = age <= 10 * 60_000 &&
                (!highAccuracy || warm.accuracy <= 200f || warm.provider == "gps")
            if (usable) return warm
        }

        val lm = locationManager() ?: return null
        val timeout = waitMs.coerceIn(1_000, 60_000)
        val providers = pickProviders(lm, highAccuracy)
        if (providers.isEmpty()) return null

        // 足够新的缓存（2 分钟内）先用：省电，也不用干等
        val now = System.currentTimeMillis()
        providers.asSequence()
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .filter { now - it.time <= 120_000 }
            .minByOrNull { if (highAccuracy) it.accuracy.toDouble() else (now - it.time).toDouble() }
            ?.let { return it.toFix() }

        // 只有在 App 有可见界面时，系统才会投递新位置
        if (LocationVisibility.hasVisibleUi()) requestOnce(lm, providers, timeout)?.let { return it }

        // App 在后台：连 getLastKnownLocation 都会被拦（noteOp denied）。
        // 有 Root / Shizuku 时改读系统的定位服务缓存（dumpsys location），
        // 那份数据是别的应用刚取过的，通常比「什么都没有」有用得多。
        return cachedFix(lm, providers) ?: dumpsysFix(highAccuracy)
    }

    /**
     * 从 `dumpsys location` 里捞系统的「最后已知位置」。
     *
     * 为什么要绕这一圈：App 在后台时所有 LocationManager 读取都会被 appops 拦掉
     * （`noteOp denied`），连缓存都不给。而定位服务内部那份 last location 还在，
     * root / Shizuku 能读出来。拿到的点会带上它的时间，旧了由工具文案说明。
     */
    private fun dumpsysFix(highAccuracy: Boolean): LocationFix? {
        val launcher = runCatching { privilegedLauncher() }.getOrNull() ?: return null
        val out = runCatching {
            ShellRunner().run(launcher, "dumpsys location", null, 15_000, 800_000).stdout
        }.getOrNull() ?: return null
        if (out.isBlank()) return null

        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        data class Parsed(
            val provider: String, val lat: Double, val lon: Double,
            val acc: Float, val alt: Double?, val speed: Float?, val timeMs: Long
        )

        // 一行里就带着「哪个 provider、坐标、精度、什么时候取的」
        val re = Regex(
            "Location\\[(gps|network|fused|passive) (-?[0-9.]+),(-?[0-9.]+) hAcc=([0-9.]+) et=\\+([0-9a-z.]+)" +
                "(?:[^]\\n]*?alt=(-?[0-9.]+))?(?:[^]\\n]*?vel=(-?[0-9.]+))?"
        )
        val parsed = re.findAll(out).mapNotNull { m ->
            val etMs = parseElapsedMs(m.groupValues[5]) ?: return@mapNotNull null
            val age = (nowElapsed - etMs).coerceAtLeast(0L)
            Parsed(
                provider = m.groupValues[1],
                lat = m.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null,
                lon = m.groupValues[3].toDoubleOrNull() ?: return@mapNotNull null,
                acc = m.groupValues[4].toFloatOrNull() ?: 0f,
                alt = m.groupValues[6].toDoubleOrNull(),
                speed = m.groupValues[7].toFloatOrNull(),
                timeMs = nowWall - age
            )
        }.toList()
        if (parsed.isEmpty()) return null

        // 高精度优先 GPS，省电优先网络定位；同类里取最新的那份
        val order = if (highAccuracy) {
            listOf("gps", "fused", "network", "passive")
        } else {
            listOf("network", "fused", "gps", "passive")
        }
        for (p in order) {
            val best = parsed.filter { it.provider == p }.maxByOrNull { it.timeMs } ?: continue
            return LocationFix(
                latitude = best.lat,
                longitude = best.lon,
                accuracy = best.acc,
                altitude = best.alt,
                speed = best.speed,
                timeMs = best.timeMs,
                provider = best.provider,
                mock = false
            )
        }
        return null
    }

    /** `23d11h13m7s603ms` → 毫秒。缺哪一段都能认。 */
    private fun parseElapsedMs(raw: String): Long? {
        var rest = raw.trim().removePrefix("+")
        if (rest.isEmpty()) return null
        var total = 0L
        val units = listOf("d" to 86_400_000L, "h" to 3_600_000L, "m" to 60_000L, "s" to 1_000L, "ms" to 1L)
        var matched = false
        for ((unit, mul) in units) {
            val i = rest.indexOf(unit)
            if (i <= 0) continue
            // 「ms」里的 m 不能先被当成分钟：按 d/h/m/s/ms 的顺序找，且 ms 排最后时单独处理
            if (unit == "m" && i + 1 < rest.length && rest[i + 1] == 's') continue
            val num = rest.substring(0, i).toLongOrNull() ?: continue
            total += num * mul
            rest = rest.substring(i + unit.length)
            matched = true
            if (rest.isEmpty()) break
        }
        return if (matched) total else null
    }

    /** 按优先级挑出真正能用的定位来源（GPS 需要「精确位置」权限）。 */
    private fun pickProviders(lm: LocationManager, highAccuracy: Boolean): List<String> {
        val order = if (highAccuracy) {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        } else {
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        }
        return order.filter { id ->
            providerOn(lm, id) &&
                (id != LocationManager.GPS_PROVIDER || hasFineLocationPermission())
        }
    }

    /** 挂一次性回调要一个实时位置（**只在 App 有可见界面时有效**）。 */
    private fun requestOnce(lm: LocationManager, providers: List<String>, timeout: Long): LocationFix? {
        val latch = CountDownLatch(1)
        // 回调在主线程、等待在工具线程：用 AtomicReference，别用局部 var（可读性也更好）
        val best = java.util.concurrent.atomic.AtomicReference<Location?>(null)
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val cur = best.get()
                if (cur == null || location.accuracy <= cur.accuracy) best.set(location)
                latch.countDown()
            }
            // API 26 上它还是抽象方法，必须实现；29+ 起已被弃用
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        val started = providers.any { p ->
            runCatching {
                lm.requestLocationUpdates(p, 0L, 0f, listener, Looper.getMainLooper())
                true
            }.getOrDefault(false)
        }
        if (!started) return null
        return try {
            latch.await(timeout + 300, TimeUnit.MILLISECONDS)
            best.get()?.toFix()
        } finally {
            runCatching { lm.removeUpdates(listener) }
        }
    }

    /** 兜底：系统里存的「最后已知位置」，多旧都用（时间戳会一起报出去）。 */
    private fun cachedFix(lm: LocationManager, providers: List<String>): LocationFix? =
        providers.asSequence()
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.toFix()
}
