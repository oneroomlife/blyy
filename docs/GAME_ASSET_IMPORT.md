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
| [PAINTING] | `painting/` 的 `*_tex` | 单张大贴图 | `painting.png` / `painting.json` | Compose 图片 + 双指缩放 |

### 判定顺序（以包内容为准，路径只作兜底）

1. MonoBehaviour 里出现 `MOC3` 魔数 → **Live2D**
2. 有 `*.skel` TextAsset（配 `*.atlas`）→ **Spine**（类型取包内 `AssetBundle.m_Name` 前缀，
   如 `char/xxx` / `spinepainting/xxx`）
3. 有 ≥512×512 的 Texture2D → **静态立绘**
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
   │ Shizuku: listFiles / copyTo          （或 SAF: 选择文件 / 选择文件夹）
   ▼
cacheDir/game_bundles/<id>_<size>.src
   │ GameBundleImporter.importLocal
   ├── UnityBundleSession.open → BundleProbe.probe          （一次解压，镜像复用）
   ├── 分流：
   │     Live2D  → AlLive2dExtractor.extract
   │     Spine   → SpineBundleExtractor.extract
   │     Painting→ PaintingBundleExtractor.extract
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

## 5. 目录布局

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

## 6. 界面

`GameImportScreen`（侧栏「游戏资源导入」）：

- **资源包**分区：Shizuku 状态卡 → 类型筛选（全部/Live2D/SD/动态/立绘，带计数）→
  全部导入 / 文件夹 / 选文件 → 进度卡（可停止）→ 搜索 → 资源包卡片（状态：待导入 / 已导入 /
  有更新 / 失败，失败直接展示原因）
- **已导入**分区：类型筛选 → 两列网格（立绘显示原图、Spine 显示头像兜底/类型占位）→
  长按详情（骨骼/页数/版本/占用/来源）→ 打开 / 重命名 / 删除

`GameAssetViewerScreen`：

- Spine：`SpineSdView`（GLES2 + libgdx 3.8）全屏渲染，点击模型随机动作，
  底部可切换骨骼、拖缩放、点动作名直接播放
- 立绘：`ZoomableImage` 双指缩放
- Live2D 资源不经过这里（跳转原 Live2D 查看器，陪伴模式等能力保持不变）

导航要点：两个新路由（`gameimport`、`gameimport/view/{kind}/{assetId}`）已加入
「全屏页隐藏底部导航 / 关闭抽屉手势 / 遮蔽悬浮秘书舰」三处判断，与 Live2D 查看器一致。

---

## 7. 测试

`resource/test/` 提供四类真实资源包（Live2D 6 个、char 4 个、painting 11 个、spinepainting 10 个）。
无设备时可用 `resources/test` 目录直接「选择文件夹」导入。

自动化覆盖（JVM）：

| 测试 | 覆盖 |
|------|------|
| `GameAssetNamingTest` | 后缀剥离（`_tex`/`_res`）、变体识别、id 归一、路径→类型 |
| `SpineAtlasParserTest` | 多页解析、区域名带空格、缩放改写坐标、`rotate` 归一化与包围盒；含**真实图集**（`zhezhi_2.atlas`：3 页 103 区域 8 个角度旋转）断言 |
| `SpineBinaryHeaderTest` | `len+1` 字符串编码、版本识别、损坏数据兜底 |
| `CompressedTextureFormatTest` | ASTC 4x4/5x5/6x6/8x8/10x10/12x12、ETC2/ETC1、RGB/RGBA 变体 |
| `GameBundleProbeTest` | 六个真实包逐一断言：类型、骨骼/图集名、Spine 版本、贴图格式与尺寸 |
| `GameBundleSweepTest` | `resource/test` 全量 29 个包分类扫描（6 Live2D / 4 char / 10 painting / 9 spinepainting） |

格式侧的结论用 `resource/test` 的真实包逐一核对过（页面尺寸、页↔贴图匹配、
区域是否落在页内、版本号）：29 个包全部分类正确，8 个 Spine 包的页/贴图/包围盒校验全部通过。

---

## 8. 已知限制

- 仅支持 **ASTC / ETC2 / ETC1 / RGBA32** 贴图；DXT/PVRTC 等桌面/老机型格式会给出明确错误。
- Spine 运行时固定 **3.8**：`3.7/3.8` 可直接加载；4.x 骨骼能提取但运行时会报版本不匹配
  （导入时会记录版本并在详情页展示）。
- 单个图集页超过上限会降采样（并改写坐标，不保留原始像素）。上限按设备堆自适应：
  `memoryClass ≥512MB → 4096`、`≥256MB → 3072`、其余 `2048`
  （4096² 的 ARGB 位图要 64MB，低内存机型直接解码会 OOM）。
- 动态皮肤的 `*_hx` 等变体作为**独立骨骼**提取，同一目录多套，查看器可切换。
- 静态立绘只取包内最大（或与 id 同名）的一张贴图，包内 UI 碎图不入库。

---

## 9. 真机 / 模拟器联调记录（含已修问题）

在 Android 模拟器（x86_64, API 35, 720×1280，Adreno 740 字符串 + ASTC 4x4 可用）接入真实游戏资源包
（`live2d 271 / char 1401 / spinepainting 219 / painting 6218`）逐项验证，暴露出四个问题：

| # | 现象 | 根因 | 修复 |
|---|------|------|------|
| 1 | 扫描结果里「立绘 0」，其它三类正常 | `painting` 目录 1.2 万+ 文件，一次性 `listFiles` 返回约 1.3MB，**超过 Binder 单次回复上限** → `TransactionTooLargeException`（表现为 `DeadObjectException`），shell 进程被打挂 | AIDL 增加 `listFilesPage`：服务端缓存目录快照，按页（600 条 / 64KB）返回；客户端循环取完。桥接层加 `isBinderAlive` 校验与一次重绑重试 |
| 2 | Spine 资源导入成功但查看器黑屏，日志 `EOFException at SkeletonBinary.readString` | TypeTree 把 TextAsset 的 `m_Script` 声明成 `string`，按 UTF-8 解码再编码把二进制 `.skel` 里的非 UTF-8 字节替换成 `U+FFFD`（实测 200KB 中 3.7 万个） | 新增 `UnityTextAssetReader`：按「对齐长度 + 原始字节」直接读，跳过文本解码；`.skel` 写出后校验版本号，解析不出 3.x 直接报错而不是产出损坏资源；Spine 管线版本 1 → 2 触发旧产物重解包 |
| 3 | 查看器底部「随机动作」按钮被裁掉一半 | 缩放条 `weight(1f)` + 右侧按钮在 720dp 宽下超宽 | 去掉冗余按钮（顶栏已有随机动作入口），缩放条独占剩余宽度，倍率文字右对齐 |
| 4 | `char`（SD 小人）导入失败：`GL 错误 0x501` | 该模拟器的 GLES 实现只支持 **ASTC 4x4**，`char` 包用的是 **ASTC 6x6**（Unity `TextureFormat=50`） | 保留 4x4/5x5/6x6…全量映射（真机 Adreno/Mali 支持），并补充「按块整数倍补齐尺寸」重试；仍失败时给出可读提示：当前 GPU 不支持该块尺寸、模拟器通常只有 4x4 |

已验证可用（模拟器实测）：

- 扫描：8109 个资源包全部分类正确（含 6218 个立绘）
- 导入：`live2d`（abeikelongbi_3，1.9s，14 组动作）、`spinepainting`（aerbien_4，2.1s，8 个旋转区域归一化 + 4096→2048 缩放）
- 渲染：动态皮肤完整显示（含背景层，贴图方向/UV 正确，动作可切换）；静态立绘 2048×1206 正常显示与缩放
- 入库：索引记录、已导入计数、卡片状态（待导入/已导入/失败原因）均正确

未能在该模拟器验证：`char` SD 小人的最终渲染（受 ASTC 6x6 限制）。
真机（Adreno/Mali 全块尺寸 ASTC）上该路径与动态皮肤共用同一套解码/渲染代码，只差纹理格式。
