package cn.biuz.android.app.ui.screen

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.biuz.android.app.R
import cn.biuz.android.app.settings.AppSettingsStore
import cn.biuz.android.app.ui.scan.QrAnalyzer
import cn.biuz.android.app.ui.theme.BiuZAlpha
import cn.biuz.android.app.ui.theme.BiuZRadius
import cn.biuz.android.app.ui.theme.BiuZSpacing
import cn.biuz.android.app.ui.theme.BiuZText3
import cn.biuz.android.connection.AppSession
import cn.biuz.android.protocol.ConnectURLParser
import java.util.concurrent.Executors
import kotlinx.coroutines.launch

/**
 * 扫码取景器（L1-S / L1-S-D 同位，全屏模态）：CameraX PreviewView 取景 + ImageAnalysis
 * 分析帧（专用单线程）→ zxing 纯解码（[QrAnalyzer]/[QrFrameDecoder]）→ 识别即连接。
 *
 * 工程纪律对照（iOS 先例 §5-17②③ 的 Android 对应物）：
 * - **相机初始化禁主线程同步等待**：ProcessCameraProvider.getInstance 的 ListenableFuture
 *   绝不在主线程 get() 阻塞——以 addListener(mainExecutor) 回调承接；回调内 get() 时
 *   future 已完成（非阻塞），bindToLifecycle 在主线程执行是 CameraX 契约要求且非等待面
 *   （iOS AVCaptureSession.startRunning 挂死主线程教训的镜像坑位）；
 * - **预览互操作层禁触摸**：全尺寸拦截层消费全部指针事件，PreviewView 永不成为触达目标
 *   （iOS 预览层 allowsHitTesting(false) 等效；关闭钮等控件置于拦截层之上）；
 * - 相机权限拒绝态 = 中央引导卡（l1-s-card-denied）：去系统设置开启 + 改用手动/剪贴板
 *   输入逃生口（主 CTA 不死路）；识别到非连接内容 = 取景框下内联红字（l1-s-err-inline），
 *   不弹系统弹窗。
 */
@Composable
fun ScanScreen(
    session: AppSession,
    settings: AppSettingsStore,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    // 手动族入口文案 gating（与连接页同源：设置页品牌行 7 连击 || -ZCodeDevMode）
    val developerModePersisted by settings.developerMode.collectAsStateWithLifecycle()
    val developerMode = developerModePersisted || settings.developerModeStartupArgument

    // MARK: 相机权限三态（正常取景 / 请求中 / 拒绝引导卡）
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var permissionDenied by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
        permissionDenied = !granted
    }
    LaunchedEffect(Unit) {
        if (!permissionGranted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // MARK: 扫码态
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var boundProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var inlineError by remember { mutableStateOf<String?>(null) }
    var connectStarted by remember { mutableStateOf(false) }
    val analyzerExecutor = remember {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "biuz-qr-analyzer").apply { isDaemon = true }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            analyzerExecutor.shutdown()
            // 解绑走已绑实例引用（不碰未完成的 ListenableFuture——禁主线程同步等待）
            runCatching { boundProvider?.unbindAll() }
            boundProvider = null
        }
    }

    // 识别回调（分析线程到达 → 主线程处理；连接动作经 ConnectFlow 三路共用）
    fun handleDecoded(raw: String) {
        scope.launch {
            if (connectStarted) return@launch
            if (ConnectURLParser.extractLink(raw) == null) {
                inlineError = context.getString(R.string.scan_unrecognized)
                return@launch
            }
            connectStarted = true
            val error = ConnectFlow.connectFromText(context, session, raw)
            if (error != null) {
                inlineError = error
                connectStarted = false
            } else {
                onClose()
            }
        }
    }

    // MARK: 相机会话装配（future 监听回调承接，禁主线程阻塞等待）
    LaunchedEffect(permissionGranted, previewView) {
        val pv = previewView
        if (!permissionGranted || pv == null) return@LaunchedEffect
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                val provider = try {
                    future.get() // 回调时序 = future 已完成，此 get 非阻塞
                } catch (e: Exception) {
                    cameraError = context.getString(R.string.scan_cam_init_fail_fmt, e.message ?: e.javaClass.simpleName)
                    return@addListener
                }
                try {
                    provider.unbindAll()
                    val preview = Preview.Builder().build()
                    preview.setSurfaceProvider(pv.surfaceProvider)
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(analyzerExecutor, QrAnalyzer(onDecoded = ::handleDecoded))
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                    boundProvider = provider
                } catch (e: Exception) {
                    cameraError = context.getString(R.string.scan_cam_unavailable_fmt, e.message ?: e.javaClass.simpleName)
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    // MARK: 取景框扫描线
    val scanLineTransition = rememberInfiniteTransition(label = "scanLine")
    val scanLineY by scanLineTransition.animateFloat(
        initialValue = 0.06f,
        targetValue = 0.94f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "scanLineY",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // MARK: 相机预览（互操作层；触摸被下方拦截层全量消费，View 永不收到事件）
        if (permissionGranted && cameraError == null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        // 互操作层禁触摸的第二道闸：View 自身不可点击/聚焦
                        isClickable = false
                        isFocusable = false
                    }.also { previewView = it }
                },
            )
            // 预览互操作层禁触摸（allowsHitTesting(false) 等效）：全尺寸拦截层消费全部
            // 指针事件（置于 AndroidView 之上、控件之下——控件仍在最前，正常可点）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .androidNoTouchInterceptor(),
            )
        }

        when {
            !permissionGranted && !permissionDenied -> {
                // 系统权限弹窗在场（首个 ON_RESUME 后弹）；App 层只留背景提示
                Text(
                    text = stringResource(R.string.scan_requesting),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            permissionDenied -> {
                CameraDeniedCard(
                    developerMode = developerMode,
                    onOpenSystemSettings = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(Uri.parse("package:${context.packageName}")),
                        )
                    },
                    onRetryPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    onFallback = onClose,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            cameraError != null -> {
                // 无相机设备/启动失败：如实呈现 + 逃生口（uses-feature 非必需的同口径降级）
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(BiuZSpacing.xl),
                ) {
                    Text(
                        text = cameraError.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(BiuZSpacing.md))
                    OutlinedButton(onClick = onClose, modifier = Modifier.height(44.dp)) {
                        Text(stringResource(R.string.scan_back_fallback))
                    }
                }
            }

            else -> {
                // MARK: 取景框（四角括号 + 扫描线 + 目标格式 mono 提示）与内联红字
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    Box(
                        modifier = Modifier
                            .width(260.dp)
                            .aspectRatio(1f),
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val corner = size.minDimension * 0.16f
                            val bracketWidth = 3.dp.toPx()
                            val accent = Color(0xFF32F08C)
                            fun cornerPath(x: Float, y: Float, dx: Float, dy: Float) {
                                drawLine(
                                    accent,
                                    Offset(x, y),
                                    Offset(x + dx * corner, y),
                                    strokeWidth = bracketWidth,
                                    cap = StrokeCap.Round,
                                )
                                drawLine(
                                    accent,
                                    Offset(x, y),
                                    Offset(x, y + dy * corner),
                                    strokeWidth = bracketWidth,
                                    cap = StrokeCap.Round,
                                )
                            }
                            cornerPath(0f, 0f, 1f, 1f)
                            cornerPath(size.width, 0f, -1f, 1f)
                            cornerPath(0f, size.height, 1f, -1f)
                            cornerPath(size.width, size.height, -1f, -1f)
                            // 扫描线
                            val y = size.height * scanLineY
                            drawLine(
                                accent.copy(alpha = BiuZAlpha.nearSolid),
                                Offset(size.width * 0.06f, y),
                                Offset(size.width * 0.94f, y),
                                strokeWidth = 1.5.dp.toPx(),
                            )
                        }
                    }
                    Spacer(Modifier.height(BiuZSpacing.md))
                    Text(
                        text = stringResource(R.string.scan_viewfinder_hint),
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                        color = Color.White.copy(alpha = BiuZAlpha.nearSolid),
                        textAlign = TextAlign.Center,
                    )
                    inlineError?.let { error ->
                        Spacer(Modifier.height(BiuZSpacing.sm))
                        Text(
                            // l1-s-err-inline：取景框下内联红字（不弹系统弹窗）
                            text = error,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFFFF5D5D),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = BiuZSpacing.lg),
                        )
                    }
                }

                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(BiuZSpacing.sm)
                        .size(44.dp),
                ) {
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.scan_close_cd),
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

/**
 * 预览互操作层禁触摸拦截（Modifier 承载）：全尺寸消费全部指针事件，
 * 保证 PreviewView 永不成为事件目标（拦截层置于 AndroidView 之上、控件之下）。
 */
private fun Modifier.androidNoTouchInterceptor(): Modifier = this.then(
    Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent().changes.forEach { it.consume() }
            }
        }
    },
)

@Composable
private fun CameraDeniedCard(
    developerMode: Boolean,
    onOpenSystemSettings: () -> Unit,
    onRetryPermission: () -> Unit,
    onFallback: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .padding(BiuZSpacing.lg),
    ) {
        // l1-s-card-denied：相机权限拒绝引导卡（主 CTA 不死路）
        Text(
            text = stringResource(R.string.scan_denied_title),
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
        )
        Spacer(Modifier.height(BiuZSpacing.sm))
        Text(
            text = stringResource(
                R.string.scan_denied_body_fmt,
                stringResource(
                    if (developerMode) R.string.scan_denied_dev_suffix
                    else R.string.scan_denied_default_suffix,
                ),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = BiuZAlpha.nearSolid),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(BiuZSpacing.lg))
        Button(
            onClick = onOpenSystemSettings,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            shape = BiuZRadius.medium,
        ) {
            Text(stringResource(R.string.scan_open_settings))
        }
        Spacer(Modifier.height(BiuZSpacing.xs))
        OutlinedButton(
            onClick = onFallback,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            shape = BiuZRadius.medium,
        ) {
            // l1-s-btn-manual 逃生口（两态共用语义）：回连接页（剪贴板横幅/开发者模式手输）
            Text(stringResource(R.string.scan_fallback_manual))
        }
        Spacer(Modifier.height(BiuZSpacing.xs))
        androidx.compose.material3.TextButton(onClick = onRetryPermission) {
            Text(stringResource(R.string.scan_retry_permission), color = Color.White.copy(alpha = BiuZAlpha.dense))
        }
    }
}
