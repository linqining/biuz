package cn.biuz.android.app.ui.screen

import android.content.Context
import cn.biuz.android.app.R
import cn.biuz.android.connection.AppSession
import cn.biuz.android.connection.ServerConfig
import cn.biuz.android.protocol.ConnectURLParser
import java.util.UUID

/**
 * 「识别 → 连接」三路共用动作（剪贴板一键填充 / 扫码识别 / 开发者模式手动表单）：
 * - 中继配对链接（https://…/remote/v4?sid=…&hash=…&mid=…）→ connectRelayLink（装配层按
 *   deviceSid 复用注册表项，重复连接不膨胀列表——AppSession.connectRelayLink 同口径）；
 * - 局域网直连链接 / host:port → ServerConfig 发起连接；同 host:port:useTLS 的已存服务器
 *   就地更新令牌（updateToken 复用注册表项，防重复扫码堆叠条目；注册表清单仅装配层可见，
 *   :app 只能对 savedServer 去重——多服务器去重挂账装配层）；
 * - 解析失败 → 返回用户可读错误文案（内联红字，f-err 口径），不发起连接。
 *
 * 错误文案资源化（设置里程碑对账裁决「服务层错误串也进资源」）：本面 UI 自持提示
 * （空输入）经 [context] 取资源（双语）；协议层解析错误（ConnectURLParser.Failure
 * .error.message）仍是数据面原文，原样透传。
 */
internal object ConnectFlow {

    /**
     * 识别并连接。返回 null = 连接已发起（mode 转 Connecting）；非 null = 内联错误文案。
     * [manualToken] = 手动表单令牌栏（自动拆解路径 token 缺席时兜底）。
     */
    suspend fun connectFromText(
        context: Context,
        session: AppSession,
        raw: String,
        manualToken: String? = null,
    ): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return context.getString(R.string.connect_empty_input)
        val token = manualToken?.trim()?.takeIf { it.isNotEmpty() }
        return when (val link = ConnectURLParser.extractLink(trimmed)) {
            is ConnectURLParser.ParsedLink.Relay -> {
                session.connectRelayLink(trimmed)
                null
            }
            is ConnectURLParser.ParsedLink.Direct -> {
                connectDirect(session, link.parsed.copy(token = link.parsed.token ?: token))
                null
            }
            null -> when (val parsed = ConnectURLParser.parse(trimmed)) {
                is ConnectURLParser.ParseResult.Success -> {
                    connectDirect(session, parsed.parsed.copy(token = parsed.parsed.token ?: token))
                    null
                }
                is ConnectURLParser.ParseResult.Failure -> parsed.error.message
            }
        }
    }

    private suspend fun connectDirect(session: AppSession, parsed: ConnectURLParser.Parsed) {
        // token 归一为非空串：空串 = --no-token 免鉴权（ServerConfig.token 注）
        val token = parsed.token ?: ""
        val saved = session.savedServer
        if (saved != null && saved.host == parsed.host && saved.port == parsed.port &&
            saved.useTLS == parsed.useTLS
        ) {
            if (token.isNotEmpty() && token != saved.token) {
                session.updateToken(saved, token)
            } else {
                session.connect(saved)
            }
            return
        }
        session.connect(
            ServerConfig(
                id = UUID.randomUUID().toString(),
                name = null,
                host = parsed.host,
                port = parsed.port,
                useTLS = parsed.useTLS,
                token = token,
            ),
        )
    }
}
