/**
 * Live2D 查看器渲染逻辑（运行于 WebView，页面由拦截器以 https://live2d.local 提供）。
 *
 * 职责：
 *  - 加载 Cubism 4 模型（pixi-live2d-display + Cubism Core）
 *  - 手势：单指拖动平移 / 双指捏合缩放 / 双击复位 / 点击随机触摸动作
 *  - 视线跟随指针（model.focus）
 *  - 待机动作自动循环（idle 组由运行时自动接管）
 *  - 模型加载完成后渲染 1024x1024 缩略图并回传宿主保存
 *  - 帧率治理：无交互且仅待机时降到 30fps，交互/非待机动作期间恢复满帧（省电）
 *  - 陪伴模式：每 N 毫秒随机触发一个非待机动作并通知宿主配语音
 *
 * 与宿主通信：
 *  - JS → Kotlin：window.AndroidBridge.postMessage(JSON)
 *    {t:'ready'} {t:'loaded'} {t:'error',message} {t:'motionStart',group}
 *    {t:'motionFinish'} {t:'thumb',data} {t:'log',message}
 *    {t:'tap',group}（点击模型） {t:'companion',group}（陪伴模式定时触发）
 *  - Kotlin → JS：window.__viewer.playMotion / playExpression / resetView / setPaused / setCompanion
 */
(function () {
    'use strict';

    var bridge = window.AndroidBridge || null;
    function post(msg) {
        try {
            if (bridge) bridge.postMessage(JSON.stringify(msg));
        } catch (e) { /* 桥不可用时静默 */ }
    }
    function postError(message) { post({ t: 'error', message: String(message) }); }
    function postLog(message) { post({ t: 'log', message: String(message) }); }

    window.addEventListener('error', function (e) {
        postError(e.message || '脚本错误');
    });
    window.addEventListener('unhandledrejection', function (e) {
        var reason = e.reason;
        postError((reason && (reason.message || reason)) || '未处理的异步错误');
    });

    var params = new URLSearchParams(location.search);
    var modelId = params.get('model') || '';
    var BASE = 'https://live2d.local/models/' + encodeURIComponent(modelId) + '/';

    var app = null;
    var model = null;
    var fitScale = 1;
    var userScale = 1, userX = 0, userY = 0;
    var MIN_SCALE = 0.25, MAX_SCALE = 8;
    var transformAnim = null;   // 统一的变换动画（复位/旋转重适配共用）
    var appliedW = 0, appliedH = 0; // 已应用的画布尺寸（修正旋转竞态用）
    var thumbSent = false;

    /** 点击模型时的候选动作组（碧蓝航线触摸系动作） */
    var TOUCH_GROUPS = ['touch_body', 'touch_head', 'touch_special', 'main_1', 'main_2', 'main_3'];

    // 手势指针表（模块级：陪伴调度器需要感知"用户正在交互"以顺延触发）
    var pointers = new Map();

    // ---------- 帧率治理 ----------
    // 空闲（仅待机动作在循环）时把 ticker 限到 30fps，显著降低 GPU/CPU 占用；
    // 用户手势、手动/陪伴动作播放期间恢复满帧，视觉无感知切换。
    var IDLE_FPS = 30;
    var BOOST_MS = 2500;            // 最近一次交互后的满帧保持时长
    var lastBoostAt = 0;
    var lastMotionStartAt = 0;      // 动作开始/结束时间戳（判"非待机动作在播"）
    var lastMotionFinishAt = 0;

    function boostFrames() { lastBoostAt = performance.now(); }

    function currentMotionGroup() {
        try {
            return model.internalModel.motionManager.state.currentGroup || null;
        } catch (e) { return null; }
    }

    function idleGroupNames() {
        try {
            var g = model.internalModel.motionManager.groups;
            return (g && g.idle && g.idle.length) ? g.idle : ['idle'];
        } catch (e) { return ['idle']; }
    }

    function applyFpsGovernor() {
        if (!app) return;
        var g = currentMotionGroup();
        // 动作已结束（finish 晚于 start）且没有新动作接续 → 不算"在播"，
        // 覆盖无 idle 组的模型：播完一个动作后不应永远保持满帧
        var running = lastMotionStartAt > lastMotionFinishAt;
        var active = (performance.now() - lastBoostAt < BOOST_MS) ||
            (running && g && idleGroupNames().indexOf(g) < 0);
        var want = active ? 0 : IDLE_FPS; // 0 = 不限帧
        if (app.ticker.maxFPS !== want) app.ticker.maxFPS = want;
    }

    // ---------- 陪伴模式调度 ----------
    // 计时挂在 ticker 上（deltaMS 累计）：宿主暂停 ticker（退后台）即自动暂停，
    // 恢复后从剩余时长继续；间隔变更由宿主重推 setCompanion 即时生效。
    var companion = { on: false, intervalMs: 180000, acc: 0, lastGroup: null };

    /** 模型实际拥有的动作组（≥1 条动作） */
    function availableGroups() {
        try {
            var defs = model.internalModel.motionManager.definitions;
            return Object.keys(defs).filter(function (g) { return defs[g] && defs[g].length; });
        } catch (e) { return []; }
    }

    /** 陪伴触发候选：全部非待机组；连发避免与上次同组 */
    function pickCompanionGroup() {
        var groups = availableGroups().filter(function (g) { return g !== 'idle'; });
        if (!groups.length) return null;
        if (groups.length > 1 && companion.lastGroup) {
            var filtered = groups.filter(function (g) { return g !== companion.lastGroup; });
            if (filtered.length) groups = filtered;
        }
        return groups[Math.floor(Math.random() * groups.length)];
    }

    function triggerCompanionMotion() {
        var g = pickCompanionGroup();
        if (!g) return;
        companion.lastGroup = g;
        var count = 1;
        try { count = model.internalModel.motionManager.definitions[g].length || 1; } catch (e) { /* 保守取 0 号 */ }
        var idx = count > 1 ? Math.floor(Math.random() * count) : 0;
        post({ t: 'companion', group: g });
        playMotion(g, idx);
    }

    function companionTick() {
        if (!companion.on || !model) return;
        companion.acc += app.ticker.deltaMS;
        if (companion.acc < companion.intervalMs) return;
        // 用户正在拖动/捏合：本次顺延到抬手后触发，避免打断手势
        if (pointers.size > 0) { companion.acc = companion.intervalMs; return; }
        companion.acc = 0;
        triggerCompanionMotion();
        kickIdleIfNeeded(false);
    }

    // ---------- 陪伴模式待机接管 ----------
    // 运行时在动作队列清空时会自动请求待机（startRandomMotion(groups.idle, IDLE)），
    // 但 groups.idle 名字与模型定义不匹配时是 no-op → 动作播完模型定格。
    // 陪伴模式下显式兜底：队列空闲 500ms 后按"运行时配置组 → idle → 首个组"接续待机。
    var lastIdleKickAt = 0;

    /** 随机播一条待机动作；找不到可用待机组返回 false */
    function startIdleMotion() {
        if (!model) return false;
        try {
            var mg = model.internalModel.motionManager;
            var candidates = [];
            try { if (mg.groups && mg.groups.idle) candidates.push(mg.groups.idle); } catch (e) { /* groups 不可读则跳过 */ }
            candidates.push('idle');
            availableGroups().forEach(function (g) { candidates.push(g); });
            for (var i = 0; i < candidates.length; i++) {
                if (groupMotionCount(candidates[i]) > 0) {
                    // priority 1 = IDLE：不抢占更高优先级动作，仅填充空闲
                    mg.startRandomMotion(candidates[i], 1).catch(function () { /* 待机失败静默 */ });
                    return true;
                }
            }
        } catch (e) {
            postLog('待机接续失败: ' + ((e && e.message) || e));
        }
        return false;
    }

    /**
     * 待机循环守护：队列空闲时保证待机在播。
     * 节流 2s + 动作结束后 500ms 内不介入（先让运行时自行接续，避免双发）。
     */
    function kickIdleIfNeeded(force) {
        if (!companion.on || !model) return;
        var now = performance.now();
        if (!force && now - lastIdleKickAt < 2000) return;
        if (now - lastMotionFinishAt < 500) return;
        try {
            if (!model.internalModel.motionManager.isFinished()) return; // 动作/待机播放中
        } catch (e) { return; }
        lastIdleKickAt = now;
        startIdleMotion();
    }

    function clamp(v, lo, hi) { return v < lo ? lo : (v > hi ? hi : v); }

    function applyTransform() {
        if (!model) return;
        model.scale.set(fitScale * userScale);
        model.position.set(
            window.innerWidth / 2 + userX,
            window.innerHeight * 0.52 + userY
        );
    }

    function computeFit() {
        if (!model) return;
        var W = window.innerWidth, H = window.innerHeight;
        // ⚠️ 必须用 internalModel 的原始尺寸：model.width 是含当前 scale 的
        // 实时宽度，用它重算适配会在每次 resize 时复利放大（旋转比例失真根因）
        var mw = (model.internalModel && model.internalModel.width) || model.width;
        var mh = (model.internalModel && model.internalModel.height) || model.height;
        fitScale = Math.min((W * 0.92) / mw, (H * 0.86) / mh);
        fitScale = Math.min(fitScale, 4); // 防止贴图极小的模型被放大过头
    }

    function cancelTransformAnim() {
        if (transformAnim) {
            cancelAnimationFrame(transformAnim.raf);
            transformAnim = null;
        }
    }

    /**
     * 把模型的缩放/位置平滑过渡到目标值（easeOutCubic）。
     * 复位与旋转重适配共用，动画期间手势会取消它。
     */
    function animateTransformTo(targetScale, tx, ty, duration) {
        if (!model) return;
        cancelTransformAnim();
        var from = { s: model.scale.x, x: model.x, y: model.y };
        var t0 = performance.now();
        function step(now) {
            if (!model) return;
            var p = clamp((now - t0) / duration, 0, 1);
            var ease = 1 - Math.pow(1 - p, 3);
            model.scale.set(from.s + (targetScale - from.s) * ease);
            model.position.set(
                from.x + (tx - from.x) * ease,
                from.y + (ty - from.y) * ease
            );
            if (p < 1) {
                transformAnim.raf = requestAnimationFrame(step);
            } else {
                transformAnim = null;
            }
        }
        transformAnim = { raf: requestAnimationFrame(step) };
    }

    /** 按当前 user* 参数平滑滑向适配布局 */
    function animateToCurrentFit(duration) {
        animateTransformTo(
            fitScale * userScale,
            window.innerWidth / 2 + userX,
            window.innerHeight * 0.52 + userY,
            duration
        );
    }

    /** 双击复位：回正缩放与平移，平滑过渡 */
    function animateReset() {
        if (!model) return;
        userScale = 1;
        userX = 0;
        userY = 0;
        animateToCurrentFit(280);
    }

    /**
     * 视口尺寸确定后的重布局：
     *  - 手动兜底 renderer.resize（修正 PIXI resizeTo 在旋转过程中的竞态，
     *    旋转中 resizeTo 可能拿到中间尺寸，画布比例随之失真）
     *  - 旧方向下的平移量已无意义，回正 user 变换
     *  - 以动画滑向新方向的居中适配，避免跳变
     */
    function layoutForSize(animate) {
        var W = window.innerWidth, H = window.innerHeight;
        if (W <= 0 || H <= 0 || !app) return;
        if (W !== appliedW || H !== appliedH) {
            appliedW = W;
            appliedH = H;
            app.renderer.resize(W, H);
        }
        if (!model) return;
        computeFit();
        userScale = 1;
        userX = 0;
        userY = 0;
        if (animate) {
            animateToCurrentFit(340);
        } else {
            cancelTransformAnim();
            applyTransform();
        }
    }

    /** 动作组在模型里的条数（拿不到定义表时返回 1，保持旧行为不拦截） */
    function groupMotionCount(group) {
        try {
            var defs = model.internalModel.motionManager.definitions[group];
            return defs ? defs.length : 0;
        } catch (e) { return 1; }
    }

    function playMotion(group, index) {
        if (!model) return;
        // 组不存在时静默跳过且不发 motionStart，宿主"动作"选中态才不会悬挂
        if (groupMotionCount(group) === 0) {
            postLog('动作组不存在，跳过: ' + group);
            return;
        }
        boostFrames();
        // 宿主发起的动作无需等运行时事件即可判"在播"（事件兜底失效时帧率治理仍正确）
        lastMotionStartAt = performance.now();
        post({ t: 'motionStart', group: group });
        try {
            // MotionPriority.FORCE = 3：手动指定的动作必须立即抢占待机动作
            Promise.resolve(model.motion(group, index, 3)).catch(function (e) {
                postError('动作播放失败: ' + ((e && e.message) || e));
            });
        } catch (e) {
            postError('动作播放失败: ' + ((e && e.message) || e));
        }
    }

    function playExpression(index) {
        if (!model) return;
        boostFrames();
        try {
            model.expression(index);
        } catch (e) {
            postError('表情切换失败: ' + ((e && e.message) || e));
        }
    }

    // ---------- 手势 ----------

    // 单击/双击消歧：单击动作延迟触发，双击窗口内第二指落下则取消。
    // 窗口(300ms)必须小于延迟(320ms)，否则单击已发射、双击复位仍会撞上触摸动作。
    var TAP_DOUBLE_MS = 300, TAP_SINGLE_DELAY = 320;
    var singleTapTimer = 0;

    // 触摸上报节流：拖拽等连续手势没有新的按下，1.5s 心跳上报避免宿主 10s 计时误收界面
    var lastTouchPostAt = 0;
    function maybePostTouch(force) {
        var now = performance.now();
        if (force || now - lastTouchPostAt > 1500) {
            lastTouchPostAt = now;
            post({ t: 'touch' });
        }
    }

    function cancelPendingTap() {
        if (singleTapTimer) {
            clearTimeout(singleTapTimer);
            singleTapTimer = 0;
        }
    }

    /** 点击互动：从模型实际拥有的触摸系组里随机；全缺时退而求其次用任意非待机组 */
    function fireTapInteraction() {
        var all = availableGroups();
        var pool = TOUCH_GROUPS.filter(function (g) { return all.indexOf(g) >= 0; });
        if (!pool.length) pool = all.filter(function (g) { return g !== 'idle'; });
        if (!pool.length) return;
        var g = pool[Math.floor(Math.random() * pool.length)];
        post({ t: 'tap', group: g });
        playMotion(g, 0);
    }

    function bindGestures(canvas) {
        var pinchDist = 0;
        var drag = null;        // {x, y, moved, time}
        var lastTapAt = 0;

        canvas.addEventListener('pointerdown', function (e) {
            try { canvas.setPointerCapture(e.pointerId); } catch (err) { /* 忽略 */ }
            cancelPendingTap(); // 新手势开始，上一拍的单击判定作废
            boostFrames();
            // 通知宿主"用户触摸了画布"：陪伴沉浸模式下用于恢复界面显示
            maybePostTouch(true);
            pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
            if (pointers.size === 1) {
                drag = { x: e.clientX, y: e.clientY, moved: false, time: performance.now() };
            } else if (pointers.size === 2) {
                var pts = Array.from(pointers.values());
                pinchDist = Math.hypot(pts[0].x - pts[1].x, pts[0].y - pts[1].y);
                drag = null; // 进入捏合即取消拖动
            }
        });

        canvas.addEventListener('pointermove', function (e) {
            // 视线跟随：无论是否按住，指针移动都会吸引模型视线
            boostFrames();
            maybePostTouch(false); // 长按拖拽期间心跳上报，维持宿主界面显示
            if (model) {
                try { model.focus(e.clientX, e.clientY); } catch (err) { /* 忽略 */ }
            }
            if (!pointers.has(e.pointerId)) return;
            var prev = pointers.get(e.pointerId);
            var dx = e.clientX - prev.x, dy = e.clientY - prev.y;
            pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });

            if (pointers.size === 1 && drag) {
                if (!drag.moved && Math.hypot(e.clientX - drag.x, e.clientY - drag.y) > 8) {
                    drag.moved = true;
                }
                if (drag.moved) {
                    userX += dx;
                    userY += dy;
                    cancelTransformAnim();
                    applyTransform();
                }
            } else if (pointers.size === 2 && pinchDist > 0) {
                var pts = Array.from(pointers.values());
                var dist = Math.hypot(pts[0].x - pts[1].x, pts[0].y - pts[1].y);
                if (dist > 0 && pinchDist > 0) {
                    userScale = clamp(userScale * (dist / pinchDist), MIN_SCALE, MAX_SCALE);
                    cancelTransformAnim();
                    applyTransform();
                }
                pinchDist = dist;
            }
        });

        function onUp(e) {
            if (!pointers.has(e.pointerId)) return;
            pointers.delete(e.pointerId);
            if (pointers.size < 2) pinchDist = 0;
            if (drag && pointers.size === 0) {
                var now = performance.now();
                if (!drag.moved && now - drag.time < 600) {
                    if (now - lastTapAt < TAP_DOUBLE_MS) {
                        // 双击：复位视角（并取消已在等待的单击动作）
                        lastTapAt = 0;
                        cancelPendingTap();
                        animateReset();
                    } else {
                        lastTapAt = now;
                        // 单击：延迟触发随机触摸动作，等待双击窗口关闭
                        cancelPendingTap();
                        singleTapTimer = setTimeout(function () {
                            singleTapTimer = 0;
                            fireTapInteraction();
                        }, TAP_SINGLE_DELAY);
                    }
                }
                drag = null;
            }
        }
        canvas.addEventListener('pointerup', onUp);
        canvas.addEventListener('pointercancel', onUp);
    }

    // ---------- 缩略图 ----------

    /**
     * 渲染 1024x1024 缩略图并回传。
     * 通过独立 RenderTexture 渲染整个舞台，尺寸与屏幕无关，结果稳定。
     */
    function captureThumb() {
        if (!model || !app || thumbSent) return;
        try {
            var SIZE = 1024, PAD = 48;
            var b = model.getLocalBounds();
            var bw = Math.max(b.width, 1), bh = Math.max(b.height, 1);
            var scale = Math.min((SIZE - PAD * 2) / bw, (SIZE - PAD * 2) / bh);

            var saved = { x: model.x, y: model.y, sx: model.scale.x, sy: model.scale.y, ax: model.anchor.x, ay: model.anchor.y, a: model.alpha };
            model.alpha = 1;
            model.anchor.set(0, 0);
            model.scale.set(scale);
            model.position.set(
                SIZE / 2 - (b.x + bw / 2) * scale,
                SIZE / 2 - (b.y + bh / 2) * scale
            );

            var rt = PIXI.RenderTexture.create({ width: SIZE, height: SIZE, resolution: 1 });
            app.renderer.render(app.stage, { renderTexture: rt, clear: true });
            var canvas = app.renderer.plugins.extract.canvas(rt);
            var data = canvas.toDataURL('image/jpeg', 0.82);
            rt.destroy(true);

            model.anchor.set(saved.ax, saved.ay);
            model.scale.set(saved.sx, saved.sy);
            model.position.set(saved.x, saved.y);
            model.alpha = saved.a;

            thumbSent = true;
            post({ t: 'thumb', data: data });
        } catch (e) {
            postLog('缩略图生成失败: ' + ((e && e.message) || e));
        }
    }

    // ---------- 启动 ----------

    function setupApp() {
        var canvas = document.getElementById('stage');
        app = new PIXI.Application({
            view: canvas,
            resizeTo: window,
            backgroundAlpha: 0,
            antialias: true,
            // 独占性能模式对单模型查看无必要，低功耗偏好让系统优先省电 GPU
            powerPreference: 'low-power',
            resolution: Math.min(window.devicePixelRatio || 1, 2),
            autoDensity: true
        });
        appliedW = window.innerWidth;
        appliedH = window.innerHeight;
        bindGestures(canvas);
        // 每帧驱动：帧率治理 + 陪伴调度 + 待机守护（ticker 停止时全部自动冻结）
        app.ticker.add(function () {
            applyFpsGovernor();
            companionTick();
            kickIdleIfNeeded(false);
        });
        document.addEventListener('visibilitychange', function () {
            if (!app) return;
            if (document.hidden) {
                app.ticker.stop();
            } else {
                app.ticker.start();
            }
        });
        // 视口尺寸变化（旋转横竖屏等）：等尺寸稳定后再重布局，
        // Android WebView 旋转过程中 innerWidth/innerHeight 会分多帧变化，
        // 直接用单次 resize 回调会拿到中间尺寸导致画布比例错误。
        function onViewportChanged() {
            var lastW = window.innerWidth, lastH = window.innerHeight;
            var stableFrames = 0;
            function check() {
                var W = window.innerWidth, H = window.innerHeight;
                if (W === lastW && H === lastH) {
                    stableFrames++;
                    if (stableFrames >= 3) {
                        layoutForSize(true);
                        return;
                    }
                } else {
                    lastW = W;
                    lastH = H;
                    stableFrames = 0;
                }
                requestAnimationFrame(check);
            }
            requestAnimationFrame(check);
        }
        window.addEventListener('resize', onViewportChanged);
        window.addEventListener('orientationchange', onViewportChanged);
        if (window.visualViewport) {
            window.visualViewport.addEventListener('resize', onViewportChanged);
        }
        if (window.screen && screen.orientation && screen.orientation.addEventListener) {
            screen.orientation.addEventListener('change', onViewportChanged);
        }
    }

    function loadModel() {
        if (!window.Live2DCubismCore) {
            postError('Cubism Core 加载失败');
            return;
        }
        if (!window.PIXI || !PIXI.live2d) {
            postError('Live2D 运行时加载失败');
            return;
        }
        var manifestUrl = BASE + '__l2d_manifest__.json';
        fetch(manifestUrl)
            .then(function (res) {
                if (!res.ok) throw new Error('读取模型清单失败 HTTP ' + res.status);
                return res.json();
            })
            .then(function (manifest) {
                if (!manifest || !manifest.model3) throw new Error('模型清单缺少 model3 字段');
                var rel = String(manifest.model3).split('/').map(encodeURIComponent).join('/');
                return PIXI.live2d.Live2DModel.from(BASE + rel, {
                    autoInteract: true,
                    autoUpdate: true
                });
            })
            .then(function (m) {
                model = m;
                // 锚点居中：position 直接表示模型中心的屏幕位置
                m.anchor.set(0.5, 0.5);
                app.stage.addChild(m);
                computeFit();
                applyTransform();

                // 动作结束通知宿主（非待机动作播完 → 宿主复位控制条选中态）；
                // start/finish 时间戳同时供帧率治理判断"非待机动作是否在播"
                try {
                    var mg = m.internalModel.motionManager;
                    var markStart = function () { lastMotionStartAt = performance.now(); };
                    var markFinish = function () {
                        lastMotionFinishAt = performance.now();
                        // 陪伴模式：动作结束后稍候即检查待机是否接上（运行时接不上则显式兜底）
                        setTimeout(function () { kickIdleIfNeeded(true); }, 560);
                    };
                    mg.on('motionFinish', function () {
                        markFinish();
                        post({ t: 'motionFinish' });
                    });
                    // motionStart 事件由 MotionState 发出，MotionManager 是否转发依版本而异 → 两处都挂
                    mg.on('motionStart', markStart);
                    if (mg.state && mg.state.on) mg.state.on('motionStart', markStart);
                } catch (err) { /* 事件不可用不影响渲染（帧率治理退化为仅交互升频） */ }

                // 入场淡入
                m.alpha = 0;
                var t0 = performance.now();
                (function fade() {
                    if (!model) return;
                    var p = clamp((performance.now() - t0) / 400, 0, 1);
                    model.alpha = p;
                    if (p < 1) requestAnimationFrame(fade);
                })();

                post({ t: 'loaded' });
                // 等待待机动作把模型摆出姿势后再截缩略图
                setTimeout(captureThumb, 1400);
                setTimeout(function () {
                    var hint = document.getElementById('hint');
                    if (hint) hint.classList.add('hide');
                }, 8000);
            })
            .catch(function (e) {
                postError((e && e.message) || '模型加载失败');
            });
    }

    // 宿主遥控入口
    window.__viewer = {
        playMotion: playMotion,
        playExpression: playExpression,
        resetView: function () { animateReset(); },
        setPaused: function (paused) {
            if (!app) return;
            if (paused) { app.ticker.stop(); } else { app.ticker.start(); }
        },
        /** 陪伴模式开关与触发间隔（毫秒）；间隔变更即时生效（重新计帧） */
        setCompanion: function (enabled, intervalMs) {
            companion.on = !!enabled;
            companion.intervalMs = Math.max(5000, intervalMs | 0);
            companion.acc = 0;
            // 开启即接管待机：静态定格的模型立刻进入待机循环
            if (companion.on) {
                lastIdleKickAt = 0;
                kickIdleIfNeeded(true);
            }
        }
    };

    post({ t: 'ready' });
    try {
        setupApp();
        loadModel();
    } catch (e) {
        postError('初始化失败: ' + ((e && e.message) || e));
    }
})();
