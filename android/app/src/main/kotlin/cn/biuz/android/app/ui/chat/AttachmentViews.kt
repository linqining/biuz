package cn.biuz.android.app.ui.chat

// 附件视图（iOS MessageViews.swift AttachmentStripView/AttachmentThumbView + ChatView.swift
// PendingAttachmentThumb 直译，2026-10-10 媒体界面轮）。
//
// 关键坑对位（iOS 报障史，任务书点名）：
// - **视频必须有渲染分支**（v1.32②）：恒转圈根因 = video/* 预览到达后无分支可走；
//   本文件视频缩略格恒有形（封面帧 → 兜底格 → 失败格，无空转圈死路）；
// - **全屏单一宿主**（v1.32③）：同一视图链挂两个全屏位点时后挂遮蔽先挂——图片/视频
//   合流一个 sealed 状态（FullscreenAttachment），ChatScreen 单点挂 [AttachmentFullscreenHost]；
// - **稳定缓存**（v1.32③）：视频数据落 cacheDir/biuz-video（StableVideoCache），
//   点按校验在场、缺则重取重建（openVideo 重验重取路径），仍失败上屏不静默空点；
// - **播放失败上屏**（v1.32③）：MediaPlayer OnErrorListener 拒绝事件 → 错误行
//   （what/extra 原文）上屏，禁黑屏无声（AVPlayer 对不支持容器只黑屏的同坑）。
//
// a11y identifier 对位 iOS：05-attachment-thumb / 05-attach-item / 05-attach-remove
// （测试锚点，iOS 同名——Compose 侧用 semantics testTag 同值）。

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import cn.biuz.android.app.R
import cn.biuz.android.app.ui.theme.BiuZAlpha
import cn.biuz.android.app.ui.theme.BiuZAccent
import cn.biuz.android.app.ui.theme.BiuZBgCard
import cn.biuz.android.app.ui.theme.BiuZBgInput
import cn.biuz.android.app.ui.theme.BiuZBorder
import cn.biuz.android.app.ui.theme.BiuZOrange
import cn.biuz.android.app.ui.theme.BiuZRadius
import cn.biuz.android.app.ui.theme.BiuZSpacing
import cn.biuz.android.app.ui.theme.BiuZText2
import cn.biuz.android.app.ui.theme.BiuZText3
import cn.biuz.android.store.AttachmentPreview
import cn.biuz.android.store.ConversationStore
import cn.biuz.android.store.PendingAttachment
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// MARK: - 全屏单一宿主状态（v1.32③：图片/视频合流一个位点）

/** 全屏附件（图片原图 / 视频稳定缓存路径）；单一宿主按 sealed 分支渲染。 */
sealed class FullscreenAttachment {
    data class Image(val preview: AttachmentPreview) : FullscreenAttachment()

    /** path 用 String（data class equals 稳定；File 无值语义）。 */
    data class Video(val path: String) : FullscreenAttachment()
}

/** 全屏宿主状态（ChatScreen 持有单实例；缩略格经它打开——全屏位点全局唯一）。 */
class AttachmentFullscreenState {
    var item: FullscreenAttachment? by mutableStateOf(null)
}

// MARK: - 消息流附件条（iOS AttachmentStripView :1518-1534 直译）

/**
 * 会话流附件缩略条（G-014：attachmentReadV4 分块读 → 缩略图 + 全屏）。
 * 无附件数据（refs 空）由调用方不挂（iOS「无附件数据不渲染占位死块」同口径）。
 * rowAlignment：用户行传 Alignment.End（图随气泡右对齐，iOS .trailing 同口径）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AttachmentStripView(
    store: ConversationStore,
    sessionId: String,
    refs: List<String>,
    fullscreen: AttachmentFullscreenState,
    rowAlignment: Alignment.Horizontal = Alignment.Start,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.sm, rowAlignment),
        verticalArrangement = Arrangement.spacedBy(BiuZSpacing.xs),
    ) {
        refs.forEach { ref ->
            AttachmentThumbView(store = store, sessionId = sessionId, ref = ref, fullscreen = fullscreen)
        }
    }
}

// MARK: - 单附件缩略格（iOS AttachmentThumbView :1536-1750 直译）

/** 缩略格规格（iOS 132×96pt 等比直取）。 */
private val THUMB_WIDTH = 132.dp
private val THUMB_HEIGHT = 96.dp

@Composable
fun AttachmentThumbView(
    store: ConversationStore,
    sessionId: String,
    ref: String,
    fullscreen: AttachmentFullscreenState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var preview by remember(ref) { mutableStateOf<AttachmentPreview?>(null) }
    var failed by remember(ref) { mutableStateOf(false) }
    var imageBitmap by remember(ref) { mutableStateOf<ImageBitmap?>(null) }
    var poster by remember(ref) { mutableStateOf<ImageBitmap?>(null) }
    var videoPath by remember(ref) { mutableStateOf<String?>(null) }
    var videoLoadFailed by remember(ref) { mutableStateOf(false) }

    fun isVideo(p: AttachmentPreview?): Boolean = p?.mediaType?.startsWith("video/") == true

    /** 非媒体附件的类型字形（audio / pdf / 文本 / 其它二进制）。 */
    fun fileKindGlyph(mediaType: String): String = when {
        mediaType.startsWith("audio/") -> "♪"
        mediaType == "application/pdf" -> "📕"
        mediaType.startsWith("text/") -> "📄"
        else -> "📎"
    }

    /**
     * 视频准备（iOS prepareVideo :1699-1740 直译）：数据写稳定缓存 → 封面帧
     * （biuz-thumb 内容寻址缓存）；force = 点按重取路径（文件被系统回收后重建）。
     * 写盘失败静默保持旧态（iOS try? return 同口径——缩略格仍在，点按再试）。
     */
    suspend fun prepareVideo(p: AttachmentPreview, force: Boolean) {
        if (!force) {
            val existing = videoPath
            if (existing != null && File(existing).isFile) return
        }
        val file = withContext(Dispatchers.IO) {
            StableVideoCache.write(context, sessionId, ref, p.mediaType, p.data)
        } ?: return
        videoPath = file.absolutePath
        videoLoadFailed = false
        if (poster == null) {
            val frame = withContext(Dispatchers.IO) { VideoPosterCache.posterFor(context, p.data) }
            poster = frame?.asImageBitmap()
        }
    }

    LaunchedEffect(ref) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching { store.attachmentPreview(sessionId = sessionId, ref = ref) }.getOrNull()
        }
        preview = loaded
        failed = loaded == null
        if (loaded != null && !isVideo(loaded) && imageBitmap == null) {
            imageBitmap = withContext(Dispatchers.IO) { decodeSampledBitmap(loaded.data) }?.asImageBitmap()
        }
        if (loaded != null && isVideo(loaded)) {
            prepareVideo(loaded, force = false)
        }
    }

    /**
     * 视频点按（iOS openVideo :1679-1692 直译）：稳定缓存文件在场即进全屏；
     * 缺（系统回收）则重取重建；仍失败如实上屏（videoLoadFailed 失败格），禁静默空点。
     */
    fun openVideo() {
        val p = preview ?: return
        val existing = videoPath
        if (existing != null && File(existing).isFile) {
            fullscreen.item = FullscreenAttachment.Video(existing)
            return
        }
        scope.launch {
            prepareVideo(p, force = true)
            val path = videoPath
            if (path != null && File(path).isFile) {
                fullscreen.item = FullscreenAttachment.Video(path)
            } else {
                videoLoadFailed = true
            }
        }
    }

    val currentImage = imageBitmap
    when {
        currentImage != null -> {
            Box(
                modifier = modifier
                    .size(width = THUMB_WIDTH, height = THUMB_HEIGHT)
                    .clip(BiuZRadius.medium)
                    .border(1.dp, BiuZBorder, BiuZRadius.medium)
                    .combinedClickableSafe {
                        val p = preview
                        if (p != null) fullscreen.item = FullscreenAttachment.Image(p)
                    }
                    .testTag("05-attachment-thumb"),
            ) {
                Image(
                    bitmap = currentImage,
                    contentDescription = stringResource(R.string.attach_thumb_cd),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        isVideo(preview) -> {
            // 视频缩略格（封面帧 → 兜底格 → 失败格；播放角标；**无恒转圈死路**——v1.32②）
            Box(
                modifier = modifier
                    .size(width = THUMB_WIDTH, height = THUMB_HEIGHT)
                    .clip(BiuZRadius.medium)
                    .border(1.dp, BiuZBorder, BiuZRadius.medium)
                    .background(BiuZBgInput)
                    .combinedClickableSafe { openVideo() }
                    .testTag("05-attachment-thumb"),
                contentAlignment = Alignment.Center,
            ) {
                val posterBitmap = poster
                when {
                    videoLoadFailed -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(text = "▶̸", fontSize = 16.sp, color = BiuZText3)
                        Text(
                            text = stringResource(R.string.attach_video_load_fail),
                            fontSize = 10.sp,
                            color = BiuZText3,
                        )
                    }
                    posterBitmap != null -> Image(
                        bitmap = posterBitmap,
                        contentDescription = stringResource(R.string.attach_thumb_cd),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    else -> Text(text = "▶", fontSize = 20.sp, color = BiuZText3)
                }
                // 播放角标（封面在场时叠加；iOS play.circle.fill 同位）
                if (posterBitmap != null && !videoLoadFailed) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .background(Color.Black.copy(alpha = BiuZAlpha.half), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = "▶", fontSize = 12.sp, color = Color.White)
                    }
                }
            }
        }
        failed -> {
            // 读失败：轻量失败态（不占死块——仅一行提示；iOS :1588-1597 同口径）
            Row(
                modifier = modifier
                    .padding(vertical = BiuZSpacing.xs)
                    .testTag("05-attachment-failed"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.xs),
            ) {
                Text(text = "⚠", fontSize = 11.sp, color = BiuZText3)
                Text(
                    text = stringResource(R.string.attach_load_fail),
                    fontSize = 11.sp,
                    color = BiuZText3,
                )
            }
        }
        // 非图片/视频但预览已到位（audio/*、application/pdf、file/* …）：出**文件格**
        // ——媒体类型字形 + 文件名。原实现落到下面「读面在途」转圈分支，而这些类型
        // BitmapFactory 永远解不出图 → **永久转圈**（无文件名、无图标、不可点）。
        // composer 侧待发附件早有 `📄` 兜底（见本文件草稿条分支），会话流侧缺这层。
        else -> {
            val filePreview = preview
            if (filePreview != null && !isVideo(filePreview)) {
                Column(
                    modifier = Modifier
                        .size(width = 132.dp, height = 96.dp)
                        .clip(BiuZRadius.medium)
                        .background(BiuZBgInput)
                        .border(1.dp, BiuZBorder, BiuZRadius.medium)
                        .padding(BiuZSpacing.tiny)
                        .testTag("05-attachment-file"),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = fileKindGlyph(filePreview.mediaType),
                        fontSize = 20.sp,
                        color = BiuZText2,
                    )
                    Spacer(Modifier.size(2.dp))
                    Text(
                        text = filePreview.mediaType,
                        style = TextStyle(fontSize = 10.sp),
                        color = BiuZText3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                // 读面在途：小转圈（预览未到达；到达后上面各分支必接管）
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(BiuZSpacing.tiny)
                        .size(14.dp)
                        .testTag("05-attachment-loading"),
                    strokeWidth = 2.dp,
                    color = BiuZText3,
                )
            }
        }
    }
}

// MARK: - 待发附件缩略卡（iOS PendingAttachmentThumb，ChatView.swift:2631-2746 直译）

/**
 * 72dp 待发卡：底部 4dp 进度条 + mono 百分比（上传进度行）；成功勾角标 / 失败橙角标
 * （点条目重试）；右上角 ✕ 移除（热区）；非图片按 mediaType 落兜底（视频走封面帧）。
 * 解码缓存（iOS cachedPreview 教训直译）：逐块进度触发重组，不解码缓存即每块全尺寸
 * 重解码（20MB/54 块 = 54 次解码尖峰）——remember(item.id) 一次解码。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PendingAttachmentThumb(
    item: PendingAttachment,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var previewImage by remember(item.id) { mutableStateOf<ImageBitmap?>(null) }
    var poster by remember(item.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(item.id) {
        if (item.isImage && previewImage == null) {
            previewImage = withContext(Dispatchers.IO) { decodeSampledBitmap(item.data, maxDim = 512) }?.asImageBitmap()
        } else if (item.isVideo && poster == null) {
            poster = withContext(Dispatchers.IO) { VideoPosterCache.posterFor(context, item.data) }?.asImageBitmap()
        }
    }
    Column(
        modifier = modifier
            .width(72.dp)
            .testTag("05-attach-item"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(BiuZRadius.small)
                .border(1.dp, BiuZBorder, BiuZRadius.small),
        ) {
            // 基底（图片解码 → 视频封面帧 → mediaType 兜底字形；无空态）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .combinedClickableSafe(
                        enabled = item.state == PendingAttachment.State.failed,
                    ) { onRetry() },
                contentAlignment = Alignment.Center,
            ) {
                val image = previewImage
                val posterBitmap = poster
                when {
                    item.isImage && image != null -> Image(
                        bitmap = image,
                        contentDescription = item.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    item.isVideo && posterBitmap != null -> Image(
                        bitmap = posterBitmap,
                        contentDescription = item.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    else -> Text(
                        text = when {
                            item.isImage -> "🖼"
                            item.isVideo -> "🎬"
                            else -> "📄"
                        },
                        fontSize = 18.sp,
                    )
                }
            }
            // 上传态层：进度条 + 百分比 / pending 转圈（iOS 底部叠层同构）
            when (item.state) {
                PendingAttachment.State.uploading -> Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = BiuZSpacing.xs, vertical = BiuZSpacing.xxs),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    ThinUploadProgressBar(progress = item.progress.toFloat())
                    Text(
                        text = item.percentText,
                        style = TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace),
                        color = BiuZText2,
                    )
                }
                PendingAttachment.State.pending -> CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(BiuZSpacing.xs)
                        .size(14.dp),
                    strokeWidth = 2.dp,
                    color = BiuZText3,
                )
                else -> Unit
            }
            // 状态角标（committed 勾 / failed 橙叹号；左上角）
            when (item.state) {
                PendingAttachment.State.committed -> StatusBadge {
                    Text(text = "✓", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = BiuZAccent)
                }
                PendingAttachment.State.failed -> StatusBadge {
                    Text(text = "!", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = BiuZOrange)
                }
                else -> Unit
            }
            // ✕ 移除（32dp 热区包 18dp 圆底；右上角；iOS 44pt 热区口径）
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(32.dp)
                    .combinedClickableSafe(onClick = onRemove)
                    .testTag("05-attach-remove"),
                contentAlignment = Alignment.TopEnd,
            ) {
                Box(
                    modifier = Modifier
                        .padding(BiuZSpacing.xs)
                        .size(18.dp)
                        .background(BiuZBgCard.copy(alpha = BiuZAlpha.nearSolid), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "✕", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = BiuZText2)
                }
            }
        }
        Text(
            text = item.name,
            style = TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace),
            color = BiuZText3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StatusBadge(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .padding(BiuZSpacing.xxs)
            .size(18.dp)
            .background(BiuZBgCard.copy(alpha = BiuZAlpha.nearSolid), CircleShape),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** 4dp 细进度条（iOS ThinProgressBar 对位；fraction 0..1）。 */
@Composable
private fun ThinUploadProgressBar(progress: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(BiuZBgInput),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(BiuZAccent),
        )
    }
}

// MARK: - 全屏宿主（单一 Dialog 位点；图片原图 / 视频播放器二分支）

/**
 * 全屏附件宿主（v1.32③ 单一宿主：图片/视频合流一个 Dialog 位点，item == null 不挂载）。
 * 关闭单路径：state.item = null。
 */
@Composable
fun AttachmentFullscreenHost(state: AttachmentFullscreenState) {
    val item = state.item ?: return
    Dialog(
        onDismissRequest = { state.item = null },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            // 全屏黑底延伸到系统栏后（iOS ignoresSafeArea 同口径）
            decorFitsSystemWindows = false,
        ),
    ) {
        when (item) {
            is FullscreenAttachment.Image -> AttachmentImageFullscreen(
                preview = item.preview,
                onClose = { state.item = null },
            )
            is FullscreenAttachment.Video -> AttachmentVideoFullscreen(
                videoPath = item.path,
                onClose = { state.item = null },
            )
        }
    }
}

/** 全屏图片查看（黑底 scaledToFit + 关闭；iOS AttachmentFullscreenView 同构——保存相册面 Android scoped storage 权限差异挂账另轮）。 */
@Composable
private fun AttachmentImageFullscreen(preview: AttachmentPreview, onClose: () -> Unit) {
    var bitmap by remember(preview.ref) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(preview.ref) {
        if (bitmap == null) {
            bitmap = withContext(Dispatchers.IO) { decodeSampledBitmap(preview.data, maxDim = 2048) }?.asImageBitmap()
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("05-attachment-full"),
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = stringResource(R.string.attach_full_image_cd),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = stringResource(R.string.attach_load_fail),
                fontSize = 13.sp,
                color = Color.White.copy(alpha = BiuZAlpha.nearSolid),
                modifier = Modifier.align(Alignment.Center),
            )
        }
        FullscreenCloseButton(onClose = onClose, modifier = Modifier.align(Alignment.TopEnd))
    }
}

/**
 * 全屏视频播放（MediaPlayer + TextureView）：自动开播、关闭即释放；失败上屏错误行
 * （禁黑屏无声——v1.32③；错误原文 = OnErrorListener what/extra 或同步异常 message）。
 * 重试 = epoch +1 重建 surface 与 player（稳定路径在场时瞬时解码器故障可自愈；
 * 文件真坏仍失败，错误行常驻）。
 */
@Composable
private fun AttachmentVideoFullscreen(videoPath: String, onClose: () -> Unit) {
    var failureText by remember(videoPath) { mutableStateOf<String?>(null) }
    // 装配世代：重试 +1 → key 块销毁旧 TextureView/player 并全新装配
    var playbackEpoch by remember(videoPath) { mutableStateOf(0) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("05-attachment-video-full"),
    ) {
        if (failureText == null) {
            key(playbackEpoch) {
                VideoPlayerSurface(
                    videoPath = videoPath,
                    failPrefix = stringResource(R.string.attach_video_play_fail),
                    onError = { failureText = it },
                )
            }
        } else {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(BiuZSpacing.sm),
            ) {
                Text(text = "▶̸", fontSize = 24.sp, color = Color.White.copy(alpha = BiuZAlpha.dense))
                Text(
                    text = failureText ?: "",
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = Color.White.copy(alpha = BiuZAlpha.nearSolid),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = BiuZSpacing.xl),
                )
                Text(
                    text = stringResource(R.string.attach_video_retry),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = BiuZAccent,
                    modifier = Modifier
                        .combinedClickableSafe {
                            failureText = null
                            playbackEpoch += 1
                        }
                        .padding(BiuZSpacing.sm),
                )
            }
        }
        FullscreenCloseButton(onClose = onClose, modifier = Modifier.align(Alignment.TopEnd))
    }
}

/**
 * 播放器承载面：TextureView（AndroidView 互操作）+ MediaPlayer。player 实例由
 * DisposableEffect onDispose 统一释放（关闭即停并释放——iOS onDisappear 同口径）。
 */
@Composable
private fun VideoPlayerSurface(videoPath: String, failPrefix: String, onError: (String) -> Unit) {
    val playerRef = remember { arrayOfNulls<MediaPlayer>(1) }
    DisposableEffect(videoPath) {
        onDispose {
            playerRef[0]?.let { p ->
                runCatching { p.stop() }
                runCatching { p.release() }
            }
            playerRef[0] = null
        }
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        surface: SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) {
                        startPlayback(videoPath, Surface(surface), playerRef) { detail ->
                            onError("$failPrefix · $detail")
                        }
                    }

                    override fun onSurfaceTextureSizeChanged(
                        surface: SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) = Unit

                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true

                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                }
            }
        },
    )
}

/**
 * 装配并开播（onSurfaceTextureAvailable 回调线程；自动开播 iOS onAppear play 同构）。
 * 失败原文：同步装配异常落 exception message；运行期错误落 what/extra 码（MediaPlayer
 * 无 message 文本，码即原文）——两路都上屏，返回 true 吞掉框架默认错误对话框。
 */
private fun startPlayback(
    videoPath: String,
    surface: Surface,
    playerRef: Array<MediaPlayer?>,
    onError: (String) -> Unit,
) {
    val player = MediaPlayer()
    playerRef[0] = player
    try {
        player.setDataSource(videoPath)
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build(),
        )
        player.setSurface(surface)
        player.setOnErrorListener { _, what, extra ->
            onError("what=$what extra=$extra")
            true // 已处理：禁框架默认错误对话框（失败上屏由调用方承担）
        }
        player.setOnPreparedListener { it.start() }
        player.prepareAsync()
    } catch (e: Exception) {
        onError(e.message ?: e.toString())
        runCatching { player.release() }
        playerRef[0] = null
    }
}

/** 全屏关闭钮（44dp 热区；半透明圆底 ✕；iOS xmark 钮同位 top-end）。 */
@Composable
private fun FullscreenCloseButton(onClose: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(top = BiuZSpacing.lg, end = BiuZSpacing.lg)
            .size(44.dp)
            .combinedClickableSafe(onClick = onClose)
            .testTag("05-attachment-close"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(Color.White.copy(alpha = BiuZAlpha.faint), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "✕",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
        }
    }
}

/** combinedClickable 包装（ExperimentalFoundationApi 收口一处）。 */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableSafe(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    this.combinedClickable(enabled = enabled, onClick = onClick)
