# BLYY 全页面 UI 视觉审计报告（2026-10-10）

> 定位：**视觉质感、设计一致性、细节打磨**维度的全页面审计 + 本轮落地记录。
> 结构性债务（巨型 Composable、VM 模式、状态缺失）见 [UI_DEEP_AUDIT_AND_REFACTOR_PLAN.md](UI_DEEP_AUDIT_AND_REFACTOR_PLAN.md)；
> 视觉目标态设计规格见 [UI_DESIGN_V2_PLAN.md](UI_DESIGN_V2_PLAN.md)（「深海舰队」设计语言）。
> 本报告回答：每个模块**现在长什么样、哪里不精致、本轮修了什么、还剩什么**。

---

## 0. 审计方法与范围

- 范围：`ui/` 全部 26 屏 + 20 组件文件 + 11 主题文件（约 35,000 行 Compose），双风格（指挥中心/经典）× 明暗双模式。
- 方法：主题层与组件库逐行精读；V2 验收脚本量化（色彩纪律 / 阴影旁路 / 触控目标 / 无限动画清单）；设备实机截图（亮/暗）+ `dumpsys gfxinfo` 帧率采样。
- 本轮代码改动共 10 个文件，全部通过 `compileDebugKotlin`、`testDebugUnitTest`、`assembleDebug` 与实机安装验证。

---

## 1. 色彩体系审计（MD3 规范 · 明暗适配 · 对比度）

### 1.1 总体评价

色彩纪律**优秀**：屏幕/组件层 `Color(0x...)` 硬编码为 **0**，全部色彩经由 `AppColors`/`ClassicColors`/`JuusPalette` 三套令牌归口。M3 ColorScheme 双风格 × 明暗四套装配完整（含 `surfaceContainerLowest/Low` 五档阶梯），Material You 动态色仅接管 surface 阶梯、品牌色锁定，策略正确。

### 1.2 本轮发现并已修复的缺陷

| # | 缺陷 | 位置 | 修复 |
|---|---|---|---|
| C1 | **OnTertiaryLight 白字落 #5B8DEF 仅 3.2:1**（AA 要求 4.5），明暗两态双双不达标（暗态 #ECFEFF 落 #7EB6FF 仅 2.0:1） | [Color.kt](file:///d:/Android/Project/blyy/app/src/main/java/com/azurlane/blyy/ui/theme/Color.kt) | 明暗两态 onTertiary 均改深海军蓝 `#0A1628`（5.6:1 / 8.7:1），影响面：秘书舰 SD 整理按钮等 tertiary 填充 |
| C2 | **PanelBorder 青→金→青渐变违反 V2 红线**（金色通胀：普通面板描边出现金色） | Color.kt `Gradient.PanelBorder` | 降为纯青双档 `#48CAE4@45% → #0096C7@18%`，消费者：BlyyDialog、BlyyListItem |
| C3 | **金属渐变标题亮色模式中段为白色**——白段落在亮色海床上不可见，实机截图中「后宅**空**荡荡的…」的「空」字被漂白 | Color.kt `MetallicText` + HomeScreen 空态标题 | 亮色渐变改 `青#0096C7 → 深青#0077B6 → 金#E8A838`，保留金属折光且全程可读（已实机验证） |
| C4 | **主按钮亮色模式白字对比不足**：#0096C7 上白字 3.4:1，`ButtonText`（16sp SemiBold）不构成 WCAG 大字 | BlyyComponents `BlyyPrimaryButton` | 亮色模式填充基色改 `PrimaryDeepLight #0077B6`（4.9:1 达 AA），暗色不变（onPrimary 深海军蓝 7.6:1） |
| C5 | **首页空态 CTA「去挑选舰娘」**：填充右端 `primary@80%` 在亮色海床上白字仅 ~2.9:1 | HomeScreen `PremiumInteractiveButton` | 亮色模式改 `PrimaryDeep→Primary` 渐变（V2 §7.1 规格） |
| C6 | **3 个含金死令牌**：`Gradient.HudAccent`、`Gradient.GlassBorderLight/Dark`（青金青渐变）0 消费者 | Color.kt | 直接删除——红色线以删除方式落实 |

### 1.3 验收矩阵（本轮复核结果）

| 组合 | 要求 | 结果 |
|---|---|---|
| onSurface / surface（明暗两套） | ≥7:1 | ✅ #0A1628 落 #F5FAFF ≈13:1；#E2EAF4 落 #0A1628 ≈12:1 |
| onSurfaceVariant / surface | ≥4.5:1 | ✅ #3D5A73 / #94A8BE 双态达标 |
| 白字 / Primary 填充 | 大字场景才允许；小字用 PrimaryDeep | ✅ 本轮起主按钮/CTA 已切 PrimaryDeep；**残留**：原生 M3 Button（Leaderboard/Mode 屏）仍白字落 #0096C7，随组件收敛（审计文档 T1）统一 |
| onTertiary / tertiary | ≥4.5:1 | ✅ 本轮修复（C1） |
| 金色文字 | 只允许 GoldTextLight/Dark | ✅ 屏幕层无 `FFD166` 直落文字；金属渐变金段仅作装饰性标题（C3 已保证可读） |
| Scrim 上文字 | alpha ≥Medium 才放字 | ✅ ShipCard 阵营徽章用 Scrim.Base@66% 底 + 白字 |

### 1.4 待办（色彩域）

- **JuusPalette.BubbleOutgoing #5BA4E6 白字 2.66:1**：啾信 JUUSTAGRAM 风格有独立设计规范（有出处的设计决策），本轮未动；建议后续对其出气泡白字加深或气泡加深的 A/B 方案。
- 亮色海床色阶（背景 #D4E8F5 家族提亮降饱和，V2 §2.2）按 V2 风险预案**维持现状**，待试点屏 A/B 后一次定稿。
- 原生 M3 Button/OutlinedButton/FilledTonalButton 残留（Leaderboard:221、Mode:791,971、Random:145,277）——随「按钮三套并一套」收敛时按新规格实现。

---

## 2. 组件规格审计（圆角 · 阴影 · 描边 · 状态反馈 · 触控）

### 2.1 本轮已落地

| 项 | 内容 | 位置 |
|---|---|---|
| **四层深度阴影收编** | GuessCorrectCard `Level3`（亮暗两态误用亮色阴影）→ `blyyDepth(Instrument)`；GuessRewardImage 默认纯黑阴影 → `blyyDepth(Lookout)`；GuessImage 题面卡 `shadow(16.dp)` 裸值+双主色染色 → `blyyDepth(Instrument, spotTint=primary)`；IconSettings 图标预览默认纯黑 → Depth 双色 | [GuessGameComponents.kt](file:///d:/Android/Project/blyy/app/src/main/java/com/azurlane/blyy/ui/screens/guess/GuessGameComponents.kt)、[GuessImageScreen.kt](file:///d:/Android/Project/blyy/app/src/main/java/com/azurlane/blyy/ui/screens/GuessImageScreen.kt)、[IconSettingsScreen.kt](file:///d:/Android/Project/blyy/app/src/main/java/com/azurlane/blyy/ui/screens/IconSettingsScreen.kt) |
| **ShipCard 有色阴影规范** | ambient 由稀有度染色改为标准 Depth 双色（全阵列环境光统一），spot 保留稀有度染色 @25%——「彩色只染直射光」 | [ShipCard.kt](file:///d:/Android/Project/blyy/app/src/main/java/com/azurlane/blyy/ui/components/ShipCard.kt#L100-L108) |
| **ShipCard 比例对齐** | `aspectRatio(0.8f)` → `AppSpacing.Card.AspectRatio(0.75)`，与 shimmer 骨架逐像素同构，加载完成瞬间不再跳动 | ShipCard.kt |
| **ShipCard 描边降金** | 卡片描边渐变的金色中段移除（普通卡禁金；传奇档 rarityColor 本身即金、誓约金边由 OathSpecialEffect 承载） | ShipCard.kt |
| **触控热区 ≥48dp** | 17 处 `IconButton(size(28/32.dp))` 外层补 `minimumInteractiveComponentSize()`（视觉尺寸不变、热区补足）：会话列表 ×2、配置卡 ×9、历史 ×2、搜索历史删除 ×1、Live2D 库 ×1、稍后再听 ×2 | ConversationListScreen / ConfigCards / GuessHistoryScreen / GalleryTopBar / Live2dLibraryScreen / VoicePlayLaterSheet |

### 2.2 现状健康度（复核确认）

- **圆角**：`BlyyShapes` 切角三档 + `AppSpacing.Corner` 九档归口完整；聊天气泡收尖角、Sheet 顶部大圆角等形状语义正确。
- **状态反馈**：按压三联动（scale + 阴影回落 + 边框变亮）在 ShipCard/主按钮/Guess 分段控件已成型；主按钮按压流光为一次性 Animatable（静止零开销）；禁用态降饱和 + 文字降对比处理正确。
- **触觉**：`BlyyHaptics` 四档语义触觉 20+ 文件在用，全 app 唯一触觉入口。
- **阴影旁路残余**：GuessGameComponents 的 DifficultySelector(:219) 与分段选中态(:306)、VoiceScreen 语言按钮(:434)——均为「已令牌化的刻意设计」（Depth 色 / 主色 spot 辉光），保留。

---

## 3. 逐模块审计与优化点标注

> 状态标记：✅ 本轮已修 · 🔶 有既定规格待落地（挂在结构重构阶段） · 🆕 本轮新发现

### 3.1 首页（我的后宅）

- ✅ 空态标题金属渐变亮色模式白段不可见（C3，实机验证修复）
- ✅ 空态 CTA 亮色对比不足（C5）
- 🆕 **空态 CTA 被底部导航栏玻璃（95% 不透明）压在下面**：横屏矮屏（480dp 高）下按钮与导航栏重叠，亮色下近乎不可见。本轮已尝试「避让 padding」方案，因横屏垂直空间不足导致内容溢出，**按用户决定已撤回**。建议后续方案：横向布局拆分（横屏下文字与按钮左右排布）或空态列可滚动，单独排期。
- 🔶 AGSL 流体背景无誓约舰时空转（oathIntensity==0 仍每 33ms 重绘）→ 静态渐变降级（V2 §7.1）
- 🔶 错误态渲染已具备（B10 已修），下拉刷新失败细条样式待与 GalleryErrorBanner 统一

### 3.2 船坞/图鉴（Gallery）

- ✅ ShipCard 比例/阴影/描边三项修复（见 §2.1）
- 🔶 GallerySearchBar 与 ClassicGallerySearchBar 同文件 80% 重复 → 搜索框三合一（T1.4）
- 🔶 筛选 Sheet「草稿+应用」心智统一（学生 Sheet 即时生效 → 迁移）
- 🔶 顶栏「搜索激活时不隐藏」规则未加（正在输入时顶栏消失是灾难）

### 3.3 语音档案（Voice）

- ✅ 稍后再听 28dp IconButton ×2 热区补足
- 🔶 播放大按钮辉光暂停时仍在跑（VoicePlayerBar:553 需按播放态 gating）→ 动画白名单治理（T3）
- 🔶 进度 500ms 轮询 → Listener 回调 + 拖动 100ms 插值（滑杆跟手）
- 🔶 列表 key `index_` 前缀问题（B6）待修

### 3.4 识舰娘（Guess）

- ✅ 结算卡/奖励图/题面卡三处阴影收编四层深度模型（暗色模式下不再发灰/发脏）
- 🔶 ImageCard「加载中」与「加载失败」合并渲染（B7：5 次重试耗尽后永久转圈）→ P1 待修
- 🔶 「反复回放」文案与行为不符（B9）
- 🔶 两屏装配序列合并 GuessGameScaffold（T2.6，保留竞态修复语义）

### 3.5 啾信（Jiuxin）

- ✅ 会话列表顶栏 +/菜单 32dp IconButton 热区补足
- ✅ 配置卡 9 处 32dp IconButton 热区补足
- 🔶 失败消息不可见不可重试（B2，P0）——VM 状态机完整，MessageBubble 待渲染 status
- 🔶 气泡我方 #5BA4E6 白字 2.66:1（JUUSTAGRAM 独立设计语言，需产品决策）
- 🔶 时间戳合并（同发送者 5 分钟内仅末条显示）→ 阅读优先改造

### 3.6 秘书舰（Secretary）

- ✅ SD 整理按钮 onTertiary 白字对比修复（C1 间接受益）
- 🔶 Mode 屏 1373 行拆分 + SecretarySdViewModel（T2.1，旋屏丢状态是其中最重一项）
- 🔶 翻牌屏无限旋转 → 真 3D 翻面（V2 §7.6）

### 3.7 设置 / 关于 / 排行榜 / 水印 / Live2D

- ✅ 图标预览阴影 Depth 双色（暗色不再纯黑打架）
- ✅ Live2D 库 28dp 关闭钮热区补足
- 🔶 更新渠道卡双实现合并（About:537-600 vs AppChrome:1134-1222）
- 🔶 水印相机黑底语境 17 处 Color.White/Black → Scrim 语义族
- 🔶 Leaderboard raw FilledTonalButton/Card 收编 + 名次 NumericHud

### 3.8 全局组件（AppChrome / AppRoot）

- ✅ 底栏描边渐变仍含金中段（AppChrome:236 `Accent.Gold@0.3`）——**注**：属于导航高亮的品牌识别元素，本轮按「普通面板禁金、导航高亮可保留」的宽口径暂留，若要严格执行 V2 红线可替换为 `Accent.Cyan` 双档
- 🔶 UpdateAvailableDialog 用 raw AlertDialog → BlyyDialog 体系（T3.6 弹窗统一）
- 🔶 底栏隐显 20 条字符串前缀条件数据化

---

## 4. 屏幕适配审计

- **手表**：`WatchSpacing/WatchTypography` 65% 缩放副本完整，但散落 12+ 处 `if (isWatchScreen())` 内联三元未沉淀为令牌（T1.8 收编项）。
- **横屏矮屏**（本轮实机即 480dp 高）：首页空态 CTA 与导航栏重叠为本轮实测发现（§3.1 🆕）；其余屏的纵向滚动内容不受影响。
- **网格**：`GridCells.Adaptive(120dp)` 全部网格类屏已在用，无固定列数硬编码。
- **平板 ≥840dp**：双栏布局为 V2 后续增强项，当前仅单栏 max-width 居中——暂无回归。

---

## 5. 性能与动效审计

### 5.1 实机帧率（127.0.0.1:16480，模拟器）

首页空态加载 + 连续滚动两屏：**Total 189 帧 / Janky 1.06%**（50th 17ms / 90th 21ms / 95th 48ms / 99th 77ms）——中低端机型无明显损耗的验收口径下，模拟器数据宽裕；真机验证建议复测 ShipCard 长网格滚动（动画预算见 §5.2）。

### 5.2 无限动画白名单清单（`rememberInfiniteTransition` 全量 12 处运行点）

| 位置 | 类别 | 白名单判定 |
|---|---|---|
| ChatBubbles:219 typing | 正在生成 | ✅ |
| GuessVoiceScreen:391 voicePulse | 正在播放 | ✅ |
| VoicePlayerBar:276/553 | 播放态辉光/按钮 | ⚠️ :553 需确认暂停时停跑 |
| VoicePlayLaterSheet:297 | 正在播放脉冲 | ✅ |
| AppChrome:297 选中呼吸 | 选中态 | ✅（gating 已做） |
| AppChrome:859 / AboutScreen:367 检查更新脉冲 | 正在加载 | ✅ |
| Live2dViewerScreen:718 motionPulse | 正在播放 | ✅（需确认 gating） |
| HomeScreen:455/534/768/890 誓约氛围/粒子/悬浮/CTA流光 | 首页氛围（AGSL 同源） | ⚠️ T3.2 目标：无誓约时降级 |
| ShipCard:436/494/650 稀有度辉光/誓约/收藏 | 装饰常驻 | ❌ 超 2 动画/卡预算 → T3.1 合并 drawBehind |
| SecretaryShipRandomScreen:167 flip | 翻牌（应为一次性） | ❌ → 真 3D 翻面时移除 |

### 5.3 渲染性能防线（已建立）

- ShipCard 滚动降级机制（`decorativeAnimation` 参数，滚动时高稀有卡辉光→静态光条）已存在；
- AsyncImage 替代 SubcomposeAsyncImage、Coil crossfade、`key`+`contentType` 稳定重组等网格优化已在位；
- AGSL 着色器 30fps 节流 + 生命周期绑定已在位。

---

## 6. 本轮改动清单（10 文件）

| 文件 | 改动 |
|---|---|
| theme/Color.kt | C1/C2/C3/C6：onTertiary×2、PanelBorder 降金、MetallicText 亮色重定义、删 3 个死令牌 |
| theme/Depth.kt | （M1 已有，本轮为消费参照） |
| components/BlyyComponents.kt | C4：主按钮亮色 PrimaryDeep 基色 |
| components/ShipCard.kt | 比例令牌化 + 阴影规范 + 描边降金 |
| screens/guess/GuessGameComponents.kt | 结算卡/奖励图阴影收编 blyyDepth |
| screens/GuessImageScreen.kt | 题面卡阴影收编（删 16.dp 裸值） |
| screens/IconSettingsScreen.kt | 预览阴影 Depth 双色 |
| screens/ConversationListScreen.kt | 触控热区 ×2 |
| screens/config/ConfigCards.kt | 触控热区 ×9 |
| screens/GuessHistoryScreen.kt / gallery/GalleryTopBar.kt / live2d/Live2dLibraryScreen.kt / voice/VoicePlayLaterSheet.kt | 触控热区 ×2/×1/×1/×2 |
| screens/HomeScreen.kt | C3 消费 + C5 CTA 渐变（空态避让已按用户决定撤回） |

验证：`compileDebugKotlin` ✅ / `testDebugUnitTest` ✅ / `assembleDebug` ✅ / 实机安装 + 亮暗截图 + gfxinfo ✅。

---

## 7. 下一步优先级建议

1. **P0 行为修复**（审计文档阶段 0 清单中尚未完成的 B1/B2/B3/B7）——视觉打磨的收益会被真 bug 抵消；
2. **组件收敛期搭载 V2 规格**（T1 系列）：BlyyScreenScaffold/BlyyListItem 激活、搜索框三合一、四态渲染器；
3. **动画预算治理**（T3.1/T3.2）：ShipCard 7 动画/卡合并、Home AGSL 空转降级、播放键辉光 gating；
4. **首页空态 CTA 遮挡**（本轮 🆕）：横屏布局专项，建议横屏下空态改左右布局；
5. **亮色海床 A/B 定稿**：V2 §2.2 数值已备，试点屏截图对比后一次切换。
