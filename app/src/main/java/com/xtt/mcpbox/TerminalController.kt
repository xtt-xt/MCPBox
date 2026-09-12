package com.xtt.mcpbox

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.xtt.mcpbox.core.CommandLauncher
import com.xtt.mcpbox.core.LogKind
import com.xtt.mcpbox.core.ShellBackends
import com.xtt.mcpbox.core.ShellSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * App 内置终端：维护一个常驻 shell 会话，输出节流后推给 Compose。
 * cd / export 这类状态会在同一个进程里保留（除非切后端或点中断）。
 */
class TerminalController(private val appContext: Context) {

    private val main = Handler(Looper.getMainLooper())

    private val buffer = StringBuilder()
    private var session: ShellSession? = null

    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    private val _backendId = MutableStateFlow(AppCore.config.terminalBackend)
    val backendId: StateFlow<String> = _backendId

    private val _backendLabel = MutableStateFlow("")
    val backendLabel: StateFlow<String> = _backendLabel

    val history = ArrayList<String>()
    private var historyIndex = -1

    private val flush = Runnable {
        synchronized(buffer) {
            _text.value = buffer.toString()
        }
    }

    fun append(textToAdd: String) {
        synchronized(buffer) {
            buffer.append(textToAdd)
            if (buffer.length > 400_000) {
                buffer.delete(0, buffer.length - 300_000)
                buffer.insert(0, "...（输出过多，已截断旧内容）\n")
            }
        }
        main.removeCallbacks(flush)
        main.postDelayed(flush, 90)
    }

    fun clear() {
        synchronized(buffer) { buffer.setLength(0) }
        _text.value = ""
    }

    /** 启动/切换后端。 */
    fun start(backend: String? = null, cwd: String? = null): String {
        val wanted = backend ?: _backendId.value
        val launcher = ShellBackends.pick(wanted, AppCore.config)
            ?: return "没有可用的 Shell 后端"
        _backendId.value = launcher.id
        _backendLabel.value = launcher.label
        val workdir = cwd?.takeIf { it.isNotBlank() }
            ?: AppCore.config.terminalCwd.takeIf { it.isNotBlank() }
            ?: appWorkdir()
        session?.kill()
        val s = ShellSession(
            launcher = launcher,
            cwd = workdir,
            onOutput = { append(it) },
            onExit = { code ->
                main.post {
                    _running.value = false
                    append("\n[会话已结束，退出码 $code]\n")
                }
            }
        )
        session = s
        val ok = s.start()
        _running.value = ok
        if (!ok) {
            append("[无法启动 ${launcher.label}：${s.startError ?: "未知原因"}]\n")
            log("终端启动失败：${s.startError}", false)
        } else {
            log("终端已启动（${launcher.label}）", true)
        }
        return if (ok) "已连接：${launcher.label}" else "启动失败：${s.startError ?: "未知原因"}"
    }

    private fun appWorkdir(): String = runCatching {
        java.io.File(appContext.filesDir, "shell").apply { mkdirs() }.absolutePath
    }.getOrDefault("/sdcard")

    fun send(command: String) {
        val s = session
        if (s == null || !s.alive) {
            append("[会话不在运行，正在重新启动]\n")
            start()
        }
        append("\n$ $command\n")
        if (history.lastOrNull() != command) history.add(command)
        if (history.size > 100) history.removeAt(0)
        historyIndex = history.size
        session?.send(command)
    }

    /** 光标中断：管道模式下没法发 SIGINT，只能结束并重开会话。 */
    fun interrupt(): String {
        append("\n[^C 已中断，会话重启]\n")
        return start()
    }

    fun previousCommand(): String? {
        if (history.isEmpty()) return null
        historyIndex = (historyIndex - 1).coerceAtLeast(0)
        return history.getOrNull(historyIndex)
    }

    fun nextCommand(): String? {
        if (history.isEmpty()) return null
        historyIndex = (historyIndex + 1).coerceAtMost(history.size - 1)
        return history.getOrNull(historyIndex)
    }

    fun backends(): List<Pair<String, Boolean>> = ShellBackends.all().map {
        it.id to runCatching { it.isAvailable() }.getOrDefault(false)
    }

    private fun log(message: String, ok: Boolean) {
        AppCore.log.add(LogKind.SYSTEM, ok = ok, message = message)
    }
}
