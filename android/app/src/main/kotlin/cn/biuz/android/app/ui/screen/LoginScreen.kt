package cn.biuz.android.app.ui.screen

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import cn.biuz.android.app.R
import cn.biuz.android.app.ui.components.BiuzInlineNotice
import cn.biuz.android.app.ui.components.SealBrandMark
import cn.biuz.android.app.ui.components.BiuzNoticeKind
import cn.biuz.android.app.ui.theme.BiuZAlpha
import cn.biuz.android.app.ui.theme.BiuZBorderStrong
import cn.biuz.android.app.ui.theme.BiuZOnSeal
import cn.biuz.android.app.ui.theme.BiuZRadius
import cn.biuz.android.app.ui.theme.BiuZSeal
import cn.biuz.android.app.ui.theme.BiuZSpacing
import cn.biuz.android.app.ui.theme.BiuZText
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
 *
 * **授权页 WebView 必须满窗口（2026-11-23 白屏根修）**：实测 Play WebView（133）在
 * 「WebView 几何窄于窗口」的宿主下（insets padding / weight 偏移等）视口单位
 * vh/dvh/svh/lvh 恒算 0（同一实例内 100% / px / innerHeight 均正常；裸 fillMaxSize
 * 或任何触摸窗口边缘的几何下正常；48dp 顶部偏移即复现，与是否重叠状态栏无关）。
 * chat.z.ai 授权页无 viewport meta、root 靠 h-screen/100dvh 撑高，vh=0 时整页折叠成
 * 12px 灰线（body overflow:hidden），表象即「点击登录白屏」。因此授权页 WebView 以
 * `fillMaxSize` 独占整窗（edge-to-edge，可绘制到系统栏下），顶栏（取消/加载失败/诊断）
 * 改为 **原生 View 叠在 WebView 之上**——AndroidView 子 View 恒绘制在 Compose 内容
 * 之上，Compose 顶栏无法覆盖 WebView。
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
    // 登录平台（2026-11-24：从「独立的选择页」收进主按钮下拉——默认 Z.ai，箭头就地切换）
    var provider by remember { mutableStateOf(LoginProvider.ZAI) }
    var menuExpanded by remember { mutableStateOf(false) }
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

    val url = authorizeUrl
    if (url != null) {
        // 授权页：满窗口 WebView（根修，见文件头注释）。**不得**放进下方带 insets padding 的
        // Box——父级 padding 收缩约束会使 WebView 窄于窗口，视口单位 vh 恒 0 → 白屏。
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                OAuthWebViewHost(
                    context = context,
                    authorizeUrl = url,
                    diagnosticsEnabled = debugEnabled,
                    onCancel = {
                        authorizeUrl = null
                        onClose()
                    },
                    onCallbackUrl = { callbackUrl -> finishWithCallback(callbackUrl) },
                )
            },
            onRelease = { it.release() },
        )
        return
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // 2026-11-24 登录页简化 + 配色对齐印系（用户裁决：①「登录 z.ai 是不是也应该改成红色，
        // 和 logo 颜色更匹配，登录页简单一些」②「点击登录 z.ai 后还有个页面选择，交互繁琐，
        // 不能提供一个切换的箭头么」）：
        //   品牌页形态（主印 + 名称居中）→ 一句说明 → **一个**朱磦主按钮 → 暂不登录。
        //   主按钮右侧的下拉箭头就地切换登录平台（Z.ai / BigModel），不再先跳一页选平台：
        //   点按钮主体 = 用当前平台直接开始授权；点箭头 = 展开两项菜单换平台。
        //   配色取 seal（印系品牌位专用色）与主印成对，整页收敛到纸 / 墨 / 朱三色。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = BiuZSpacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            SealBrandMark(size = 84.dp)
            Spacer(Modifier.height(BiuZSpacing.md))
            Text(
                text = stringResource(R.string.app_wordmark),
                color = BiuZSeal,
                style = MaterialTheme.typography.displayLarge.copy(letterSpacing = 2.sp),
            )
            Spacer(Modifier.height(BiuZSpacing.sm))
            Text(
                text = stringResource(R.string.login_description),
                color = BiuZText3,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(BiuZSpacing.xxl))

            LoginProviderButton(
                provider = provider,
                enabled = !submitting,
                onPick = { picked ->
                    provider = picked
                    menuExpanded = false
                },
                onLogin = {
                    errorText = null
                    authorizeUrl = session.beginOAuth(provider.id)
                },
                menuExpanded = menuExpanded,
                onMenuExpandedChange = { menuExpanded = it },
            )

            errorText?.let { message ->
                Spacer(Modifier.height(BiuZSpacing.md))
                BiuzInlineNotice(
                    message = message,
                    kind = BiuzNoticeKind.Error,
                    tag = "01-login-error",
                )
            }

            Spacer(Modifier.height(BiuZSpacing.xl))
            Text(
                text = stringResource(R.string.login_close),
                color = BiuZText3,
                fontSize = 13.sp,
                modifier = Modifier
                    .clip(BiuZRadius.small)
                    .clickable { onClose() }
                    .padding(vertical = BiuZSpacing.md, horizontal = BiuZSpacing.lg)
                    .testTag("01-login-close"),
            )
            if (debugEnabled) {
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

/** 登录平台（界面语汇只有两项：全球 Z.ai / 中国 BigModel）。internal：纯映射，单测直测。 */
internal enum class LoginProvider(val id: OAuthProviderId, val labelRes: Int, val regionRes: Int) {
    ZAI(OAuthProviderId.ZAI, R.string.login_action_zai, R.string.login_region_global),
    BIGMODEL(OAuthProviderId.BIGMODEL, R.string.login_action_bigmodel, R.string.login_region_cn),
}

/**
 * 登录主按钮（48dp / 圆角 12）：**朱磦实底纸色字**（`seal` / `onSeal`，与主印同色成对）——
 * 本页唯一主动作。右侧分隔线 + 下拉箭头就地切换平台（[menuExpanded]），点按钮主体 = 直接用
 * 当前平台开始授权。以前是「先跳一页选平台、再点登录」两步，2026-11-24 用户裁决收成一步。
 * 平台切换项用两行（名称 + 地域），菜单项高度 ≥48dp 且为整行可点。
 */
@Composable
private fun LoginProviderButton(
    provider: LoginProvider,
    enabled: Boolean,
    onPick: (LoginProvider) -> Unit,
    onLogin: () -> Unit,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
) {
    val container = if (enabled) BiuZSeal else MaterialTheme.colorScheme.surfaceVariant
    val content = if (enabled) BiuZOnSeal else BiuZText3
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(container, BiuZRadius.medium)
                .clip(BiuZRadius.medium)
                .clickable(enabled = enabled, onClick = onLogin)
                .testTag("01-btn-zai"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(BiuZSpacing.lg))
            Text(
                text = stringResource(provider.labelRes),
                color = content,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(provider.regionRes),
                color = content.copy(alpha = BiuZAlpha.dense),
                fontSize = 12.sp,
            )
            // 平台切换箭头（独立热区 44dp，不触发登录）：分隔线在纸色字上取 30% 透明
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(44.dp)
                    .clickable(enabled = enabled) { onMenuExpandedChange(true) }
                    .testTag("01-btn-provider-switch"),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(vertical = BiuZSpacing.md)
                        .width(1.dp)
                        .background(content.copy(alpha = BiuZAlpha.subtle)),
                )
                Spacer(Modifier.width(BiuZSpacing.xs))
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(BiuZSpacing.xs))
            }
            Spacer(Modifier.width(BiuZSpacing.sm))
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { onMenuExpandedChange(false) },
        ) {
            LoginProvider.entries.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(option.labelRes),
                                fontSize = 14.sp,
                                color = if (option == provider) BiuZSeal else BiuZText,
                                fontWeight = if (option == provider) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            Spacer(Modifier.width(BiuZSpacing.sm))
                            Text(
                                text = stringResource(option.regionRes),
                                fontSize = 12.sp,
                                color = BiuZText3,
                            )
                            if (option == provider) {
                                Spacer(Modifier.weight(1f))
                                Text("✓", fontSize = 13.sp, color = BiuZSeal)
                            }
                        }
                    },
                    onClick = { onPick(option) },
                    modifier = Modifier.testTag("01-option-${option.name.lowercase()}"),
                )
            }
        }
    }
}

/**
 * 授权页宿主：**满窗口** WebView + 原生顶栏浮层（取消 / 加载失败 / debug 诊断）。
 *
 * 为什么顶栏是原生 View 而不是 Compose：AndroidView 的子 View 恒绘制在 Compose 内容
 * 之上（AndroidComposeView 内 View 层级高于 Compose 绘制层），Compose 浮层会被 WebView
 * 盖住；同时 WebView 必须独占整窗（见 [LoginScreen] 文件头，视口单位缺陷）。
 */
private class OAuthWebViewHost(
    context: Context,
    private val authorizeUrl: String,
    private val diagnosticsEnabled: Boolean,
    private val onCancel: () -> Unit,
    private val onCallbackUrl: (String) -> Unit,
) : FrameLayout(context) {

    private var webView: WebView? = null
    private val topBar: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP)
    }
    private val cancelView: TextView = TextView(context).apply {
        text = context.getString(R.string.login_cancel)
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.parseColor("#99000000"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        isClickable = true
        contentDescription = "01-oauth-cancel"
        setOnClickListener { onCancel() }
    }
    private val errorRow: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Color.parseColor("#E6000000"))
        visibility = GONE
    }
    private val errorView: TextView = TextView(context).apply {
        setTextColor(Color.parseColor("#FF8A80"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(dp(12), dp(8), dp(8), dp(8))
    }
    private val reloadView: TextView = TextView(context).apply {
        text = context.getString(R.string.login_reload)
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        isClickable = true
        contentDescription = "01-oauth-reload"
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setOnClickListener {
            hideError()
            webView?.reload()
        }
    }
    private val diagView: TextView = TextView(context).apply {
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.parseColor("#CC000000"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
        typeface = Typeface.MONOSPACE
        setPadding(dp(6), dp(4), dp(6), dp(4))
        maxLines = 4
        visibility = GONE
    }
    private val diagLines = ArrayDeque<String>()

    /** 新建 WebView 并挂到宿主最底层（顶栏浮层之上保持可见）。 */
    private fun attachWebView() {
        val web = WebView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // 授权链路依赖 cookie（authorize 与回调同浏览器会话）
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = client
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
                    diag("console: ${msg?.message()?.take(150)}")
                    return true
                }
            }
            loadUrl(authorizeUrl)
        }
        addView(web, 0)
        webView = web
        if (diagnosticsEnabled) {
            runCatching {
                WebView.getCurrentWebViewPackage()?.let { pkg ->
                    diag("wv: ${pkg.packageName.take(40)} ${pkg.versionName}")
                }
            }
        }
    }

    private val client = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val target = request?.url?.toString() ?: return false
            diag("nav→ ${target.take(110)}")
            if (isOAuthCallback(target)) {
                onCallbackUrl(target)
                return true
            }
            return false
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            diag("start: ${url?.take(110) ?: "null"}")
            hideError()
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            diag("finished: ${url?.take(110) ?: "null"}")
            // debug 诊断：DOM 挂载 + 视口单位实测（验收后随诊断面板一并清理）
            view ?: return
            view.evaluateJavascript(
                "(function(){try{var d=document.createElement('div');d.style.cssText='position:fixed;top:0;left:0;width:10px';d.style.height='100vh';document.body.appendChild(d);" +
                    "var vh=d.getBoundingClientRect().height|0;d.style.height='100%';var pct=d.getBoundingClientRect().height|0;d.remove();" +
                    "return JSON.stringify({vh:vh,pct:pct|0,ih:window.innerHeight,appLen:document.getElementById('app')?document.getElementById('app').innerHTML.length:-1})}catch(e){return 'probe-err:'+e}})()",
            ) { result ->
                diag("page: ${result?.take(150)}")
                // 页面落地后 10s 自动收起诊断面板（不遮挡登录表单；错误行走 errorRow 常驻不受影响）
                postDelayed({ diagView.visibility = GONE }, 10_000L)
            }
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?,
        ) {
            if (request?.isForMainFrame != true) {
                diag("res err: ${error?.description ?: "?"}")
                return
            }
            diag("MAIN ERR: ${error?.errorCode ?: "?"} ${error?.description ?: "?"}")
            showError("无法打开授权页（${error?.description ?: "网络错误"}）")
        }

        override fun onReceivedHttpError(
            view: WebView?,
            request: WebResourceRequest?,
            response: WebResourceResponse?,
        ) {
            if (request?.isForMainFrame != true) return
            diag("MAIN HTTP ${response?.statusCode ?: "?"}")
            showError("授权页返回 HTTP ${response?.statusCode ?: "?"}")
        }

        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            // 渲染进程崩溃：WebView 会永久白屏，销毁后重建（返回 true 自己接管）
            diag("RENDER GONE: ${detail?.didCrash()}")
            showError("授权页渲染进程异常退出（${detail?.didCrash() ?: "?"}），请点击重载")
            view?.let {
                removeView(it)
                it.destroy()
            }
            webView = null
            post { attachWebView() }
            return true
        }
    }

    // 注意：`client` 必须在 init 之前**声明**（Kotlin 按声明顺序初始化属性）——
    // attachWebView() 在 init 中取用 client，声明在 init 之后会拿到未初始化的 null。
    init {
        // 顶栏内部留出状态栏高度（padding 打在浮层自身，不影响 WebView 尺寸）
        topBar.setPadding(0, statusBarHeight(context), 0, 0)
        errorRow.addView(
            errorView,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        errorRow.addView(reloadView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        topBar.addView(cancelView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        topBar.addView(errorRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        if (diagnosticsEnabled) {
            topBar.addView(diagView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        addView(topBar)
        attachWebView()
    }

    private fun showError(message: String) {
        errorView.text = message
        errorRow.visibility = VISIBLE
    }

    private fun hideError() {
        errorRow.visibility = GONE
    }

    private fun diag(line: String) {
        if (!diagnosticsEnabled) return
        if (diagLines.size >= 12) diagLines.removeFirst()
        diagLines.addLast(line.take(150))
        diagView.text = diagLines.joinToString("\n")
        diagView.visibility = VISIBLE
    }

    /** 释放（授权流程结束/离开组合时调用）：destroy 后 WebView 不可复用。 */
    fun release() {
        webView?.let {
            removeView(it)
            it.destroy()
        }
        webView = null
    }
}

private fun dp(value: Int): Int = (value * android.content.res.Resources.getSystem().displayMetrics.density).toInt()

/** 状态栏高度：根窗口 insets 优先，取不到时回退系统资源（浮层自身 padding，不影响 WebView 尺寸）。 */
private fun statusBarHeight(context: Context): Int {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val insets = (context as? android.app.Activity)?.window?.decorView?.rootWindowInsets
        if (insets != null) return insets.getInsets(android.view.WindowInsets.Type.statusBars()).top
    }
    val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
    return if (resId > 0) context.resources.getDimensionPixelSize(resId) else dp(24)
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
