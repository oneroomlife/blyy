package com.azurlane.blyy.ui.screens

import android.app.Activity
import android.content.pm.ActivityInfo
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
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
import com.azurlane.blyy.ui.components.BlyyTopBar
import com.azurlane.blyy.ui.components.ZoomableImage
import com.azurlane.blyy.ui.theme.*
import com.azurlane.blyy.viewmodel.StudentGalleryState
import com.azurlane.blyy.viewmodel.StudentGalleryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "StudentGalleryScreen"

/**
 * 视频缩略图内存缓存 — 缓存 MediaMetadataRetriever 提取的视频首帧
 *
 * key = 视频 URL，value = Bitmap（可能为 null 表示提取失败，避免重复尝试）
 */
private val videoThumbnailCache = ConcurrentHashMap<String, Bitmap?>()

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
    videoThumbnailCache[videoUrl]?.let { return it }
    return withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            // gamekee CDN 需要Referer头，否则可能返回403
            val headers = mapOf("Referer" to "https://www.gamekee.com/")
            retriever.setDataSource(videoUrl, headers)
            val bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            videoThumbnailCache[videoUrl] = bitmap
            Log.d(TAG, "Video thumbnail extracted: $videoUrl, success=${bitmap != null}")
            bitmap
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract video thumbnail: $videoUrl — ${e.message}")
            videoThumbnailCache[videoUrl] = null // 缓存失败结果，避免重复尝试
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
        // 先查缓存：命中（含 null 失败结果）则直接返回，避免重复网络请求
        val cached = videoThumbnailCache[videoUrl]
        value = when {
            cached != null -> ThumbnailState.Success(cached)
            videoThumbnailCache.containsKey(videoUrl) -> ThumbnailState.Failed
            else -> {
                val bitmap = loadVideoThumbnail(videoUrl)
                if (bitmap != null) ThumbnailState.Success(bitmap) else ThumbnailState.Failed
            }
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
    val hapticFeedback = LocalHapticFeedback.current
    val configuration = LocalConfiguration.current

    val gallery = state.gallery
    val tabs = gallery?.tabs ?: emptyList()
    val selectedTabIndex = state.selectedTabIndex.coerceIn(0, tabs.lastIndex.coerceAtLeast(0))

    var viewerImageUrl by remember { mutableStateOf<String?>(null) }
    var playingVideo by remember { mutableStateOf<StudentGalleryVideo?>(null) }

    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
    val screenWidthDp = configuration.screenWidthDp.dp

    fun downloadImage(url: String, description: String) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val imageUrl = URL(url)
                    val safeName = description.ifBlank { url.hashCode().toString() }
                        .replace(Regex("[^\\w\\u4e00-\\u9fa5-]"), "_")
                        .take(40)
                    val fileName = "${studentName}_${safeName}.png"
                    val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS
                    )
                    val file = File(downloadsDir, "BLYY/StudentGallery/$fileName")
                    file.parentFile?.mkdirs()

                    imageUrl.openStream().use { input ->
                        FileOutputStream(file).use { output ->
                            input.copyTo(output)
                        }
                    }

                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            "已保存到 Download/BLYY/StudentGallery/$fileName",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "下载失败: ${e.message}", Toast.LENGTH_SHORT).show()
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
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 3.dp
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
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            viewModel.selectTab(index)
                        }
                    )

                    AnimatedContent(
                        targetState = selectedTabIndex,
                        modifier = Modifier.fillMaxSize(),
                        transitionSpec = {
                            (fadeIn(animationSpec = tween(AppAnimation.Duration.Normal)) +
                                slideIntoContainer(
                                    AnimatedContentTransitionScope.SlideDirection.Start,
                                    animationSpec = tween(AppAnimation.Duration.Normal)
                                )) togetherWith
                                (fadeOut(animationSpec = tween(AppAnimation.Duration.Fast)) +
                                    slideOutOfContainer(
                                        AnimatedContentTransitionScope.SlideDirection.Start,
                                        animationSpec = tween(AppAnimation.Duration.Fast)
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
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    viewerImageUrl = image.url
                                },
                                onImageLongClick = { image ->
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
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
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
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
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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
            items(tabs.size) { index ->
                val tab = tabs[index]
                val isSelected = index == selectedTabIndex

                GalleryTabItem(
                    name = tab.name,
                    count = tab.totalCount,
                    isSelected = isSelected,
                    accentColor = accentColor,
                    isDark = isDark,
                    isCommandCenter = isCommandCenter,
                    onClick = { onTabSelected(index) }
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
            key = { index, image -> image.url + index }
        ) { index, image ->
            GalleryImageItem(
                image = image,
                isEmoji = isEmoji,
                accentColor = accentColor,
                isDark = isDark,
                isCommandCenter = isCommandCenter,
                onClick = { onImageClick(image) },
                onLongClick = { onImageLongClick(image) }
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
            key = { it.url }
        ) { video ->
            VideoItemCard(
                video = video,
                accentColor = accentColor,
                isDark = isDark,
                isCommandCenter = isCommandCenter,
                onClick = { onVideoClick(video) }
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
                    video.coverUrl.isNotBlank() -> {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(video.coverUrl)
                                .crossfade(true)
                                .build(),
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

/**
 * 全屏图片查看器 — 支持缩放、关闭、下载
 */
@Composable
private fun ImageViewerOverlay(
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
private fun VideoPlayerOverlay(
    videoUrl: String,
    videoTitle: String,
    videoDescription: String = "",
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val isDark = LocalIsDark.current
    val glassSurface = if (isDark) AppColors.GlassSurfaceDark else AppColors.GlassSurfaceLight
    val hapticFeedback = LocalHapticFeedback.current
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
            .build().also { player ->
                player.setMediaItem(MediaItem.fromUri(videoUrl))
                player.prepare()
                player.playWhenReady = true
            }
    }

    // 生命周期管理：监听播放状态 + 释放播放器
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

        onDispose {
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
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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
