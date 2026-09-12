package com.xtt.mcpbox.core

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * 一条命令的底层执行后端：应用沙箱 / Root / Shizuku。
 * 具体实现由 Android 层提供（Shizuku 需要它的 API），核心只依赖这个接口，
 * 所以整套逻辑可以在普通 JVM 上跑测试。
 */
interface CommandLauncher {
    val id: String
    val label: String
    val uidLabel: String

    /** 当前是否可用（su 是否存在、Shizuku 是否在跑并已授权）。 */
    fun isAvailable(): Boolean

    /** 启动一个进程执行命令，stdout/stderr 分开。 */
    fun launch(command: String, cwd: String?, env: Map<String, String>): Process

    /** 启动一个常驻 shell 供交互式终端使用。 */
    fun launchInteractive(cwd: String?, env: Map<String, String>): Process =
        launch("sh", cwd, env)

    val hint: String get() = label
}

/**
 * 通用实现：sh -c / 常驻 sh。
 * 安卓上就是 /system/bin/sh（应用自身 UID），在家用 Linux 上换成 /bin/sh 也能跑测试。
 */
class PosixShLauncher(
    override val id: String = "app",
    override val label: String = "应用沙箱",
    private val shellPath: String = "/system/bin/sh",
    override val uidLabel: String = "应用自身 UID",
    override val hint: String = "不需要额外权限，只能操作应用有权访问的文件"
) : CommandLauncher {

    override fun isAvailable(): Boolean = runCatching { File(shellPath).exists() }.getOrDefault(false)

    override fun launch(command: String, cwd: String?, env: Map<String, String>): Process {
        val pb = ProcessBuilder(shellPath, "-c", command)
        if (!cwd.isNullOrBlank()) pb.directory(File(cwd))
        pb.environment().putAll(env)
        return pb.start()
    }

    override fun launchInteractive(cwd: String?, env: Map<String, String>): Process {
        val pb = ProcessBuilder(shellPath)
        if (!cwd.isNullOrBlank()) pb.directory(File(cwd))
        pb.environment().putAll(env)
        pb.redirectErrorStream(true)
        return pb.start()
    }
}

/** Root：走 su（Magisk / KernelSU / Sui 都行）。 */
class SuLauncher(private val suPath: String? = null) : CommandLauncher {

    override val id: String = "root"
    override val label: String = "Root"
    override val uidLabel: String = "root (uid 0)"
    override val hint: String = "没检测到 su。需要设备已 root（Magisk / KernelSU / Sui）"

    private val candidates = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
        "/system/sbin/su", "/vendor/bin/su", "/debug_ramdisk/su"
    )

    val resolved: String?
        get() = suPath ?: candidates.firstOrNull { runCatching { File(it).exists() }.getOrDefault(false) }

    override fun isAvailable(): Boolean = resolved != null

    /**
     * Android 15+ 里每个进程的 /data/data 是隔离的 mount namespace：
     * 就算拿到 root，也只能看到自己 + 系统包，别家应用的数据目录会"凭空消失"。
     * 进 global mount namespace 才能看全 —— Magisk 用 `-mm`，KernelSU 用 `-M`。
     */
    private val mountFlag: String? by lazy { probeMountFlag() }

    private fun probeMountFlag(): String? {
        for (flag in listOf("-mm", "-M", "--mount-master")) {
            val count = runCatching {
                val p = ProcessBuilder(resolved ?: "su", flag, "-c", "ls /data/data | wc -l")
                    .redirectErrorStream(true)
                    .start()
                val text = p.inputStream.bufferedReader().use { it.readText() }
                p.waitFor()
                text.trim().lines().lastOrNull()?.trim()?.toIntOrNull()
            }.getOrNull()
            // 正常设备 /data/data 里至少几十个包目录
            if (count != null && count > 20) return flag
        }
        return null
    }

    /** 当前能看到多少个应用数据目录（用于界面提示/诊断）。 */
    fun visibleDataDirCount(): Int? = runCatching {
        val args = ArrayList<String>()
        args += (resolved ?: "su")
        mountFlag?.let { args += it }
        args += listOf("-c", "ls /data/data | wc -l")
        val p = ProcessBuilder(args).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        text.trim().lines().lastOrNull()?.trim()?.toIntOrNull()
    }.getOrNull()

    override fun launch(command: String, cwd: String?, env: Map<String, String>): Process {
        val su = resolved ?: "su"
        val args = ArrayList<String>()
        args += su
        mountFlag?.let { args += it }
        args += listOf("-c", command)
        val pb = ProcessBuilder(args)
        if (!cwd.isNullOrBlank()) pb.directory(File(cwd))
        pb.environment().putAll(env)
        return pb.start()
    }

    override fun launchInteractive(cwd: String?, env: Map<String, String>): Process {
        val su = resolved ?: "su"
        val pb = ProcessBuilder(su)
        if (!cwd.isNullOrBlank()) pb.directory(File(cwd))
        pb.environment().putAll(env)
        pb.redirectErrorStream(true)
        return pb.start()
    }
}

/** 环境变量与默认目录，由 Android 层填一次。 */
object ShellEnv {
    @Volatile var home: String = "/sdcard"
    @Volatile var tmp: String = "/data/local/tmp"
    @Volatile var extraPath: String = "/data/local/bin"

    fun build(): Map<String, String> = mapOf(
        // 保留系统原本的 PATH（安卓上是 .../system/bin 等），再补上我们想加的目录
        "PATH" to buildList {
            System.getenv("PATH")?.takeIf { it.isNotBlank() }?.let { add(it) }
            addAll(listOf("/sbin", "/system/sbin", "/system/bin", "/system/xbin", "/vendor/bin", extraPath.trimEnd('/')))
        }.distinct().joinToString(":"),
        "HOME" to home,
        "TMPDIR" to tmp,
        "LANG" to "zh_CN.UTF-8",
        "TERM" to "xterm-256color",
        "MCPBOX" to "1"
    )
}

/** 所有可用的执行后端。 */
object ShellBackends {
    private val list = CopyOnWriteArrayList<CommandLauncher>()

    fun register(launcher: CommandLauncher) {
        list.removeAll { it.id == launcher.id }
        list.add(launcher)
    }

    fun all(): List<CommandLauncher> = list.toList()

    fun byId(id: String?): CommandLauncher? = list.firstOrNull { it.id == id }

    fun available(): List<CommandLauncher> = list.filter { runCatching { it.isAvailable() }.getOrDefault(false) }

    /** auto = 依次尝试 shizuku → root → app；指定了就按指定的来。 */
    fun pick(preference: String, config: Config): CommandLauncher? {
        val avail = available()
        if (preference != "auto") {
            return avail.firstOrNull { it.id == preference } ?: avail.firstOrNull()
        }
        val order = config.shellPreference.split(',')
        order.forEach { id ->
            avail.firstOrNull { it.id == id.trim() }?.let { return it }
        }
        return avail.firstOrNull()
    }
}

data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val durationMs: Long,
    val backend: String,
    val command: String,
    val truncated: Boolean = false
) {
    val ok: Boolean get() = exitCode == 0 && !timedOut

    fun toText(maxChars: Int = 120_000): String {
        val sb = StringBuilder()
        sb.append("退出码：").append(exitCode)
        if (timedOut) sb.append("（超时，已强制结束）")
        sb.append("　耗时：").append(durationMs).append(" ms")
        sb.append("　后端：").append(backend).append('\n')
        sb.append("命令：").append(command).append("\n----\n")
        if (stdout.isNotBlank()) sb.append(stdout.trimEnd()).append('\n')
        if (stderr.isNotBlank()) {
            sb.append("[stderr]\n").append(stderr.trimEnd()).append('\n')
        }
        if (stdout.isBlank() && stderr.isBlank()) sb.append("（无输出）\n")
        if (truncated) sb.append("（输出过长，已截断）\n")
        val text = sb.toString()
        return if (text.length > maxChars) text.take(maxChars) + "\n...（已截断）" else text
    }
}

/** 一次性执行命令，带超时和输出上限。 */
class ShellRunner {

    fun run(
        launcher: CommandLauncher,
        command: String,
        cwd: String? = null,
        timeoutMs: Long = 60_000,
        maxOutput: Int = 200_000
    ): ShellResult {
        val started = System.currentTimeMillis()
        val process = try {
            launcher.launch(command, cwd, ShellEnv.build())
        } catch (e: Exception) {
            return ShellResult(
                exitCode = -1, stdout = "", stderr = "启动失败：${e.message}",
                timedOut = false, durationMs = 0, backend = launcher.id, command = command
            )
        }
        val outBuf = StringBuilder()
        val errBuf = StringBuilder()
        val truncated = java.util.concurrent.atomic.AtomicBoolean(false)
        val t1 = drain(process.inputStream, outBuf, maxOutput, truncated)
        val t2 = drain(process.errorStream, errBuf, maxOutput, truncated)

        // 注意：这里不能用 Process.waitFor(timeout, unit)。
        // 它的默认实现只捕获 IllegalThreadStateException，而 Shizuku 的远端进程
        // 在还没结束时抛的是 IllegalArgumentException("process hasn't exited")，
        // 会把异常直接漏出整个调用（表现为 run_shell 报 "process hasn't exited"）。
        val deadline = started + timeoutMs
        var code = -1
        var finished = false
        while (System.currentTimeMillis() < deadline) {
            val rc = tryExitCode(process)
            if (rc != null) {
                code = rc
                finished = true
                break
            }
            try {
                Thread.sleep(40)
            } catch (e: InterruptedException) {
                break
            }
        }
        if (!finished) {
            runCatching { process.destroyForcibly() }
        }
        runCatching { t1.join(1500) }
        runCatching { t2.join(1500) }
        val dur = System.currentTimeMillis() - started
        return ShellResult(
            exitCode = code,
            stdout = outBuf.toString(),
            stderr = errBuf.toString(),
            timedOut = !finished,
            durationMs = dur,
            backend = launcher.id,
            command = command,
            truncated = truncated.get()
        )
    }

    /** 进程结束就返回退出码，还没结束就返回 null（吃掉各家实现抛的不一样异常）。 */
    private fun tryExitCode(process: Process): Int? = try {
        process.exitValue()
    } catch (e: Throwable) {
        null
    }

    /** 轮询等进程结束（同样的原因：不能用 Process.waitFor(timeout)）。返回退出码，超时返回 -1。 */
    private fun waitForProcess(process: Process, deadline: Long): Pair<Int, Boolean> {
        while (System.currentTimeMillis() < deadline) {
            val rc = tryExitCode(process)
            if (rc != null) return rc to true
            try {
                Thread.sleep(30)
            } catch (e: InterruptedException) {
                break
            }
        }
        runCatching { process.destroyForcibly() }
        return -1 to false
    }

    /**
     * 只取原始 stdout 字节（二进制安全）—— 走 root 读私有目录文件时用。
     * 不转成文本，免得 UTF-8 解码把二进制内容弄坏。
     */
    fun runBytes(
        launcher: CommandLauncher,
        command: String,
        cwd: String? = null,
        timeoutMs: Long = 30_000,
        maxBytes: Long = 32L * 1024 * 1024
    ): ByteArray? {
        val process = try {
            launcher.launch(command, cwd, ShellEnv.build())
        } catch (e: Exception) {
            return null
        }
        val out = java.io.ByteArrayOutputStream()
        val reader = Thread({
            runCatching {
                process.inputStream.use { ins ->
                    val buf = ByteArray(16384)
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        val room = maxBytes - out.size()
                        if (room > 0) out.write(buf, 0, minOf(n.toLong(), room).toInt())
                    }
                }
            }
        }, "bridge-stdout").apply { isDaemon = true; start() }
        val errReader = Thread({
            runCatching { process.errorStream.use { it.readBytes() } }
        }, "bridge-stderr").apply { isDaemon = true; start() }

        waitForProcess(process, System.currentTimeMillis() + timeoutMs)
        runCatching { reader.join(2000) }
        runCatching { errReader.join(500) }
        return out.toByteArray()
    }

    /** 把二进制内容喂给命令的 stdin（走 root 写文件时用）。 */
    fun runWithStdin(
        launcher: CommandLauncher,
        command: String,
        stdin: ByteArray,
        cwd: String? = null,
        timeoutMs: Long = 120_000
    ): ShellResult {
        val started = System.currentTimeMillis()
        val process = try {
            launcher.launch(command, cwd, ShellEnv.build())
        } catch (e: Exception) {
            return ShellResult(-1, "", "启动失败：${e.message}", false, 0, launcher.id, command)
        }
        val outBuf = StringBuilder()
        val errBuf = StringBuilder()
        val truncated = java.util.concurrent.atomic.AtomicBoolean(false)
        val t1 = drain(process.inputStream, outBuf, 64_000, truncated)
        val t2 = drain(process.errorStream, errBuf, 64_000, truncated)
        val writer = Thread({
            runCatching {
                process.outputStream.use {
                    it.write(stdin)
                    it.flush()
                }
            }
        }, "bridge-stdin").apply { isDaemon = true; start() }

        val (code, finished) = waitForProcess(process, started + timeoutMs)
        runCatching { writer.join(2000) }
        runCatching { t1.join(1500) }
        runCatching { t2.join(800) }
        return ShellResult(
            exitCode = code,
            stdout = outBuf.toString(),
            stderr = errBuf.toString(),
            timedOut = !finished,
            durationMs = System.currentTimeMillis() - started,
            backend = launcher.id,
            command = command,
            truncated = truncated.get()
        )
    }

    private fun drain(
        stream: java.io.InputStream,
        sink: StringBuilder,
        maxChars: Int,
        truncated: java.util.concurrent.atomic.AtomicBoolean
    ): Thread = Thread({
        runCatching {
            InputStreamReader(stream, Charsets.UTF_8).buffered(8192).use { reader ->
                val buf = CharArray(8192)
                while (true) {
                    val n = reader.read(buf)
                    if (n <= 0) break
                    synchronized(sink) {
                        if (sink.length < maxChars) {
                            sink.append(buf, 0, minOf(n, maxChars - sink.length))
                        } else {
                            truncated.set(true)
                        }
                    }
                }
            }
        }
    }, "shell-drain").apply { isDaemon = true; start() }
}

/**
 * 常驻交互式会话：给 App 里的「终端」用。
 * 命令直接写进同一个 shell 的 stdin，所以 cd / export 这些状态会保留。
 */
class ShellSession(
    private val launcher: CommandLauncher,
    private val cwd: String? = null,
    private val onOutput: (String) -> Unit,
    private val onExit: (Int) -> Unit = {}
) {
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: Thread? = null
    private var stderrReader: Thread? = null

    @Volatile var alive: Boolean = false
        private set

    @Volatile var startError: String? = null
        private set

    fun start(): Boolean {
        kill()
        return try {
            val p = launcher.launchInteractive(cwd, ShellEnv.build())
            process = p
            writer = BufferedWriter(OutputStreamWriter(p.outputStream, Charsets.UTF_8))
            alive = true
            startError = null
            reader = Thread({
                runCatching {
                    BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8), 8192).use { r ->
                        val buf = CharArray(4096)
                        while (true) {
                            val n = r.read(buf)
                            if (n <= 0) break
                            onOutput(String(buf, 0, n))
                        }
                    }
                }.onFailure { onOutput("\n[读取输出失败：${it.message}]\n") }
                alive = false
                val code = runCatching { p.exitValue() }.getOrDefault(-1)
                onExit(code)
            }, "shell-session-reader").apply { isDaemon = true; start() }

            // Shizuku 之类的后端不会合并 stderr，单独再读一路（合并过的流会立刻 EOF，无害）
            stderrReader = Thread({
                runCatching {
                    BufferedReader(InputStreamReader(p.errorStream, Charsets.UTF_8), 4096).use { r ->
                        val buf = CharArray(2048)
                        while (true) {
                            val n = r.read(buf)
                            if (n <= 0) break
                            onOutput(String(buf, 0, n))
                        }
                    }
                }
            }, "shell-session-stderr").apply { isDaemon = true; start() }
            // 让用户看到自己处在哪个身份/目录
            send("echo \"[MCP 文件盒] uid=$(id -u 2>/dev/null) @ $(pwd)\"")
            true
        } catch (e: Exception) {
            startError = e.message ?: e.javaClass.simpleName
            alive = false
            false
        }
    }

    fun send(command: String) {
        val w = writer ?: return
        try {
            w.write(command)
            if (!command.endsWith("\n")) w.write("\n")
            w.flush()
        } catch (e: Exception) {
            onOutput("\n[写入失败：${e.message}]\n")
        }
    }

    fun kill() {
        alive = false
        runCatching { writer?.close() }
        writer = null
        runCatching { process?.destroyForcibly() }
        process = null
        runCatching { reader?.interrupt() }
        reader = null
        runCatching { stderrReader?.interrupt() }
        stderrReader = null
    }
}

/** 从一条命令里取出「基础命令名」，用于「始终允许 xxx」的前缀规则。 */
fun commandPrefix(command: String): String {
    val tokens = command.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    for (t in tokens) {
        val eq = t.indexOf('=')
        if (eq > 0 && t.substring(0, eq).matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) continue // FOO=bar cmd
        if (t == "sudo" || t == "su" || t == "env" || t == "nohup" || t == "time") continue
        return t
    }
    return tokens.firstOrNull() ?: command.take(32)
}
