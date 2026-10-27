/**
 * Live2D 查看器渲染逻辑（运行于 WebView，页面由拦截器以 https://live2d.local 提供）。
 *
 * 职责：
 *  - 加载 Cubism 4 模型（pixi-live2d-display + Cubism Core）
 *  - 手势：单指拖动平移 / 双指捏合缩放 / 双击复位 / 点击随机触摸动作
 *  - 视线跟随指针（model.focus）
 *  - 待机动作自动循环（idle 组由运行时自动接管）
 *  - 模型加载完成后渲染 1024x1024 缩略图并回传宿主保存
 *  - 帧率治理：无交互且仅待机时 GPU 渲染 2:1 抽帧（约 30fps，参数更新仍 60Hz），
 *    交互/非待机动作期间恢复逐帧渲染（省电且帧距恒定无 setTimeout 抖动）
 *  - 待机无缝循环：idle 淡入缩短为 400ms + 剩余 400ms 时预排队下一条交叉淡化
 *  - 陪伴模式：每 N 毫秒随机触发一个非待机动作并通知宿主配语音
 *
 * 与宿主通信：
 *  - JS → Kotlin：window.AndroidBridge.postMessage(JSON)
 *    {t:'ready'} {t:'loaded'} {t:'error',message} {t:'motionStart',group}
 *    {t:'motionFinish'} {t:'thumb',data} {t:'log',message}
 *    {t:'tap',group}（点击模型） {t:'companion',group}（陪伴模式定时触发）
 *  - Kotlin → JS：window.__viewer.playMotion / playExpression / resetView / setPaused /
 *    setCompanion / setGyro（陀螺仪开关与灵敏度） / setGyroTilt（倾斜信号流）
 *
 * 陀螺仪注视/视差：宿主监听旋转矢量传感器，把"屏幕系重力倾斜"归一化信号
 * （sx, sy ∈ [-1,1]，右倾/后仰为正）经 setGyroTilt 持续下发；渲染端指数平滑后
 * 驱动 model.focus 视线与轻微同向位移（贴纸随重力下垂隐喻）。指针交互期间
 * 陀螺仪让位（指针持有视线 1.5s），倾斜变化期间短时满帧渲染，静止即回落抽帧。
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

    // ---------- 待机循环无缝化（必须在任何模型构造之前生效） ----------
    // 库默认 idleMotionFadingDuration=2000：待机动作的淡入/淡出被烙成 2s（AL 模型
    // motion3.json 均不声明 Fade 时间，走该默认值）。上一条待机结束→参数冻结一帧→
    // 新待机以 2s 缓慢淡入接管，前 ~0.5s 权重趋零 → 观感即"卡一下再慢慢苏醒"。
    // 缩短为 400ms：结束姿态迅速交棒；同时给预排队交叉淡化提供合理淡出时长。
    // 注意淡入时长在动作"创建时"烙进缓存，此覆盖必须先于 Live2DModel.from 执行。
    try {
        if (window.PIXI && PIXI.live2d && PIXI.live2d.config) {
            PIXI.live2d.config.idleMotionFadingDuration = 400;
        }
    } catch (e) { /* config 不可达则退回库默认 */ }

    var fitScale = 1;
    var userScale = 1, userX = 0, userY = 0;
    var MIN_SCALE = 0.25, MAX_SCALE = 8;
    var transformAnim = null;   // 统一的变换动画（复位/旋转重适配共用）
    var appliedW = 0, appliedH = 0; // 已应用的画布尺寸（修正旋转竞态用）
    var thumbSent = false;

    /** 非待机组首帧预载条数（资源包直读模型单组可能上百条，全量解析会卡首帧） */
    var PRELOAD_PER_GROUP = 6;

    /** 点击模型时的候选动作组（碧蓝航线触摸系动作） */
    var TOUCH_GROUPS = ['touch_body', 'touch_head', 'touch_special', 'main_1', 'main_2', 'main_3'];

    // 手势指针表（模块级：陪伴调度器需要感知"用户正在交互"以顺延触发）
    var pointers = new Map();

    // ---------- 帧率治理 v2（渲染抽帧） ----------
    // 不用 PIXI ticker.maxFPS 限帧：其实现是 rAF + setTimeout 补时，WebView 的
    // setTimeout 有 4~15ms 调度漂移 → 30fps 限帧帧距忽长忽短，动作衔接处呈
    // 周期性顿挫。改为：ticker 全速 rAF（参数更新/视线跟随 60Hz 均匀采样），
    // GPU 渲染按 2:1 抽帧（渲染是功耗大头，省一半），帧距恒定 33.3ms 零抖动。
    var BOOST_MS = 2500;            // 最近一次交互后的满帧渲染保持时长
    var boostUntil = 0;             // 满帧渲染保持到的时刻（交互 2500ms / 陀螺仪倾斜 600ms）
    var lastMotionStartAt = 0;      // 动作开始/结束时间戳（判"非待机动作在播"）
    var lastMotionFinishAt = 0;
    var renderEvery2Frames = false; // true = 空闲态，渲染抽帧
    var frameParity = 0;

    /** 交互后保持满帧渲染；ms 缺省 = 完整 BOOST_MS，陀螺仪等持续信号源用短时长 */
    function boostFrames(ms) {
        boostUntil = performance.now() + (ms || BOOST_MS);
    }

    function currentMotionGroup() {
        try {
            return model.internalModel.motionManager.state.currentGroup || null;
        } catch (e) { return null; }
    }

    /**
     * 待机组名解析。
     *
     * pixi-live2d-display 的 Cubism4MotionManager 把待机组名**硬编码**为 `groups = {idle: 'Idle'}`，
     * 而模型 model3.json 里的组名通常是 `idle`（碧蓝的资源包直读产物也是小写）。
     * 运行时在动作队列清空后会用 `startRandomMotion('Idle', IDLE)` 自动接待机 —— 名字对不上
     * 时是静默 no-op，待机就此定格。这里按「运行时声明名 → 定义表里大小写不敏感匹配」解析，
     * 返回实际存在的组名列表（首个优先）。
     */
    function idleGroupNames() {
        var out = [];
        try {
            var mg = model.internalModel.motionManager;
            if (mg.groups && mg.groups.idle) out.push(mg.groups.idle);
            var defs = mg.definitions || {};
            Object.keys(defs).forEach(function (g) {
                if (g !== out[0] && g.toLowerCase() === 'idle' && out.indexOf(g) < 0) out.push(g);
            });
        } catch (e) { /* 忽略，退回默认名 */ }
        if (!out.length) out.push('idle');
        return out;
    }

    /** 判定某个动作组是否属于待机组（大小写不敏感） */
    function isIdleGroup(group) {
        if (!group) return false;
        return String(group).toLowerCase() === 'idle';
    }

    function applyFpsGovernor() {
        if (!app) return;
        var g = currentMotionGroup();
        // 动作已结束（finish 晚于 start）且没有新动作接续 → 不算"在播"，
        // 覆盖无 idle 组的模型：播完一个动作后不应永远保持满帧渲染
        var running = lastMotionStartAt > lastMotionFinishAt;
        var active = (performance.now() < boostUntil) ||
            (running && g && !isIdleGroup(g));
        renderEvery2Frames = !active;
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

    // ---------- 陀螺仪注视/视差 ----------
    // 宿主以 ~16Hz 下发"屏幕系重力倾斜"归一化信号（右倾/后仰为正），
    // 此处指数平滑（τ=120ms）后每帧驱动 model.focus 视线与轻微同向位移。
    // 模型隐喻：贴在屏幕玻璃上的存在随重力轻微下垂——位移与视线同向。
    var gyro = {
        enabled: false,
        gain: 1,
        tx: 0, ty: 0,           // 宿主最新目标（-1..1）
        sx: 0, sy: 0,           // 平滑后的当前值（驱动视线/位移）
        ox: 0, oy: 0            // 当前视差位移（px，随 sx/sy 派生）
    };
    var GYRO_SMOOTH_TAU_MS = 120;   // 指数平滑时间常数：响应跟手且无抖动
    var GYRO_BOOST_MS = 600;        // 倾斜变化期间的满帧保持（静止后迅速回落抽帧省电）
    var GYRO_POINTER_OWNER_MS = 1500; // 指针移动后接管视线的时长
    var lastPointerFocusAt = -1e9;  // 最近一次指针视线跟随时间戳

    /** 宿主下发开关与灵敏度（percent 40..220，缺省 100） */
    function setGyro(enabled, gainPercent) {
        gyro.enabled = !!enabled;
        var percent = typeof gainPercent === 'number' ? gainPercent : 100;
        gyro.gain = clamp(percent / 100, 0.4, 2.2);
        // 关闭即目标归零：平滑回中，不突跳
        if (!gyro.enabled) {
            gyro.tx = 0;
            gyro.ty = 0;
        }
    }

    /** 宿主倾斜信号流（-1..1）；未启用时忽略 */
    function setGyroTilt(x, y) {
        if (!gyro.enabled || typeof x !== 'number' || typeof y !== 'number') return;
        gyro.tx = clamp(x, -1, 1);
        gyro.ty = clamp(y, -1, 1);
    }

    /** 每帧驱动：平滑 → 视线（指针优先）→ 视差位移 → 变化期间短时满帧 */
    function gyroTick(deltaMS) {
        if (!model) return;
        var now = performance.now();
        // 指数平滑（帧率归一）
        var k = 1 - Math.exp(-deltaMS / GYRO_SMOOTH_TAU_MS);
        gyro.sx += (gyro.tx - gyro.sx) * k;
        gyro.sy += (gyro.ty - gyro.sy) * k;
        var moving = Math.abs(gyro.tx - gyro.sx) + Math.abs(gyro.ty - gyro.sy) > 0.01;
        if (moving) boostFrames(GYRO_BOOST_MS);

        // 视线：指针交互期间让位（避免陀螺仪与指针互相拉扯）
        var pointerOwns = pointers.size > 0 || (now - lastPointerFocusAt < GYRO_POINTER_OWNER_MS);
        if (gyro.enabled && !pointerOwns) {
            // 焦点绕画面上部（脸部区域）随倾斜偏移，纵向幅度略小
            try {
                model.focus(
                    window.innerWidth / 2 + gyro.sx * window.innerWidth * 0.42,
                    window.innerHeight * 0.42 + gyro.sy * window.innerHeight * 0.38
                );
            } catch (err) { /* focus 不可用时仅视差降级 */ }
        }

        // 视差位移：满偏 ≈ 画面短边 1.2%（纵向再收 40%，只做"轻微位移"）
        var amp = 0.012 * Math.min(window.innerWidth, window.innerHeight);
        gyro.ox = gyro.sx * amp;
        gyro.oy = gyro.sy * amp * 0.6;
        // 变换动画（复位/旋转重适配）期间不直接写 position（动画目标已含视差），
        // 否则会与 animateTransformTo 的逐帧插值互相覆盖
        if (!transformAnim) {
            applyTransform();
        }
    }

    // ---------- 陪伴模式待机接管 ----------
    // 运行时在动作队列清空时会自动请求待机（startRandomMotion(groups.idle, IDLE)），
    // 但 groups.idle 名字与模型定义不匹配时是 no-op → 动作播完模型定格。
    // 陪伴模式下显式兜底：宽限 250ms 后按"运行时配置组 → idle → 首个组"接续待机。
    var lastIdleKickAt = 0;

    /** 随机播一条待机动作；找不到可用待机组返回 false */
    function startIdleMotion() {
        if (!model) return false;
        try {
            var mg = model.internalModel.motionManager;
            var candidates = idleGroupNames().concat(availableGroups());
            for (var i = 0; i < candidates.length; i++) {
                if (groupMotionCount(candidates[i]) > 0) {
                    // priority 1 = IDLE：不抢占更高优先级动作，仅填充空闲
                    Promise.resolve(mg.startRandomMotion(candidates[i], 1))
                        .catch(function () { /* 待机失败静默 */ });
                    return true;
                }
            }
        } catch (e) {
            postLog('待机接续失败: ' + ((e && e.message) || e));
        }
        return false;
    }

    /**
     * 待机循环守护：队列空闲时保证待机在播（无缝衔接）。
     * 运行时在每个 update 帧同步重启待机（同帧无间隙）；仅在运行时接不上
     * （groups.idle 组名不匹配等）时兜底介入。判定顺序：
     *   动作结束宽限 250ms（让运行时先接）→ 队列必须空闲 →
     *   运行时未预订待机（reserved* 已置位 = 待机文件加载中，绝不双发）。
     */
    function kickIdleIfNeeded(force) {
        if (!companion.on || !model) return;
        var now = performance.now();
        if (!force && now - lastIdleKickAt < 1000) return;
        if (now - lastMotionFinishAt < 250) return;
        try {
            var mg = model.internalModel.motionManager;
            if (!mg.isFinished()) return;
            var st = mg.state;
            if (st) {
                if (st.currentGroup !== undefined) return;
                if (st.reservedGroup !== undefined || st.reservedIdleGroup !== undefined) return;
            }
        } catch (e) { return; }
        lastIdleKickAt = now;
        startIdleMotion();
    }

    // ---------- 待机预排队（消除结束空档帧） ----------
    // 运行时重启待机要等上一条完全结束（IDLE 优先级门控要求 currentPriority=0），
    // 中间存在参数冻结的空档帧。预排队在剩余 <400ms 时以 NORMAL(2) 优先级提前
    // 启动同组下一条（2>1 越过门控；startRandomMotion 天然排除在播条目）→
    // Cubism 队列对新条目自动给现行条目 setFadeOut → 新旧 400ms 重叠交叉淡化。
    // 单动作待机组无可切条目，退回"短淡入 + 循环姿态首尾连续"路径。
    var idlePrequeued = false;

    function prequeueNextIdle() {
        if (!model || idlePrequeued || lastMotionStartAt === 0) return;
        var mg, group, index, motion;
        try {
            mg = model.internalModel.motionManager;
            group = mg.state.currentGroup;
            index = mg.state.currentIndex;
            if (group === undefined || index === undefined) return;
            motion = mg.motionGroups[group] && mg.motionGroups[group][index];
        } catch (e) { return; }
        if (!motion) return;
        // 仅对"正在播的确实是待机组"预排队（非待机动作交给结束后的接续逻辑）
        var idleNames = idleGroupNames();
        if (idleNames.indexOf(group) < 0) return;
        var duration = -1;
        try { duration = motion.getDuration(); } catch (e) { return; }
        if (!(duration > 0)) return;
        var remaining = duration - (performance.now() - lastMotionStartAt) / 1000;
        if (!(remaining > 0) || remaining > 0.4) return;
        if (groupMotionCount(group) < 2) return; // 单条待机：无可切，短淡入兜底
        idlePrequeued = true;
        try {
            mg.startRandomMotion(group, 2).catch(function () { idlePrequeued = false; });
        } catch (e) {
            idlePrequeued = false;
        }
    }

    function clamp(v, lo, hi) { return v < lo ? lo : (v > hi ? hi : v); }

    function applyTransform() {
        if (!model) return;
        model.scale.set(fitScale * userScale);
        // 陀螺仪视差位移叠加在用户平移之上（贴纸随重力轻微下垂）
        model.position.set(
            window.innerWidth / 2 + userX + gyro.ox,
            window.innerHeight * 0.52 + userY + gyro.oy
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

    /** 按当前 user* 参数平滑滑向适配布局（目标含陀螺仪视差，动画结束无跳变） */
    function animateToCurrentFit(duration) {
        animateTransformTo(
            fitScale * userScale,
            window.innerWidth / 2 + userX + gyro.ox,
            window.innerHeight * 0.52 + userY + gyro.oy,
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
            // 视线跟随：无论是否按住，指针移动都会吸引模型视线；
            // 同时记录接管时间戳，1.5s 内陀螺仪注视让位（避免两者互相拉扯）
            boostFrames();
            lastPointerFocusAt = performance.now();
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
        // 摘掉 Application 自带的整帧渲染，改为治理器控制的抽帧渲染
        //（LOW 优先级 = 在动作/参数更新监听之后执行，抽帧不影响参数采样）
        app.ticker.remove(app.render, app);
        app.ticker.add(
            function () {
                if (!renderEvery2Frames || (frameParity ^= 1) === 0) {
                    app.render();
                }
            },
            undefined,
            PIXI.UPDATE_PRIORITY.LOW
        );
        // 每帧驱动：帧率治理 + 陪伴调度 + 陀螺仪注视 + 待机守护/预排队（ticker 停止时全部自动冻结）
        app.ticker.add(function () {
            applyFpsGovernor();
            companionTick();
            gyroTick(app.ticker.deltaMS);
            kickIdleIfNeeded(false);
            prequeueNextIdle();
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

                // ---------- 待机循环接线（关键） ----------
                // 运行时（pixi-live2d-display 0.4.0）把待机组名**硬编码**为 `{idle:'Idle'}`：
                // 动作队列清空后会 `startRandomMotion('Idle', IDLE)` 自动接待机，名字对不上
                // 就是静默 no-op → 待机播完永久定格。这里把运行时的待机组名接到模型实际
                // 拥有的待机组（通常是 `idle`），自动重启才真正生效。
                var idleNames = idleGroupNames();
                try {
                    var mgInit = m.internalModel.motionManager;
                    if (mgInit.groups && idleNames.length) mgInit.groups.idle = idleNames[0];
                } catch (err) { /* 接线失败退回兜底逻辑 */ }
                // 待机动作原地循环：运行时 `CubismMotion.parse` 只把 JSON 的 Loop 写进
                // `_motionData.loop`，而 doUpdateParameters 判断的是 `_isLoop` 成员
                //（setIsLoop 从未被调用）→ 所有动作都被当成一次性播完。待机组必须显式
                // setIsLoop(true)：时间到 duration 时运行时会把 startTime 重置为当前时刻，
                // 动作原地续播，队列永不清空 → 没有"播完→空档→重新淡入"的卡顿。
                var applyIdleLoop = function (group, index) {
                    if (!isIdleGroup(group)) return;
                    try {
                        var arr = mgInit.motionGroups[group];
                        var motion = arr && arr[index];
                        if (motion && motion.setIsLoop && !motion.isLoop()) motion.setIsLoop(true);
                    } catch (err) { /* 单条失败不影响渲染 */ }
                };
                // motionLoaded 事件在 loadMotion 完成时触发 → 待机组每条加载完立刻标记循环
                try {
                    mgInit.on('motionLoaded', function (g, i, motion) {
                        if (!isIdleGroup(g) || !motion || !motion.setIsLoop) return;
                        try { if (!motion.isLoop()) motion.setIsLoop(true); } catch (err) { /* 忽略 */ }
                    });
                } catch (err) { /* 事件不可用则靠预载阶段补标 */ }

                // 动作结束通知宿主（非待机动作播完 → 宿主复位控制条选中态）；
                // start/finish 时间戳同时供帧率治理判断"非待机动作是否在播"
                try {
                    var mg = m.internalModel.motionManager;
                    var markStart = function () {
                        lastMotionStartAt = performance.now();
                        idlePrequeued = false; // 新动作已接棒，待机预排队周期复位
                    };
                    var markFinish = function () {
                        lastMotionFinishAt = performance.now();
                        // 陪伴模式：动作结束后短暂宽限即检查待机是否接上
                        //（运行时同帧自动接；接不上（组名不匹配）时 280ms 兜底，肉眼无定格）
                        setTimeout(function () { kickIdleIfNeeded(true); }, 280);
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
                // 预载动作（本地虚拟域、几 KB~几十 KB 级）：运行时默认只预载待机组，
                // 陪伴兜底选中的其他组首播会有一帧加载间隙——预载后全程无缝。
                // 资源包直读产物一个模型可能有 100+ 条动作（chaijun_6 = 113 条），
                // 一次性全解析会占几十 MB 并把首帧卡住，因此分两档：
                //  1) 待机组全量 + 其余组前 6 条 —— 点击互动与待机衔接立刻可用；
                //  2) 入场动画结束后分批（每批 8 条）后台补齐，用户无感。
                try {
                    var mgPreload = m.internalModel.motionManager;
                    var defs = mgPreload.definitions;
                    var pendingPreload = [];
                    Object.keys(defs).forEach(function (g) {
                        if (!defs[g] || !defs[g].length) return;
                        for (var i = 0; i < defs[g].length; i++) {
                            if (isIdleGroup(g) || i < PRELOAD_PER_GROUP) {
                                try {
                                    // 待机组：加载完成后立刻标记为循环，避免首轮播完定格
                                    Promise.resolve(mgPreload.loadMotion(g, i)).then(function () {
                                        applyIdleLoop(g, i);
                                    }).catch(function () { /* 忽略 */ });
                                } catch (err) { /* 单条失败不影响 */ }
                            } else {
                                pendingPreload.push([g, i]);
                            }
                        }
                    });
                    if (pendingPreload.length) {
                        var cursor = 0;
                        var step = function () {
                            if (!model || cursor >= pendingPreload.length) return;
                            var slice = pendingPreload.slice(cursor, cursor + 8);
                            cursor += 8;
                            for (var k = 0; k < slice.length; k++) {
                                (function (g, i) {
                                    try {
                                        Promise.resolve(mgPreload.loadMotion(g, i)).then(function () {
                                            applyIdleLoop(g, i);
                                        }).catch(function () { /* 忽略 */ });
                                    } catch (err) { /* 忽略 */ }
                                })(slice[k][0], slice[k][1]);
                            }
                            setTimeout(step, 120);
                        };
                        setTimeout(step, 2500);
                    }
                } catch (err) { /* 预载不可用不影响渲染 */ }
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
        },
        /** 陀螺仪注视/视差：开关 + 灵敏度（百分比 40..220，缺省 100） */
        setGyro: setGyro,
        /** 陀螺仪倾斜信号流（-1..1，右倾/后仰为正；宿主 ~16Hz 持续下发） */
        setGyroTilt: setGyroTilt
    };

    post({ t: 'ready' });
    try {
        setupApp();
        loadModel();
    } catch (e) {
        postError('初始化失败: ' + ((e && e.message) || e));
    }
})();
