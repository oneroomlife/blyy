# 🚢 BLYY - 碧蓝航线语音播放器

<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/cf.jpg" alt="BLYY Logo" width="120" height="120">
</p>

<p align="center">
  <strong>一款现代化的碧蓝航线舰娘语音播放器 Android 应用</strong>
</p>

<p align="center">
  <a href="#-功能特性">功能特性</a> •
  <a href="#-截图预览">截图预览</a> •
  <a href="#-使用说明">使用说明</a> •
  <a href="#-更新日志">更新日志</a> •
  <a href="#-安装">安装</a> •
  <a href="#-技术栈">技术栈</a> •
  <a href="#-项目结构">项目结构</a> •
  <a href="#-贡献指南">贡献指南</a> •
  <a href="#-致谢">致谢</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-green.svg" alt="Platform">
  <img src="https://img.shields.io/badge/Language-Kotlin-orange.svg" alt="Language">
  <img src="https://img.shields.io/badge/License-GPL%20v3-blue.svg" alt="License">
  <img src="https://img.shields.io/badge/API-26%2B-brightgreen.svg" alt="API">
  <img src="https://img.shields.io/badge/Jetpack%20Compose-1.7-blueviolet.svg" alt="Compose">
</p>

---

## 📖 项目简介

BLYY（**B**lu**l**ane **Y**ouTube/**Y**our **Y**our 助手）是一款专为碧蓝航线玩家设计的现代化语音播放器应用，基于 Kotlin + Jetpack Compose 构建，提供双主题风格（经典紫色 / 指挥中心 HUD）、流畅的动画、丰富的小游戏与 AI 对话能力，让舰娘语音欣赏变成一种享受。

### ✨ 功能特性

#### 🎨 视觉与交互
- **Material Design 3** 声明式 UI，深色/浅色主题自动适配
- **双主题切换**：经典风格 / 指挥中心（Command Center）HUD 风格
- **毛玻璃效果**、切角矩形面板、动态渐变背景
- **8dp 网格间距系统**、统一动画规范、按压反馈
- **小屏/手表端自适应**：检测屏幕最小宽度 ≤ 360dp 时自动缩放 UI 元素

#### 🚢 舰娘管理
- 完整的舰娘列表浏览，按阵营/类型/稀有度多维筛选
- 收藏喜爱的舰娘到后宅，长按卡片支持更多操作
- 誓约粉色特效、稀有度光晕、收藏徽章
- 卡片式布局 + 骨架屏加载

#### 🎵 语音播放
- 全量舰娘语音台词，支持中/日双语切换
- 播放模式：单曲循环 / 列表循环 / 随机播放
- **稍后播放队列**：长按语音条目添加到队列，支持队列管理
- 媒体会话控制（Media3 ExoPlayer + MediaSession）
- 锁屏通知栏控制、耳机线控支持

#### 🎭 秘书舰模式
- 可拖动的舰娘立绘小人（可隐藏到屏幕外）
- 点击立绘随机播放语音
- 沉浸式语音播放界面
- 桌面悬浮窗（需要权限）

#### 🤖 碧蓝航线助手（v1.3.0）
- **查玩家**：查询指挥官信息（等级、UID、收集率、资源、委托、科研、待办副本）
- **查建造**：查询玩家建造记录（支持分页，最多 500 条）
- UID 与服务器在「设置 → 碧蓝航线助手」统一配置

#### 🐦 啾信（AI 对话）
- 与 AI 舰娘进行角色扮演对话
- 支持多轮上下文、自定义人设
- 在「设置 → 啾信配置」中配置 API Key

#### 🎮 小游戏
- **看图识舰娘**：通过立绘辨认舰娘
- **听音识舰娘**：通过语音辨认舰娘
- 答题得分系统，错题回顾

#### 🎨 Live2D 模型浏览
- 浏览社区 Live2D 资源（[l2d.su](https://l2d.su/)）
- WebView 嵌入浏览，可放大查看
- 浮窗互动体验

#### 📱 现代化架构
- **MVVM** 架构模式 + Clean Architecture 分层
- **Jetpack Compose** 声明式 UI
- **Hilt** 依赖注入
- **Kotlin Flow** 响应式编程
- **Room** 本地数据库 + **DataStore** 偏好存储
- **Media3 ExoPlayer** 音频播放

#### ⌚ 多端适配
- 完整支持手机、平板、可折叠设备
- 智能检测小屏设备（手表等）并自动缩放 UI

## 📸 截图预览

| 船坞界面 | 后宅界面 | 语音播放界面 |
|:-------:|:-------:|:-----------:|
| ![船坞](docs/screenshots/gallery.png) | ![后宅](docs/screenshots/home.png) | ![语音](docs/screenshots/voice.png) |

> 注：截图位于 `docs/screenshots/` 目录；如截图与最新版本不一致，请提交 Issue 提醒更新。

## 🔧 环境要求

| 工具 | 版本要求 |
|------|---------|
| Android Studio | Ladybug (2024.2.1) 或更高 |
| JDK | 17 或更高 |
| Kotlin | 1.9.0 或更高 |
| Gradle | 8.7+（项目自带 wrapper） |
| Android SDK | minSdk 24 / targetSdk 35 / compileSdk 36 |
| 设备 | Android 8.0 (API 26) 或更高 |

## 📥 安装

### 从源码构建

1. **克隆仓库**
   ```bash
   git clone https://github.com/oneroomlife/blyy.git
   cd blyy
   ```

2. **打开项目**
   - 使用 Android Studio 打开项目根目录
   - 等待 Gradle 同步完成

3. **构建 APK**
   ```bash
   # Debug 版本
   ./gradlew :app:assembleDebug

   # Release 版本（需自行配置签名）
   ./gradlew :app:assembleRelease
   ```

4. **安装到设备**
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

### 下载已发布版本

前往 [Releases](https://github.com/oneroomlife/blyy/releases) 页面下载最新版本的 APK。

> 提示：项目当前 `versionName = 1.4.0`，对应 `versionCode = 1`。发布前请同步修改 `app/build.gradle.kts`。

## 📖 使用说明

### 碧蓝航线小助手

1. 打开应用，从首页侧拉菜单进入 **碧蓝航线助手**。
2. 首次使用会提示「未配置查询参数」，点击 **去设置**。
3. 在「设置 → 碧蓝航线助手」中填入：
   - **默认 UID**：游戏内玩家 UID
   - **默认服务器**：服务器名称或 ID（如 `120` / `秋季旅行`）
4. 返回助手页面，切换「查玩家」/「查建造」分区，点击对应查询按钮即可。
5. UID/服务器集中存储在 DataStore 中，查询时自动读取，切换分区时各自结果独立保留。

### 服务器名称解析

支持以下两种输入方式：
- **名称模糊匹配**：输入 `秋季` 即可匹配 `秋季旅行`（id=120）
- **数字 ID**：直接输入 `120` 即可

### 啾信（AI 对话）

1. 从侧拉菜单进入 **啾信**。
2. 首次使用会提示配置 API Key，点击 **去设置**。
3. 在「设置 → 啾信配置」填入 API Key 和模型参数。
4. 返回啾信页面，选择舰娘人设开始对话。

### 切换主题

在「设置 → 界面风格」中开启/关闭 **指挥中心 UI**，即可在经典风格与 HUD 风格之间切换。

### 稍后播放队列

1. 在语音列表页长按某条语音，选择 **稍后播放** 即可加入队列。
2. 播放控制栏的 **列表按钮** 打开队列弹窗。
3. 队列弹窗中支持：点击单项播放、播放全部、清空列表、移除单项。

### Live2D 模型浏览

在侧拉菜单中点击 **查看 Live2D** 进入 L2D 浏览页面。内置了对 [l2d.su](https://l2d.su/) 的 SSL 证书信任（如需访问其他 L2D 站点，请在「设置 → 高级」中添加信任域名）。

## 📝 更新日志

### v1.4.0 (2026-06)

#### ✨ UI 优化
- **侧拉菜单栏重构**：
  - 修复 "查看 Live2D" 图标语义错误（`Adb` → `ViewInAr`）
  - 8 个菜单项按功能分组（娱乐 / 挑战 / 工具 / 系统），每组带渐变竖条标题
  - 双风格适配：指挥中心风格使用切角矩形，经典风格使用圆角
  - 选中指示器升级为渐变竖条
  - 菜单列表改用 `LazyColumn`，支持大量菜单项时滚动
- **稍后播放队列优化**：
  - 标题栏重构：图标 + 标题 + 数量徽章 + 双按钮布局
  - 空状态添加引导文案与图标
  - 列表项提取为独立组件，结构更清晰
  - 正在播放项添加脉冲动画与主题色描边
  - 序号/状态指示区分三种状态（正在播放/下一个/未播放）
  - 头像容错：URL 为空时显示 `Person` 占位图标
  - 舰名/场景分离显示
  - 移除按钮改为低调的 `Close` 小图标

### v1.3.0 (2026-06)

#### ✨ 新增功能
- **碧蓝航线小助手**：侧拉菜单集成小助手入口，支持查玩家/查建造两种模式
- **小屏/手表端自适应**：检测屏幕最小宽度 ≤ 360dp 时自动缩放 UI 元素

#### 🔧 改进
- 设置页增加「碧蓝航线助手」配置区域
- AndroidManifest 新增 `<supports-screens>` 声明
- 版本号升级至 1.3.0

### v1.2.3 (2025)
- 新增应用内自动更新检测与多项 Live2D 稳定性优化

### v1.2.0 (2025)
- 升级指挥中心（Command Center）HUD 风格

### v1.1.x
- Live2D 模块完善、UI 优化、基础功能上线

## 🏗️ 技术栈

### 核心技术

| 技术 | 版本 | 用途 |
|------|------|------|
| [Kotlin](https://kotlinlang.org/) | 1.9+ | 主要开发语言 |
| [Jetpack Compose](https://developer.android.com/jetpack/compose) | BOM | 声明式 UI 框架 |
| [Material Design 3](https://m3.material.io/) | - | UI 设计系统 |
| [Hilt](https://dagger.dev/hilt/) | 2.59 | 依赖注入 |
| [Kotlin Flow](https://kotlinlang.org/docs/flow.html) | - | 响应式编程 |
| [Room](https://developer.android.com/training/data-storage/room) | 2.6+ | 本地数据库 |
| [DataStore Preferences](https://developer.android.com/topic/libraries/architecture/datastore) | - | 偏好设置存储 |
| [Media3 ExoPlayer](https://developer.android.com/media/media3) | 1.3+ | 音频播放 |
| [OkHttp](https://square.github.io/okhttp/) | 4.x | 网络请求 |
| [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) | 1.6+ | JSON 序列化 |
| [Coil](https://coil-kt.github.io/coil/) | 2.6+ | 图片加载 |
| [Jsoup](https://jsoup.org/) | 1.18+ | HTML 解析 |
| [compose-shimmer](https://github.com/valentinilk/compose-shimmer) | 1.3+ | 骨架屏动画 |

完整依赖与许可证信息见 [docs/DEPENDENCIES.md](docs/DEPENDENCIES.md)。

## 📁 项目结构

```
blyy/
├── app/                                # Android 应用模块
│   ├── build.gradle.kts                # 应用构建脚本
│   ├── lint-baseline.xml               # Lint 基线
│   ├── proguard-rules.pro              # R8/ProGuard 规则
│   └── src/main/
│       ├── AndroidManifest.xml         # 应用清单
│       ├── java/com/azurlane/blyy/
│       │   ├── MainActivity.kt         # 主入口（含侧拉菜单）
│       │   ├── data/                   # 数据层
│       │   │   ├── local/              # Room + DataStore
│       │   │   ├── model/              # 数据模型
│       │   │   └── repository/         # 数据仓库
│       │   ├── di/                     # Hilt 依赖注入
│       │   ├── service/                # Media3 PlaybackService
│       │   ├── ui/                     # 表现层
│       │   │   ├── components/         # 公共 UI 组件
│       │   │   ├── screens/            # 界面屏幕
│       │   │   └── theme/              # 主题与设计系统
│       │   └── viewmodel/              # ViewModel 层
│       └── res/                        # 资源（图片、字符串、主题）
├── docs/                               # 项目文档
│   ├── DEPENDENCIES.md                 # 依赖清单
│   └── screenshots/                    # 截图
├── .github/                            # GitHub 配置
│   ├── ISSUE_TEMPLATE/                 # Issue 模板
│   └── workflows/                      # CI 工作流
├── gradle/                             # Gradle Wrapper
│   ├── libs.versions.toml              # 版本目录
│   └── wrapper/
├── agent-skill-eval/                   # 技能评估（仅本地，不参与构建）
├── .gitignore                          # Git 忽略配置
├── build.gradle.kts                    # 根 Gradle 脚本
├── gradle.properties                   # Gradle 属性
├── gradlew / gradlew.bat               # Gradle Wrapper 脚本
├── settings.gradle.kts                 # 项目设置
├── README.md                           # 项目说明（本文档）
├── CODE_OF_CONDUCT.md                  # 行为准则
├── CONTRIBUTING.md                     # 贡献指南
├── LICENSE                             # GPL v3 许可证
└── SECURITY.md                         # 安全策略
```

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
- UI 代码严格遵守项目 [设计系统](app/src/main/java/com/azurlane/blyy/ui/theme/)
  - 颜色：`AppColors`
  - 间距：`AppSpacing`（8dp 网格）
  - 形状：`BlyyShapes`（切角矩形）
  - 动画：`AppAnimation`
- 提交前请运行 `./gradlew :app:lint`

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

**本项目不提供任何游戏资源文件，所有资源均通过网络从公开渠道获取。**

## 🙏 致谢

### 数据与资源来源
- 📚 [碧蓝航线 Wiki](https://wiki.biligame.com/blhx/) - 舰娘数据与立绘来源
- 🎭 [**l2d.su**](https://l2d.su/) - **Live2D 模型资源平台**。感谢 [l2d.su](https://l2d.su/) 提供高质量的 Live2D 模型浏览与下载服务，BLYY 的 Live2D 模块正是基于该网站构建。本项目内置了对 `l2d.su` 的 SSL 证书信任以便内嵌浏览。
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
