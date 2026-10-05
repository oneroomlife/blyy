# BLYY UI 深度审计与优化重构开发文档

> 版本：v1.0（2026-10-04）
> 范围：`ui/` 目录全部 35,030 行 Compose 代码（26 屏 + 20 组件文件 + 11 主题文件）+ 18 个 ViewModel 的状态暴露面
> 方法：4 组并行深度阅读（图鉴/首页、语音/识舰娘、啾信/助手、秘书舰/系统/组件库）+ 令牌层与导航壳逐行精读，关键缺陷均经源码二次实证
> 关联文档：[ARCHITECTURE.md](ARCHITECTURE.md)（分层架构）、[UI_TOKEN_GUIDE.md](UI_TOKEN_GUIDE.md)（令牌速查）；2026-06 一二期重构报告已归档于 git 历史（513b906 之前）

---

## 0. 文档定位

本文档是下一阶段 UI 优化重构的**唯一工作底稿**，回答三个问题：

1. 现在的 UI 长什么样、是怎么组织的（§1–§3）；
2. 哪里有问题、问题有多大（§4 共性债、§5 缺陷清单）；
3. 按什么顺序改、改的时候不能破坏什么（§6 路线图、§7 红线）。

与 2026-06 的《UI_REFACTOR_REPORT》不同：那份报告驱动的一二期重构（令牌化、组件统一、大文件拆分）已全部落地推送；本文基于重构后的现状重新审计，重点转向**残留缺陷、组件库闲置与并存、状态管理模式分裂、巨型 Composable 二次膨胀**四类问题。

---

## 1. UI 总体架构

### 1.1 技术栈与分层

Jetpack Compose + Material 3 + Hilt + Navigation Compose + Coil + Media3（ExoPlayer/Session）+ AGSL RuntimeShader（API 33+ 流体背景）。UI 层包结构：

```
ui/
├── AppRoot.kt              897 行  导航壳：NavHost + 底栏 + 抽屉 + 更新弹窗 + 全局立绘
├── components/             20 文件 公共组件库（见 §2.3 盘点）
├── theme/                  11 文件 设计令牌（见 §2.1）
├── icons/Github.kt                 自绘图标
└── screens/
    ├── chat/  config/  gallery/  guess/  voice/   五个子包（啾信、啾信配置、图鉴、识舰娘、语音）
    └── *.kt                26 个顶层 Screen
```

### 1.2 信息架构与导航

**导航结构（AppRoot.kt）**：`SharedTransitionLayout`（AppRoot.kt:269，承载头像共享元素转场）→ `ModalNavigationDrawer`（:330，11 项菜单，菜单项硬编码在 AppChrome.kt:464-552）→ `NavHost`（:367，28 条路由）→ 底部 `ModernNavigationBar`（:880，仅 2 个 Tab）→ 根级 `SecretaryChibiOverlay`（:851-869，悬浮窗未开启时的应用内秘书舰立绘）。

**路由全景表**：

| 分组 | 路由 | 界面 | ViewModel |
|---|---|---|---|
| 底部 Tab | `home` | HomeScreen（我的后宅） | HomeViewModel（MVI） |
| 底部 Tab | `gallery` | GalleryScreen（船坞/成员档案） | GalleryViewModel（MVI，双流注入） |
| 舰娘详情 | `voice/{shipName}?avatarUrl` | VoiceScreen（语音档案） | VoiceViewModel + PlayerViewModel |
| 舰娘详情 | `gallery/{shipName}?avatarUrl` | ShipGalleryScreen（立绘翻页） | ShipGalleryViewModel |
| 成员详情 | `student_voice/{studentName}?avatarUrl&studentLink` | VoiceScreen 复用 | VoiceViewModel |
| 成员详情 | `student_gallery/{studentName}?avatarUrl&studentLink` | StudentGalleryScreen | StudentGalleryViewModel |
| 识舰娘 | `guess_image` / `guess_voice` / `guess_history` | 看图 / 听音 / 历史 | GuessShipViewModel（两个玩法共用）/ GuessHistoryViewModel |
| 水印 | `watermark_camera`、`watermark_editor?uri&wid` | 相机入口页 / 编辑器 | WatermarkCameraViewModel / WatermarkEditorViewModel |
| 社区 | `leaderboard` | LeaderboardScreen | LeaderboardViewModel |
| 系统 | `about`、`settings`、`app_icon_settings`、`image_cropper/{imageUri}` | 关于 / 设置 / 图标 / 裁剪 | AboutViewModel / SettingsViewModel / UpdateCheckViewModel |
| 小助手 | `assistant`、`assistant_config` | 数据查询页 / 查询参数配置 | AssistantViewModel（MVI）/ SettingsViewModel |
| 啾信 | `jiuxin_conversation_list`、`jiuxin_chat` | 会话列表 / 聊天 | **JiuxinViewModel（Activity 级共享，3177 行）** |
| 啾信配置 | `jiuxin_config`、`jiuxin_ship_config` | 全局配置 / 会话级配置 | 同上 |
| 秘书舰 | `secretary_mode`、`secretary_settings`、`secretary_random`、`secretary_pick_home`、`secretary_pick_gallery`、`sd_resource_gallery` | 模式 / 设置 / 翻牌 / 两个选人入口 / SD 资源库 | SecretaryShipViewModel（AppRoot 级代持） |

另：抽屉 `live2d` 项直接跳浏览器（AppRoot.kt:338-347）；语音路由注册了 deep link `blyy://voice/{shipName}?avatarUrl={avatarUrl}`（:510-512）。

**导航壳行为细节**：
- 页面转场：Material Container Transform 风格的横滑 + 淡入（AppRoot.kt:371-432，spring 与 `AppAnimation.Springs.Gentle` 同参内联——注释已说明 spring<Float> 无法用于 IntOffset）；Tab 间切换 FadeThrough（:185-198）。
- 底栏隐显：20 个路由条件枚举 `showBottomBar`（AppRoot.kt:212-234，**字符串前缀匹配，新增页面必须记得加条件**）+ Gallery 滚动时 `isBottomBarVisible` 联动（:499-501）+ graphicsLayer 位移/淡出（:258-267, 871-878）。
- Gallery Tab 文案随档案类型切换「船坞/成员」（:256, 474-480）。
- 启动更新检测：UpdateCheckViewModel 挂在 SharedTransitionLayout 层（:272-327），`UpdateAvailableDialog` 弹窗 + 网盘链接强制刷新。
- **秘书舰域的 ViewModel 由 AppRoot 创建一次并跨 6 条路由共享**（:363-364），Mode/Settings 屏靠 10+ 个回调参数 props drilling（:754-805）；悬浮窗开关绕过 VM，直接操作 `MainActivity.overlayState` 静态流 + `startOverlayService()`（:772-785, 851）。

### 1.3 双风格体系与多设备自适应

- **UiStyle 二态**：`COMMAND_CENTER`（HUD 指挥中心风：切角面板、扫描线、辉光）与 `CLASSIC`（Material 经典风），由 `LocalUiStyle` + `isCommandCenter()` 分发；同层还有 `LocalIsDark`、`LocalIsWatch`、`LocalSemanticColors` 四个 CompositionLocal（Theme.kt:196-201）。
- **动态色**：Material You 动态取色仅接管 surface 阶梯，primary/secondary/tertiary/error 锁品牌色（Theme.kt:152-182）——开关在设置页。
- **手表适配**：`isWatchScreen()`（最小边 ≤360dp，ScreenAdaptive.kt:17-20）+ 整套 `WatchSpacing`/`WatchTypography` 缩放副本（:46-179）。问题：适配散落在 12+ 处 `if (isWatchScreen())` 内联三元（GalleryTopBar.kt 14 处、SettingsNavigationRow 等），未沉淀为令牌。

---

## 2. 设计系统现状

### 2.1 令牌体系全景

| 文件 | 令牌对象 | 内容 | 健康度 |
|---|---|---|---|
| Color.kt（718 行） | `AppColors`、`ClassicColors`、`MedalColors` | M3 色板（明暗双套 × 双风格）、Panel/Depth 有色阴影/Accent/Player/Live2D/Glass*/Rarity 七族扩展色、Gradient 刷子族、Semantic 明暗 | ✅ 全 app 屏幕层 `Color(0x...)` 为 0，色彩纪律优秀 |
| Spacing.kt（208 行） | `AppSpacing` | 基础 8dp 网格 + Padding/Gap/Screen/Card/Icon/Corner/Hud/Elevation/Border/Height/Avatar/Figure/TopBar/Game 14 个子对象 | ✅ 覆盖广，但屏幕层仍有 ~250 处裸 dp 残留 |
| Shape.kt | `BlyyShapes`、`chamferedShape` | 切角/圆角形状唯一来源（面板三档、NavBar、Card、Button、Dialog、BottomSheet、聊天气泡收尖角） | ✅ |
| Animation.kt（323 行） | `AppAnimation` | Duration/Easings（11 种）/Springs（7 种）/Press 按压规范/Specs/Repeating 循环族/Interaction/Page/Effect/Component | ✅ 定义完备，但旁路多（裸 tween/spring 约 15 处） |
| Elevation.kt | `AppElevation` | 六档 L0-L5 + PressedDelta + GlassTonal | ✅ |
| SemanticColors.kt | `SemanticColors` + `LocalSemanticColors` | Success/Warning/Info 十二字段，明暗双套 | ✅ |
| ScreenAdaptive.kt | `WatchSpacing`/`WatchTypography`/`adaptiveDp` | 手表缩放副本 | ⚠️ 与主令牌需双维护 |
| IconTokens.kt | `BlyyIcon` | 图标五档语义尺寸 + tint alpha 权重规范 | ✅ 但使用率低 |
| GameStyles.kt（391 行） | `GameStyles` | 纯转发 AppSpacing.Game + 自有动画 | ⚠️ 与 AppSpacing.Game 双入口 |
| Type.kt（339 行） | `AppTypography` | 双风格 Typography + 语义扩展（CardTitle/ButtonText 等） | ✅ |

**令牌层遗留问题**：
1. `AppColors.Depth`（有色阴影令牌，Color.kt:106-113）定义后**使用率极低**——ShipCard 阴影仍在用 rarityColor 自拟（ShipCard.kt:99-104），当初「收编各 Screen 残留硬编码」的目标未完成。
2. `AppSpacing.Card.AspectRatio = 0.75f` 与 ShipCard 实际 `aspectRatio(0.8f)`（ShipCard.kt:97）不一致，shimmer 骨架与真卡形状不吻合。
3. `GameStyles` 与 `AppSpacing.Game` 是同一套值的两个门面，`BlyyButton`（BlyyDesignSystem.kt:57-71）只有 GameStyles.kt:299 一个消费者。

### 2.2 主题装配

`BlyyTheme`（Theme.kt:184-208）一次性注入四 CompositionLocal + 双风格 ColorScheme/Typography。`ClassicDarkScheme` 缺 `surfaceContainerLowest/Low` 两个阶梯（Theme.kt:80-101），经典风格下这两个档位回落 M3 默认值，与自定阶梯衔接可能不连续。

### 2.3 组件库盘点

**核心库 BlyyComponents.kt（1247 行）——全部在用，是事实上的 UI 基建**：

| 组件 | 外部使用 | 备注 |
|---|---|---|
| `AdaptiveScreenBackground` | **35 文件 56 次**（全库第一） | 双风格背景分流 |
| `BlyyTopBar` | 30 文件 53 次 | HUD 顶栏，内部按 UiStyle 分发 `ClassicTopBar` |
| `BlyySectionPanel` | 20 文件 50 次 | 分组面板 |
| `BlyyPrimaryButton`/`BlyySecondaryButton` | 16+8 文件 | 切角渐变 + 流光按压 |
| `BlyyPanel` | 12 文件 31 次 | 切角容器 + 四角 L 装饰 |
| `BlyyChip`、`BlyySettingsRow`、`BlyyEmptyState`、`BlyyErrorState`、`BlyyLoadingState`、`BlyySpeechBubble` | 3-8 文件 | 状态三件套覆盖良好 |
| `adaptiveGlassSurface/Border/CardShape` | 3-5 文件 | 双风格取色函数 |

**BlyyDesignSystem.kt（353 行）——「第二组件库」，一半闲置**：

| 组件 | 状态 | 处置建议 |
|---|---|---|
| `BlyyScreenScaffold` | **0 调用** | **激活**：35 处手写 `AdaptiveScreenBackground + Column + BlyyTopBar` 三件套本可由它收编 |
| `BlyySearchBar` | 0 调用 | 激活或删除（Pick 屏自写了私有 SearchAndFilterBar） |
| `BlyyCard`/`BlyyMetricCard` | 0 外部调用 | 与 BlyyPanel 双卡片体系，建议删除 |
| `BlyySuccessBanner` | 0 调用 | 删除 |
| `BlyyTabRow`、`BlyyAnimatedEmptyState` | 2 文件低频 | 保留 |
| `BlyyButton`（variant 分发器） | 仅 GameStyles 内部 1 次 | 并入 GameStyles 或删除，Tertiary/Text 变体是空壳 |

**其他组件文件**：`BlyyConfirmDialog`（12 文件 17 次）、`BlyyBottomSheet`（21 文件 32 次）、`StableOutlinedTextField`（15 文件 35 次，含全库最好的光标竞态修复注释）三大件健康；`BlyyDialog`、`BlyySearchField`、`BlyyListItem`（**讽刺：它就是为统一「图标+标题+副标题+箭头」导航行而生，却 0 调用**）、`BlyySnackbarHost` 四个 0 调用；`BlyyHaptics`（20+ 文件，四档语义触觉，唯一触觉入口）、`BlyyEntrance`（8 文件 28 次）健康；`ShipCardShimmer` 与 `BlyySkeleton` 双骨架屏体系并存；`SecretaryChibiOverlay` 悬浮窗/应用内两分支约 120 行逐行重复。

**AppChrome.kt（1222 行）**：名为「Chrome」但导航壳实体在 AppRoot；实际装了底栏（ModernNavigationBar + Item）、抽屉（ModernDrawerSheet + Item，菜单项硬编码）、更新弹窗（UpdateAvailableDialog + UpdateChannelOption）。头部残留拆分时未清理的约 40 个死 import（NavHost/SharedTransitionLayout/HomeViewModel 等）。`UpdateAvailableDialog` 用 raw `AlertDialog` 而非自家 BlyyDialog 体系。

**「同一件事 N 套实现」并存总表**（重构的核心靶子）：

| 领域 | 体系 A | 体系 B | 体系 C |
|---|---|---|---|
| 导航行/设置行卡 | `BlyyListItem`（0 用） | `BlyySettingsRow`（开关） | 私有 6 件：SettingsNavigationRow、SettingsEntryCard、ModeOptionCard、SubOptionCard、UpdateChannelCard、UpdateChannelOption |
| 卡片 | `BlyyPanel`（31 次） | `BlyyCard`/`BlyyMetricCard`（0 用） | raw M3 Card（LeaderboardScreen:190,267） |
| 按钮 | BlyyPrimary/Secondary | GameStyles.GameButton | raw M3 Button/OutlinedButton/FilledTonalButton（Mode:791,971、Random:145,277、Leaderboard:221） |
| 对话框 | BlyyConfirmDialog/BlyyBottomSheet | raw AlertDialog（JiuxinChatScreen 4 处、AppChrome 更新弹窗） | 自绘玻璃 Dialog（MessageActionSheet/MessageEditSheet） |
| 搜索框 | GallerySearchBar 与 ClassicGallerySearchBar（**同文件 80% 重复**，GalleryTopBar.kt:206-603） | SdResourceGallery 用 raw OutlinedTextField（:165-200） | Pick 屏私有 SearchAndFilterBar |
| 缩放手势 | WatermarkEditor `awaitEachGesture` 手写 105 行（最健壮） | ImageCropper `detectTransformGestures` | `ZoomableImage` 组件（自带手写双击检测） |
| 骨架屏 | `BlyySkeleton` 系列 | `ShipCardShimmer` | 裸 CircularProgressIndicator（ShipGallery:127-133） |
| 空态 | `BlyyEmptyState`/`BlyyAnimatedEmptyState` | Home 自绘 EmptyFavoritesView、Sd 自绘两套 | 啾信 JuusEmptyState、chat EmptyChatState |
| 头像 | `RobustAvatar` | `ShipAvatarDisplay`（JiuxinShipConfigScreen:630-673，自带复合 URL 解码） | — |
| 图片下载 | ShipGalleryScreen:94-122 | StudentGalleryScreen:202-241 | VoiceScreen:177-214（三份 `URL.openStream`） |
| 格式化时间 | `formatSessionTime`（ChatCommon.kt:233） | `formatSessionPreview`（ConversationListScreen:898） | — |

### 2.4 ViewModel 状态暴露模式盘点

| 模式 | 代表 | 问题 |
|---|---|---|
| **标准 MVI**（state + onIntent） | AssistantViewModel（124 行范本）、SecretaryShipViewModel、AboutViewModel、HomeViewModel、GuessShipViewModel | 无 |
| 散装 StateFlow + setter | SettingsViewModel（每设置项一条 flow，同时服务 Settings/IconSettings/ImageCropper/AssistantConfig 4 屏）、JiuxinViewModel（**22 个 StateFlow**，聊天区另有 chatUiState 大对象） | 参数爆炸：PersonaSection 29 参、ApiSection 23 参、Secretary Mode/Settings 屏 10+ 回调 props drilling |
| 单 UiState MVVM | LeaderboardViewModel、Watermark 两兄弟 | 可向 MVI 靠拢 |
| **无 VM** | SdResourceGalleryScreen（直连 SDResourceManager 单例 + 本地 remember） | 旋转屏丢状态、错误被吞 |
| **贫血双传**（state + viewModel 同时传） | ShipGalleryScreen、StudentGalleryScreen、Guess 两屏、SecretaryShipRandomScreen | 与 MVI 屏两种写法并存 |

一次性事件**没有统一通道**：现状是四种并存——可空 StateFlow 消费（WatermarkCamera.message）、LaunchedEffect(state.lastResult) 状态驱动（Guess 两屏）、UI 层直调 Toast（全 app 30+ 处）、Snackbar（VoiceScreen.playbackError）。

---

## 3. 界面分组深度分析

### 3.1 首页与图鉴组

**HomeScreen.kt（889 行）**：`Box`（AGSL 流体着色器背景 + 誓约三层氛围背景）→ 顶栏 → `PullToRefreshBox` → `LazyVerticalGrid(Adaptive)`。四态中**错误态缺失**（HomeViewModel.state.error 字段存在但 UI 从不消费，HomeScreen.kt:264-268 只有空/载/数据三分支）。亮点：着色器 30fps 节流 + 生命周期绑定（:167-186）、滚动时关闭装饰动画（:230-232）、空态错落入场。债：着色器在无誓约舰（oathIntensity=0）时仍每 33ms 重绘；`key = { it.name }`（:258）同名舰有理论崩溃风险；openWiki 与 GalleryScreen 逐行重复（:129-147）。

**ShipCard.kt（712 行）**：核心网格卡。亮点：按压三联动效、`combinedClickable` + 语义触觉、滚动时装饰动画降级（RarityGlow→静态 AccentBar，:144-148）。债：①单卡无限动画预算失控——高稀有+誓约+动画开启时 **7 个无限动画/卡**（RarityGlow 2 + OathSpecialEffect 3 + FavoriteBadgeAnimated 2）；②placeholder 语义错误（加载中显示 BrokenImage 碎图图标，:413）；③`onLongClick` 回调语义混乱（仅三个回调全 null 才触发，:126-130）；④aspectRatio 硬编码 0.8f 与 shimmer 0.75f 不一致。

**GalleryScreen.kt（554 行）**：图鉴主列表，**状态机完成度全组最高**（loading/error/empty/缓存数据+错误横幅四态齐全 + ForceRefresh 重试）。债：①`GalleryScreen` 本体 372 行 God-Composable（搜索防抖/顶栏隐显/筛选/弹窗调度全内联）；②`derivedStateOf` 误用（:115-124，key 已含 searchInput，等于白付开销）；③UI 150ms + VM 200ms **双层防抖**；④13 个阵营选项硬编码在 UI 层（:179-202）；⑤`filteredShips` 是 VM 第二条 StateFlow（GalleryViewModel.kt:131-146），与 uiState 双流注入 AppRoot（:825,837）。

**gallery/GalleryTopBar.kt（1066 行）**：`GallerySearchBar` 与 `ClassicGallerySearchBar` 约 80% 结构重复（两份 BasicTextField、两份清除按钮逐字同构）；文件头 1-81 行是整段复制的 ~40 个死 import；`isWatchScreen()` 内联分支 14 处；搜索历史持久化（SharedPreferences）写在 UI 层（:929-980）。

**gallery/GalleryFilterSheets.kt（408 行）**：两个筛选 Sheet **交互模型不一致**——舰娘 Sheet 是「草稿+应用」，学生 Sheet 是「即时生效」；头部与底部操作条在两 Sheet 中重复；重置按钮 enabled 联动行为不一致。

**ShipGalleryScreen.kt（521 行）**：立绘 HorizontalPager。债：①`downloadImage` 用 `URL(url).openStream()` 裸写公共 Downloads 目录（:94-122）——**API 29+ scoped storage 下是真实兼容性风险**，且无进度/取消；②「重试」按钮实际执行 onBack（:138）；③loading 是裸转圈（与其他页骨架屏不一致）；④圆点指示器无动画不可点无语义；⑤翻页 Log.d 调试残留。

**StudentGalleryScreen.kt（1412 行）**：图/表情/视频三 Tab。亮点：视频首帧 MediaMetadataRetriever + 缓存、ExoPlayer Referer 防盗链、横竖屏锁定 DisposableEffect、双风格 tab 切换动画。债：①`VideoPlayerOverlay` 333 行巨石（:1080-1412），**ExoPlayer 在 `remember(videoUrl)` 中创建**（:1122-1157）且无 ON_STOP 暂停监听——退到后台视频继续出声；②视频缩略图缓存无上限无 LRU（:108）；③downloadImage 与 ShipGallery 逐字重复（:202-241）；④600.dp 断点硬编码 ×2（:344,346）。

**SdResourceGalleryScreen.kt（755 行）**：**全 app 唯一无 ViewModel 界面**。债：①`deleteResource/renameResource` 在 onClick 主线程直接调磁盘 IO（:271,311）；②`listAll` 失败被吞成 emptyList()（:122-124）——错误态缺失，用户看到误导性「暂无 SD 资源」；③自绘空态 ×2 不用现成组件；④`skinDisplayNameSafe` 与 util 重复（:744-755，注释自认）。

### 3.2 语音组

**VoiceScreen.kt（1047 行）**：`VoiceScreenContent` **254 行巨型 Composable**，且把 4 个业务函数内嵌其中（downloadVoice 裸 HttpURLConnection 写公共目录 :177-214、shareVoice、toggleFavorite、addToPlayLater，全部 UI 层直调 Toast）。与 PlaybackService 的连接是规范的（VM 持有 PlaybackServiceConnection 单例，UI 不触碰 MediaController，listener 挂载用锁串行化）。**LazyColumn key 用 `index_` 前缀**（:353）——收藏置顶排序变化时整表 key 失效。错误态不完整：有缓存数据时错误被静默吞（:274 条件 `error != null && voices.isEmpty()`）；空态缺失。亮点：AGSL 背景 30fps 节流、共享元素转场、拖拽立绘 + 台词气泡动效精致。

**VoicePlayerBar.kt（652 行）**：底部播放器，折叠/展开 `updateTransition` 双形态 + 拖拽超屏宽 12% 折叠成球。债：进度由 VM 500ms 轮询驱动（滑杆精度 0.5s 级）；上一/下一首走 VoiceIntent、播/暂停走 PlayerViewModel **双命令通道**；播放大按钮常驻无限辉光（暂停时也在跑，:535-541）；拖拽过程无跟手位移。

**VoicePlayLaterSheet.kt（465 行）**：队列 Sheet。`PlayLaterQueueItem` 215 行四态巨石；28dp IconButton 低于 48dp 最小触控目标（:344,452）；文件头 97 行复制 import。

### 3.3 识舰娘组

**共享库 GuessGameComponents.kt（842 行）——全组设计系统标杆文件**：16 个共享件（ScoreChip/ScoreBanner/ErrorBanner/DifficultySelector 三段受光渐变+Depth 阴影/Hints/HintButton/ActionButton/InputField/三反馈卡/SettlementDialog/StatItem），文件头注释明确了收口原则。残余：小尺寸字面量 ~8 处、`String.format` 无 Locale（:787）、全文件无触觉（入口层做了）。

**GuessImageScreen / GuessVoiceScreen（各 ~500 行）——同构度 85%**：入口层（startGame/触觉/结算/退出二次确认，各 ~100 行，**连确认文案都一字不差**）与 Content 层（各 ~170 行）完全平行，仅题面区（ImageCard vs VoicePlayerCard）不同。12 个组件已共享，但**装配序列未共享**。近期竞态修复（勿破坏）：数据到位后若游戏已激活但无题目，`delay(250)` 补载（GuessShipViewModel.kt:144-160）；语音题目拉取失败换船重试最多 5 次（:283-307）。债：①`ImageCard` 中 `imageUrl == null` 一律渲染「正在加载」转圈（GuessImageScreen.kt:449-465）——**5 次重试耗尽后页面永远转圈**，加载与失败两种状态被合并；②「反复回放」按钮实际是随机换一条该舰娘的其他语音（GuessVoiceScreen.kt:183-187 vs 文案 :445），行为与文案不符；③播放失败静默（PlayerUiState.errorMessage 从未被消费）；④`shadow(16.dp)` 等绕过 AppElevation。

**GuessHistoryScreen.kt（790 行）**：`HistoryRecordCard` 228 行、`HistoryDetailDialog` 156 行两个巨石；7 个独立 collectAsState。**VM 状态发布缺陷（已实证）**：`uiState = combine(_stats, _records)`，而 `filter/selectedIds/isMultiSelectMode/detailRecord` 在 transform 内用 `.value` 快照读取（GuessHistoryViewModel.kt:173-189）——筛选能工作仅因 `_records = _filter.flatMapLatest{...}` 间接触发重发射，**多选勾选、详情弹窗的刷新靠 Room 恰好发射的巧合**。另外 VM 已聚合完整的 HistoryStats 统计，UI 却从未渲染（死数据）；`deleteAll()` 无 UI 入口。

### 3.4 啾信组

**ConversationListScreen.kt（1553 行，全 app 最大界面文件）**：JUUSTAGRAM 风格。`ConversationListScreen` 本体 345 行（10 个 remember + LazyColumn 内联拖拽手势 + 派生缓存）；`JuusNewChatSheet` 与 `JuusNewGroupSheet` ~150 行结构重复；`JuusApiConfigRow/JuusPersonaConfigRow` 与 config/ConfigCards 三卡两套同语义实现；自绘 `formatSessionPreview` 与 ChatCommon 的 `formatSessionTime` 语义重复。债：①`orderedList = mutableStateListOf(*conversations)` 双数据源 + LaunchedEffect 同步（:222-228），拖拽回滚依赖闭包快照，时序脆弱；②76.dp 行高假设与实际 item 高度不精确相等，拖拽换位漂移（:232）；③色彩纪律极好（JuusPalette 归口，0 处 Color(0x)，但 `if (isDark)` 三元 34 处）。

**JiuxinChatScreen.kt（713 行）**：`JiuxinChatScreen` **单函数 535 行**，内含 4 个滚动控制 LaunchedEffect（进场两帧 scrollToItem、shouldAutoScroll 离底判定、消息数变化 animateScroll、IME debounce(280)——**这套滚动防抖是多次调参的成果，重构前必读行内注释**）+ **9 个弹层/对话框平铺函数尾部**（:526-712）。**真缺陷（已实证）**：VM 有完整的 `markMessageFailed/retryMessage` 状态机（JiuxinViewModel.kt:2091-2111），但 `MessageBubble`（ChatBubbles.kt:276-574）**完全不渲染 message.status，无重试入口**——失败消息在 UI 上不可见不可重试。弹窗视觉不统一（Material AlertDialog ×4 vs 自绘玻璃 Dialog ×2）。

**chat/ 子包（6 文件 2256 行）**：`MessageBubble` 320 行五分支巨石，四个气泡分支骨架 80-90 行逐字重复（可抽 BubbleScaffold 消 ~250 行）；`JuusColors` 是 JuusPalette 的全量二次别名层（三层令牌：调色板→别名→UI，应决策删层）；`UserConfigDialog/AvatarPickerSheet/BackgroundPickerSheet` **签名直接接收 JiuxinViewModel**，VM 穿透到叶子组件（+ConfigDialogs.VoiceShipPickerSheet 共 4 处）；`ChatInputBar` 固定 40dp 单行是刻意的防抖设计（:256-260 注释），focusRequester 死代码（:175）。

**JiuxinConfigScreen.kt（729 行）+ config/ 子包（1651 行）**：两级状态机（主菜单→分区）。**9 个 AlertDialog 全部内联**，其中三个「保存命名」对话框结构 90% 相同（~240 行可收敛为参数化对话框）；`showClearPersonaConfirm` 与 `showClearMemoryConfirm` 两个几乎同义的确认状态并存；`ApiSection`（238 行）/`PersonaSection`（294 行，**29 个参数**）参数爆炸；`ModelListState` 五态下拉与 JiuxinShipConfigScreen.kt:309-383 **逐行级重复实现两遍**；语音关键词→场景映射说明文案与 `JiuxinVoiceTagEngine` 实际映射表双源（改引擎必忘改文案）。本文件令牌纪律全 app 最佳（裸 dp 仅 1 处）。

**JiuxinShipConfigScreen.kt（685 行）**：API 选择以 url+key+model 三元组全值匹配判定选中态（:245-248，相同值的两个配置无法区分）；`ShipAvatarDisplay` 与 RobustAvatar 功能重叠并行存在；169-229 缩进错乱。

**AssistantScreen（736 行）+ AssistantConfigScreen（348 行）**：**与啾信无功能重叠**——是「碧蓝航线小助手」数据查询页（查玩家/查建造），仅共享组件库。AssistantViewModel 是标准 MVI 范本（单 state + sealed Intent）。问题：命名易混淆（Assistant vs 啾信助手）；AssistantConfigScreen 用 SettingsViewModel 而非 AssistantViewModel（配置与消费跨 VM）；渐变边框逻辑两处重复。

### 3.5 秘书舰组

**SecretaryShipModeScreen.kt（1373 行）——根本不是一个 Screen，是 2 个 Screen + 12 个私有组件塞一个文件**：Mode 屏（104-259）、Settings 屏（441-595，被独立路由使用）、SD 资源管理块（858-1188，含 SAF `OpenDocumentTree` launcher 与 `SdResourceOrganizer.organize` 协程）。**架构债全组最重**：①`organizeProgress/organizeResult/isOrganizing` 全是 Composable 内 `remember`（:866-867）——**旋转屏即丢**，且 UI 直接跑 IO 协程；②`LocalSdResolver/SDResourceManager` 全局单例可变状态被 UI 直接订阅（:654,871,874）；③悬浮窗开关走 MainActivity 静态态绕过 VM；④radialGradient 圆徽章样板重复 5 次（:310-335,393-410,758-771,932-952,1222-1247）。

**SecretaryShipPickScreen.kt（407 行）**：两个薄包装 + 私有 SearchAndFilterBar（与闲置 BlyySearchBar 同构）；筛选三件套与 GalleryScreen 筛选能力重复实现；**无 VM**，筛选状态全 remember，阵营/舰种/稀有度硬编码中文列表；129-197 缩进错乱；`onLongClick = {}` 空实现。

**SecretaryShipRandomScreen.kt（283 行）**：四态状态机 + 翻牌动画。是唯一 MVI 直连 VM 的 Secretary 屏（范本）。债：「翻转」实为无限旋转非真翻面；`revealed` UI 状态与 VM `isFlipping` 双状态源；800/600/1500ms 动效字面量未走 AppAnimation.Duration；未用 import 4 个。

### 3.6 系统与工具组

**SettingsScreen.kt（325 行）**：6 个 SectionPanel + 私有 `SettingsNavigationRow`（双风格分支，与 BlyyListItem/SettingsEntryCard 三者同构）；SettingsViewModel 散 flow 模式；缩进错乱。

**AboutScreen.kt（600 行）**：MVI 消费范本（AboutViewModel）。债：`UpdateChannelCard`（:537-600）与 AppChrome 的 `UpdateChannelOption`（:1134-1222）**几乎逐行同构**（注释自己承认）；Toast 直用而项目里有闲置的 BlyySnackbarHost。

**IconSettingsScreen.kt（502 行）**：图标预览 SAFE_ZONE_RATIO 缩放 trick + Coil 缓存键破缓存 + Photo Picker 跳裁剪，工程质量好。债：私有 `Dp.toPx()` 扩展重复造轮子且在 Brush radius 里误用；与 ImageCropper 共用 SettingsViewModel 使其承担「设置+图标+裁剪」三职。

**LeaderboardScreen.kt（440 行）**：BlyyTabRow 唯一真实消费者之一；两级 TTL 缓存全在 VM（内存 5min/本地 1h）。债：wildcard import；`FilledTonalButton`/`Card` 绕开 Blyy 体系；每 RankCard 内 remember SimpleDateFormat。

**WatermarkCameraScreen.kt（481 行）**：相机**入口页**（刻意不集成 CameraX，委托系统相机 + FileProvider，取消时清理临时文件——设计合理）。债：水印选择用 raw `ModalBottomSheet` 而非 BlyyBottomSheet；黑底白字体系 17 处 Color.White/Black 脱离语义色；`FloatingControl/IconButtonCircle` 又一对重复圆钮。

**WatermarkEditorScreen.kt（555 行）**：**手势数学全 app 最健壮**（awaitEachGesture 105 行：高刷播种、指针数变化防跳变、锚点缩放、边界 clamp）但整段内嵌 Composable，且 `EditorPreviewArea/WatermarkPreview` **向下传 viewModel** 直接调 `viewModel::applyGestureTransform`（:203-206,252-258）；三分线 Color.White 硬编码；手势数学应抽纯函数便于测试。

**ImageCropperScreen.kt（189 行）**：三套缩放手势之一；`LaunchedEffect` 解码 bounds 仅日志用途（:81-88，纯浪费一次 IO）。

### 3.7 全局组件

**AppRoot.kt（897 行）**：见 §1.2。债：`showBottomBar` 20 条字符串前缀条件应数据化；Secretary 域 props drilling；`MainActivity.overlayState` 静态流两处 collect（:772,851）；导航转场 spring 内联注释已说明原因（保留）。

**ZoomableImage.kt（209 行）**：手写时间戳双击检测（:162-183）不如 detectTapGestures 可靠；缩放状态 model 变化时不重置（当前调用点每页新建实例未爆雷，复用时会）；无惯性 fling。

**RobustAvatar.kt（230 行）**：**真缺陷（已实证）**：`Box(modifier = modifier)`（:206）与内部 `AsyncImage(modifier = modifier)`（:211）**同一个 modifier 双重应用**——尺寸/裁剪被施加两次；`contentDescription = null` 硬编码无 a11y 入口；~160 行无关死 import（从聊天界面拆出时的残骸）。

---

## 4. 跨界面共性问题（按危害排序）

1. **巨型 Composable 二次膨胀**（≥200 行 12 个）：JiuxinChatScreen 535、GalleryScreen 372、VideoPlayerOverlay 333、MessageBubble 320、ConversationListScreen 本体 345、VoiceScreenContent 254、PersonaSection 294、HistoryRecordCard 228、PlayLaterQueueItem 215、ApiSection 238、ShipCard 248、JuusConversationItem 195。共性病因：弹窗群内联、装配序列不抽象、业务逻辑（IO/格式化/持久化）下沉不及时。
2. **状态缺失/状态混淆**：Home 错误态未消费、ShipGallery 重试=返回、SdResource 错误吞成空态、Voice 有缓存时错误静默+空态缺失、GuessImage 加载与失败合并为永久转圈、失败消息不可见不可重试、HistoryStats 聚合了却不渲染。
3. **VM 模式分裂**：MVI/散 flow/无 VM/贫血双传四种并存；一次性事件四种机制；GalleryViewModel 双流注入； Secretary 域 props drilling；4 个组件签名收 VM；SettingsViewModel 一拖四。
4. **同一件事 N 套实现**：见 §2.3 并存总表（导航行 ×8、卡片 ×3、按钮 ×3、对话框 ×3、搜索 ×3、手势 ×3、骨架 ×3、空态 ×5+、头像 ×2、下载 ×3）。
5. **令牌旁路**：颜色纪律好（Color(0x) 为 0），但裸 dp ~250 处、`Color.White/Black.copy(alpha)` 遮罩体系（Voice/PlayerBar/相机/裁剪约 40 处）无 scrim 语义色、`if (isDark)` 三元 60+ 处（ConversationList 34、ChatBubbles 26）、裸 tween/spring ~15 处、动效字面量 ms（Random 屏 800/600/1500/2000）。
6. **LazyColumn 卫生**：VoiceScreen key 用 index 前缀（不稳定）、HistoryRecordCard 无 animateItemPlacement、PlayLater/History 的 28/32dp IconButton 触控目标不足、语音缩略图缓存无上限。
7. **性能热点**：ShipCard 7 个无限动画/卡、Home AGSL 无誓约时空转、ExoPlayer remember 中创建 + 后台不暂停、播放大按钮常驻辉光。
8. **死代码与残骸**：4 个 0 调用组件、3 个文件头复制 import 块（GalleryTopBar/FilterSheets 各 ~40 个、RobustAvatar ~160 行、AppChrome ~40 个）、RandomScreen 4 个未用 import、ChatInputBar focusRequester、ImageCropper 调试解码、翻页 Log.d。
9. **可访问性**：折叠播放球、语言切换按钮、LanguageButton 等可点元素无语义描述；编辑模式对屏幕阅读器无状态播报；圆点指示器无语义。
10. **一致性细节**：筛选 Sheet 草稿 vs 即时两种心智模型；弹窗 Material vs 自绘玻璃两种视觉；同名工具函数双份；Locale 不一致；缩进错乱三处（JiuxinShipConfigScreen:169-229、Pick:129-197、Settings:96-223）。

---

## 5. 缺陷清单（重构前须知的真 Bug）

| # | 级别 | 缺陷 | 位置 |
|---|---|---|---|
| B1 | **P0** | 多选/详情/筛选状态在 VM 中用 `.value` 快照读取，不触发 uiState 重发射——多选勾选、详情弹窗刷新靠 Room 发射的巧合 | GuessHistoryViewModel.kt:173-189 |
| B2 | **P0** | 失败消息不可见不可重试（VM 状态机完整，UI 未渲染 status） | ChatBubbles.kt:276-574 vs JiuxinViewModel.kt:2091-2111 |
| B3 | **P0** | SD 资源删除/重命名在主线程执行磁盘 IO；listAll 失败被吞成误导性空态 | SdResourceGalleryScreen.kt:271,311,122-124 |
| B4 | **P0** | 三处 `URL.openStream()` 裸写公共 Downloads 目录——API 29+ scoped storage 兼容性风险 | ShipGalleryScreen.kt:94-122、StudentGalleryScreen.kt:202-241、VoiceScreen.kt:177-214 |
| B5 | **P1** | ExoPlayer 在 remember(videoUrl) 中创建 + 无 ON_STOP 暂停——退后台继续出声 | StudentGalleryScreen.kt:1122-1157 |
| B6 | **P1** | LazyColumn key 含 index 前缀，收藏置顶时整表 key 失效 | VoiceScreen.kt:353 |
| B7 | **P1** | 题目「加载中」与「加载失败」合并渲染，重试耗尽后永久转圈 | GuessImageScreen.kt:449-465 |
| B8 | **P1** | RobustAvatar modifier 双重应用；contentDescription 恒 null | RobustAvatar.kt:206,211,210 |
| B9 | **P1** | 「反复回放」实为随机换曲，文案与行为不符 | GuessVoiceScreen.kt:183-187,445 |
| B10 | **P1** | HomeScreen.state.error 从未消费；ShipGallery 重试按钮执行的是返回 | HomeScreen.kt:264-268、ShipGalleryScreen.kt:138 |
| B11 | **P2** | 拖拽排序双数据源 + 76dp 行高假设漂移 | ConversationListScreen.kt:222-232 |
| B12 | **P2** | API 配置选中态三元组全值匹配，同值配置无法区分 | JiuxinShipConfigScreen.kt:245-248 |
| B13 | **P2** | 视频缩略图缓存无上限；ShipCard placeholder 显示碎图图标 | StudentGalleryScreen.kt:108、ShipCard.kt:413 |
| B14 | **P2** | ShipGallery/StudentGallery 的 state+viewModel 双传屏，配置变更时 UI 状态归属不清 | ShipGalleryScreen.kt:57-58 等 |

---

## 6. 重构路线图

> 原则：**先止血（bug）、再收敛（组件/令牌）、后拆解（结构）、终统一（模式）**。每阶段独立可发布、可回滚；阶段内任务按「纯增量 > 移动 > 等价重写 > 行为变更」排序。

### 阶段 0：缺陷止血（预计 2-3 天，全部是行为修复，不动结构）

| 任务 | 做什么 | 验收 |
|---|---|---|
| T0.1 | GuessHistoryViewModel：把 `_filter/_selectedIds/_isMultiSelectMode/_detailRecord` 并入 uiState 的 combine 上游（B1） | 多选勾选即时高亮；详情弹窗开关可靠 |
| T0.2 | MessageBubble 渲染 message.status==FAILED + 重试入口（复用 VM retryMessage）（B2） | 断网发消息→气泡标红→点重试成功 |
| T0.3 | 新建 `MediaDownloader`（MediaStore 实现）替换三处 URL.openStream，带进度 Toast/通知（B4） | Android 14 实机下载立绘/语音/视频成功入相册 |
| T0.4 | SdResourceGallery：delete/rename 包 Dispatchers.IO + loading 态；listAll 失败走错误态（B3） | 删除大目录不卡顿；断权限时显示错误而非空态 |
| T0.5 | 识舰娘 ImageCard 区分加载/失败两态，失败给重试（B7）；「回放」改为真重播或改文案（B9） | 断网时不再永久转圈 |
| T0.6 | ExoPlayer 移出 remember、加 ON_STOP 暂停（B5）；VoiceScreen key 改 `voice.scene+url`（B6） | 退后台无声；收藏置顶后列表动画正常 |
| T0.7 | RobustAvatar 修 modifier 双应用 + 暴露 contentDescription 参数（B8） | 头像尺寸不翻倍；TalkBack 可读 |
| T0.8 | Home 错误态渲染 + ShipGallery 真重试（B10）；ShipCard placeholder 换中性占位（B13 后半） | 断网首页下拉刷新有错误提示 |

### 阶段 1：组件库收敛与令牌收编（预计 1-1.5 周，纯结构，无行为变化）

| 任务 | 做什么 | 消灭量 |
|---|---|---|
| T1.1 | **激活 `BlyyScreenScaffold`**（补齐滚动隐藏/底部避让参数），收编 35 处 `AdaptiveScreenBackground+Column+BlyyTopBar` 三件套 | ~700 行 |
| T1.2 | **激活/增强 `BlyyListItem`**（加 accentColor/isWatch 变体），替换导航行家族 6 个私有实现 + SettingsNavigationRow + SettingsEntryCard | ~400 行 |
| T1.3 | `GalleryScaffold` 四态渲染器（loading/error/empty/data + 网格脚手架 + 骨架选择），统一 4 个画廊/图鉴屏与 VoiceScreen 的状态分支；统一三种 loading 与三种重试语义 | ~350 行 |
| T1.4 | 搜索框三合一（GallerySearchBar/ClassicGallerySearchBar → SearchInputCore + 样式参数）；SdResource 搜索换 BlyySearchField；Pick 屏换 BlyySearchBar | ~200 行 |
| T1.5 | 筛选 Sheet 抽 SheetHeader/SheetActionBar；统一「草稿+应用」心智（学生 Sheet 迁移）；config 三张卡抽 ConfigEntityCard；啾信两个 NewChatSheet 合并；9 个内联 AlertDialog 收敛为参数化 SaveNameDialog/ConfirmDialog | ~800 行 |
| T1.6 | 下载/格式化工具归口：MediaDownloader（阶段 0 已建）+ openWiki + ToggleFavorite + skinDisplayName + formatSessionTime 单实现 | ~150 行 |
| T1.7 | 死代码清理：删 BlyyCard/BlyyMetricCard/BlyySuccessBanner/BlyyDialog（0 调用）、5 个文件死 import 块、RandomScreen 未用 import、focusRequester、调试 Log.d/bounds 解码；GameStyles 并入 AppSpacing.Game | ~400 行 |
| T1.8 | 令牌收编三批：①间距→AppSpacing（~250 处裸 dp 按图标/头像/组件尺寸分批）；②遮罩黑/白→语义色 scrim/onScrim（SemanticColors 扩展）；③裸 tween/spring/ms 字面量→AppAnimation；`isWatchScreen()` 内联分支下沉为尺寸令牌；Color.kt 补 Classic scheme 缺失的 surfaceContainerLowest/Low | 令牌覆盖率 →95% |
| T1.9 | 手势统一：以 WatermarkEditor 实现为源抽 `rememberPanZoomState()`（纯函数数学 + 测试），替换 ImageCropper 与 ZoomableImage | 3 套→1 套 |

### 阶段 2：巨型 Composable 拆解与结构重组（预计 2-3 周，先移动后重写）

| 任务 | 拆什么 → 拆成什么 | 关键约束 |
|---|---|---|
| T2.1 | SecretaryShipModeScreen.kt（1373 行）→ mode/、settings/ 两个文件 + `SdResourceManageSection` + **新建 SecretarySdViewModel**（SAF 状态入 VM，修旋屏丢失）；悬浮窗开关封装 `rememberOverlayController()` | Secretary 状态接入统一为 hiltViewModel 直连（Random 屏为范本），消 AppRoot 10+ 回调 props drilling |
| T2.2 | JiuxinChatScreen（535 行）→ 9 弹层改状态驱动 `when(activeDialog)` 分发 + 拆 ChatDialogs.kt；ConversationListScreen 本体拆 LazyContent/ DragController；JiuxinConfigScreen 拆三分区文件 | **见 §7 红线 1-3**；滚动防抖逻辑原样移动 |
| T2.3 | MessageBubble 抽 BubbleScaffold（四分支骨架合一）；ChatBubbles 消 26 处 isDark 三元（JuusColors 改提供 light()/dark() 单点取色或 CompositionLocal） | 气泡视觉逐像素不变 |
| T2.4 | VoiceScreenContent（254 行）拆分组列表组件 + downloadVoice/shareVoice 等 4 个业务函数下沉 VM/领域层；PlayLaterQueueItem 按四态拆子组件；HistoryRecordCard 拆头像/统计/操作区；HistoryStats 统计卡补渲染 | 语音页行为不变 |
| T2.5 | VideoPlayerOverlay（333 行）拆 `rememberVideoPlayer()` 封装 + 播放/手势/错误映射分层；视频缩略图缓存改 LruCache(24) | 修 B5 于阶段 0，此处只拆结构 |
| T2.6 | 识舰娘两屏合并：`GuessGameScaffold(difficultySelector, questionArea: @Composable, feedback..., ...)` slot 模板 + 共享结算/退出状态机，两屏 Content 合一 | 共享组件库 guess/ 原则延续；竞态修复代码勿动 |
| T2.7 | GalleryScreen（372 行）拆 SearchSection/TopBarController/FilterOptions；删无效 derivedStateOf；双层防抖去一层；阵营选项列表移入数据层；GalleryViewModel.filteredShips 并回单一 state 流 | 搜索手感（150ms 防抖）不变 |
| T2.8 | VM 收口：PersonaSection/ApiSection 29/23 参数改传 data class；4 个收 VM 的弹层组件改纯回调；Pick 屏补 VM；PersonalitySection 同义确认状态合并 | **见 §7 红线 1** |

### 阶段 3：动效、性能与可访问性打磨（预计 1 周）

| 任务 | 内容 |
|---|---|
| T3.1 | ShipCard 动画预算：RarityGlow/OathSpecialEffect/FavoriteBadgeAnimated 合并为单一 drawBehind 驱动（≤2 无限动画/卡），滚动降级机制保留 |
| T3.2 | Home AGSL Canvas 在 oathIntensity==0 时跳过重绘；播放大按钮辉光仅在播放中运行 |
| T3.3 | 列表动效补全：History 卡 animateItemPlacement、ShipGallery 圆点指示器 animateDpAsState + 可点跳页 + 语义 |
| T3.4 | 触控目标：PlayLater/History 的 28/32dp IconButton 提至 48dp 热区（minimumInteractiveComponentSize） |
| T3.5 | a11y：补齐折叠播放球/语言切换/手势区语义；编辑模式 stateDescription；ZoomableImage 手势区 semantics |
| T3.6 | 弹窗视觉统一：Material AlertDialog vs 自绘玻璃 Dialog 二选一（建议保留自绘玻璃，向 BlyyDialog 体系靠）；Toast 全量迁移至 Snackbar/事件通道（可选，量大放最后） |
| T3.7 | Locale 修复（String.format 补 Locale）、三处缩进错乱整理、JuusColors 别名层去留决策落地 |

### 每阶段验收标准

1. `gradlew assembleDebug` 零警告级通过（遇 Unresolved reference 且报错文件在构建间波动 → 先删 `app/build/kotlin` 再判断，是增量缓存损坏的已知症状）；
2. 阶段 0/1 在模拟器逐屏点检（注意模拟器坐标空间与真机差异），阶段 2/3 附前后截图对比；
3. T2.6/T1.3 等涉及纯逻辑抽取的任务配 JVM 单测；
4. 每任务独立 commit，遵循「纯移动不改行为」与「行为修复分开提」的既往惯例（feat/refactor/fix 前缀，功能名用「啾信」）。

---

## 7. 红线与不变量（重构时必须保护）

1. **啾信存储原子性**：人格/预设/API 配置是整表 JSON 单 key 存储，任何 UI 重构都必须继续经由 VM 的 `updateAiApiConfigs/updateAiPersonaConfigs/updateAiJiuxinPresets` 原子 `edit{}` transform——**禁止在 UI 层读 StateFlow 快照后整表写回**。
2. **长期记忆锚**：记忆编辑/清空只能走 `savePersonaMemoryText/clearPersonaMemory`，不得绕过 `summarizedCount/summarizedLastTs` 进度锚另写字段（JiuxinConfigScreen 的记忆卡片是唯一 UI 入口）。
3. **Activity 级 ViewModel 共享**：聊天页与会话列表共享同一 JiuxinViewModel（`findActivityViewModelStoreOwner`，JiuxinChatScreen.kt:182-191 有长注释）——拆分文件时不得改作用域；退出聊天时 `saveCurrentSessionMessages(updateTimestamp=false)` 的 DisposableEffect 语义不得丢失。
4. **聊天滚动防抖**：进场两帧 scrollToItem、shouldAutoScroll 离底 3 项判定、消息数变化 animateScroll、IME debounce(280)——是多次调参成果，拆解时原样移动并保留行内注释。
5. **识舰娘竞态修复**：GuessShipViewModel 的 `delay(250)` 补载（:144-160）与语音题目 5 次换船重试（:283-307）是刚修的 bug，重构装配序列时保留其语义。
6. **结算落库**：识舰娘退出确认后 `confirmExitAndSave()` 走 NonCancellable + sessionId upsert 防重（GuessShipViewModel.kt:708-746）。
7. **悬浮窗服务**：MainActivity.overlayState + startOverlayService 的通路与 SecretaryChibiOverlay 双模式（悬浮窗/应用内互斥显示，AppRoot.kt:849-869）不得在 T2.1 中被破坏。
8. **播放器连接**：UI 层不直接触碰 MediaController（PlaybackServiceConnection 单例 + listener 锁串行化），T2.4 下沉业务函数时保持该边界。
9. **git 卫生**：scripts/、source/、test_screenshots/ 等本地产物不入暂存区（已在 .gitignore），只 add 具体改动文件。

---

## 8. 附录：重构收益预估

| 维度 | 现状 | 目标 |
|---|---|---|
| ui/ 总行数 | 35,030 | ~30,000（阶段 1-2 预计净删 4,000-5,000 行重复/死代码） |
| ≥200 行 Composable | 12 个 | ≤4 个（且为有意的装配根） |
| 组件并存 | 10 个领域 ×2-8 套 | 每领域 1 套 + 明确弃用件删除 |
| VM 模式 | 4 种并存 | MVI 标准化（AssistantViewModel 为范本），一次性事件统一通道 |
| 真缺陷 | 14 项（§5） | 阶段 0 清零 P0/P1 |
| 令牌覆盖率 | 颜色 100% / 尺寸 ~70% | ≥95% |
