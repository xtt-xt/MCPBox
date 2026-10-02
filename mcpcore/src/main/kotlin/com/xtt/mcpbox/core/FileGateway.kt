// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.core

import java.io.BufferedOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 文件进出通道：
 *   POST /upload?path=/storage/emulated/0/...              把外面的文件推进手机（原始 body）
 *   POST /upload?path=/storage/emulated/0/xxx&extract=1     body 当 zip，**整个文件夹一次传**（解压到该目录）
 *   GET  /download?path=/storage/emulated/0/...            把手机里的文件取出去
 *   GET  /download?path=/storage/emulated/0/xxx&zip=1      目录打成 zip 一次取出去
 *   GET  /upload                                           手机浏览器可用的上传页面
 *
 * 两个方向都走权限矩阵：上传 = 写文件权限，下载 = 读文件权限。
 * root / Shizuku 可用时，私有目录（/data/data/xxx）也能进能出。
 *
 * 为什么要 extract / zip：一次请求 = **一次审批**。逐个文件传一个文件夹要弹 N 次审批，
 * 打包成一个 zip 就只弹一次。
 */
class FileGateway(
    private val config: Config,
    private val sandbox: PathSandbox,
    private val approval: ApprovalCenter,
    private val log: EventLog,
    private val bridge: FileBridge
) {

    data class Result(
        val status: Int,
        val contentType: String,
        val body: ByteArray,
        val headers: Map<String, String> = emptyMap()
    )

    // ------------------------------------------------------------------ 上传

    fun handleUpload(req: HttpRequest): Result {
        if (req.method == "GET" || req.method == "HEAD") {
            return Result(200, "text/html; charset=utf-8", uploadPage().toByteArray(Charsets.UTF_8))
        }
        if (req.method != "POST" && req.method != "PUT") {
            return json(405, false, L("只支持 POST / PUT（GET 会给你一个上传网页）"))
        }

        val rawPath = (req.query["path"] ?: req.header("x-file-path") ?: "").trim()
        if (rawPath.isBlank()) {
            return json(400, false, L("缺少 path 参数：要写到手机上的哪个位置"))
        }
        val nameHint = (req.header("x-file-name") ?: req.query["name"])?.trim()

        // 压缩包模式：body 当成 zip 解压到 path（一个请求 = 一次审批，整个文件夹一起传）
        if (wantsArchive(req)) return handleZipUpload(req, rawPath, nameHint)

        // 目标是目录（结尾带 /、或者本来就是个目录）→ 自动拼上文件名
        val looksDir = rawPath.endsWith("/") || rawPath.endsWith("\\")
        val asDir = looksDir || runCatching {
            val f = sandbox.resolve(rawPath)
            f.isDirectory || bridge.exists(f) && (bridge.stat(f)?.dir == true)
        }.getOrDefault(false)
        val target = if (asDir) {
            rawPath.trimEnd('/', '\\') + "/" + (nameHint?.takeIf { it.isNotBlank() }
                ?: "upload-${System.currentTimeMillis()}.bin")
        } else {
            rawPath
        }

        val file = try {
            sandbox.resolve(target)
        } catch (e: Exception) {
            return json(400, false, e.message ?: L("路径不合法"))
        }
        try {
            sandbox.assertWritable(file)
        } catch (e: Exception) {
            return json(403, false, e.message ?: L("当前模式下不允许写入"))
        }

        val bytes = req.body
        if (bytes.isEmpty()) {
            return json(400, false, L("请求体是空的（把文件内容放在 body 里发过来）"))
        }
        val limit = config.maxUploadMb * 1024L * 1024L
        if (bytes.size > limit) {
            return json(
                413, false,
                L("文件太大：%s，上限 %s MB").format(sandbox.humanSize(bytes.size.toLong()), config.maxUploadMb)
            )
        }

        val append = req.query["append"]?.let { it == "1" || it.equals("true", true) } ?: false
        val payload = if (append && bridge.exists(file)) {
            (bridge.readBytes(file) ?: ByteArray(0)) + bytes
        } else {
            bytes
        }

        return try {
            approval.guard(
                perm = PermKey.WRITE,
                tool = "http_upload",
                path = file.path,
                summary = L("网页 / HTTP 上传：%s（%s）").format(file.name, sandbox.humanSize(payload.size.toLong())),
                detail = L("写入位置：%s").format(file.path) +
                    if (append) L("\n（追加模式）") else "" +
                    if (sandbox.isPrivatePath(file)) L("\n（应用私有目录，经 %s 写入）").format(bridge.privilegedLabel) else "",
                client = req.remote,
                mediaType = req.header("content-type"),
                byteSize = payload.size.toLong()
            )
            val ok = bridge.writeBytes(file, payload)
            if (!ok) {
                json(500, false, L("写入失败：应用和 root / Shizuku 都没能写入 %s").format(file.path))
            } else {
                val md5 = md5Hex(payload)
                log.add(
                    LogKind.REQUEST, tool = "http_upload", path = file.path, client = req.remote,
                    ok = true, message = L("上传 %s 字节 → %s").format(payload.size, file.path)
                )
                json(
                    200, true, L("已写入 %s").format(file.path),
                    extra = mapOf(
                        "path" to file.path,
                        "bytes" to payload.size,
                        "md5" to md5,
                        "append" to append
                    )
                )
            }
        } catch (e: PermissionDeniedException) {
            json(403, false, e.message ?: L("没有获得写入许可"))
        }
    }

    // ------------------------------------------------------------------ 下载

    fun handleDownload(req: HttpRequest): Result {
        if (req.method != "GET" && req.method != "HEAD") {
            return json(405, false, L("只支持 GET"))
        }
        val raw = (req.query["path"] ?: req.header("x-file-path") ?: "").trim()
        if (raw.isBlank()) return json(400, false, L("缺少 path 参数"))

        val file = try {
            sandbox.resolve(raw)
        } catch (e: Exception) {
            return json(400, false, e.message ?: L("路径不合法"))
        }
        if (!bridge.exists(file)) return json(404, false, L("文件不存在：%s").format(file.path))
        val stat = bridge.stat(file)
        if (stat?.dir == true) {
            // 目录：加 &zip=1 就打成压缩包一次取走（一个请求 = 一次审批）
            if (wantsArchive(req)) return handleZipDownload(req, file)
            return json(
                400, false,
                L("这是一个目录，不能直接下载：%s").format(file.path) +
                    L("（加 &zip=1 可以整个打包下载）")
            )
        }

        return try {
            approval.guard(
                perm = PermKey.READ,
                tool = "http_download",
                path = file.path,
                summary = L("网页 / HTTP 下载：%s").format(file.name),
                detail = file.path,
                client = req.remote
            )
            val max = config.maxUploadMb * 1024L * 1024L
            val bytes = bridge.readBytes(file, max)
                ?: return json(500, false, L("读不出来：应用没权限，且 root / Shizuku 不可用"))
            log.add(
                LogKind.REQUEST, tool = "http_download", path = file.path, client = req.remote,
                ok = true, message = L("下载 %s 字节 ← %s").format(bytes.size, file.path)
            )
            Result(
                status = 200,
                contentType = guessMime(file.name),
                body = bytes,
                headers = mapOf(
                    "Content-Disposition" to "attachment; filename=\"${file.name}\"",
                    "X-File-Path" to file.path,
                    "X-File-Md5" to md5Hex(bytes)
                )
            )
        } catch (e: PermissionDeniedException) {
            json(403, false, e.message ?: L("没有获得读取许可"))
        }
    }

    // ------------------------------------------------------------------ 压缩包（整目录）

    /**
     * `?extract=1` / `?zip=1`：这次请求是「整包」操作（上传解压 / 打包下载）。
     *
     * 两个方向都用同一个开关名，因为对调用方来说语义一致：**这次走的是压缩包**。
     * 上传方向 path = 解压到哪个目录；下载方向 path = 打包哪个目录。
     */
    private fun wantsArchive(req: HttpRequest): Boolean {
        fun on(name: String) = req.query[name]?.let { it == "1" || it.equals("true", true) } ?: false
        return on("extract") || on("zip") || on("unzip")
    }

    /** 压缩包先落临时文件：用 [ZipFile] 读中央目录，**不解压**就能拿到条目名和大小。 */
    private fun tempArchive(prefix: String): File {
        val dir = File(ShellEnv.tmp).let { if (it.isDirectory && it.canWrite()) it else File(".") }
        val rand = (Math.random() * 100_000).toInt()
        return File(dir, "mcpbox-$prefix-${System.currentTimeMillis()}-$rand.zip")
    }

    /** 条目名不安全就返回原因（绝对路径 / 盘符 / 向上跳目录）。 */
    private fun unsafeEntryReason(name: String): String? {
        val n = name.replace('\\', '/')
        if (n.isBlank()) return null
        if (n.startsWith("/")) return L("绝对路径")
        if (n.length >= 2 && n[1] == ':') return L("带盘符")
        if (n.split('/').any { it == ".." }) return L("向上跳目录")
        return null
    }

    private fun insideDir(dir: File, f: File): Boolean = try {
        val d = dir.canonicalPath.trimEnd('/')
        val p = f.canonicalPath
        p == d || p.startsWith("$d/")
    } catch (e: Exception) {
        false
    }

    /** 一个条目写进目标文件；私有目录走特权后端，普通路径直接写。 */
    private fun writeEntry(zf: ZipFile, entry: ZipEntry, out: File): Boolean = runCatching {
        if (sandbox.isPrivatePath(out)) {
            val bytes = zf.getInputStream(entry).use { it.readBytes() }
            bridge.writeBytes(out, bytes)
        } else {
            out.parentFile?.mkdirs()
            zf.getInputStream(entry).use { ins ->
                out.outputStream().use { ins.copyTo(it) }
            }
            true
        }
    }.getOrDefault(false)

    /**
     * 上传一个 zip → 解压到 path 指定的目录。
     *
     * 顺序刻意是「先校验、再审批、最后才写盘」：条目名、文件数、解压后总大小
     * 全在写之前算清楚，所以压缩包有问题时不会有半个目录留在手机上；
     * 审批文案里也能带上准确的文件数。
     */
    private fun handleZipUpload(req: HttpRequest, rawPath: String, nameHint: String?): Result {
        val dir = try {
            sandbox.resolve(rawPath)
        } catch (e: Exception) {
            return json(400, false, e.message ?: L("路径不合法"))
        }
        if (bridge.exists(dir) && bridge.stat(dir)?.dir != true) {
            return json(400, false, L("解压需要一个目录，但 path 指向的是文件：%s").format(dir.path))
        }
        try {
            sandbox.assertWritable(dir)
        } catch (e: Exception) {
            return json(403, false, e.message ?: L("当前模式下不允许写入"))
        }

        val bytes = req.body
        if (bytes.isEmpty()) return json(400, false, L("请求体是空的（把 zip 内容放在 body 里发过来）"))
        val limit = config.maxUploadMb * 1024L * 1024L
        if (bytes.size > limit) {
            return json(
                413, false,
                L("压缩包太大：%s，上限 %s MB").format(sandbox.humanSize(bytes.size.toLong()), config.maxUploadMb)
            )
        }

        val zipName = nameHint?.takeIf { it.isNotBlank() } ?: "archive.zip"
        val tmp = tempArchive("upload")
        try {
            if (!runCatching { tmp.writeBytes(bytes) }.isSuccess) {
                return json(500, false, L("写临时文件失败：%s").format(tmp.path))
            }
            val zf = try {
                ZipFile(tmp)
            } catch (e: Exception) {
                return json(400, false, L("不是有效的 zip 压缩包：%s").format(e.message ?: ""))
            }
            zf.use {
                val entries = it.entries().toList()
                for (e in entries) {
                    val bad = unsafeEntryReason(e.name) ?: continue
                    return json(400, false, L("压缩包里有不安全的条目（%s）：%s").format(bad, e.name))
                }
                val files = entries.filter { !it.isDirectory && it.name.isNotBlank() }
                if (files.isEmpty()) return json(400, false, L("压缩包里没有文件"))

                val countLimit = 20_000
                if (files.size > countLimit) {
                    return json(
                        413, false,
                        L("压缩包里文件太多：%s 个，上限 %s 个").format(files.size, countLimit)
                    )
                }
                val total = files.sumOf { e -> e.size.coerceAtLeast(0L) }
                val extractLimit = limit * 4
                if (total > extractLimit) {
                    return json(
                        413, false,
                        L("解压后太大：%s，上限 %s").format(
                            sandbox.humanSize(total), sandbox.humanSize(extractLimit)
                        )
                    )
                }

                return try {
                    approval.guard(
                        perm = PermKey.WRITE,
                        tool = "http_upload",
                        path = dir.path,
                        summary = L("上传压缩包并解压：%s（%s，%s 个文件）").format(
                            zipName, sandbox.humanSize(bytes.size.toLong()), files.size
                        ),
                        detail = L("解压到：%s").format(dir.path) +
                            L("\n（解压后 %s）").format(sandbox.humanSize(total)) +
                            if (sandbox.isPrivatePath(dir)) {
                                L("\n（应用私有目录，经 %s 写入）").format(bridge.privilegedLabel)
                            } else "",
                        client = req.remote,
                        mediaType = req.header("content-type"),
                        byteSize = bytes.size.toLong()
                    )
                    var done = 0
                    var outBytes = 0L
                    for (e in entries) {
                        val out = File(dir, e.name)
                        if (!insideDir(dir, out)) continue
                        if (e.isDirectory) {
                            if (!out.isDirectory) bridge.mkdirs(out)
                            continue
                        }
                        if (!writeEntry(it, e, out)) {
                            return json(500, false, L("写入失败（第 %s 个）：%s").format(done + 1, out.path))
                        }
                        done++
                        outBytes += e.size.coerceAtLeast(0L)
                    }
                    log.add(
                        LogKind.REQUEST, tool = "http_upload", path = dir.path, client = req.remote,
                        ok = true,
                        message = L("解压上传：%s → %s（%s 个文件，%s）").format(
                            zipName, dir.path, done, sandbox.humanSize(outBytes)
                        )
                    )
                    json(
                        200, true,
                        L("已解压 %s 个文件 → %s").format(done, dir.path),
                        extra = mapOf(
                            "path" to dir.path,
                            "entry" to zipName,
                            "files" to done,
                            "bytes" to outBytes
                        )
                    )
                } catch (e: PermissionDeniedException) {
                    json(403, false, e.message ?: L("没有获得写入许可"))
                }
            }
        } finally {
            runCatching { tmp.delete() }
        }
    }

    /** 目录 → 一个 zip 下载（一个请求 = 一次审批）。 */
    private fun handleZipDownload(req: HttpRequest, dir: File): Result {
        val limit = config.maxUploadMb * 1024L * 1024L
        val files = ArrayList<Pair<String, File>>()
        var total = 0L

        fun walk(d: File, prefix: String, depth: Int): Boolean {
            if (depth > 24) return true
            val entries = bridge.listDir(d) ?: return false
            for (e in entries) {
                if (e.name == "." || e.name == "..") continue
                val rel = if (prefix.isEmpty()) e.name else "$prefix/${e.name}"
                val child = File(e.path)
                if (e.dir) {
                    if (!walk(child, rel, depth + 1)) return false
                } else {
                    files += rel to child
                    total += e.size.coerceAtLeast(0L)
                    if (files.size > 20_000 || total > limit) return false
                }
            }
            return true
        }

        if (!walk(dir, "", 0)) {
            return json(
                413, false,
                L("目录太大，打不进一个 zip（上限 %s）").format(sandbox.humanSize(limit))
            )
        }
        if (files.isEmpty()) return json(400, false, L("目录是空的：%s").format(dir.path))

        return try {
            approval.guard(
                perm = PermKey.READ,
                tool = "http_download",
                path = dir.path,
                summary = L("打包下载目录：%s（%s 个文件）").format("${dir.name}.zip", files.size),
                detail = L("目录：%s").format(dir.path) +
                    L("\n（打包前 %s）").format(sandbox.humanSize(total)),
                client = req.remote
            )
            val tmp = tempArchive("download")
            try {
                ZipOutputStream(BufferedOutputStream(tmp.outputStream())).use { zos ->
                    for ((rel, f) in files) {
                        val data = bridge.readBytes(f, limit) ?: continue
                        zos.putNextEntry(ZipEntry(rel))
                        zos.write(data)
                        zos.closeEntry()
                    }
                }
                if (tmp.length() > limit) {
                    return json(
                        413, false,
                        L("打包后太大：%s，上限 %s MB").format(
                            sandbox.humanSize(tmp.length()), config.maxUploadMb
                        )
                    )
                }
                val bytes = tmp.readBytes()
                log.add(
                    LogKind.REQUEST, tool = "http_download", path = dir.path, client = req.remote,
                    ok = true,
                    message = L("打包下载：%s（%s 个文件，%s）").format(
                        dir.path, files.size, sandbox.humanSize(bytes.size.toLong())
                    )
                )
                Result(
                    status = 200,
                    contentType = "application/zip",
                    body = bytes,
                    headers = mapOf(
                        "Content-Disposition" to "attachment; filename=\"${dir.name}.zip\"",
                        "X-File-Path" to dir.path,
                        "X-File-Count" to files.size.toString()
                    )
                )
            } finally {
                runCatching { tmp.delete() }
            }
        } catch (e: PermissionDeniedException) {
            json(403, false, e.message ?: L("没有获得读取许可"))
        }
    }

    // ------------------------------------------------------------------ 网页

    /** 手机浏览器直接用的上传页（HTML + fetch，不依赖 multipart 解析）。 */
    fun uploadPage(): String = """
<!DOCTYPE html>
<html lang="zh-CN"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${L("MCP 文件盒 · 上传")}</title>
<style>
  :root { color-scheme: dark; }
  body { margin:0; background:#0b0b0d; color:#e8e6ea;
         font-family:-apple-system,"PingFang SC","Noto Sans CJK SC",sans-serif; padding:18px; }
  h1 { font-size:26px; margin:6px 0 4px; }
  p.sub { color:#9e9ea7; font-size:13px; margin:0 0 18px; }
  .card { background:#1c1c20; border-radius:24px; padding:18px; margin-bottom:10px; }
  label { display:block; font-size:13px; color:#9e9ea7; margin-bottom:8px; }
  input[type=text], input[type=file] { width:100%; box-sizing:border-box; background:#141416; color:#e8e6ea;
    border:0; border-radius:16px; padding:12px; font-size:14px; margin-bottom:14px; }
  input[type=file] { padding:10px; }
  .check { display:flex; align-items:center; gap:10px; font-size:13px; color:#c9c7cf;
           background:#141416; border-radius:16px; padding:12px; margin-bottom:14px; }
  .check input { width:auto; margin:0; }
  button { width:100%; background:#a8c7fa; color:#0b0b0d; border:0; border-radius:50px;
    padding:14px; font-size:15px; font-weight:600; }
  button:disabled { opacity:.5; }
  progress { width:100%; height:10px; margin-top:12px; }
  pre { background:#141416; border-radius:16px; padding:12px; font-size:12px;
        white-space:pre-wrap; word-break:break-all; margin:12px 0 0; }
</style></head><body>
<h1>${L("上传到手机")}</h1>
<p class="sub">${L("选文件（可多选）或选一个 zip 解压，直接写进手机存储（会按权限设置弹审批）")}</p>
<div class="card">
  <label>${L("目标路径（可以只写到目录，会自动带上原文件名）")}</label>
  <input type="text" id="path" value="/storage/emulated/0/xtt/app/mcp/">
  <label>${L("文件（可多选；想整个文件夹一起传，就先把文件夹压成一个 zip）")}</label>
  <input type="file" id="file" multiple>
  <label class="check"><input type="checkbox" id="extract">${L("这是压缩包：解压到目标目录（只传第一个文件，只弹一次审批）")}</label>
  <button id="go" onclick="up()">${L("开始上传")}</button>
  <progress id="bar" value="0" max="100" style="display:none"></progress>
  <pre id="out">${L("等待中…")}</pre>
</div>
<script>
function send(f, p, ex, bar) {
  return new Promise(function (done) {
    const xhr = new XMLHttpRequest();
    let q = '/upload?path=' + encodeURIComponent(p) + '&name=' + encodeURIComponent(f.name);
    if (ex) q += '&extract=1';
    xhr.open('POST', q);
    xhr.setRequestHeader('Content-Type', f.type || 'application/octet-stream');
    xhr.upload.onprogress = function (e) { if (e.lengthComputable) bar.value = e.loaded / e.total * 100; };
    xhr.onload = function () { done({ ok: xhr.status >= 200 && xhr.status < 300, text: xhr.responseText }); };
    xhr.onerror = function () { done({ ok: false, text: '${L("失败：网络错误")}' }); };
    xhr.send(f);
  });
}
async function up() {
  const files = Array.prototype.slice.call(document.getElementById('file').files);
  const p = document.getElementById('path').value.trim();
  const ex = document.getElementById('extract').checked;
  const out = document.getElementById('out');
  const bar = document.getElementById('bar');
  if (!files.length) { out.textContent = '${L("先选文件")}'; return; }
  if (!p) { out.textContent = '${L("先填目标路径")}'; return; }
  // 压缩包模式只传第一个文件（zip 里已经是一整个文件夹）
  const list = ex ? files.slice(0, 1) : files;
  const lines = [];
  const btn = document.getElementById('go');
  btn.disabled = true;
  bar.style.display = 'block';
  for (let i = 0; i < list.length; i++) {
    out.textContent = (i + 1) + '/' + list.length + ' · ${L("上传中")}：' + list[i].name;
    bar.value = 0;
    const r = await send(list[i], p, ex, bar);
    lines.push((r.ok ? 'OK   ' : 'FAIL ') + list[i].name + ' — ' + r.text);
    out.textContent = lines.join('\n');
  }
  bar.value = 100;
  btn.disabled = false;
}
</script>
</body></html>
""".trimIndent()

    // ------------------------------------------------------------------ 辅助

    private fun json(
        status: Int,
        ok: Boolean,
        message: String,
        extra: Map<String, Any?> = emptyMap()
    ): Result {
        val obj = jo(
            "ok" to ok,
            "message" to message,
            *extra.entries.map { it.key to it.value }.toTypedArray()
        )
        return Result(status, "application/json; charset=utf-8", obj.toString().toByteArray(Charsets.UTF_8))
    }

    private fun md5Hex(bytes: ByteArray): String = try {
        MessageDigest.getInstance("MD5").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        "?"
    }

    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "apk" -> "application/vnd.android.package-archive"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "txt", "md", "log" -> "text/plain; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "zip" -> "application/zip"
        "mp3" -> "audio/mpeg"
        "mp4" -> "video/mp4"
        "so" -> "application/octet-stream"
        else -> "application/octet-stream"
    }
}
