# 游戏资源导入（独立功能 · 技术说明）

> 目标：把碧蓝航线 `AssetBundles/` 下的四类资源包（Live2D / SD 小人 / 动态皮肤 / 静态立绘）
> 在手机上直接解析成**可渲染**的本地资源，并提供独立的扫描、导入、库管理与查看界面。
>
> 本文记录实现结构、格式结论、边界与已知限制。Live2D 的逆向细节见
> [LIVE2D_BUNDLE_IMPORT.md](LIVE2D_BUNDLE_IMPORT.md)。

---

## 1. 为什么要独立成模块

原先「游戏资源包导入」是 Live2D 皮肤库里的一个子页面（`live2d/bundles`），
扫描器、索引、UI 都与 Live2D 强耦合，只能处理 `live2d/` 目录、只能产出 Cubism 模型。
新增 SD 小人 / 动态皮肤 / 静态立绘后，这套耦合会带来三个问题：

1. **状态互相污染**：三类资源的「已导入」状态与 Live2D 共用一份索引，判断条件不同；
2. **入口歧义**：用户从「Live2D 皮肤库」进入后，看到的却是四类资源的混合列表；
3. **产物目录混放**：非 Cubism 产物塞进 Live2D 模型库会让皮肤库扫描报「无法识别」。

因此把导入链路整体抽到独立功能（`util/gameimport` + `ui/screens/gameimport`）：

- Live2D 皮肤库**只保留**本地文件夹 / zip 导入（`Live2dImporter`）；
- 游戏资源包（四类）统一走新模块，从侧栏「游戏资源导入」进入；
- 皮肤库里的「从游戏资源包导入」入口只是**跳转**到新模块并预选 Live2D 类型。

---

## 2. 资源形态与判定

| 类型 | 游戏目录 | 包内结构 | 产物 | 渲染 |
|------|----------|----------|------|------|
| [LIVE2D] | `live2d/` | MonoBehaviour 内嵌 moc3 + 贴图 + Mecanim 动作 | `<id>.model3.json` / `model.moc3` / `textures/*.png` / `motions/*` | pixi-live2d-display（WebView） |
| [SD_CHAR] | `char/`（含 `metaship/`） | `*.skel` + `*.atlas` TextAsset + 贴图 | `<name>.skel` / `<name>.atlas` / `<page>.png` / `spine.json` | libgdx Spine 3.8 |
| [DYNAMIC_SKIN] | `spinepainting/` 的 `*_res` | 同上（骨骼包在 `_res` 内） | 同上（多骨骼时同一目录多套） | libgdx Spine 3.8 |
| [PAINTING] | `painting/` 的 `*_tex`（+ 同名 prefab） | **碎片图集** + 拼装 Mesh；prefab 里是 MeshImage 图层树 | `painting.png` / `painting.json` | Compose 图片 + 双指缩放 |
| [LOADING_BG] | `loadingbg/`（+ `loadingbg_hx/`） | 1~2 张 1920×1080 贴图（同名多尺寸） | 同上（RAW 模式） | Compose 图片 + 导出 |

### 判定顺序（以包内容为准，路径只作兜底）

1. MonoBehaviour 里出现 `MOC3` 魔数 → **Live2D**
2. 有 `*.skel` TextAsset（配 `*.atlas`）→ **Spine**（类型取包内 `AssetBundle.m_Name` 前缀，
   如 `char/xxx` / `spinepainting/xxx`）
3. 有 ≥512×512 的 Texture2D → **静态立绘**（`_tex` 包内的网格/图层信息决定能否还原成整图）
4. 其余（GameObject / CanvasRenderer / 材质）→ **预制体空壳**，提示用户改选同名的
   `_tex` / `_res` 包

扫描阶段按目录 + 后缀先做粗筛，避免把几百个预制体都列出来：

- `painting` 只列 `_tex`
- `spinepainting` 只列 `_res`
- `char` / `live2d` 目录本身即资源
- 统一跳过 `<128KB`、`shader` / `dependencies` / `manifest` / `hashes` 等文件

---

## 3. 提取链路

```
游戏私有目录 .ys/.unity3d
   │ Shizuku: listFilesPage（分页）/ copyTo（唯一导入来源，避免 SAF 目录树带来的半成品与权限问题）
   ▼
cacheDir/game_bundles/<id>_<size>.src
   │ GameBundleImporter.importLocal
   ├── UnityBundleSession.open → BundleProbe.probe          （一次解压，镜像复用）
   ├── 分流：
   │     Live2D  → AlLive2dExtractor.extract
   │     Spine   → SpineBundleExtractor.extract
   │     Painting→ PaintingBundleExtractor.extract（+ 同名 prefab / _rw 图层 / UI 图集）
   ├── 产物写入 <base>/.game_assets_staging/<kind>/<id>/
   └── rename 落库到 <externalFilesDir>/game_assets/<kind>/<id>/
   ▼
索引 filesDir/game_import_index.json（记录管線版本 / 大小 / mtime）
```

关键点：

1. **一次解压**：`UnityBundleSession` 与 `AlLive2dExtractor` 共用同一份镜像缓存
   （`cacheDir/al_live2d/<name>-<len>-<mtime>.img`），探测完再提取不会重复解压 30MB+ 的包。
2. **原子落库**：暂存目录与资源根同分区，`rename` 即原子切换；失败时暂存整体删除，
   目标目录保持原样，不会留下打不开的空目录。
3. **索引防重复**：`pipelineVersion` 按类型比较，产物结构升级后旧记录自动判为「有更新」。
   首次启动会把旧 `live2d_bundle_index.json` 迁移过来，老用户不会掉「已导入」状态。
4. **id 分配**：同一来源重新导入复用原 id；id 被别的来源占用时追加路径哈希后缀，
   避免两个包互相覆盖。
5. **立绘要多复制几个文件**：扫描阶段按名字挑出 `painting/<名字>`（prefab）、
   图层包与共享 UI 图集（官方客户端叫 `ui/commonui_atlas`，部分客户端带 `.ys`），
   随主包一起复制到 `cacheDir/game_bundles/`
   （导入结束即删）。没有 prefab 就只能还原单层，没有 `_rw` 就只剩背景。
   图层包的命名是**变体夹在中间**的：`2b_hx` 的人物层叫 `2b_rw_hx_tex`，
   所以判定规则是「去掉变体标记后比较基础名 + 图层关键字」，并要求图层包的
   变体集合是主包变体集合的子集（见 `PaintingCompanionRules`）。
   判定必须**一次遍历建索引**：`painting` 有 1 万+ 文件、候选立绘 6000+，
   逐个候选扫全目录是 O(N²)（实测 6900 万次字符串解析，扫描卡 4 分钟）。

---

## 4. Spine 提取的三个坑（都会导致「导入成功但黑屏/撕裂」）

### 4.1 贴图必须按图集页名命名

`.atlas` 第一段是页文件名（`zhezhi_2.png`），`TextureAtlas` 按这个相对路径找图片。
落盘时必须以页名（含子目录）为准，不能沿用 Texture2D 的 `m_Name`。

### 4.2 多页图集

动态皮肤常见 3~4 页（`zhezhi_2` 3 页、`zhuzi_2_DOA` 4 页）。
每页单独解码落盘，贴图与页按「去扩展名、忽略大小写」匹配；页缺失或尺寸不符直接报错，
而不是静默产出黑块。

### 4.3 `rotate: 90/180/270`（Spine 4 写法）

实测 13 个测试包里有 35 个区域使用角度写法：

```
body_shadow2
  rotate: 270
  xy: 2005, 513
  size: 2202, 1501
```

libgdx 1.13 的 `TextureAtlas` 只认 `true/false`（`Boolean.parseBoolean("270") == false`），
会把它们当作**未旋转**，渲染成方向错误、边缘撕裂的图。导入时按几何等价关系归一化：

| 原值 | 改写为 | 像素处理 | 页上包围盒 |
|------|--------|----------|------------|
| 0 / `false` | `false` | 无 | `(w, h)` |
| 90 / `true` | `true` | 无 | `(h, w)` |
| 180 | `false` | 区域旋转 180° | `(w, h)` |
| 270 | `true` | 区域旋转 180° | `(h, w)` |

像素处理在 `IntArray` 阶段完成（`BitmapPixelOps.rotate180`），
避免「不可变 Bitmap 无法 setPixels」与「多占一张 4096² 位图」两个问题。

### 4.4 图集过大时同步缩放

贴图页最长边上限默认 4096（`SpineBundleExtractor.DEFAULT_MAX_PAGE_SIDE`）。
超过时**同时**缩放页尺寸与区域 `xy/size/orig/offset`，并让贴图按缩放后的页尺寸解码，
否则 UV 与像素对不上。

---

## 5. 静态立绘：把碎片图集还原成整图

这是 v3 管线重写的部分。原先的实现（v1/v2）把 `_tex` 包里的**图集本身**当成成品：
一张 1024×556 的横图里散落着几十块人物碎片、舰装、背景，用户看到的就是
「拼不起来、人物错位」——不是缩放问题，是**画面根本没拼**。

### 5.1 游戏是怎么画的

| 文件 | 内容 | 作用 |
|------|------|------|
| `painting/<名字>_tex` | 1 张 Texture2D + 1 个 `<名字>-mesh` + Sprite | 图层贴图 + **拼装网格**（顶点 = 画面坐标，UV = 图集坐标） |
| `painting/<名字>`（无后缀） | MeshImage 图层树 + RectTransform | 决定各图层（背景/人物/舰装/前景）**摆在哪里、多大** |
| `ui/commonui_atlas` | 共享 UI 图集 | 少数立绘的装饰层 |

游戏运行时：每个 `MeshImage` 用 `mMesh` 把图集拼成一张图，再按 RectTransform
（锚点/尺寸/轴心/位置/缩放）叠到画布上。**离线还原 = 这两步都做**。

### 5.2 三级还原策略（`PaintingComposer`）

| 模式 | 触发条件 | 结果 |
|------|----------|------|
| `layout` | 有 prefab（导入时随主包一起复制） | 逐图层还原 + 按布局叠放，与游戏内一致 |
| `mesh` | 只有 `_tex` 包 | 用包内网格把碎片拼成整图（经典立绘只有一层） |
| `raw` | 既无 prefab 也无网格 | 贴图本身就是成品（老资源），原样导出 |

侧车 `painting.json` 记录 `mode / layers / skippedLayers / atlas`，查看器与卡片
据此显示「N 层合成 / 网格还原」，不再是含糊的「分层素材」。

### 5.3 五个坑（都会导致「拼不起来 / 错位」）

1. **图集不是成品**：必须按网格三角形从图集采样；直接展示 PNG 必然是碎片拼贴。
2. **方向**：网格 y 向上、位图行号向下；UV 的 v 也向上。顶点按 `py = maxY - y`、
   采样行按 `(1 - v) * texHeight` 映射，翻错一处就是整图镜像。
3. **UV 越界串色**：图集碎片紧挨着打包，双线性采样跨出碎片边界会吸到隔壁碎片的
   颜色（人物边缘出现彩色描边）。采样坐标要**夹紧到三角形自身的 UV 包围盒**，
   这样尺度 1:1 时采样点正好落在纹素中心，既不模糊也不串色。
4. **浮点被截断**：RectTransform 的锚点/轴心全是 `0..1` 的小数，
   类型树读取器如果走 `toLong()` 就会把 `0.5` 读成 `0`——布局全零，图层挤到原点。
   `UnityValues.float()` 专供这类字段（`0.5→0` 这个坑实测让整张立绘错位到左上角）。
5. **prefab 根节点的缩放不参与布局**：碧蓝的立绘根节点普遍带 `0.53` 的缩放
   （用于适配屏幕），按常规矩阵乘进去会让所有图层整体缩掉一半。ALPA 的
   `ExtendedTransform` 明确忽略根节点自身缩放，本实现照搬。

### 5.4 内存与尺寸

- 图集按 `maxSide`（默认 4096）解码，取完像素立即 `recycle()`，峰值只有
  「一张图集 + 光栅化结果」；
- 输出画布 = 主图层声明的贴图尺寸（`mRawSpriteSize`，`2b_2` 即 2048×1206），
  与布局换算差 ≤2px 时吸附，避免整层被重采样；
- 只有真正需要缩放的图层（`_rw` 人物层 1730→770）才走 `drawBitmap(src→dstRect)`。

## 5.5 过场图（loadingbg）与资源导出

### 5.5.1 过场图

`AssetBundles/loadingbg/` 下是加载/过场插画，结构与立绘完全不同：**没有网格、没有 prefab**，
每个包只有 1~2 张同名贴图（`bg_1` 的 1920×1080 与 1024×576 都叫 `bg_1`），和谐版单独放在
`loadingbg_hx/`。

因此过场图直接复用立绘提取器的 **RAW 模式**：

- 扫描：`GameAssetKind.LOADING_BG` 声明 `siblingDirs = ["loadingbg_hx"]`，四类目录并发列举时
  把兄弟目录一起列，资源 id 带目录后缀（`bg_1` / `bg_1_hx`）避免撞名；
- 选图：同名多尺寸时**取面积最大的那份**（旧策略取「第一个匹配」，会随机拿到 1024 预览图）；
- 产物：`painting.png` + `painting.json`（`mode=raw`），查看器与立绘共用同一套 UI。

### 5.5.2 导出

查看器顶栏的下载按钮把当前图片写到用户可见目录（与语音/相册的下载共用 `Downloads/BLYY/`）：

```
Downloads/BLYY/Painting/<显示名>_<资源id>.png     静态立绘
Downloads/BLYY/LoadingBg/<显示名>_<资源id>.png    过场图
```

- 文件名同时带**可读名**与**稳定 id**：`2B · 须臾的休憩_2b_2.png`，
  显示名方便人找，id 保证不同皮肤不重名；
- 非法字符（`/ \ : * ? " < > |`）替换成 `_`，超长截断到 80 字符；
- API 29+ 走 MediaStore（免存储权限），同名由系统追加 ` (1)`。

### 5.5.3 设备能力：ASTC 6x6 的坑

过场图与 **SD 小人**的贴图都是 **ASTC 6x6**（Unity `TextureFormat=50`），而部分 GPU
（模拟器、低端机）只实现 ASTC 4x4：`glCompressedTexImage2D` 直接返回 `GL_INVALID_VALUE`，
表现为「导入成功但解码失败 / 黑屏」。

- `CompressedTextureDecoder.supportedAstcBlockSizes()` 用 1x1 离屏上下文对每种块尺寸
  上传**一个块**探测，结果缓存；
- 应用在「游戏资源导入」顶部给出明确提示（当前设备只支持 ASTC 4x4），
  解码失败时错误文案也改成 `不支持 ASTC 6x6（1920x1080，GL 错误 0x501）`；
- 真机（Adreno / Mali）普遍支持全部块尺寸，无需处理；
- 后续可选：内置 CPU ASTC 解码兜底（本轮未做，见「已知限制」）。

### 5.6 游戏导入的 SD 小人如何进入秘书舰

`game_assets/char/<id>/` 里解出来的小人（`<name>.skel` + `.atlas` + 页 PNG + `spine.json`）
和秘书舰原先的 SD 资源库（`blhx_sd/`）是两套目录。打通方式不是复制文件，而是**让资源库多扫一个根**：

| 环节 | 做法 |
|------|------|
| 索引 | `SDResourceManager.buildIndex()` 额外扫描 `game_assets/char/*`，资源 id 加 `game/` 前缀（`game/boge`），来源标记 `GAME_IMPORT` |
| 皮肤 | 同一目录多套骨骼按 `.skel` 名后缀区分（`boge` → 默认、`boge_g` → 改造…），与 `blhx_sd` 一致 |
| 预览 | 优先用 `spine.json` 里主骨骼的第一页，其次同名 PNG（避免拿到别的皮肤的页） |
| 舰名 | `Live2dNameResolver` 启动时注册 `shipNameProvider`，把拼音目录名反查成中文舰名，秘书舰据此联动语音/立绘 |
| 按舰名回退 | `blhx_sd` 里没有该舰娘时，自动回退到游戏导入的小人（先比中文名，再比拼音前缀） |
| 选择 | SD 资源管理页列出全部资源，游戏导入的标「游戏导入」徽章；**只读**（重命名/删除仍归游戏资源导入模块） |
| 刷新 | 每次导入 / 删除 / 重命名 SD 资源后调用 `SDResourceManager.onGameImportChanged()`，选择器立刻可见 |

顺带修掉一个断点：`SecretaryShipSettingsScreen` 早就接收了 `onOpenSdGallery` 回调，
但界面上从来没有渲染入口——用户在设置里根本进不去 SD 资源库（导入的小人自然也选不到）。
现在「SD 小人」分区里补了资源库卡片（管理 / 清除），入口与秘书舰的悬浮小人同屏。

## 6. 目录布局

```
<externalFilesDir>/
├── live2d/<id>/…                    # Live2D（沿用 Live2dLibrary，未迁移）
├── game_assets/
│   ├── char/<id>/                   # SD 小人：<name>.skel/.atlas + 页 PNG + spine.json
│   ├── spinepainting/<id>/          # 动态皮肤：同上（多骨骼时为多套）
│   └── painting/<id>/               # 静态立绘：painting.png + painting.json
└── .game_assets_staging/<kind>/<id>/ # 解包暂存（不在资源根下，不会被扫描）
```

侧车文件：

- `spine.json`：`{ kind, primary, skeletons[{name, version, pages}], textureSize, source, createdAt }`
- `painting.json`：`{ file, width, height, unityFormat, source, createdAt }`

侧车缺失时（如手工拷贝目录）按目录内容推断：骨架名取 `*.skel` 文件名、版本读二进制包头、
页数取 `*.png` 数量。

Spine 二进制版本读取注意：字符串长度是 **`len + 1`** 编码（`0` = null、`1` = 空串），
少减 1 会把紧跟 hash 的 `0x07` 当成长度 7 的字符串，导致版本号乱码。

---

## 7. 界面

`GameImportScreen`（侧栏「游戏资源导入」）：

- **资源包**分区：Shizuku 状态卡 → 类型筛选（全部/Live2D/SD/动态/立绘，带计数）→
  全部导入（**唯一入口**：按 Shizuku 扫描结果批量导入，不再提供 SAF 选文件/选文件夹）→
  进度卡（可停止）→ 搜索 → 资源包卡片（状态：待导入 / 已导入 / 有更新 / 失败，
  失败直接展示原因）
- **已导入**分区：类型筛选 → 两列网格（立绘显示原图、Spine 显示头像兜底/类型占位）→
  长按详情（骨骼/页数/版本/占用/来源）→ 打开 / 重命名 / 删除

`GameAssetViewerScreen`：

- Spine：`SpineSdView`（GLES2 + libgdx 3.8）全屏渲染；**单指拖动平移、双指缩放、
  双击复位**，顶栏另有「适应屏幕」；不再有动作列表/倍速条等悬浮控制层
- 立绘 / 过场图：`ZoomableImage` 双指缩放；顶栏下载按钮导出到 `Downloads/BLYY/`；
  立绘副标题注明还原方式（「N 层合成」/「网格还原」）
  与成因——立绘不是一张图，而是图集 + 网格 + 图层布局拼出来的
- Live2D 资源不经过这里（跳转原 Live2D 查看器，陪伴模式等能力保持不变）
- 退出时主动 `onPause` + `visibility = GONE`：`ZOrderOnTop` 的 SurfaceView 若只靠
  Compose 移除，会晚一步销毁导致上一屏残留模型

导航要点：两个新路由（`gameimport`、`gameimport/view/{kind}/{assetId}`）已加入
「全屏页隐藏底部导航 / 关闭抽屉手势 / 遮蔽悬浮秘书舰」三处判断，与 Live2D 查看器一致。

---

## 8. 测试

`resource/test/` 提供四类真实资源包（Live2D 6 个、char 4 个、painting 11 个、spinepainting 10 个）。
无设备时可在桌面用 `.work_tmp` 下的 Python 校验脚本对 `resource/test` 逐包核对（页↔贴图匹配、区域越界、旋转区域数量）。

自动化覆盖（JVM）：

| 测试 | 覆盖 |
|------|------|
| `GameAssetNamingTest` | 后缀剥离（`_tex`/`_res`）、变体识别、id 归一、路径→类型 |
| `SpineAtlasParserTest` | 多页解析、区域名带空格、缩放改写坐标、`rotate` 归一化与包围盒；含**真实图集**（`zhezhi_2.atlas`：3 页 103 区域 8 个角度旋转）断言 |
| `SpineBinaryHeaderTest` | `len+1` 字符串编码、版本识别、损坏数据兜底 |
| `CompressedTextureFormatTest` | ASTC 4x4/5x5/6x6/8x8/10x10/12x12、ETC2/ETC1、RGB/RGBA 变体 |
| `GameBundleProbeTest` | 六个真实包逐一断言：类型、骨骼/图集名、Spine 版本、贴图格式与尺寸 |
| `GameBundleSweepTest` | `resource/test` 全量 29 个包分类扫描（6 Live2D / 4 char / 10 painting / 9 spinepainting） |
| `MeshRasterizerTest` | 1:1 逐像素、放大四角对齐、UV 夹紧不串色、透明区、重叠合成、退化网格 |
| `PaintingLayoutTest` | 真实 prefab（`2b_2`）：根节点/画布/图层尺寸/落点（人物层 770×911 @ (666,253)）|
| `PaintingMeshRealDataTest` | 4 个真实包 + 真实图集：网格还原的画布尺寸、覆盖率，并与独立实现逐像素比对 |

格式侧的结论用 `resource/test` 的真实包逐一核对过（页面尺寸、页↔贴图匹配、
区域是否落在页内、版本号）：29 个包全部分类正确，8 个 Spine 包的页/贴图/包围盒校验全部通过。

---

## 9. 已知限制

- 仅支持 **ASTC / ETC2 / ETC1 / RGBA32** 贴图；DXT/PVRTC 等桌面/老机型格式会给出明确错误。
- Spine 运行时固定 **3.8**：`3.7/3.8` 可直接加载；4.x 骨骼能提取但运行时会报版本不匹配
  （导入时会记录版本并在详情页展示）。
- 图集页默认**保持原分辨率（4096）**：降采样会让线性过滤把相邻区域像素带进来，
  出现「边缘发白、部件重影」。为容纳 4096² 位图（约 64MB），manifest 开启
  `android:largeHeap`；仅 `memoryClass < 128MB` 的极端设备才降到 2048。
- 导入来源只有 Shizuku（游戏目录）；不再提供 SAF 选文件 / 选文件夹。
- 动态皮肤的 `*_hx` 等变体作为**独立骨骼**提取，同一目录多套，查看器可切换。
- 静态立绘的**差分表情**（`paintingface/<名字>`）尚未合成：产物是无表情底图。
  表情是独立 Sprite 组，需要额外复制该目录下的包，之后再叠到 prefab 的 `face` 槽。
- 装饰层只有复制到 `ui/commonui_atlas` 时才会参与合成；该包缺失时图层被跳过
  （界面仍正常，只是少了商店贴纸/角标一类的小装饰）。
- `_rw`（人物层）/`_bjN`（背景条）仍是**图层**而不是独立立绘：扫描时只列主包，
  但它们会作为附属包参与合成。
- 设备 GPU 只支持 ASTC 4x4 时，过场图与 SD 小人（ASTC 6x6）无法解码：
  应用内会显式提示，不是静默失败。要在这类设备上显示，需要补一个 CPU ASTC 解码器。

---

## 10. 真机 / 模拟器联调记录（含已修问题）

在 Android 模拟器（x86_64, API 35, 720×1280，Adreno 740 字符串 + ASTC 4x4 可用）接入真实游戏资源包
（`live2d 271 / char 1401 / spinepainting 219 / painting 6218`）逐项验证，暴露出四个问题：

| # | 现象 | 根因 | 修复 |
|---|------|------|------|
| 1 | 扫描结果里「立绘 0」，其它三类正常 | `painting` 目录 1.2 万+ 文件，一次性 `listFiles` 返回约 1.3MB，**超过 Binder 单次回复上限** → `TransactionTooLargeException`（表现为 `DeadObjectException`），shell 进程被打挂 | AIDL 增加 `listFilesPage`：服务端缓存目录快照，按页（600 条 / 64KB）返回；客户端循环取完。桥接层加 `isBinderAlive` 校验与一次重绑重试 |
| 2 | Spine 资源导入成功但查看器黑屏，日志 `EOFException at SkeletonBinary.readString` | TypeTree 把 TextAsset 的 `m_Script` 声明成 `string`，按 UTF-8 解码再编码把二进制 `.skel` 里的非 UTF-8 字节替换成 `U+FFFD`（实测 200KB 中 3.7 万个） | 新增 `UnityTextAssetReader`：按「对齐长度 + 原始字节」直接读，跳过文本解码；`.skel` 写出后校验版本号，解析不出 3.x 直接报错而不是产出损坏资源；Spine 管线版本 1 → 2 触发旧产物重解包 |
| 3 | 查看器底部「随机动作」按钮被裁掉一半 | 缩放条 `weight(1f)` + 右侧按钮在 720dp 宽下超宽 | 去掉冗余按钮（顶栏已有随机动作入口），缩放条独占剩余宽度，倍率文字右对齐 |
| 4 | `char`（SD 小人）导入失败：`GL 错误 0x501` | 该模拟器的 GLES 实现只支持 **ASTC 4x4**，`char` 包用的是 **ASTC 6x6**（Unity `TextureFormat=50`） | 保留 4x4/5x5/6x6…全量映射（真机 Adreno/Mali 支持），并补充「按块整数倍补齐尺寸」重试；仍失败时给出可读提示：当前 GPU 不支持该块尺寸、模拟器通常只有 4x4 |
| 5 | 动态皮肤局部错位/撕裂（`rotate:180/270` 的区域跑到别的位置） | `.atlas` 的 `xy` 是**左下原点**，而解码后的位图是自上而下；翻转矩形直接用了 `y`，等于把区域上下镜像到页的另一侧 | 归一化时换算成图片坐标 `topY = pageHeight - (y + boxH)`；同时把图集页上限放宽到「≥256MB 堆即 4096」，常见皮肤不再缩放，消除取整误差 |
| 6 | 退出查看器后模型残留在上一屏 | `setZOrderOnTop(true)` 的 SurfaceView 比 Compose 视图晚销毁 | `onDispose` 里先 `onPause()` 再 `visibility = GONE` |
| 7 | 只在 `com.bilibili.azurlane` 下找资源，渠道服/外服扫不到 | 包名写死 | 已知包名列表（国服/EN/JP/KR/TW）+ `Android/data/<包名>` 自动发现兜底 |
| 8 | 动态皮肤「部件边缘发白、重影、撕裂」 | 图集页被降采样到 3072/2048，图集区域之间的窄 padding 在缩放后被线性过滤串色 | 默认 4096 原分辨率 + `largeHeap`；仍保留「按边界反推宽高」的精确缩放实现给极端设备 |
| 9 | 离开查看器时模型残留在上一屏 | 见 #6 | 已实测退出后立即清理，无残留 |
| 10 | 进入「游戏资源导入」长时间卡在「正在扫描游戏资源目录…」（实测约 4 分钟） | 附属包判定写成了「每个候选扫一遍全目录」：6382 个候选 × 10881 个文件名 ≈ 6900 万次 `split/lowercase/joinToString`。列举本身只占 0.7s（服务端 `buildListing` 10 881 项 561ms） | ① 附属包改为**一次遍历**建 `基础名→文件` / `主包→图层` 两张索引，候选 O(1) 查表（374→162ms）；② 四类目录**并发列举**，服务端快照改成多槽 LRU（单槽会被彼此顶掉、每次重建整棵目录）；③ 每行只 `split` 一次，候选判定与文件名索引同一循环。实测 **1082ms**（列举 594ms + 附属包 162ms + 其余 320ms） |
| 11 | `2b_hx` 导入后只有背景、没有人物（`layers=1/4`，跳过 3 层） | 换装图层命名是**变体夹在中间**：`2b_rw_hx_tex`、`2b_bj1_hx_tex`，旧规则只认 `<主包>_` 前缀，全部漏掉 | 改为变体感知：拆名时把变体标记（`hx/n/doa/shophx/buildhx`）从任意位置摘出，图层包要求「基础名 = 主包基础名 + 图层关键字」且变体集合是主包子集；prefab 要求完全同名。实测同一资源从 `1 层（跳过 3）` 变为 **`4 层（跳过 0）`**，查看器显示「2048×1800 · 4 层合成」 |

已验证可用（模拟器实测）：

- 扫描：8109 个资源包全部分类正确（含 6218 个立绘）
- 导入：`live2d`（abeikelongbi_3，1.9s，14 组动作）、`spinepainting`（aerbien_4，2.1s，8 个旋转区域归一化 + 4096→2048 缩放）
- 渲染：动态皮肤完整显示（含背景层，贴图方向/UV 正确，动作可切换）；静态立绘 2048×1206 正常显示与缩放
- 入库：索引记录、已导入计数、卡片状态（待导入/已导入/失败原因）均正确

未能在该模拟器验证：`char` SD 小人的最终渲染（受 ASTC 6x6 限制）。
真机（Adreno/Mali 全块尺寸 ASTC）上该路径与动态皮肤共用同一套解码/渲染代码，只差纹理格式。
