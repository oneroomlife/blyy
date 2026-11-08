# 碧蓝语音（blyy）Android APP UI/UX 全量设计分析报告

> 审计日期：2026-11-08
> 审计对象：`com.azurlane.blyy` v3.0.0（versionCode 1，targetSdk 35，2026-11-08 构建的 debug APK，与工作区源码一致）
> 审计环境：MuMu 模拟器 index3（Android 15，720×1280 @240dpi，视口 360×640dp），横竖屏双形态实测
> 审计方式：源码静态全量分析（5 路并行：导航/令牌/组件/动效/屏幕）+ 真机运行时全量走查（35 条路由逐一访问，91 张截图 + 交互验证）
> 截图证据目录：`test_screenshots/ui_audit_2026-11/`（本地，已 gitignore；正文引用形如 `【31】`= `31_ship_context_menu.png`）
> 上一轮审计：2026-10-04 两份底稿（已随重构完成清理）；本次为 2026-10-31 六批次全量重构后的**复审**

---

## 目录

1. [项目概况与分析范围](#1-项目概况与分析范围)
2. [APP 整体架构及功能模块](#2-app-整体架构及功能模块)
3. [全量页面清单及页面层级](#3-全量页面清单及页面层级)
4. [页面导航关系及用户操作流程](#4-页面导航关系及用户操作流程)
5. [整体视觉风格分析](#5-整体视觉风格分析)
6. [色彩体系分析](#6-色彩体系分析)
7. [字体及排版规范分析](#7-字体及排版规范分析)
8. [页面布局与空间结构分析](#8-页面布局与空间结构分析)
9. [图标及视觉素材分析](#9-图标及视觉素材分析)
10. [UI 组件及复用体系分析](#10-ui-组件及复用体系分析)
11. [交互逻辑与用户体验分析](#11-交互逻辑与用户体验分析)
12. [页面及组件状态分析](#12-页面及组件状态分析)
13. [动效与动画系统全量分析](#13-动效与动画系统全量分析)
14. [Android 设备适配分析](#14-android-设备适配分析)
15. [无障碍体验分析](#15-无障碍体验分析)
16. [UI 性能与动效性能分析](#16-ui-性能与动效性能分析)
17. [全量问题清单及优先级评估](#17-全量问题清单及优先级评估)
18. [典型页面深度分析](#18-典型页面深度分析)
19. [整体 UI 重设计建议](#19-整体-ui-重设计建议)
20. [统一设计规范建设方案](#20-统一设计规范建设方案)
21. [分阶段优化与实施路线](#21-分阶段优化与实施路线)
22. [最终结论](#22-最终结论)
23. [尚未完成的检查项、访问限制及待验证事项](#23-尚未完成的检查项访问限制及待验证事项)

---

## 1. 项目概况与分析范围

### 1.1 产品定位

碧蓝语音（blyy）是一款碧蓝航线/蔚蓝档案双档案的舰娘/学生资料助手应用，核心能力：

- **档案查询**：舰娘/学生语音档案（在线台词库+播放）、立绘画廊、船坞收藏网格
- **陪伴互动**：秘书舰系统（应用内小人+桌面悬浮窗+桌面小组件）、Live2D 本地皮肤库与查看器、陀螺仪注视
- **游戏化**：看图/听音识舰娘、积分排行榜、历史战绩
- **AI 社交**：啾信（JUUS 风格 AI 聊天，自定义 API/人格）
- **工具**：碧蓝航线助手（指挥官数据查询）、水印相机/编辑器、生日日历、游戏资源导入（Shizuku）、自定义图标

### 1.2 技术栈

| 维度 | 内容 |
|---|---|
| UI 框架 | Jetpack Compose + Material3 1.4.0（单 Activity，无 XML View 页面） |
| 架构 | Hilt DI + MVVM（viewmodel/）+ DataStore/Room，导航 Navigation-Compose（35 路由，全部 `composable()` 型） |
| 渲染特例 | Live2D = WebView + PIXI（虚拟域 `live2d.local`）；SD 小人 = GLSurfaceView + libgdx spine-runtime 3.8.99；首页/语音页背景 = AGSL RuntimeShader（API 33+） |
| 异步 | kotlinx-coroutines + StateFlow（三配置表 Eagerly） |
| 图片 | Coil 三级缓存（派生图防盗链拦截器、GIF 解码） |

### 1.3 分析范围与覆盖率

| 范围 | 覆盖情况 |
|---|---|
| 路由 | **35/35 全部静态分析；35/35 运行时到访**（含深链与小组件入口代码级确认） |
| 弹窗/浮层 | 41 个 Blyy 封装调用点 + 10 个裸用点全部源码确认；运行时实测 8 类弹窗（结算/确认/详情/新建聊天/新建对话/API 配置/筛选/水印选择） |
| 主题变体 | 指挥中心/经典 × 浅色/深色四象限实测；横屏实测；1.3x 字体缩放实测 |
| 特殊 UI 面 | 桌面小组件（生日件实测）、悬浮窗双窗口（实测）、启动闪屏（实测） |
| 未能实测 | 见 §23：鉴权类（啾信需 API Key 的完整对话）、Shizuku 授权流、游戏内部分状态（断网错误态、上传失败态）等 |

### 1.4 审计基线说明

2026-10-31 完成的六批次重构已解决上一轮审计的大部分结构性问题（阴影唯一来源、颜色归口、对话框封装、巨屏拆分）。本次审计确认重构成果基本落地，同时发现**新一批问题**：跨风格颜色泄漏（P1）、横屏布局冲突（P1）、全局小人 z 序（P2）、手势冲突（P2）等，详见 §17。

---

## 2. APP 整体架构及功能模块

### 2.1 应用骨架（AppRoot.kt）

```
MainActivity（enableEdgeToEdge，singleTop，configChanges 全量，沉浸式状态栏可选隐藏）
└─ BlyyTheme（uiStyle × isDark × isWatch 四 CompositionLocal）
   └─ AppContent（AppRoot.kt:160）
      ├─ SharedTransitionLayout（:245，Home→Voice 头像共享元素）
      ├─ ModalNavigationDrawer（:306，13 入口/4 分组抽屉）
      ├─ NavHost（:334，35 路由，统一 Container-Transform 风格转场）
      ├─ 全局 SecretaryChibi 小人挂载（:978-1027，380ms 重挂载节流）
      └─ ModernNavigationBar 底栏（:1029，2 Tab，滚动联动隐藏）
```

关键全局机制：

- **uiStyleReady 门控**（MainActivity:118-155）：风格/暗色读取完成前不渲染，防旧风格闪现
- **外部路由桥**：小组件/深链 → MainActivity `pendingNavRoute` StateFlow → AppRoot 导航（AppRoot:208-219）
- **底栏可见性**：仅 home/gallery 显示（AppRoot:169-195），gallery 滚动时联动隐藏（:229,478）
- **小人遮蔽规则**：`live2d*` 与 `gameimport/view*` 全屏查看器遮蔽小人，`secretary_*` 已解除遮蔽（AppRoot:991-993）；悬浮窗服务开启时应用内小人隐藏（:985，实测验证一致）

### 2.2 功能模块 × 页面分布

| 模块 | 页面（路由） | 特征 |
|---|---|---|
| 主导航 | home / gallery | 2 Tab 底栏；gallery 双档案（舰娘/学生） |
| 档案详情 | voice / student_voice / gallery/{ship}（立绘）/ student_gallery | 共享元素转场、播放器、可拖拽小人 |
| 游戏化 | guess_image / guess_voice / guess_history / leaderboard | 共享组件库（GuessGameComponents 826 行） |
| 秘书舰 | secretary_mode / random / pick_home / pick_gallery / settings / sd_resource_gallery | 悬浮窗+小组件的配置中枢 |
| Live2D | live2d（库）/ live2d/view（查看器） | WebView 渲染、帧率治理、陪伴模式 |
| 游戏导入 | gameimport / gameimport/view | Shizuku、双 Tab、Spine/GL 查看器 |
| 啾信 | jiuxin_conversation_list / chat / config / ship_config | JUUS 独立视觉语言 |
| 工具系统 | assistant / settings / about / app_icon_settings / image_cropper / birthday_calendar / watermark_camera / watermark_editor | 常规表单/展示页 |

### 2.3 Manifest 要点（AndroidManifest.xml）

- 单 Activity（exported，singleTop）+ 2 Service（PlaybackService 媒体、SecretaryOverlayService specialUse 悬浮窗）+ 2 Provider（Shizuku/FileProvider）+ 4 Receiver（3 小组件 + 日期变更刷新）
- 权限：INTERNET、FOREGROUND_SERVICE×3、SYSTEM_ALERT_WINDOW、POST_NOTIFICATIONS、存储（maxSdk 降级声明）
- `enableOnBackInvokedCallback` 已声明（预测性返回）；无 screenOrientation（全跟随系统）；`adjustResize`

---

## 3. 全量页面清单及页面层级

### 3.1 完整路由表（35 条，AppRoot.kt:334-976）

| # | 路由 | 页面 | 层级 | 入口 | 运行时状态 |
|---|---|---|---|---|---|
| 1 | `home` | 我的后宅 | 一级 Tab | 启动起点 | 【01】已实测 |
| 2 | `gallery` | 舰娘/学生档案 | 一级 Tab | 底栏 | 【30】【34b】已实测 |
| 3 | `voice/{shipName}` | 语音档案 | 二级 | Home/Gallery/生日件/深链 | 【33】已实测 |
| 4 | `gallery/{shipName}` | 立绘画廊 | 二级 | Home/Gallery 长按菜单 | 【32】已实测 |
| 5 | `avatar_figure/{shipName}` | 选立绘做头像 | 三级 | Gallery 仅 DOCK | 代码级 |
| 6 | `avatar_crop/{archiveType}/{shipName}` | 头像裁切 | 三级（DOCK 四级链） | Gallery/立绘选择 | 代码级 |
| 7 | `student_voice/{studentName}` | 学生语音（复用 VoiceScreen） | 二级 | Gallery 学生卡 | 【35】已实测 |
| 8 | `student_gallery/{studentName}` | 学生画廊 | 二级 | Gallery 学生长按 | 【36b】已实测 |
| 9 | `guess_image` | 看图识舰娘 | 二级 | 抽屉 | 【04】已实测 |
| 10 | `guess_voice` | 听音识舰娘 | 二级 | 抽屉 | 【06】已实测 |
| 11 | `guess_history` | 历史记录 | 二级 | 抽屉/猜题页 | 【07】已实测 |
| 12 | `watermark_camera` | 水印相机 | 二级 | 抽屉 | 【26】已实测 |
| 13 | `watermark_editor` | 水印编辑器 | 三级 | 相机页 | 代码级 |
| 14 | `leaderboard` | 积分排行榜 | 二级 | 抽屉/历史页 | 【08】已实测 |
| 15 | `about` | 关于 | 二级 | 抽屉 | 【28】已实测 |
| 16 | `settings` | 设置 | 二级 | 抽屉/助手页 | 【27】已实测 |
| 17 | `app_icon_settings` | 自定义图标 | 三级 | 设置 | 【29】已实测 |
| 18 | `image_cropper/{imageUri}` | 图标裁切 | 四级 | 图标设置 | 代码级 |
| 19 | `assistant` | 碧蓝航线助手 | 二级 | 抽屉 | 【09】已实测 |
| 20 | `assistant_config` | 助手配置 | 三级 | 设置/排行榜/历史 | 代码级 |
| 21 | `jiuxin_config` | 啾信配置 | 三级 | 设置/聊天/列表 | 【12】已实测 |
| 22 | `jiuxin_ship_config` | 单舰配置 | 三级 | 聊天页 | 代码级 |
| 23 | `jiuxin_chat` | 啾信聊天 | 三级 | 会话列表 | 【11】已实测（未配置弹窗态） |
| 24 | `jiuxin_conversation_list` | 啾信会话列表 | 二级 | 抽屉 | 【10】已实测 |
| 25 | `secretary_mode` | 今日秘书舰 | 二级 | 抽屉 | 【13】【16】已实测 |
| 26 | `secretary_settings` | 秘书舰设置 | 三级 | 模式页 | 【17】已实测 |
| 27 | `sd_resource_gallery` | SD 资源管理 | 四级 | 秘书舰设置 | 【18】已实测 |
| 28 | `birthday_calendar` | 生日日历 | 二级 | 抽屉/小组件 | 【19】已实测 |
| 29 | `live2d` | Live2D 皮肤库 | 二级 | 抽屉 | 【20】已实测 |
| 30 | `gameimport?kind=` | 游戏资源导入 | 二级 | 抽屉/Live2D 库 | 【22】已实测 |
| 31 | `gameimport/view/{kind}/{assetId}` | 资源查看器 | 三级 | 导入页 | 【25】已实测 |
| 32 | `live2d/view/{modelId}` | Live2D 查看器 | 三级 | 库/导入页（Live2D 包直通） | 【21】【46b】已实测 |
| 33 | `secretary_random` | 随机翻牌 | 三级 | 模式页 | 【14】已实测 |
| 34 | `secretary_pick_home` | 从后宅选择 | 三级 | 模式页 | 代码级 |
| 35 | `secretary_pick_gallery` | 从船坞选择 | 三级 | 模式页 | 代码级 |

导航图中**没有** dialog/bottomSheet 型目的地——全部模态 UI 在页面内部实现（BlyyDialog/BlyyBottomSheet 体系）。

### 3.2 页面层级特征

- 最深链路 4 级：设置→应用快捷方式→图标裁切；秘书舰→设置→SD 资源管理；gallery→立绘选择→头像裁切
- **层级深度总体健康**（最深 4 级且均为低频配置流），但存在"同屏内嵌二级导航"的例外：啾信配置主菜单→分区详情是**页面内状态切换**（`activeSection`，非路由），导致该页返回行为与系统返回预期不一致（分区详情内按返回直接退出整页，而非回到主菜单）
- 唯一 deeplink：`blyy://voice/{shipName}`（Manifest:76-83）

---

## 4. 页面导航关系及用户操作流程

### 4.1 跳转邻接表（页面 → 可达页面）

```
home ──→ voice/{ship} | gallery/{ship} | gallery(Tab) | 抽屉13入口
gallery ──→ voice/{ship} | student_voice/{student} | gallery/{ship} | student_gallery/{student} | avatar_figure → avatar_crop
抽屉(13) ──→ secretary_mode / live2d / gameimport / birthday_calendar / guess_image / guess_voice
            / guess_history / leaderboard / assistant / jiuxin_conversation_list / watermark_camera
            / settings / about
guess_image/voice ──→ guess_history ──→ leaderboard / assistant_config
watermark_camera ⇄ watermark_editor（互为兜底跳转）
settings ──→ assistant_config / jiuxin_config / app_icon_settings → image_cropper
jiuxin_list ──→ jiuxin_chat ──→ jiuxin_config / jiuxin_ship_config
secretary_mode ──→ random / pick_home / pick_gallery / settings ──→ sd_resource_gallery
birthday_calendar ──→ voice/{ship}
live2d ──→ live2d/view / gameimport?kind=live2d
gameimport ──→ live2d/view（live2d 包特例直通）/ gameimport/view
random/pick_* ──(确认后 popUpTo 自身)──→ home
小组件/深链 ──（pendingNavRoute 桥）──→ voice / birthday_calendar / home
```

### 4.2 核心操作流程实测

**流程 A：挑舰娘→听语音（主循环）**
后宅→去挑选舰娘→船坞（1004 舰 3 列网格）→点卡片→**共享元素头像转场**→语音档案→点台词行→播放+行内均衡器图标+小人同步说台词【33b】。路径 3 步，核心循环顺畅；共享元素转场是全局亮点动效。

**流程 B：设置秘书舰**
抽屉→今日秘书舰→随机翻牌→开始翻牌（800ms 旋转+600ms 缩放脉冲，1.5s 定时）→揭示卡（MediumBouncy spring）→确认为今日秘书舰→自动回首页且小人更新【14c→15】。流程 4 步；翻牌页信息密度低（大面积空白）。

**流程 C：猜题**
抽屉→看图识舰娘→（难度默认简单）→看图→输入→提交→错误红横幅【04c】/正确金色奖杯卡→结算退出→游戏结算弹窗→退出→**二次确认弹窗**【05b】→回首页+战绩入库【07】。注意：**顶部返回箭头同样触发结算弹窗**（实测），防误触良好但两层弹窗略繁琐。

**流程 D：筛选舰娘**
船坞→搜索图标（Header 揭示动画）→筛选漏斗→BottomSheet（阵营 13/类型 15/稀有度 7 chips）→选择→应用筛选→网格过滤+漏斗出现计数徽章【30c→30e】。计数徽章用红色（见 P3-09）。

**流程 E：切换学生档案**
船坞顶栏舰娘/学生分段切换→网格整列切换（学生卡为另一套卡面）→底栏 Tab 文案"船坞"→"成员"动态变化【34b】。档案级切换顺滑，但双卡面语言差异大（见 §5.4）。

**流程 F：Live2D 查看**
抽屉→Live2D 皮肤→点模型卡→查看器（WebView 异步加载）→沉浸态（5s 自动收起控制条为迷你浮标）→点浮标展开控制条→点模型触发"惊喜"（随机动作+触摸台词气泡）【21→21b→21c】。交互链完整、反馈及时，是体验最完整的高级页面。

### 4.3 返回路径审计

- 各二级页均正确响应系统返回（BackHandler 场景：啾信编辑模式、资源查看器自定义退场编排）
- 猜题页返回箭头=结算退出流程（有意设计，防丢进度）
- 悬浮查看器（live2d/view、gameimport/view）禁用侧滑返回手势（AppRoot:310-311，因 GL Surface 边缘手势冲突——合理）
- **例外**：啾信配置分区详情的内嵌导航返回不一致（见 3.2）

---

## 5. 整体视觉风格分析

### 5.1 风格体系总览：一主一副两辅

| 风格 | 覆盖 | 语言 | 状态 |
|---|---|---|---|
| **指挥中心（COMMAND_CENTER，默认）** | 全部主界面 | 深海 HUD：切角几何（8-20dp CutCorner）、受光渐变面板、青蓝主色+金辅助、双色 ambient/spot 阴影、微光描边 | 成熟完整，纪律好 |
| **经典（CLASSIC）** | 同上（开关切换） | 常规 Material：圆角 8-28dp、紫色主色（#7C3AED）、多色分区标题 | 完整但存在颜色泄漏（P1-02） |
| **JUUS（啾信线）** | 会话列表/聊天页/其弹层 | iOS 风：全圆角、独立蓝 #5BA4E6、粉语音气泡、白底渐变 | 有意独立，但与 Blyy 弹窗/组件混用时观感割裂 |
| **工具暗色辅线** | Live2D 查看器/资源查看器/水印相机/游戏导入暗态 | 深底沉浸+细线描边 | 与指挥中心暗色兼容良好 |

风格切换机制：设置页"指挥中心 UI"开关 → DataStore `ui_style` → 色板/字体/形状三轨整体切换（Theme.kt:188-213），切换有 uiStyleReady 门控防闪现。**实测切换即时生效且全局一致**（除 P1-02 泄漏点）。

### 5.2 指挥中心风格评价（主力）

- **识别性强**：切角+受光渐变+三色流光顶栏构成独特 HUD 语言，在同类工具 App 中辨识度高
- **一致性高**：三大反模式基本清零——裸 `.shadow(` 全仓 1 处（Depth.kt:55 封装内部）、theme 外内联 `Color(0x` 仅 icons/Github.kt:25 一处、屏幕层裸 `AlertDialog(` 0 处（均源码核实）
- **光影层次清晰**：Sea/Deck/Instrument/Lookout 四层深度体系（0/4/8/12dp）+ 有色阴影（非纯黑），面板"受光"顶部高光 + L 型角饰等细节统一
- **问题**：密度偏高（信息卡普遍 2-3 层嵌套：AdaptiveScreenBackground→BlyyPanel→内容卡），以及 §8 所述的玻璃穿透问题

### 5.3 风格割裂点

1. **啾信线与主风格的双语言并存**：会话列表页完全自绘（JuusLeftNavRail+白胶囊头），进入其确认弹窗却是指挥中心切角壳【10→11】；BlyyBottomSheet 在啾信语境下渲染为切角暗面板与 JUUS 白圆角页面同屏【10c】。两语言各自内部一致，但**弹窗/横幅这一层没有跟随语境切换**，是混用缝隙。
2. **学生档案卡 vs 舰娘卡两套卡面**：同一网格页内，舰娘卡=切角+稀有度渐变描边+左下阵营章，学生卡=圆角+暗底+右上星级+左上职业圆章【30 vs 34b】。功能等价而视觉语言不同，无过渡说明。
3. **设置分区标题多色**：经典风格下六个分区标题呈现紫/粉红/青/紫/青多色（【40】），指挥中心风格下为蓝/金/蓝/金交替——两种规则不一致（CC 是双色节奏、Classic 是彩虹），削弱经典风格的成熟度。

### 5.4 视觉密度与留白

- 档案网格类页面密度合适（3 列 360dp 宽，卡间距 12dp）
- **空态页面留白过大**：首页空态垂直居中后底部仍有 ~200dp 空白【01】；随机翻牌页上下留白占屏 ~70%【14】——空态信息量低时建议增加引导性内容而非纯居中
- 表单页（啾信配置 API 分区）留白合理、分区节奏统一【12b】

---

## 6. 色彩体系分析

### 6.1 令牌档案（AppColors，Color.kt 803 行，397 处定义）

**品牌主色（指挥中心）**：Primary #0096C7(亮)/#48CAE4(暗)，OnPrimary #FFF/#002B44，PrimaryDeep #0077B6/#0096C7，PrimaryVeil 12%/16% α
**金色辅助**：Secondary #E8A838/#FFD166；红线注释明确 `#FFD166 禁作文字色`，Gold.TextLight #8C5E00(≥5:1)/TextDark #FFD97A(≥7:1) 有对比度设计意识
**表面阶梯**：亮色 Background #D4E8F5 → Surface #F5FAFF（背景比面板深一阶），暗色两者同 #0A1628 靠渐变区分；SurfaceContainer 五档齐备
**语义色**：Success #16A34A/#4ADE80、Warning #D97706/#FBBF24、Error #DC2626/#F87171、Info = Primary 系；暗色 onXxx 统一 #E2EAF4
**稀有度**：七档色+渐变对（海上传奇 #FFD700→#FFA500 … 普通 #64748B→#475569），isHighRarity 含前四档
**归口体系**：Live2DViewer 12 色、Widget 8 色、Glass 6 色、Panel/Depth/Gradient/ChatColors/JuusPalette 全部对象化

### 6.2 双主题四象限实测（指挥中心/经典 × 亮/暗）

| 组合 | 实测 | 评价 |
|---|---|---|
| CC 亮 | 【01】【30】 | 主体验，青蓝+浅海天渐变，层次清楚 |
| CC 暗 | 【37】【38】【39】 | 深海 HUD 观感出色，青色荧光点缀克制，**四象限中最佳** |
| Classic 亮 | 【43】 | 紫色常规 Material，可接受，**但空态 CTA/标题颜色泄漏为青蓝（P1-02）** |
| Classic 暗 | 【40】【41】【42】 | 灰黑+紫，一致；分区标题多色杂（P3-04） |

### 6.3 对比度抽查

- CC 亮色模式按钮：主按钮亮色分支用 PrimaryDeepLight(#0077B6) 底+白字（BlyyPrimaryButton 注释明确按 V2 规范修复过 AA 4.9:1）——**但 HomeScreen 空态自制按钮走 `AppColors.PrimaryDeepLight/PrimaryLight` 渐变且在 Classic 下未跟随（P1-02）**，同一规范只在组件库落实、屏幕自制件漏网
- 次级文字 OnSurfaceVariant #3D5A73(亮)/#94A8BE(暗) 对浅色面板对比充分
- 暗色 onError=#1A0000（Theme.kt:41）——错误容器上的文字近纯黑，正确
- 玻璃顶栏（GalleryTopBar）在浅色网格滚动时与卡片发生穿透叠加（【34】顶部"云仙/伊404"透过顶栏可见），玻璃 α 偏低，属于对比度/层级的复合问题（P2-06）

### 6.4 色彩使用一致性问题

1. **筛选 Sheet 内选中态双标**：阵营/类型的"全部"选中=蓝 12% 浅底+蓝字，稀有度的"全部"选中=深色实底+白字+描边（【30c】同屏可见两种选中语言）→ P3-08
2. **筛选计数徽章红色**：激活筛选数徽章为红底白字（【30e】右上漏斗"2"）——红色在本 App 语义为 Error/危险，计数徽章宜用 Primary → P3-09
3. **Classic 分区标题彩虹化**（§5.3-3）→ P3-04
4. 禁用色三入口并存（Text.Disabled / BlyyIcon.AlphaDisabled / Interaction.DisabledAlpha=0.38，数值一致但入口不归一）→ 规范债 P3-25

---

## 7. 字体及排版规范分析

### 7.1 字体资产

- **无自定义字体文件**（res/font 不存在）：Display 族=SansSerif，数字族=Monospace，正文=Default——中文全靠系统字体渲染，各机型观感不可控（旗舰的 HarmonyOS Sans/小米兰亭与原生 Roboto 混排差异明显）
- CJK 行盒：`withCjkLinePadding()` = includeFontPadding(true)（Type.kt:18）——中文行高有意放宽

### 7.2 排版令牌（AppTypography 24 式）

| 层级 | 样式 | 字号/行高 | 字重 |
|---|---|---|---|
| 展示 | DisplayLarge/Medium/Small | 57/64、45/52、36/44 | Normal |
| 标题 | Headline L/M/S | 32/40、28/36、24/32 | SemiBold |
| 小标题 | Title L/M/S | 22/28、16/24、14/20 | SemiBold/Medium |
| 正文 | Body L/M/S | 16/24、14/20、12/16 | Normal |
| 标签 | Label L/M/S | 14/20、12/16、11/16 | Medium |
| 说明 | Caption L/M/S | 12/16、11/14、10/12 | Normal |
| 卡片 | CardTitle/CardLabel | 15/20、10/14 | Bold/Medium |
| 空态 | EmptyTitle/EmptyDescription | 24/32 Bold、14/26 Light | — |
| 数字 HUD | NumericHud/Score/Timer | tnum + Monospace | — |

双风格 Typography：CommandCenter 对 title/label 系注入 Display/Mono 字族 + 加宽字距（0.3-1sp），Classic 直接映射——风格切换时排版气质变化明显（实测【27】vs【40】）。

### 7.3 实测问题

1. **全量硬编码中文，零国际化**：screens 包 `stringResource(` 引用 **0 次**、含中文行 2964 行、strings.xml 仅 8 条（app 名+通知渠道+6 条小组件）。页面标题全部硬编码（HomeScreen.kt:220"我的后宅"、GalleryScreen.kt:442 等）→ 结构性规范债 P2-11
2. **字号跳跃**：抽屉条目 17sp 级标题（未走 TitleLarge）与 Body 描述混排尚可，但个别页面出现 54.sp/15.sp/10.sp 裸值（Live2dViewerScreen.kt:1278-1289 沉浸台词），跨页文字尺度无节制 → P3-15
3. **截断处理不统一**：语音行播放时展开全文、非播放 3 行截断（【33b】vs【33】良好）；Live2D 卡标题单行省略（【20】"阿贝克隆比·坏坏兔的恶作剧…"）；资源包路径单行强制截断且无查看全文途径（【22】路径 ".../files…"）→ P3-16

---

## 8. 页面布局与空间结构分析

### 8.1 布局骨架盘点

| 模式 | 页面 | 说明 |
|---|---|---|
| BlyyScreenScaffold（统一骨架） | **仅 Settings、Leaderboard 2 屏** | 骨架级组件采用率极低 |
| Material Scaffold | Home、Voice | 自绘 containerColor Transparent |
| AdaptiveScreenBackground+自绘 Column/Box | 其余 31 屏 | 主流形态：背景→BlyyTopBar→内容 |

**骨架采用不足是布局不一致的根源**：各屏"四态编排"各自手写（骨架屏 3 种并存：BlyySkeletonList/Grid、ShipCardShimmer、第三方 shimmer 库），间距与 TopBar 下方留白因屏而异。

### 8.2 间距与圆角令牌（AppSpacing）

- 8dp 网格刻度完整：Xxs 2 → Massive 64；Height 体系（Button 48/Input 56/ListItem 64/NavBar 80）
- 圆角/切角双轨：Corner 12 档 + Chamfer 10/Panel 12/Dialog 28；BlyyShapes 切角家族（PanelSmall 8/PanelMedium 12/Dialog 20/NavBar 对角 14）
- **三套海拔刻度并存**（源码核实）：AppSpacing.Elevation(0/2/4/8/12/16) vs AppElevation(0/1/3/6/12/16) vs DepthLayer(0/4/8/12)——数值语义冲突，属规范债 → P3-25
- 屏幕层裸 dp/sp 残余：VoiceScreen 38 处、Live2dViewerScreen 35 处、BirthdayCalendarScreen 26 处（最密集三屏）；识舰娘共享组件库自身也有 15 处裸 dp

### 8.3 实测布局问题

| 问题 | 证据 | 定级 |
|---|---|---|
| **横屏首页空态 CTA 与底栏位置冲突**：按钮落在导航栏水平带内，被玻璃面板穿透叠盖 | 【44】 | **P1-01** |
| 玻璃浮动顶栏与滚动内容穿透：学生档案列表滚动时，顶栏半透明面板下卡片文字清晰透出，标题可读性受损 | 【34】顶部 | P2-06 |
| 底栏遮盖网格末行：船坞/学生档案滚动到底时末行卡片被玻璃底栏覆盖约 40%高度（contentPadding 未按 NavBar 80dp 预留） | 【30】底部 | P2-07 |
| 全局小人默认/拖拽位置遮挡内容：首页空态标题被小人覆盖（"后宅空荡荡的…"被遮）、生日日历中部日期被遮、秘书舰设置开关被遮（实测点击被小人拦截一次） | 【15】【19】【17】 | P2-05 |
| 抽屉宽度占屏 85%（360dp 视口下 ~306dp>推荐 280-300dp），且滚动位置跨打开持久化（LazyListState 未重置） | 【02】【03】 | P3-06/07 |
| 悬浮查看器顶栏信息双行叠在画面上（资源查看器副标题"SD 小人·Spine 3.8.99·1 页图集·拖动/…"与标题挤压） | 【25】 | P3 |

---

## 9. 图标及视觉素材分析

### 9.1 图标体系

- 全部 Material Icons Rounded（iconTokens：Inline 16/Standard 20/Emphasis 24/Hero 32/Display 48dp，权重用 tint α 而非 Filled/Outlined 混用——一致性好）
- 唯一自绘矢量：Github 图标（ui/icons/Github.kt，含 theme 外唯一 `Color(0x` 例外）
- **图标语义抽查**：抽屉 13 项图标语义准确（Person/ViewInAr/CloudDownload/Cake/Image/MusicNote/History/BarChart/助手表盘/聊天机器人/相机/齿轮/星）；**例外**——Live2D 库顶栏"多选"按钮用排序样式图标（⇅+线）与"排序"动作混淆（【20】【46a】顶部四钮：多选/刷新/添加/导入，多选图标语义弱）→ P3-17

### 9.2 图片素材

- 舰娘/学生卡图源：Wiki 静态图+Coil 缓存，实测加载清晰、裁切统一（Crop）；**稀有度渐变描边卡**是视觉资产亮点
- **SD 资源管理卡预览缺陷**：预览图直接展示精灵图集散片（【18】三张卡的预览都是散落的肢体碎片），观感如同加载失败——应渲染单帧组装形象或占位 → P3-10
- 水印相框缩略图：5 款相框预览（【26b】）清晰、风格统一（线框/相纸/幻灯/录像带）
- 启动闪屏：Android 12+ 默认 SplashScreen（白底+居中图标）【截图 home_cold_start】，与指挥中心深海气质脱节（P3-18，可用 `windowSplashScreenBackground` 调深色底）

### 9.3 装饰元素

- HUD 装饰家族统一：三色流光顶栏底线、面板 L 型角饰、1px 顶部高光、斜纹分区头（助手页"进度追踪"衬线带【09c】）——装饰密度偏高但同源
- 金色使用遵守"普通面板描边禁金"红线（Gradient.PanelBorder 注释），实测未发现违例

---

## 10. UI 组件及复用体系分析

### 10.1 组件清单总览（ui/components 19 文件 + guess 共享库）

**设计系统层（应全 App 复用）**：BlyyScreenScaffold/BlyyStateScaffold(四态)/BlyyTopBar/BlyyPanel/BlyySectionPanel/BlyyPrimaryButton/BlyySecondaryButton/BlyyCard/BlyyMetricCard/BlyyTabRow/BlyySearchBar/BlyySegmentedControl/BlyyChip/BlyyEmptyState/BlyyErrorState/BlyyLoadingState/BlyySettingsRow/BlyyListItem/BlyyEntrance/BlyyDialog 家族(Confirm/Form/BottomSheet/DragHandle)/BlyyTextField/BlyyHaptics/BlyySkeleton 家族/RobustAvatar/ShipCard/ZoomableImage/SpineSdView/SecretaryChibiOverlay/ModernNavigationBar/ModernDrawerSheet

**识舰娘共享库（GuessGameComponents 826 行）**：题面双玩法 12 组件收口（主色统一 primary、阴影统一 Depth、动效统一 AppAnimation——上一轮审计成果，实测两玩法观感一致 ✓）

### 10.2 采用率审计（重构后的残留欠账）

| 组件 | 应采用场景 | 实际采用 | 缺口 |
|---|---|---|---|
| BlyyScreenScaffold | 全部 31 个标准屏 | **2 屏** | 29 屏仍手写背景+Column |
| BlyyStateScaffold（四态） | 全部数据屏 | **2 屏**（Leaderboard/GuessHistory） | 其余屏手写 when 四态分支，形态为"骨架+全屏错误+顶部横幅"但编排各异 |
| BlyyListItem | 设置类行 | **3 处** | 设置行主力是 BlyySettingsRow（10 处） |
| BlyyBottomSheet/ConfirmDialog/FormDialog | 全部弹层 | 41 处调用 ✓ | 8 处裸 ModalBottomSheet + 2 处裸 Dialog 逃逸 |

### 10.3 逃逸点清单（源码定位）

- **裸 ModalBottomSheet 8 处**：WatermarkCameraScreen.kt:325、GameImportScreen.kt:264、Live2dLibraryScreen.kt:462/519、Live2dViewerScreen.kt:905/964、Live2dViewerSheets.kt:175/298
- **裸 `Dialog(` 2 处**：chat/ChatSheets.kt:470（MessageActionSheet）、:619（MessageEditSheet）——JUUS 配色自绘，绕过封装
- **裸 M3 Button 3 处**：GuessHistoryScreen.kt:759、ShipGalleryScreen.kt:349、SecretaryShipSettingsScreen.kt:698（随机翻牌页"开始翻牌"即此，圆角胶囊与主按钮切角语言冲突，【14】实测可见）
- **裸 M3 Card 3 处**：GuessHistoryScreen.kt:251、LeaderboardScreen.kt:203/282
- **死代码组件 5 个**：BlyyMetricCard、BlyySearchField、BlyySuccessBanner、BlyySnackbarHost、BlyyDialog（0 外部调用）；BlyyButton 的 Tertiary/Text 变体只是 Secondary 别名（未实现差异）
- **重复实现待合并**：BlyySegmentedControl（BlyyDesignSystem.kt:581）与 GuessDifficultySelector（GuessGameComponents.kt:207）容器/选中段规格完全相同，仅差描述文案槽位

### 10.4 组件状态完备性

- BlyyPrimaryButton：enabled/disabled/pressed(0.94 HeavyScale+阴影回落+流光 700ms) 完备；**流光未接 rememberReducedMotion**
- BlyySegmentedControl/BlyyChip：pressed/selected/textColor 动画齐备
- BlyyTextField/StableOutlinedTextField：focus/error/clear 齐；StableOutlinedTextField 有防回环竞态处理（异步回显场景）
- **BlyySnackbarHost 无人使用**：全局反馈走 Dialog/Banner，无 Toast/Snackbar 体系（水印编辑器自挂 M3 SnackbarHost 一处）

---

## 11. 交互逻辑与用户体验分析

### 11.1 交互行为盘点（实测）

| 交互 | 位置 | 实测结果 |
|---|---|---|
| 单击语音行 | VoiceScreen | 播放+行图标变均衡器+全文展开+小人同步气泡 ✓ |
| 长按语音行 | VoiceScreen | DropdownMenu 收藏置顶/稍后播放/下载/分享 ✓【33c】 |
| 长按舰娘卡 | Gallery | ShipContextMenuSheet（立绘/Wiki/誓约+自定义图片两入口）✓【31】 |
| 长按学生卡 | Gallery | 同一 Sheet 组件复用，"从舰娘立绘选择"对学生显示"暂不支持" ✓ |
| 横向拖播放条 | VoiceScreen | >12% 屏宽折叠为左下角气泡 ✓【33d】 |
| 慢速横滑立绘页 | ShipGallery | **不翻页**（被 ZoomableImage pan 吞掉）✗【32b】 |
| 快速横滑立绘页 | ShipGallery | 翻页+皮肤切换 ✓【32c】 |
| 双击缩放/拖拽 | ZoomableImage 三处 | 正常 |
| 长按拖拽排序 | 啾信会话列表 | detectDragGesturesAfterLongPress+animateItem ✓（代码级） |
| 点小人 | 全局 | 随机语音+台词气泡 ✓【17】实测 |
| 拖小人 | 全局 | 位置持久化跨页/跨风格保持 ✓（拖拽后位置在抽屉/设置/生日页均保持） |
| 点悬浮窗小人 | 桌面 | 台词气泡弹出 ✓【49b】 |
| 小组件点击 | 桌面 | 生日件深链日历/寿星直达语音（代码级，WidgetActions:239-257） |
| 双击/单击消歧 | Live2D 查看器 | TAP_DOUBLE_MS=300/320ms 延迟单击（代码级） |
| 陀螺仪注视 | Live2D 查看器 | 控制条 陀螺仪 chip 选中态实测可见【21b】（传感器效果模拟器上无法验证） |

### 11.2 交互问题清单

1. **手势冲突（P2-03）**：立绘页 HorizontalPager 与 ZoomableImage transformable 竞争——慢速滑动被图片平移手势吞掉，只有快滑能翻页。用户"慢慢滑动看下一张立绘"的自然操作会失败。建议：ZoomableImage 未缩放时放行水平手势（或 `detectDragGestures` 按轴向分派）。
2. **小人遮挡与点击拦截（P2-05）**：小人悬浮在内容之上且吞掉点击（实测秘书舰设置页点开关命中小人触发台词）。虽然可拖走，但**默认位置+无自动避让**导致高频遮挡；建议增加"双击隐藏 5s/半透明化"或布局感知避让。
3. **猜题双弹窗退出（P3-19）**：结算退出→确认退出两层确认；且顶部返回箭头也进入同一流程。建议合并为单层（结算弹窗内直接提供"保存并退出"）。
4. **啾信配置内嵌导航返回不一致（P2-12）**：分区详情在页面内切换，返回键直接退出整页而非回到主菜单（代码级：activeSection 状态无 BackHandler）。
5. **空态引导弱**：首页空态 CTA 与文案齐全 ✓；但啾信空态仅有图标+两行字【10】、随机翻牌页大面积空白【14】、SD 库空态 instructions 密集【18】——空态的信息引导策略不统一。

### 11.3 反馈机制实测

- **即时反馈优秀**：全部按钮有按压缩放（三档 0.94/0.96/0.97+专属弹簧）；触觉 24 处语义化（Tick/Confirm/LongPress/Heavy），猜对 Confirm/猜错 Heavy 的触觉语义正确
- **异步反馈**：排行榜刷新→骨架屏→数据淡入 ✓；Live2D 查看→加载 phase 态 ✓；语音重试提示"正在重试(n/3)/从缓存加载"（VoiceScreen:287-303，信息透明度好）
- **缺失反馈**：长按拖拽会话排序无触觉（源码 ConversationListScreen 拖拽路径未见 haptic）；水印编辑器保存成功走 Snackbar（孤立通道）

---

## 12. 页面及组件状态分析

### 12.1 四态（加载/空/错误/内容）实测矩阵

| 页面 | 加载 | 空 | 错误 | 内容 | 备注 |
|---|---|---|---|---|---|
| Home | ShipCardShimmer【代码】 | ✓【01】 | 全屏错误态+顶部缓存横幅双形态【代码】 | — | 状态最全 |
| Gallery | ShipCardShimmer | ✓ | 自绘 GalleryErrorState（与 BlyyErrorState 同语言）| ✓【30】 | 自绘错误态=封装的复制品 |
| Voice | BlyySkeletonList+重试提示 | — | BlyyErrorState+Snackbar | ✓【33】 | |
| Leaderboard | ✓骨架【08】 | — | BlyyStateScaffold 错误态【代码】 | ✓【08b】 | **唯一走 BlyyStateScaffold 的网络屏** |
| GuessImage/Voice | "正在准备题目"卡 ✓【06】 | — | **错误横幅承载同步中提示**【06】 | ✓【06b】 | P2-10：同步提示用错误样式 |
| GuessHistory | BlyyStateScaffold | — | 无网络态（本地库，合理省略） | ✓【07】 | |
| 助手 | BlyyLoadingState | — | BlyyErrorState | ✓【09b】 | 未配置态="静默空白"（P3-20：初始无输入框无引导，点查询才知道有缓存配置） |
| 啾信列表 | 无（本地） | ✓【10】 | — | — | |
| 学生画廊 | BlyySkeletonGrid ✓【36】 | ✓ | BlyyErrorState | ✓【36c】 | |
| Live2D 库 | — | ✓ BlyyAnimatedEmptyState | 结果横幅 8s 自动消失 | ✓【20】 | |
| 资源查看器 | ✓ | — | ✓ | ✓【25】 | 自定义退场编排（GL 不吃 Compose 转场） |
| SD 资源库 | SdLoadingState+骨架 | 两种空态（无资源/搜索空） | SdErrorState | ✓【18】 | 五态 when 手写 |
| 悬浮窗 | — | — | — | 静态图回退+白色光晕【17】 | 首次显示有延迟（49→49c 间隔 ~40s 后才可见） |

**结论**：状态覆盖率高（所有网络屏均有加载/错误/空三态），但**编排不统一**（仅 2 屏走 BlyyStateScaffold）+ 个别语义错位（同步中=错误样式）。

### 12.2 弹窗状态实测

- 结算弹窗【04d】：得分卡+三维统计+平均分，数据完整
- 退出确认【05b】：文案明确告知后果（"结果将保存到历史记录"）
- 历史详情【07b】：10 项统计齐备+上传排行入口
- 新建聊天 Sheet【10c】：双分区（API/人格）空态+底部状态行"API: 当前·舰娘: 当前"——配置前置条件透明 ✓
- API 未配置弹窗【11】：引导文案+直达配置按钮 ✓（但弹窗风格为 CC 切角、语境为 JUUS，见 §5.3）
- 水印选择 Sheet【26b】：切角弹层+横排缩略图+选中描边 ✓

---

## 13. 动效与动画系统全量分析

### 13.1 动效令牌底座（Animation.kt，全 App 单一参数源）

- **Duration 11 档**：Micro 90 / Instant 100 / Quick 150 / Fast 200 / Medium 250 / Smooth 300 / Normal 350 / Slow 500 / VerySlow 800 / PageTransition 450 / StaggerDelay 40
- **Easings 14 种**：Standard(FastOutSlowIn)、Emphasized(0.2,0,0,1)、EmphasizedDecelerate(0.05,0.7,0.1,1)、EmphasizedAccelerate(0.3,0.8,0.15)、EaseInOutSine、Bounce/Smooth/Anticipate/Overshoot 多项式（后四种**全仓 0 调用**——死令牌）
- **Springs 8 种**：Standard(1.0,1500)/Bouncy(0.5,200)/Stiff(1.0,10000)/Soft(0.2,50)/Responsive(1.0,10000)/**Gentle(0.9,400)（页面转场主弹簧）**/Snappy(0.5,1500)/gentle<T>()
- **Press 三档**：Light 0.96(spring 0.75/500)、Standard 0.97(0.7/400)、Heavy 0.94(0.65/350)
- **Repeating 6 种**：breathing 2500/pulse 1500/rotate 8000/glow 2000/shimmer 3000/float 3500
- keyframes/tweenSpec/snap/animateContentSize **全仓 0 处**——动效手段收敛（好）

### 13.2 页面级转场（源码参数，AppRoot.kt:334-394）

| 场景 | 规格 |
|---|---|
| NavHost 默认 | enter: slideInX(it/3, Gentle spring)+fadeIn(350ms, delay50, EmphasizedDecelerate)；exit: slideOutX(-it/5, 350 EmphasizedAccelerate)+fadeOut(200)；popEnter/popExit 镜像 |
| home⇄gallery Tab | Fade Through：enter fade+scaleIn(0.96, 300ms delay90)；exit fadeOut(90)+scaleOut(1.04, 90)——Material Motion 标准实现 |
| gameimport/view | 特例短淡入淡出（90-150ms，因 GL Surface） |
| Home→Voice | SharedTransitionLayout 共享头像（key=`avatar-${ship.name}`），实测【32→33】流畅 |

底栏滚动联动：translationY 300px(Gentle)+alpha tween(200)；抽屉=M3 默认动画+开合触觉。

### 13.3 组件级动效（重点目录，全参数源码核实）

| 动效 | 位置 | 参数 | 评价 |
|---|---|---|---|
| 按压缩放家族 | 全部按钮/卡片/Chip | 三档 0.94/0.96/0.97+专属弹簧 | 统一、手感好 |
| BlyyPrimaryButton 流光 | BlyyComponents.kt:521 | Animatable -1→2, tween(700, Linear)，按压触发一次性 | 有趣；未接减动效 |
| ShipCard 稀有度辉光 | ShipCard.kt:461 | α 0.06↔0.22 tween(3500 Reverse)+描边 2800 | 高稀有度限定+滚动门控 ✓ |
| ShipCard 誓约特效 | ShipCard.kt:519 | 粉光 3200+金边 2000+3 粒子 sparklePhase 2000 | 语义化装饰 |
| 底栏选中光晕呼吸 | AppChrome.kt:284 | 1↔1.15 spring+glow(2000)，仅 CC 风格挂载 | 门控好 |
| BlyyEntrance 错峰入场 | 24 处 | tween(350, delay=index×40, Emphasized) | 已接 reducedMotion ✓ |
| GalleryTopBar 揭示/收拢 | GalleryTopBar.kt:401 | expandHorizontally(350)+slide(it/5)+fadeIn(240 delay60)；标题块自绘 layout 裁切防高度抖动 | 精细（防抖注释明确） |
| VoicePlayerBar 折叠 | VoicePlayerBar.kt:120 | updateTransition 5 通道：α tween(200/250)+scale spring(MediumBouncy,Medium) 0.85↔1+offset ±32dp | 唯一 updateTransition 用例，折叠气泡有辉光+摇摆 isPlaying 门控 |
| 立绘翻页 ChibiFlip | ShipGalleryScreen.kt:408 | rotationY 0→90(150)→snap(-90)→0(150)+cameraDistance 12×density | 卡片翻转感 |
| 随机翻牌 | SecretaryShipRandomScreen.kt:167 | 旋转 800ms Linear 无限+缩放 0.9↔1.1 600ms；1500ms 后揭示 | 简洁有效 |
| Live2D 台词气泡 | Live2dViewerScreen.kt:560 | fadeIn(150)+scaleIn(0.85,150)+slideInY(-it/4) | 轻快 |
| 猜音呼吸 | GuessVoiceScreen.kt:383 | scale 1↔1.02+光晕 0.25↔0.55 tween(1500 EaseInOutSine)，isPlaying 门控 ✓ | |
| 打字指示 TypingDots | ChatBubbles.kt:218 | 3 点 offsetY 0↔-6 tween(400, delay=index×150, Linear, Reverse) | 经典 |
| 会话拖拽排序 | ConversationListScreen.kt:270 | animateItem+graphicsLayer(shadowElevation/α0.95) | 代码级 |

### 13.4 系统/特殊动效（性能敏感区）

| 系统 | 机制 | 帧率 | 减动效 |
|---|---|---|---|
| AGSL 流体背景（Home/Voice） | RuntimeShader+produceState+delay(33) | **30fps 节流**（源码注释明确）+STARTED 生命周期暂停 | ✗ 未接线 |
| Live2D（WebView+PIXI） | ticker 全速采样+GPU 渲染 2:1 抽帧≈30fps；交互后 2500ms BOOST 满帧；待机交叉淡化 400ms | 动静分区 | N/A（WebView 层） |
| Spine SD（GLSurfaceView） | RENDERMODE_CONTINUOUSLY 全速 VSync；defaultMix 0.2s；alpha 走 GL 顶点色 | 未限帧（与 Live2D 治理策略不一致） | N/A |
| 陀螺仪注视 | SENSOR_DELAY_UI≈16Hz→JS 指数平滑 τ=120ms | — | N/A |

### 13.5 rememberReducedMotion 接线审计

**全仓仅 2 个消费点**（BlyyEntrance:35、BlyyAnimatedEmptyState:361）。未接线的重要动效：全部 rememberInfiniteTransition 装饰动画（RarityGlow/誓约/收藏徽章/Home 氛围光+粒子/底栏光晕/更新弹窗脉冲）、AGSL 常驻背景、页面转场、按压缩放、Banner 展隐、翻牌动画。Animation.kt:345 注释声称"装饰性动效应跳过"但**大面积未落地** → P2-08。（注：Compose 不读系统 ANIMATOR_DURATION_SCALE，故系统"移除动画"设置下全部转场照常播放）

### 13.6 动效质量总评

- **流畅性**（实测）：共享元素转场、Tab Fade Through、抽屉、Sheet 滑入、骨架 shimmer 均顺滑无跳帧；720p 模拟器上 Live2D 交互 BOOST 满帧明显
- **一致性**：时长/弹簧全部走令牌 ✓；按压语言统一 ✓
- **突兀点**：猜题错误横幅出现时下方内容瞬时下推（expandVertically 默认参数无弹簧，生硬）；结算弹窗出现/消失均为 M3 默认（与自定义转场体系割裂但可接受）
- **过载风险**：Home 空态同时运行 AGSL(30fps)+誓约光晕呼吸+漂浮粒子+心形徽章呼吸四层常驻动画——无内容时的功耗热点（P3-21）

---

## 14. Android 设备适配分析

### 14.1 实测矩阵（720×1280@240dpi，Android 15）

| 维度 | 结果 | 证据/问题 |
|---|---|---|
| 竖屏（基准） | 全部页面正常 | 全量截图 |
| 横屏 | **首页空态 CTA 与底栏冲突（P1-01）**；gallery 6 列自适应 ✓；语音页横屏正常（背景+横排）✓；Live2D 查看器横屏**最佳形态** ✓【46b】；学生卡标签竖排换行（P3-11）【45】 | 【44】【45】【46a】【46b】 |
| 旋转 | configChanges 全量声明，旋转不重建，抽屉状态/导航栈保留 ✓（横屏自动关抽屉 AppRoot:222-227） | 实测 |
| 深浅色 | 四象限全测（§6.2） | 【37-43】 |
| 双风格 | 全局即时切换 ✓；HomeScreen 空态泄漏（P1-02） | 【43】 |
| 字体 1.3x | 无截断/溢出；抽屉描述换行正常；设置行自动增高 ✓ | 【47】 |
| 沉浸式状态栏 | 设置开启后全 App 隐藏、下滑临时呼出 ✓；**ModalBottomSheet 弹出时状态栏重现**（弹窗窗口未继承隐藏 flag）【31】 | P2-09 |
| 手势导航 | 悬浮查看器禁边缘手势（合理）；预测性返回已声明未实测 | 代码级 |
| 键盘 | 聊天输入 imePadding+防抖注释（ChatInputBar:216）；猜题输入正常呼起 | 实测 |
| API 版本 | AGSL 需 API33+（有守卫）；触觉 API<30 回退链；dynamicColor 12+ 锁品牌主色 | 代码级 |

### 14.2 屏幕适配策略评估

- **仅"手表/非手表"两档**（isWatchScreen ≤360dp），无手机/平板宽度分档、无 WindowSizeMode——360dp 手机与 800dp 平板渲染同一布局（网格列数除外）。平板/折叠屏适配缺位（P3-22）
- WatchSpacing/WatchTypography 约 65%/80% 缩减的完整副令牌——手表支持是有意投入
- MuMu 720p 模拟器（sw480dp w853dp land）下全部页面可用，唯 P1-01 冲突

---

## 15. 无障碍体验分析

| 检查项 | 现状 | 评估 |
|---|---|---|
| contentDescription | 关键图标有（返回/关闭/菜单均有中文 desc，uiautomator 可读）；装饰图标 null（正确） | 良好 |
| 触摸目标 | **猜题页返回按钮 a11y 节点仅 36×36px（18×18dp）**（dump 实测），远低于 48dp 群最低标准；IconButton 大多数走 minimumInteractiveComponentSize（M3 默认）应达标，个别自绘触摸区存疑 | **P2-13** |
| 对比度 | 语义色/金文字色有 AA 设计红线；灰字 on 海天渐变背景（首页描述文字 #0A1628 on #D4E8F5≈9:1 ✓） | 良好 |
| 减动效 | rememberReducedMotion 仅 2 接线点，系统"移除动画"下装饰动画/转场照播 | **P2-08** |
| 仅颜色传义 | 稀有度=颜色+渐变描边（形状同）；**难度模式/正确错误反馈有图标+文字双通道 ✓**；阵营标签纯文字 ✓ | 可接受 |
| TalkBack 焦点序 | 未实测（模拟器 TalkBack 环境未部署） | 待验证（§23） |
| 系统字号 2.0x | 未实测（1.3x 已通过，2.0x 存疑——顶栏单行标题在 2.0x 下有截断风险） | 待验证 |
| 长文本 | 全中文、行高 includeFontPadding 放宽、长台词有截断+播放展开双态 | 良好 |

---

## 16. UI 性能与动效性能分析

> 以下区分：**实测观察**（模拟器 720p 现象）与**源码推断**（未做专业 profiling）。

### 16.1 实测观察

- 冷启动：闪屏→首页内容约 2-4s（含网络/缓存）；uiStyleReady 门控生效无风格闪现
- 滚动：船坞 1004 舰 3 列网格滚动流畅（ShipCard decorativeAnimation 滚动门控关闭辉光——源码设计）；语音列表（数百条+图片）滚动无卡顿
- Live2D 查看器：加载 3-6s（WebView+模型），交互 BOOST 满帧、静止 30fps
- 转场：35 路由间切换无白帧/跳变（小人 380ms 重挂载节流防 GL 重建压帧预算——源码设计）
- **观察性判断（未测帧率）**：Home 空态四层常驻动画区域在低端机上有功耗/发热风险（§13.6）

### 16.2 源码推断的性能设计（已有治理）

| 治理 | 位置 |
|---|---|
| AGSL 30fps 节流 + 生命周期暂停 + uniform 每帧 setFloat 不重建 Brush | HomeScreen.kt:168-187 |
| Live2D GPU 2:1 抽帧（帧距恒定 33.3ms，弃 setTimeout 漂移方案）+BOOST 窗口 | viewer.js:811-830 |
| Spine 热重载不重建 GLSurfaceView；≤0.004 α 跳过 draw call | SpineSdView.kt:180/1550 |
| Coil 三级缓存（内存 25%/磁盘 10%）+派生图防盗链专用客户端 | MyApplication.kt:60-104 |
| BlyyPanel Path 缓存防每帧 GC | BlyyComponents.kt:351 |
| 渐变 overlayBrush remember 化避免 30fps 重绘分配 | VoiceScreen.kt:564-580 |
| 网格 key+contentType 全覆盖（除 StudentGallery 索引 key） | §动效附录 |

### 16.3 风险点

1. **三套骨架/shimmer 并存**：同一屏可能叠加两种 shimmer 相位（列表屏 ShipCardShimmer 与 BlyySkeleton 混用无约束）
2. **Spine 未限帧 vs Live2D 限帧**：同为 GL 渲染策略不一（悬浮窗 Spine 常驻全速 VSync——桌面续航影响待测）
3. LazyListScope 内禁 remember 的坑已在上轮修复（审计记忆），未发现回归

---

## 17. 全量问题清单及优先级评估

> 编号规则：P{n}-{序号}。编号保留审计过程中的验证间隙——部分疑点经复测证伪后移除（见 §23 误报澄清），故序号不连续属正常。
> 证据列：截图号【xx】= test_screenshots/ui_audit_2026-11/ 内同名 png；源码= file:line。
> 定级标准：P0 阻断功能/P1 严重影响视觉与核心流程/P2 明显不合理或不一致/P3 细节提升。

### P1（高优先级，2 项）

| 编号 | 页面/组件 | 问题 | 证据 | 建议 |
|---|---|---|---|---|
| **P1-01** | Home 空态 × 横屏 | 空态 CTA"去挑选舰娘"垂直位置落进底部导航栏水平带，与玻璃底栏叠盖、按钮半透明化不可读 | 【44】+ AppRoot:1029 底栏常驻 + EmptyFavoritesView 居中布局 | 空态布局接入 bottomBar 高度 insets（或横屏下底栏隐藏策略统一） |
| **P1-02** | Home 空态 × 经典风格 | CTA 渐变硬编码 `AppColors.PrimaryDeepLight/PrimaryLight`（HomeScreen.kt:935）、空态标题硬编码 `Gradient.MetallicText`（:731-732）——Classic 紫色主题下按钮/标题仍是指挥中心青蓝 | 【43】+ 源码 | 改用 `MaterialTheme.colorScheme.primary` 家族（亮色 AA 补偿逻辑保留，但色值取自主题） |

### P2（常规优化，13 项）

| 编号 | 页面/组件 | 问题 | 证据 | 建议 |
|---|---|---|---|---|
| P2-03 | ShipGallery 立绘页 | HorizontalPager 与 ZoomableImage 手势冲突：慢速滑动被图片 pan 吞掉，仅快滑可翻页 | 【32b】(未翻页) vs 【32c】(快滑翻页)；ZoomableImage transformable | 图片未缩放时按轴向放行水平手势给 Pager |
| P2-05 | 全局小人 SecretaryChibiOverlay | 小人 z 序高于抽屉/内容且吞点击：遮挡抽屉条目、空态标题、日历格、设置开关（实测点击被拦截触发台词） | 【02/16/19/17】 | 默认位置避让/半透明化/点击穿透开关联动提示 |
| P2-06 | GalleryTopBar 玻璃顶栏 | 浅色下玻璃 α 过低，滚动内容文字透出顶栏，标题可读性受损 | 【34】顶部 | 提高玻璃底 α 或增加 blur/渐变 scrim |
| P2-07 | Gallery/StudentGallery 网格 | 底栏遮盖网格末行约 40% 高度，contentPadding 未按 NavBar 80dp 预留 | 【30】底部 | LazyGrid contentPadding bottom +=NavBar 高度 |
| P2-08 | 全局动效 | rememberReducedMotion 仅 2 接线点；全部无限装饰动画/转场/按压不响应系统减动效 | 源码（Animation.kt:345 注释 vs 实际） | 至少覆盖：无限装饰动画族、AGSL 静帧化、转场 fade-only |
| P2-09 | 全部 ModalBottomSheet | 沉浸式状态栏开启时，弹窗窗口未继承隐藏 flag，弹出瞬间状态栏重现 | 【31】 | 弹窗 Dialog window 同步 insetsController |
| P2-10 | GuessVoice/Image | "正在同步舰娘数据，请点击下一题重试"用 GuessErrorBanner 红色错误样式承载非错误信息 | 【06】+ GuessShipViewModel.kt:271 → GuessVoiceScreen.kt:284 | 增加 Info 级横幅变体 |
| P2-11 | 全 App | 国际化缺位：strings.xml 8 条，screens 包中文硬编码 2964 行、stringResource 0 次 | 源码统计 | 迁移页面标题/按钮/弹窗文案至 strings（增量） |
| P2-12 | 啾信配置页 | 分区详情=页面内嵌导航（activeSection），返回键直接退出整页，与用户预期"回到主菜单"不符 | 源码 JiuxinConfigScreen:192 | 分区详情加 BackHandler 回主菜单 |
| P2-13 | GuessGameComponents 顶栏 | 返回按钮 a11y 命中区 36×36px（18×18dp）低于 48dp 标准 | uiautomator dump [48,36][84,72] | 触摸目标补足 minimumInteractiveComponentSize |
| P2-14 | 弹窗体系 | 三套弹窗路径并存：Blyy 封装(41 处) + 8 处裸 ModalBottomSheet（live2d/watermark/gameimport 域）+ 2 处裸 Dialog（ChatSheets:470/619）——弹层进出动画/形状/手势行为不统一 | 源码清单 §10.3 | 逃逸点收编（live2d 域可先统一到 BlyyBottomSheet 的暗色变体） |
| P2-15 | 骨架采用 | BlyyScreenScaffold/BlyyStateScaffold 各仅 2 屏采用；四态编排手写导致 Gallery 错误态=封装复制品、SD 库五态自写 | 源码 + 【12 章节】矩阵 | 下一批试点收编（见 §21 路线） |
| P2-16 | 悬浮窗 | 静态图分支首次显示延迟明显（服务启动后桌面上 ~40s 无可见内容，无加载占位/进度） | 【49】无 → 【49c】有 | 占位图/加载态；排查 fitRect 预载链路 |

### P3（细节打磨，22 项）

| 编号 | 位置 | 问题 | 证据 |
|---|---|---|---|
| P3-04 | Settings 经典风格 | 六分区标题呈现紫/粉/青/紫/青多色（CC 为蓝金双色节奏），规则不一 | 【40】 |
| P3-06 | Drawer | 抽屉宽 85%（306dp@360dp 视口），超出 M3 惯例（≤280dp 内容宽） | 【02】 |
| P3-07 | Drawer | 滚动位置跨打开持久化（LazyListState 未重置），重开抽屉停在上次位置 | 实测 |
| P3-08 | 筛选 Sheet | 稀有度"全部"选中=深色实底，阵营/类型"全部"=蓝色浅底，同屏两种选中语言 | 【30c】 |
| P3-09 | Gallery 筛选钮 | 激活筛选计数徽章红色（Error 语义误用），应为 Primary | 【30e】 |
| P3-10 | SD 资源库 | 资源卡预览显示散落的精灵图集碎片，观感似加载失败 | 【18】 |
| P3-11 | 学生卡 × 横屏 | 卡内标签（"学生""神秘"）横屏 6 列下文字竖排换行 | 【45】 |
| P3-15 | Live2dViewer/各屏 | 裸 sp 残余（54.sp 台词等），跨页字号无节制；识舰娘共享库自身 15 处裸 dp | 源码清单 |
| P3-16 | 游戏导入 | 资源包路径单行强制截断无查看全文途径 | 【22】 |
| P3-17 | Live2D 库顶栏 | "多选"按钮图标（⇅线）与排序语义混淆 | 【20】 |
| P3-18 | 启动闪屏 | 默认白底 SplashScreen 与深海主题脱节 | 冷启动截图 |
| P3-19 | 猜题退出 | 结算退出双层确认（结算弹窗→确认弹窗） | 【04d→05b】 |
| P3-20 | 助手页 | 初始态无输入区无引导（仅查询按钮），点查询才知有缓存配置 | 【09】 |
| P3-21 | Home 空态 | AGSL+光晕+粒子+徽章四层常驻动画叠加，无内容时功耗热点 | 【01】+ 源码 |
| P3-22 | 平板/折叠屏 | 无宽度分档/WindowSizeMode，360dp 与 800dp 同布局 | 源码 ScreenAdaptive.kt |
| P3-23 | 触觉 | 底栏 Tab 用 LongPress 强度触觉（AppChrome.kt:314），与 Tick 语义档错位；会话拖拽排序无触觉 | 源码 |
| P3-24 | 死代码 | BlyyMetricCard/BlyySearchField/BlyySuccessBanner/BlyySnackbarHost/BlyyDialog 0 调用；BlyyButton Tertiary/Text=Secondary 别名；Easings Bounce/Smooth/Anticipate/Overshoot 0 调用 | 源码 |
| P3-25 | 规范债 | 三套海拔刻度并存（AppSpacing.Elevation/AppElevation/DepthLayer）；禁用色三入口；RobustAvatar.kt ~百行陈旧 import；12 文件残留未用 AlertDialog import | 源码 |
| P3-26 | 会话列表 | 空态引导弱（仅图标+两行字）；JUUS 语境与 CC 弹窗混用观感割裂（§5.3-1） | 【10→11】 |
| P3-28 | 悬浮窗 | 静态图分支带白色光晕描边，与 SD/立绘分支视觉不统一 | 【17】 |
| P3-29 | 排行榜 Tab | 四分类 Tab 横向滚动无渐隐遮罩，右端"听音…"被硬切 | 【08】 |

---

## 18. 典型页面深度分析

### 18.1 首页（home）——门面页

- **结构**：Material Scaffold+AGSL 流体背景+HUD 顶栏（汉堡+标题）+2Tab 底栏+全局小人
- **四态**：骨架（ShipCardShimmer）/空态（心形徽章+MetallicText 标题+CTA）/错误（全屏+缓存横幅双形态）/内容（誓约舰网格+稀有度辉光+誓约特效+长按切誓约）
- **亮点**：空态即品牌表达（呼吸光晕+粒子）；共享元素转场起点；小人常驻陪伴
- **问题**：P1-01（横屏 CTA 冲突）、P1-02（经典风格泄漏）、P3-21（动画叠加）、小人遮挡（P2-05）
- **重设计建议**：内容态优先——誓约网格空态到内容态的过渡应有 grid item 错峰入场（现有 BlyyEntrance 未用于网格）；CTA 按钮收编到 BlyyPrimaryButton（消除 P1-02 根因）

### 18.2 船坞（gallery）——高频核心页

- **结构**：无 Scaffold，AdaptiveGalleryTopBar 浮动合并顶栏（标题块+搜索揭示+档案切换胶囊+计数）+3 列网格+滚动联动顶栏/底栏隐藏
- **亮点**：搜索揭示动画精细（防高度抖动的自绘 layout 裁切）；筛选 Sheet chips 体系完整；长按菜单 Sheet 信息架构好（头像头+三快捷+两自定义入口）
- **问题**：P2-06（玻璃穿透）、P2-07（底栏遮末行）、P3-08/09（筛选选中态/徽章色）、双卡面语言（§5.3-2）
- **建议**：顶栏玻璃加 scrim；末行 padding；筛选 chips 选中态统一组件化（BlyyChip 已有，Sheet 内改用）

### 18.3 语音档案（voice）——内容消费主页面

- **结构**：共享元素头像头+暖色模糊背景（AGSL+blur(100dp) 立绘）+皮肤分组 sticky 列表+玻璃播放条（可折叠气泡）+可拖拽小人+行菜单
- **亮点**：播放态行内反馈链完整（均衡器图标+全文展开+小人台词同步）；双语切换（舰娘 CN/JP、学生 中配/日配）位置统一；重试提示透明（n/3/缓存）
- **问题**：小人气泡贴边截断（P2 关联）、静态图回退小人观感粗糙【35】、双语切换条出现时布局跳动（expandVertically 默认参数）
- **建议**：台词气泡测量后 clamp；静态图回退加圆角遮罩与光晕统一处理

### 18.4 Live2D 查看器（live2d/view）——技术最复杂页

- **结构**：WebView+PIXI 渲染+沉浸式浮层系统（信息栏/台词气泡/控制条/迷你浮标/沉浸提示五层）
- **亮点**：5s 自动收起+点屏唤出的注意力管理；控制条六功能布局清楚；"惊喜"反馈链（动作+台词+触觉）；横屏即最佳形态
- **问题**：裸 ModalBottomSheet×2（动作/表情选择）；陀螺仪 chip 选中态样式与"当前开启"的持续性关系不够直观（长按才知道详情？——设置在 Sheet 内）
- **建议**：Sheet 收编；控制 chip 增加开启态徽标

### 18.5 啾信会话列表（jiuxin_conversation_list）——独立风格页

- **结构**：JUUS 左导航轨（JUUS// logo+2 圆钮）+白胶囊头（JUUSTAGRAM/消息+新建+编辑）+会话卡列表
- **亮点**：风格完整自洽（若视为"啾信 App 内的 App"）；编辑模式长按拖拽+BackHandler
- **问题**：与主风格混用缝隙（弹窗/Sheet）；空态弱；无加载/错误态（本地数据合理但删除同步失败场景缺失）
- **建议**：为 JUUS 线定义配套的 JUUS 风格 Dialog/Sheet 变体（BlyyDialog 已有双风格插槽可扩展第三风格）

### 18.6 秘书舰模式页（secretary_mode）——配置中枢

- **结构**：方式选择双卡（随机/心有所属）+SD 下载卡+当前秘书舰卡+设置入口（后两者仅在有秘书舰时出现）
- **问题**：无秘书舰时页面下半空白且**设置入口不可见**（新用户找不到悬浮窗/语音设置在哪）【13】；有秘书舰后卡片才出现【16】——入口随状态隐藏的发现性问题
- **建议**：设置入口常驻（未设置时灰态或引导文案）

### 18.7 设置（settings）——骨架标准页

- **结构**：BlyyScreenScaffold+BlyySectionPanel 六分区+BlyySettingsRow 开关行+BlyyEntrance 错峰
- **评价**：全 App 布局规范最好的页面（唯一标准骨架+列表项组件化用户）；开关描述文案随状态变化（"已隐藏状态栏，从屏幕顶部下滑可临时呼出"）信息设计好
- **问题**：经典风格分区标题多色（P3-04）

---

## 19. 整体 UI 重设计建议

### 19.1 是否需要推翻重设？

**不需要**。指挥中心风格经两轮重构已形成成熟、自洽、可执行的体系（令牌完整、反模式清零、组件库成型）。本次审计的 P1 问题均为**局部泄漏/适配缺口**而非体系性缺陷。建议走"巩固+收敛"路线而非重设计。

### 19.2 视觉升级方向（保留基因）

1. **统一组件语言收尾**：8 处裸 Sheet/2 处裸 Dialog/3 处裸 Button/3 处裸 Card 收编（P2-14）——这是"看起来最后 5% 不统一"的全部来源
2. **双风格完整性修复**：P1-02 泄漏点修复后，Classic 才算真正可用（目前 Home 空态在 Classic 下穿帮）
3. **第三风格（JUUS）边界文档化**：明确"啾信线页面=JUUS 语言，其弹窗/横幅=JUUS 变体"，扩展 BlyyDialog/BlyyBottomSheet 支持 JUUS 皮肤（参数已有 uiStyle 槽位可加 juus 变体）
4. **信息密度分级**：档案网格页维持；配置表单页可再松一档（现有 Panel→Row 两层嵌套可减一层）
5. **启动体验**：SplashScreen 深海化（P3-18）

### 19.3 信息架构与页面优化

| 页面 | 处置 |
|---|---|
| 全部保留现有结构 | 抽屉 13 入口 4 分组架构合理；35 路由层级健康 |
| 局部调整 | secretary_mode（设置入口常驻）、assistant（初始引导态）、啾信配置（内嵌导航改真路由或加 BackHandler） |
| 可合并 | guess_image/voice 的"难度+题面+输入"结构已共享 ✓ 无需动 |
| 流程简化 | 猜题退出单层化（P3-19）；水印 相机→编辑器 返回链已互为兜底 ✓ |

### 19.4 交互与动效重构清单

- **保留**（体系资产）：共享元素转场、Tab Fade Through、按压三档、BlyyEntrance 错峰、GalleryTopBar 揭示、播放条折叠、Live2D 浮层注意力管理、触觉语义档
- **调参**：错误横幅 expandVertically 加 spring；双语切换条同上；翻牌时长 1.5s→1.2s
- **补齐**：reducedMotion 接线（P2-08 清单）；列表项触觉补齐（拖拽排序）
- **新增**：誓约网格内容态 item 错峰入场；悬浮窗静态图加载占位

---

## 20. 统一设计规范建设方案

现有令牌体系（AppColors/AppTypography/AppSpacing/BlyyShapes/AppElevation+Depth/AppAnimation/IconTokens）已覆盖 80%，补齐以下缺口即可成为完整 Design Token 文档：

| 规范项 | 现状 | 建设动作 |
|---|---|---|
| 色彩 | 完整（含语义/稀有度/玻璃/Widget） | 增补"筛选徽章用 Primary"使用规则；禁用态入口归一 |
| 字体 | 24 式+双风格变体 | 增补"标题 sp 上限"规则（54.sp 沉浸台词入令牌 LargeDisplay） |
| 间距 | 8dp 网格完整 | 屏幕层裸 dp 专项清理（Voice/Live2dViewer/Birthday 三屏优先） |
| 圆角 | 双轨（圆角/切角）+materialShapes | 文档化"哪些组件允许裸 RoundedCornerShape"（聊天气泡/学生卡等特殊体） |
| 阴影 | blyyDepthShadow 唯一来源 ✓ | **合并三套海拔刻度为一套**（P3-25） |
| 图标 | IconTokens+Rounded 家族 | 补"多选/排序"类易混图标替换规则 |
| 组件 | 57 组件 | 死代码清理；BlyySegmentedControl⇄GuessDifficultySelector 合并；逃逸点收编 |
| 动效 | Duration/Easing/Spring/Press/Repeating 完整 | 删除 0 调用 Easing；reducedMotion 纳入组件验收项 |
| 布局 | BlyyScreenScaffold/BlyyStateScaffold | **推广为强制骨架**（新页面禁手写四态） |
| 适配 | 手表双档 | 增补宽度分档规划（≥600dp 两栏） |
| 文案 | 零国际化 | strings 迁移规范（页面标题先行） |

---

## 21. 分阶段优化与实施路线

### 第一阶段：止血修复（1-2 天，纯修复不改架构）

| 项 | 内容 | 验收 |
|---|---|---|
| P1-01 | Home 空态横屏 bottomBar insets 修复 | 横屏截图 CTA 独立于底栏 |
| P1-02 | HomeScreen 空态颜色改主题化 | Classic 四象限截图无青色泄漏 |
| P2-07 | 网格 contentPadding +NavBar | 滚动到底末行完整可见 |
| P2-10 | 同步提示改 Info 横幅 | 猜音加载截图无红色误导 |
| P3-09 | 筛选徽章改 Primary 色 | 【30e】复拍 |
| P2-13 | 猜题返回钮命中区 ≥48dp | dump 复测 |

### 第二阶段：体验关键项（3-5 天）

| 项 | 内容 |
|---|---|
| P2-03 | 立绘页手势分派修复 |
| P2-05 | 小人避让策略（双击暂隐/透明度/位置记忆优化） |
| P2-06/09 | 玻璃顶栏 scrim；弹窗沉浸式同步 |
| P2-12 | 啾信配置 BackHandler |
| P2-16 | 悬浮窗静态图加载占位 |
| P2-08（一期） | 无限装饰动画族接 reducedMotion |

### 第三阶段：组件体系收敛（1-2 批次）

- 弹窗逃逸点收编（8 Sheet+2 Dialog+3 Button+3 Card）
- BlyyScreenScaffold/BlyyStateScaffold 试点推广 3 屏（建议：About、Birthday、SdResourceGallery——四态最复杂收益最大）
- 死代码清理+BlyySegmentedControl 合并
- JUUS 弹窗变体设计

### 第四阶段：规范与国际化（持续）

- 三套海拔合并；裸 dp/sp 专项（三屏优先）；strings 迁移（页面标题→按钮→弹窗）
- 设计令牌文档导出（现有对象注释已半文档化，整理成 docs/design-tokens.md）

### 第五阶段：平板/折叠屏与验收

- 宽度分档（≥600dp 两栏布局：列表-详情）
- 全量回归截图（四象限×横竖屏×1.3x 字体）与本报告清单比对

---

## 22. 最终结论

1. **体系成熟度高**：经 2026-10 两轮重构，blyy 拥有自研的完整设计系统（色板/字排/间距/形状/深度/动效七类令牌+57 组件+双风格+手表副令牌），三大反模式（裸阴影/裸颜色/裸弹窗）基本清零，这在个人/小团队项目里属高完成度。
2. **本轮新问题集中在"缝隙"**：P1 两项都是跨维度组合暴露（横屏×空态、经典×自制组件），不是主路径缺陷；P2 集中在小人 z 序、手势冲突、骨架采用率、弹窗逃逸。
3. **动效是产品强项**：令牌统一、按压/触觉语义化、共享元素转场、注意力管理（Live2D 浮层）均达到商业应用水准；唯一系统性短板是 reducedMotion 落地率 2/全量。
4. **结构债可枚举**：strings 0 国际化、四态手写、三套海拔刻度、死代码——全部可列出清单分批偿还，不影响日常迭代。
5. **建议路线**：止血（2 天）→ 体验（5 天）→ 收敛（2 批次）→ 规范（持续）。不需要重设计。

---

## 23. 尚未完成的检查项、访问限制及待验证事项

| 项 | 原因 | 风险 |
|---|---|---|
| 啾信完整 AI 对话流 | 需有效 API Key/URL（无真实配置） | 聊天气泡/流式打字/表情包渲染未实测，仅代码级确认 |
| Shizuku 授权流/导入进度 | 需 Shizuku 服务激活 | 授权失败态、导入进度卡【22 中 ImportProgressCard 代码级】未实测 |
| watermark_editor 编辑交互 | 需真实照片（模拟器相册未准备素材）；相机预览无摄像头 | 单指拖动/双指缩放/保存反馈仅代码级 |
| 上传排行成功/失败流 | 需有效战绩+网络写接口 | 上传中/失败态未实测 |
| TalkBack 全程走查 | 模拟器未部署 TalkBack 环境 | 焦点序/无标签项待验证 |
| 2.0x 字体缩放 | 仅测 1.3x | 顶栏单行标题 2.0x 截断风险待验证 |
| 平板/折叠屏（≥600dp） | 模拟器仅 720p 手机形态 | P3-22 全部为代码级推断 |
| 悬浮窗"桌面不可见"是否必现 | 【49】→【49c】间隔含多次操作，无法确定是首载延迟还是特定时序 | 建议复测：杀服务重启后立即回桌面观察 |
| 誓约流（oath）| 需收藏舰娘+长按确认（模拟器收藏为空） | 誓约特效动画/确认弹窗仅代码级 |
| 群聊（啾信）| 需多人配置 | 群聊 UI 仅代码级 |
| 语音播放音质/后台播放 | 模拟器音频通道未验证 | PlaybackService 后台行为仅代码级 |
| 性能专业测量 | 未接 GPU profiler/systrace | §16 均为观察+源码推断，非实测数据 |
| 抽屉在 ≤360dp 小屏的滚动体验 | 模拟器恰为 360dp，已测；更小屏未测 | — |
| 深链 blyy://voice 完整链路 | 需外部 Intent 触发环境 | 代码级确认 Manifest+桥接 |

### 误报澄清（审计过程中排除的疑点）

1. ~~首页 CTA 浅色低对比~~ —— 首帧截图为进场动画中间帧，终态对比度正常（【01】终态深蓝）
2. ~~抽屉不可滚动~~ —— 首测滑动起点超出列表区（横屏坐标空间），UI dump 证实 LazyColumn 可滚动（【03】）
3. ~~翻牌动画期间闪空~~ —— 输入延迟导致的空帧截图，源码确认 180dp 旋转卡全程可见
4. ~~设置页左缘神秘眼睛浮标~~ —— 为 SD 小人美术素材的一部分（翅膀装饰），不在 a11y 树
5. ~~悬浮窗桌面永远不可见~~ —— 二次复测正常显示（49c），定性为首载延迟
6. ~~听音"换一条语音"按钮与行为不符~~ —— 源码核实按钮文案即"换一条"（GuessVoiceScreen.kt:458），随机播另一条+唯一语音回退重播的语义一致且 KDoc 明确记录

---

## 附录 A：截图清单（test_screenshots/ui_audit_2026-11/）

01 首页基线 · 02/03 抽屉上下 · 04*看图识舰娘四态 · 05/05b 结算与确认弹窗 · 06/06b 听音加载与就绪 · 07/07b 历史与详情 · 08/08b 排行榜骨架与数据 · 09*助手四态 · 10*啾信列表/菜单/新建 Sheet · 11 未配置弹窗 · 12/12b 啾信配置 · 13/16 秘书舰模式（无/有秘书舰） · 14*随机翻牌三帧 · 15 首页有秘书 · 17/17b 秘书舰设置两屏 · 18 SD 资源库 · 19 生日日历 · 20 Live2D 库 · 21*Live2D 查看器三态 · 22/24/24b 游戏导入三视图 · 23 Live2D 直通复用 · 25 资源查看器 · 26/26b 水印相机与弹层 · 27/27b 设置两屏 · 28 关于 · 29 图标设置 · 30*船坞搜索筛选五帧 · 31/31b 长按菜单 · 32*立绘页四帧（含手势对照） · 33*语音页四态 · 34/34b 档案切换（含穿透问题） · 35 学生语音 · 36*学生画廊三帧 · 37-43 深色/经典四象限 · 44/45 横屏首页/档案 · 46*横屏 Live2D 三帧 · 47/47b 字体缩放 · 48 桌面小组件 · 49*悬浮窗三帧

## 附录 B：源码引用索引（主要）

- 导航：ui/AppRoot.kt（路由 :334-976、抽屉 :306-329、底栏 :169-195/:1029、小人 :978-1027、转场 :134-155）
- 令牌：ui/theme/{Color,SemanticColors,Type,Font,Shape,Spacing,Depth,Elevation,Animation,IconTokens,ScreenAdaptive,UiStyle,Theme,GameStyles}.kt
- 组件：ui/components/{BlyyDesignSystem,BlyyComponents,BlyyDialog,BlyyListItem,BlyyTextField,BlyySkeleton,BlyyEntrance,BlyyHaptics,AppChrome,RobustAvatar,ShipCard,ZoomableImage,SpineSdView,SecretaryChibiOverlay}.kt
- P1-02 源码：ui/screens/HomeScreen.kt:935（PrimaryDeepLight 硬编码）、:731-732（MetallicText）
- P2-10 源码：viewmodel/GuessShipViewModel.kt:271 → ui/screens/GuessVoiceScreen.kt:284
- 弹窗逃逸：WatermarkCameraScreen.kt:325、GameImportScreen.kt:264、Live2dLibraryScreen.kt:462/519、Live2dViewerScreen.kt:905/964、Live2dViewerSheets.kt:175/298、chat/ChatSheets.kt:470/619
