package com.azurlane.blyy.ui.screens

/*
 * 学生图集的全屏查看器 — 自 StudentGalleryScreen.kt 机械拆分而来，纯移动零逻辑改动。
 * ImageViewerOverlay（缩放/下载）/ VideoPlayerOverlay（视频播放）供主屏调用（internal）。
 */
import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import com.azurlane.blyy.data.model.StudentGalleryImage
import com.azurlane.blyy.data.model.StudentGalleryTab
import com.azurlane.blyy.data.model.StudentGalleryVideo
import com.azurlane.blyy.ui.components.AdaptiveScreenBackground
import com.azurlane.blyy.ui.components.BlyyEmptyState
import com.azurlane.blyy.ui.components.BlyyErrorState
import com.azurlane.blyy.ui.components.BlyySkeletonGrid
import com.azurlane.blyy.ui.components.BlyyTopBar
import com.azurlane.blyy.ui.components.ZoomableImage
import com.azurlane.blyy.ui.theme.*
import com.azurlane.blyy.viewmodel.StudentGalleryState
import com.azurlane.blyy.viewmodel.StudentGalleryViewModel
import com.azurlane.blyy.ui.components.BlyyHaptic
import com.azurlane.blyy.ui.components.rememberBlyyHaptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.azurlane.blyy.util.MediaDownloader
import kotlinx.coroutines.withContext
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import java.util.concurrent.ConcurrentHashMap
private const val TAG = "StudentGalleryScreen"

/**
 * 全屏图片查看器 — 支持缩放、关闭、下载
 */
@Composable
internal fun ImageViewerOverlay(
    imageUrl: String,
    onDismiss: () -> Unit,
    onDownload: () -> Unit
) {
    val context = LocalContext.current
    val isDark = LocalIsDark.current
    val glassSurface = if (isDark) AppColors.GlassSurfaceDark else AppColors.GlassSurfaceLight

    // 图片加载状态追踪
    var isImageLoading by remember { mutableStateOf(true) }
    var hasImageError by remember { mutableStateOf(false) }

    val request = remember(imageUrl) {
        ImageRequest.Builder(context)
            .data(imageUrl)
            .crossfade(true)
            .listener(
                onStart = { isImageLoading = true; hasImageError = false },
                onSuccess = { _, _ -> isImageLoading = false; hasImageError = false },
                onError = { _, _ -> isImageLoading = false; hasImageError = true }
            )
            .build()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.95f))
    ) {
        ZoomableImage(
            model = request,
            contentDescription = "画廊图片预览",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
            error = rememberVectorPainter(Icons.Rounded.BrokenImage),
            placeholder = rememberVectorPainter(Icons.Rounded.Image)
        )

        // 加载中指示器
        if (isImageLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = Color.White.copy(alpha = 0.8f),
                    strokeWidth = 3.dp
                )
            }
        }

        // 加载失败提示
        if (hasImageError) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Rounded.BrokenImage,
                        contentDescription = "加载失败",
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(AppSpacing.Sm))
                    Text(
                        text = "图片加载失败",
                        style = AppTypography.LabelMedium,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }
            }
        }

        // 顶部操作栏 — 关闭 + 下载
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Md),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                onClick = onDismiss,
                shape = CircleShape,
                color = glassSurface.copy(alpha = 0.9f),
                tonalElevation = AppSpacing.Elevation.Sm,
                modifier = Modifier.size(44.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "关闭",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Surface(
                onClick = onDownload,
                shape = CircleShape,
                color = glassSurface.copy(alpha = 0.9f),
                tonalElevation = AppSpacing.Elevation.Sm,
                modifier = Modifier.size(44.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = "下载",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // 底部提示
        Text(
            text = "双击缩放 · 双指缩放 · 长按下载",
            style = AppTypography.LabelSmall,
            color = Color.White.copy(alpha = 0.6f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = AppSpacing.Lg)
        )
    }
}

/**
 * 全屏视频播放器 — 使用 ExoPlayer + ResolvingDataSource 突破 gamekee 防盗链
 *
 * - 自动根据视频 URL 域名注入 Referer（gamekee / biligame）
 * - 支持播放/暂停/拖动进度/快进快退，PlayerView 提供原生控件
 * - 播放器在 Overlay 关闭时自动释放，避免内存泄漏
 * - 加载中显示进度环，播放失败显示错误提示
 */
@UnstableApi
@Composable
internal fun VideoPlayerOverlay(
    videoUrl: String,
    videoTitle: String,
    videoDescription: String = "",
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val isDark = LocalIsDark.current
    val glassSurface = if (isDark) AppColors.GlassSurfaceDark else AppColors.GlassSurfaceLight
    val hapticFeedback = rememberBlyyHaptics()
    val configuration = LocalConfiguration.current

    // 横屏时使用更贴合的宽高比，竖屏时 16:9 居中
    // forceLandscape 为 true 时即时切换布局，无需等待旋转动画完成
    var forceLandscape by remember { mutableStateOf(false) }
    val isLandscape = forceLandscape || configuration.screenWidthDp > configuration.screenHeightDp

    // 播放器状态
    var isBuffering by remember { mutableStateOf(true) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var isControllerVisible by remember { mutableStateOf(true) }

    // 进入时锁定竖屏并保存原始方向，退出时恢复（覆盖所有退出路径）
    DisposableEffect(Unit) {
        val originalOrientation =
            activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose {
            activity?.requestedOrientation = originalOrientation
        }
    }

    // 响应横屏切换
    LaunchedEffect(forceLandscape) {
        activity?.requestedOrientation = if (forceLandscape)
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    // 创建 ExoPlayer — 使用 ResolvingDataSource 按域名注入 Referer
    // B5 修复：remember 中只做构建（纯构造无副作用），setMediaItem/prepare/play
    // 移入下方 DisposableEffect，避免副作用在组合期执行且无法被清理
    val exoPlayer = remember(videoUrl) {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .setConnectTimeoutMs(20000)
            .setReadTimeoutMs(20000)
            .setAllowCrossProtocolRedirects(true)

        val resolvingFactory = ResolvingDataSource.Factory(httpDataSourceFactory) { dataSpec ->
            val host = dataSpec.uri.host ?: ""
            val referer = when {
                host.contains("gamekee") -> "https://www.gamekee.com/"
                host.contains("biligame") || host.contains("hdslb") -> "https://wiki.biligame.com/"
                else -> "https://www.google.com/"
            }
            dataSpec.withAdditionalHeaders(mapOf("Referer" to referer))
        }

        val dataSourceFactory = DefaultDataSource.Factory(context, resolvingFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        // 音频属性 — 标记为电影类型，让系统正确路由音频并与其他应用协调焦点
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        ExoPlayer.Builder(context)
            .setAudioAttributes(audioAttributes, true)  // true = 自动管理音频焦点
            .setHandleAudioBecomingNoisy(true)           // 耳机拔出时自动暂停
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
    }

    // 生命周期管理：监听播放状态 + 退后台自动暂停/回前台续播（B5 修复）+ 释放播放器
    // B5 修复：LifecycleOwner 须在组合期获取（DisposableEffect 体不是 @Composable 上下文）
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) {
                    playbackError = null
                }
                if (state == Player.STATE_ENDED) {
                    exoPlayer.playWhenReady = false
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Video playback error: ${error.message}", error)
                isBuffering = false
                playbackError = when (error.errorCodeName) {
                    "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED",
                    "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT" -> "网络连接失败，请检查网络后重试"
                    "ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE",
                    "ERROR_CODE_IO_BAD_HTTP_STATUS" -> "视频资源无法访问（可能被防盗链拦截）"
                    "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED",
                    "ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED" -> "视频格式不受支持"
                    else -> error.message ?: "视频播放失败"
                }
            }
        }
        exoPlayer.addListener(listener)

        // B5 修复：退到后台时暂停视频（原先继续出声），回到前台按离开时的播放状态续播
        var wasPlayingWhenStopped = false
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    wasPlayingWhenStopped = exoPlayer.isPlaying
                    exoPlayer.pause()
                }
                Lifecycle.Event.ON_START -> {
                    if (wasPlayingWhenStopped) exoPlayer.play()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)

        // 首次挂载（或 videoUrl 变化重建播放器）时启动播放 — 原先写在 remember 内的副作用移到此处
        exoPlayer.setMediaItem(MediaItem.fromUri(videoUrl))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            exoPlayer.removeListener(listener)
            exoPlayer.release()
            Log.d(TAG, "Video player released")
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.97f))
    ) {
        // 视频播放区域 — 横屏全屏，竖屏 16:9 居中
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = true
                    // 禁用内置触摸隐藏，避免与下方点击监听器冲突导致控件闪烁
                    // （内置 onHideOnTouch 在 ACTION_DOWN 隐藏，performClick 又显示 = 闪回）
                    setControllerHideOnTouch(false)
                    // 5 秒无操作自动隐藏控件
                    setControllerShowTimeoutMs(5000)
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    setShowFastForwardButton(true)
                    setShowRewindButton(true)
                    setShowSubtitleButton(false)
                    resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { visibility ->
                            isControllerVisible = visibility == View.VISIBLE
                        }
                    )
                    // 手势处理：单击切换控件显隐，双击暂停/播放
                    // 使用 GestureDetector + setOnTouchListener 替代 setOnClickListener
                    // （PlayerView 重写 onTouchEvent 未调用 super，performClick 不触发）
                    val gestureDetector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
                        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                            if (isControllerVisible) hideController() else showController()
                            return true
                        }

                        override fun onDoubleTap(e: MotionEvent): Boolean {
                            if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                            showController()
                            return true
                        }
                    })
                    setOnTouchListener { _, event ->
                        gestureDetector.onTouchEvent(event)
                        true
                    }
                }
            },
            modifier = Modifier
                .then(
                    if (isLandscape) {
                        Modifier.fillMaxSize()
                    } else {
                        Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                    }
                )
                .align(Alignment.Center)
        )

        // 缓冲指示器
        if (isBuffering && playbackError == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = Color.White.copy(alpha = 0.9f),
                    strokeWidth = 3.dp
                )
            }
        }

        // 错误提示
        if (playbackError != null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = AppSpacing.Lg)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = "播放失败",
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(AppSpacing.Sm))
                    Text(
                        text = playbackError ?: "视频加载失败",
                        style = AppTypography.BodyMedium,
                        color = Color.White.copy(alpha = 0.85f),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(AppSpacing.Md))
                    Surface(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(AppSpacing.Corner.Md),
                        color = glassSurface.copy(alpha = 0.95f)
                    ) {
                        Text(
                            text = "关闭",
                            style = AppTypography.LabelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Sm)
                        )
                    }
                }
            }
        }

        // 顶部操作栏 — 关闭按钮 + 标题 + 横屏切换（控制器可见时显示）
        if (isControllerVisible || playbackError != null) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.6f),
                                Color.Transparent
                            )
                        )
                    )
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    onClick = {
                        hapticFeedback(BlyyHaptic.Tick)
                        onDismiss()
                    },
                    shape = CircleShape,
                    color = glassSurface.copy(alpha = 0.9f),
                    tonalElevation = AppSpacing.Elevation.Sm,
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "关闭",
                            tint = Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.width(AppSpacing.Md))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = videoTitle.ifBlank { "视频播放" },
                        style = AppTypography.TitleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (videoDescription.isNotBlank() && videoDescription != videoTitle) {
                        Text(
                            text = videoDescription,
                            style = AppTypography.BodySmall,
                            color = Color.White.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(AppSpacing.Md))

                Surface(
                    onClick = {
                        hapticFeedback(BlyyHaptic.Tick)
                        forceLandscape = !forceLandscape
                    },
                    shape = CircleShape,
                    color = glassSurface.copy(alpha = 0.9f),
                    tonalElevation = AppSpacing.Elevation.Sm,
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (forceLandscape) Icons.Rounded.StayCurrentPortrait
                                          else Icons.Rounded.ScreenRotation,
                            contentDescription = if (forceLandscape) "竖屏" else "横屏",
                            tint = Color.White
                        )
                    }
                }
            }
        }

        // 底部提示
        if (!isControllerVisible) {
            Text(
                text = "点击屏幕显示控件",
                style = AppTypography.LabelSmall,
                color = Color.White.copy(alpha = 0.5f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = AppSpacing.Md)
            )
        }
    }
}
