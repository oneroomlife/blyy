# 🚢 BLYY - 碧蓝航线语音播放器

<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/cf.jpg" alt="BLYY Logo" width="120" height="120">
</p>

<p align="center">
  <strong>一款现代化的碧蓝航线舰娘 Android 应用</strong>
</p>

<p align="center">
  <a href="#features">功能特性</a> •
  <a href="#screenshots">截图预览</a> •
  <a href="#usage">使用说明</a> •
  <a href="#install">安装</a> •
  <a href="#tech-stack">技术栈</a> •
  <a href="#project-structure">项目结构</a> •
  <a href="#matcher">匹配引擎</a> •
  <a href="#testing">测试</a> •
  <a href="#ci">CI</a> •
  <a href="#contributing">贡献指南</a> •
  <a href="#star-history">Star 走势</a> •
  <a href="#acknowledgements">致谢</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-green.svg" alt="Platform">
  <img src="https://img.shields.io/badge/Language-Kotlin%202.1-orange.svg" alt="Language">
  <img src="https://img.shields.io/badge/Jetpack%20Compose-BOM-blueviolet.svg" alt="Compose">
  <img src="https://img.shields.io/badge/API-24%2B-brightgreen.svg" alt="API">
  <img src="https://img.shields.io/badge/License-GPL%20v3-blue.svg" alt="License">
</p>

---

## 📖 项目简介

碧蓝语音（BLYY）是一款专为碧蓝航线玩家设计的多功能舰娘应用，基于 Kotlin 2.1 + Jetpack Compose 构建，涵盖**语音播放、舰娘图鉴、秘书舰悬浮窗、桌面小组件、游戏资源包直读、生日日历、AI 角色扮演对话、猜舰娘小游戏、Live2D 皮肤库、水印相机**等能力。应用内置全量舰娘本地头像匹配引擎（wiki 图鉴 1004/1004 命中），支持双主题风格（经典紫色 / 指挥中心 HUD）与流畅的动画体验。

<a id="features"></a>

## ✨ 功能特性

### 🏠 底部导航三大主页

#### 后宅（Home）
- 誓约舰娘的后宅展示页，AGSL 着色器驱动的誓约粉色动态光效、稀有度光晕、收藏徽章
- 小屏/手表端自适应：检测屏幕最小宽度 ≤ 360dp 时自动缩放 UI

#### 船坞（Gallery）
- 完整舰娘图鉴，按阵营/舰种/稀有度多维筛选，支持搜索；点击舰娘直达语音页
- **双档案模式**：舰船档案（DOCK）/ 成员档案（STUDENT）动态切换
- **长按舰娘卡片弹出精致操作菜单**：誓约/解除誓约（誓约后立绘自动切换婚皮）、查看立绘、前往 Wiki
- **自定义头像**：从相册/文件挑选图片，经 3:4 裁切页所见即所得应用；头像解析链优先级为 **自定义 > 本地 assets（誓约婚皮/默认）> 网络**，随时可恢复默认
- **本地头像匹配引擎**：内置 1100+ 高清头像资产，全量 wiki 图鉴 1004/1004 命中（详见[匹配引擎](#matcher)）

#### 关于（About）
- 版本信息、检查更新（对接网盘发布链接）
- 缓存管理、开源依赖清单

### 🎵 语音播放
- 全量舰娘语音台词，中/日双语切换
- 播放模式：单曲循环 / 列表循环 / 随机播放
- **稍后播放队列**：长按语音条目入队，支持队列管理与播放全部
- Media3 ExoPlayer + MediaSession：锁屏控制、耳机线控、前台服务

### 🎭 秘书舰模式
- 可拖动的舰娘小人悬浮窗（SYSTEM_ALERT_WINDOW），点击随机播放语音，拖拽位置自动记忆、重启后原地恢复
- **今日秘书舰**：按稀有度权重从船坞抽取每日秘书舰，可开启每日自动随机
- **秘书舰设置页**：SD 小人大小/皮肤、显示与语音设置、SD 资源库统一管理
- SD 小人来源统一解析：本地 `blhx_sd` 库、**游戏资源导入解出的小人**、自定义资源均可被秘书舰/悬浮窗/查看器识别

### 🧩 桌面小组件（Glance）
- **今日推荐舰娘（2×2）**：每天为你推荐一位舰娘，点击直达她的语音页
- **秘书舰问候（4×2）**：头像 + 舰名 + 台词问候，一键换人、播放语音；组件舰娘与应用内秘书舰**完全隔离**，可各自指定
- **舰娘生日（4×2）**：今日寿星与近期生日提醒，点击查看完整日历
- 纯后台交互 + 头像限时加载（弱网自动回退占位图），刷新绝不阻塞

### 📦 游戏资源包直读（Shizuku）
不依赖电脑，在手机上把碧蓝航线的 UnityFS 资源包（`.ys` / `.unity3d`）解析成**可渲染**的本地资源：

- **五类资源自动识别**，分别转换为可渲染产物：
  - `live2d` → Cubism 模型（moc3/贴图/物理，Mecanim 动作还原 `motion3.json`，上百条动作）
  - `char` → Spine **SD 小人**（`.skel` + `.atlas` + 图集页，libgdx Spine 实时渲染）
  - `spinepainting` → Spine **动态皮肤**（多骨骼/多页图集，换装变体切换）
  - `painting` → **静态立绘**（按游戏本体的网格/图层布局还原成整图）
  - `loadingbg` → **过场图**（高清 1920×1080）
- **立绘三级还原**：prefab 图层树布局 → 网格拼装 → 原图直出，把游戏里的「碎片图集」还原成完整立绘
- **扫描自动发现**：官服/渠道服/外服包名自动探测，扫描结果与导入状态持久化在索引里，**下次启动不重复解包**，管线升级时旧产物标记「有更新」
- **兼容性兜底**：纯 Kotlin ASTC 软件解码器（1952 条黄金样本对拍），硬件不支持 6×6 ASTC 的模拟器/低端设备也能出图；Shizuku 通道四态探测，release 包 R8 裁剪问题已根治
- **导出分享**：立绘/过场图一键导出到 `Downloads/BLYY/`

### 🎂 生日日历
- 月历界面浏览全舰娘生日，今日寿星醒目展示
- 数据来自离线聚合的静态名单（`assets/blhx_birthdays.json`），随档案刷新自动回填
- 配套「舰娘生日」桌面小组件，寿星日提醒

### 🐦 啾信（AI 角色扮演对话）
- 与 AI 舰娘进行沉浸式角色扮演对话，支持多轮上下文
- **舰娘人格管理**：为每名舰娘配置人设、开场白、表情包
- **舰娘长期记忆**：对话摘要自动沉淀，跨会话记忆舰娘性格
- 会话列表管理、啾信语音（对话内容驱动 TTS 语音回复）
- 在「设置 → 啾信配置」配置 API Key 与模型参数

### 🤖 碧蓝航线助手
- **查玩家**：指挥官信息（等级、UID、收集率、资源、委托、科研、待办副本）
- **查建造**：建造记录分页查询（最多 500 条）
- UID 与服务器在「设置 → 碧蓝航线助手」统一配置

### 🎮 猜舰娘小游戏
- **看图识舰娘**：根据立绘辨认舰娘
- **听音识舰娘**：根据语音辨认舰娘
- 答题得分、答题历史回顾、全服排行榜

### 🎨 更多功能
- **SD 资源图鉴**：浏览 Spine 骨骼动画 SD 小人资源，支持本地导入整理（zstd 压缩解析）；入口在「秘书舰设置 → SD 资源库」
- **Live2D 皮肤库**：导入本地 Cubism 3/4 模型（文件夹/zip 免权限导入），内置 pixi-live2d-display 渲染，支持动作/表情/手势交互与真实缩略图，多选批量删除
  - **陪伴模式**：循环待机无缝衔接，每到设定间隔随机播放动作并配语音；锁屏风时钟/电量角标；沉浸模式自动收齐全部界面，触摸画布唤出
  - **陀螺仪注视/视差**：屏幕系重力响应驱动模型视线与轻微位移，灵敏度可调
- **水印相机**：CameraX 拍照 + 可定制水印排版（相框/标题/署名），支持截图编辑
- **应用图标设置**：多种启动器图标风格任选

### 🌗 视觉与交互
- Material Design 3，深色/浅色主题自动适配
- **双主题**：经典风格 / 指挥中心（Command Center）HUD 风格
- AGSL 深度阴影系统（Depth 阴影令牌/受光渐变/按钮辉光投影）、语义色板收敛
- 统一弹窗与按钮组件、骨架屏加载态、CJK 文本行盒修正（根治汉字上下裁切）
- 尊重系统「减少动画」设置的无障碍降级
- 8dp 网格间距系统、切角形状（BlyyShapes）、手表/小屏自适应布局

<a id="screenshots"></a>

## 📸 截图预览

| 船坞界面 | 后宅界面 | 语音播放界面 |
|:-------:|:-------:|:-----------:|
| ![船坞](docs/screenshots/船坞.png) | ![后宅](docs/screenshots/首页.png) | ![语音播放](docs/screenshots/语音播放.png) |
| **啾信（AI 对话）** | **看图识舰娘** | **听音识舰娘** |
| ![啾信](docs/screenshots/啾信.png) | ![看图识舰娘](docs/screenshots/看图识舰娘.png) | ![听音识舰娘](docs/screenshots/听音识舰娘.png) |

> 注：截图位于 `docs/screenshots/` 目录；如截图与最新版本不一致，请提交 Issue 提醒更新。

## 🔧 环境要求

| 工具 | 版本要求 |
|------|---------|
| Android Studio | 最新稳定版（AGP 9.1.1 需较新版本支持） |
| JDK | 17（Gradle Toolchain 自动对齐） |
| Kotlin | 2.1.10（Compose Compiler 插件随版本启用） |
| Gradle | 9.8.0（项目自带 wrapper，无需手动安装） |
| Android SDK | minSdk 24 / targetSdk 35 / compileSdk 36 |
| 设备 | Android 7.0 (API 24) 或更高 |

<a id="install"></a>

## 📥 安装

### 从源码构建

1. **克隆仓库**
   ```bash
   git clone https://github.com/oneroomlife/blyy.git
   cd blyy
   ```

2. **打开项目**
   - 使用 Android Studio 打开项目根目录
   - 等待 Gradle 同步完成（首次会自动下载 libgdx natives）

3. **构建 APK**
   ```bash
   # Debug 版本
   ./gradlew :app:assembleDebug

   # Release 版本（需自行配置签名；Shizuku/AIDL 相关混淆规则已内置）
   ./gradlew :app:assembleRelease
   ```

4. **安装到设备**
   ```bash
   ./gradlew :app:installDebug
   # 或
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

### 下载已发布版本

前往 [Releases](https://github.com/oneroomlife/blyy/releases) 或应用内「关于 → 检查更新」下载最新 APK

<a id="usage"></a>

## 📖 使用说明

### 导航结构

- **底部三个主页面**：后宅 / 船坞（舰船⇆成员档案切换）/ 关于；点击舰娘卡片进入她的语音播放页
- **侧拉菜单**：今日秘书舰、Live2D 皮肤、游戏资源导入、生日日历、看图/听音识舰娘、历史记录、积分排行榜、碧蓝航线助手、啾信、水印相机、设置、关于
- **桌面小组件**：在启动器小组件列表添加「今日推荐舰娘」「秘书舰问候」「舰娘生日」

### 桌面小组件

1. 长按桌面空白处进入 **小组件** 列表，找到 BLYY
2. 拖出想要的组件添加到桌面；秘书舰问候组件上点 **换人** 切换舰娘、点 **语音** 播放台词问候，组件舰娘与应用内秘书舰互不影响
3. 舰娘生日组件展示今日寿星与近期生日，点击直达完整日历

### 游戏资源包直读（Shizuku）

1. 安装并启动 [Shizuku](https://shizuku.rikka.app/)，从侧栏进入 **游戏资源导入** 并授权
2. 点 **刷新** 自动探测游戏 `AssetBundles/` 下的 `live2d` / `char` / `painting` / `spinepainting` / `loadingbg` 目录（存在哪个列哪个）
3. 点 **导入**（或 **全部导入**）完成「复制 → 解包 → 提取 → 落库」；导入完成后在 **已导入** 分区点卡片查看（Spine 实时渲染 / 立绘缩放浏览），顶栏可 **导出** 立绘与过场图到 `Downloads/BLYY/`
4. 没有 Shizuku 也可以：顶栏选择 `.ys` / `.unity3d` 文件，或已解包的资源文件夹直接导入
5. 通道卡片显示 Shizuku 连接四态（未运行 / 未授权 / 服务未响应 / 就绪）；「服务未响应」时点 **重试** 重新绑定

### 自定义舰娘头像

1. 船坞 **长按舰娘卡片** 打开操作菜单，选择 **自定义图片**（相册/文件）
2. 在裁切页自由缩放定位 3:4 取景框后应用，全应用头像即时生效
3. 想还原时再次长按选择 **恢复默认头像**

### Live2D 陪伴模式与陀螺仪

1. 侧拉菜单进入 **Live2D 皮肤**，导入模型（文件夹或 zip）或使用游戏资源导入的产物
2. 点 **陪伴** 开启：舰娘循环待机并无缝衔接，每到设定间隔随机播放动作与语音；在陪伴设置层调整间隔、开关 **时钟/电量角标**
3. 开启 **陀螺仪** 后倾斜设备，模型视线与位置随重力轻微移动，灵敏度可调

### 秘书舰

1. 从侧拉菜单进入 **今日秘书舰**，授予悬浮窗权限后舰娘小人常驻屏幕，点击随机播放语音
2. **秘书舰设置** 中可调整 SD 小人大小/皮肤、管理 SD 资源库；「随机秘书舰」页面可开启每日按稀有度自动抽取
3. 后宅长按舰娘卡片也可快速设为秘书舰

### 碧蓝航线助手

1. 从侧拉菜单进入 **碧蓝航线助手**，首次使用提示「未配置查询参数」，点击 **去设置**
2. 在「设置 → 碧蓝航线助手」填入默认 UID 与服务器
3. 返回助手页切换「查玩家」/「查建造」分区查询

### 啾信（AI 对话）

1. 从侧拉菜单进入 **啾信**，首次使用引导配置 API Key
2. 在「设置 → 啾信配置」填入 API Key 和模型参数；在「啾信舰娘配置」为舰娘创建人格
3. 返回啾信页面选择舰娘人设开始对话，对话会自动沉淀长期记忆

### 稍后播放队列

1. 语音列表页长按某条语音选择 **稍后播放** 入队
2. 播放控制栏的 **列表按钮** 打开队列弹窗，支持单项播放/播放全部/清空/移除

### 切换主题

在「设置 → 界面风格」开启/关闭 **指挥中心 UI**，即可在经典风格与 HUD 风格间切换。

<a id="tech-stack"></a>

## 🏗️ 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| [Kotlin](https://kotlinlang.org/) | 2.1.10 | 开发语言（KSP + Compose Compiler 插件） |
| [Jetpack Compose](https://developer.android.com/jetpack/compose) | BOM | 声明式 UI 框架 + Navigation Compose |
| [Material Design 3](https://m3.material.io/) | - | UI 设计系统 |
| [Hilt](https://dagger.dev/hilt/) | 2.59.2 | 依赖注入（KSP 编译） |
| [Room](https://developer.android.com/training/data-storage/room) | 2.8.4 | 本地数据库（舰娘/答题历史，v9 schema 导出） |
| [DataStore Preferences](https://developer.android.com/topic/libraries/architecture/datastore) | 1.2.0 | 偏好与玩家设置存储 |
| [Glance](https://developer.android.com/jetpack/compose/glance) | 1.1.1 | 桌面小组件（Compose 风格 API） |
| [WorkManager](https://developer.android.com/topic/libraries/background-work) | 2.11.1 | 小组件后台刷新任务 |
| [Shizuku](https://shizuku.rikka.app/) | 13.1.5 | ADB 身份读取游戏私有目录（api + provider） |
| [Media3 ExoPlayer](https://developer.android.com/media/media3) | 1.3.1 | 音频播放 + MediaSession |
| [Coil](https://coil-kt.github.io/coil/) | 2.7.0 | 图片加载（含 GIF） |
| [OkHttp](https://square.github.io/okhttp/) | 4.12.0 | 网络请求 |
| [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) | 1.10.0 | JSON 序列化 |
| [Jsoup](https://jsoup.org/) | 1.18.1 | wiki HTML 解析 |
| [Spine + libgdx](https://esotericsoftware.com/spine-runtimes) | 3.8.99.1 / 1.13.5 | SD 小人骨骼动画运行时 |
| [pixi.js + pixi-live2d-display](https://github.com/guansss/pixi-live2d-display) | 6.5.10 / 0.4.0 | Live2D 模型 Web 渲染运行时（内置 assets） |
| [CameraX](https://developer.android.com/media/camera/camerax) | 1.6.1 | 水印相机 |
| [pinyin4j](https://github.com/belerweb/pinyin4j) | 2.5.1 | 中文转拼音（名称匹配引擎） |
| [desugar_jdk_libs](https://github.com/google/desugar_jdk_libs) | 2.1.5 | java.time 脱糖（生日日历/小组件，minSdk 24） |
| [compose-shimmer](https://github.com/valentinilk/compose-shimmer) | 1.3.3 | 骨架屏动画 |

完整依赖清单以 [`gradle/libs.versions.toml`](gradle/libs.versions.toml) 为准，架构设计详见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)，游戏资源解析细节见 [docs/GAME_ASSET_IMPORT.md](docs/GAME_ASSET_IMPORT.md) 与 [docs/LIVE2D_BUNDLE_IMPORT.md](docs/LIVE2D_BUNDLE_IMPORT.md)。

<a id="project-structure"></a>

## 📁 项目结构

```
blyy/
├── app/                                # Android 应用模块
│   ├── build.gradle.kts                # 应用构建脚本（含 libgdx natives 提取、AIDL、脱糖）
│   ├── lint-baseline.xml               # Lint 基线
│   ├── proguard-rules.pro              # R8 规则（含 Shizuku UserService 反射保留）
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml     # 应用清单（3 个小组件 receiver + ShizukuProvider）
│       │   ├── aidl/                   # Shizuku UserService 接口（IBlyyShellService）
│       │   ├── assets/
│       │   │   ├── blhx_avatar/        # 舰娘本地头像资产（1118，拼音命名）
│       │   │   ├── blhx_birthdays.json # 舰娘生日静态名单（离线聚合生成）
│       │   │   ├── blhx_sd/            # 舰娘 SD 小人资源（Spine 骨骼）
│       │   │   ├── live2d/             # Live2D 渲染运行时（pixi.js 内置资源）
│       │   │   └── photo_frame/        # 水印相机相框素材
│       │   ├── java/com/azurlane/blyy/
│       │   │   ├── MainActivity.kt     # 主入口
│       │   │   ├── SecretaryOverlayService.kt  # 秘书舰悬浮窗服务
│       │   │   ├── widget/             # Glance 桌面小组件（今日推荐/秘书舰问候/生日）
│       │   │   ├── data/               # 数据层
│       │   │   │   ├── local/          # Room（AppDatabase v9/DAO）+ DataStore
│       │   │   │   ├── model/          # 数据模型
│       │   │   │   └── repository/     # 数据仓库（wiki 抓取/语音解析/生日回填）
│       │   │   ├── di/                 # Hilt 依赖注入
│       │   │   ├── domain/             # 用例层（生日日历/选秘书舰等）
│       │   │   ├── service/            # Media3 PlaybackService
│       │   │   ├── ui/
│       │   │   │   ├── components/     # 公共 UI 组件（ShipCard/骨架屏/统一弹窗等）
│       │   │   │   ├── icons/          # 自定义图标
│       │   │   │   ├── screens/        # 30 个根屏幕 + birthday/gameimport/guess 等 8 个共享子包
│       │   │   │   └── theme/          # 设计系统（颜色/间距/形状/深度阴影/语义色/自适应等 14 个令牌文件）
│       │   │   ├── util/
│       │   │   │   ├── ShipNameMatcher.kt      # 舰名/资源 id 统一匹配引擎（十级策略）
│       │   │   │   ├── AvatarMatcher.kt        # 头像匹配壳（委托统一引擎）
│       │   │   │   ├── gameimport/             # 游戏资源导入管线（扫描/导入/立绘合成/导出，16 文件）
│       │   │   │   ├── unity/                  # UnityFS 解析/纹理/网格/动作还原（15 文件）
│       │   │   │   ├── shizuku/                # Shizuku 桥 + UserService 实现
│       │   │   │   ├── CustomAvatarManager.kt  # 自定义头像存储与解析（第一优先级）
│       │   │   │   ├── SDResourceManager.kt    # SD 资源管理（本地/自定义/游戏导入三源）
│       │   │   │   └── ...             # Live2D 库/更新检查/缓存/权限/水印合成等
│       │   │   └── viewmodel/          # ViewModel 层（25）
│       │   └── res/                    # 资源（图片、字符串、小组件配置）
│       └── test/                       # JVM 单元测试（36 文件 / 202 用例）
│           ├── util/                   # 匹配引擎/名单/多选守卫等
│           ├── util/unity/             # UnityFS 解析/ASTC 黄金样本/网格光栅化
│           ├── util/gameimport/        # 立绘还原/导出/索引持久化/Spine 合同
│           ├── widget/                 # 小组件逻辑
│           └── resources/              # wiki 全量舰名/资产快照/ASTC 黄金样本/图集
├── docs/                               # 项目文档
│   ├── ARCHITECTURE.md                 # 架构与 API 文档
│   ├── GAME_ASSET_IMPORT.md            # 游戏资源导入技术说明（五类资源/扫描/还原管线）
│   ├── LIVE2D_BUNDLE_IMPORT.md         # Live2D 资源包逆向结论
│   ├── USAGE.md                        # 使用说明
│   └── screenshots/                    # 截图
├── tools/astc_fixture/                 # ASTC 黄金样本生成工具链（C++ 对拍程序 + 脚本）
├── .github/                            # GitHub 配置（Issue 模板 / CI 工作流）
├── gradle/
│   ├── libs.versions.toml              # 版本目录（依赖唯一事实来源）
│   └── wrapper/
├── scripts/                            # 本地工具脚本（不入版本控制）
├── .gitignore / .gitattributes
├── build.gradle.kts                    # 根 Gradle 脚本
├── gradle.properties                   # Gradle 属性
├── gradlew / gradlew.bat               # Gradle Wrapper
├── settings.gradle.kts                 # 项目设置
├── README.md                           # 项目说明（本文档）
├── CODE_OF_CONDUCT.md / CONTRIBUTING.md / LICENSE / SECURITY.md
```

<a id="matcher"></a>

## 🧩 舰名匹配引擎

应用的所有「舰娘名 → 本地资源」解析统一走 [ShipNameMatcher](app/src/main/java/com/azurlane/blyy/util/ShipNameMatcher.kt)（纯 Kotlin，无 Context 依赖）：船坞头像、秘书舰 SD 小人、游戏资源 id、Live2D 皮肤显示名共用同一套规则，历史上两套各自演进的算法与互相冲突的别名表已收编合一。

- **资产约定**：`assets/blhx_avatar/` 以「舰名无声调全拼 + 皮肤后缀」命名（如 `boge.webp` ← 博格、`z23_h.webp` ← Z23 婚皮、`dafeng_alter.webp` ← 大凤·META）
- **十级匹配策略**：别名（有序候选，按索引实存择优）→ 变体后缀（META/μ兵装/幼女/改造）→ 尾部标记（`霞(DOA)` → `xia_doa`）→ 原名/全拼精确 → 包含 → 音节对齐前缀 → 模糊子串 → 首字母缩写 → 逐段拼接 → 拼写容错
- **硬规则**：
  - 皮肤形态（META/μ兵装/改造/婚皮）专属资源缺失时**拒绝回退基础形态**，交网络兜底（宁空勿错）
  - 前缀/子串匹配必须落在**拼音音节边界**上（"水星号" ≠ "彗星"，防跨字误配）
- **全量回归测试**：`AvatarMatcherTest` 用 wiki 图鉴 1004 个舰名逐一校验，当前 **1004/1004 命中、零碰撞**；`ShipNameMatcherTest` 锁定单点策略语义。新增资源或舰娘后运行 `./gradlew :app:testDebugUnitTest --tests "*AvatarMatcherTest*"` 防回归

<a id="testing"></a>

## 🧪 测试

```bash
# 运行全部 JVM 单元测试（36 文件 / 202 用例）
./gradlew :app:testDebugUnitTest

# 头像匹配全量回归（1004 舰名逐一校验）
./gradlew :app:testDebugUnitTest --tests "com.azurlane.blyy.util.AvatarMatcherTest"
```

代表性测试套件：

| 套件 | 守护点 |
|------|--------|
| `AvatarMatcherTest` | wiki 图鉴 1004 舰全量命中 + 零碰撞审计 |
| `ShipNameMatcherTest` | 匹配引擎十级策略的单点语义 |
| `AstcDecoderFixtureTest` | ASTC 软件解码 1952 条块级黄金样本（sha256 对拍） |
| `PaintingMeshRealDataTest` | 真实资源包端到端：碎片图集 → 整图还原 |
| `SpineRotationContractTest` | Spine 图集旋转语义合同 |
| `GameImportIndexTest` | 导入索引持久化行为 |
| `Live2dSelectionTest` | 多选删除选中集守卫（防路径穿越） |
| `GyroTiltMathTest` / `BirthdayCalendarTest` | 陀螺仪姿态数学 / 生日日历逻辑 |

提交前建议运行 `./gradlew :app:lint`（项目带 lint-baseline.xml）。

<a id="ci"></a>

## ⚙️ CI

GitHub Actions（[android.yml](.github/workflows/android.yml)）在 push / PR 时自动以 JDK 17 构建并执行检查。

<a id="contributing"></a>

## 🤝 贡献指南

我们欢迎所有形式的贡献！请阅读 [CONTRIBUTING.md](CONTRIBUTING.md) 了解详细流程。

### 提交流程

1. **Fork 项目** 并克隆到本地
2. **创建特性分支**：`git checkout -b feature/amazing-feature`
3. **提交更改**：`git commit -m 'feat: add amazing feature'`
4. **推送到分支**：`git push origin feature/amazing-feature`
5. **创建 Pull Request**

### 提交规范

本项目使用 [Conventional Commits](https://www.conventionalcommits.org/) 规范：

| 前缀 | 用途 |
|------|------|
| `feat:` | 新增功能 |
| `fix:` | 修复 Bug |
| `docs:` | 文档更新 |
| `style:` | 代码格式调整（不影响逻辑） |
| `refactor:` | 代码重构（既不是新增功能，也不是修复 Bug） |
| `perf:` | 性能优化 |
| `test:` | 测试相关 |
| `chore:` | 构建 / 工具链相关 |
| `ci:` | CI/CD 配置 |

格式：`<type>(<scope>): <subject>`，例如 `feat(voice): add play later queue animation`

### 代码规范

- 遵循 [Kotlin 官方编码规范](https://kotlinlang.org/docs/coding-conventions.html)
- UI 代码严格遵守 [设计系统](app/src/main/java/com/azurlane/blyy/ui/theme/)（14 个令牌文件）：
  - 颜色：`AppColors` + 语义色板（`SemanticColors`）
  - 间距：`AppSpacing`（8dp 网格）
  - 形状：`BlyyShapes`（切角矩形）
  - 动画：`AppAnimation`（尊重系统减动效设置）
  - 深度阴影：`AppElevation` / Depth 令牌
  - 弹窗/按钮：优先使用公共组件（`BlyyFormDialog` 等），禁止裸 `AlertDialog`
- 新增头像资源请放入 `assets/blhx_avatar/` 并遵循拼音命名约定，跑一遍头像回归测试

## 📋 行为准则

请阅读并遵守 [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)，以确保为所有人提供友好、安全和受欢迎的环境。

## 🔒 安全策略

安全相关问题请参考 [SECURITY.md](SECURITY.md)，请勿在公开 Issue 中披露安全漏洞。

## 📄 许可证

本项目采用 **GNU General Public License v3.0** 许可证 — 详见 [LICENSE](LICENSE) 文件。

## ⚠️ 免责声明

**本项目中使用的所有游戏资源（包括但不限于台词、立绘、语音、图片等）的版权归原游戏公司所有。**

- 本项目为非官方粉丝作品，仅供学习和个人娱乐使用
- 所有游戏资源版权归 **碧蓝航线**（Azur Lane）及其运营公司所有
- 游戏数据来源于 [碧蓝航线 Wiki](https://wiki.biligame.com/blhx/)
- 如有侵权，请通过 [GitHub Issues](https://github.com/oneroomlife/blyy/issues) 联系删除

**本项目不提供任何游戏资源文件，所有资源均通过网络或用户自有游戏数据从公开渠道获取。**

<a id="star-history"></a>

## 📈 Star 走势

<p align="center">
  <a href="https://star-history.com/#oneroomlife/blyy">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/svg?repos=oneroomlife/blyy&type=Date&theme=dark" />
      <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/svg?repos=oneroomlife/blyy&type=Date&theme=light" />
      <img alt="Star History Chart" src="https://api.star-history.com/svg?repos=oneroomlife/blyy&type=Date" />
    </picture>
  </a>
</p>

<a id="acknowledgements"></a>

## 🙏 致谢

### 数据与资源来源
- 📚 [碧蓝航线 Wiki](https://wiki.biligame.com/blhx/) - 舰娘数据与立绘来源
- 🦴 [Spine Runtime](https://esotericsoftware.com/spine-runtimes) - SD 小人骨骼动画运行时
- 🎞️ [pixi-live2d-display](https://github.com/guansss/pixi-live2d-display) & [Live2D Cubism SDK](https://www.live2d.com/) - Live2D 模型 Web 渲染运行时
- 🛠️ [Texture2DDecoder](https://github.com/Perfare/AssetStudio) - ASTC 软件解码参考实现（MIT）
- 🎨 [Material Design](https://material.io/) - 设计语言与组件规范
- 🛠️ [Jetpack Compose](https://developer.android.com/jetpack/compose) - 现代化的 Android UI 工具包

### 灵感与社区
- 感谢所有为本项目提交 Issue、PR、Star 的贡献者
- 感谢碧蓝航线玩家社区的反馈与建议

## 📮 联系方式

- 🐛 Bug 反馈：[GitHub Issues](https://github.com/oneroomlife/blyy/issues)
- 💡 功能建议：[GitHub Discussions](https://github.com/oneroomlife/blyy/discussions)
- 👤 维护者：[oneroomlife](https://github.com/oneroomlife)

---

<p align="center">
  Made with ❤️ by BLYY Contributors<br>
  <sub>本项目为非官方粉丝作品，与上海蛮啾网络 / 哔哩哔哩游戏无关</sub>
</p>
