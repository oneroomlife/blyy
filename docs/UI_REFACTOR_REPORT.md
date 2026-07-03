# UI 重构方案与对比分析报告

> 项目：碧蓝航线语音应用 (blyy)
> 日期：2026-06-22
> 范围：UI 设计诊断、技术架构升级、动效系统、性能优化

---

## 一、项目概况

### 技术栈
- **UI 框架**：Jetpack Compose + Material Design 3
- **架构**：MVVM + Hilt 依赖注入 + Navigation Compose
- **设计系统**：双 UI 风格（Command Center HUD 风格 / Classic Material 风格）
- **图片加载**：Coil (AsyncImage / SubcomposeAsyncImage)
- **页面规模**：17 个 Screen、9 个组件库、13 个主题文件

### 设计系统现状
项目已建立较成熟的设计系统，包含：
- `AppColors` / `AppTypography` / `AppSpacing` / `BlyyShapes` / `AppAnimation` / `AppElevation` 六大 token 体系
- `BlyyTopBar` / `BlyyPanel` / `BlyyPrimaryButton` / `BlyyChip` / `BlyyEmptyState` / `BlyyErrorState` / `BlyyLoadingState` / `BlyyConfirmDialog` / `BlyyBottomSheet` 等 22+ 统一组件
- `LocalIsDark` / `LocalUiStyle` / `LocalSemanticColors` CompositionLocal 体系

---

## 二、审计发现

通过 4 个并行搜索代理对全项目进行系统审计，识别出以下四类问题：

### 2.1 硬编码违规
| 类型 | 数量 | 典型示例 |
|------|------|---------|
| 硬编码 dp 值 | 250+ | `Modifier.size(64.dp)` → `AppSpacing.Icon.Massive` |
| 硬编码 FontWeight | 125+ | `fontWeight = FontWeight.Bold` → `AppTypography.TitleMediumBold` |
| 硬编码 RoundedCornerShape | 40+ | `RoundedCornerShape(28.dp)` → `BlyyShapes.DialogClassic` |
| 硬编码 Color | 30+ | `Color(0xFF2C2C2E)` → 设计系统色值 |
| 硬编码 easing | 15+ | `FastOutSlowInEasing` → `AppAnimation.Easings.Standard` |

### 2.2 性能问题
| 问题 | 位置 | 影响 |
|------|------|------|
| ShipCard 装饰动画在 HomeScreen 滚动时未关闭 | HomeScreen.kt | 滚动卡顿、GPU 负载高 |
| 列表 items 缺少 key 参数 | VoiceScreen / AssistantScreen | 重组效率低，动画错位 |
| 冗余 derivedStateOf | VoiceScreen.kt:152 | 不必要的 remember 开销 |
| luminance() 重复计算 | GuessImageScreen / GuessVoiceScreen | 每帧颜色计算 |
| AsyncImage 阻塞布局 | ShipCard.kt | 列表滚动掉帧 |
| ImageRequest 每次重组重建 | ShipGalleryScreen.kt | 内存抖动 |

### 2.3 动效不一致
- 部分 Screen 直接 import Compose 原生 easing（`FastOutSlowInEasing`），未使用 `AppAnimation.Easings`
- 对话框/底部弹窗缺少统一形状 token（`BlyyShapes.DialogClassic` / `BottomSheetClassic` 缺失）
- 聊天气泡形状散落在各 Screen 自行实现

### 2.4 组件复用不足
| 重复实现 | 涉及文件 | 统一组件 |
|---------|---------|---------|
| 自定义空/错/加载状态 | VoiceScreen / ShipGalleryScreen / GuessHistoryScreen | BlyyEmptyState / BlyyErrorState / BlyyLoadingState |
| AlertDialog 直接使用 | 10 处（6 个文件） | BlyyConfirmDialog / BlyyDialog |
| ModalBottomSheet 直接使用 | 7 处（5 个文件） | BlyyBottomSheet |
| GuessImage/GuessVoice 重复组件 | 8 对近完全相同函数 | 待提取为共享组件 |

---

## 三、重构实施

### 阶段 0：补齐设计 Token 缺口

**目标**：消除调用方 `.copy()` 和硬编码值的根本原因。

| 文件 | 变更 |
|------|------|
| `Spacing.kt` | `Corner` 新增 `Xs2=14.dp`、`Dialog=28.dp`；`Icon` 新增 `Md2=18.dp`、`Xl1=22.dp`；`Avatar` 新增 `Lg1=44.dp`；`Figure` 新增 `Banner=70.dp` |
| `Type.kt` | 新增 8 个字重变体：`TitleMediumBold`、`TitleSmallMedium`、`LabelLargeBold`、`LabelMediumBold`、`LabelSmallBold`、`LabelSmallMedium`、`BodyMediumMedium`、`BodySmallMedium` |
| `Shape.kt` | `BlyyShapes` 新增 `DialogClassic`(28dp)、`BottomSheetClassic`(24dp)、`ChatBubbleReceived`、`ChatBubbleSent` |
| `Animation.kt` | `Easings` 新增 `DecelerateIn`、`AccelerateOut`、`EaseInOutSine` |

### 阶段 1：P0 性能修复

| 修复项 | 文件 | 方案 |
|--------|------|------|
| ShipCard 装饰动画滚动时关闭 | HomeScreen.kt | `derivedStateOf` 监听 `LazyGridState.isScrollInProgress`，传入 `decorativeAnimation` 参数 |
| 列表 key 缺失 | VoiceScreen.kt / AssistantScreen.kt | `itemsIndexed(..., key = { _, item -> item.voiceUrl })` |
| 冗余 derivedStateOf | VoiceScreen.kt:152 | 直接使用 `playerState.favorites` |
| luminance() 重复计算 | GuessImageScreen / GuessVoiceScreen | 替换为 `LocalIsDark.current` |
| AsyncImage 阻塞布局 | ShipCard.kt | 改用 `SubcomposeAsyncImage` + `crossfade(true)` + loading 占位 |
| ImageRequest 重建 | ShipGalleryScreen.kt | `remember(imageUrl) { ImageRequest.Builder(...).build() }` |

### 阶段 2：P0 组件复用迁移

#### 2.1 状态组件统一（5 处替换）

| 原始组件 | 文件 | 替换为 |
|---------|------|--------|
| `ErrorStateView` | VoiceScreen.kt | `BlyyErrorState` |
| `LoadingStateView` | VoiceScreen.kt | `BlyyLoadingState` |
| `ErrorState` | ShipGalleryScreen.kt | `BlyyErrorState` |
| `EmptyState` | ShipGalleryScreen.kt | `BlyyEmptyState` |
| `EmptyHistoryView` | GuessHistoryScreen.kt | `BlyyEmptyState` |

> HomeScreen `EmptyFavoritesView` 保留 — 为高级动画空状态（PremiumFloatingArtwork + 渐变文字 + 错落入场），不宜降级为基础组件。

#### 2.2 对话框/底部弹窗统一（9 处替换）

| 类型 | 原始 | 文件 | 替换为 |
|------|------|------|--------|
| 确认对话框 | `AlertDialog` | JiuxinChatScreen.kt (删除确认) | `BlyyConfirmDialog` (isDestructive=true) |
| 确认对话框 | `AlertDialog` | GuessVoiceScreen.kt (退出确认) | `BlyyConfirmDialog` |
| 确认对话框 | `AlertDialog` | GuessHistoryScreen.kt (配置提示) | `BlyyConfirmDialog` |
| 底部弹窗 | `ModalBottomSheet` | VoiceScreen.kt (播放队列) | `BlyyBottomSheet` |
| 底部弹窗 | `ModalBottomSheet` | JiuxinChatScreen.kt (历史对话) | `BlyyBottomSheet` |
| 底部弹窗 | `ModalBottomSheet` | JiuxinChatScreen.kt (头像选择) | `BlyyBottomSheet` |
| 底部弹窗 | `ModalBottomSheet` | JiuxinConfigScreen.kt (头像选择) | `BlyyBottomSheet` |
| 底部弹窗 | `ModalBottomSheet` | JiuxinConfigScreen.kt (语音舰娘) | `BlyyBottomSheet` |
| 底部弹窗 | `ModalBottomSheet` | SecretaryShipPickScreen.kt (筛选) | `BlyyBottomSheet` |

> GalleryScreen 的 `ModalBottomSheet` 保留 — 使用自定义 `glassSurface` 毛玻璃效果，BlyyBottomSheet 不支持自定义容器色。

#### 2.3 GuessImage/GuessVoice 重复组件分析

识别出 8 对近完全相同的函数，仅 `primary` / `secondary` 颜色差异：

| 函数对 | 相似度 | 建议提取方式 |
|--------|--------|-------------|
| `WrongAnswerCard` / `VoiceWrongCard` | 完全相同 | 直接提取 |
| `ScoreChip` / `VoiceScoreChip` | 近完全相同 | 增加 `accentColor` 参数 |
| `ScoreBanner` / `VoiceScoreBanner` | 近完全相同 | 增加 `accentColor` 参数 |
| `ModernPrimaryButton` / `ModernVoicePrimaryButton` | 近完全相同 | 增加 `accentColor` 参数 |
| `ModernOutlinedButton` / `ModernVoiceOutlinedButton` | 近完全相同 | 增加 `accentColor` 参数 |
| `ModernInputField` / `VoiceInputField` | 近完全相同 | 增加 `accentColor` 参数 |
| `StatItemContent` / `VoiceStatItemContent` | 近完全相同 | 增加 `accentColor` 参数 |
| `ModernSettlementDialog` / `ModernVoiceSettlementDialog` | 近完全相同 | 增加 `accentColor` 参数 |

> 建议创建 `ui/components/GuessGameComponents.kt`，将上述函数提取为共享组件，通过 `accentColor` 参数区分主题色。实现延后至下一迭代。

---

## 四、优化前后对比

### 4.1 视觉一致性

| 维度 | 优化前 | 优化后 |
|------|--------|--------|
| 空状态 | 5 种不同实现（不同图标尺寸、颜色、间距） | 统一 `BlyyEmptyState`（72dp 图标圈 + TitleMedium + BodyMedium） |
| 错误状态 | 3 种不同实现（不同图标、按钮文字） | 统一 `BlyyErrorState`（Warning 图标 + BlyyPanel 容器 + "重试"按钮） |
| 确认对话框 | 直接 `AlertDialog`（无风格适配） | `BlyyConfirmDialog`（Command Center 切角 / Classic 圆角自动切换） |
| 底部弹窗 | 直接 `ModalBottomSheet`（形状/手柄不一致） | `BlyyBottomSheet`（统一拖拽手柄 + 风格自适应形状） |

### 4.2 性能

| 指标 | 优化前 | 优化后 |
|------|--------|--------|
| HomeScreen 滚动帧率 | 装饰动画持续运行导致掉帧 | `derivedStateOf` 监听滚动状态，滚动时自动暂停 |
| VoiceScreen 列表重组 | 无 key，全量重组 | `key = voiceUrl`，增量重组 |
| ShipCard 图片加载 | `AsyncImage` 阻塞布局 | `SubcomposeAsyncImage` + loading 占位 |
| ShipGalleryScreen | `ImageRequest` 每次重组重建 | `remember(imageUrl)` 缓存 |
| GuessImage/Voice 亮度计算 | 每帧 `luminance()` 计算 | `LocalIsDark.current` 零开销 |

### 4.3 代码质量

| 指标 | 优化前 | 优化后 |
|------|--------|--------|
| 自定义状态组件 | 5 处重复实现（~150 行） | 0 处（统一引用组件库） |
| 直接 AlertDialog 使用 | 10 处 | 7 处（3 处替换为 BlyyConfirmDialog） |
| 直接 ModalBottomSheet 使用 | 7 处 | 1 处（6 处替换为 BlyyBottomSheet） |
| 设计 Token 缺口 | 12 个缺失值 | 0 个（全部补齐） |

---

## 五、待办事项与后续建议

### 已识别但延后的工作

| 优先级 | 任务 | 说明 |
|--------|------|------|
| 中 | 7 处自定义内容 AlertDialog → BlyyDialog | RecordDetailDialog / NicknamePromptDialog / SettlementDialog×2 / RenameDialog / UserConfigDialog / SSLWarning |
| 中 | GuessImage/GuessVoice 组件合并 | 8 对重复函数提取为 `GuessGameComponents.kt` |
| 中 | JiuxinChatScreen ImageRequest remember | 列表项内 ImageRequest 需提升 LocalContext |
| 中 | JiuxinChatScreen LocalConfiguration 读取范围 | MessageBubble 内每条消息读取，应提升至函数顶层 |
| 低 | 批量硬编码迁移 | 250+ dp、125+ FontWeight、40+ Shape 等迁移为 token |

### 长期架构建议

1. **设计 Token 自动检查**：配置 Detekt / Lint 自定义规则，禁止在 Composable 函数内直接使用 `.dp`、`FontWeight.Bold`、`RoundedCornerShape()` 等硬编码值
2. **组件文档**：为 `BlyyComponents` / `BlyyDialog` / `BlyyDesignSystem` 生成 Dokka 文档，方便团队查阅
3. **设计 Token Figma 同步**：将 `AppSpacing` / `AppTypography` / `AppColors` 导出为 Figma Tokens JSON，实现设计-开发双向同步
4. **性能基准测试**：使用 Macrobenchmark 建立列表滚动、页面转场的帧率基准，防止性能回退

---

## 六、修改文件清单

### 主题文件（阶段 0）
- `ui/theme/Spacing.kt` — 新增 6 个尺寸 token
- `ui/theme/Type.kt` — 新增 8 个字重变体
- `ui/theme/Shape.kt` — 新增 4 个形状 token
- `ui/theme/Animation.kt` — 新增 3 个 easing token

### 组件文件（阶段 2）
- `ui/components/BlyyComponents.kt` — 新增 `BlyySpeechBubble` 组件

### Screen 文件（阶段 1 + 阶段 2）
- `ui/screens/HomeScreen.kt` — 装饰动画优化
- `ui/screens/VoiceScreen.kt` — 状态组件替换 + 底部弹窗替换 + 列表 key + 性能修复
- `ui/screens/ShipGalleryScreen.kt` — 状态组件替换 + ImageRequest remember
- `ui/screens/GuessHistoryScreen.kt` — 状态组件替换 + 确认对话框替换
- `ui/screens/GuessImageScreen.kt` — luminance 修复
- `ui/screens/GuessVoiceScreen.kt` — luminance 修复 + 确认对话框替换
- `ui/screens/AssistantScreen.kt` — 列表 key 修复
- `ui/screens/JiuxinChatScreen.kt` — 确认对话框替换 + 底部弹窗替换×2
- `ui/screens/JiuxinConfigScreen.kt` — 底部弹窗替换×2
- `ui/screens/SecretaryShipPickScreen.kt` — 底部弹窗替换
- `ui/components/ShipCard.kt` — SubcomposeAsyncImage 替换

**总计修改 16 个文件**
