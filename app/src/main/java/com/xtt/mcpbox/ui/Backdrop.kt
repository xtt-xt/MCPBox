// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.xtt.mcpbox.AppCore
import com.xtt.mcpbox.core.LogKind
import com.xtt.mcpbox.i18n.L
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * 自定义背景：一张图片或一段视频垫在最底下，上面盖一层**主题背景色**的遮罩，
 * 遮罩的不透明度可调（100% = 原来那种纯色底）。
 *
 * 三条渲染规则（改之前先读这段）：
 *  ① **视频只有一份**，铺在最外层（[BackdropLayer]）—— 一个 MediaPlayer、一个 TextureView，
 *     页面只往上叠遮罩，所以转场时背景**不会跟着页面滑动**。
 *  ② **图片是每页各画一份**（[BackdropPageSurface]）—— 同一张 bitmap 画两次很便宜，
 *     换来的是「转场时两页各自完整」，不会出现两页遮罩叠在一起（一条更暗的带子）。
 *     代价：图片会跟着页面移动（有一点视差感）。
 *  ③ 页面盖不到的地方（底栏那一条）也要有东西：所以图片模式在最外层**也画一份不打遮罩的图**，
 *     免得那儿露出空白。
 *
 * 遮罩色永远取 `MaterialTheme.colorScheme.background` —— 跟随主题 / 深浅色自动变。
 */

/** 背景类型。 */
enum class BackdropKind(val id: String, val label: String) {
    IMAGE("image", "图片"),
    VIDEO("video", "视频");

    companion object {
        fun of(id: String?): BackdropKind = entries.firstOrNull { it.id == id } ?: IMAGE
    }
}

/** 背景填充方式。 */
enum class BackdropScale(val id: String, val label: String) {
    CROP("crop", "裁剪铺满"),
    FIT("fit", "完整显示");

    companion object {
        fun of(id: String?): BackdropScale = entries.firstOrNull { it.id == id } ?: CROP
    }
}

/* --------------------------------------------------------------- 文件这一层 */

/** 背景文件的信息（设置页用来显示「选了哪张、多大、多少像素」）。 */
data class BackdropInfo(val name: String, val sizeText: String, val detail: String)

/**
 * 背景文件的落盘位置：`files/backdrop/`。
 *
 * 选中的文件一律**复制**进来（不放引用）：系统给的 uri 授权可能过期、原文件也可能被删，
 * 复制一份最省事；换新的时旧文件直接删掉，不会越攒越多。
 */
object BackdropFiles {

    fun dir(ctx: Context): File = File(ctx.filesDir, "backdrop").also { it.mkdirs() }

    /** 当前设置指向的那个文件；没有 / 不在了就返回 null。 */
    fun current(ctx: Context): File? {
        val name = AppCore.prefs.backdropFile
        if (name.isNotBlank()) {
            val f = File(dir(ctx), name)
            if (f.isFile) return f
        }
        // 兜底认领：偏好里没记（或记的文件不在了），但目录里躺着文件 —— 直接用它，
        // 并把名字与类型补写回偏好。历史版本漏写过这个键，不认领的话文件明明在却显示不出来。
        val orphan = listFiles(ctx).firstOrNull() ?: return null
        AppCore.prefs.backdropFile = orphan.name
        AppCore.prefs.backdropKind = if (orphan.name.startsWith("video_")) "video" else "image"
        return orphan
    }

    /** 目录里有效的背景文件（按修改时间新→旧；临时文件不算）。 */
    private fun listFiles(ctx: Context): List<File> =
        dir(ctx).listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") && it.length() > 0 }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /** 把选中的 uri 复制进私有目录，返回新的文件名（旧文件顺手删掉）。 */
    suspend fun import(ctx: Context, uri: Uri, kind: BackdropKind): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val declared = declaredSize(ctx, uri)
                val limit = 512L * 1024 * 1024
                if (declared != null && declared > limit) {
                    error(L("文件太大了（上限 512MB）"))
                }

                val tmp = File(dir(ctx), "import_${System.currentTimeMillis()}.part")
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { out -> input.copyTo(out) }
                } ?: error(L("打不开这个文件"))

                if (tmp.length() <= 0L) {
                    tmp.delete()
                    error(L("这个文件是空的"))
                }
                if (tmp.length() > limit) {
                    tmp.delete()
                    error(L("文件太大了（上限 512MB）"))
                }
                if (!usable(kind, tmp)) {
                    tmp.delete()
                    error(
                        if (kind == BackdropKind.IMAGE) L("这个文件不是能识别的图片")
                        else L("这个文件不是能播放的视频")
                    )
                }
                val dest = File(dir(ctx), "${kind.id}_${System.currentTimeMillis()}.${extOf(ctx, uri, kind)}")
                if (!tmp.renameTo(dest)) {
                    tmp.delete()
                    error(L("复制到应用目录失败"))
                }
                dir(ctx).listFiles()?.forEach { if (it.name != dest.name) it.delete() }
                dest.name
            }
        }

    /** 删掉全部背景文件（「清除背景」用）。 */
    fun clear(ctx: Context) {
        dir(ctx).listFiles()?.forEach { it.delete() }
    }

    private fun declaredSize(ctx: Context, uri: Uri): Long? = runCatching {
        ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
    }.getOrNull()

    /** 存下来的文件名后缀：先用原文件名，再用 MIME，最后兜底 dat。 */
    private fun extOf(ctx: Context, uri: Uri, kind: BackdropKind): String {
        val fromName = runCatching {
            ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()?.substringAfterLast('.', "")
        if (fromName != null && fromName.length in 2..5 && fromName.all { it.isLetterOrDigit() }) {
            return fromName.lowercase()
        }
        return when (ctx.contentResolver.getType(uri)?.lowercase()) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/heic", "image/heif" -> "heic"
            "video/mp4" -> "mp4"
            "video/webm" -> "webm"
            "video/quicktime" -> "mov"
            "video/x-matroska" -> "mkv"
            else -> if (kind == BackdropKind.IMAGE) "img" else "vid"
        }
    }

    /** 这文件真的能当背景用吗（图片试解码、视频试读元数据）。 */
    private fun usable(kind: BackdropKind, f: File): Boolean = when (kind) {
        BackdropKind.IMAGE -> imageSize(f) != null
        BackdropKind.VIDEO -> videoSize(f) != null
    }

    /** 图片像素尺寸（只读头部，不解码整张）。 */
    fun imageSize(f: File): IntArray? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) null else intArrayOf(o.outWidth, o.outHeight)
    }.getOrNull()

    /** 视频像素尺寸（已按旋转角摆正）。 */
    fun videoSize(f: File): IntArray? = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(f.absolutePath)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (w == null || h == null || w <= 0 || h <= 0) null
            else if (rot == 90 || rot == 270) intArrayOf(h, w) else intArrayOf(w, h)
        } finally {
            r.release()
        }
    }.getOrNull()

    /** 视频时长文本（读不到就返回 null）。 */
    fun durationText(f: File): String? = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(f.absolutePath)
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            if (ms == null || ms <= 0) null else "%d:%02d".format(ms / 60000, (ms % 60000) / 1000)
        } finally {
            r.release()
        }
    }.getOrNull()

    /** 设置页里当前文件的信息行。 */
    suspend fun info(file: File, kind: BackdropKind): BackdropInfo = withContext(Dispatchers.IO) {
        val size = humanSize(file.length())
        val px = (if (kind == BackdropKind.IMAGE) imageSize(file) else videoSize(file))
        val res = px?.let { "${it[0]} × ${it[1]}" } ?: L("尺寸未知")
        val dur = if (kind == BackdropKind.VIDEO) durationText(file) else null
        BackdropInfo(
            name = file.name,
            sizeText = size,
            detail = if (dur == null) res else "$res · $dur"
        )
    }

    /** 视频背景的静态预览帧（设置页的预览卡用，别为预览再开一路解码）。 */
    suspend fun poster(file: File, kind: BackdropKind): ImageBitmap? = withContext(Dispatchers.IO) {
        if (kind == BackdropKind.IMAGE) return@withContext decodeScaled(file, 1280)
        runCatching {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(file.absolutePath)
                // 第 0 帧常常是黑的，往前找一点；再取不到就退回第 0 帧
                val b = r.getFrameAtTime(1_000_000) ?: r.getFrameAtTime(0) ?: return@runCatching null
                b.asImageBitmap()
            } finally {
                r.release()
            }
        }.getOrNull()
    }

    fun humanSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.2f GB".format(bytes.toDouble() / (1L shl 30))
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes.toDouble() / (1L shl 20))
        bytes >= 1L shl 10 -> "%.0f KB".format(bytes.toDouble() / (1L shl 10))
        else -> "$bytes B"
    }
}

/** 按长边降采样解码（背景只需要屏幕级别，原图直接解会 OOM）。 */
private fun decodeScaled(file: File, maxDim: Int): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) {
        sample *= 2
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    BitmapFactory.decodeFile(file.absolutePath, opts)?.asImageBitmap()
}.getOrNull()

/**
 * 解码结果缓存（只留一张）。
 *
 * 图片模式下**每一页各画一份**：转场时同时存在的那两三页如果各解一次，
 * 一张 4000×3000 的照片就会被解成两三份、每份十几 MB —— 所以全 App 共用这一份 bitmap。
 * 换文件（路径或修改时间变了）时自动重解。
 */
private object BackdropImages {
    private var key: String? = null
    private var bitmap: ImageBitmap? = null

    /** 记得在 IO 线程调（第一次会真的解码）。 */
    fun load(file: File): ImageBitmap? {
        val k = "${file.path}@${file.lastModified()}"
        if (k != key) {
            bitmap = decodeScaled(file, 2048)
            key = k
        }
        return bitmap
    }
}

/* --------------------------------------------------------------- 渲染这一层 */

/** 背景当前生效的状态（渲染层只读这个，别在渲染里现读 Prefs）。 */
data class BackdropState(
    val active: Boolean = false,
    val kind: BackdropKind = BackdropKind.IMAGE,
    val file: File? = null,
    /** 遮罩不透明度 0f~1f（1 = 完全盖住背景 = 原来的纯色底）。 */
    val tintAlpha: Float = 0.88f,
    /** 模糊半径（dp），0 = 不模糊。 */
    val blur: Float = 0f,
    val scale: BackdropScale = BackdropScale.CROP,
    /** 子页面要不要也透出背景。 */
    val subPages: Boolean = true
) {
    /** 这一页要不要透出背景（[sub] = 这一页是不是子页）。 */
    fun showsOn(sub: Boolean): Boolean = active && (!sub || subPages)

    companion object {
        val Off = BackdropState()
    }
}

/** 把背景状态传给页面（[PageSurface] 从这里取，不用一层层传参）。 */
val LocalBackdrop = compositionLocalOf { BackdropState.Off }

/** 按 [revision]（主题版本号）现读一遍偏好。改了背景设置要让它变一下才会生效。 */
@Composable
fun rememberBackdropState(revision: Int): BackdropState {
    val ctx = LocalContext.current
    return remember(revision) {
        val p = AppCore.prefs
        val file = BackdropFiles.current(ctx)
        BackdropState(
            active = p.backdropEnabled && file != null,
            kind = BackdropKind.of(p.backdropKind),
            file = file,
            tintAlpha = p.backdropTint / 100f,
            blur = p.backdropBlur.toFloat(),
            scale = BackdropScale.of(p.backdropScale),
            subPages = p.backdropSubpages
        )
    }
}

/**
 * 页面自己的底色（遮罩）。
 *
 * 没背景 = 原来的不透明主题底色；有背景 = 主题底色**带透明度**（下面的背景透出来）。
 *
 * ⚠️ 只由**页面**铺这一层（[PageSurface]）。盒子（Scaffold / [PredictiveBackBox] 的那几层）
 * 在有背景时一律留空 —— 盒子是嵌套的，每层都铺一遍就是这么个结果：
 * 0.88 的遮罩叠三层实际已经接近不透明，滑块白调了。
 */
@Composable
fun backdropSurfaceColor(bd: BackdropState): Color =
    if (bd.active) MaterialTheme.colorScheme.background.copy(alpha = bd.tintAlpha)
    else MaterialTheme.colorScheme.background

/** 盒子的兜底底色：有背景时留空（页面自己会铺遮罩），没背景时还是原来的不透明底。 */
@Composable
fun backdropBoxColor(bd: BackdropState): Color =
    if (bd.active) Color.Transparent else MaterialTheme.colorScheme.background

/**
 * 最外层的背景层（整个 App 共用这一份）。
 *
 * 视频：真正的播放窗口就在这儿，全 App 只有这一个 MediaPlayer。
 * 图片：也在这儿画一份（**不打遮罩**）—— 页面盖不到的角落（比如底栏那一条）不至于露白；
 *       页面上那份**带遮罩**的图是页面自己画的（见 [BackdropPageSurface]）。
 */
@Composable
fun BackdropLayer(state: BackdropState) {
    if (!state.active) return
    Box(Modifier.fillMaxSize()) {
        if (state.kind == BackdropKind.VIDEO) {
            VideoBackdrop(state)
        } else {
            BackdropImage(state.file, state.scale, state.blur)
        }
    }
}

/** 页面自己的背景层：图片模式画「图 + 遮罩」，视频模式只画遮罩（视频在最外层）。 */
@Composable
fun BackdropPageSurface(state: BackdropState) {
    Box(Modifier.fillMaxSize()) {
        if (state.kind == BackdropKind.IMAGE) {
            BackdropImage(state.file, state.scale, state.blur)
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background.copy(alpha = state.tintAlpha))
        )
    }
}

/** 一张铺满屏幕的背景图（带可选模糊）。 */
@Composable
private fun BackdropImage(file: File?, scale: BackdropScale, blurDp: Float) {
    if (file == null) return
    val bmp by produceState<ImageBitmap?>(null, file.path, file.lastModified()) {
        value = withContext(Dispatchers.IO) { BackdropImages.load(file) }
    }
    val image = bmp ?: return
    val blurMod = if (blurDp > 0.5f && Build.VERSION.SDK_INT >= 31) Modifier.blur(blurDp.dp) else Modifier
    Image(
        bitmap = image,
        contentDescription = null,
        contentScale = if (scale == BackdropScale.CROP) ContentScale.Crop else ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .then(blurMod)
    )
}

/**
 * 背景视频：循环 + 静音 + 只在 App 在**前台**时播放。
 *
 * 用 [TextureView]（不是 SurfaceView）：它走普通的 View 绘制，能按正常的层级压在半透明页面底下，
 * 也能挂 RenderEffect 做模糊。
 */
@Composable
private fun VideoBackdrop(state: BackdropState) {
    val ctx = LocalContext.current
    val file = state.file ?: return
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current

    val holder = remember(file.path) { VideoHolder(file) }
    var videoSize by remember(file.path) { mutableIntStateOf(0) }
    var videoHeight by remember(file.path) { mutableIntStateOf(0) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    val view = remember(file.path) {
        TextureView(ctx).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    holder.attach(st)
                }

                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}

                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    holder.release()
                    return true
                }

                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }
    }

    // 视频尺寸（prepare 之后才拿得到）+ 窗口尺寸 → 自己算缩放矩阵。
    // 不用 setVideoScalingMode：那套只对 SurfaceView 可靠，TextureView 会直接把画面拉满（变形）。
    SideEffect {
        holder.onSize = { w, h ->
            videoSize = w
            videoHeight = h
        }
        holder.onError = { reason ->
            AppCore.log.add(
                LogKind.ERROR,
                ok = false,
                message = L("背景视频播放失败：%s").format(reason)
            )
        }
    }

    LaunchedEffect(videoSize, videoHeight, boxSize, state.scale) {
        val vw = videoSize
        val vh = videoHeight
        val bw = boxSize.width
        val bh = boxSize.height
        if (vw <= 0 || vh <= 0 || bw <= 0 || bh <= 0) return@LaunchedEffect
        val s = if (state.scale == BackdropScale.CROP) max(bw.toFloat() / vw, bh.toFloat() / vh)
        else min(bw.toFloat() / vw, bh.toFloat() / vh)
        val m = Matrix()
        m.setScale(s, s)
        m.postTranslate((bw - vw * s) / 2f, (bh - vh * s) / 2f)
        runCatching { view.setTransform(m) }
    }

    // 模糊（Android 12+；低版本忽略）
    LaunchedEffect(state.blur) {
        if (Build.VERSION.SDK_INT < 31) return@LaunchedEffect
        val px = with(density) { state.blur.dp.toPx() }
        runCatching {
            view.setRenderEffect(
                if (px > 0.5f) RenderEffect.createBlurEffect(px, px, Shader.TileMode.CLAMP) else null
            )
        }
    }

    // 切后台就暂停（省电），回前台接着播
    DisposableEffect(lifecycleOwner, file.path) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> holder.pause()
                Lifecycle.Event.ON_START -> holder.resume()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            holder.release()
        }
    }

    AndroidView(
        factory = { view },
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { boxSize = it }
    )
}

/** 背景视频的播放器：循环、静音、能暂停 / 恢复。 */
private class VideoHolder(private val file: File) {

    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var prepared = false
    private var wantPlay = true

    /** 视频尺寸回调（prepare 完成后）。 */
    var onSize: ((Int, Int) -> Unit)? = null

    /** 出错回调（解码不了 / 打不开）。 */
    var onError: ((String) -> Unit)? = null

    fun attach(texture: SurfaceTexture) {
        if (player != null) return
        val s = Surface(texture)
        surface = s
        val mp = MediaPlayer()
        player = mp
        runCatching {
            mp.setDataSource(file.absolutePath)
            mp.setSurface(s)
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            mp.setOnVideoSizeChangedListener { _, w, h -> onSize?.invoke(w, h) }
            mp.setOnErrorListener { _, what, extra ->
                onError?.invoke("$what/$extra")
                true
            }
            mp.setOnPreparedListener { p ->
                prepared = true
                if (wantPlay) runCatching { p.start() }
            }
            mp.prepareAsync()
        }.onFailure {
            onError?.invoke(it.message ?: L("播放失败"))
            release()
        }
    }

    fun pause() {
        wantPlay = false
        runCatching { player?.takeIf { prepared }?.pause() }
    }

    fun resume() {
        wantPlay = true
        runCatching {
            player?.takeIf { prepared && !it.isPlaying }?.start()
        }
    }

    fun release() {
        runCatching { player?.release() }
        player = null
        prepared = false
        runCatching { surface?.release() }
        surface = null
    }
}

/** 选背景文件的入口（系统文件选择器 → 复制进私有目录）。返回「打开选择器」的函数。 */
@Composable
internal fun rememberBackdropPicker(
    ctx: Context,
    kind: BackdropKind,
    onPicked: () -> Unit
): () -> Unit {
    val scope = rememberCoroutineScope()
    val busy = remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null || busy.value) return@rememberLauncherForActivityResult
        busy.value = true
        scope.launch {
            val result = BackdropFiles.import(ctx, uri, kind)
            busy.value = false
            result.fold(
                onSuccess = { fileName ->
                    // 文件名必须落进偏好 —— 少了这一句，文件复制进去了、开关也开了，
                    // 渲染层还是以为「没选文件」，表现就是「选了没反应」
                    AppCore.prefs.backdropFile = fileName
                    AppCore.prefs.backdropKind = kind.id
                    AppCore.prefs.backdropEnabled = true
                    toast(ctx, L("背景已更新"))
                    onPicked()
                },
                onFailure = { toast(ctx, it.message ?: L("导入失败")) }
            )
        }
    }
    return {
        runCatching {
            launcher.launch(if (kind == BackdropKind.IMAGE) arrayOf("image/*") else arrayOf("video/*"))
        }
    }
}
