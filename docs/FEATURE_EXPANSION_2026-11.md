# BLYY 深度分析与功能扩展路线图（2026-11）

> 分析对象：`com.azurlane.blyy` v3.0.1（195 commits，2026-06-02 → 2026-11-14）
> 分析方式：全量源码阅读 + 构建产物核对 + 三路并行专项审计（资源管线 / AI 对话 / 数据网络）
> 本文只谈「往哪长」与「怎么长」，不重复 `UI_UX_FULL_AUDIT_2026-11.md` 的页面级问题清单

## ✅ 修复进展（2026-11-14 批次）

本轮已落地本文 §3 中的 4 项 P0，实现细节见各条目：

| ID | 原问题 | 处理 | 关键改动 |
|---|---|---|---|
| P0-1 | 排行榜把 GitHub PAT 打包进客户端 | **彻底移除远端排行榜**，改为「我的成绩榜」= 本地个人历史成绩榜（无网络、无凭证、无上传） | 删除 `LeaderboardRepository` / `LeaderboardCrypto` / `Leaderboard.kt`；新增 `PersonalScoreRepository` + `domain/PersonalScoreBoard` + `PersonalLeaderboardViewModel`；重写 `LeaderboardScreen`；清掉历史页的上传态与昵称弹窗 |
| P0-2 | 成绩校验只有范围检查，可刷榜 | 随 P0-1 一起消失（榜单只展示本机数据，没有"别人"可刷） | 旧 `uploadScore` / `validateEntry` / UID 去重全部删除 |
| P0-3 | API Key / UID / 服务器明文存 Preferences | 新增 `util/security/SecretCipher`（Keystore AES-256-GCM），凭证与含凭证的 JSON 块（API 配置 / 会话快照 / 啾信预设）统一切换为密文落盘，历史明文启动时就地迁移 | `PlayerSettingsDataStore`：`aiApiKey`、`aiApiConfigs`、`aiChatSessions`、`aiJiuxinPresets`、`assistantDefaultUid/Server` 读写全加密；`migrateSecretsToEncrypted()` 在 `MyApplication` 冷启动调用 |
| P0-4 | 零可观测，线上崩溃只能靠用户截图 | 新增 `util/diagnostics/`：`AppLog`（内存环形 + 文件落盘）+ `CrashReporter`（未捕获异常落盘 + 链式放行）+ `DiagnosticsExporter`（zip 分享）；设置页新增「诊断与反馈」 | `MyApplication` 安装；`res/xml/file_paths.xml` 增 `diagnostics/`；设置页可导出/清空 |
| P0-6 | `versionCode` 恒为 1 | 由 `versionName` 推导（`3.0.1 → 3_000_100`），并在构建脚本内校验区间 | `app/build.gradle.kts` 的 `versionCodeOf()` |
| P0-5 | 无备份/恢复：78 个 key + Room 全在本地，换机即清零 | 新增「备份与恢复」完整功能：SAF 导出/导入 + 本机滚动备份 + 每日自动备份 + 恢复前预览确认 | `data/backup/BackupManager` + `BackupValidator`（纯函数校验，12 例单测）+ `BackupRestoreViewModel` + `BackupRestoreScreen`；含"名录未建立时誓约暂存、刷新后补写"的闭环 |

**第二轮清理（2026-11-15）：旧排行榜零残留**

除删除代码外，把命名与数据也一并收口，避免"删了功能、留了痕迹"：

| 类型 | 处理 |
|---|---|
| 类/文件命名 | `LeaderboardScreen` → `PersonalScoreScreen`；`PersonalLeaderboardViewModel` → `PersonalScoreViewModel`；`PersonalLeaderboardUiState` → `PersonalScoreUiState` |
| 路由/回调 | 路由 `leaderboard` → `my_scores`；`onLeaderboard` → `onScoreBoard` |
| 偏好键 | `leaderboard_nickname` → `commander_nickname`（读取侧兼容旧键，启动时迁移并删除）；`leaderboard_cache_json/ts` 由 `purgeLegacyLeaderboardData()` 清除 |
| API | `leaderboardNickname` → `commanderNickname`、`setLeaderboardNickname` → `setCommanderNickname` |
| 文案/图标 | 历史页入口图标改为奖杯（`EmojiEvents`），文案统一为「我的成绩榜」 |

> 迁移逻辑集中在 `PlayerSettingsDataStore.purgeLegacyLeaderboardData()`（幂等、启动执行），老用户升级后昵称不丢、缓存不残留。

仍待处理：P1 全量（离线优先、god class 拆分、i18n、AI 流式等）—— 见 §4 与 §6 路线图；备份/恢复（P0-5）已于本轮补齐。

---

## 0. 一页纸结论

**一句话定位**：BLYY 已经不是「语音播放器」，而是一个**已验证技术纵深的二游内容终端**——UnityFS 解析、Spine/Live2D 实时渲染、Shizuku 免电脑读私有目录、Media3 后台播放、Glance 组件、Compose 双主题，这套组合在国内粉丝向 App 里属于第一梯队。

**但它现在的天花板不在「能不能解析」，而在四个地方**：

| # | 结构性判断 | 证据 | 后果 |
|---|---|---|---|
| 1 | **内容没有沉淀**：只有「名录」进了 Room（2 张表），语音/立绘/角色档案/学生画廊全在内存或 5 分钟 HTML 缓存里 | `AppDatabase.kt:20`、`ShipRepository.kt:107` | 断网 = 语音/立绘/画廊全空；每次冷启动重新爬 wiki，抓取又是全局 500ms 串行闸门（`NetworkHelper.kt:29-46`） |
| 2 | **陪伴没有关系**：「誓约」就是 `isFavorite` 布尔值 | `ShipCard.kt:151`、`GalleryScreen.kt:378` | 婚礼立绘/誓约语音/纪念日/好感度这些最贵的情感资产，一个都没接住 |
| 3 | **重活已经做完，轻活没人收**：解析层 5 类资源已闭环，但 Live2D 表情（UI 面板已就绪）、图标图集、CG 相册、BGM 都还空着 | `Live2dViewerScreen.kt:963-987` vs `AlLive2dExtractor.kt:156-168` | 投入产出比最高的增量全在「复用现成管线」这一侧 |
| 4 | **平台能力大面积空转**：无 WallpaperService / 无 WorkManager（依赖只在 toml 里躺着）/ 无 AlarmManager / 无 Paging / 无 Retrofit / 无崩溃上报 / 无 i18n | 见 §2.5 | 触达、留存、可运营性三件事都缺抓手；出不了海，也定位不了线上崩溃 |

**Top 12 建议（按 价值/成本 排序，详见 §4）**

| 排名 | 建议 | 方向 | 量 | 为什么现在做 |
|---|---|---|---|---|
| 1 | 语音/立绘/档案落库 + 预置 `ships.db` 快照 | 数据 | M | 一次性拆掉「冷启动必须联网」这个体验天花板 |
| 2 | 站内「舰娘详情页」（属性/技能/装备/皮肤/语音/评价） | 图鉴 | L | 目前点「Wiki」是跳外部浏览器（`GalleryScreen.kt:288`），用户一出去就不回来 |
| 3 | 誓约语义修正（与收藏解耦）+ 誓约纪念日 | 情感 | S/M | 半天数据层 + 一天 UI，换来整条情感主线 |
| 4 | 备份/恢复（WebDAV / Gist）+ 首次换机引导 | 数据 | M | 78 个 DataStore key + Room 全在本地，换机即清零 |
| 5 | 崩溃上报 + 启动/抓取耗时埋点 | 基建 | S | 全项目 0 可观测，线上崩了只能靠用户截图 |
| 6 | Live2D 表情/姿态库（管线已能枚举 TextAsset） | 资源 | S | 管线侧只差 `buildModel3` 写 `Expressions` 段，UI 面板已存在 |
| 7 | 台词卡分享（图片+音频，走 `ACTION_SEND`） | 传播 | S | 现在「分享」= 复制链接（`VoiceScreen.kt:199-204`） |
| 8 | 播放器补齐：睡眠定时 / 倍速 / 持久队列 / 音频焦点 | 音频 | S | Media3 基础已就位，只差产品化外壳 |
| 9 | AI 流式输出 + 供应商降级链 + 成本统计 | AI | S/M | 响应体现在一次性返回，打字机是假动画 |
| 10 | 拼音/别名/CV/画师 搜索与筛选 | 图鉴 | S | 现有搜索是 `name.contains`（`GalleryViewModel.kt:134`），`PinyinHelper` 已在仓库里 |
| 11 | 年度/月度报告（Recap）+ 分享图 | 传播 | S/M | 播放/收藏/答题数据全在本地，天然可生成 |
| 12 | Live2D 动态壁纸（WallpaperService） | 触达 | M/L | 现有渲染栈可复用，是留存最强的常驻形态 |

---

## 1. 工程体检

### 1.1 体量分布

| 层 | 文件 | 行数 | 占比 | 备注 |
|---|---|---|---|---|
| `ui/` | 92 | 45,284 | 54.5% | 57 个 screen 文件 + 19 个 component + 14 个 theme token |
| `util/` | 75 | 19,072 | 22.9% | Unity 解析 15 + 资源导入 16 + Shizuku 2 + 匹配/下载/水印等 |
| `viewmodel/` | 25 | 10,170 | 12.2% | 单个 `JiuxinViewModel` ≈3.0k 行＝全层 30% |
| `data/` | 20 | 4,912 | 5.9% | Room 2 表 + DataStore 78 key + 5 repository |
| `widget/` | 6 | 1,310 | 1.6% | 3 个 Glance 组件 + 同步器 |
| `domain/` `service/` `di/` + 根 4 文件 | 13 | 1,300 | 1.6% | 用例层偏薄，注入只有 1 个 Module |
| **合计** | **232** | **83,151** | 100% | 测试另有 45 文件 / 5,981 行 |

**判读**：这是典型的「UI 与工具深、领域与数据浅」结构。`domain/` 只有 7 个文件（多数是单行转发），业务规则主要活在 ViewModel 与 repository 里——这也是 god VM 的成因。

### 1.2 技术底座

Kotlin 2.1.10 / AGP 9.1.1 / Gradle 9.8 / Compose BOM / Hilt 2.59.2 / Room 2.8.4（v9 schema 已导出）/ DataStore 1.2.0 / Media3 1.3.1 / Glance 1.1.1 / Shizuku 13.1.5 / Spine 3.8.99 + libgdx 1.13.5 / OkHttp 4.12 / Jsoup 1.18 / Coil 2.7 / pinyin4j / desugar 2.1.5。minSdk 24 / targetSdk 35 / compileSdk 36。

**已被验证的两个硬事实**（值得写进 README，避免后来者踩坑）：

- `libgdx.so` 的 ELF `LOAD` 段对齐为 **0x4000**（arm64 & armeabi-v7a 均实测），符合 Android 15 的 16 KB page size 要求。**新增 native 依赖时需重复这项校验**，这是上架级红线。
- 16 KB 之外，`gdx-platform` 的 `.so` 必须由 `extractGdxNatives` 任务搬进 `generated/gdxNatives` 源集，否则 `System.loadLibrary("gdx")` 直接崩（`app/build.gradle.kts:111-147` 已注释清楚）。

### 1.3 产物与过程

| 项 | 值 | 判读 |
|---|---|---|
| release APK | 30.6 MB | assets 22.9 MB（头像 15.2 + 相框 6.9 + Live2D 运行时 0.8），native 1.1 MB |
| debug APK | 58.6 MB | 正常 |
| APK 内 ABI | arm64 / v7a / x86 / x86_64 四套全打 | 走 AAB 或 `splits` 可省 1 MB+，并让 Play 按设备下发 |
| 提交节奏 | 195 commits / 5.5 个月 | 单人高强度迭代，提交信息规范度高（Conventional Commits 落地） |
| 版本号 | `versionCode = 1`，`versionName = "3.0.1"` | ⚠️ versionCode 恒为 1，无法做灰度/回滚判定，Play 也会拒绝 |

### 1.4 质量基线

- **测试**：45 个 JVM 测试文件 / 5,981 行，覆盖纯函数与真实资源包（ASTC 1952 条黄金样本对拍、Spine 图集旋转语义、立绘网格还原、头像 1004/1004 全量命中）。**覆盖的盲区很明确**：`GameBundleImporter` / `GameBundleScanner` / `GameAssetLibrary`（Context + IO + 失败态最密集处）零覆盖，EGL 路径只测了格式映射。
- **CI**：单 workflow，JDK 17 构建 + 单测；无 instrumented test、无截图测试、无 Baseline Profile。
- **Lint**：带 baseline，意味着历史问题被冻结——新增代码不会红，但也提醒「lint 已不再是护栏」。
- **R8**：`proguard-rules.pro` 85 行，Room/序列化模型全量 keep，Shizuku UserService 反射已保。代价是 JSON 字段名全部留在 APK 里。

### 1.5 功能地图（现状，用于避免重复建设）

| 模块 | 现状能力 | 页面 |
|---|---|---|
| 后宅 | 誓约（=收藏）舰娘展示、AGSL 动态光效、小屏自适应 | `home` |
| 船坞 | 1004 舰全量图鉴、阵营/舰种/稀有度筛选、名称搜索、双档案（DOCK 舰船 / STUDENT 学生）、长按菜单（誓约/自定义头像/立绘/Wiki） | `gallery` |
| 语音 | 皮肤分组台词、中/日切换、三播放模式、稍后播放队列、单句收藏置顶、下载、复制分享、Media3 锁屏控制 | `voice/*`、`student_voice/*` |
| 立绘 | 立绘浏览器、点击下载到 `Downloads/BLYY/` | `gallery/*`、`student_gallery/*` |
| 秘书舰 | 悬浮窗（拖拽/位置记忆/触摸穿透/帧率档）、今日秘书舰按稀有度抽取、SD 皮肤与缩放 | `secretary_*` |
| 资源管线 | Shizuku 直读 → 5 类资源解包（Live2D / SD / 动态皮肤 / 立绘 / 过场图），扫描快照 81ms 秒开，ASTC 软解兜底，导出 | `gameimport*`、`live2d*` |
| Live2D | 模型库、查看器、陪伴模式（时钟/电量）、陀螺仪注视、表情面板 | `live2d`、`live2d/view` |
| AI 啾信 | OpenAI 兼容、多 API 配置、人格、长期记忆摘要、群聊、语音标签触发原声、贴纸 | `jiuxin_*` |
| 小游戏 | 看图/听音识舰娘、难度、提示扣分、历史、**个人成绩榜（本地）** | `guess_*`、`my_scores` |
| 助手 | 查玩家（档案卡 6 宫格）、查建造（500 条 + 属性筛选） | `assistant*` |
| 其他 | 生日日历、水印相机、应用图标切换、SD 资源图鉴、双主题、3 个桌面组件、`blyy://` 深链 | 多处 |

**结论**：功能面已经「宽」，下一步该「深」——同一条主线往下做三层（数据 → 关系的语义 → 常驻触达），而不是继续加第 41 个页面。

---

## 2. 架构评估

### 2.1 分层实现度

```
UI (Compose) ──> ViewModel ──> Repository ──> Room / DataStore / OkHttp+Jsoup+WebView+HttpURLConnection
                   │
                   └─(25 个 VM 中 6 个直连 DataStore/DAO/OkHttp，绕过 repository)
domain/ 仅 7 文件，且多为一行转发（GetShipsUseCase.kt 14 行、ToggleFavoriteUseCase.kt 10 行）
```

- **Clean 是「形似」**：`domain` 没有承接规则，用例层退化为函数别名。真正需要被复用的规则（筛选、排序、抽取秘书舰、记忆摘要触发、资源可用性判定）分散在 VM 与 repository 内。
- **Hilt 只有 1 个 Module**（`di/AppModule.kt` 82 行，只提供 OkHttp/Json/Context 之类）。仓库层、匹配器、管理器大量走 `@Inject constructor` + `@Singleton`，没有接口抽象 → 单测必须起 Context 或跳过，这也是 `GameBundleImporter` 零覆盖的直接原因。

### 2.2 耦合热点（改一处要读一片）

| 文件 | 行数 | 问题 |
|---|---|---|
| `viewmodel/JiuxinViewModel.kt` | ≈3,000 | 网络 + 持久化 + 提示词 + 裁剪 + 记忆摘要 + 语音标签 + 贴纸 + 群聊编排全在一个类 |
| `ui/components/SpineSdView.kt` | ≈1,900 | GLSurfaceView + Renderer + 内容度量 + Gdx 桩实现挤在同一文件 |
| `ui/screens/gameimport/GameImportTabs.kt` | ≈1,500 | 列表/预览/批量操作/徽标全部内联 |
| `SecretaryOverlayService.kt` | 1,376 | 窗口管理 + 手势 + 屏幕状态广播 + 通知 + 双 ComposeView |
| `viewmodel/GameImportViewModel.kt` | 1,189 | 通道状态机 + 扫描 + 导入 + 排序 + 提示 |
| `util/gameimport/GameBundleScanner.kt` | 827 | 通道状态机 + 根目录发现 + 扫描 + **导入**（导入本属 Importer） |
| `data/repository/ShipRepository.kt` | 1,279 | wiki 抓取 + gamekee 抓取 + 语音解析 + 生日回填 + 学生 14 维筛选，五件事一个类 |

### 2.3 数据层：Room 只是「名录库」

- `ships` 12 列 + `guess_history` 13 列，仅此两表（`AppDatabase.kt:20`，v9 已导出 schema）。
- **未落库**：语音列表、立绘 URL、角色档案（`ShipCharacterInfo`）、学生画廊（含视频）、建造记录、排行榜。
- DataStore 78 个 key，其中还有 **2 类无上限动态 key**：`fig_<舰名>`、`ai_session_msgs_<会话id>`。
- 后果链条：`每条 AI 消息 = 一次全量 JSON 重写（会话 200 条）+ 会话列表重写`；`冷启动 = 重新打 wiki`；`断网 = 语音/立绘/画廊空白`。
- **TTL 碎片化**：CacheManager 默认 30 分钟，HTML 5 分钟，语音 1 小时，档案时间戳 5 分钟，排行榜 5 分钟/1 小时。同一屏里的数据可能来自五个不同新鲜度。

### 2.4 网络层：四套栈 + 一道全局闸门

| 栈 | 用途 | 特征 |
|---|---|---|
| Jsoup | wiki 名录/语音/立绘 | 5 UA 轮换 + 403/429 退避（`NetworkHelper.kt:20-107`），但**全局限流 500ms 互斥**（`:29-46`） |
| WebView | gamekee（Vue SPA） | 注入 JS 点击侧栏 + 桌面 UA（`WebViewHtmlFetcher.kt:128,367`），每次改版都可能碎 |
| OkHttp | B站接口 / GitHub | 单例无拦截器，30/60/120s 超时，磁盘缓存 20 MB（`AppModule.kt:58-82`） |
| HttpURLConnection | 更新检查 / 网盘链接 / 下载 | 手写连接，无统一重试 |

- **限流副作用**：`enrichStudentFilterData` 逐个 `delay(500)`（`ShipRepository.kt:900-924`）→ 300 个学生约 30 分钟。限流本身是**正确设计**，问题在于「串行 + 无并发 + 无断点续跑」。
- **错误模型三套并存**：`Result<Document>`、`throw IllegalStateException`、返回空对象静默吞。调用方无法统一区分「暂时失败」与「结构变更」。
- **无证书 pinning**（只信系统 CA，反而是好的取舍）、**无全局 UA**、**无跨栈并发限流**。

### 2.5 平台能力缺口（逐项实测）

| 能力 | 现状 | 影响 |
|---|---|---|
| WallpaperService | 0 处 | 最强常驻形态（Live2D 壁纸）缺席 |
| WorkManager | toml 里声明 `work = "2.11.1"`，**build.gradle 未引用，0 个 import** | 无任何周期性后台任务能力；README 却写着「WorkManager 小组件刷新」→ 文档漂移 |
| AlarmManager | 0 处 | 生日/委托/维护/纪念日提醒做不了 |
| Paging3 | 0 处 | 千级列表全量在内存，筛选在 Composable 侧 |
| Retrofit | 0 处（OkHttp 手写） | 尚可，但 4 套栈收敛时值得一起做 |
| 流式（SSE） | 0 处，AI 一次性返回 | 打字机是假动画 |
| STT / 录音 | 无 `RECORD_AUDIO` | 「用说的」这条最自然的交互缺失 |
| 崩溃上报 / 埋点 | **0 处**（`setDefaultUncaughtExceptionHandler` 搜索命中是误报） | 线上崩溃只能靠用户截图 |
| i18n | 仅 `values/` + `values-night/`，`strings.xml` 0.7 KB，源码内 **4,189 处**中文字面量 | 出海成本被推到很高；也无法做文案热修 |
| 无障碍 | 有 reduce-motion 降级，但自定义 Canvas/GL 组件缺语义标签 | 见既有 UI 审计 |
| 大屏/折叠 | 有手表（≤360dp）自适应，无平板/折叠双栏 | 平板体验等同放大手机 |

### 2.6 文档与实现漂移（会误导贡献者）

| 文档说法 | 实际 |
|---|---|
| README「WorkManager 2.11.1 小组件后台刷新」 | 未引用该依赖，刷新走 `WidgetSync` 直连会话 |
| README「36 文件 / 202 用例」 | 实际 45 文件 / 5,981 行 |
| README「Room v9 schema 导出」 | ✔ 一致（`app/schemas/.../9.json`） |
| `docs/ARCHITECTURE.md` 的模块树 | 只覆盖早期模块，缺 `widget/`、`gameimport/`、`unity/`、`shizuku/`、`chat/`、`assistant/` |

**建议**：文档与代码同批评审（CI 里加一条「README 引用的类/依赖必须存在」的脚本即可）。

---

## 3. 关键问题诊断

### P0（安全 / 服务可用性 / 数据丢失）

| ID | 问题 | 证据 | 后果 |
|---|---|---|---|
| P0-1 | **排行榜把 GitHub PAT 打包进客户端**，`LeaderboardCrypto` 只是凭证混淆，不是成绩签名 | `LeaderboardRepository.kt:57-58`、`LeaderboardCrypto.kt:29-100` | Frida hook 即可取到 PAT → 全库改写/清空；token 随 APK 扩散后 GitHub 自动吊销 → **全体用户同时无法上传** |
| P0-2 | 成绩校验只做范围检查 | `LeaderboardRepository.kt:128-144` | `score = Int.MAX_VALUE` 可上榜；100 个假 UID 就能把真人挤出 Top100 |
| P0-3 | **AI API Key / 游戏 UID / 服务器名明文存 Preferences**，且 Key 被复制进每个会话快照 | `PlayerSettingsDataStore.kt:154,762`、`JiuxinModels.kt:243` | 备份文件、日志、截图任意一处泄露即扩散 |
| P0-4 | 无崩溃上报、无异常兜底日志落盘 | 全局搜 0 命中 | 线上问题不可定位，只能等复现 |
| P0-5 | 无备份/恢复：78 个 key + Room 全在本地 | 全局 | 换机/重装 = 收藏、誓约、人格、记忆、设置**全丢**，且无导出通道 |
| P0-6 | `versionCode` 恒为 1 | `app/build.gradle.kts:31` | 无法灰度/回滚；上架应用商店会被拒 |

### P1（体验天花板 / 可维护性）

| ID | 问题 | 证据 |
|---|---|---|
| P1-1 | 语音/立绘/档案/画廊未落库，断网即空；冷启动依赖 wiki | §2.3 |
| P1-2 | wiki CSS 选择器与 gamekee SPA 结构硬编码，已改版两次 | `ShipRepository.kt:163,317,520,1277`、`:742` 兜底正则 |
| P1-3 | 全局 500ms 限流 + 串行 → 学生 enrich 30 分钟量级 | `NetworkHelper.kt:29-46`、`ShipRepository.kt:900-924` |
| P1-4 | AI 每条消息全量重写 JSON（会话 + 列表两次） | `JiuxinViewModel.kt:2917,2981` |
| P1-5 | 私聊上下文按「条数」裁剪（`takeLast(20)`），不按长度 | `JiuxinViewModel.kt:3017` |
| P1-6 | 每次贴图解码新建 EGL 上下文并在 finally 销毁 | `CompressedTextureDecoder.kt:307-342,367-388,566+`（共 6 处 create/terminate 配对） |
| P1-7 | 落盘逐张 PNG `compress(100)` | `UnityTextureExtractor.kt:94-97` |
| P1-8 | 「新增一类资源」要动 12 个文件（kind 枚举 × 10+ 处 exhaustive when） | 见 §4.6 |
| P1-9 | 誓约 = `isFavorite`，语义错位，无法承载婚礼立绘/誓约语音/纪念日 | `ShipCard.kt:151`、`GalleryScreen.kt:378` |

### P2（打磨 / 一致性）

i18n 缺失（4,189 处字面量）、无 Paging、四套网络栈、错误模型三套、TTL 碎片化、测试对 IO 层零覆盖、无障碍标签不全、大屏无布局分支、APK 四 ABI 全打、文档漂移。

---

## 4. 扩展蓝图

### 4.0 四条设计原则（决定「做成什么形状」）

1. **先沉淀，再展示**：任何新内容先问「它落在 Room 的哪张表」——落不了库的功能，冷启动/断网就是半残。
2. **关系 > 展示**：能产生「我与这个舰娘之间发生过什么」的功能优先（誓约、纪念日、好感、记忆、周报），这类功能才有留存。
3. **复用现有纵深**：新功能的成本 ≈ 0 的部分，尽量挂在已有的解析/渲染/播放/合成能力上（表情库、CG 相册、BGM、台词卡）。
4. **常驻三件套**：壁纸 / 组件 / 通知。用户不打开 App 的时间占 99%，触达决定生死。

### 4.1 全量候选池（40 项）

价值 = 用户价值，成本 = 1 名资深 Android 的人力（S ≤ 3 人日 / M 4–10 / L 11–25 / XL > 25）

| ID | 功能 | 方向 | 价值 | 成本 | 依赖 |
|---|---|---|---|---|---|
| A1 | 舰娘详情页（属性/技能/装备/皮肤/语音/评价） | 图鉴 | ★★★★★ | L | B1, A3 |
| A2 | 装备库 + 配装/伤害计算器 | 图鉴 | ★★★★ | L | A1 |
| A3 | 图鉴数据落库 + 预置 `ships.db` + 增量热更 | 数据 | ★★★★★ | M | — |
| A4 | 拼音/别名/首字母/CV/画师 搜索与筛选 | 图鉴 | ★★★★ | S | A3 |
| A5 | 台词全文检索（跨舰娘、跨皮肤） | 图鉴 | ★★★ | S | A3 |
| A6 | 舰队编成/阵容分享（图鉴级，非战斗模拟） | 图鉴 | ★★★ | M | A1 |
| B1 | 誓约状态与收藏解耦（婚礼立绘/誓约语音/誓约时间） | 情感 | ★★★★★ | S | — |
| B2 | 纪念日体系（誓约/入港/生日/周年）+ 提醒 | 情感 | ★★★★ | M | B1, H0 |
| B3 | 好感度/亲密度（互动累积、解锁台词与头像框） | 情感 | ★★★★ | M | B1 |
| B4 | 舰娘日记/周报（基于交互与收听记录生成） | 情感 | ★★★ | M | B3, C6 |
| B5 | 每日一句（通知/锁屏/组件） | 情感 | ★★★★ | S | H0 |
| C1 | 播放器产品化：睡眠定时/倍速/持久队列/音频焦点 | 音频 | ★★★★ | S | — |
| C2 | 本地语音库（游戏 voice 导入 + 收藏离线下载管理） | 音频 | ★★★★ | M | C1, F5 |
| C3 | BGM/角色歌播放器（新资源 kind + 音乐 UI） | 音频 | ★★★★★ | M | F5 |
| C4 | 台词剧场/舰娘电台（场景串播 + BGM + 字幕） | 音频 | ★★★★ | M | C3 |
| C5 | 台词卡分享（图片+音频，`ACTION_SEND`） | 音频/传播 | ★★★★ | S | — |
| C6 | 收听统计与年度回顾（Recap） | 传播 | ★★★★ | S/M | — |
| D1 | Live2D 动态壁纸（WallpaperService） | 触达 | ★★★★★ | M/L | — |
| D2 | 组件矩阵扩充（播放控制/语音日签/日历/导入进度） | 触达 | ★★★ | S/M | C1 |
| D3 | 通知中心与快捷操作（每日一句/生日/活动） | 触达 | ★★★ | S | H0 |
| D4 | 平板/折叠双栏 + 大屏布局分支 | 体验 | ★★★ | M | — |
| D5 | Android Auto / Wear Tile（语音播放场景） | 触达 | ★★ | M | C1 |
| E1 | AI 流式输出 + 可中断 + 打字机 | AI | ★★★★★ | S | — |
| E2 | 供应商降级链 + 成本/用量统计 | AI | ★★★★ | S | — |
| E3 | 记忆条目化 + 可视化编辑/删除 | AI | ★★★★ | S | — |
| E4 | RAG 事实源（舰船资料/台词进上下文） | AI | ★★★★★ | M | A3 |
| E5 | STT 语音输入 | AI | ★★★★ | M | — |
| E6 | 拍照/截图识舰娘（多模态） | AI | ★★★ | M | A3 |
| E7 | 群聊增强（关系矩阵/话题接力/发言权） | AI | ★★★ | S | E1 |
| E8 | 人格市场 + 导入导出 | AI | ★★★ | S | — |
| E9 | 主动消息（嘘寒问暖，WorkManager 定时） | AI | ★★★★ | M | H0, B3 |
| E10 | 离线小模型（llama.cpp/MLC） | AI | ★★ | XL | — |
| F1 | Live2D 表情/姿态库 | 资源 | ★★★★★ | S | — |
| F2 | 图标图集（舰船/装备/技能） | 资源 | ★★★★ | M | — |
| F3 | CG/过场批量相册 | 资源 | ★★★★ | S | — |
| F4 | 立绘差分（大破/表情脸/破损） | 资源 | ★★★ | M | — |
| F5 | 音频资源（语音包 / BGM） | 资源 | ★★★★★ | M/L | — |
| F6 | kind 注册表重构（12 处 → 1 处） | 资源 | ★★★ | M | — |
| G1 | 多游戏模块化（STUDENT→GameProfile） | 平台 | ★★★★ | L | A3 |
| G2 | 新 IP 接入模板（名录/语音/立绘/筛选维度） | 平台 | ★★★ | M | G1 |
| H1 | 崩溃上报 + 埋点（自托管） | 基建 | ★★★★ | S | — |
| H2 | 备份/恢复 + WebDAV/Gist 云同步 | 基建 | ★★★★★ | M | — |
| H3 | i18n（JA/EN）+ 远程文案 | 基建 | ★★★★ | L | — |
| H4 | 模块化拆分（`:core/:data/:feature-*`） | 基建 | ★★★ | L | — |
| H5 | 性能：Baseline Profile / WebP 落盘 / EGL 单例 | 基建 | ★★★★ | M | — |
| H6 | 排行榜后端整改（去 PAT + 服务端校验） | 基建 | ★★★★ | M | — |

### 4.2 A｜图鉴深度化：从「跳转浏览器」到「站内一站到底」

**A1 舰娘详情页**

- *缺口*：现在点「Wiki」开外部浏览器（`GalleryScreen.kt:288`、`HomeScreen.kt:143`）；`Ship` 实体只有 type/rarity/faction/extra 四个字符串，**没有任何数值**（`Ship.kt:17-37`）。
- *方案*：新建 `ShipDetail`（Room v10 表：`shipName` PK、`statsJson`、`skillsJson`、`equipSlotsJson`、`acquire`、`cv`、`illustrator`、`releaseDate`、`voiceActor`）。数据两条腿：① wiki 详情页解析（复用 `NetworkHelper`，选择器集中到一张 `WikiSelectors.kt` 便于改版时一处修）；② 预置快照（A3）做首屏与离线兜底。
- *UI*：`ShipDetailScreen` 用「档案卡 + 分页签（属性/技能/装备/皮肤/语音/评价）」；皮肤 Tab 直接复用 `ShipGalleryViewModel`，语音 Tab 复用 `VoiceViewModel`。
- *量*：L（数据模型 + 解析 + 2 个页面）。
- *验收*：随机 20 舰的属性/技能与 wiki 一致；断网时 100% 有内容；从船坞进详情 <300ms。

**A2 装备库与配装器**

- *方案*：`Equipment` 表（名称/类型/属性/适用舰种/获取途径）+ `Loadout` 本地保存；计算器只做**面板属性求和**（不是战斗模拟，避免维护深渊）。
- *量*：L。*风险*：装备数据量大（500+），解析成本与 A1 同源，建议与 A1 同批做。

**A3 数据落库 + 预置快照（最高杠杆的基础设施）**

- *现状*：`ships` 只有名录；语音/立绘在内存 Map + 5 分钟 HTML 缓存（`ShipRepository.kt:107`）。
- *方案*：
  1. `ships` 加 `voicesJson` / `galleryJson` / `profileJson` 三列（或拆 `ship_voices` / `ship_gallery` 表，利于按 URL 去重）；
  2. `AppModule` 用 `Room.databaseBuilder(...).createFromAsset("blyy.db")` 预置首启快照（1004 舰名录 + 生日 + 常用语音索引，估计 2–4 MB）；
  3. `SnapshotManifest(version, sha256, url)` 走现有 `RemoteConfigFetcher` 多源链路增量下发（服务端就是 GitHub Release，无需新后端）；
  4. 读取路径统一「Room 优先 → 网络补 → 回写」，UI 侧只需 `Flow`。
- *量*：M。*收益*：首启零网络可用；抓取从「每次冷启动」变成「每天一次后台增量」。
- *验收*：飞行模式下语音列表、立绘、图鉴、日历全可打开；冷启动到船坞可交互 <2s。

**A4 搜索与筛选增强**

- *现状*：`ship.name.contains(query, ignoreCase)`（`GalleryViewModel.kt:134`）——搜「企业」能中，搜「qy」不中。
- *方案*：`PinyinHelper`（已在仓库）生成首字母与全拼索引列；别名表复用 `ShipNameMatcher` 的 A 类别名；新增 CV/画师/实装年份/改造与科研标签筛选。
- *量*：S。*验收*：搜「qy / 企业 / enterprise」同一结果；CV 筛选命中率与 wiki 一致。

### 4.3 B｜誓约与陪伴：把「布尔值」升级成「关系」

**B1 誓约语义修正（性价比之王）**

- *现状*：`isOathed = ship.isFavorite`（`ShipCard.kt:151`），Toast 文案直接叫「已与 X 誓约」（`GalleryScreen.kt:378`）。收藏与誓约共用一个布尔 → 用户想「收藏但没誓约」就做不到。
- *方案*：`ships` 加 `oathAt: Long?`（0 表示未誓约），`isFavorite` 回归收藏；迁移时把**现有的 `isFavorite = 1` 全部视为已誓约**（保平滑）。头像解析链 `isOathed` 分支不变，立即获得婚礼立绘；新增「誓约语音」分组（wiki 有【誓约】表，`SkinVoiceIndex.kt:11` 已识别该命名规则）。
- *量*：S（半天数据层 + 一天 UI/文案）。*验收*：可同时存在「收藏未誓约」「誓约未收藏」两种状态；誓约后立绘自动切婚皮且可回退。

**B2 纪念日体系**

- *方案*：`Milestone` 表（舰名/类型[誓约/入港/生日/周年]/日期/是否提醒）；提醒走 `WorkManager`（需先补依赖，见 H0）每日 9:00 检查一次，命中则发通知；组件「舰娘生日」扩成「纪念日」。
- *量*：M。*验收*：誓约当天与周年当天各有一次通知；关闭提醒后不再打扰。

**B3 好感度**

- *方案*：`Affinity(shipName, exp, level, lastInteract)`；行为埋点：播放台词 +1、看图 +1、答题答对 +2、AI 对话 +1/轮（每日封顶）。等级解锁：新台词分组、头像框、Live2D 表情。**不要做成体力/数值游戏**，只做「陪伴的度量」。
- *量*：M。*风险*：数值设计容易滑向「逼肝」，需给「纯陪伴模式」开关。

**B4 舰娘日记 / 周报**

- *方案*：以 B3 事件流为素材，本地模板化生成（不依赖 LLM，LLM 可选润色）："这周你听了我 37 句台词，最多的是「出击」场景"。配 C6 一起做，一次投入两处收益。

**B5 每日一句**

- 复用 `WidgetData` 的台词缓存与 `SecretaryGreetingWidget` 的选句逻辑，发「每日 8:00 通知 + 长按复制」。S 级。
- 注意：`POST_NOTIFICATIONS` 已在 Manifest 申请，但**没有任何通知渠道用于此类提醒**（只有播放与悬浮窗两个 channel），需新建低打扰 channel。

### 4.4 C｜音频体验：从「播台词」到「音频终端」

**C1 播放器产品化（S，一周内可完成）**

| 项 | 现状 | 做法 |
|---|---|---|
| 睡眠定时 | 无 | `Player` + 协程倒计时 + 淡出 3s |
| 倍速 | 无 | `PlaybackParameters` 0.75–1.5x（台词练习场景真实有用） |
| 队列持久化 | 有 `play_later_v2`，但**播放进度与当前曲目未持久化** | 序列化 `currentItemIndex + position`，冷启动恢复 |
| 音频焦点 | 无 `AudioFocus` 处理（只有 ExoPlayer 默认 handleAudioFocus） | 显式 `AudioAttributes` + 来电/其他 App 播放时 duck |
| 耳机线控 | Media3 默认有 | 补「双击下一句」语义 |

**C2 本地语音库**：把「下载」从一次性动作升级为可管理的离线库（列表、占用空间、批量下载某舰全部台词、按皮肤清理）。当前 `MediaDownloader` 只把文件丢进 `Downloads/BLYY/`（`VoiceScreen.kt:187-197`），**不入库也就无法在 App 内浏览**。

**C3 BGM / 角色歌**：见 F5。这是把 App 从「配音播放器」升级为「碧蓝音乐播放器」的关键一步，用户感知极强。

**C4 台词剧场 / 电台**：`VoiceLine.scene` 本身就是剧情顺序；按场景串播 + BGM 垫底 + 滚动字幕 = 通勤场景的杀时间内容。不需要新数据，只需编排器。

**C5 台词卡分享**：现在 `shareVoice` 只把「台词 + 链接」写剪贴板（`VoiceScreen.kt:199-204`）。做成：合成 9:16 卡片（立绘 + 台词 + 舰名 + 二维码）→ `FileProvider` + `ACTION_SEND`。水印相机的合成体系（`WatermarkComposer.kt` 619 行）可直接复用。

**C6 年度回顾（Recap）**：本地统计 12 个月播放/收藏/答题/看图数据 → 生成 5 页可分享长图（模板化，无需后端）。传播性极强，成本 S/M。

### 4.5 D｜桌面与常驻：把 App 变成「一直在」的东西

**D1 Live2D 动态壁纸**（触达最强，成本中高）

- *方案*：`Live2DWallpaperService : WallpaperService` + 复用现有 pixi/Live2D 渲染（WebView 在壁纸里不可用，需改走原生 GL 渲染路径，或退化方案：**序列帧/静态分层视差**）。工程上分两档：
  - **低成本档（M）**：静态立绘 + 陀螺仪视差 + 定时眨眼粒子（用 `SpineSdView` 的 GL 栈渲染 SD 小人，风险最低）；
  - **完整档（L）**：Cubism 原生渲染或 WebView 壁纸（后者在部分 ROM 上受限，需真机矩阵验证）。
- *风险*：耗电与内存（壁纸常驻），必须提供「帧率/仅充电时动画」开关——`ChibiFrameRate.kt` 已有帧率分档基础设施。

**D2 组件矩阵**：现有 3 个（今日推荐 2×2 / 秘书舰问候 4×2 / 生日 4×2）。补：①「语音播放器」4×1（上/下一句 + 播放，Media3 命令走 `MediaController`）；②「语音日签」2×2（今日一句 + 一键换）；③「资源导入进度」（长任务可见化）；④ 日历 4×4。Glance 的 `ActionCallback` 体系已成熟（`WidgetActions.kt`）。

**D3 通知中心**：把「生日/纪念日/每日一句/AI 主动消息/更新」统一到一个通知组，用户可逐项开关。现在只有播放与悬浮窗两个 channel，没有用户可见的通知设置页。

### 4.6 E｜AI 啾信（采用专项审计结论，按性价比重排）

| 序 | 项 | 落点 | 量 | 说明 |
|---|---|---|---|---|
| E1 | **SSE 流式 + 可中断** | `JiuxinApiRepository.kt:195`（响应已能解析 `delta`） | S | 返回 `Flow<String>`，UI 增量渲染，`isLoading` 语义改为「生成中」 |
| E2 | 供应商降级链 + 成本统计 | `JiuxinApiRepository.kt:300`（`isRetryable` 已分类 13 类错误）；响应含 `usage` | S | 多候选轮询 + 本地累计 token/费用，设置页展示 |
| E3 | 记忆条目化 | `PlayerSettingsDataStore.kt:184`、`ConfigSections.kt:664` | S | 现在是整段文本，改成条目列表（可单删、可编辑、可预览生效） |
| E4 | **RAG 事实源** | `ShipDao` + A3 数据 | M | 检索舰船档案/台词/技能 → 注入 system 段，治幻觉；这是「更像那个舰娘」的质变项 |
| E5 | STT 语音输入 | `ChatInputBar.kt:164` | M | `RECORD_AUDIO` + 系统识别或云端；先做「长按说话」单轮 |
| E6 | 立绘识舰（多模态） | `StickerMatcher` / ship 库 | M | 图片消息体 `image_url`；非兼容端点会 400，需能力探测 |
| E7 | 群聊增强 | `JiuxinViewModel.kt:2205` | S | 关系矩阵（谁先开口/谁插话）+ 话题接力，注意 token 成本 |
| E8 | 人格市场 / 导入导出 | `JiuxinModels.kt:348`（`@Serializable`） | S | 分享 JSON（**导出时必须剥离 apiKey**）+ 内置若干官方人格 |
| E9 | 主动消息 | 需先补 WorkManager | M | 结合 B3 好感度决定频率上限；默认关闭，白名单时段 |

**结构性建议**：`JiuxinViewModel` 3,000 行必须拆——按「PromptBuilder / ContextWindow / MemoryService / VoiceTagService / SessionStore / ApiClient」六块抽出，`JiuxinVoiceTagEngine` 已是成功先例（抽出来后立刻可单测）。同时把会话消息从 DataStore JSON 迁到 Room（`messages` 表），顺带解决 P1-4 的写放大。

### 4.7 F｜资源管线延伸（采用专项审计结论）

**关键判断**：管线的瓶颈不在解析能力，而在 `GameAssetKind` 枚举被 10+ 处 exhaustive `when` 复制——**新增一类资源要动 12 个文件**。所以先做 F6（注册表重构），再批量上内容。

| 序 | 项 | 复用 | 新增 | 量 |
|---|---|---|---|---|
| F6 | **kind 注册表**（bundleDir/后缀规则/扫描谓词/提取器/渲染器/导出目录/管线版本） | 现有 5 类作为首批条目 | `AssetRenderer` 接口 + `AssetKindRegistry` | M |
| F1 | **Live2D 表情/姿态库** | `AlLive2dExtractor.kt:156-168` 已枚举 TextAsset；`Live2dViewerScreen.kt:963-987` 表情面板已存在 | `buildModel3` 写 `Expressions`/`Pose` 段 + 管线版本 +1 | **S** |
| F3 | CG/过场批量相册 | 全链路已通 + 导出已通 | 批量全导 + 相册页（按舰/CG 标签） | **S** |
| F2 | 图标图集（舰船/装备/技能） | `UnityTextureExtractor` + `CLASS_SPRITE` 反查 | Sprite 级裁剪（`m_Rect`/`m_AtlasRect`）+ 放宽 128 KB 下限 | M |
| F4 | 立绘差分（大破/表情脸） | `PaintingComposer` 图层合成 | 变体白名单 + 成对展示 | M |
| F5 | **音频（语音包 / BGM）** | 复制 + 索引 + 现有播放器 | 裸 ogg/wav：直接拷（M）；wem/bnk/Fade：需外部解码器（L） | M/L |

F5 的判据是硬的：**先跑一次现成的 `listFilesPage` 探测 `files/` 下音频目录结构**，再决定做哪一档。若音频是裸 ogg，这就是「C3 BGM 播放器」的全部前置条件。

**性能三修（与 F 同批）**：EGL 上下文单例（`CompressedTextureDecoder` 里 6 份上下文生命周期合并）、落盘改 WebP 无损或「raw + 按需编码」、ASTC 软解按块行并行（IO 池）。

### 4.8 G｜平台化：BLYY 已经偷偷是多游戏 App

**事实**：`gallery` 里第二个档案 `STUDENT` 抓的是 **gamekee 的蔚蓝档案学生页**（`ShipRepository.kt:76-78`，14 维筛选 `StudentFilterData.kt`），语音/立绘/画廊各有一套解析。也就是说「多 IP」的地基已经在跑了，只是被硬编码在 `ShipRepository` 里。

- **G1**：抽 `GameProfile(id, name, listSource, voiceParser, galleryParser, filterSchema, themeAccent)`，`ArchiveType` 升级为 `profileId`；`ships` 表加 `profileId` 列（v11 迁移，`archiveType` 保留兼容）。
- **G2**：新 IP 接入模板（明日方舟/NIKKE/鸣潮…）——每接入一个，`util` 侧只写一个 parser，其余复用。
- **G3**：跨 IP 每日推荐/联动（"今天推荐：企业 & 白子"），组件与后宅都能吃这份数据。

*量*：L，但它是把「一个粉丝 App」变成「一个二游终端」的分水岭。

### 4.9 H｜基础设施（不做这些，上面全是沙上建塔）

| 序 | 项 | 做法 | 量 |
|---|---|---|---|
| H0 | **调度底座** | `app` 模块补 `androidx.work:work-runtime-ktx`（toml 已声明版本但从未引用）+ 统一 `NotificationChannels`（提醒/每日一句/主动消息/更新各一档）+ 通知总开关页 | S |
| H1 | 崩溃 + 埋点 | 自托管 GlitchTip/Sentry **或** 极简版：`UncaughtExceptionHandler` + 环形日志文件 + 设置页「导出诊断包」；埋点只做关键漏斗（启动/抓取/导入/AI 首字延迟） | S |
| H2 | 备份/恢复 + 云同步 | `BackupBundle`（JSON + zip：收藏/誓约/设置/人格/记忆/组件配置）→ ①本地导出（SAF）②WebDAV/Gist 手动同步 ③每 7 天自动备份到 App 私有目录并保留 3 份 | M |
| H3 | i18n | 先把「新增代码」强制 `stringResource`，存量按模块分批搬（4189 处，别一次性改）；语言：日/英优先；配 `values-ja`/`values-en` + 远程文案覆盖 | L |
| H4 | 模块化 | `:core:design` / `:core:common` / `:data` / `:feature:gallery` / `:feature:voice` / `:feature:asset` / `:feature:ai`；先拆出 `:util:unity`（纯 JVM 可测）与 `:feature:asset`（体积最大、依赖最脏） | L |
| H5 | 性能 | Baseline Profile（启动 + 船坞滚动）、EGL 单例、WebP、APK 走 AAB/ABI split、`ktx`/`Room` 查询加索引审计 | M |
| H6 | 排行榜整改 | 见 P0-1/2：**删除客户端 PAT**，改用无凭证的「提交到自建轻后端」（Cloudflare Workers / Vercel Function + KV），服务端校验 `score ≤ 题数×10` 与 `accuracy ∈ [0,1]`，并用设备指纹做频率限制 | M |

---

## 5. 反向清单：这几件事**不要**做

1. **不要为了「抓得快」拆掉 `NetworkHelper` 的 500ms 闸门。** 它是这个项目对上游站点唯一的技术礼貌，也是避免被封禁的唯一保险。要提速请做「并发 + 缓存 + 断点续跑 + 夜间批处理」，不是去掉节流。
2. **不要继续往 DataStore 存大 JSON。** 78 个 key 里已经有无上限动态 key（`ai_session_msgs_<id>`），再叠下去会撞 Preferences 的单文件读写放大与损坏风险。新数据一律 Room。
3. **不要再给 `JiuxinViewModel` 加功能。** 先拆后加，否则每一次改动都是 3,000 行里的盲改。
4. **不要引入第二套图表/网络/图片库**（例如为了 A1 加 Retrofit + 又加 Ktor）。四套网络栈已经够多，收敛比新增更值钱。
5. **不要做需要常驻服务端的功能**（实时对战、云端同步写、社区 UGC），除非确实有后端预算——排行榜已经用「客户端 PAT + 公共仓库」演示了这条路的下场。
6. **不要把「誓约」再做成一键布尔。** 关系要有时间轴，否则 B2/B3/B4 全落不下来。
7. **不要一次性铺满 5 个游戏。** 先把 STUDENT（BA）这条现有链路抽象干净，验证模板，再谈第三个 IP。

---

## 6. 路线图（4 阶段，约 3 个月）

### Phase 1｜止血与地基（2 周）

| 任务 | 依赖 | 验收 |
|---|---|---|
| H1 崩溃上报 + 诊断包导出 | — | 人为制造崩溃后可导出含堆栈/设备/最近 200 条日志的 zip |
| H6 排行榜去 PAT（先切只读 + 下线上传入口） | — | APK 内 grep 不到 `ghp_`/`github_pat_`；榜单仍可读 |
| P0-3 Key 存储加固（EncryptedFile / Keystore） | — | `adb shell run-as ... cat` 看不到明文 Key |
| P0-6 `versionCode` 接管（`versionCode = 3000100` 之类语义化） | — | CI 产物 versionCode 单调递增 |
| A3 数据落库第一步：`ships` 加列 + 预置 `ships.db` | — | 飞行模式首启船坞/日历/语音索引可用 |
| B1 誓约解耦 | A3 | 收藏与誓约可交叉组合 |

### Phase 2｜体验质变（4 周）

| 任务 | 依赖 | 验收 |
|---|---|---|
| E1 流式对话 + E2 降级链 + 成本统计 | — | 首字延迟 <1s；故意填错 key 能自动降级；设置页显示本月 token/费用 |
| C1 播放器产品化（睡眠/倍速/持久队列/焦点） | — | 杀进程后恢复「第 3 首 01:23」；定时结束后淡出 |
| C5 台词卡分享 + C6 Recap | — | 生成 9:16 图并调起系统分享 |
| F1 表情库 + F3 CG 相册 + F6 注册表 | 管线 | 表情面板可切换 ≥5 个动作；CG 可批量导出 |
| B5 每日一句 + D3 通知中心 | H0(WorkManager) | 关闭开关后 24h 内零通知 |
| A4 搜索增强 | A3 | 拼音/首字母/CV 三路命中 |

### Phase 3｜能力扩张（6–8 周）

| 任务 | 依赖 | 验收 |
|---|---|---|
| A1 舰娘详情页 + A2 装备/配装 | A3, F2 | 20 舰数据抽检一致；断网可用 |
| C3 BGM 播放器 + C2 本地语音库 | F5 | 可离线播放游戏 BGM 与语音包 |
| D1 Live2D 壁纸（先低成本档） | H5 | 壁纸可切换舰娘、30fps 下 1h 耗电 <3% |
| E4 RAG + E3 记忆条目化 | A3 | 问「企业的主炮是什么」不再编造；记忆可单删 |
| H2 备份/恢复 + WebDAV | A3 | 导出→清数据→导入，收藏/誓约/人格/记忆 100% 还原 |
| H5 性能（Baseline Profile / EGL 单例 / WebP） | — | 冷启动 -25%；导入 4096² 耗时 -40% |

### Phase 4｜平台化（持续）

G1 多游戏抽象 → G2 第二个 IP 模板 → H3 i18n → H4 模块化 → E9 主动消息 / E5 STT / D5 车机与 Wear。

**里程碑判据**：Phase 2 结束应达到「断网可用 + 崩溃可查 + 有传播物」；Phase 3 结束应达到「站内信息闭环（不用跳 wiki）+ 常驻触达成立」；Phase 4 才有资格谈「多 IP」。

---

## 7. 优先级矩阵

```
价值
 高 │ A3 B1 C1 C5 E1 E4 F1 F3 H1 H2 B5     ← 先做（多数 ≤ M）
    │ A1 A2 C3 D1 E5 F5 F2 B2 B3 G1
 低 │ E10 D5 H3 H4 A6 D4                    ← 后置或砍
    └──────────────────────────────────────
        低            成本            高
```

**取舍原则**：本轮不再新增「第 41 个页面」，所有新功能必须挂在 A3（数据）、B1（关系）、C1（播放）、F6（管线）这四条主干上——挂不上的，说明它只是装饰。

---

## 8. 风险与边界

| 风险 | 说明 | 缓解 |
|---|---|---|
| **上游结构变更** | wiki CSS / gamekee SPA / B站小程序接口三处都改过版 | 选择器集中到一处 + 三条黄金样本 HTML 的单测 + 「解析失败」显式上报（不要静默返回空） |
| **资源版权** | 立绘/语音/BGM 均为游戏资产 | 维持现状口径（不内置游戏资源、只解析用户自有数据、导出到本地）；F5 音频尤其注意**不要做分发** |
| **Shizuku 依赖** | 依赖 ADB 身份读私有目录，非 root 用户门槛不低 | 保留「手动选 `.ys` 文件」通道（已存在）；考虑补 SAF 目录授权通道 |
| **接口合规** | 抓取需保持节流与 UA 轮换 | 见 §5.1，把节流当作特性而非障碍 |
| **隐私** | 游戏 UID / 服务器 / API Key / AI 对话内容 | H2 备份默认加密；诊断包默认脱敏；隐私政策列出 UID 用于排行榜 |
| **包体积** | assets 22.9 MB + 四 ABI | F5/CG 相册上线后考虑「资源包按需下载」（Play Asset Delivery 或首次进入时拉取） |
| **单人维护** | 195 commits / 5.5 月，god class 已出现 | H4 模块化 + 把 `:util:unity` 做成纯 JVM 模块（可测、可复用、可开源单独发） |

---

## 9. 附录：本文关键证据索引

| 结论 | 位置 |
|---|---|
| Room 仅 2 表、v9 | `data/local/AppDatabase.kt:20` |
| DataStore 78 key + 动态 key | `data/local/PlayerSettingsDataStore.kt`（全文） |
| 誓约 = isFavorite | `ui/components/ShipCard.kt:151`、`ui/screens/GalleryScreen.kt:378` |
| 名称搜索仅 contains | `viewmodel/GalleryViewModel.kt:134` |
| 分享仅复制链接 | `ui/screens/VoiceScreen.kt:199-204` |
| wiki 跳外链 | `ui/screens/GalleryScreen.kt:288`、`HomeScreen.kt:143` |
| 学生档案 = gamekee BA | `data/repository/ShipRepository.kt:76-78`、`data/model/StudentFilterData.kt` |
| 全局 500ms 限流 | `util/NetworkHelper.kt:29-46` |
| B站接口 | `data/repository/AssistantRepository.kt:34` |
| 排行榜内嵌 PAT | `data/repository/LeaderboardRepository.kt:57-58`、`LeaderboardCrypto.kt:29-100` |
| AI 非流式 | `data/repository/JiuxinApiRepository.kt:312-329` |
| AI 上下文按条数裁剪 | `viewmodel/JiuxinViewModel.kt:3017` |
| AI 全量 JSON 重写 | `viewmodel/JiuxinViewModel.kt:2917,2981` |
| EGL 每贴图重建 | `util/unity/CompressedTextureDecoder.kt:307-342,367-388,566+`（共 6 处） |
| PNG q100 落盘 | `util/unity/UnityTextureExtractor.kt:94-97` |
| kind 扩散 12 处 | `GameAssetKind.kt` / `GameBundleImporter.kt:148` / `GameAssetLibrary.kt:112,154,262` 等 |
| Live2D 表情面板已就绪 | `ui/screens/live2d/Live2dViewerScreen.kt:963-987` vs `util/unity/AlLive2dExtractor.kt:156-168` |
| 组件体系 | `widget/WidgetActions.kt`、`WidgetData.kt:255-272` |
| 深链桥 | `MainActivity.kt:73-80`、`ui/AppRoot.kt:494-496` |
| 16 KB 对齐（已达标） | `app/build/generated/gdxNatives/*/libgdx.so` LOAD align = 0x4000 |
| versionCode 恒为 1 | `app/build.gradle.kts:31` |
| WorkManager 仅声明未使用 | `gradle/libs.versions.toml` vs 0 个 `androidx.work` import |
