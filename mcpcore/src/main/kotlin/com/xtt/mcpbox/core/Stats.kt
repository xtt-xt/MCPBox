// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * 统计快照：界面只读这个，不碰内部可变状态。
 *
 * - [requestsByDay] 按自然日（设备本地时区）存的总请求次数，热力图直接用它
 * - [serverMillis]  服务器累计运行时长，**已经含当前这一段**（在跑就实时涨）
 */
data class StatsSnapshot(
    val firstAt: Long = 0L,
    val appLaunches: Long = 0L,
    val serverStarts: Long = 0L,
    val serverMillis: Long = 0L,
    val requestsTotal: Long = 0L,
    val requestsByDay: Map<String, Long> = emptyMap(),
    /** 当前这一段运行的起点；0 = 服务器没在跑。 */
    val serverRunningSince: Long = 0L
) {
    val serverRunning: Boolean get() = serverRunningSince > 0L

    /** 有记录的天数。 */
    val activeDays: Int get() = requestsByDay.count { it.value > 0L }

    /**
     * 按天相加的总数。
     *
     * 正常情况下它等于 [requestsTotal]。不相等说明「按天聚合」那一步漏了或者去重了，
     * 热力图就会和卡片上的总数对不上 —— 界面只在真的不等时才记一条日志，不刷屏。
     */
    val dailySum: Long get() = requestsByDay.values.sum()
}

/**
 * 使用统计。**只增不减**：没有任何入口会把它清零，
 * 「重置全部设置」也不动它（它不在 SharedPreferences 里，是独立的一个文件）。
 *
 * 记什么：
 *  - 每日请求次数（POST /mcp 里每条 JSON-RPC 消息算一次）
 *  - 服务器累计运行时长（服务在跑就累加，进程被杀最多丢最后一次落盘前的十几秒）
 *  - 打开应用次数（每次冷启动进程 +1）
 *  - 服务器启动次数
 *
 * 落盘策略：请求很密集，不能每来一条就写一次文件 —— 打上 dirty 标记，
 * 由一个 15 秒一轮的守护线程统一写；而「打开应用 / 启动服务器 / 停止服务器」
 * 这种低频又重要的动作直接写。
 *
 * [file] 为 null 时纯内存运行（单元测试用）。写入是原子的（先写 .tmp 再改名）。
 */
class StatsStore(
    private val file: File? = null,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /** 落盘格式。`_type` 让备份导入时能认出这是统计（见 [Backup.sniff]）。 */
    @Serializable
    data class Data(
        @SerialName("_type") val type: String = TYPE,
        val version: Int = VERSION,
        val app: String = "",
        val updatedAt: Long = 0L,
        val firstAt: Long = 0L,
        val appLaunches: Long = 0L,
        val serverStarts: Long = 0L,
        val serverMillis: Long = 0L,
        val requestsTotal: Long = 0L,
        val requests: Map<String, Long> = emptyMap()
    )

    companion object {
        const val TYPE = "mcpbox.stats"
        const val VERSION = 1

        /** 攒一会儿再写文件，别把请求变成磁盘压力。 */
        private const val SAVE_INTERVAL_MS = 15_000L

        /** 某时刻属于哪个自然日（设备本地时区）。 */
        fun dayKey(now: Long, zone: ZoneId = ZoneId.systemDefault()): String =
            java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString()

        /** 今天。 */
        fun todayKey(now: Long = System.currentTimeMillis()): String = dayKey(now)
    }

    private val J = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()

    private var data = Data()
    /** 当前这段运行的起点；0 = 没在跑。 */
    private var sessionStart = 0L
    private var dirty = false
    /** 「打开应用」一个进程只记一次（转屏 / 从最近任务切回不会重复记）。 */
    private var launchCounted = false
    private var saver: Thread? = null

    // ------------------------------------------------------------------ 读写

    fun load() {
        val f = file ?: return
        synchronized(lock) {
            val text = runCatching { if (f.exists()) f.readText() else null }.getOrNull() ?: return
            data = runCatching { J.decodeFromString(Data.serializer(), text) }
                .getOrElse { Data() }
        }
        ensureSaver()
    }

    /** 立刻落盘（进程要退出时用）。 */
    fun flush() {
        synchronized(lock) { saveLocked() }
    }

    /** 按天分组的请求次数（热力图用）。 */
    fun snapshot(): StatsSnapshot = synchronized(lock) {
        val now = clock()
        StatsSnapshot(
            firstAt = data.firstAt,
            appLaunches = data.appLaunches,
            serverStarts = data.serverStarts,
            serverMillis = data.serverMillis + (if (sessionStart > 0L) now - sessionStart else 0L),
            requestsTotal = data.requestsTotal,
            requestsByDay = LinkedHashMap(data.requests),
            serverRunningSince = sessionStart
        )
    }

    // ------------------------------------------------------------------ 记账

    /** 记 [n] 条 MCP 消息。 */
    fun noteRequests(n: Int) {
        if (n <= 0) return
        val now = clock()
        synchronized(lock) {
            val day = dayKey(now)
            val days = LinkedHashMap(data.requests)
            days[day] = (days[day] ?: 0L) + n
            data = data.copy(
                requests = days,
                requestsTotal = data.requestsTotal + n,
                firstAt = if (data.firstAt == 0L) now else data.firstAt
            )
            dirty = true
        }
        ensureSaver()
    }

    fun noteRequest() = noteRequests(1)

    /** 打开一次 App（一个进程只算一次）。 */
    fun noteAppLaunch() {
        val now = clock()
        synchronized(lock) {
            if (launchCounted) return
            launchCounted = true
            data = data.copy(
                appLaunches = data.appLaunches + 1,
                firstAt = if (data.firstAt == 0L) now else data.firstAt
            )
        }
        synchronized(lock) { saveLocked() }
    }

    /** 服务器起来了：次数 +1，并开始给运行时长计时。 */
    fun noteServerStart() {
        val now = clock()
        synchronized(lock) {
            foldSessionLocked(now)
            sessionStart = now
            data = data.copy(
                serverStarts = data.serverStarts + 1,
                firstAt = if (data.firstAt == 0L) now else data.firstAt
            )
        }
        synchronized(lock) { saveLocked() }
    }

    /** 服务器停了：把这一段时长并进累计值。 */
    fun noteServerStop() {
        synchronized(lock) {
            foldSessionLocked(clock())
            sessionStart = 0L
            saveLocked()
        }
    }

    // ------------------------------------------------------------------ 备份

    /** 导出成一段 JSON（备份用）。 */
    fun exportJson(): String = synchronized(lock) {
        foldSessionLocked(clock())
        J.encodeToString(Data.serializer(), data.copy(updatedAt = clock(), app = ServerMeta.fullVersion))
    }

    /**
     * 从备份写回。
     *
     * 累计量**取较大值**而不是相加 —— 同一份备份恢复两次不会把数字翻倍，
     * 装到新机上也能把老机的记录原样带过来（覆盖模式则直接用备份里的）。
     */
    fun importJson(text: String, merge: Boolean): String {
        val incoming = runCatching { J.decodeFromString(Data.serializer(), text) }.getOrElse {
            throw ToolFailure(L("不是合法的统计备份：%s").format(it.message))
        }
        synchronized(lock) {
            foldSessionLocked(clock())
            data = if (merge) merged(data, incoming) else incoming.copy(updatedAt = clock())
            saveLocked()
            return L("已恢复 %s 天记录 · 总请求 %s 次")
                .format(data.requests.size, data.requestsTotal)
        }
    }

    /** 合并两份统计：按天取较大值，累计量同样取较大值，起始时间取更早的。 */
    private fun merged(a: Data, b: Data): Data {
        val days = LinkedHashMap(a.requests)
        b.requests.forEach { (k, v) -> days[k] = maxOf(days[k] ?: 0L, v) }
        val starts = listOf(a.firstAt, b.firstAt).filter { it > 0L }
        return Data(
            updatedAt = clock(),
            app = if (b.app.isNotBlank()) b.app else a.app,
            firstAt = starts.minOrNull() ?: 0L,
            appLaunches = maxOf(a.appLaunches, b.appLaunches),
            serverStarts = maxOf(a.serverStarts, b.serverStarts),
            serverMillis = maxOf(a.serverMillis, b.serverMillis),
            requestsTotal = maxOf(a.requestsTotal, b.requestsTotal),
            requests = days
        )
    }

    // ------------------------------------------------------------------ 内部

    /** 把「当前这一段」的时长并进累计值，并把起点挪到现在（可反复调用）。 */
    private fun foldSessionLocked(now: Long) {
        if (sessionStart <= 0L) return
        data = data.copy(serverMillis = data.serverMillis + (now - sessionStart).coerceAtLeast(0L))
        sessionStart = now
    }

    private fun saveLocked() {
        val f = file ?: run { dirty = false; return }
        val now = clock()
        foldSessionLocked(now)
        data = data.copy(updatedAt = now, app = ServerMeta.fullVersion)
        dirty = false
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(J.encodeToString(Data.serializer(), data))
            if (!tmp.renameTo(f)) {
                f.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    /** 起一个后台落盘线程（有文件才需要）。 */
    private fun ensureSaver() {
        if (file == null || saver != null) return
        synchronized(lock) {
            if (saver != null) return
            val t = Thread({
                while (true) {
                    try {
                        Thread.sleep(SAVE_INTERVAL_MS)
                    } catch (e: InterruptedException) {
                        return@Thread
                    }
                    if (dirty) runCatching { synchronized(lock) { saveLocked() } }
                }
            }, "mcp-stats")
            t.isDaemon = true
            t.start()
            saver = t
        }
    }
}

/** 热力图用的一格：某一天的请求次数。 */
data class HeatDay(val date: LocalDate, val count: Long)

/**
 * 把每日请求次数摊成「按周分列」的网格：一列 = 一周（周一起），
 * 行 = 周一到周日。返回的每一列固定 7 格，早于 [from] 的格是 null（画成空白）。
 *
 * 这个是纯函数，没碰 Compose —— 想换起始日期 / 想测都方便。
 */
fun heatWeeks(
    byDay: Map<String, Long>,
    today: LocalDate,
    from: LocalDate
): List<List<HeatDay?>> {
    // 起点对齐到那一周的周一
    var cursor = from.minusDays((from.dayOfWeek.value - 1).toLong())
    val out = ArrayList<List<HeatDay?>>()
    val last = today
    while (!cursor.isAfter(last)) {
        val week = ArrayList<HeatDay?>(7)
        for (i in 0 until 7) {
            val d = cursor.plusDays(i.toLong())
            week.add(if (d.isBefore(from) || d.isAfter(last)) null else HeatDay(d, byDay[d.toString()] ?: 0L))
        }
        out.add(week)
        cursor = cursor.plusDays(7)
    }
    return out
}

/** 热力图的分档方式。 */
enum class HeatScaleKind(val id: String) {
    /** 线性：`v / max`，简单直观，但单日极值会把其余日子压得很暗。 */
    LINEAR("linear"),

    /** 平方根：`sqrt(v / max)`，中低段层次更好（数据量少的时候用）。 */
    SQRT("sqrt"),

    /** 分位数：按 P50 / P75 / P90 分档，不受极值影响（分布极不均匀时用）。 */
    QUANTILE("quantile")
}

/**
 * 热力图的色阶：`次数 → 等级(0..4) → 颜色`。
 *
 * 中间隔这一层是为了让**颜色和数值解耦**：换配色、加档位都只动颜色那一半，
 * 算法这边只回答「这天算第几档」。
 *
 * 三套算法各有失效边界，所以按数据自己挑：
 *  - 记录少（不到 20 天）或全 0：线性 —— 样本太少时任何统计量都没意义；
 *  - `max / median > 10`：分位数 —— 有一天特别忙的话，线性会把别的日子全压成一档；
 *  - 其余：平方根 —— 中低段（1~20 次）的层次比线性清楚。
 */
data class HeatScale(
    val kind: HeatScaleKind,
    val max: Long,
    val median: Long,
    /** 非 0 的档位边界（次数 ≤ 边界 即落到对应档）；分位数模式下是 P50/P75/P90/P99。 */
    val cuts: List<Double>,
    /** 有记录（> 0）的天数。 */
    val activeDays: Int
) {
    /** 0 = 没有请求；1..4 = 由浅到深。 */
    fun level(count: Long): Int {
        if (count <= 0L) return 0
        if (max <= 0L) return 0
        val v = when (kind) {
            HeatScaleKind.LINEAR -> count.toDouble() / max
            HeatScaleKind.SQRT -> kotlin.math.sqrt(count.toDouble() / max)
            HeatScaleKind.QUANTILE -> 0.0 // 分位数直接用 cuts 比
        }
        if (kind == HeatScaleKind.QUANTILE) {
            return when {
                count <= cuts[0] -> 1
                count <= cuts[1] -> 2
                count <= cuts[2] -> 3
                else -> 4
            }
        }
        return when {
            v <= 0.25 -> 1
            v <= 0.5 -> 2
            v <= 0.75 -> 3
            else -> 4
        }
    }

    companion object {
        /** 少于这么多天就不做统计判断，直接线性。 */
        const val MIN_DAYS_FOR_ADAPTIVE = 20

        /** 最忙那天超过中位数这么多倍，就改用分位数。 */
        const val OUTLIER_RATIO = 10.0

        fun of(counts: Collection<Long>): HeatScale {
            val positive = counts.filter { it > 0L }.sorted()
            val max = positive.maxOrNull() ?: 0L
            if (positive.isEmpty()) {
                return HeatScale(HeatScaleKind.LINEAR, 0L, 0L, listOf(0.0, 0.0, 0.0, 0.0), 0)
            }
            val median = percentile(positive, 50.0)
            val p = { q: Double -> percentile(positive, q) }
            val qCuts = listOf(p(50.0), p(75.0), p(90.0), p(99.0))
            val kind = when {
                positive.size < MIN_DAYS_FOR_ADAPTIVE -> HeatScaleKind.LINEAR
                median > 0 && max.toDouble() / median > OUTLIER_RATIO -> HeatScaleKind.QUANTILE
                else -> HeatScaleKind.SQRT
            }
            return HeatScale(
                kind = kind,
                max = max,
                median = median.toLong(),
                cuts = if (kind == HeatScaleKind.QUANTILE) qCuts
                else listOf(max * 0.25, max * 0.5, max * 0.75, max.toDouble()),
                activeDays = positive.size
            )
        }

        /** 最近秩法分位数（够用，不引入统计库）。 */
        private fun percentile(sorted: List<Long>, q: Double): Double {
            if (sorted.isEmpty()) return 0.0
            val rank = kotlin.math.ceil(q / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
            return sorted[rank - 1].toDouble()
        }
    }
}

/** 兼容旧调用：按线性算一档。新代码请用 [HeatScale.level]。 */
fun heatLevel(count: Long, max: Long): Int = HeatScale.of(listOf(max)).level(count)

