package com.example.blyy.ui.screens

import android.util.Log
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.blyy.ui.components.ZoomableImage
import com.example.blyy.ui.theme.*
import com.example.blyy.viewmodel.ShipGalleryState
import com.example.blyy.viewmodel.ShipGalleryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL

private const val TAG = "ShipGalleryScreen"

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ShipGalleryScreen(
    shipName: String,
    avatarUrl: String,
    state: ShipGalleryState,
    viewModel: ShipGalleryViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isDark = isSystemInDarkTheme()
    val hapticFeedback = LocalHapticFeedback.current
    
    val illustrations = state.gallery?.illustrations ?: emptyList()
    val figures = state.gallery?.figures ?: emptyList()
    val pagerState = rememberPagerState(pageCount = { illustrations.size })
    
    val glassSurface = if (isDark) AppColors.GlassSurfaceDark else AppColors.GlassSurfaceLight
    
    val currentPage = pagerState.currentPage
    val displayFigure = figures.getOrNull(currentPage) ?: figures.firstOrNull()
    
    var showSwitchSuccess by remember { mutableStateOf(false) }

    LaunchedEffect(currentPage, displayFigure) {
        Log.d(TAG, "Page: $currentPage, displayFigure: ${displayFigure?.first}")
    }
    
    fun switchFigure() {
        displayFigure?.let { (skinName, figureUrl) ->
            viewModel.selectFigure(shipName, skinName, figureUrl)
            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            showSwitchSuccess = true
            scope.launch {
                kotlinx.coroutines.delay(1500)
                showSwitchSuccess = false
            }
            Toast.makeText(context, "已切换立绘：$skinName", Toast.LENGTH_SHORT).show()
        }
    }

    fun downloadImage(url: String, name: String) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val imageUrl = URL(url)
                    val fileName = "${shipName}_${name}.png"
                    val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS
                    )
                    val file = File(downloadsDir, "BLYY/Gallery/$fileName")
                    file.parentFile?.mkdirs()
                    
                    imageUrl.openStream().use { input ->
                        FileOutputStream(file).use { output ->
                            input.copyTo(output)
                        }
                    }
                    
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "已保存到 Download/BLYY/Gallery/$fileName", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "下载失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainer)
        )
        
        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (state.error != null) {
            ErrorState(error = state.error, onBack = onBack)
        } else if (illustrations.isEmpty()) {
            EmptyState(onBack = onBack)
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    val (skinName, imageUrl) = illustrations[page]
                    
                    Box(modifier = Modifier.fillMaxSize()) {
                        ZoomableImage(
                            model = ImageRequest.Builder(context)
                                .data(imageUrl)
                                .crossfade(true)
                                .listener(
                                    onError = { _, _ ->
                                        Log.e(TAG, "Failed to load image: $imageUrl")
                                    },
                                    onSuccess = { _, _ ->
                                        Log.d(TAG, "Successfully loaded image: $imageUrl")
                                    }
                                )
                                .build(),
                            contentDescription = skinName,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                            error = rememberVectorPainter(Icons.Rounded.BrokenImage),
                            placeholder = rememberVectorPainter(Icons.Rounded.Image)
                        )
                        
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(160.dp)
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            Color.Transparent,
                                            if (isDark) Color.Black.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.8f)
                                        )
                                    )
                                )
                        )
                        
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 70.dp)
                                .fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Surface(
                                shape = RoundedCornerShape(AppSpacing.Corner.Lg),
                                color = if (isDark) Color.Black.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.85f),
                                tonalElevation = AppSpacing.Elevation.Sm
                            ) {
                                Text(
                                    text = skinName,
                                    style = AppTypography.TitleMedium,
                                    color = if (isDark) Color.White else Color.Black,
                                    modifier = Modifier.padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Sm),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
                
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 32.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    repeat(illustrations.size) { index ->
                        val isSelected = currentPage == index
                        Box(
                            modifier = Modifier
                                .padding(horizontal = AppSpacing.Xxs)
                                .size(if (isSelected) 10.dp else 6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                        )
                    }
                }
                
                if (figures.isNotEmpty() && displayFigure != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(bottom = 10.dp, end = AppSpacing.Md)
                    ) {
                        FigureDisplayPanel(
                            displayFigure = displayFigure,
                            isDark = isDark,
                            showSwitchSuccess = showSwitchSuccess,
                            isCurrentSelected = state.selectedFigureUrl == displayFigure.second,
                            onSwitchClick = { switchFigure() }
                        )
                    }
                }
                
                TopBar(
                    glassSurface = glassSurface,
                    shipName = shipName,
                    onBack = onBack,
                    onDownload = {
                        val currentImage = illustrations.getOrNull(currentPage)
                        if (currentImage != null) {
                            downloadImage(currentImage.second, currentImage.first)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun FigureDisplayPanel(
    displayFigure: Pair<String, String>,
    isDark: Boolean,
    showSwitchSuccess: Boolean = false,
    isCurrentSelected: Boolean = false,
    onSwitchClick: () -> Unit = {}
) {
    val hapticFeedback = LocalHapticFeedback.current
    var showBubble by remember { mutableStateOf(true) }
    
    val successScale = remember { Animatable(1f) }
    
    LaunchedEffect(showSwitchSuccess) {
        if (showSwitchSuccess) {
            successScale.animateTo(
                targetValue = 1.2f,
                animationSpec = spring(stiffness = Spring.StiffnessLow, dampingRatio = Spring.DampingRatioMediumBouncy)
            )
            successScale.animateTo(
                targetValue = 1f,
                animationSpec = spring(stiffness = Spring.StiffnessMedium, dampingRatio = Spring.DampingRatioMediumBouncy)
            )
        }
    }
    
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Xs)
    ) {
        AnimatedVisibility(
            visible = showBubble,
            enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom) + scaleIn(transformOrigin = TransformOrigin(0.5f, 1f)),
            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom) + scaleOut(transformOrigin = TransformOrigin(0.5f, 1f))
        ) {
            SpeechBubble(
                text = "正在查看：${displayFigure.first}",
                isDark = isDark,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        Surface(
            shape = RoundedCornerShape(AppSpacing.Corner.Lg),
            color = if (isDark) Color.Black.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.85f),
            tonalElevation = AppSpacing.Elevation.Md,
            modifier = Modifier
                .width(100.dp)
                .wrapContentHeight()
        ) {
            Box(
                modifier = Modifier
                    .size(90.dp)
                    .padding(AppSpacing.Xs)
                    .clip(RoundedCornerShape(AppSpacing.Corner.Md))
                    .clickable {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        showBubble = !showBubble
                    }
            ) {
                ChibiFlipImage(
                    imageUrl = displayFigure.second,
                    contentDescription = displayFigure.first,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(2.dp)
                )
                
                if (isCurrentSelected) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(20.dp)
                            .background(
                                MaterialTheme.colorScheme.primary,
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = "当前选中",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
        
        Button(
            onClick = onSwitchClick,
            shape = RoundedCornerShape(AppSpacing.Corner.Md),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isCurrentSelected) 
                    MaterialTheme.colorScheme.primaryContainer 
                else 
                    MaterialTheme.colorScheme.primary
            ),
            modifier = Modifier
                .graphicsLayer {
                    scaleX = successScale.value
                    scaleY = successScale.value
                }
                .height(36.dp)
        ) {
            Icon(
                imageVector = if (isCurrentSelected) Icons.Rounded.Check else Icons.Rounded.SwapHoriz,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(AppSpacing.Xxs))
            Text(
                text = if (showSwitchSuccess) "已切换" else if (isCurrentSelected) "当前立绘" else "切换",
                style = AppTypography.LabelMedium
            )
        }
    }
}

@Composable
private fun SpeechBubble(
    text: String,
    isDark: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(AppSpacing.Corner.Md),
        color = if (isDark) Color.White.copy(alpha = 0.9f) else Color.Black.copy(alpha = 0.8f),
        modifier = modifier
    ) {
        Text(
            text = text,
            style = AppTypography.LabelSmall,
            color = if (isDark) Color.Black else Color.White,
            modifier = Modifier.padding(horizontal = AppSpacing.Sm, vertical = AppSpacing.Xxs),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ChibiFlipImage(
    imageUrl: String,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    var currentUrl by remember { mutableStateOf(imageUrl) }
    val rotation = remember { Animatable(0f) }
    val context = LocalContext.current

    LaunchedEffect(imageUrl) {
        if (imageUrl != currentUrl) {
            rotation.animateTo(90f, animationSpec = tween(150, easing = LinearOutSlowInEasing))
            currentUrl = imageUrl
            rotation.snapTo(-90f)
            rotation.animateTo(0f, animationSpec = tween(150, easing = FastOutLinearInEasing))
        }
    }

    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(currentUrl)
            .crossfade(true)
            .listener(
                onError = { _, _ ->
                    Log.e(TAG, "Failed to load chibi image: $currentUrl")
                },
                onSuccess = { _, _ ->
                    Log.d(TAG, "Successfully loaded chibi image: $currentUrl")
                }
            )
            .build(),
        contentDescription = contentDescription,
        modifier = modifier.graphicsLayer {
            rotationY = rotation.value
            cameraDistance = 12f * density
        },
        contentScale = ContentScale.Fit,
        error = rememberVectorPainter(Icons.Rounded.Person),
        placeholder = rememberVectorPainter(Icons.Rounded.Person)
    )
}

@Composable
private fun ErrorState(error: String, onBack: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.BrokenImage,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(AppSpacing.Lg))
            Text(
                text = error,
                style = AppTypography.BodyLarge,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(AppSpacing.Lg))
            Button(onClick = onBack) {
                Text("返回")
            }
        }
    }
}

@Composable
private fun EmptyState(onBack: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.BrokenImage,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(AppSpacing.Lg))
            Text(
                text = "暂无立绘数据",
                style = AppTypography.BodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(AppSpacing.Lg))
            Button(onClick = onBack) {
                Text("返回")
            }
        }
    }
}

@Composable
private fun BoxScope.TopBar(
    glassSurface: Color,
    shipName: String,
    onBack: () -> Unit,
    onDownload: () -> Unit
) {
    Surface(
        onClick = onBack,
        shape = CircleShape,
        color = glassSurface.copy(alpha = 0.9f),
        tonalElevation = AppSpacing.Elevation.Sm,
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(top = 48.dp, start = AppSpacing.Lg)
            .size(44.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "返回",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
    }
    
    Surface(
        onClick = onDownload,
        shape = CircleShape,
        color = glassSurface.copy(alpha = 0.9f),
        tonalElevation = AppSpacing.Elevation.Sm,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(top = 48.dp, end = AppSpacing.Lg)
            .size(44.dp)
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
    
    Text(
        text = shipName,
        style = AppTypography.TitleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 56.dp)
    )
}
