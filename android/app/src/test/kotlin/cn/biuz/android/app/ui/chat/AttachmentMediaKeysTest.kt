package cn.biuz.android.app.ui.chat

// 附件媒体键纯函数 JVM 单测（testDebugUnitTest）：safeKey 路径安全/确定性、视频扩展名
// 词表（iOS MessageViews.swift:1704-1708 同表）、稳定缓存命名形状、SHA-256 已知向量。
// MediaMetadataRetriever/BitmapFactory 是 Android 框架类——抽帧/解码面不可 JVM 直测，
// 归模拟器实跑面（自测说明见交付 selfCheck）。

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttachmentMediaKeysTest {

    // MARK: safeKey（iOS addingPercentEncoding(.alphanumerics) 同构）

    @Test
    fun safeKeyKeepsAlphanumericsOnly() {
        assertEquals("abcXYZ019", AttachmentMediaKeys.safeKey("abcXYZ019"))
    }

    @Test
    fun safeKeyEncodesPathAndDotSeparators() {
        // 会话 id / ref 常含 -_.；iOS .alphanumerics 只放行 L*/N*——`_`（Po）同 `-`（Pd）
        // 一并编码；路径分隔符必须不可出现（落盘文件名安全）
        val key = AttachmentMediaKeys.safeKey("sess_01/ref.name")
        assertFalse(key.contains('/'))
        assertFalse(key.contains(".."))
        assertEquals("sess%5F01%2Fref%2Ename", key)
    }

    @Test
    fun safeKeyEncodesMultibyteAsUtf8Bytes() {
        // 中文逐 UTF-8 字节 %XX（3 字节 → 3 段）；iOS percentEncoding 同构
        val key = AttachmentMediaKeys.safeKey("会")
        assertEquals("%E4%BC%9A", key)
    }

    @Test
    fun safeKeyIsDeterministicAndNonEmpty() {
        val input = "sess-abc/ref-01?x=1#frag 视频.mp4"
        assertEquals(AttachmentMediaKeys.safeKey(input), AttachmentMediaKeys.safeKey(input))
        assertTrue(AttachmentMediaKeys.safeKey(input).isNotEmpty())
    }

    // MARK: videoExtensionFor（iOS :1704-1708 同表）

    @Test
    fun videoExtensionForKnownContainers() {
        assertEquals("mov", AttachmentMediaKeys.videoExtensionFor("video/quicktime"))
        assertEquals("webm", AttachmentMediaKeys.videoExtensionFor("video/webm"))
        assertEquals("mp4", AttachmentMediaKeys.videoExtensionFor("video/mp4"))
    }

    @Test
    fun videoExtensionForUnknownFallsBackToMp4() {
        assertEquals("mp4", AttachmentMediaKeys.videoExtensionFor("video/x-matroska"))
        assertEquals("mp4", AttachmentMediaKeys.videoExtensionFor(""))
    }

    // MARK: sha256Hex（已知向量）

    @Test
    fun sha256HexMatchesKnownVector() {
        // echo -n "abc" | shasum -a 256
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            AttachmentMediaKeys.sha256Hex("abc".toByteArray()),
        )
        assertEquals(64, AttachmentMediaKeys.sha256Hex(ByteArray(0)).length)
    }

    // MARK: 缓存命名形状

    @Test
    fun posterCacheNameIsContentAddressed() {
        val data = "frame-bytes".toByteArray()
        val name = AttachmentMediaKeys.posterCacheName(data)
        assertEquals("biuz-${AttachmentMediaKeys.sha256Hex(data)}.png", name)
        // 同内容恒同键（缓存复用前提）；不同内容不同键
        assertEquals(name, AttachmentMediaKeys.posterCacheName("frame-bytes".toByteArray()))
        assertFalse(
            name == AttachmentMediaKeys.posterCacheName("other-frame".toByteArray()),
        )
    }

    @Test
    fun stableVideoNameComposesSafeKeyAndContainerExt() {
        val name = AttachmentMediaKeys.stableVideoName("sess01", "ref/01", "video/quicktime")
        assertEquals("biuz-sess01%2Dref%2F01.mov", name)
        assertEquals(
            "biuz-sess01%2Dref%2F01.mp4",
            AttachmentMediaKeys.stableVideoName("sess01", "ref/01", "video/mp4"),
        )
    }
}
