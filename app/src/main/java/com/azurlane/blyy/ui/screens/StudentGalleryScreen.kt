package com.azurlane.blyy.ui.screens

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

// 预编译正则常量 — 用于清理下载文件名中的非法字符，避免每次调用都重新解析 Regex
private val DOWNLOAD_NAME_SANITIZE_REGEX = Regex("[^\\w\\u4e00-\\u9fa5-]")

// AnimatedContent tab 切换动画规格 — 提到顶级避免每次重组都新建 4 个 tween 实例
// fadeIn/fadeOut 需要 FiniteAnimationSpec<Float>，slideIntoContainer/slideOutOfContainer 需要 FiniteAnimationSpec<IntOffset>
private val TabFadeInSpec = tween<Float>(AppAnimation.Duration.Normal)
private val TabFadeOutSpec = tween<Float>(AppAnimation.Duration.Fast)
private val TabSlideInSpec = tween<androidx.compose.ui.unit.IntOffset>(AppAnimation.Duration.Normal)
private val TabSlideOutSpec = tween<androidx.compose.ui.unit.IntOffset>(AppAnimation.Duration.Fast)

/** 缩略图降采样目标边界（px）：展示区约 120dp，512px 在 4x 密度屏也足够清晰 */
private const val VIDEO_THUMB_MAX_DIMEN = 512

/**
 * 视频缩略图内存缓存 — 缓存 MediaMetadataRetriever 提取的视频首帧（已降采样）
 *
 * 使用 LruCache 限容（约 1/8 最大堆内存）：此前是无界 ConcurrentHashMap 且缓存
 * 原始分辨率首帧（720p 单帧 3-8MB），浏览几十个视频即可累积数百 MB 堆内存引发 OOM。
 * value 为 [ThumbnailState]（Success/Failed），Failed 也占位计入缓存以避免重复尝试。
 */
private val videoThumbnailCache = object : LruCache<String, ThumbnailState>(
    (Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtLeast(4 * 1024 * 1024)
) {
    override fun sizeOf(key: String, value: ThumbnailState): Int =
        (value as? ThumbnailState.Success)?.bitmap?.byteCount ?: 1
}

/**
 * 异步加载视频缩略图（首帧）
 *
 * 当 gamekee 视频无 poster/封面图时，使用 [MediaMetadataRetriever] 从视频流中
 * 提取首帧作为封面。结果会缓存到 [videoThumbnailCache] 避免重复提取。
 *
 * @param videoUrl 视频 URL
 * @return 首帧 Bitmap，提取失败返回 null
 */
private suspend fun loadVideoThumbnail(videoUrl: String): Bitmap? {
    (videoThumbnailCache.get(videoUrl) as? ThumbnailState.Success)?.let { return it.bitmap }
    return withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            // gamekee CDN 需要Referer头，否则可能返回403
            val headers = mapOf("Referer" to "https://www.gamekee.com/")
            retriever.setDataSource(videoUrl, headers)
            // 降采样后缓存：原分辨率首帧单帧可达数 MB，缓存无界时是明确的 OOM 风险
            // getScaledFrameAtTime 为 API 27+ 接口；低版本直接走 getFrameAtTime（NoSuchMethodError 是
            // Error 而非 Exception，下方 catch 捕获不到，必须先用 SDK_INT 守卫）
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                try {
                    retriever.getScaledFrameAtTime(
                        0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        VIDEO_THUMB_MAX_DIMEN, VIDEO_THUMB_MAX_DIMEN
                    )
                } catch (e: Exception) {
                    // 部分机型/视频流不支持 getScaledFrameAtTime，回退到原接口
                    retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
            } else {
                retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
            videoThumbnailCache.put(
                videoUrl,
                if (bitmap != null) ThumbnailState.Success(bitmap) else ThumbnailState.Failed
            )
            Log.d(TAG, "Video thumbnail extracted: $videoUrl, success=${bitmap != null}")
            bitmap
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract video thumbnail: $videoUrl — ${e.message}")
            videoThumbnailCache.put(videoUrl, ThumbnailState.Failed) // 缓存失败结果，避免重复尝试
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }
}

/**
 * 视频缩略图加载状态
 */
private sealed class ThumbnailState {
    object Loading : ThumbnailState()
    data class Success(val bitmap: Bitmap) : ThumbnailState()
    object Failed : ThumbnailState()
}

/**
 * Composable 钩子：异步获取视频缩略图，自动跟随组合生命周期
 *
 * 返回 [ThumbnailState] 以区分加载中、成功、失败三种状态，
 * 使 UI 能在加载中显示进度环、失败时显示渐变占位。
 */
@Composable
private fun rememberVideoThumbnail(videoUrl: String): ThumbnailState {
    return produceState<ThumbnailState>(ThumbnailState.Loading, videoUrl) {
        // 先查缓存：命中（含 Failed 失败占位）则直接返回，避免重复网络请求
        value = videoThumbnailCache.get(videoUrl) ?: run {
            val bitmap = loadVideoThumbnail(videoUrl)
            if (bitmap != null) ThumbnailState.Success(bitmap) else ThumbnailState.Failed
        }
    }.value
}

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun StudentGalleryScreen(
    studentName: String,
    avatarUrl: String,
    state: StudentGalleryState,
    viewModel: StudentGalleryViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isDark = LocalIsDark.current
    val hapticFeedback = rememberBlyyHaptics()
    val configuration = LocalConfiguration.current

    val gallery = state.gallery
    val tabs = gallery?.tabs ?: emptyList()
    val selectedTabIndex = state.selectedTabIndex.coerceIn(0, tabs.lastIndex.coerceAtLeast(0))

    var viewerImageUrl by remember { mutableStateOf<String?>(null) }
    var playingVideo by remember { mutableStateOf<StudentGalleryVideo?>(null) }

    val isLandscape = remember(configuration) {
        configuration.screenWidthDp > configuration.screenHeightDp
    }
    val screenWidthDp = remember(configuration) { configuration.screenWidthDp.dp }

    // downloadImage 包装为 val，避免每次重组重新分配闭包
    // 预编译正则常量，避免每次调用都重新解析 Regex
    val downloadImage = remember(scope, context, studentName) {
        { url: String, description: String ->
            scope.launch {
                val safeName = description.ifBlank { url.hashCode().toString() }
                    .replace(DOWNLOAD_NAME_SANITIZE_REGEX, "_")
                    .take(40)
                val fileName = "${studentName}_${safeName}.png"
                when (val result = MediaDownloader.download(
                    context, url, fileName, "${MediaDownloader.ROOT_DIR}/StudentGallery"
                )) {
                    is MediaDownloader.Result.Success -> Toast.makeText(
                        context,
                        "已保存到 Download/${result.relativePath}/${result.displayName}",
                        Toast.LENGTH_LONG
                    ).show()
                    is MediaDownloader.Result.Failure -> {
                        Log.e(TAG, "Download failed: ${result.message}")
                        Toast.makeText(context, "下载失败: ${result.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AdaptiveScreenBackground(modifier = Modifier.fillMaxSize()) {}

        Column(modifier = Modifier.fillMaxSize()) {
            BlyyTopBar(
                title = studentName,
                subtitle = "学生画廊",
                onBackClick = onBack
            )

            when {
                state.isLoading -> {
                    Box(modifier = Modifier.fillMaxSize()) {
                        BlyySkeletonGrid(
                            minSize = 110.dp,
                            modifier = Modifier.fillMaxSize(),
                            itemCount = 12
                        )
                    }
                }

                state.error != null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(AppSpacing.Lg),
                        contentAlignment = Alignment.Center
                    ) {
                        BlyyErrorState(
                            message = state.error,
                            onRetry = onBack
                        )
                    }
                }

                gallery == null || tabs.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        BlyyEmptyState(
                            icon = Icons.Rounded.BrokenImage,
                            title = "暂无画廊数据",
                            description = "无法获取该学生的画廊资源，请稍后重试",
                            actionLabel = "返回",
                            onAction = onBack
                        )
                    }
                }

                else -> {
                    GalleryTabSwitcher(
                        tabs = tabs,
                        selectedTabIndex = selectedTabIndex,
                        onTabSelected = { index ->
                            hapticFeedback(BlyyHaptic.Tick)
                            viewModel.selectTab(index)
                        }
                    )

                    AnimatedContent(
                        targetState = selectedTabIndex,
                        modifier = Modifier.fillMaxSize(),
                        transitionSpec = {
                            (fadeIn(animationSpec = TabFadeInSpec) +
                                slideIntoContainer(
                                    AnimatedContentTransitionScope.SlideDirection.Start,
                                    animationSpec = TabSlideInSpec
                                )) togetherWith
                                (fadeOut(animationSpec = TabFadeOutSpec) +
                                    slideOutOfContainer(
                                        AnimatedContentTransitionScope.SlideDirection.Start,
                                        animationSpec = TabSlideOutSpec
                                    ))
                        },
                        label = "tabContent"
                    ) { index ->
                        val tab = tabs.getOrNull(index)
                        if (tab == null || tab.isEmpty) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                BlyyEmptyState(
                                    icon = Icons.Rounded.ImageNotSupported,
                                    title = "暂无内容",
                                    description = "「${tabs.getOrNull(index)?.name ?: ""}」分类下暂无内容"
                                )
                            }
                        } else if (tab.name == "视频") {
                            VideoTabContent(
                                tab = tab,
                                modifier = Modifier.fillMaxSize(),
                                onVideoClick = { video ->
                                    hapticFeedback(BlyyHaptic.Tick)
                                    playingVideo = video
                                }
                            )
                        } else {
                            val isEmoji = tab.name == "表情"
                            val columnCount = when {
                                isEmoji -> if (screenWidthDp > 600.dp) 6 else 4
                                isLandscape -> 3
                                screenWidthDp > 600.dp -> 3
                                else -> 2
                            }
                            ImageGridContent(
                                tab = tab,
                                columnCount = columnCount,
                                isEmoji = isEmoji,
                                onImageClick = { image ->
                                    hapticFeedback(BlyyHaptic.Tick)
                                    viewerImageUrl = image.url
                                },
                                onImageLongClick = { image ->
                                    hapticFeedback(BlyyHaptic.LongPress)
                                    downloadImage(image.url, image.description)
                                }
                            )
                        }
                    }
                }
            }
        }

        viewerImageUrl?.let { url ->
            ImageViewerOverlay(
                imageUrl = url,
                onDismiss = { viewerImageUrl = null },
                onDownload = {
                    hapticFeedback(BlyyHaptic.LongPress)
                    downloadImage(url, "fullview")
                }
            )
        }

        playingVideo?.let { video ->
            VideoPlayerOverlay(
                videoUrl = video.url,
                videoTitle = video.title,
                videoDescription = video.description,
                onDismiss = {
                    hapticFeedback(BlyyHaptic.Tick)
                    playingVideo = null
                }
            )
        }
    }
}

/**
 * 横向滚动标签切换器 — 8 个分类标签，带选中动画与计数徽章
 */
@Composable
private fun GalleryTabSwitcher(
    tabs: List<StudentGalleryTab>,
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit
) {
    val isDark = LocalIsDark.current
    val accentColor = MaterialTheme.colorScheme.primary
    val isCommandCenter = LocalUiStyle.current.isCommandCenter()
    val listState = rememberLazyListState()

    LaunchedEffect(selectedTabIndex) {
        if (tabs.isNotEmpty()) {
            listState.animateScrollToItem(selectedTabIndex)
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent
    ) {
        LazyRow(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = AppSpacing.Sm),
            contentPadding = PaddingValues(horizontal = AppSpacing.Screen.Horizontal),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
        ) {
            items(
                count = tabs.size,
                key = { index -> tabs[index].name },
                contentType = { "tab" }
            ) { index ->
                val tab = tabs[index]
                val isSelected = index == selectedTabIndex

                GalleryTabItem(
                    name = tab.name,
                    count = tab.totalCount,
                    isSelected = isSelected,
                    accentColor = accentColor,
                    isDark = isDark,
                    isCommandCenter = isCommandCenter,
                    onClick = remember(index) { { onTabSelected(index) } }
                )
            }
        }
    }
}

@Composable
private fun GalleryTabItem(
    name: String,
    count: Int,
    isSelected: Boolean,
    accentColor: Color,
    isDark: Boolean,
    isCommandCenter: Boolean,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.05f else 1f,
        animationSpec = AppAnimation.Specs.scale(),
        label = "tabScale"
    )

    val containerColor by animateColorAsState(
        targetValue = if (isSelected) {
            accentColor.copy(alpha = if (isCommandCenter) 0.25f else 0.18f)
        } else {
            if (isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.04f)
        },
        animationSpec = AppAnimation.Specs.fast(),
        label = "tabContainer"
    )

    val borderColor by animateColorAsState(
        targetValue = if (isSelected) accentColor else Color.Transparent,
        animationSpec = AppAnimation.Specs.fast(),
        label = "tabBorder"
    )

    val textColor by animateColorAsState(
        targetValue = if (isSelected) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = AppAnimation.Specs.fast(),
        label = "tabText"
    )

    val shape = if (isCommandCenter) BlyyShapes.PanelSmall else RoundedCornerShape(AppSpacing.Corner.Full)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .scale(scale)
                .clip(shape)
                .background(containerColor)
                .border(
                    width = if (isSelected) AppSpacing.Border.Normal else AppSpacing.Border.Thin,
                    color = borderColor,
                    shape = shape
                )
                .combinedClickable(onClick = onClick)
                .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Sm)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Xs)
            ) {
                Text(
                    text = name,
                    style = AppTypography.LabelLarge,
                    color = textColor,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (count > 0) {
                    Surface(
                        shape = CircleShape,
                        color = if (isSelected) accentColor.copy(alpha = 0.3f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Text(
                            text = count.toString(),
                            style = AppTypography.LabelSmallBold,
                            color = textColor,
                            modifier = Modifier.padding(horizontal = AppSpacing.Xs, vertical = AppSpacing.Xxs)
                        )
                    }
                }
            }
        }

        // 选中指示器 — 下划线动画
        val indicatorWidth by animateDpAsState(
            targetValue = if (isSelected) AppSpacing.Lg else 0.dp,
            animationSpec = AppAnimation.Specs.fast(),
            label = "indicatorWidth"
        )
        if (indicatorWidth > 0.dp) {
            Box(
                modifier = Modifier
                    .padding(top = AppSpacing.Xs)
                    .width(indicatorWidth)
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(accentColor)
            )
        }
    }
}

/**
 * 图片网格内容 — LazyVerticalGrid，自适应列数
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageGridContent(
    tab: StudentGalleryTab,
    columnCount: Int,
    isEmoji: Boolean,
    onImageClick: (StudentGalleryImage) -> Unit,
    onImageLongClick: (StudentGalleryImage) -> Unit
) {
    val isDark = LocalIsDark.current
    val isCommandCenter = LocalUiStyle.current.isCommandCenter()
    val accentColor = MaterialTheme.colorScheme.primary

    LazyVerticalGrid(
        columns = GridCells.Fixed(columnCount),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = AppSpacing.Screen.Horizontal,
            vertical = AppSpacing.Md
        ),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Gap.CardGrid),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Gap.CardGrid)
    ) {
        itemsIndexed(
            items = tab.images,
            key = { _, image -> image.url },
            contentType = { _, image -> if (image.url.endsWith(".gif")) "gif" else "image" }
        ) { index, image ->
            GalleryImageItem(
                image = image,
                isEmoji = isEmoji,
                accentColor = accentColor,
                isDark = isDark,
                isCommandCenter = isCommandCenter,
                onClick = remember(image.url) { { onImageClick(image) } },
                onLongClick = remember(image.url) { { onImageLongClick(image) } }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryImageItem(
    image: StudentGalleryImage,
    isEmoji: Boolean,
    accentColor: Color,
    isDark: Boolean,
    isCommandCenter: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) AppAnimation.Press.StandardScale else 1f,
        animationSpec = AppAnimation.Press.standard(),
        label = "imgPress"
    )

    val shape = if (isCommandCenter) BlyyShapes.Card else RoundedCornerShape(AppSpacing.Corner.Md)
    val cardBg = if (isDark) AppColors.Panel.Dark.copy(alpha = 0.6f) else AppColors.Panel.Light.copy(alpha = 0.6f)

    Column(
        modifier = Modifier
            .scale(pressScale)
            .clip(shape)
            .background(cardBg)
            .border(
                width = AppSpacing.Border.Thin,
                color = accentColor.copy(alpha = 0.15f),
                shape = shape
            )
            .combinedClickable(
                interactionSource = interactionSource,
                indication = ripple(color = accentColor.copy(alpha = 0.2f)),
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (isEmoji) Modifier.aspectRatio(1f) else Modifier.aspectRatio(0.75f))
        ) {
            val request = remember(image.url) {
                ImageRequest.Builder(context)
                    .data(image.url)
                    .crossfade(true)
                    .listener(
                        onError = { _, result ->
                            Log.e(TAG, "Failed to load image: ${image.url} — ${result.throwable.message}")
                        },
                        onSuccess = { _, _ ->
                            Log.d(TAG, "Loaded image: ${image.url}")
                        }
                    )
                    .build()
            }

            // 用 AsyncImage + onState 替代 SubcomposeAsyncImage，避免 Subcomposition 性能开销
            var imageState by remember(image.url) {
                mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty)
            }
            AsyncImage(
                model = request,
                contentDescription = image.description.ifBlank { "画廊图片" },
                modifier = Modifier.fillMaxSize(),
                contentScale = if (isEmoji) ContentScale.Fit else ContentScale.Crop,
                onState = { imageState = it }
            )
            when (imageState) {
                is AsyncImagePainter.State.Loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = accentColor.copy(alpha = 0.6f)
                        )
                    }
                }
                is AsyncImagePainter.State.Error -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.BrokenImage,
                            contentDescription = "加载失败",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
                else -> {}
            }

            // 表情类型不显示描述遮罩
            if (!isEmoji && image.description.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.65f)
                                )
                            )
                        )
                        .padding(AppSpacing.Xs)
                ) {
                    Text(
                        text = image.description,
                        style = AppTypography.LabelSmall,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * 视频标签内容 — LazyColumn 展示视频列表
 */
@Composable
private fun VideoTabContent(
    tab: StudentGalleryTab,
    modifier: Modifier = Modifier,
    onVideoClick: (StudentGalleryVideo) -> Unit
) {
    val isDark = LocalIsDark.current
    val isCommandCenter = LocalUiStyle.current.isCommandCenter()
    val accentColor = MaterialTheme.colorScheme.primary

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            horizontal = AppSpacing.Screen.Horizontal,
            vertical = AppSpacing.Md
        ),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Gap.Lg)
    ) {
        items(
            items = tab.videos,
            key = { it.url },
            contentType = { "video" }
        ) { video ->
            VideoItemCard(
                video = video,
                accentColor = accentColor,
                isDark = isDark,
                isCommandCenter = isCommandCenter,
                onClick = remember(video.url) { { onVideoClick(video) } }
            )
        }
    }
}

@Composable
private fun VideoItemCard(
    video: StudentGalleryVideo,
    accentColor: Color,
    isDark: Boolean,
    isCommandCenter: Boolean,
    onClick: () -> Unit
) {
    val shape = if (isCommandCenter) BlyyShapes.Card else RoundedCornerShape(AppSpacing.Corner.Lg)
    val cardBg = if (isDark) AppColors.Panel.Dark.copy(alpha = 0.7f) else AppColors.Panel.Light.copy(alpha = 0.7f)
    val thumbShape = if (isCommandCenter) BlyyShapes.PanelSmall else RoundedCornerShape(AppSpacing.Corner.Md)
    val context = LocalContext.current

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape),
        shape = shape,
        color = cardBg,
        shadowElevation = AppSpacing.Elevation.Sm
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick)
                .padding(AppSpacing.Md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 视频封面图 — 优先级：coverUrl > 视频首帧缩略图 > 渐变占位
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(thumbShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                accentColor.copy(alpha = 0.3f),
                                accentColor.copy(alpha = 0.1f)
                            )
                        )
                    )
                    .border(
                        width = AppSpacing.Border.Thin,
                        color = accentColor.copy(alpha = 0.4f),
                        shape = thumbShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                when {
                    // 1. 有显式封面 URL（poster 属性或关联 img）→ 直接用 Coil 加载
                    // ImageRequest 必须 remember，避免每次重组都新建请求触发 Coil 重新解码
                    video.coverUrl.isNotBlank() -> {
                        val coverRequest = remember(video.coverUrl, context) {
                            ImageRequest.Builder(context)
                                .data(video.coverUrl)
                                .crossfade(true)
                                .build()
                        }
                        AsyncImage(
                            model = coverRequest,
                            contentDescription = video.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                    // 2. 无封面 URL → 用 MediaMetadataRetriever 提取视频首帧
                    else -> {
                        val thumbState = rememberVideoThumbnail(video.url)
                        when (thumbState) {
                            is ThumbnailState.Success -> {
                                Image(
                                    bitmap = thumbState.bitmap.asImageBitmap(),
                                    contentDescription = video.title,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                            is ThumbnailState.Loading -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White.copy(alpha = 0.6f)
                                )
                            }
                            is ThumbnailState.Failed -> {
                                // 提取失败，显示视频图标占位
                                Icon(
                                    imageVector = Icons.Rounded.VideoFile,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.5f),
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                    }
                }
                // 半透明遮罩 + 播放图标（始终覆盖在封面上）
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.25f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayCircleFilled,
                        contentDescription = "播放",
                        tint = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(AppSpacing.Md))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = video.title.ifBlank { "未命名视频" },
                    style = AppTypography.TitleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (video.description.isNotBlank() && video.description != video.title) {
                    Spacer(modifier = Modifier.height(AppSpacing.Xxs))
                    Text(
                        text = video.description,
                        style = AppTypography.BodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(AppSpacing.Xxs))
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.VideoFile,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(AppSpacing.Icon.Xs)
                    )
                    Spacer(modifier = Modifier.width(AppSpacing.Xxs))
                    Text(
                        text = "视频资源",
                        style = AppTypography.LabelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }

            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(AppSpacing.Icon.Lg)
            )
        }
    }
}

