package cn.biuz.android.app.ui.screen

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import cn.biuz.android.app.R
import cn.biuz.android.app.ui.components.BiuzInlineNotice
import cn.biuz.android.app.ui.components.BiuzNoticeKind
import cn.biuz.android.app.ui.theme.BiuZRadius
import cn.biuz.android.app.ui.theme.BiuZSpacing
import cn.biuz.android.app.ui.theme.BiuZText3
import cn.biuz.android.connection.AppSession
import cn.biuz.android.connection.OAuthException
import cn.biuz.android.connection.OAuthProviderId
import kotlinx.coroutines.launch

/**
 * 001-登录（对齐 web `WebLoginPage.tsx:13-52` + `webAuthLocale.ts:20-24` 文案）。
 *
 * 流程：选 provider → 系统 WebView 打开授权页 → 拦截回调 URL →
 * `AppSession.completeOAuth`（校验 state + 换 token + Keystore 落凭据）→
 * `autoConnectAfterLogin`（设备目录按官方顺序落点）→ 回主界面。
 *
 * 失败一律如实上屏（[OAuthException] 五态文案），不静默、不伪造已登录。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(
    session: AppSession,
    onClose: () -> Unit,
    onLoggedIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var authorizeUrl by remember { mutableStateOf<String?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    // 调试入口（仅 debug 构建）：手填 ZCode JWT，跳过 OAuth 交换验证后续链路
    val debugEnabled = cn.biuz.android.app.BuildConfig.DEBUG
    var showInject by remember { mutableStateOf(false) }
    var injectToken by remember { mutableStateOf("") }

    fun finishWithCallback(callbackUrl: String) {
        if (submitting) return
        submitting = true
        scope.launch {
            try {
                val cookie = CookieManager.getInstance().getCookie("https://zcode.z.ai")
                session.completeOAuth(callbackUrl, cookieHeader = cookie)
                session.autoConnectAfterLogin()
                onLoggedIn()
            } catch (err: OAuthException) {
                errorText = err.message
            } catch (err: Exception) {
                errorText = err.message ?: "登录失败"
            } finally {
                submitting = false
                authorizeUrl = null
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        val url = authorizeUrl
        if (url != null) {
            // 授权页：取消入口放在 WebView **上方独立顶栏**——Android WebView 是独立 surface，
            // 与 Compose 兄弟节点不共享 z 序，叠在它上面的浮层会被盖住（实测首版「取消」不可见）。
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(horizontal = BiuZSpacing.lg, vertical = BiuZSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.login_cancel),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                        modifier = Modifier
                            .clip(BiuZRadius.medium)
                            .clickable {
                                authorizeUrl = null
                                onClose()
                            }
                            .padding(horizontal = BiuZSpacing.md, vertical = BiuZSpacing.sm)
                            .testTag("01-oauth-cancel"),
                    )
                }
                // 注意：update 块每次重组都会执行；在这里 loadUrl 会随重组无限重载导致页面
                // 永远停在加载态（实测：空白 + 三点转圈）。加载只在 factory 里做一次。
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .testTag("01-oauth-webview"),
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.javaScriptCanOpenWindowsAutomatically = true
                            // 授权链路依赖 cookie（authorize 与回调同浏览器会话）
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): Boolean {
                                    val target = request?.url?.toString() ?: return false
                                    if (isOAuthCallback(target)) {
                                        android.util.Log.i("zhupi", "oauth callback intercepted: " + target.substringBefore('?'))
                                        finishWithCallback(target)
                                        return true
                                    }
                                    return false
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    android.util.Log.i("zhupi", "oauth webview finished: " + url?.substringBefore('?'))
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: android.webkit.WebResourceError?,
                                ) {
                                    if (request?.isForMainFrame != true) return
                                    android.util.Log.e("zhupi", "oauth webview error: " + error?.description + " @ " + request.url)
                                    errorText = "无法打开授权页（${error?.description ?: "网络错误"}）"
                                }
                            }
                            webChromeClient = object : android.webkit.WebChromeClient() {
                                override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
                                    android.util.Log.i("zhupi", "webview console: " + msg?.message())
                                    return true
                                }
                            }
                            loadUrl(url)
                        }
                    },
                )
            }
            return@Box
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = BiuZSpacing.xl),
            verticalArrangement = Arrangement.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, BiuZRadius.medium)
                    .padding(BiuZSpacing.xl),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BiuZSpacing.md),
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Z",
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontSize = 20.sp,
                        )
                    }
                    Column {
                        Text(
                            text = stringResource(R.string.login_brand),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 12.sp,
                        )
                        Text(
                            text = stringResource(R.string.login_title),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 18.sp,
                        )
                    }
                }
                Spacer(Modifier.height(BiuZSpacing.lg))
                Text(
                    text = stringResource(R.string.login_description),
                    color = BiuZText3,
                    fontSize = 12.sp,
                    lineHeight = 20.sp,
                )
                Spacer(Modifier.height(BiuZSpacing.lg))

                ProviderButton(
                    label = stringResource(R.string.login_action_zai),
                    region = stringResource(R.string.login_region_global),
                    enabled = !submitting,
                    tag = "01-btn-zai",
                ) {
                    errorText = null
                    authorizeUrl = session.beginOAuth(OAuthProviderId.ZAI)
                }
                Spacer(Modifier.height(BiuZSpacing.sm))
                ProviderButton(
                    label = stringResource(R.string.login_action_bigmodel),
                    region = stringResource(R.string.login_region_cn),
                    enabled = !submitting,
                    tag = "01-btn-bigmodel",
                ) {
                    errorText = null
                    authorizeUrl = session.beginOAuth(OAuthProviderId.BIGMODEL)
                }

                errorText?.let { message ->
                    Spacer(Modifier.height(BiuZSpacing.md))
                    BiuzInlineNotice(
                        message = message,
                        kind = BiuzNoticeKind.Error,
                        tag = "01-login-error",
                    )
                }

                Spacer(Modifier.height(BiuZSpacing.lg))
                Text(
                    text = stringResource(R.string.login_close),
                    color = BiuZText3,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(BiuZRadius.small)
                        .clickable { onClose() }
                        .padding(vertical = BiuZSpacing.sm)
                        .testTag("01-login-close"),
                )
                if (debugEnabled) {
                    Spacer(Modifier.height(BiuZSpacing.sm))
                    Text(
                        text = "开发者：手动填入 ZCode JWT",
                        color = BiuZText3,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clip(BiuZRadius.small)
                            .clickable { showInject = true }
                            .padding(vertical = BiuZSpacing.sm)
                            .testTag("01-debug-inject"),
                    )
                }
            }
        }

        if (showInject) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showInject = false },
                title = { Text("开发者：填入 ZCode JWT") },
                text = {
                    Column {
                        Text(
                            "从已登录网页的 localStorage['zcodejwttoken'] 复制。仅用于联调验证设备目录 / Relay V2。",
                            fontSize = 12.sp,
                            color = BiuZText3,
                        )
                        Spacer(Modifier.height(BiuZSpacing.sm))
                        androidx.compose.material3.OutlinedTextField(
                            value = injectToken,
                            onValueChange = { injectToken = it },
                            label = { Text("zcodeJwtToken") },
                            singleLine = false,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(110.dp)
                                .testTag("01-debug-token"),
                        )
                    }
                },
                confirmButton = {
                    Text(
                        text = "保存并继续",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .clickable {
                                val jwt = injectToken.trim()
                                if (jwt.isNotEmpty()) {
                                    session.injectCredentials(
                                        accessToken = jwt,
                                        zcodeJwtToken = jwt,
                                        displayName = "调试账号",
                                    )
                                    showInject = false
                                    onLoggedIn()
                                }
                            }
                            .padding(BiuZSpacing.sm)
                            .testTag("01-debug-save"),
                    )
                },
                dismissButton = {
                    Text(
                        text = "取消",
                        color = BiuZText3,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .clickable { showInject = false }
                            .padding(BiuZSpacing.sm),
                    )
                },
            )
        }
    }
}

@Composable
private fun ProviderButton(
    label: String,
    region: String,
    enabled: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(
                if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                BiuZRadius.medium,
            )
            .clip(BiuZRadius.medium)
            .clickable(enabled = enabled, onClick = onClick)
            .testTag(tag)
            .padding(horizontal = BiuZSpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onPrimary,
            fontSize = 15.sp,
        )
        Spacer(Modifier.size(BiuZSpacing.sm))
        Text(
            text = region,
            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.65f),
            fontSize = 12.sp,
        )
    }
}

/**
 * 回调 URL 判定（与连接层 `ZaiOAuthClient.isCallbackUrl` 同口径）。
 *
 * 关键：远控入口的 `redirect_uri` 是 `https://zcode.z.ai/remote/v4`，**不是** `/cn/share/callback`；
 * 只按路径判会漏拦，WebView 会把回调页自己跑完（换 token 后跳到官方域），App 永远拿不到 code。
 * 因此判据以**参数形状**为准：`state` 在场且 `code|authCode|error` 任一在场即回调。
 */
internal fun isOAuthCallback(url: String): Boolean {
    val lower = url.lowercase()
    if (!lower.startsWith("https://") && !lower.startsWith("http://")) return false
    val query = lower.substringAfter('?', "")
    if (query.isEmpty()) return false
    fun has(name: String): Boolean = query.split('&').any { it.startsWith("$name=") && it.length > name.length + 1 }
    val hasState = has("state")
    return hasState && (has("code") || has("authcode") || has("error"))
}
