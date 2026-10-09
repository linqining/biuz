package cn.biuz.android.app.ui.chat

// 附件媒体辅助（iOS MessageViews.swift AttachmentThumbView 的媒体半边直译，2026-10-10）。
//
// 键纯函数（AttachmentMediaKeys，JVM 单测直测）对位 iOS safeKey/扩展名推导：
// - safeKey = iOS addingPercentEncoding(withAllowedCharacters: .alphanumerics) 同构
//   （MessageViews.swift:1709-1710）——只保留 ASCII 字母数字，其余逐 UTF-8 字节 %XX，
//   保证落盘文件名无路径分隔符、跨次稳定（稳定键复用同一路径，不叠随机文件垃圾）；
// - 视频扩展名 = iOS :1704-1708 同表（quicktime→mov / webm→webm / 其他→mp4）。
//
// 稳定缓存（v1.32③ 教训直译）：视频数据落 **cacheDir/biuz-video**（不是 tmpDir——
// iOS 原实现写 temporaryDirectory 被系统回收，切后台回来文件已失而封面还在内存，
// 点按即黑屏）；封面帧落 **cacheDir/biuz-thumb**（data SHA-256 键，跨重组/返场复用，
// 免每帧重抽——MediaMetadataRetriever 抽帧是重 IO）。
//
// 线程口径：全部 IO 函数挂起式由调用方在 Dispatchers.IO 上执行（UI 层 LaunchedEffect
// + withContext）；本文件不做线程切换以便单测直驱。

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.math.min

/** 附件媒体键纯函数（JVM 可测；零 Android 依赖）。 */
object AttachmentMediaKeys {

    /**
     * 百分比编码安全键（iOS addingPercentEncoding(.alphanumerics) 同构，逐 UTF-8 字节）：
     * 输出只含 [A-Za-z0-9%]——无路径分隔符、无 ".." 原文、确定性（同输入恒同输出）。
     */
    fun safeKey(raw: String): String {
        val bytes = raw.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder(bytes.size * 3)
        for (b in bytes) {
            val c = b.toInt().toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9') {
                sb.append(c)
            } else {
                sb.append('%').append("%02X".format(b))
            }
        }
        return sb.toString()
    }

    /** 视频容器扩展名（iOS MessageViews.swift:1704-1708 同表；未知 mime 兜底 mp4）。 */
    fun videoExtensionFor(mediaType: String): String = when (mediaType) {
        "video/quicktime" -> "mov"
        "video/webm" -> "webm"
        else -> "mp4"
    }

    /** 小写十六进制 SHA-256（十六进制字符恒 64 位；输入字节级确定性）。 */
    fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    /** 封面帧缓存文件名（biuz-thumb 目录内；内容寻址——同文件字节恒同键）。 */
    fun posterCacheName(data: ByteArray): String = "biuz-${sha256Hex(data)}.png"

    /** 稳定视频缓存文件名（biuz-video 目录内；<会话>-<ref> 键 + 容器扩展名，iOS :1709-1714 同构）。 */
    fun stableVideoName(sessionId: String, ref: String, mediaType: String): String =
        "biuz-${safeKey("$sessionId-$ref")}.${videoExtensionFor(mediaType)}"
}

/** 视频数据稳定缓存（cacheDir/biuz-video；v1.32③「切后台文件被回收→点按黑屏」根修）。 */
object StableVideoCache {

    /** 稳定缓存目录（cacheDir——系统可清但生命周期远长于 tmp；重建成本低）。 */
    fun dir(context: Context): File = File(context.cacheDir, "biuz-video")

    fun fileFor(context: Context, sessionId: String, ref: String, mediaType: String): File =
        File(dir(context), AttachmentMediaKeys.stableVideoName(sessionId, ref, mediaType))

    /**
     * 原子写盘（tmp + rename 对位 iOS .atomic）：失败返回 null（调用方保持旧态，
     * 点按重取路径再试；禁静默假成功——返回 null 即上层 failed 口径）。
     */
    fun write(
        context: Context,
        sessionId: String,
        ref: String,
        mediaType: String,
        data: ByteArray,
    ): File? {
        return try {
            val target = fileFor(context, sessionId, ref, mediaType)
            val parent = target.parentFile
            if (parent != null && !parent.isDirectory) parent.mkdirs()
            val tmp = File(parent, "${target.name}.part")
            tmp.outputStream().use { it.write(data) }
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return null
            }
            target
        } catch (e: IOException) {
            null
        }
    }
}

/** 视频封面帧缓存（cacheDir/biuz-thumb；MediaMetadataRetriever 抽 0s 帧，iOS AVAssetImageGenerator 同位）。 */
object VideoPosterCache {

    fun dir(context: Context): File = File(context.cacheDir, "biuz-thumb")

    /**
     * 取封面帧：缓存命中直接解码；未命中抽帧后 PNG 落盘再回。
     * 失败返回 null（调用方落「video 图标兜底格」，不转圈不空块——iOS 兜底格同口径）。
     */
    fun posterFor(context: Context, data: ByteArray): Bitmap? {
        val parent = dir(context)
        val cached = File(parent, AttachmentMediaKeys.posterCacheName(data))
        if (cached.isFile) {
            BitmapFactory.decodeFile(cached.absolutePath)?.let { return it }
            // 缓存损坏（半写/格式坏）：删除重建
            cached.delete()
        }
        val frame = extractFrame(data) ?: return null
        try {
            if (!parent.isDirectory) parent.mkdirs()
            val tmp = File(parent, "${cached.name}.part")
            tmp.outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 90, it) }
            if (!tmp.renameTo(cached)) {
                tmp.delete()
                return frame // 落盘失败不致命：帧已生成，直接回（本会话内存可用）
            }
        } catch (e: IOException) {
            return frame
        }
        return frame
    }

    /** 抽 0s 帧：内存 MediaDataSource 主路（API 23+，minSdk 24 覆盖）；异常回退临时文件。 */
    private fun extractFrame(data: ByteArray): Bitmap? {
        // 显式 release（MediaMetadataRetriever 的 AutoCloseable 是 API 29+，minSdk 24 禁 use）
        val retriever = MediaMetadataRetriever()
        try {
            try {
                retriever.setDataSource(ByteArrayMediaDataSource(data))
            } catch (e: Exception) {
                return extractFrameViaTempFile(retriever, data)
            }
            return try {
                retriever.getFrameAtTime(0)
            } catch (e: Exception) {
                null
            }
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // release 异常不掩盖抽帧结果
            }
        }
    }

    private fun extractFrameViaTempFile(retriever: MediaMetadataRetriever, data: ByteArray): Bitmap? {
        return try {
            val tmp = File.createTempFile("biuz-frame", ".vid")
            try {
                tmp.outputStream().use { it.write(data) }
                retriever.setDataSource(tmp.absolutePath)
                retriever.getFrameAtTime(0)
            } finally {
                tmp.delete()
            }
        } catch (e: Exception) {
            null
        }
    }
}

/** 内存字节源（MediaMetadataRetriever.setDataSource(MediaDataSource)，API 23+）。 */
private class ByteArrayMediaDataSource(private val data: ByteArray) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        val pos = position.toInt()
        if (pos < 0 || pos >= data.size) return -1
        val count = min(size, data.size - pos)
        if (count <= 0) return 0
        System.arraycopy(data, pos, buffer, offset, count)
        return count
    }

    override fun getSize(): Long = data.size.toLong()

    override fun close() {}
}

/** 降采样解码（20MB 原图直解码 OOM 防护；最长边约 [maxDim] 内）。 */
fun decodeSampledBitmap(data: ByteArray, maxDim: Int = 1024): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(data, 0, data.size, options)
}
