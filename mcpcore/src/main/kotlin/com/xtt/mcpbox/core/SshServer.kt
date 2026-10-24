// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.core

import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.file.FileSystemFactory
import org.apache.sshd.common.session.Session
import org.apache.sshd.common.session.SessionContext
import org.apache.sshd.common.session.SessionListener
import org.apache.sshd.server.Environment
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.AbstractCommandSupport
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ShellFactory
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.sftp.common.SftpConstants
import org.apache.sshd.sftp.common.SftpException
import org.apache.sshd.sftp.server.SftpSubsystem
import org.apache.sshd.sftp.server.SftpSubsystemConfigurator
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.CopyOption
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * SSH / SFTP 服务端。
 *
 * 为什么自己做服务端而不是让用户装 Termux：客户端（RikkaHub 的「SSH 工作区」）要的是
 * 「一台能读写的 Linux 机器」，而我们真正想给的是**这台手机上的文件**。自己起 sshd 就能
 * 把 SFTP 的每一次路径解析都过一遍 [PathSandbox]，「允许访问的目录」这条白名单照旧生效。
 *
 * 三条硬约束（改之前先读）：
 *  ① 路径白名单只有 [PathSandbox] 一处判定，别在别处再写一套；
 *  ② 只读模式（`config.readOnly`）在 SFTP 的写操作入口统一拦，别漏掉 mkdir / rename / setstat；
 *  ③ 命令执行是**独立开关**，默认关：关掉时连 commandFactory 都不装，客户端 exec 会直接被拒。
 */
class PhoneSshServer(
    private val config: Config,
    private val sandbox: PathSandbox,
    private val log: EventLog,
    /** 主机密钥的落脚点（App 私有目录）。 */
    private val keyDir: File
) {

    /**
     * 注意：这里不能直接用构造参数名 `log` —— [GuardedSftpSubsystem] 继承自 MINA 的
     * AbstractLoggingBean，它自带一个 slf4j 的 `log` 字段，会把 `eventLog.add(...)` 吃掉。
     */
    private val eventLog: EventLog = log

    data class Status(
        val running: Boolean,
        val port: Int,
        val lastError: String?,
        val fingerprint: String,
        val connections: Int
    )

    @Volatile
    private var server: SshServer? = null

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var fingerprint: String = ""
        private set

    private val clients = CopyOnWriteArrayList<Session>()

    /**
     * 看门狗线程：MINA 在这台机器上出现过「TCP 还能连上、但再也不回 banner」的挂死，
     * 端口一直 LISTEN、accept 队列一直堆积，重启服务就恢复。与其等用户去设置页开关一次，
     * 不如自己每分钟敲一次门，连续两次没人应就原地重启。
     */
    @Volatile
    private var watchdog: Thread? = null

    /** 看门狗自己发起的那次连接，不要写进活动日志。 */
    @Volatile
    private var probing = false

    val isRunning: Boolean get() = server?.isOpen == true

    fun start(): Boolean {
        if (isRunning) return true
        lastError = null
        // MINA 的日志默认是 INFO，会把握手细节刷到 stderr（Android 上就是 logcat）。
        // 只留告警：真出问题时 slf4j-simple 的 WARN/ERROR 仍然能看见。
        if (System.getProperty("org.slf4j.simpleLogger.defaultLogLevel") == null) {
            System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn")
        }
        return try {
            val s = build()
            s.start()
            server = s
            fingerprint = runCatching { computeFingerprint(s) }.getOrDefault("")
            eventLog.add(
                LogKind.SYSTEM,
                message = L("SSH 服务已启动，端口 %s").format(config.sshPort)
            )
            startWatchdog()
            true
        } catch (t: Throwable) {
            val msg = t.message ?: t.javaClass.simpleName
            lastError = msg
            eventLog.add(LogKind.ERROR, ok = false, message = L("SSH 服务启动失败：%s").format(msg))
            runCatching { server?.stop(true) }
            server = null
            false
        }
    }

    fun stop() {
        watchdog?.interrupt()
        watchdog = null
        val s = server ?: return
        server = null
        clients.clear()
        runCatching { s.stop(true) }
        eventLog.add(LogKind.SYSTEM, message = L("SSH 服务已停止"))
    }

    // ---------------------------------------------------------------- 看门狗

    private fun startWatchdog() {
        if (watchdog?.isAlive == true) return
        watchdog = Thread({
            var misses = 0
            while (true) {
                try {
                    Thread.sleep(PROBE_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    return@Thread
                }
                val current = server ?: return@Thread
                if (!current.isOpen) return@Thread
                if (probeBanner()) {
                    misses = 0
                    continue
                }
                misses++
                if (misses < 2) continue
                misses = 0
                // 再确认一次还没被换掉，避免和用户手动重启撞车
                if (server !== current) continue
                eventLog.add(LogKind.ERROR, ok = false, message = L("SSH 服务无响应，已自动重启"))
                server = null
                runCatching { current.stop(true) }
                start()
                return@Thread
            }
        }, "ssh-watchdog").apply {
            isDaemon = true
            start()
        }
    }

    /** 连一下自己的端口，半秒内能读到 SSH 版本横幅就算活着。 */
    private fun probeBanner(): Boolean {
        probing = true
        return try {
            java.net.Socket().use { sock ->
                sock.connect(java.net.InetSocketAddress("127.0.0.1", config.sshPort), 2000)
                sock.soTimeout = 3000
                val line = sock.getInputStream().bufferedReader().readLine()
                line != null && line.startsWith("SSH-")
            }
        } catch (e: Exception) {
            false
        } finally {
            probing = false
        }
    }

    /** 配置改了（端口 / 认证 / 命令开关）后按需要重启。 */
    fun restartIfRunning(): Boolean {
        if (!isRunning) return false
        stop()
        return start()
    }

    private fun build(): SshServer {
        val s = SshServer.setUpDefaultServer()
        // 只监听本机时绑定 127.0.0.1：手机上的客户端（RikkaHub）走 loopback 就够了，
        // 局域网要显式打开，密码认证暴露在 Wi-Fi 里不是一个好默认。
        s.host = if (config.sshBindAll) "0.0.0.0" else "127.0.0.1"
        s.port = config.sshPort
        s.keyPairProvider = hostKeyProvider()

        // 会话别被空闲超时掐掉：工作区是长期挂着的（默认 10 分钟就会断开）。
        // 这里必须给**秒数**（Long）：MINA 的会话超时检查是按数字解析这个属性的，
        // 塞 Duration 进去会在计时线程里抛 NumberFormatException（实测过）。
        org.apache.sshd.common.PropertyResolverUtils.updateProperty(
            s, org.apache.sshd.core.CoreModuleProperties.IDLE_TIMEOUT.name, 7L * 24 * 3600
        )

        s.passwordAuthenticator = PasswordAuthenticator { username, password, _ ->
            val ok = config.sshPasswordEnabled &&
                username == config.sshUser &&
                config.sshPassword.isNotEmpty() &&
                password == config.sshPassword
            if (!ok) eventLog.add(LogKind.SYSTEM, ok = false, message = L("SSH 密码认证失败：%s").format(username))
            ok
        }

        s.publickeyAuthenticator = PublickeyAuthenticator { username, key, _ ->
            val allowed = username == config.sshUser && config.sshPubkeyEnabled && keyAllowed(key)
            if (!allowed) {
                eventLog.add(LogKind.SYSTEM, ok = false, message = L("SSH 公钥认证失败：%s").format(username))
            }
            allowed
        }

        // 虚拟路径 = 真实绝对路径（不做 chroot）：白名单由 SftpGuard 统一判定，
        // 这样客户端看到的 /storage/emulated/0/... 和我们配置里的路径是同一套。
        s.fileSystemFactory = object : FileSystemFactory {
            override fun getUserHomeDir(session: SessionContext?): Path =
                File(config.primaryRoot()).absoluteFile.toPath()

            override fun createFileSystem(session: SessionContext?): FileSystem = FileSystems.getDefault()
        }

        s.subsystemFactories = listOf(GuardedSftpFactory())
        s.addSessionListener(object : SessionListener {
            override fun sessionCreated(session: Session) {
                clients.add(session)
                if (!probing) eventLog.add(LogKind.SYSTEM, message = L("SSH 客户端已连接"))
            }

            override fun sessionException(session: Session, t: Throwable) {
                System.err.println("MCPBOX-SSH session error: $t")
            }

            override fun sessionDisconnect(session: Session, reason: Int, msg: String?, language: String?, initiator: Boolean) {
                System.err.println("MCPBOX-SSH disconnect reason=$reason msg=$msg")
            }

            override fun sessionClosed(session: Session) {
                clients.remove(session)
                if (!probing) eventLog.add(LogKind.SYSTEM, message = L("SSH 客户端已断开"))
            }
        })

        if (config.sshShellEnabled) {
            s.commandFactory = CommandFactory { _, command -> RemoteCommand(command) }
            s.shellFactory = ShellFactory { RemoteCommand(null) }
        }
        return s
    }

    private fun hostKeyProvider(): SimpleGeneratorHostKeyProvider {
        keyDir.mkdirs()
        val file = File(keyDir, "host_key.pem")
        return SimpleGeneratorHostKeyProvider(file.toPath()).apply {
            algorithm = "RSA"
            keySize = 3072
        }
    }

    private fun computeFingerprint(s: SshServer): String {
        val key = s.keyPairProvider.loadKeys(null).firstOrNull()?.public ?: return ""
        val line = PublicKeyEntry.toString(key)
        val blob = line.split(' ').getOrNull(1) ?: return ""
        val raw = Base64.getDecoder().decode(blob)
        val digest = MessageDigest.getInstance("SHA-256").digest(raw)
        val b64 = Base64.getEncoder().withoutPadding().encodeToString(digest)
        return "SHA256:$b64"
    }

    // ---------------------------------------------------------------- 认证

    private fun keyAllowed(key: PublicKey): Boolean {
        val given = runCatching { PublicKeyEntry.toString(key).split(' ').getOrNull(1) }.getOrNull() ?: return false
        return authorizedLines().any { line ->
            val blob = line.trim().split(' ').getOrNull(1) ?: return@any false
            blob == given
        }
    }

    private fun authorizedLines(): List<String> =
        (listOf(config.sshKeyPublic) + config.sshExtraKeys.split('\n'))
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

    // ---------------------------------------------------------------- SFTP 守门

    /**
     * 所有 SFTP 操作都会先经过 [SftpSubsystem.resolveFile]，在这里把虚拟路径换算成真实文件、
     * 交给 [PathSandbox] 判一次白名单。越界直接回 SFTP 的 permission denied。
     */
    private inner class GuardedSftpFactory : SftpSubsystemFactory() {
        override fun createSubsystem(channel: ChannelSession): org.apache.sshd.server.command.Command =
            GuardedSftpSubsystem(channel, this)
    }

    private inner class GuardedSftpSubsystem(
        channel: ChannelSession,
        cfg: SftpSubsystemConfigurator
    ) : SftpSubsystem(channel, cfg) {

        override fun resolveFile(remotePath: String): Path {
            // 客户端的视角和我们不一样：RikkaHub 把「工作目录」当成它自己的根，
            // 打开文件页第一件事就是列 "/"，之后每一级都用 "/Download" 这种
            // 「相对它自己那个根」的写法。所以这里按 chroot 处理：
            //  · "" / "." / "~" / "/" → 主根目录；
            //  · 命中白名单的绝对路径（/storage/emulated/0/...）原样使用，
            //    给 sshfs、命令行 sftp 这类客户端留后路；
            //  · 其余路径（包括所有相对路径）都挂到主根目录下面。
            // 越界的仍然交给 [guard] 拒掉，白名单只有 PathSandbox 一处判定。
            val root = File(config.primaryRoot()).absoluteFile.toPath()
            val raw = remotePath.trim()
            val mapped = if (raw.isEmpty() || raw == "." || raw == "./" || raw == "~" || raw == "~/" || raw == "/") {
                root
            } else {
                val direct = runCatching { super.resolveFile(raw) }.getOrNull()
                when {
                    direct != null && allowed(direct) -> direct
                    else -> root.resolve(relativePart(raw))
                }
            }
            guard(mapped, remotePath)
            return mapped
        }

        /** 去掉开头的 `/`、`~/`、`./`，得到一个可以挂在主根目录下面的相对路径。 */
        private fun relativePart(raw: String): String {
            var p = raw.replace('\\', '/')
            if (p.startsWith("~/")) p = p.substring(2) else if (p.startsWith("~")) p = p.substring(1)
            return p.trimStart('/')
        }

        /** 这个真实路径是不是已经在白名单里（不抛异常版本）。 */
        private fun allowed(path: Path): Boolean = try {
            sandbox.resolve(path.toAbsolutePath().toString(), mustExist = false)
            true
        } catch (e: Exception) {
            false
        }

        private fun guard(path: Path, raw: String) {
            try {
                sandbox.resolve(path.toAbsolutePath().toString(), mustExist = false)
            } catch (e: SandboxException) {
                eventLog.add(
                    LogKind.SYSTEM, ok = false,
                    message = L("SSH 拒绝越界路径：%s").format(raw)
                )
                throw SftpException(SftpConstants.SSH_FX_PERMISSION_DENIED, e.message ?: L("路径超出允许范围"))
            } catch (e: Exception) {
                throw SftpException(SftpConstants.SSH_FX_PERMISSION_DENIED, e.message ?: L("路径无权访问"))
            }
        }

        // ---- 只读模式：写操作全部拦下（读照旧）

        private fun denyReadOnly(): Nothing = throw SftpException(
            SftpConstants.SSH_FX_PERMISSION_DENIED,
            L("当前是只读模式，SSH 不能修改文件")
        )

        override fun doOpen(id: Int, handle: String, access: Int, flags: Int, attrs: Map<String, *>?): String {
            if (config.readOnly && writes(access, flags)) denyReadOnly()
            return super.doOpen(id, handle, access, flags, attrs)
        }

        override fun doWrite(id: Int, handle: String, fileOffset: Long, dataOffset: Int, data: ByteArray, dataLen: Int, offset: Int) {
            if (config.readOnly) denyReadOnly()
            super.doWrite(id, handle, fileOffset, dataOffset, data, dataLen, offset)
        }

        override fun doMakeDirectory(id: Int, path: String, attrs: Map<String, *>?) {
            if (config.readOnly) denyReadOnly()
            super.doMakeDirectory(id, path, attrs)
        }

        override fun doRemove(id: Int, path: Path, isDirectory: Boolean) {
            if (config.readOnly) denyReadOnly()
            super.doRemove(id, path, isDirectory)
        }

        override fun doRename(id: Int, oldPath: String, newPath: String, opts: Collection<CopyOption>?) {
            if (config.readOnly) denyReadOnly()
            super.doRename(id, oldPath, newPath, opts)
        }

        override fun doSetAttributes(id: Int, path: String, attrs: Path, flags: Map<String, *>?, followLinks: Boolean) {
            if (config.readOnly) denyReadOnly()
            super.doSetAttributes(id, path, attrs, flags, followLinks)
        }

        override fun createLink(id: Int, linkPath: String, targetPath: String, symbolic: Boolean) {
            if (config.readOnly) denyReadOnly()
            super.createLink(id, linkPath, targetPath, symbolic)
        }

        /**
         * 这次 open 是不是「写」。
         *
         * **不要自己拆 access / flags 的位**：MINA 传进来的这两个 int 不是客户端的原始
         * pflags（实测同一个 put 会变成 access=1、flags=258），直接问它自己的
         * [FileHandle.getOpenOptions] 拿标准 open 选项最稳。
         */
        private fun writes(access: Int, flags: Int): Boolean {
            val opts = runCatching {
                org.apache.sshd.sftp.server.FileHandle.getOpenOptions(access, flags)
            }.getOrNull() ?: return true
            return opts.any {
                it == java.nio.file.StandardOpenOption.WRITE ||
                    it == java.nio.file.StandardOpenOption.APPEND ||
                    it == java.nio.file.StandardOpenOption.CREATE ||
                    it == java.nio.file.StandardOpenOption.CREATE_NEW ||
                    it == java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
            }
        }
    }

    // ---------------------------------------------------------------- 命令

    /**
     * 一条命令专用的执行器。
     *
     * 为什么是「每条命令一个」而不是共用一个池：MINA 在通道结束时会把命令的 executor
     * `close()` / `shutdownNow()` 掉，共用池就会「第一条能跑，之后全拒」。
     * 每条命令自己一个、随命令一起收，双方都不别扭。
     *
     * 为什么必须给 executor（不能传 null）：`AbstractCommandSupport` 在没有 executor 时
     * 会直接在**调用线程**上 `run()`，而那是 MINA 的 Nio2 IO 线程 —— 一条
     * `waitFor()` 的长命令能把整个 SSH 服务器挂死（实测：之后所有连接连 banner 都收不到）。
     */
    private class CommandExecutor : org.apache.sshd.common.util.threads.CloseableExecutorService {

        private val pool: java.util.concurrent.ExecutorService =
            java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                Thread(r, "ssh-exec").apply { isDaemon = true }
            }

        override fun execute(command: Runnable) = pool.execute(command)
        override fun submit(task: Runnable): java.util.concurrent.Future<*> = pool.submit(task)
        override fun <T> submit(task: java.util.concurrent.Callable<T>): java.util.concurrent.Future<T> =
            pool.submit(task)
        override fun <T> submit(task: Runnable, result: T): java.util.concurrent.Future<T> =
            pool.submit(task, result)
        override fun <T> invokeAll(tasks: MutableCollection<out java.util.concurrent.Callable<T>>): MutableList<java.util.concurrent.Future<T>> =
            pool.invokeAll(tasks)
        override fun <T> invokeAll(
            tasks: MutableCollection<out java.util.concurrent.Callable<T>>,
            timeout: Long,
            unit: java.util.concurrent.TimeUnit
        ): MutableList<java.util.concurrent.Future<T>> = pool.invokeAll(tasks, timeout, unit)
        override fun <T> invokeAny(tasks: MutableCollection<out java.util.concurrent.Callable<T>>): T =
            pool.invokeAny(tasks)
        override fun <T> invokeAny(
            tasks: MutableCollection<out java.util.concurrent.Callable<T>>,
            timeout: Long,
            unit: java.util.concurrent.TimeUnit
        ): T = pool.invokeAny(tasks, timeout, unit)

        override fun close(immediately: Boolean): org.apache.sshd.common.future.CloseFuture {
            pool.shutdownNow()
            val future = org.apache.sshd.common.future.DefaultCloseFuture(
                java.util.concurrent.atomic.AtomicInteger(0), this
            )
            future.setClosed()
            return future
        }

        override fun isClosed(): Boolean = pool.isShutdown
        override fun isClosing(): Boolean = pool.isShutdown
        override fun addCloseFutureListener(listener: org.apache.sshd.common.future.SshFutureListener<org.apache.sshd.common.future.CloseFuture>?) {}
        override fun removeCloseFutureListener(listener: org.apache.sshd.common.future.SshFutureListener<org.apache.sshd.common.future.CloseFuture>?) {}

        override fun shutdown() = pool.shutdown()
        override fun shutdownNow(): MutableList<Runnable> = pool.shutdownNow()
        override fun isShutdown(): Boolean = pool.isShutdown
        override fun isTerminated(): Boolean = pool.isTerminated
        override fun awaitTermination(timeout: Long, unit: java.util.concurrent.TimeUnit): Boolean =
            pool.awaitTermination(timeout, unit)
    }

    /**
     * exec / 交互式 shell 的落地：真正的进程由 [ShellBackends] 里选出来的后端跑
     * （应用沙箱 / Root / Shizuku），输出直接接回 SSH 通道。
     */
    private inner class RemoteCommand(private val line: String?) :
        AbstractCommandSupport(line ?: "shell", CommandExecutor()) {

        @Volatile
        private var proc: Process? = null

        override fun run() {
            val launcher = ShellBackends.pick(config.sshShellBackend, config)
            if (launcher == null) {
                runCatching {
                    getErrorStream()?.write(L("没有可用的命令后端").toByteArray())
                }
                onExit(127, L("没有可用的命令后端"))
                return
            }
            val env = mapOf(
                "PATH" to "/sbin:/system/sbin:/system/bin:/system/xbin:/vendor/bin:/usr/bin:/bin",
                "HOME" to config.primaryRoot(),
                "TERM" to "xterm-256color",
                "LANG" to "en_US.UTF-8"
            )
            val cwd = config.primaryRoot()
            var code = 0
            try {
                val p = if (line == null) {
                    launcher.launchInteractive(cwd, env)
                } else {
                    launcher.launch(line, cwd, env)
                }
                proc = p
                val out = Thread { pump(p.inputStream, getOutputStream()) }.apply {
                    isDaemon = true; name = "ssh-out"; start()
                }
                val err = Thread { pump(p.errorStream, getErrorStream()) }.apply {
                    isDaemon = true; name = "ssh-err"; start()
                }
                val inp = Thread { pump(getInputStream(), p.outputStream) }.apply {
                    isDaemon = true; name = "ssh-in"; start()
                }
                code = p.waitFor()
                // 输出可能还在路上，稍等一下再收尾，否则客户端会看到空输出
                out.join(1500)
                err.join(1500)
                inp.interrupt()
            } catch (e: Exception) {
                runCatching { getErrorStream()?.write((e.message ?: "exec failed").toByteArray()) }
                code = 127
            }
            onExit(code)
        }

        override fun destroy(channel: ChannelSession?) {
            runCatching { proc?.destroy() }
            super.destroy(channel)
        }

        private fun pump(input: InputStream?, output: OutputStream?) {
            if (input == null || output == null) return
            val buf = ByteArray(8192)
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    output.write(buf, 0, n)
                    output.flush()
                }
            } catch (_: Exception) {
            }
        }
    }

    companion object {

        /** 看门狗敲门间隔。别太密：每敲一次都会真的建一条 TCP 连接。 */
        private const val PROBE_INTERVAL_MS = 30_000L

        /**
         * 生成一对客户端密钥（给「把私钥填进客户端」用）。
         *
         * 优先 Ed25519（现代、短）；设备上的 JCE 没有 EdDSA 时退回 RSA 3072。
         * 私钥用 OpenSSH 格式输出 —— JSch / sshj / OpenSSH 命令行都认这一种。
         */
        fun generateClientKeyPair(): Pair<String, String> {
            val kp = runCatching {
                // MINA 会自动注册 net.i2p 的 EdDSA provider（Android 的 JCE 没有 EdDSA）
                val gen = org.apache.sshd.common.util.security.SecurityUtils
                    .getKeyPairGenerator(org.apache.sshd.common.util.security.SecurityUtils.EDDSA)
                    ?: throw IllegalStateException("no eddsa")
                gen.generateKeyPair()
            }.getOrElse {
                val gen = KeyPairGenerator.getInstance("RSA")
                gen.initialize(3072, SecureRandom())
                gen.generateKeyPair()
            }
            val privatePem = runCatching {
                java.io.ByteArrayOutputStream().use { out ->
                    org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter.INSTANCE
                        .writePrivateKey(kp, "mcpbox", null, out)
                    out.toString("UTF-8")
                }
            }.getOrElse { "" }
            val publicLine = runCatching { PublicKeyEntry.toString(kp.public) }.getOrElse { "" }
            return privatePem to publicLine
        }

        /**
         * 启动前的准备：把日志压到只报告警，并让 MINA 先把它的加密 provider 注册上
         * （Ed25519 走 `net.i2p.crypto.eddsa`，Android 的 JCE 里没有 EdDSA）。
         */
        fun bootstrap(homeDir: String? = null, androidMode: Boolean = false) {
            if (System.getProperty("org.slf4j.simpleLogger.defaultLogLevel") == null) {
                System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn")
            }
            // Android 没有 user.home / user.name 这两个系统属性。
            // MINA 在 SshServer.setUpDefaultServer() 的静态初始化里就会去读它
            // （PublicKeyEntry / DefaultAuthorizedKeysAuthenticator 要算 ~/.ssh 的路径），
            // 读不到直接抛 IllegalArgumentException("No user home")，
            // 整个 ServerBuilder 类初始化失败 —— 之后连重试都会被 JVM 拒绝（Rejecting re-init）。
            // 必须在第一次碰到 SSHD 之前补上。
            val home = System.getProperty("user.home")
            if (home.isNullOrBlank()) {
                System.setProperty("user.home", homeDir ?: "/data/local/tmp")
            }
            if (System.getProperty("user.name").isNullOrBlank()) {
                System.setProperty("user.name", "mcpbox")
            }
            // MINA 自己的安卓开关：它的 ExceptionUtils.peelException 里有一段
            // `instanceof javax.management.ReflectionException`，而安卓根本没有
            // javax.management —— 一旦被自动探测漏掉，这段校验就会抛
            // NoClassDefFoundError，把整条「取 KeyAgreement」的路打断，
            // 最终让 SshServer.setUpDefaultServer() 的类初始化失败。
            // 这个属性是它给的显式开关，真机上必须自己打开。
            if (androidMode) {
                // 注意：**只设 org.apache.sshd.androidMode 属性不管用** —— MINA 拿同一个
                // value matcher 校验该属性的值（"true" 匹配不上），所以 isAndroid() 仍然是 false。
                // 直接调它的 setAndroid 把结果钉死。
                runCatching { org.apache.sshd.common.util.OsUtils.setAndroid(true) }
            }
        }
    }
}
