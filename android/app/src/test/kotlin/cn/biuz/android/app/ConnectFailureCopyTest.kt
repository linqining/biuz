package cn.biuz.android.app

import cn.biuz.android.app.ui.screen.ConnectFailureHint
import cn.biuz.android.app.ui.screen.ConnectRecovery
import cn.biuz.android.app.ui.screen.connectFailureBodyRes
import cn.biuz.android.app.ui.screen.connectFailureHints
import cn.biuz.android.app.ui.screen.connectFailureTitleRes
import cn.biuz.android.app.ui.screen.failureCopyForConnectError
import cn.biuz.android.app.ui.screen.failureReasonForConnectError
import cn.biuz.android.app.ui.screen.recoveryForConnectFailure
import cn.biuz.android.connection.ConnectError
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * L3 云中继登录门槛分支回归门（2026-11-23，iOS `ConnectError.relayAuthRequired` 同构移植）。
 *
 * 断言词面与 iOS 一致：reason=relay-auth-required、主 CTA=登录 Z.ai 账号、
 * 对照行只出 RelayAuth（不混入 WS/超时族检查项）。
 */
class ConnectFailureCopyTest {

    @Test
    fun relayAuthRequiredMapsToDedicatedReasonAndCopy() {
        val error = ConnectError.RelayAuthRequired
        assertEquals("relay-auth-required", failureReasonForConnectError(error))
        val copy = failureCopyForConnectError(error)
        assertEquals("relay-auth-required", copy.reason)
        assertEquals("登录门槛", copy.badge)
        assertEquals("云中继需要登录 Z.ai 账号", copy.title)
        // 主 CTA 词面（= R.string.login_entry 同串）
        assertEquals("登录 Z.ai 账号", copy.action)
    }

    @Test
    fun relayAuthRequiredDoesNotFallBackToTransport() {
        // Transport（含此前被包成 Transport 的中继鉴权错误）仍走 relay-unavailable；
        // 只有 RelayAuthRequired 走专属分支——防止映射回退成「中转异常 + 重试」死路。
        assertEquals(
            "relay-unavailable",
            failureReasonForConnectError(ConnectError.Transport("云中继需要登录 Z.ai 账号（缺少登录凭据）")),
        )
    }

    @Test
    fun relayAuthHintsOnlyRelayAuth() {
        assertEquals(
            listOf<ConnectFailureHint>(ConnectFailureHint.RelayAuth),
            connectFailureHints(ConnectError.RelayAuthRequired),
        )
    }

    // MARK: 失败卡简化呈现层（2026-11-24 交互重构：一行结论 + 一句话 + 恢复动作）

    /** web 12 种失败 reason + 未登记 reason：标题/说明都必须有资源，未登记的落通用「连接失败」。 */
    @Test
    fun simplifiedCopy_coversEveryReason_andUnknownFallsBackToGeneric() {
        val reasons = listOf(
            "device-not-owned", "session-not-found", "session-expired", "session-conflict",
            "workspace-closed", "desktop-disconnected", "invalid-mobile-connection",
            "desktop-bootstrap-timeout", "connection-recovery-timeout", "relay-unavailable",
            "relay-auth-required", "unsupported-action", "unexpected-error",
        )
        for (reason in reasons) {
            assertTrue(connectFailureTitleRes(reason) != 0, "缺标题资源：$reason")
            assertTrue(connectFailureBodyRes(reason) != 0, "缺说明资源：$reason")
        }
        assertEquals(
            "unexpected-error".let { connectFailureTitleRes(it) },
            connectFailureTitleRes("never-registered-reason"),
            "未登记 reason 必须落通用标题，不得缺文案",
        )
        // 同义原因共用一份文案（失效两态 / 超时两态 / 无项目两态）
        assertEquals(
            connectFailureTitleRes("session-not-found"),
            connectFailureTitleRes("invalid-mobile-connection"),
        )
        assertEquals(
            connectFailureBodyRes("desktop-bootstrap-timeout"),
            connectFailureBodyRes("connection-recovery-timeout"),
        )
    }

    /** 恢复动作跟随「什么能解」：要登录 → LOGIN；链接失效 → RESCAN；其余 → RECONNECT。 */
    @Test
    fun simplifiedRecovery_followsWhatFixesTheError() {
        fun recoveryOf(error: ConnectError) =
            recoveryForConnectFailure(failureReasonForConnectError(error))
        assertEquals(ConnectRecovery.LOGIN, recoveryOf(ConnectError.RelayAuthRequired))
        assertEquals(ConnectRecovery.LOGIN, recoveryForConnectFailure("device-not-owned"))
        assertEquals(ConnectRecovery.RESCAN, recoveryOf(ConnectError.Http(401, "GET /api/server-info")))
        assertEquals(ConnectRecovery.RESCAN, recoveryForConnectFailure("session-not-found"))
        assertEquals(ConnectRecovery.RECONNECT, recoveryOf(ConnectError.Timeout("GET /api/server-info")))
        assertEquals(ConnectRecovery.RECONNECT, recoveryOf(ConnectError.Transport("conn refused")))
        assertEquals(ConnectRecovery.RECONNECT, recoveryOf(ConnectError.EmptyWorkspaces))
        assertEquals(ConnectRecovery.RECONNECT, recoveryOf(ConnectError.HandshakeFailed("x")))
        assertEquals(ConnectRecovery.RECONNECT, recoveryOf(ConnectError.ProtocolVersion("remote v2")))
    }

    /**
     * 连接页新键 zh/en 目录对账（AGENTS §5-18①：xcodebuild/AGP 不提取新键，新键必须手工入
     * 两份目录；缺 en = en 态回退中文）。只读源码树上的两份 strings.xml。
     */
    @Test
    fun connectPageNewKeys_existInBothLocaleCatalogs() {
        fun catalog(path: String): Set<String> {
            val candidates = listOf(File(path), File("app/$path"))
            val file = candidates.firstOrNull { it.isFile }
                ?: error("找不到字符串目录 $path（workingDir=${File(".").absolutePath}）")
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val nodes = doc.getElementsByTagName("string")
            return buildSet { for (i in 0 until nodes.length) add(nodes.item(i).attributes.getNamedItem("name").nodeValue) }
        }
        val zh = catalog("src/main/res/values/strings.xml")
        val en = catalog("src/main/res/values-en/strings.xml")
        val keys = buildList {
            add("app_wordmark")
            add("connect_reconnect_fmt")
            add("connect_rescan_reconnect")
            add("login_entry")
            add("devices_title")
            addAll(
                listOf(
                    "auth", "owned", "stale", "closed", "conflict",
                    "noproj", "offline", "timeout", "network", "generic",
                ).flatMap { listOf("connect_fail_s_${it}_title", "connect_fail_s_${it}_body") },
            )
        }
        keys.forEach { key ->
            assertTrue(key in zh, "zh 缺键 $key")
            assertTrue(key in en, "en 缺键 $key")
        }
    }
}
