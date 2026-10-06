/**
 * Live2D 查看器渲染逻辑（运行于 WebView，页面由拦截器以 https://live2d.local 提供）。
 *
 * 职责：
 *  - 加载 Cubism 4 模型（pixi-live2d-display + Cubism Core）
 *  - 手势：单指拖动平移 / 双指捏合缩放 / 双击复位 / 点击随机触摸动作
 *  - 视线跟随指针（model.focus）
 *  - 待机动作自动循环（idle 组由运行时自动接管）
 *  - 模型加载完成后渲染 1024x1024 缩略图并回传宿主保存
 *
 * 与宿主通信：
 *  - JS → Kotlin：window.AndroidBridge.postMessage(JSON)
 *    {t:'ready'} {t:'loaded'} {t:'error',message} {t:'motionStart',group}
 *    {t:'motionFinish'} {t:'thumb',data} {t:'log',message}
 *  - Kotlin → JS：window.__viewer.playMotion / playExpression / resetView / setPaused
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

    function playMotion(group, index) {
        if (!model) return;
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
        try {
            model.expression(index);
        } catch (e) {
            postError('表情切换失败: ' + ((e && e.message) || e));
        }
    }

    // ---------- 手势 ----------

    function bindGestures(canvas) {
        var pointers = new Map();
        var pinchDist = 0;
        var drag = null;        // {x, y, moved, time}
        var lastTapAt = 0;

        canvas.addEventListener('pointerdown', function (e) {
            try { canvas.setPointerCapture(e.pointerId); } catch (err) { /* 忽略 */ }
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
                    if (now - lastTapAt < 320) {
                        // 双击：复位视角
                        lastTapAt = 0;
                        animateReset();
                    } else {
                        lastTapAt = now;
                        // 单击：随机播放一个触摸系动作（无 HitAreas，自行挑组），
                        // 并上报宿主以触发对应互动语音
                        var g = TOUCH_GROUPS[Math.floor(Math.random() * TOUCH_GROUPS.length)];
                        post({ t: 'tap', group: g });
                        playMotion(g, 0);
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
            resolution: Math.min(window.devicePixelRatio || 1, 2),
            autoDensity: true
        });
        appliedW = window.innerWidth;
        appliedH = window.innerHeight;
        bindGestures(canvas);
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

                // 动作结束通知宿主（非待机动作播完 → 宿主复位控制条选中态）
                try {
                    m.internalModel.motionManager.on('motionFinish', function () {
                        post({ t: 'motionFinish' });
                    });
                } catch (err) { /* 事件不可用不影响渲染 */ }

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
