# 🏗️ BLYY 架构与 API 文档

本文档面向开发者，详细介绍 BLYY 项目的架构设计、模块划分、关键 API 与扩展指南。

## 目录

- [整体架构](#整体架构)
- [模块结构](#模块结构)
- [数据层](#数据层)
- [领域层 / ViewModel](#领域层--viewmodel)
- [UI 层](#ui-层)
- [设计系统](#设计系统)
- [关键 API](#关键-api)
- [扩展指南](#扩展指南)

---

## 整体架构

BLYY 采用 **MVVM + Clean Architecture** 分层架构：

```
┌─────────────────────────────────────────────┐
│                  UI Layer                   │
│   (Jetpack Compose Screens & Components)   │
└──────────────────┬──────────────────────────┘
                   │ StateFlow / Callbacks
┌──────────────────▼──────────────────────────┐
│              ViewModel Layer                │
│      (UI State + Business Intentions)       │
└──────────────────┬──────────────────────────┘
                   │ Use Cases / Direct Calls
┌──────────────────▼──────────────────────────┐
│             Repository Layer                │
│   (Data Source Coordination, Caching)       │
└──────────────────┬──────────────────────────┘
                   │ Flow / Suspend
        ┌──────────┴──────────┐
        ▼                     ▼
┌──────────────┐    ┌─────────────────┐
│  Local Data  │    │   Remote Data   │
│ Room + DS    │    │ OkHttp + Jsoup  │
└──────────────┘    └─────────────────┘
```

### 技术选型理由

| 选型 | 理由 |
|------|------|
| Jetpack Compose | 现代化声明式 UI，代码量少，状态管理清晰 |
| Hilt | 编译时依赖注入，类型安全，与 Compose 集成良好 |
| Room | 类型安全的 SQLite ORM，KSP 编译时生成代码 |
| DataStore | 替代 SharedPreferences，协程 + Flow 原生支持 |
| Media3 ExoPlayer | Google 官方播放器，支持后台、MediaSession、通知栏 |
| Kotlin Flow | 响应式数据流，与 Compose 生命周期完美结合 |

---

## 模块结构

```
app/src/main/java/com/azurlane/blyy/
├── MainActivity.kt           # 应用入口、导航、侧拉菜单
├── data/                     # 数据层
│   ├── local/
│   │   ├── ShipDao.kt        # Room DAO
│   │   ├── ShipDatabase.kt   # Room 数据库
│   │   └── PlayerSettingsDataStore.kt  # DataStore 偏好
│   ├── model/                # 数据模型
│   │   ├── Ship.kt
│   │   ├── VoiceLine.kt
│   │   ├── JiuxinModels.kt
│   │   └── ...
│   └── repository/           # 数据仓库
│       └── ShipRepository.kt
├── di/
│   └── AppModule.kt          # Hilt 模块
├── service/
│   └── PlaybackService.kt    # Media3 后台服务
├── ui/                       # 表现层
│   ├── components/           # 公共 UI 组件
│   │   ├── BlyyComponents.kt
│   │   ├── ShipCard.kt
│   │   ├── ShipCardShimmer.kt
│   │   ├── ZoomableImage.kt
│   │   └── SecretaryChibiOverlay.kt
│   ├── screens/              # 界面屏幕
│   │   ├── HomeScreen.kt
│   │   ├── VoiceScreen.kt
│   │   ├── SettingsScreen.kt
│   │   ├── AboutScreen.kt
│   │   ├── AssistantScreen.kt
│   │   ├── AssistantConfigScreen.kt
│   │   ├── JiuxinChatScreen.kt
│   │   ├── JiuxinConfigScreen.kt
│   │   ├── Live2DScreen.kt
│   │   ├── GalleryScreen.kt
│   │   ├── GuessByImageScreen.kt
│   │   └── GuessByVoiceScreen.kt
│   └── theme/                # 主题与设计系统
│       ├── Theme.kt
│       ├── Color.kt
│       ├── Type.kt
│       ├── Font.kt
│       ├── Spacing.kt
│       ├── Shape.kt
│       ├── UiStyle.kt
│       ├── Animation.kt
│       ├── GameStyles.kt
│       └── ScreenAdaptive.kt
└── viewmodel/                # ViewModel 层
    ├── PlayerViewModel.kt
    ├── VoiceViewModel.kt
    ├── Live2DViewModel.kt
    └── JiuxinViewModel.kt
```

---

## 数据层

### ShipDao

舰娘相关数据库操作：

```kotlin
@Dao
interface ShipDao {
    @Query("SELECT * FROM ships")
    fun getAllShips(): Flow<List<Ship>>

    @Query("SELECT * FROM ships WHERE id = :id")
    suspend fun getShipById(id: String): Ship?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(ships: List<Ship>)

    @Query("DELETE FROM ships")
    suspend fun clearAll()
}
```

### PlayerSettingsDataStore

管理用户偏好与稍后播放队列：

```kotlin
// 稍后播放队列相关
val playLaterList: Flow<List<PlayLaterItem>>
suspend fun addToPlayLater(item: PlayLaterItem)
suspend fun removeFromPlayLater(voiceUrl: String)
suspend fun clearPlayLaterList()
suspend fun markAsPlayed(voiceUrl: String)

// 玩家设置
val defaultUid: Flow<String>
val defaultServer: Flow<String>
suspend fun setDefaultUid(uid: String)
suspend fun setDefaultServer(server: String)
```

### PlayLaterItem 数据模型

```kotlin
@Serializable
data class PlayLaterItem(
    val voiceUrl: String,        // 语音 URL（同时作为唯一标识）
    val shipName: String,         // 舰娘名称
    val scene: String,            // 场景/皮肤
    val dialogue: String,         // 台词
    val skinName: String = "",
    val avatarUrl: String = "",
    val addedTime: Long = System.currentTimeMillis(),
    val hasPlayed: Boolean = false
)
```

---

## 领域层 / ViewModel

### PlayerViewModel

管理 ExoPlayer 播放器状态、稍后播放队列：

```kotlin
data class PlayerUiState(
    val isPlaying: Boolean = false,
    val currentMediaItem: MediaItem? = null,
    val playMode: PlayMode = PlayMode.LIST_LOOP,
    val playLaterList: List<PlayLaterItem> = emptyList(),
    val isPlayingFromQueue: Boolean = false,
    val currentlyPlayingQueueItemUrl: String? = null,
    // ...
)

class PlayerViewModel @Inject constructor(...) : ViewModel() {
    fun playOrPause()
    fun seekTo(position: Long)
    fun cyclePlayMode()                  // 切换播放模式
    fun skipToNext() / skipToPrevious()
    fun addToPlayLater(item: PlayLaterItem)
    fun removeFromPlayLater(voiceUrl: String)
    fun startPlayQueue()                 // 从队首开始播放
    fun playFromPlayLater(item: PlayLaterItem)  // 播放指定条目
    fun clearPlayLaterList()
}
```

### VoiceViewModel

管理语音列表：

```kotlin
sealed interface VoiceIntent {
    data object PlayPause : VoiceIntent
    data object SkipNext : VoiceIntent
    data object SkipPrevious : VoiceIntent
    data class PlayVoiceAtIndex(val index: Int) : VoiceIntent
    data class SeekTo(val position: Long) : VoiceIntent
}

class VoiceViewModel @Inject constructor(
    private val playerViewModel: PlayerViewModel,
    private val repository: ShipRepository
) : ViewModel() {
    val state: StateFlow<VoiceViewState>
    fun onIntent(intent: VoiceIntent)
}
```

---

## UI 层

### 侧拉菜单（MainActivity.kt）

侧拉菜单使用 Material 3 的 `ModalNavigationDrawer`：

```kotlin
ModalNavigationDrawer(
    drawerState = drawerState,
    drawerContent = {
        ModernDrawerSheet(
            currentRoute = currentDestination?.route,
            onNavigate = { route -> ... },
            onClose = { ... }
        )
    }
) {
    NavHost(...)
}
```

`ModernDrawerSheet` 的菜单项定义：

```kotlin
data class DrawerMenuItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val description: String,
    val color: Color,
    val group: String = ""  // 菜单分组：娱乐 / 挑战 / 工具 / 系统
)
```

### 语音播放控制栏（VoiceScreen.kt）

`GlassPlayerControlBar` 组件提供：

- 播放/暂停、上一首/下一首按钮
- 进度条 + 拖动 seek
- 播放模式循环切换
- 稍后播放队列入口
- 展开/收起手势
- 指挥中心风格与经典风格自动适配

`PlayLaterBottomSheet` 是队列弹窗：

```kotlin
@Composable
private fun PlayLaterBottomSheet(
    items: List<PlayLaterItem>,
    currentlyPlayingUrl: String?,
    isPlayingFromQueue: Boolean,
    onDismiss: () -> Unit,
    onPlayItem: (PlayLaterItem) -> Unit,
    onStartQueue: () -> Unit,
    onRemoveItem: (PlayLaterItem) -> Unit,
    onClearAll: () -> Unit
)
```

---

## 设计系统

设计系统的完整 token 化是 BLYY 的核心特色。所有 UI 元素都必须使用设计系统，禁止硬编码颜色、尺寸、动画。

### 颜色（AppColors）

```kotlin
object AppColors {
    val PrimaryLight: Color = Color(0xFF0096C7)  // 指挥中心青蓝
    val SecondaryLight: Color = Color(0xFFE8A838)  // 金色高亮
    val TertiaryLight: Color = Color(0xFF5B8DEF)   // 蓝色
    val BackgroundDark: Color = Color(0xFF0A1628)  // 深海背景
    // ... 完整 7 级 Surface、5 级 Rarity、玻璃效果
}
```

### 间距（AppSpacing）

8dp 网格系统：

```kotlin
object AppSpacing {
    val Xxs = 2.dp; val Xs = 4.dp; val Sm = 8.dp
    val Md = 12.dp; val Lg = 16.dp; val Xl = 20.dp
    val Xxl = 24.dp; val Xxxl = 32.dp
    val Huge = 48.dp; val Massive = 64.dp

    object Corner {
        val Xs = 4.dp; val Sm = 8.dp; val Md = 12.dp
        val Lg = 16.dp; val Xxl = 24.dp
    }
    // ...
}
```

### 形状（BlyyShapes）

碧蓝航线特色切角矩形：

```kotlin
object BlyyShapes {
    val PanelSmall: Shape = chamferedShape(8.dp)
    val PanelMedium: Shape = chamferedShape(12.dp)
    val PanelLarge: Shape = chamferedShape(16.dp)
    val NavBar: Shape = diagonalChamferedShape(14.dp)
    val Card: Shape = chamferedShape(10.dp)
    val Button: Shape = chamferedShape(8.dp)
}
```

### 动画（AppAnimation）

统一的动画规范：

```kotlin
object AppAnimation {
    val Duration = object {
        val Instant = 100; val Fast = 200; val Normal = 350
        val Slow = 500; val VerySlow = 800
    }
    val Spring = object {
        val Standard = SpringSpec<Float>(...)
        val Bouncy = SpringSpec<Float>(...)
    }
    val Press = object {
        val LightScale = 0.96f
        val StandardScale = 0.97f
        val HeavyScale = 0.94f
    }
}
```

### 小屏自适应（ScreenAdaptive）

手表端（最小边 ≤ 360dp）自动缩放：

```kotlin
@Composable
fun isWatchScreen(): Boolean { ... }

@Composable
fun adaptiveDp(watch: Dp, normal: Dp): Dp { ... }

@Composable
fun adaptiveSp(watch: TextUnit, normal: TextUnit): TextUnit { ... }
```

### 双风格切换（UiStyle）

通过 `LocalUiStyle` 全局切换：

```kotlin
@Composable
fun MyComponent() {
    if (LocalUiStyle.current.isCommandCenter()) {
        // 指挥中心风格：切角矩形、青蓝配色、HUD 元素
    } else {
        // 经典风格：圆角矩形、紫色配色
    }
}
```

---

## 关键 API

### 访问设计系统

```kotlin
import com.azurlane.blyy.ui.theme.*

// 颜色
MaterialTheme.colorScheme.primary
AppColors.PrimaryLight

// 间距
AppSpacing.Md
AppSpacing.Padding.ListItemHorizontal

// 形状
BlyyShapes.Card
BlyyShapes.PanelMedium

// 动画
AppAnimation.Duration.Normal
AppAnimation.Press.StandardScale

// 自适应
if (isWatchScreen()) AppSpacing.Sm else AppSpacing.Md
```

### 添加新的设置项

1. 在 `PlayerSettingsDataStore` 中添加 Flow 与 setter：

```kotlin
val mySetting: Flow<String> = dataStore.data.map { it[MY_KEY] ?: "" }
suspend fun setMySetting(value: String) {
    dataStore.edit { it[MY_KEY] = value }
}
```

2. 在 `SettingsScreen` 中添加 UI 控件绑定 ViewModel

3. 在 `ViewModel` 中封装状态暴露给 UI

### 添加新的菜单项

1. 在 `MainActivity.kt` 的 `menuItems` 列表中添加 `DrawerMenuItem`：

```kotlin
DrawerMenuItem(
    route = "my_new_route",
    label = "我的新功能",
    icon = Icons.Rounded.MyIcon,
    description = "功能简介",
    color = MaterialTheme.colorScheme.primary,
    group = "工具"  // 娱乐 / 挑战 / 工具 / 系统
)
```

2. 在 `NavHost` 中添加对应路由：

```kotlin
composable("my_new_route") {
    MyNewScreen(...)
}
```

### 添加新的稍后播放条目来源

1. 构造 `PlayLaterItem`：

```kotlin
val item = PlayLaterItem(
    voiceUrl = voice.getActiveAudioUrl(language),
    shipName = shipName,
    scene = voice.scene,
    dialogue = voice.dialogue,
    avatarUrl = avatarUrl
)
```

2. 调用 ViewModel：

```kotlin
playerViewModel.addToPlayLater(item)
```

---

## 扩展指南

### 添加新的游戏模式

1. 在 `screens/` 中创建新文件 `MyGameScreen.kt`
2. 在 `di/AppModule.kt` 中注册依赖（如果需要新的 ViewModel/Repository）
3. 在 `viewmodel/` 中创建 `MyGameViewModel`
4. 在 `MainActivity.kt` 的 `NavHost` 中添加路由
5. 在 `menuItems` 中添加菜单项

### 自定义主题

如需添加新的 UI 风格：

1. 在 `UiStyle.kt` 的 `UiStyle` 枚举中添加新风格
2. 在 `Theme.kt` 的 `BlyyTheme` 中添加对应主题映射
3. 在 `BlyyShapes`、`AppColors` 中添加对应风格的定义
4. 在所有 `if (LocalUiStyle.current.isCommandCenter())` 处添加新分支

### 集成第三方 API

1. 在 `gradle/libs.versions.toml` 中添加依赖
2. 在 `di/AppModule.kt` 中提供依赖（如 OkHttp、Retrofit）
3. 在 `data/` 中创建模型与 API 接口
4. 在 `data/repository/` 中封装业务逻辑

---

## 性能与质量保证

- **Lint 检查**：`./gradlew :app:lint`
- **单元测试**：`./gradlew :app:test`
- **构建**：`./gradlew :app:assembleDebug`
- **R8/ProGuard**：Release 构建已开启代码混淆与资源压缩

### 关键性能点

- 列表项使用 `key()` 确保 LazyColumn 重用
- 大图片使用 Coil 加载并自动缓存
- 协程作用域使用 `viewModelScope` 避免泄漏
- 状态使用 `StateFlow`/`collectAsStateWithLifecycle` 避免不必要的重组
- 重复渲染的复杂组件使用 `remember` 缓存

---

更多问题请参考：
- 📖 [README.md](../README.md)
- 📋 [使用说明](USAGE.md)
- 🤝 [贡献指南](../CONTRIBUTING.md)
