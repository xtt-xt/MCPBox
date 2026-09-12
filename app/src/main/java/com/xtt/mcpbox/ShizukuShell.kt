// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import com.xtt.mcpbox.core.CommandLauncher
import com.xtt.mcpbox.core.LogKind
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.File

/**
 * Shizuku 后端：用 ADB shell（uid 2000）的身份执行命令，
 * 能做的事情比应用自身多得多（访问 /data/local/tmp、pm/am/dumpsys 等）。
 *
 * 需要 Shizuku 正在运行，并且用户在本 App 里点过「申请授权」。
 */
class ShizukuLauncher(private val context: Context) : CommandLauncher {

    override val id: String = "shizuku"
    override val label: String = "Shizuku（ADB shell）"
    override val uidLabel: String = "shell (uid 2000)"
    override val hint: String =
        "需要先安装并启动 Shizuku，然后在 App 的「终端」页点「申请 Shizuku 授权」"

    override fun isAvailable(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    override fun launch(command: String, cwd: String?, env: Map<String, String>): Process =
        startOnShell(arrayOf("/system/bin/sh", "-c", command), cwd, env)

    override fun launchInteractive(cwd: String?, env: Map<String, String>): Process =
        startOnShell(arrayOf("/system/bin/sh"), cwd, env)

    /**
     * Shizuku 13 把 `Shizuku.newProcess` 收成了 private，官方做法是通过
     * IShizukuService.newProcess 拿到 IRemoteProcess，再包成 ShizukuRemoteProcess
     * （它的构造器是包内可见，所以这里用反射搭一下）。
     */
    private fun startOnShell(cmd: Array<String>, cwd: String?, env: Map<String, String>): Process {
        val service = IShizukuService.Stub.asInterface(Shizuku.getBinder())
            ?: throw IllegalStateException("Shizuku 服务不可用")
        val envArray = env.entries.map { "${it.key}=${it.value}" }.toTypedArray()
        val remote = service.newProcess(cmd, envArray, cwd?.takeIf { it.isNotBlank() })
            ?: throw IllegalStateException("Shizuku 拒绝创建进程")
        val ctor = processCtor ?: throw IllegalStateException("拿不到 ShizukuRemoteProcess 构造函数")
        return ctor.newInstance(remote) as Process
    }

    private companion object {
        val processCtor: java.lang.reflect.Constructor<*>? by lazy {
            runCatching {
                Class.forName("rikka.shizuku.ShizukuRemoteProcess")
                    .getDeclaredConstructor(moe.shizuku.server.IRemoteProcess::class.java)
                    .apply { isAccessible = true }
            }.getOrNull()
        }
    }
}

/** Shizuku 的权限申请与状态监听。 */
object ShizukuHelper {

    const val REQUEST_CODE = 4210

    @Volatile var lastResult: String? = null

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    private var registered = false

    fun addListener(l: () -> Unit) { listeners.add(l) }

    private fun notifyListeners() {
        Handler(Looper.getMainLooper()).post { listeners.forEach { runCatching { it() } } }
    }

    /** 应用启动时调用一次，之后 Shizuku 起来/挂了都能感知。 */
    fun install(app: Application) {
        if (registered) return
        registered = true
        runCatching {
            Shizuku.addBinderReceivedListenerSticky {
                AppCore.log.add(LogKind.SYSTEM, message = "Shizuku 服务已连接")
                notifyListeners()
            }
            Shizuku.addBinderDeadListener {
                AppCore.log.add(LogKind.SYSTEM, ok = false, message = "Shizuku 服务已断开")
                notifyListeners()
            }
            Shizuku.addRequestPermissionResultListener { requestCode, grantResult ->
                if (requestCode != REQUEST_CODE) return@addRequestPermissionResultListener
                val granted = grantResult == PackageManager.PERMISSION_GRANTED
                lastResult = if (granted) "已获得 Shizuku 授权" else "被拒绝"
                AppCore.log.add(
                    LogKind.SYSTEM, ok = granted,
                    message = "Shizuku 授权结果：" + (if (granted) "已允许" else "被拒绝")
                )
                notifyListeners()
            }
        }.onFailure {
            AppCore.log.add(LogKind.ERROR, ok = false, message = "注册 Shizuku 监听失败：${it.message}")
        }
    }

    /** Shizuku 是否在运行。 */
    fun isRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun isGranted(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun version(): String = runCatching {
        if (Shizuku.isPreV11()) "旧版本" else "v${Shizuku.getVersion()}"
    }.getOrDefault("未知")

    /** 是否装了 Shizuku 本体（装了但没启动也能提示用户去启动）。 */
    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
        true
    }.getOrDefault(false)

    /** 向 Shizuku 申请 shell 权限（必须在有 Activity 的时候调用）。 */
    fun request(): String {
        if (Shizuku.isPreV11()) return "Shizuku 版本太旧，请升级到 11 以上"
        if (!isRunning()) return "Shizuku 没有运行，请先打开 Shizuku 应用并启动服务"
        if (isGranted()) return "已经授权过了"
        return try {
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                return "你之前拒绝过，需要到 Shizuku 应用 → 授权管理里手动允许「MCP 文件盒」"
            }
            Shizuku.requestPermission(REQUEST_CODE)
            "已发送授权请求"
        } catch (e: Throwable) {
            "申请失败：${e.message}"
        }
    }

    fun statusText(context: Context): String = when {
        !isInstalled(context) -> "未安装 Shizuku"
        !isRunning() -> "Shizuku 未运行"
        isGranted() -> "已授权（${version()}）"
        else -> "未授权"
    }

    /** 常用的三分类目录，给终端当默认工作目录。 */
    fun defaultCwd(context: Context): String =
        runCatching { File(context.filesDir, "shell").apply { mkdirs() }.absolutePath }
            .getOrDefault("/data/local/tmp")
}
