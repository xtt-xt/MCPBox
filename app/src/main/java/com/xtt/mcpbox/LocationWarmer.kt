// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import com.xtt.mcpbox.core.LocationFix
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 「打开 App 就顺手取一次位置」，给工具留一份新鲜的缓存。
 *
 * 为什么要这个：Android 的「使用应用时允许」定位权限只在 App 有可见界面时才投递位置。
 * 等 AI 调工具的时候 App 通常在后台，那一刻要不到实时位置，只能读系统缓存 ——
 * 而系统缓存可能是几小时前的（GPS 只在用地图时才刷新）。
 *
 * 所以改成**在能取的时候先取**：每次 App 回到前台（[LocationVisibility] 的 onActivityStarted）
 * 就悄悄请求一次位置，存在内存里；工具调用先读这份。
 *  · 只在 App 可见时取，后台绝不偷偷定位；
 *  · 60 秒内不重复取（切来切去很常见，没必要每次都开 GPS）；
 *  · 取一次就撤（拿到第一个点或 20 秒超时），不做持续定位。
 */
object LocationWarmer {

    /** 多久之内不重复取（毫秒）。 */
    private const val MIN_INTERVAL_MS = 60_000L

    /** 单次最多等多久（毫秒）。 */
    private const val TIMEOUT_MS = 20_000L

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "location-warmer").apply { isDaemon = true }
    }

    @Volatile private var cached: LocationFix? = null
    @Volatile private var cachedAt: Long = 0L
    @Volatile private var running = false

    /** 最近一次预热的结果（null = 还没取到过）。 */
    fun latest(): LocationFix? = cached

    fun latestAgeMs(): Long = if (cached == null) Long.MAX_VALUE else System.currentTimeMillis() - cachedAt

    /** App 回到前台时调：必要时在后台线程悄悄取一次。 */
    fun warmUp(ctx: Context) {
        if (running) return
        if (System.currentTimeMillis() - cachedAt < MIN_INTERVAL_MS) return
        val app = ctx.applicationContext
        running = true
        runCatching {
            worker.execute {
                try {
                    fetchOnce(app)?.let {
                        cached = it
                        cachedAt = System.currentTimeMillis()
                    }
                } finally {
                    running = false
                }
            }
        }.onFailure { running = false }
    }

    /** 真正取一次（**必须在 App 可见时调用**，否则系统不投递）。 */
    private fun fetchOnce(ctx: Context): LocationFix? {
        val lm = runCatching {
            ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        }.getOrNull() ?: return null
        val fine = ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val coarse = ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!coarse) return null

        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { id ->
                runCatching { lm.isProviderEnabled(id) }.getOrDefault(false) &&
                    (id != LocationManager.GPS_PROVIDER || fine)
            }
        if (providers.isEmpty()) return null

        // 先看有没有足够新的缓存，省得白等
        val now = System.currentTimeMillis()
        providers.asSequence()
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .filter { now - it.time <= 120_000 }
            .minByOrNull { it.accuracy.toDouble() }
            ?.let { return it.toFix() }

        val latch = CountDownLatch(1)
        val best = AtomicReference<Location?>(null)
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val cur = best.get()
                if (cur == null || location.accuracy <= cur.accuracy) best.set(location)
                latch.countDown()
            }
            // API 26 上还是抽象方法，必须实现；29+ 起已弃用
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
            latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            best.get()?.toFix()
        } finally {
            runCatching { lm.removeUpdates(listener) }
        }
    }
}

/** Location → 纯数据类（mcpcore 里不能 import android）。 */
@Suppress("DEPRECATION")
internal fun Location.toFix(): LocationFix = LocationFix(
    latitude = latitude,
    longitude = longitude,
    accuracy = accuracy,
    altitude = if (hasAltitude()) altitude else null,
    speed = if (hasSpeed()) speed else null,
    bearing = if (hasBearing()) bearing else null,
    timeMs = time,
    provider = provider ?: "",
    mock = isMock
)
