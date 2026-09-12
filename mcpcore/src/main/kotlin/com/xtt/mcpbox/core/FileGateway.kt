package com.xtt.mcpbox.core

import java.security.MessageDigest

/**
 * 文件进出通道：
 *   POST /upload?path=/storage/emulated/0/...   把外面的文件推进手机（原始 body）
 *   GET  /download?path=/storage/emulated/0/... 把手机里的文件取出去
 *   GET  /upload                                手机浏览器可用的上传页面
 *
 * 两个方向都走权限矩阵：上传 = 写文件权限，下载 = 读文件权限。
 * root / Shizuku 可用时，私有目录（/data/data/xxx）也能进能出。
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
            return json(405, false, "只支持 POST / PUT（GET 会给你一个上传网页）")
        }

        val rawPath = (req.query["path"] ?: req.header("x-file-path") ?: "").trim()
        if (rawPath.isBlank()) {
            return json(400, false, "缺少 path 参数：要写到手机上的哪个位置")
        }
        val nameHint = (req.header("x-file-name") ?: req.query["name"])?.trim()

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
            return json(400, false, e.message ?: "路径不合法")
        }
        try {
            sandbox.assertWritable(file)
        } catch (e: Exception) {
            return json(403, false, e.message ?: "当前模式下不允许写入")
        }

        val bytes = req.body
        if (bytes.isEmpty()) {
            return json(400, false, "请求体是空的（把文件内容放在 body 里发过来）")
        }
        val limit = config.maxUploadMb * 1024L * 1024L
        if (bytes.size > limit) {
            return json(413, false, "文件太大：${sandbox.humanSize(bytes.size.toLong())}，上限 ${config.maxUploadMb} MB")
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
                summary = "网页 / HTTP 上传：${file.name}（${sandbox.humanSize(payload.size.toLong())}）",
                detail = "写入位置：${file.path}" +
                    if (append) "\n（追加模式）" else "" +
                    if (sandbox.isPrivatePath(file)) "\n（应用私有目录，经 ${bridge.privilegedLabel} 写入）" else "",
                client = req.remote,
                mediaType = req.header("content-type"),
                byteSize = payload.size.toLong()
            )
            val ok = bridge.writeBytes(file, payload)
            if (!ok) {
                json(500, false, "写入失败：应用和 root / Shizuku 都没能写入 ${file.path}")
            } else {
                val md5 = md5Hex(payload)
                log.add(
                    LogKind.REQUEST, tool = "http_upload", path = file.path, client = req.remote,
                    ok = true, message = "上传 ${payload.size} 字节 → ${file.path}"
                )
                json(
                    200, true, "已写入 ${file.path}",
                    extra = mapOf(
                        "path" to file.path,
                        "bytes" to payload.size,
                        "md5" to md5,
                        "append" to append
                    )
                )
            }
        } catch (e: PermissionDeniedException) {
            json(403, false, e.message ?: "没有获得写入许可")
        }
    }

    // ------------------------------------------------------------------ 下载

    fun handleDownload(req: HttpRequest): Result {
        if (req.method != "GET" && req.method != "HEAD") {
            return json(405, false, "只支持 GET")
        }
        val raw = (req.query["path"] ?: req.header("x-file-path") ?: "").trim()
        if (raw.isBlank()) return json(400, false, "缺少 path 参数")

        val file = try {
            sandbox.resolve(raw)
        } catch (e: Exception) {
            return json(400, false, e.message ?: "路径不合法")
        }
        if (!bridge.exists(file)) return json(404, false, "文件不存在：${file.path}")
        val stat = bridge.stat(file)
        if (stat?.dir == true) {
            return json(400, false, "这是一个目录，不能直接下载：${file.path}")
        }

        return try {
            approval.guard(
                perm = PermKey.READ,
                tool = "http_download",
                path = file.path,
                summary = "网页 / HTTP 下载：${file.name}",
                detail = file.path,
                client = req.remote
            )
            val max = config.maxUploadMb * 1024L * 1024L
            val bytes = bridge.readBytes(file, max)
                ?: return json(500, false, "读不出来：应用没权限，且 root / Shizuku 不可用")
            log.add(
                LogKind.REQUEST, tool = "http_download", path = file.path, client = req.remote,
                ok = true, message = "下载 ${bytes.size} 字节 ← ${file.path}"
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
            json(403, false, e.message ?: "没有获得读取许可")
        }
    }

    // ------------------------------------------------------------------ 网页

    /** 手机浏览器直接用的上传页（HTML + fetch，不依赖 multipart 解析）。 */
    fun uploadPage(): String = """
<!DOCTYPE html>
<html lang="zh-CN"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>MCP 文件盒 · 上传</title>
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
  button { width:100%; background:#a8c7fa; color:#0b0b0d; border:0; border-radius:50px;
    padding:14px; font-size:15px; font-weight:600; }
  button:disabled { opacity:.5; }
  progress { width:100%; height:10px; margin-top:12px; }
  pre { background:#141416; border-radius:16px; padding:12px; font-size:12px;
        white-space:pre-wrap; word-break:break-all; margin:12px 0 0; }
</style></head><body>
<h1>上传到手机</h1>
<p class="sub">选一个文件 + 填目标路径，直接写进手机存储（会按权限设置弹审批）</p>
<div class="card">
  <label>目标路径（可以只写到目录，会自动带上原文件名）</label>
  <input type="text" id="path" value="/storage/emulated/0/xtt/app/mcp/">
  <label>文件</label>
  <input type="file" id="file">
  <button id="go" onclick="up()">开始上传</button>
  <progress id="bar" value="0" max="100" style="display:none"></progress>
  <pre id="out">等待中…</pre>
</div>
<script>
function up() {
  const f = document.getElementById('file').files[0];
  const p = document.getElementById('path').value.trim();
  const out = document.getElementById('out');
  const bar = document.getElementById('bar');
  if (!f) { out.textContent = '先选一个文件'; return; }
  if (!p) { out.textContent = '先填目标路径'; return; }
  const xhr = new XMLHttpRequest();
  xhr.open('POST', '/upload?path=' + encodeURIComponent(p) + '&name=' + encodeURIComponent(f.name));
  xhr.setRequestHeader('Content-Type', f.type || 'application/octet-stream');
  bar.style.display = 'block';
  bar.value = 0;
  out.textContent = '上传中…';
  xhr.upload.onprogress = e => { if (e.lengthComputable) bar.value = e.loaded / e.total * 100; };
  xhr.onload = () => { out.textContent = xhr.responseText; bar.value = 100; };
  xhr.onerror = () => { out.textContent = '失败：网络错误'; };
  xhr.send(f);
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
