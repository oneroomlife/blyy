# SD 小人渲染层重构影响评估（GLSurfaceView → TextureView）

> 评估对象：把 `SpineSdGlSurfaceView`（`GLSurfaceView`）改为 `TextureView` + 自管 EGL，
> 目的是让悬浮窗在 InputDispatcher 里只剩 1 条遮挡图层，从而把穿透时的窗口 alpha
> 从 **0.5417** 恢复到 **0.79**（视觉不透明度 +46%），同时保留跨应用触摸穿透。
>
> 本文只做影响面评估，不含改动代码。

---

## 0. 一句话结论

| 维度 | 结论 |
|---|---|
| 业务逻辑复用度 | 高。动画、骨骼、命中测试、缩放、热重载、panZoom 全部与渲染容器解耦，可原样保留 |
| 必须新写的代码 | 中高。EGL 生命周期 + 渲染线程 + SurfaceTexture 管理，约 250–350 行 |
| 必须同步改的既有逻辑 | 4 处：悬浮窗 flags、查看器转场、应用内 z 序预期、注释/常量前提 |
| 最大风险 | ① **悬浮窗当前实际是软件渲染**（flags 被我们整体覆盖，把系统自动加的 `HARDWARE_ACCELERATED` 抹掉了）——TextureView 在软件渲染窗口里不显示，必须先修 ② 查看器全屏纹理尺寸/性能 ③ 反复 add/remove 窗口的 SurfaceTexture 泄漏 |
| 不会改变的行为 | **应用内"触摸穿透"仍然不可用**（AndroidView 在 Compose 命中测试里仍是顶层节点），与本次重构无关 |

---

## 1. 代码触点清单

### 1.1 必改（渲染容器本身）

| 位置 | 现状 | 改动性质 |
|---|---|---|
| `app/src/main/java/com/azurlane/blyy/ui/components/SpineSdView.kt:226` | `private class SpineSdGlSurfaceView(...) : GLSurfaceView(ctx)` | 换基类为 `TextureView`，`renderMode = RENDERMODE_CONTINUOUSLY`、`setEGLContextClientVersion`、`setEGLConfigChooser`、`holder.setFormat`、`setZOrderOnTop`、`setRenderer` 全部失效，需等价自实现 |
| `SpineSdView.kt:344`（init 块） | `setZOrderOnTop(true)` 让 GL 图层浮在窗口最上 | TextureView 无此语义，z 序回归 Compose 树 |
| `SpineSdView.kt:1393-1429` `onSurfaceCreated` | 初始化 `Gdx.gl`/`Gdx.files`/`Gdx.graphics` stub，然后 `loadAsset()` | 需区分「EGL context 首次创建」与「仅 surface 重建」；否则每次窗口 re-add 都会重新解码图集 + 采样不透明像素 |
| `SpineSdView.kt:1431-1436` `onSurfaceChanged` | 写 `viewWidth/viewHeight`、`glViewport` | 换成 `onSurfaceTextureSizeChanged` + `setDefaultBufferSize` |
| `SpineSdView.kt:1438-1557` `onDrawFrame` | 渲染主循环（自适应缩放、居中、bounds、alpha、clear、draw） | 逻辑可整段复用，只换驱动方式（Choreographer / `onFrameAvailable`） |
| `SpineSdView.kt:317/342` `resetTransform` / `reloadAsset` | `queueEvent { ... }` 是 GLSurfaceView API | 换成自己的 render-thread 任务队列 |
| `SpineSdView.kt:199-222` `DisposableEffect` | `visibility = GONE` → `onPause()` | TextureView 无 `onPause/onResume`，需停/启渲染线程；顺序补偿仍需要 |
| `SpineSdView.kt:152` `viewRef: MutableState<SpineSdGlSurfaceView?>` | 具体类型 | 抽 `SpineRenderView` 接口 |

### 1.2 需复查/微调（不改也不会崩，但语义变了）

| 位置 | 说明 |
|---|---|
| `app/src/main/java/com/azurlane/blyy/SecretaryOverlayService.kt:264` `baseWindowFlags()` | **阻塞性缺陷（已实测确认）**：`WindowManagerGlobal.addView` 会在应用开启硬件加速时自动补 `FLAG_HARDWARE_ACCELERATED`（AOSP `android/view/WindowManagerGlobal.java:404-411`），但 `applyWindowTouchAttributes()`（:399-402）用 `lp.flags = windowFlagsFor(...)` **整体赋值**，把系统补的位抹掉 → 悬浮窗实际走软件渲染（实测 `dumpsys window` 中本窗 `fl=` 无 `HARDWARE_ACCELERATED`，而同设备的 systemui 浮窗有）。TextureView 在软件渲染窗口里不显示，必须先改成"只改我们关心的位"或显式补该 flag |
| `SecretaryOverlayService.kt:378` `countObscuringLayers()` | 重构后恒返回 1，逻辑自然失效；建议保留（静态图回退分支仍需要，且能防将来再引入 SurfaceView 的回归） |
| `SecretaryOverlayService.kt:436` `scheduleTouchAttributeRecheck()` | 300ms 后重算图层的意义消失，可保留（无害）或视为死代码清理 |
| `app/src/main/java/com/azurlane/blyy/ui/components/OverlayGeometry.kt:199-210` | KDoc 里"SurfaceView 会额外贡献一条遮挡条目"的前提需要补一句"TextureView 不会" |
| `app/src/main/java/com/azurlane/blyy/ui/AppRoot.kt:904-915` | 「查看器内部是 setZOrderOnTop 的 GLSurfaceView，不吃 Compose 转场」——前提消失，enter/exit 的 `Micro` 时长与页内 GL alpha 会变成**双重淡出** |
| `AppRoot.kt:996-1001` `ChibiRemountDelayMillis = 380L` | 前提（EGL 重建 + 纹理上传尖峰）仍成立，但耗时量级可能变化，需实测后调 |
| `AppRoot.kt:1025` 注释 | 「小人是 AndroidView 里的 GLSurfaceView，Compose 命中测试仍把它当最上层节点」——重构后**仍然成立**（AndroidView 语义不变），注释无需改 |

### 1.3 调用点（3 处，全部共用同一组件，自动继承改动）

| 位置 | 场景 | 关注点 |
|---|---|---|
| `ui/components/SecretaryChibiOverlay.kt:373-390` | 系统悬浮窗主窗：`fillMaxSize`、`scaleMultiplier = 1.0f`、`touchEnabled = !touchPassthrough` | 收益点（遮挡层数 2→1） |
| `ui/components/SecretaryChibiOverlay.kt:640-670` | 应用内：`requiredSize(renderWidth, renderHeight)` + 负 offset 溢出、`scaleMultiplier = characterScale` | z 序变化（会被弹窗正常遮挡）、放大是否被裁 |
| `ui/screens/gameimport/GameAssetViewerScreen.kt:242-250` | 全屏资源查看器：`fillMaxSize`、`panZoomEnabled = true`、`resetSignal`、`contentAlpha` | 纹理尺寸上限、性能、转场重排 |

---

## 2. 逐项影响分析

### A. 渲染容器与生命周期（必改，工作量最大）

`GLSurfaceView` 替我们做了四件事，TextureView 一件都不做：

1. **EGL 环境**：`EGL14.eglGetDisplay/eglInitialize/eglChooseConfig/eglCreateContext`，以及从 `SurfaceTexture` 创建 `EGLSurface`。
2. **渲染线程**：独立线程 + 循环 + `eglSwapBuffers` + 帧同步。
3. **Surface 生命周期**：`onSurfaceCreated` / `onSurfaceChanged` / `onSurfaceDestroyed` 的自动重建与回调。
4. **上下文丢失后的资源重建语义**：GLSurfaceView 销毁 surface 时不会销毁 context（默认 `setPreserveEGLContextOnPause(false)` 才销毁），而资源重建由 `onSurfaceCreated` 承担。

改造后的关键差异与风险：

- **资源重建时机必须拆分**。当前 `loadAsset()` 挂在 `onSurfaceCreated` 里，而悬浮窗会因为「僵尸窗口修复、转屏、`ensureMainWindowAttached` 重试」反复 `removeView`/`addView`。若重构后把 `loadAsset()` 也挂在「每次 surface 重建」上，每次重建都要重新解码图集 + `SpineContentProbe` 采样不透明像素（每皮肤几十毫秒 + 峰值内存），比现在的 GLSurfaceView 更容易掉帧。
  → 必须区分 `contextCreated`（首次，做 `Gdx.gl` 初始化 + `loadAsset`）与 `surfaceRecreated`（只重建 EGLSurface + `glViewport`）。
- **`PolygonSpriteBatch` / `TextureAtlas` 与 context 绑定**（`SpineSdView.kt:1098`、`SpineSdView.kt:876-881`），且 libgdx 的 `Gdx.gl` 是**进程级静态**。多实例（悬浮窗 + 查看器可能同时存在）时，各线程的 context 不同而 `Gdx.gl` 只有一个——现状已是如此（每个 GLSurfaceView 各有一线程/上下文，共用 `Gdx.gl` 引用），重构不加剧问题，但**不要**改成多实例共享一个 EGL context + 单线程，那会把绘制串行化。
- **泄漏面**：`SurfaceTexture` 必须 `release()`，渲染线程必须能退出并 `join`，`EGLSurface/EGLContext/EGLDisplay` 必须 `eglDestroy*`。悬浮窗是长生命周期服务且反复重建窗口，这是最可能出问题的地方（现状由框架兜底）。
- **`FLAG_HARDWARE_ACCELERATED`（阻塞性前提，且是现存的隐性缺陷）**
  - AOSP 行为：`WindowManagerGlobal.addView`（`android/view/WindowManagerGlobal.java:404-411`）在 `parentWindow == null` 且 `ApplicationInfo.FLAG_HARDWARE_ACCELERATED` 置位时，会自动给 `LayoutParams` 补 `FLAG_HARDWARE_ACCELERATED`；`ViewRootImpl.java:2002` 再从 `attrs.flags` 判定该窗口是否硬件加速。本应用 `AndroidManifest.xml:47` 已开 `android:hardwareAccelerated="true"`，所以建窗那一刻是硬件加速的。
  - 但 `SecretaryOverlayService.applyWindowTouchAttributes()`（:399-402）里的 `lp.flags = windowFlagsFor(touchPassthrough)` 是**整体赋值**，`windowFlagsFor` 只返回 `NOT_FOCUSABLE|LAYOUT_IN_SCREEN|NOT_TOUCH_MODAL|LAYOUT_NO_LIMITS`（穿透时再加 `NOT_TOUCHABLE`），系统补的 `HARDWARE_ACCELERATED` 被直接抹掉。
  - 实测证据（127.0.0.1:16480，Android 15）：
    ```
    // 本应用悬浮窗
    mAttrs={(692,78)(165x247) ... ty=APPLICATION_OVERLAY fmt=TRANSLUCENT alpha=0.54174244
      fl=NOT_FOCUSABLE NOT_TOUCHABLE NOT_TOUCH_MODAL LAYOUT_IN_SCREEN LAYOUT_NO_LIMITS
    // 对照：systemui 的浮窗
      fl=NOT_FOCUSABLE HARDWARE_ACCELERATED
    ```
    → 悬浮窗现在实际是**软件渲染**（Compose 的阴影/模糊/部分绘制效果都会退化）。
  - 结论：TextureView 在软件渲染窗口里**不显示内容**，所以这条不是"可以以后再补"，而是重构的前置修复。修法二选一：
    - `baseWindowFlags()` 里显式 `or FLAG_HARDWARE_ACCELERATED`（简单、幂等）；
    - 更好的做法：`applyWindowTouchAttributes` 改成"只改我们拥有的位"——`lp.flags = (lp.flags and TOUCH_BITS.inv()) or windowFlagsFor(touchPassthrough)`，避免将来再有系统/框架补的位被抹。
  - 副作用：修好后整窗（Compose 气泡、拖动提示、文字）从软件 Canvas 切到 RenderThread 硬件渲染，阴影/圆角/抗锯齿观感会变，需要整窗视觉回归 + 崩溃面回归。

### B. 交互（低风险，逻辑可原样保留）

- 触摸走 `OnTouchListener` + `event.rawX/rawY` + `SpineRenderer.isContentTouch(viewX, viewY)`（`SpineSdView.kt:1345`）+ `boundsInViewport`（`SpineSdView.kt:763`），全部是 `View` 层 API，与 SurfaceView/TextureView 无关。
- `GLSurfaceView` 设置 `OnTouchListener` 后会吃掉整个触摸序列；`TextureView` 是普通 View，行为一致（不消费则父级可继续处理）。`touchEnabled=false` 的穿透语义不变。
- 查看器 panZoom（单指平移 / 双指缩放 / 双击复位）全部在 `OnTouchListener` 内实现，不受影响。
- **重要：应用内穿透不会因此变好。** `SecretaryChibiOverlay.kt:301-311` 描述的"抓帧换静帧"方案目前**只存在于注释**（全仓库 grep `静帧`/`PixelCopy` 无实现），应用内的 AndroidView 依然是 Compose 命中测试的最上层节点。重构只解决"跨应用 + 窗口 alpha 代价"，不解决"应用内 AndroidView 挡手势"。

### C. 视觉与转场（中风险，需要重新编排）

现状是 `setZOrderOnTop(true)`：小人是 SurfaceFlinger 独立图层，**不吃** Compose 的 alpha / 位移 / 裁剪 / 转场。代码里有多处针对这一点的补偿：

1. `GameAssetViewerScreen.kt:108-144`：页内用 `Animatable` 驱动 `contentAlpha`（GL 顶点 alpha）做淡出，路由层只给 `Micro` 时长的 fade 兜底，注释明确写"两层 alpha 相乘会让内容看起来发灰、变慢"。
2. `AppRoot.kt:904-915`：`gameimport/view` 路由的 enter/exit 被刻意压到最短。
3. `SpineSdView.kt:118-138` KDoc：`contentAlpha` 必须走 GL，因为 Compose alpha 对 Surface 无效。

改成 TextureView 后这一整条链路的前提消失：

- **好处**：Compose 的 `graphicsLayer{ alpha }`、转场、圆角、裁剪对小人生效，`contentAlpha` 可以简化为 Compose alpha（但保留 GL 顶点 alpha 也没问题，二者会相乘，二选一即可）。
- **风险**：若不改，查看器会「页内 GL 淡出 + 路由 fade」双重淡出，观感发灰且更慢。必须同步调整——这是**必改项**，不是可选优化。
- **z 序变化（行为变化）**：应用内小人现在盖在所有 Compose 内容之上（含底部面板、对话框）；改后会被正常遮挡。判断为更正确，但属于用户可见变化，需要确认预期。
- **放大溢出**：应用内分支用 `Modifier.requiredSize(...)` + 负 offset 突破父 `Box`（`SecretaryChibiOverlay.kt:640-650` 注释）。TextureView 是普通 View，会受父级 `clipToBounds` 影响；当前外层 `Box` 无裁剪，理论无影响，但需在 `sdScale = 2.0` 下目视确认没被裁掉一圈。

### D. 性能 / 功耗 / 内存（中风险，集中在查看器）

| 项 | 悬浮窗（165×132） | 查看器（全屏） |
|---|---|---|
| 额外 graphics buffer | ≈ 87 KB | 1280×720 ≈ 3.7 MB；1080×2400 ≈ 10.4 MB |
| 额外 GPU 合成 | 可忽略 | 每帧多一次纹理采样合成，预计 +0～3 ms（低端机更明显，需实测） |
| 纹理尺寸上限 | 无风险 | **必须处理 `GL_MAX_TEXTURE_SIZE`**：GLES2 老设备可能只有 2048，1080×2340 会超限 → 需按 `min(maxTextureSize, viewSize)` 渲染再 `setDefaultBufferSize` + `setTransform` 拉伸 |
| 帧驱动 | 现在 60fps 常驻 | 同上；TextureView 需 Choreographer 驱动，否则不是烧 CPU 就是动画卡 |

- `RENDERMODE_CONTINUOUSLY` 是 vsync 驱动的，TextureView 要自己实现等价节流。若顺手改成"脏帧渲染"，会改变观感（拖动/动画的流畅度），建议单独一次改动，不要混在本次重构里。
- 悬浮窗常驻渲染本身是既有耗电点，重构不改善也不恶化。

### E. 悬浮窗穿透收益（核心目标）

- `windowAlphaFor()`（`SecretaryOverlayService.kt:355`）会因 `mainWindowObscuringLayers()` 从 2 变 1，自动算出 **0.79** 而不是 0.5417，`tapThroughWindowAlpha` 的 L=1 分支无需改动。
- 视觉：小人从 54% 不透明度回到 79%，观感明显更实。
- **必须实测确认** TextureView 不产生遮挡条目：理论上 `TextureView` 的 `BufferQueue` 由 `RenderThread` 消费，不对应 `SurfaceControl`，不会出现在 `dumpsys input` 的遮挡栈里；但这是本次收益的唯一支点，必须用
  `dumpsys input | grep -i "obscur\|<pkg>"` + `logcat | grep "obscuring opacity"` 双证据确认。
- `countObscuringLayers` / 3 秒看门狗 / 300ms 重算可以保留：对静态图回退分支仍有效，且能兜住"将来又引入 SurfaceView"的回归，成本近零。

### F. 兼容性与稳定性

- `minSdk = 24`：`TextureView` 自 API 14 可用，无 API 门槛。
- 老 GPU（Mali-400 / 早期 Adreno）上 `TextureView` 有已知黑屏、合成异常案例；`SurfaceView` 更稳。建议加渲染后端开关（默认 TextureView，异常回退 GLSurfaceView），至少保留一个可切换入口。
- 反复 `addView`/`removeView`（`ensureMainWindowAttached` 重试、转屏、僵尸窗口修复）是泄漏高发路径：`SurfaceTexture` 未释放会累积 buffer，渲染线程未退出会累积线程。
- 进程被杀/后台回收后的恢复路径：`SecretaryOverlayService.onTaskRemoved` 保留服务，服务可能长期存活并多次重建窗口，等价于反复走 surface 重建路径。

### G. 可以顺便简化 / 需要保留的补偿

| 项 | 处置 |
|---|---|
| `contentAlpha` 走 GL 顶点 alpha（`SpineSdView.kt:118-138`、`1541-1543`） | 可保留（与 Compose alpha 不冲突），也可简化为 `graphicsLayer`；二选一，不要都留 |
| `DisposableEffect` 的 `visibility = GONE` → `onPause()`（`SpineSdView.kt:211-221`） | **保留**：TextureView 也需要"先撤图层再停线程"，否则退出时残留一帧 |
| `ChibiRemountDelayMillis = 380L`（`AppRoot.kt:130`） | **保留**：EGL 重建尖峰仍在，需实测微调 |
| 路由 `Micro` 淡入淡出（`AppRoot.kt:904-915`） | **改**：可恢复为正常时长 |
| `SecretaryShipSettingsScreen.kt` 触摸穿透文案（"GPU 渲染图层额外计入遮挡"） | **改**：重构后不再有额外图层，文案要回到"≤80% 不透明度"的单层说法 |

---

## 3. 风险矩阵

| # | 风险 | 等级 | 触发条件 | 缓解 |
|---|---|---|---|---|
| 1 | flags 整体赋值抹掉 `HARDWARE_ACCELERATED` → 悬浮窗软件渲染；TextureView 直接不显示 | **高** | 改 TextureView 必现（现状已存在） | 先修 `applyWindowTouchAttributes`；`dumpsys window` 确认 `fl=` 出现 `HARDWARE_ACCELERATED` |
| 2 | 反复重建窗口导致 `SurfaceTexture`/线程泄漏 | **高** | 转屏、僵尸窗口修复、长时间前台 | 严格 release + join；写一个"重建 50 次"的压力验证 |
| 3 | 查看器全屏超 `GL_MAX_TEXTURE_SIZE` → 黑屏/花屏 | 中高 | 1080×2340 + 老 GPU | 按最大纹理尺寸降采样 + `setTransform` |
| 4 | 查看器双重淡出、观感发灰 | 中 | 不改转场必现 | 同步重排 `GameAssetViewerScreen` 与 `AppRoot` 的 fade |
| 5 | 全屏合成掉帧 | 中 | 低端机 | 实测帧率；必要时只在悬浮窗启用 TextureView |
| 6 | 应用内 z 序变化（被弹窗遮挡） | 低 | 用户可见 | 确认预期，必要时 `zIndex` 调整 |
| 7 | 放大溢出被裁剪 | 低 | `sdScale = 2.0` | 目视验证 |
| 8 | 硬件加速后 Compose 悬浮窗观感变化 | 中 | 改 flags 后 | 整窗截图对比（气泡、提示、圆角） |

---

## 4. 建议的落地顺序（若决定做）

1. **抽接口，不动行为**：把 `SpineRenderer` 从 `GLSurfaceView.Renderer` 解耦成独立的 `surfaceCreated/sizeChanged/draw` 回调 + 自己的任务队列，先让 `GLSurfaceView` 走新接口（行为 0 变化，可对照实测）。
2. **加 `TextureSpineView`**：实现同一接口，`SpineSdView(renderBackend = GL | TEXTURE)` 暴露开关，默认仍 `GL`。
3. **先修 flags 位操作**（独立小改动、可单独验证）：`applyWindowTouchAttributes` 改为按位修改，`dumpsys window` 确认 `HARDWARE_ACCELERATED` 出现，整窗视觉回归一次。这一步与渲染后端无关，本身就该修。
4. **悬浮窗先切**（收益最大、耦合最小）：实测 `dumpsys input` 层数、穿透、视觉、电量、重建 50 次不泄漏。
5. **应用内再切**：验证弹窗遮挡、放大溢出、拖拽位置记忆、气泡层级。
6. **查看器最后切**：纹理上限、帧率、转场重排。
7. **每步保留 feature flag**，出问题单点回滚。

工作量粗估：接口抽取 0.5 天、TextureView 后端 1.5–2 天、四处联调与回归 1–1.5 天，合计 **3–4 天**（不含老机型真机验证）。

---

## 5. 替代方案对比（供决策）

| 方案 | 穿透时 alpha | 动画 | 应用内穿透 | 工作量 | 风险 |
|---|---|---|---|---|---|
| A. 保持现状（GLSurfaceView + 分层摊薄） | 0.54 | ✅ | ❌ | 0 | 无 |
| B. 全量换 TextureView | **0.79** | ✅ | ❌ | 3–4 天 | 中高（见矩阵） |
| C. 仅悬浮窗换 TextureView | **0.79** | ✅ | ❌ | 2 天 | 中（隔离在最小窗口） |
| D. 穿透开启时换"抓帧静帧"Image | **0.79** | ❌ 静止 | ✅（顺带解决） | 0.5 天 | 低，但穿透时小人不动，桌面宠物体验受损 |
| E. 自适应：静止时静帧、有交互时切回 GL | 0.79 / 0.54 混合 | 部分 | 部分 | 1.5 天 | 中（状态机复杂度） |

**建议**：若"穿透时小人必须是活的"是硬要求 → 走 **C（仅悬浮窗）**，把风险锁在最小的那扇窗里；若可以接受穿透时静止 → **D** 性价比最高（顺带把注释里承诺了但没实现的"应用内穿透"一起做掉）。
