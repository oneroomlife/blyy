# 设计 Token 速查表

> 项目：碧蓝航线语音应用 (blyy)
> 用途：开发时快速查阅设计 Token，避免硬编码

---

## 一、间距 AppSpacing

### 基础单位（8dp 网格）

| Token | 值 | 用途 |
|-------|-----|------|
| `None` | 0dp | 无间距 |
| `Xxs` | 2dp | 微间距（标签内边距） |
| `Xs` | 4dp | 最小间距 |
| `Sm` | 8dp | 小间距（组件间） |
| `Md` | 12dp | 中间距（卡片内） |
| `Lg` | 16dp | 大间距（屏幕边距） |
| `Xl` | 20dp | 超大间距 |
| `Xxl` | 24dp | 区块间距 |
| `Xxxl` | 32dp | 段落间距 |
| `Xxxxl` | 40dp | 区块级间距 |
| `Huge` | 48dp | 超大留白 |
| `Massive` | 64dp | 空状态图标 |

### 圆角 Corner

| Token | 值 | 用途 |
|-------|-----|------|
| `None` | 0dp | 直角 |
| `Xs` | 4dp | 最小圆角 |
| `Xs2` | 14dp | Guess 卡片圆角 |
| `Sm` | 8dp | 按钮/Chip 圆角 |
| `Md` | 12dp | 面板圆角 |
| `Lg` | 16dp | 卡片圆角 |
| `Xl` | 20dp | 对话框圆角 |
| `Xxl` | 24dp | 底部弹窗圆角 |
| `Dialog` | 28dp | 经典风格对话框 |
| `Chamfer` | 10dp | 切角矩形默认值 |
| `Full` | 9999dp | 圆形 |

### 图标尺寸 Icon

| Token | 值 | 用途 |
|-------|-----|------|
| `Xs` | 12dp | 最小图标 |
| `Sm` | 16dp | 列表项图标 |
| `Md2` | 18dp | 辅助图标 |
| `Md` | 20dp | 标准图标 |
| `Lg` | 24dp | 顶栏图标 |
| `Xl1` | 22dp | 播放器图标 |
| `Xl` | 28dp | 大图标 |
| `Xxl` | 32dp | 空状态图标 |
| `Huge` | 48dp | 强调图标 |
| `Massive` | 64dp | 错误状态图标 |

### 头像尺寸 Avatar

| Token | 值 | 用途 |
|-------|-----|------|
| `Xs` | 24dp | 列表项小头像 |
| `Sm` | 32dp | 顶栏头像 |
| `Md` | 36dp | 聊天头像 |
| `Lg1` | 44dp | 答题选项头像 |
| `Lg` | 48dp | 设置项头像 |
| `Xl` | 64dp | 卡片头像 |
| `Xxl` | 80dp | 详情页头像 |
| `Huge` | 120dp | 关于页大头像 |

### 立绘尺寸 Figure

| Token | 值 | 用途 |
|-------|-----|------|
| `Sm` | 110dp | 折叠态立绘 |
| `Md` | 140dp | 语音页立绘 |
| `Lg` | 160dp | 画廊页立绘 |
| `Xl` | 180dp | 翻牌动画 |
| `Xxl` | 220dp | 全屏立绘 |
| `Banner` | 70dp | 卡片底部渐变遮罩 |

### 组件内边距 Padding

| Token | 值 | 用途 |
|-------|-----|------|
| `CardInner` | 16dp | 卡片内边距 |
| `CardOuter` | 6dp | 卡片外边距 |
| `ButtonHorizontal` | 24dp | 按钮水平内边距 |
| `ButtonVertical` | 12dp | 按钮垂直内边距 |
| `InputHorizontal` | 16dp | 输入框水平内边距 |
| `InputVertical` | 14dp | 输入框垂直内边距 |
| `ListItemHorizontal` | 16dp | 列表项水平内边距 |
| `ListItemVertical` | 12dp | 列表项垂直内边距 |
| `ChipHorizontal` | 12dp | Chip 水平内边距 |
| `ChipVertical` | 6dp | Chip 垂直内边距 |

### 边框 Border

| Token | 值 | 用途 |
|-------|-----|------|
| `Thin` | 1dp | 细边框 |
| `Normal` | 2dp | 标准边框 |
| `Thick` | 3dp | 粗边框 |

### 阴影 Elevation

| Token | 值 | 用途 |
|-------|-----|------|
| `None` | 0dp | 无阴影 |
| `Sm` | 2dp | 微浮 |
| `Md` | 4dp | 标准卡片 |
| `Lg` | 8dp | 强调卡片 |
| `Xl` | 12dp | 对话框 |
| `Xxl` | 16dp | 全屏 Modal |

---

## 二、排版 AppTypography

### 基础样式

| Token | 字号 | 字重 | 行高 | 用途 |
|-------|------|------|------|------|
| `DisplayLarge` | 57sp | Normal | 64sp | 超大显示 |
| `DisplayMedium` | 45sp | Normal | 52sp | 大显示 |
| `DisplaySmall` | 36sp | Normal | 44sp | 中显示 |
| `HeadlineLarge` | 32sp | SemiBold | 40sp | 大标题 |
| `HeadlineMedium` | 28sp | SemiBold | 36sp | 中标题 |
| `HeadlineSmall` | 24sp | SemiBold | 32sp | 小标题 |
| `TitleLarge` | 22sp | SemiBold | 28sp | 页面标题 |
| `TitleMedium` | 16sp | SemiBold | 24sp | 区块标题 |
| `TitleSmall` | 14sp | Medium | 20sp | 子标题 |
| `BodyLarge` | 16sp | Normal | 24sp | 正文大 |
| `BodyMedium` | 14sp | Normal | 20sp | 正文 |
| `BodySmall` | 12sp | Normal | 16sp | 辅助文字 |
| `LabelLarge` | 14sp | Medium | 20sp | 标签大 |
| `LabelMedium` | 12sp | Medium | 16sp | 标签中 |
| `LabelSmall` | 11sp | Medium | 16sp | 标签小 |

### 字重变体（避免 `.copy(fontWeight = ...)`）

| Token | 基础样式 | 字重 | 用途 |
|-------|---------|------|------|
| `TitleMediumBold` | TitleMedium | Bold | 强调标题 |
| `TitleSmallMedium` | TitleSmall | Medium | 设置项副标题 |
| `LabelLargeBold` | LabelLarge | Bold | 计数徽章 |
| `LabelMediumBold` | LabelMedium | Bold | 强调小标签 |
| `LabelSmallBold` | LabelSmall | Bold | 状态标签 |
| `LabelSmallMedium` | LabelSmall | Medium | 聊天名称/时间戳 |
| `BodyMediumMedium` | BodyMedium | Medium | 输入框文字 |
| `BodySmallMedium` | BodySmall | Medium | 气泡文字 |

### 自定义样式

| Token | 字号 | 字重 | 用途 |
|-------|------|------|------|
| `CardTitle` | 15sp | Bold | 卡片名称 |
| `CardLabel` | 10sp | Medium | 卡片标签 |
| `EmptyTitle` | 24sp | Bold | 空状态标题 |
| `EmptyDescription` | 14sp | Light | 空状态描述 |
| `ButtonText` | 16sp | SemiBold | 按钮文字 |
| `NavigationLabel` | 12sp | Medium | 导航栏标签 |

---

## 三、形状 BlyyShapes

| Token | 形状 | 用途 |
|-------|------|------|
| `PanelSmall` | 8dp 切角 | 小型面板/按钮 |
| `PanelMedium` | 12dp 切角 | 中型面板 |
| `PanelLarge` | 16dp 切角 | 大型面板/毛玻璃卡片 |
| `NavBar` | 14dp 对角切角 | 底部导航栏 |
| `Card` | 10dp 切角 | 舰船卡片 |
| `Button` | 8dp 切角 | 按钮 |
| `Dialog` | 20dp 切角 | 对话框（Command Center） |
| `BottomSheet` | 20dp 顶部切角 | 底部弹窗（Command Center） |
| `DialogClassic` | 28dp 圆角 | 对话框（Classic） |
| `BottomSheetClassic` | 24dp 顶部圆角 | 底部弹窗（Classic） |
| `ChatBubbleReceived` | 左下尖角圆角 | 聊天气泡（接收） |
| `ChatBubbleSent` | 右下尖角圆角 | 聊天气泡（发送） |

---

## 四、动画 AppAnimation

### 时长 Duration

| Token | 值 | 用途 |
|-------|-----|------|
| `Instant` | 100ms | 即时反馈 |
| `Fast` | 200ms | 快速过渡 |
| `Normal` | 350ms | 标准动画 |
| `Slow` | 500ms | 慢速动画 |
| `VerySlow` | 800ms | 入场动画 |
| `PageTransition` | 450ms | 页面转场 |
| `StaggerDelay` | 40ms | 错落延迟 |

### 缓动 Easings

| Token | 公式 | 用途 |
|-------|------|------|
| `Standard` | FastOutSlowInEasing | 标准缓动 |
| `DecelerateIn` | LinearOutSlowInEasing | 元素进入减速 |
| `AccelerateOut` | FastOutLinearInEasing | 元素退出加速 |
| `EaseInOutSine` | CubicBezier(0.37, 0, 0.63, 1) | 正弦进出（呼吸/光晕） |
| `Emphasized` | 自定义多项式 | 强调动效 |
| `EmphasizedDecelerate` | 三次方减速 | 强调进入 |
| `EmphasizedAccelerate` | 三次方加速 | 强调退出 |
| `Linear` | LinearEasing | 线性（进度条） |
| `Bounce` | 分段三次方 | 弹跳 |
| `Smooth` | 平滑三次方 | 平滑过渡 |

### 按压反馈 Press

| Token | 缩放比例 | 用途 |
|-------|---------|------|
| `LightScale` | 0.96 | Chip/小按钮 |
| `StandardScale` | 0.97 | 卡片/列表项 |
| `HeavyScale` | 0.94 | 主按钮/FAB |

### 弹簧 Springs

| Token | 阻尼比 | 刚度 | 用途 |
|-------|--------|------|------|
| `Standard` | NoBouncy | Medium | 标准 |
| `Bouncy` | MediumBouncy | Low | 弹性 |
| `Stiff` | NoBouncy | High | 紧凑 |
| `Soft` | HighBouncy | VeryLow | 柔软 |
| `Snappy` | MediumBouncy | Medium | 清脆 |

### 循环动画 Repeating

| 方法 | 默认时长 | 模式 | 用途 |
|------|---------|------|------|
| `breathing()` | 2500ms | Reverse | 呼吸效果 |
| `pulse()` | 1500ms | Restart | 脉冲 |
| `rotate()` | 8000ms | Restart | 旋转 |
| `glow()` | 2000ms | Reverse | 光晕 |
| `shimmer()` | 3000ms | Restart | 闪光 |
| `float()` | 3500ms | Reverse | 悬浮 |

---

## 五、阴影 AppElevation

| Token | 值 | 用途 |
|-------|-----|------|
| `Level0` | 0dp | 背景/分隔 |
| `Level1` | 1dp | 列表项/Chip |
| `Level2` | 3dp | 标准卡片 |
| `Level3` | 6dp | 强调卡片/FAB |
| `Level4` | 12dp | 对话框/BottomSheet |
| `Level5` | 16dp | 全屏 Modal |
| `PressedDelta` | 2dp | 按压态阴影回落 |
| `GlassTonal` | 2dp | 毛玻璃面板推荐 tonal |

---

## 六、使用规范

### 禁止

```kotlin
// 禁止硬编码 dp
Modifier.size(64.dp)           // ❌
Modifier.padding(16.dp)        // ❌

// 禁止硬编码 FontWeight
fontWeight = FontWeight.Bold   // ❌

// 禁止硬编码 Shape
RoundedCornerShape(28.dp)      // ❌

// 禁止硬编码 easing
tween(easing = FastOutSlowInEasing)  // ❌
```

### 正确

```kotlin
// 使用 Token
Modifier.size(AppSpacing.Icon.Massive)           // ✅
Modifier.padding(AppSpacing.Lg)                   // ✅

// 使用字重变体
style = AppTypography.TitleMediumBold             // ✅

// 使用 BlyyShapes
shape = BlyyShapes.DialogClassic                  // ✅

// 使用 AppAnimation.Easings
tween(easing = AppAnimation.Easings.Standard)     // ✅
```

### 组件优先

优先使用统一组件，而非自行实现：

| 场景 | 使用组件 |
|------|---------|
| 空状态 | `BlyyEmptyState` |
| 错误状态 | `BlyyErrorState` |
| 加载状态 | `BlyyLoadingState` |
| 确认对话框 | `BlyyConfirmDialog` |
| 自定义对话框 | `BlyyDialog` |
| 底部弹窗 | `BlyyBottomSheet` |
| 顶栏 | `BlyyTopBar` |
| 面板 | `BlyyPanel` |
| 主按钮 | `BlyyPrimaryButton` |
| 次按钮 | `BlyySecondaryButton` |
| Chip | `BlyyChip` |
| 设置行 | `BlyySettingsRow` |
| 分段面板 | `BlyySectionPanel` |
| 聊天气泡 | `BlyySpeechBubble` |
