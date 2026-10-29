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

### 5.5.3 设备能力：ASTC 6x6 与 CPU 软件解码兜底

过场图与 **SD 小人**的贴图都是 **ASTC 6x6**（Unity `TextureFormat=50`），而部分 GPU
（模拟器、低端机）只实现 ASTC 4x4：`glCompressedTexImage2D` 直接返回 `GL_INVALID_VALUE`，
旧版本表现为「导入成功但解码失败 / 黑屏」。

现在分成两步：能上硬件就上硬件，上不去就**在 CPU 上解**。

| 环节 | 实现 |
|------|------|
| 能力探测 | `CompressedTextureDecoder.supportedAstcBlockSizes()` 用 1x1 离屏上下文对每种块尺寸上传**一个块**，看 GL 是否报错；结果缓存 |
| 路由 | `decode()`：ASTC 且硬件不支持该块尺寸 → 直接走 `decodeAstcSoftware()`；硬件支持但 GL 抛异常（驱动报错 / 上下文被回收 / 后台线程拿不到 GLES3）→ **同样回退软件解码** |
| 软件解码 | `AstcDecoder`：纯 Kotlin，无 EGL / GL 依赖，覆盖 2D 全部块尺寸（4x4…12x12、8x5、10x6）、1~4 分区、双平面权重、16 种 CEM（含 HDR 7/11/14/15）、void-extent 与保留块 |
| 结果 | 与 GL 路径同样输出 `ARGB_8888`（自上而下），`maxSize` 降采样语义一致 |

**行序（最容易踩、也最难自查的坑）**：Unity 的压缩纹理是**自下而上**（OpenGL 约定）存的，
参考实现原样返回，而 Android 位图要求自上而下。GL 路径靠「上传 → 读回 → 按行倒序」隐式翻转，
CPU 路径必须在写像素时显式翻转（`AstcDecoder.decode(flipVertical = true)`）。
漏了这一步的表现不是「图坏了」，而是：
- 过场图整张上下颠倒（文字倒着读）；
- Spine 图集每个区域都采到镜像位置 → **SD 小人「混乱错位」**。

正确性用**块级黄金样本**兜住：`app/src/test/resources/astc/` 有 8 种块尺寸 × 240 个随机块
+ 32 个特殊块（共 1952 条），以及 7 张整图样本（含 37×41、13×7 这类非块整数倍尺寸），
由「参考实现 + 权重夹紧补丁」（`tools/astc_fixture/`）生成，`AstcDecoderFixtureTest`
逐块比对像素 SHA-256。规范外的越界块标成 `unsafe`，只校验「不崩 + 输出确定」。

代价与提示：CPU 解码一张 1920×1080 的 ASTC 6x6（5.76 万块）在桌面 JVM 实测 **140ms/张**，
手机端约数百毫秒；且只在**导入时**发生一次（产物是 PNG），不影响浏览性能。
超过 4M 像素的大图会「边解边降采样」直接按目标尺寸输出，避免「全尺寸 int[] + 位图 + 缩放位图」
三份内存同时在峰值。因此界面上的提示从「会失败，请换真机」
改成了「当前 GPU 未硬件支持 ASTC 6x6：会自动改用 CPU 解码（画质一致，单张大图会慢一些）」。

### 5.5.4 立绘完整性自检（「个别舰娘少素材」）

有些皮肤的画面并不是「一张图」，而是 **`_tex` 图集 + MeshImage 声明的矩形**共同决定：
`mRawSpriteSize` 给出画布尺寸，`mMesh` 给出真正有几何体的区域。两者不一致时，
画面上就会出现「少了上半身/头部」。

实测 `painting/beiqi_tex`（贝奇默认皮肤，客户端 2026-10 版本）：

| 项 | 数值 |
|----|------|
| 图集 | 1024×418（ASTC 4x4） |
| prefab 声明的画布 | 606×979 |
| `beiqi-mesh` 顶点/三角形 | 120 / 60 |
| mesh 实际覆盖高度 | 754（**77%**，顶部 23% 无几何体） |

也就是说这个包里的图集**本身就没有头部/上半身像素**（左右两半都核对过），
网格也只覆盖到胸口以上一点点 —— 属于「游戏内资源没下全 / 该皮肤为分层资源」，
不是解码问题。因此导入时会做一次覆盖率自检并如实记录：

```json
"warning": "顶部约 23% 没有几何体覆盖（该立绘可能还有未下载的图层包，建议先在游戏内打开一次再重新导入）"
```

- 判据：`mesh.height / layer.height < 0.85` 记为缺层（低于 15% 的空白多是正常留白，不报警）；
- 查看器副标题直接显示这条提示，避免用户以为是导入坏了；
- 对照组：`22_tex`（1024×524 → 858×1024）、`beiqi_2_tex` 都是 `warning: null`，不误报。

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

### 5.6.1 「游戏资源导入 → 今日秘书舰」的完整链路（2026-10 打通）

两条功能共用同一份产物目录（`game_assets/char/<id>/`），但历史上只「看起来」连通，
实际有三处断点，全部已修：

| 断点 | 现象 | 根因 | 修复 |
|------|------|------|------|
| ①索引早退 | SD 资源库里**一个游戏导入的小人都看不到** | `SDResourceManager.buildIndex()` 在 `blhx_sd/` 不存在时 `return emptyMap()`，后面扫 `game_assets/char` 的代码根本没执行；新用户往往从没手工放过 SD 资源，正好命中 | 本地目录不存在只记日志、继续扫游戏导入资源；日志会写明「本地 0，游戏导入 N」 |
| ②只能单向 | 在导入页看到小人，却要回秘书舰设置里翻资源库 | 导入页卡片只有「查看 / 重新导入」 | 已导入的 SD 卡片新增**「设为今日秘书舰」**：`GameImportIntent.SetAsSecretary` → `SecretaryManager.setSdResourceId("game/<id>")`，一次点击完成 |
| ③按舰名匹配走不到 | 没 pin、也没有网络立绘时小人整块不显示；皮肤选择器显示「未找到 SD 资源」 | 渲染门槛与皮肤列表都只看 `figureUrl / webChibiUrl / sdResourceId`，`SDResourceManager.resolve(…, shipName)` 这条「按舰名回退到游戏导入资源」的路径被绕过 | ① 状态新增 `sdAvailable`（IO 线程算「当前秘书舰能不能解析出 SD」），悬浮窗/主界面/Service 三处门槛统一带上它；② `SdSkinSelector` 未 pin 时先走统一 `resolve`（含游戏导入），再退回 `LocalSdResolver`；③ 监听 `LocalSdResolver.revision`，导入/删除后立刻重算 |

另外补了**悬空 pin 自愈**：游戏导入页删除/改名后，`sdResourceId` 会指向不存在的资源，
过去表现为「设置里显示已选择、画面却什么都没有」。现在 `SecretaryManager` 检出后自动清除
pin 并回退到舰名匹配（不动舰名/语音）。

验证（模拟器实测，pin 与不 pin 两条路都走通）：

```
SDResourceManager: buildIndex: 本地 SD 目录不存在（只扫游戏导入资源）：…/blhx_sd
SDResourceManager: buildIndex: 发现 2 个资源（本地 0，游戏导入 2）
SecretaryManager: sdAvailable=true（ship=2B, pin=game/2b）   # 卡片一键设置
SecretaryManager: sdAvailable=true（ship=2B, pin=）          # 清除 pin 后按舰名回退
SpineSdView: Loaded Spine asset=2B … Started idle animation: stand
```

**查看器的两个坑（都会让小人「看着像坏了」）**：

1. **待机动画不能按「attachment 覆盖率」选**。`char/2b` 的 `dead` 覆盖率最高（倒地时部件反而全开），
   旧逻辑按覆盖率排序就把 `dead` 选成待机 —— 一进查看器就是「躺在地上的一团」。
   现在按「命中已知待机名（stand/normal/sit…）→ 排除死亡/受击名 → 覆盖率 → 时长」四级排序。
2. **取景边界要用真正会播放的那条动画**。旧逻辑取所有 idle 名的**并集**，`sit`/`sleep`
   会把包围盒撑大（2B 并集 829 高，`stand` 只有 801 但重心完全不同），
   模型被缩成一小块还偏到右下。现在改用 `idleCoverageOrder.first()` 的边界重新采样。

顺带修掉一个断点：`SecretaryShipSettingsScreen` 早就接收了 `onOpenSdGallery` 回调，
但界面上从来没有渲染入口——用户在设置里根本进不去 SD 资源库（导入的小人自然也选不到）。
现在「SD 小人」分区里补了资源库卡片（管理 / 清除），入口与秘书舰的悬浮小人同屏。

### 5.7 舰娘名匹配统一引擎（ShipNameMatcher）

历史上船坞头像（`AvatarMatcher`）与秘书舰 SD（`LocalSdResolver`）各自实现了一套
拼音匹配：6 个同义函数两份拷贝、两张**互相冲突**的别名表（「贝露」一边 `HDN401`
一边 `peineiluopo`；「泛用型布里/试作型布里MKII」在 SD 表里都指向 `buli_super`），
策略顺序、阈值也各不相同——同一舰娘在不同界面可能匹配到不同资源。

现在统一到 [ShipNameMatcher]（纯 Kotlin，无 Context，可 JVM 全量回归）：

| 级别 | 策略 | 例子 |
|------|------|------|
| ALIAS | 别名精确（**有序候选**，按索引实际存在者择优） | 贝露→HDN401 / 倔强→juejiang |
| SPECIAL | 变体优先：META→`_alter`、μ兵装→`_idol`、小X→`_younv`、`.改`→`_g` | 长春.改→changchun_g |
| TAIL_MARK | 尾部标记：`霞(DOA)`→`xia_doa`、`天城CV`→`tiancheng_cv` | |
| PINYIN | 全拼精确（含变体名全拼，`大黄蜂II`→`dahuangfengII`） | |
| CONTAINING | 候选以「拼音_非皮肤后缀」开头 | suixiang_doa |
| PREFIX | 候选是全拼前缀（音节边界对齐） | wuerlixi ← 乌尔里希·冯·胡滕 |
| FUZZY | 全拼的音节对齐子串（占比闸门） | qinli ← 五河琴里 |
| INITIALS | 首字母缩写 | welxfht ← 乌尔里希冯胡滕 |
| SEGMENTS | 逐字拼音前 N 段拼接 | |
| TYPO | 拼写容错（Damerau-Levenshtein ≤1/≤2） | jueqiang→juejiang |

关键语义（从旧实现继承并固化）：

- **音节边界闸门**：前缀/子串类匹配必须落在逐字拼音的边界上
  （"水星号" ≠ "彗星"，防跨字拼接误配）；
- **皮肤形态硬性不回退**：META / μ兵装 / `.改` 的专属资源缺失时返回 null，
  绝不显示基础舰娘的形象（显示错图比显示空白更糟）；
- **变体不剥离进全拼**：`大黄蜂II` 的全拼是 `dahuangfengii`（命中 `dahuangfengII.webp`），
  `小赤城` 是 `xiaochicheng`——变体只影响「带后缀」的优先尝试；
- **别名冲突按存在性择优**：`贝露` 保留 `[HDN401, peineiluopo]` 双候选，
  哪个在当前索引里存在用哪个（船坞用 HDN401，永远不会误配皇家舰佩内洛珀）；
- **SD 特有别名隔离**：`哈里森→molisen` 只在 SD 匹配生效（船坞头像会触发撞车审计）。

消费方：船坞头像（`AvatarMatcher.match/matchOath` 委托）、秘书舰 SD
（`LocalSdResolver.resolve` 委托）、`Live2dNameResolver` 反查（变体剥离走 `keyParts`）、
`AvatarAssetIndex`（候选基名走 `keyParts`）——游戏资源导入的显示名
（`2b_2` → 「2B · 换装2」）与头像封面因此自动受益。

回归：`AvatarMatcherTest`（1004 舰全量语料 + 零撞车审计）、
`ShipNameMatcherTest`（单点语义 10 组）、`PinyinSyllableAlignTest` 全部通过。

本轮在已提交的引擎骨架上修复的三个实际缺陷：

1. **尾部标记污染全拼**：`大黄蜂II` 曾因剥掉 `II` 只能撞上"大黄蜂"、`霞(DOA)` 曾拼出
   `xiadoa_doa` 永远命中不了 `xia_doa`——现在 core 恒为原文（全拼保留 `ii`），
   剥标记的基名单独存放专供「拼音_标记」尝试；
2. **别名大小写**：表里的 `HDN401` / `zhixiao_DOA` 是大写写法，而索引键全为小写，
   直接 `index[alias]` 永远落空（"贝露"才会误配皇家舰佩内洛珀）——统一小写比对；
3. **SD 接入**：`LocalSdResolver` 886 行旧实现（含冲突 MANUAL_MAP）删除，
   委托引擎并启用 `SD_EXTRA_ALIASES`；反向包含放行联动标记后缀
   （`mengmeng_tolove` 是梦梦的唯一资源）而仍拒绝皮肤形态。

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
- 设备 GPU 只支持 ASTC 4x4（模拟器常见）时，过场图与 SD 小人（ASTC 6x6）走 **CPU 软件解码**：
  已不再黑屏，只比硬件解码慢（单张大图几十毫秒，仅导入时一次）。
- ETC2 / ETC1 仍只走 GL 通路（游戏现网资源都是 ASTC，这两条是兼容老包的旁路）；
  若某天遇到「只支持 ETC2 的包 + 不支持 GLES3 的设备」，同样需要补软件解码。

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
| 12 | **release 包「Shizuku 已连接却扫描不到任何资源」**（debug 包正常） | R8 把 `BlyyShellService::class.java.name` 折叠成字符串常量后，服务实现类变成「只被字符串引用」：类名保留、**构造器与全部 AIDL 方法被删**（`build/outputs/mapping/release/usage.txt` 里能直接看到）。Shizuku 在独立进程里反射 `newInstance(Context)` 失败 → 绑定回调永远不来 → `discoverRoots()` 吞掉异常返回空表，UI 只显示「未找到资源目录」 | ① `proguard-rules.pro` 增加 keep（服务类 + `<init>(Context)` + AIDL 包 + `rikka.shizuku` 入口）；② 绑定层加**真实 `ping()` 往返**校验与一次重绑重试；③ `discoverRoots()` 不再吞异常，失败原因上浮成可读文案。release APK 实测：`mapping.txt` 里服务类名与 `<init>` 保持原名，`classes.dex` 中 `listFilesPage` / `copyTo` 均在 |
| 13 | Shizuku 连上后页面顶部一直挂着「Shizuku 已连接（v13）」横幅 | 提示卡无条件渲染，连接成功后成了纯噪音 | `ShellState` 新增 `SERVICE_UNREACHABLE`（权限给了但服务不通），提示卡只在 `NOT_RUNNING / NO_PERMISSION / SERVICE_UNREACHABLE` 时出现；`READY` 时整张卡片从列表移除，空态文案也改成「点右上角刷新」而不是教用户去连 Shizuku |
| 14 | 模拟器上 SD 小人（ASTC 6x6）导入后黑屏 | 该 GPU 只实现 ASTC 4x4 | 见 §5.5.3：新增纯 Kotlin `AstcDecoder` 兜底 + 1952 条块级对拍夹具；`canDecode()` 不再依赖 GPU 能力 |
| 15 | **过场图整张上下颠倒；SD 小人「混乱错位」** | CPU 解码漏了行序翻转：Unity 压缩纹理自下而上，位图要求自上而下。GL 路径靠读回时倒序隐式翻转，CPU 路径直接按原序写入 | `AstcDecoder.decode(flipVertical = true)`：未缩放与降采样两条路径都在写目标行时翻转；新增两条单测（翻转 = 解完全尺寸后倒序、降采样路径同样只翻行）。模拟器实测：`bg_1` 标题栏文字恢复正常方向 |
| 16 | SD 小人一进查看器就是「躺在地上的一团」/ 缩成右下角一小块 | ① 待机动画按覆盖率排序选中了 `dead`；② 取景边界用了所有 idle 动画的并集 | ① 待机四级排序（已知名 → 排除 dead/hurt → 覆盖率 → 时长），日志 `Started idle animation: stand`；② 用真正播放的那条动画重采样边界，日志 `取景边界改用待机动画 stand` |
| 18 | **「今日秘书舰」的 SD 资源库里看不到刚从游戏导入的小人** | `SDResourceManager.buildIndex()` 在 `blhx_sd/` 不存在时直接返回空表 —— 新用户从来没有手工放过 SD 资源，游戏导入的 `game_assets/char/*` 根本没被扫描 | 见 §5.6.1 断点①：本地目录缺失不再早退，继续扫游戏导入资源；日志区分「本地/游戏导入」计数 |
| 19 | 清掉 SD 选择（或从没设置过）后秘书舰小人整块不显示，皮肤选择器还提示「未找到 SD 资源」 | 显示门槛与皮肤列表只看 `figureUrl / webChibiUrl / sdResourceId`，绕过了「按舰名回退到游戏导入资源」；删除资源后 pin 还会悬空 | 见 §5.6.1 断点③：新增 `sdAvailable` 门槛 + 皮肤列表走统一 `resolve` + 监听资源修订号；并补悬空 pin 自愈 |
| 17 | 贝奇默认立绘「少了上半身」 | 该客户端的 `painting/beiqi_tex` 图集本身只有下半身（1024×418），`beiqi-mesh` 只覆盖声明的 606×979 画布中的 754 高（77%） | 见 §5.5.4：新增覆盖率自检，写入 `painting.json.warning` 并在查看器副标题展示；对照组 `22_tex` / `beiqi_2_tex` 不误报 |


已验证可用（模拟器实测）：

- 扫描：8109 个资源包全部分类正确（含 6218 个立绘）
- 导入：`live2d`（abeikelongbi_3，1.9s，14 组动作）、`spinepainting`（aerbien_4，2.1s，8 个旋转区域归一化 + 4096→2048 缩放）
- 渲染：动态皮肤完整显示（含背景层，贴图方向/UV 正确，动作可切换）；静态立绘 2048×1206 正常显示与缩放
- 入库：索引记录、已导入计数、卡片状态（待导入/已导入/失败原因）均正确

`char` SD 小人的最终渲染：该模拟器没有 ASTC 6x6，现在走 CPU 软件解码路径出图
（`CompressedTextureDecoder.decode` → `decodeAstcSoftware`），与真机（Adreno/Mali 全块尺寸 ASTC）
共用同一套后续流程，差别只在「谁来解这个压缩块」。

release 包验证方式（无需真机）：

```bash
./gradlew :app:assembleRelease
grep -n "BlyyShellService" app/build/outputs/mapping/release/mapping.txt   # 类名与 <init> 必须保持原名
unzip -p app/build/outputs/apk/release/*.apk classes.dex | strings | grep -E "listFilesPage|copyTo"
```

---

## 11. Shizuku 通道：状态机、release 裁剪与失败可见性

扫描链路只有一条：`ShizukuBridge`（绑定 UserService）→ `BlyyShellService`（shell 身份列目录 / 拷文件）。
这条链路有三处容易「静默失败」，现在都被显式处理：

### 11.1 状态机（UI 与扫描共用）

```kotlin
enum class ShellState { UNKNOWN, NOT_RUNNING, NO_PERMISSION, SERVICE_UNREACHABLE, READY }
```

| 状态 | 判据 | 界面 |
|------|------|------|
| `NOT_RUNNING` | `Shizuku.pingBinder()` 失败 | 卡片：未检测到 Shizuku 服务 |
| `NO_PERMISSION` | `checkSelfPermission() != GRANTED` | 卡片 + 「授权」按钮 |
| `SERVICE_UNREACHABLE` | 权限有、但 `requireService().ping()` 失败/超时 | 卡片 + 失败原因 + 「重试」按钮 |
| `READY` | `ping()` 真的回了一个字符串 | **不渲染任何 Shizuku 提示**（连接成功后提示即噪音） |

`probeState()` 是唯一判据来源：只判断「有权限」是不够的——`bindUserService` 是异步的，
绑定失败只会让等待超时，UI 却会一直显示「已连接」。

### 11.2 release 包为什么必须 keep UserService

服务实现类跑在 Shizuku 拉起的**独立进程**里，由框架反射实例化：

```java
Class.forName(className).getConstructor(Context.class).newInstance(context);
```

`ShizukuBridge` 里写的是 `BlyyShellService::class.java.name`。R8 会把这种「类字面量取名字」
折叠成字符串常量，于是该类的可达性只剩一个字符串 → **类被保留、成员全删**：

```
# app/build/outputs/mapping/release/usage.txt（修复前）
com.azurlane.blyy.util.shizuku.BlyyShellService:
    public void <init>(android.content.Context)     ← 构造器被删
    public java.lang.String listFilesPage(...)      ← AIDL 方法被删
    public long copyTo(...)
```

现象：`pingBinder()` 正常、权限也给了，`bindUserService()` 不报错，但回调永远不来 →
`requireService()` 超时 → `discoverRoots()` 返回空 → 「已连接却扫不到资源」。

修法是 `proguard-rules.pro` 里显式 keep（类 + `<init>(Context)` + AIDL 包 + `rikka.shizuku` 入口）。
验证不看真机，看产物：`mapping.txt` 里服务类名与 `<init>` 必须保持**原名**，且
`classes.dex` 里能搜到 `listFilesPage` / `copyTo`。

### 11.3 失败必须可见

- `discoverRoots()` 不再 `runCatching{}.getOrNull()`：绑定失败与「游戏没下载资源」是两件事，
  吞掉之后用户只看到「未找到资源目录」；
- `requireService()` 带一次重绑重试（远端进程被系统回收后，第二次基本都能成），
  并把底层异常翻译成人话（`NoSuchMethodError` → 「安装包被裁剪，请安装完整版」）；
- 权限回调、进入页面、点刷新、点重试都走同一个 `probeState()`，不存在「三个入口三套判断」。
