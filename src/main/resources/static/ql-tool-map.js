/**
 * 青龙调试工具 - 输送线 Canvas 拓扑图
 *
 * 查看：滚轮缩放、空白区域拖拽平移、选中节点或轨道
 * 编辑：拖动节点、双击节点修改编码、右键删除节点
 * 连线：从一个节点拖到另一个节点创建有向轨道；选中轨道后可通过工具栏/Delete/右键删除
 */
(function () {
    'use strict';

    const CFG = {
        // 旧布局按约 104×62 的步距保存；迁移时会放大坐标间距，卡片保持可读但不再相互遮挡。
        nodeW: 124,
        nodeH: 80,
        grid: 28,
        snapGrid: 8,
        fontFamily: "'JetBrains Mono','Cascadia Code',Consolas,monospace",
        colors: {
            idle:     { border: '#22d3ee', rail: '#38bdf8', text: '#e0f2fe', muted: '#7dd3fc', card: '#101a33', label: '空闲' },
            occupied: { border: '#10b981', rail: '#10b981', text: '#d1fae5', muted: '#6ee7b7', card: '#0d2c2c', label: '有货' },
            task:     { border: '#f59e0b', rail: '#f59e0b', text: '#fef3c7', muted: '#fcd34d', card: '#312315', label: '输送中' },
            alarm:    { border: '#fb4f64', rail: '#fb4f64', text: '#fff1f2', muted: '#fda4af', card: '#381728', label: '报警' },
            offline:  { border: '#64748b', rail: '#64748b', text: '#cbd5e1', muted: '#94a3b8', card: '#172033', label: '离线' },
            unknown:  { border: '#818cf8', rail: '#818cf8', text: '#e0e7ff', muted: '#a5b4fc', card: '#181a3a', label: '待匹配' }
        },
        rail: '#38bdf8',
        railUnderlay: 'rgba(30,64,175,.28)',
        selected: '#facc15',
        hover: '#22d3ee',
        draft: '#a78bfa',
        background: '#090f20'
    };

    let canvas, ctx, containerEl, tooltipEl;
    let nodes = [];
    let labels = [];
    let links = [];
    let nodeStates = {};
    let deviceConnected = false;
    let transform = { x: 40, y: 40, scale: 1 };
    let mode = 'view';
    let dragging = false, dragTarget = null, dragMoved = false, panning = false;
    let dragStart = { x: 0, y: 0 }, dragNodeStart = { x: 0, y: 0 }, panStart = { x: 0, y: 0 };
    let hoverNode = null, hoverLink = -1, selectedNode = null, selectedLink = -1, linkDraft = null;
    let onLayoutChanged = null, onSelectionChanged = null, onViewChanged = null, onTaskDelete = null, onNodeClick = null;
    // 阶段二：轨道流动动画（按点位实时状态推断货物流向，仅在有货流动时运行）
    let animRaf = null, animPhase = 0, hasActiveFlow = false, activeLinkSet = new Set(), lastFrameTs = 0;
    function prefersReducedMotion() {
        return window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    }

    window.qlMap = {
        init,
        destroy,
        setLayout,
        updateStates,
        setConnectionState,
        setMode,
        getMode: () => mode,
        fitToView: autoFit,
        zoomBy,
        getZoom: () => transform.scale,
        focusNode,
        findPaths,
        repairLinks,
        deleteSelectedLink,
        hasSelectedLink: () => selectedLink >= 0 && selectedLink < links.length,
        onChanged: fn => { onLayoutChanged = fn; },
        onSelectionChanged: fn => { onSelectionChanged = fn; },
        onViewChanged: fn => { onViewChanged = fn; },
        onTaskDelete: fn => { onTaskDelete = fn; },
        onNodeClick: fn => { onNodeClick = fn; },
        clearSelection: () => clearSelection(),
        getLayout
    };

    function init(container) {
        destroy();
        containerEl = container;
        container.style.position = 'relative';
        container.innerHTML = '';
        canvas = document.createElement('canvas');
        canvas.tabIndex = 0;
        canvas.style.cssText = 'width:100%;height:100%;display:block;outline:none;cursor:grab;';
        container.appendChild(canvas);

        tooltipEl = document.createElement('div');
        tooltipEl.style.cssText = 'position:absolute;display:none;z-index:20;max-width:240px;padding:8px 10px;border:1px solid rgba(148,163,184,.38);border-radius:8px;background:rgba(15,23,42,.94);box-shadow:0 10px 24px rgba(15,23,42,.2);color:#f8fafc;font:11px/1.55 ' + CFG.fontFamily + ';white-space:pre-line;pointer-events:none;';
        container.appendChild(tooltipEl);

        ctx = canvas.getContext('2d');
        resize();
        canvas.addEventListener('wheel', onWheel, { passive: false });
        canvas.addEventListener('mousedown', onMouseDown);
        canvas.addEventListener('mousemove', onMouseMove);
        canvas.addEventListener('mouseup', onMouseUp);
        canvas.addEventListener('mouseleave', onMouseLeave);
        canvas.addEventListener('contextmenu', onContext);
        canvas.addEventListener('dblclick', onDblClick);
        canvas.addEventListener('keydown', onKeyDown);
        window.addEventListener('resize', resize);
        document.addEventListener('visibilitychange', onVisibility);
        draw();
    }

    function destroy() {
        stopAnim();
        hasActiveFlow = false;
        activeLinkSet = new Set();
        document.removeEventListener('visibilitychange', onVisibility);
        if (canvas) {
            window.removeEventListener('resize', resize);
            canvas.remove();
        }
        canvas = null;
        ctx = null;
        containerEl = null;
        tooltipEl = null;
    }

    function setMode(nextMode) {
        mode = nextMode === 'edit' || nextMode === 'link' ? nextMode : 'view';
        linkDraft = null;
        if (canvas) canvas.style.cursor = mode === 'view' ? 'grab' : 'default';
        draw();
    }

    function setLayout(layoutData, options) {
        nodes = [];
        labels = [];
        links = [];
        clearSelection(false);
        if (!Array.isArray(layoutData) || layoutData.length === 0) {
            draw();
            return;
        }

        const repairEmptyLinks = !!(options && options.repairEmptyLinks);
        const migrateLegacy = !!(options && options.migrateLegacy);
        const coordinateLayout = layoutData.some(item => Number.isFinite(Number(item.x)) && Number.isFinite(Number(item.y)));
        if (coordinateLayout) {
            layoutData.forEach((item, index) => {
                if (!item) return;
                if (isLayoutLabel(item)) {
                    labels.push({
                        id: 'label-' + index,
                        text: String(item.text || item.label || item.code || ''),
                        x: Number.isFinite(Number(item.x)) ? Number(item.x) : index * (CFG.nodeW + 48),
                        y: Number.isFinite(Number(item.y)) ? Number(item.y) : 0
                    });
                    return;
                }
                if (item.code === undefined || item.code === null) return;
                nodes.push({
                    id: String(item.code),
                    code: String(item.code),
                    x: Number.isFinite(Number(item.x)) ? Number(item.x) : index * (CFG.nodeW + 48),
                    y: Number.isFinite(Number(item.y)) ? Number(item.y) : 0
                });
            });
            if (migrateLegacy) compactLegacyCoordinates();
            layoutData.forEach(item => {
                if (!item || isLayoutLabel(item) || !Array.isArray(item.links)) return;
                item.links.forEach(to => addLink(String(item.code), String(to)));
            });
            // 历史版本会把没有画出的轨道保存为 links: []。仅在迁移阶段按坐标恢复，
            // 新版布局通过 schema 标记，避免用户主动删光轨道后又被自动补回。
            if (links.length === 0 && repairEmptyLinks) restoreLegacyLinks();
        } else {
            layoutData.forEach((item, index) => {
                if (!item) return;
                if (isLayoutLabel(item)) {
                    labels.push({
                        id: 'label-' + index,
                        text: String(item.text || item.label || item.code || ''),
                        x: Number(item.c || 0) * (CFG.nodeW + 18),
                        y: Number(item.r || 0) * (CFG.nodeH + 16)
                    });
                    return;
                }
                if (item.code === undefined || item.code === null) return;
                nodes.push({
                    id: String(item.code),
                    code: String(item.code),
                    x: Number(item.c || 0) * (CFG.nodeW + 18),
                    y: Number(item.r || 0) * (CFG.nodeH + 16)
                });
            });
            layoutData.forEach(item => {
                if (!item || isLayoutLabel(item) || item.code === undefined || item.code === null) return;
                // 未标方向时，连接同一行最近右邻和同一列最近下邻；显式方向允许跨越空单元格。
                const dirs = Array.isArray(item.dirs) && item.dirs.length ? item.dirs : ['right', 'down'];
                dirs.forEach(dir => {
                    const target = findNeighbor(item, dir, layoutData);
                    if (target) addLink(String(item.code), String(target.code));
                });
            });
        }
        autoReadableView();
        recomputeActiveLinks();
        draw();
    }

    function getLayout() {
        const linkMap = {};
        links.forEach(link => {
            if (!linkMap[link.from]) linkMap[link.from] = [];
            linkMap[link.from].push(link.to);
        });
        const pointLayout = nodes.map(node => ({
            kind: 'point',
            code: node.code,
            x: node.x,
            y: node.y,
            links: linkMap[node.code] || []
        }));
        const labelLayout = labels.map(label => ({
            kind: 'label',
            text: label.text,
            x: label.x,
            y: label.y
        }));
        return pointLayout.concat(labelLayout);
    }

    function updateStates(states) {
        nodeStates = {};
        (states || []).forEach(state => { nodeStates[normCode(state.pointCode)] = state; });
        if (selectedNode) notifySelection();
        recomputeActiveLinks();
        draw();
    }

    function setConnectionState(connected) {
        deviceConnected = !!connected;
        if (!deviceConnected) {
            nodeStates = {};
            hideTooltip();
            clearSelection(false);
            activeLinkSet = new Set();
            hasActiveFlow = false;
            stopAnim();
        }
        draw();
    }

    function draw() {
        if (!canvas || !ctx) return;
        const dpr = window.devicePixelRatio || 1;
        const width = canvas.width / dpr;
        const height = canvas.height / dpr;
        ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
        ctx.clearRect(0, 0, width, height);
        const bg = ctx.createLinearGradient(0, 0, width, height);
        bg.addColorStop(0, '#080e1d');
        bg.addColorStop(.55, '#0c1428');
        bg.addColorStop(1, '#080d1b');
        ctx.fillStyle = bg;
        ctx.fillRect(0, 0, width, height);

        ctx.save();
        ctx.translate(transform.x, transform.y);
        ctx.scale(transform.scale, transform.scale);
        drawGrid(width, height);
        links.forEach((link, index) => drawLink(link, index));
        if (linkDraft) drawLinkDraft();
        labels.forEach(drawLabel);
        nodes.forEach(drawNode);
        links.forEach((link, index) => drawLinkArrowOverlay(link, index));
        ctx.restore();

        drawModeHint(width, height);
    }

    // ---------- 阶段二：按点位实时状态推断货物流向 ----------
    // 规则：某轨道 A→B（沿输送方向），若 A 占位且有任务、B 也有任务，
    // 说明货正从 A 往下一个点位 B 走，该轨道流动。方向即轨道自身方向 A→B。
    function isActiveLink(link) {
        const from = getFullState(link.from);
        const to = getFullState(link.to);
        return !!(from && from.occupied && from.hasTask && to && to.hasTask);
    }
    function recomputeActiveLinks() {
        activeLinkSet = new Set();
        if (deviceConnected) {
            links.forEach((link, index) => { if (isActiveLink(link)) activeLinkSet.add(index); });
        }
        hasActiveFlow = activeLinkSet.size > 0;
        ensureAnim();
    }
    function ensureAnim() {
        if (hasActiveFlow && !animRaf && !prefersReducedMotion() && !document.hidden) {
            animRaf = requestAnimationFrame(tick);
        }
    }
    function stopAnim() {
        if (animRaf) { cancelAnimationFrame(animRaf); animRaf = null; }
    }
    // 30fps 上限，肉眼无差别但重绘开销减半
    function tick(ts) {
        if (!hasActiveFlow) { animRaf = null; return; }
        animRaf = requestAnimationFrame(tick);
        if (ts - lastFrameTs < 33) return;
        lastFrameTs = ts;
        animPhase = performance.now() / 1000;
        draw();
    }
    function onVisibility() {
        if (document.hidden) stopAnim(); else ensureAnim();
    }
    // 沿轨道方向（A→B）的流动光带
    function drawFlow(route) {
        ctx.save();
        ctx.strokeStyle = 'rgba(251,191,36,.95)';
        ctx.lineWidth = Math.max(2.2, 1.8 / transform.scale);
        ctx.lineCap = 'round';
        ctx.lineJoin = 'round';
        ctx.shadowColor = 'rgba(245,158,11,.7)';
        ctx.shadowBlur = 6;
        ctx.setLineDash([9, 13]);
        ctx.lineDashOffset = -((animPhase * 34) % 22);
        ctx.beginPath();
        ctx.moveTo(route[0].x, route[0].y);
        for (let i = 1; i < route.length; i++) ctx.lineTo(route[i].x, route[i].y);
        ctx.stroke();
        ctx.restore();
    }
    // 沿轨道 A→B 移动的发光货点
    function drawFlowDot(route) {
        let total = 0;
        const segs = [];
        for (let i = 1; i < route.length; i++) {
            const a = route[i - 1], b = route[i];
            const len = Math.hypot(b.x - a.x, b.y - a.y);
            segs.push({ a, b, len });
            total += len;
        }
        if (total < 1) return;
        const pos = (animPhase * 58) % total;
        let acc = 0, point = route[route.length - 1];
        for (const seg of segs) {
            if (pos <= acc + seg.len) {
                const t = seg.len ? (pos - acc) / seg.len : 0;
                point = { x: seg.a.x + (seg.b.x - seg.a.x) * t, y: seg.a.y + (seg.b.y - seg.a.y) * t };
                break;
            }
            acc += seg.len;
        }
        ctx.save();
        ctx.fillStyle = '#fde68a';
        ctx.shadowColor = 'rgba(245,158,11,.95)';
        ctx.shadowBlur = 10;
        ctx.beginPath();
        ctx.arc(point.x, point.y, Math.max(2.8, 2.2 / transform.scale), 0, Math.PI * 2);
        ctx.fill();
        ctx.restore();
    }

    function drawGrid(width, height) {
        const minX = Math.floor(-transform.x / transform.scale / CFG.grid) * CFG.grid - CFG.grid;
        const minY = Math.floor(-transform.y / transform.scale / CFG.grid) * CFG.grid - CFG.grid;
        const maxX = Math.ceil((width - transform.x) / transform.scale / CFG.grid) * CFG.grid + CFG.grid;
        const maxY = Math.ceil((height - transform.y) / transform.scale / CFG.grid) * CFG.grid + CFG.grid;
        ctx.save();
        // 主网格线（每 4 格一条），营造工程坐标纸的空间参照
        const major = CFG.grid * 4;
        const majMinX = Math.floor(minX / major) * major;
        const majMinY = Math.floor(minY / major) * major;
        ctx.strokeStyle = 'rgba(56,189,248,.06)';
        ctx.lineWidth = Math.max(.6, 1 / transform.scale);
        ctx.beginPath();
        for (let x = majMinX; x <= maxX; x += major) { ctx.moveTo(x, minY); ctx.lineTo(x, maxY); }
        for (let y = majMinY; y <= maxY; y += major) { ctx.moveTo(minX, y); ctx.lineTo(maxX, y); }
        ctx.stroke();
        // 次网格点阵
        ctx.fillStyle = 'rgba(99,130,246,.18)';
        const radius = Math.max(.65, 1 / transform.scale);
        for (let x = minX; x <= maxX; x += CFG.grid) {
            for (let y = minY; y <= maxY; y += CFG.grid) {
                ctx.beginPath(); ctx.arc(x, y, radius, 0, Math.PI * 2); ctx.fill();
            }
        }
        ctx.restore();
    }

    function drawLink(link, index) {
        const route = getLinkRoute(link, index);
        if (!route) return;
        const selected = index === selectedLink;
        const hovered = index === hoverLink;
        const active = activeLinkSet.has(index);
        // 空闲轨道退到背景（更暗），有货流向的轨道用橙色打底并叠加流光
        const baseColor = selected ? CFG.selected
            : (hovered ? CFG.hover
                : (active ? 'rgba(245,158,11,.55)' : 'rgba(56,189,248,.42)'));

        drawRoute(route, CFG.railUnderlay, Math.max(5, 2.4 / transform.scale));
        drawRoute(route, baseColor, Math.max(selected || hovered ? 2.8 : 1.5, (selected || hovered ? 2 : 1.2) / transform.scale));
        if (active && !selected) {
            if (prefersReducedMotion()) {
                drawRoute(route, 'rgba(251,191,36,.9)', Math.max(2.2, 1.6 / transform.scale));
            } else {
                drawFlow(route);
                drawFlowDot(route);
            }
        }
        if (selected) {
            drawRoute(route, 'rgba(255,255,255,.8)', .8);
        }
    }

    function drawLinkArrowOverlay(link, index) {
        const route = getLinkRoute(link, index);
        if (!route) return;
        const color = index === selectedLink ? CFG.selected : (index === hoverLink ? CFG.hover : CFG.rail);
        drawRouteArrow(route, color);
    }

    function drawRoute(route, color, width) {
        ctx.save();
        ctx.strokeStyle = color;
        ctx.lineWidth = width;
        ctx.lineCap = 'round';
        ctx.lineJoin = 'round';
        ctx.beginPath();
        ctx.moveTo(route[0].x, route[0].y);
        for (let i = 1; i < route.length; i++) ctx.lineTo(route[i].x, route[i].y);
        ctx.stroke();
        ctx.restore();
    }

    function drawRouteArrow(route, color) {
        let best = null;
        for (let i = 1; i < route.length; i++) {
            const start = route[i - 1], end = route[i];
            const length = Math.hypot(end.x - start.x, end.y - start.y);
            if (!best || length > best.length) best = { start, end, length };
        }
        if (!best || best.length < 2) return;
        const ratio = 0.5;
        const x = best.start.x + (best.end.x - best.start.x) * ratio;
        const y = best.start.y + (best.end.y - best.start.y) * ratio;
        const angle = Math.atan2(best.end.y - best.start.y, best.end.x - best.start.x);
        const size = Math.max(4.8 / transform.scale, Math.min(7.5, Math.max(3.5, best.length * 0.32)));
        ctx.save();
        ctx.fillStyle = color;
        ctx.beginPath();
        ctx.moveTo(x + size * Math.cos(angle), y + size * Math.sin(angle));
        ctx.lineTo(x - size * Math.cos(angle - Math.PI / 6), y - size * Math.sin(angle - Math.PI / 6));
        ctx.lineTo(x - size * Math.cos(angle + Math.PI / 6), y - size * Math.sin(angle + Math.PI / 6));
        ctx.closePath();
        ctx.fill();
        ctx.restore();
    }

    function drawLinkDraft() {
        const from = linkDraft.from;
        const start = { x: from.x + CFG.nodeW / 2, y: from.y + CFG.nodeH / 2 };
        const end = { x: linkDraft.toX, y: linkDraft.toY };
        const middle = { x: (start.x + end.x) / 2, y: start.y };
        const route = [start, middle, { x: middle.x, y: end.y }, end];
        ctx.save();
        ctx.setLineDash([7, 5]);
        drawRoute(route, CFG.draft, 2.5);
        ctx.restore();
    }

    function drawNode(node) {
        const state = getFullState(node.code);
        const colors = CFG.colors[state.colorKey];
        const selected = node === selectedNode;
        const hovered = node === hoverNode;
        const x = node.x, y = node.y, w = CFG.nodeW, h = CFG.nodeH;
        const unknown = state.colorKey === 'unknown';
        const offline = state.offline;
        // 语义分级：只有"有事"的点位跳出来，空闲点位退到背景
        const idle = !state.hasTask && !state.alarm && !state.occupied && !offline && !unknown;
        const emphasized = state.alarm || state.hasTask;
        const accent = state.alarm ? '#fb4f64'
            : (state.hasTask ? '#f59e0b'
                : (state.occupied ? '#10b981'
                    : (offline ? '#3f4a5f' : (unknown ? '#8b5cf6' : '#3a4a66'))));
        const borderColor = selected ? CFG.selected : (hovered ? CFG.hover : accent);

        if (transform.scale < 0.4) {
            drawOverviewNodeState(node, state, colors, x, y, w, h);
            return;
        }

        // 卡片底色：空闲/离线更沉，有事的更实
        ctx.save();
        const background = ctx.createLinearGradient(x, y, x, y + h);
        if (emphasized) {
            background.addColorStop(0, 'rgba(28,41,68,.98)');
            background.addColorStop(1, 'rgba(12,20,38,.98)');
        } else if (state.occupied) {
            // 有货站位整卡微染绿，扫全线一眼看出货在哪
            background.addColorStop(0, 'rgba(13,46,43,.96)');
            background.addColorStop(1, 'rgba(9,26,27,.97)');
        } else {
            background.addColorStop(0, 'rgba(18,27,47,.90)');
            background.addColorStop(1, 'rgba(11,17,32,.92)');
        }
        ctx.fillStyle = background;
        roundRect(ctx, x + 1, y + 1, w - 2, h - 2, 9);
        ctx.fill();
        ctx.strokeStyle = borderColor;
        ctx.lineWidth = selected ? 2.6 : (emphasized ? 1.9 : Math.max(1, 1 / Math.max(transform.scale, .25)));
        ctx.shadowColor = borderColor;
        ctx.shadowBlur = selected || hovered ? 9 : (emphasized ? 8 : 0);
        ctx.stroke();
        ctx.shadowBlur = 0;
        // 左侧 accent 竖条：空闲时很淡
        ctx.fillStyle = idle ? 'rgba(90,110,150,.32)' : borderColor;
        roundRect(ctx, x + 1, y + 9, 4, h - 18, 2);
        ctx.fill();
        ctx.restore();

        // 顶部：点位编码（空闲降为中等亮度，不刷白全屏）+ 状态 pill（空闲不显示，降噪）
        ctx.save();
        ctx.textBaseline = 'middle';
        ctx.fillStyle = idle ? '#9fb2d0' : '#f8fafc';
        ctx.font = `850 ${readableFontSize(12.5, 10, 20)}px ${CFG.fontFamily}`;
        ctx.textAlign = 'left';
        ctx.fillText(ellipsis(node.code, 11), x + 10, y + 15);
        if (!idle && !offline) {
            const pointStatus = getPointStatusVisual(state);
            const statusLabel = ellipsis(state.statusLabel, 5);
            ctx.font = `800 ${readableFontSize(8.5, 7, 14)}px ${CFG.fontFamily}`;
            const statusWidth = Math.min(55, Math.max(34, ctx.measureText(statusLabel).width + 18));
            drawMonitorPill(x + w - statusWidth - 7, y + 7, statusWidth, 17, '● ' + statusLabel, pointStatus);
        }
        ctx.restore();

        // 中间：有任务时橙色醒目；无任务时极淡一行小字，不抢注意力
        ctx.save();
        const taskX = x + 7, taskY = y + 29, taskW = w - 14, taskH = 22;
        if (state.hasTask) {
            ctx.fillStyle = 'rgba(245,158,11,.24)';
            ctx.strokeStyle = 'rgba(251,191,36,.75)';
            ctx.lineWidth = 1;
            roundRect(ctx, taskX, taskY, taskW, taskH, 6);
            ctx.fill();
            ctx.stroke();
            ctx.font = `850 ${readableFontSize(10, 8, 16)}px ${CFG.fontFamily}`;
            ctx.textAlign = 'center';
            ctx.textBaseline = 'middle';
            ctx.fillStyle = '#fde68a';
            ctx.fillText(`任务 ${state.taskNo}`, x + w / 2 - 8, taskY + taskH / 2);
        } else {
            ctx.font = `700 ${readableFontSize(9, 7.5, 15)}px ${CFG.fontFamily}`;
            ctx.textAlign = 'center';
            ctx.textBaseline = 'middle';
            ctx.fillStyle = 'rgba(148,163,184,.45)';
            ctx.fillText('无任务', x + w / 2, taskY + taskH / 2);
        }
        ctx.restore();

        // 底部：降噪——只在异常/占位/离线时显示，正常空闲不画
        ctx.save();
        ctx.font = `800 ${readableFontSize(8.3, 6.8, 14)}px ${CFG.fontFamily}`;
        if (offline || unknown) {
            drawMonitorPill(x + 7, y + h - 23, w - 14, 17,
                offline ? '● 设备离线' : '待匹配',
                monitorVisual('#94a3b8', 'rgba(100,116,139,.14)', 'rgba(148,163,184,.4)'));
        } else {
            // 占位料位：有货=绿色实心箱子+标签（跳出），空位=暗轮廓（安静）
            drawOccupancy(x + 15, y + h - 14, state.occupied);
            if (state.occupied) {
                ctx.fillStyle = '#6ee7b7';
                ctx.font = `800 ${readableFontSize(8.3, 6.8, 14)}px ${CFG.fontFamily}`;
                ctx.textAlign = 'left';
                ctx.textBaseline = 'middle';
                ctx.fillText('有货', x + 27, y + h - 13);
            }
            if (state.alarm) {
                drawMonitorPill(x + w - 59, y + h - 23, 52, 17, '● 报警',
                    monitorVisual('#fecdd3', 'rgba(239,68,68,.26)', 'rgba(251,113,133,.78)'));
            }
        }
        ctx.restore();

        if (state.hasTask) drawTaskDeleteButton(x + w - 25, y + 31);
    }

    function drawLabel(label) {
        if (!label || !label.text) return;
        ctx.save();
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillStyle = '#cbd5e1';
        ctx.font = `750 ${readableFontSize(13, 11, 23)}px ${CFG.fontFamily}`;
        ctx.shadowColor = 'rgba(15,23,42,.9)';
        ctx.shadowBlur = 5;
        ctx.fillText(ellipsis(label.text, 18), label.x + CFG.nodeW / 2, label.y + CFG.nodeH / 2);
        ctx.restore();
    }

    function drawOverviewNodeState(node, state, colors, x, y, w, h) {
        const invScale = 1 / Math.max(transform.scale, 0.01);
        const codeSize = Math.min(32, Math.max(16, 7.4 * invScale));
        const stateSize = Math.min(23, Math.max(12, 5.2 * invScale));
        const unknown = state.colorKey === 'unknown';
        const offline = state.offline;
        const idle = !state.hasTask && !state.alarm && !state.occupied && !offline && !unknown;
        const emphasized = state.alarm || state.hasTask;
        const accent = state.alarm ? '#fb4f64'
            : (state.hasTask ? '#f59e0b'
                : (state.occupied ? '#10b981'
                    : (offline ? '#3f4a5f' : (unknown ? '#8b5cf6' : '#3a4a66'))));
        ctx.save();
        // 空闲缩略卡沉入背景，有事的更实、带辉光
        ctx.fillStyle = emphasized ? 'rgba(20,30,52,.96)' : 'rgba(13,20,38,.86)';
        roundRect(ctx, x + 1, y + 1, w - 2, h - 2, 9);
        ctx.fill();
        ctx.strokeStyle = accent;
        ctx.lineWidth = emphasized ? Math.max(2, 1.6 / Math.max(transform.scale, .18)) : Math.max(1, 1 / Math.max(transform.scale, .18));
        if (emphasized) { ctx.shadowColor = accent; ctx.shadowBlur = 12; }
        ctx.stroke();
        ctx.shadowBlur = 0;
        ctx.textBaseline = 'middle';
        ctx.textAlign = 'center';
        // 空闲：仅居中显示编号（安静）
        ctx.fillStyle = idle ? '#8ba0c2' : '#f8fafc';
        ctx.font = `850 ${codeSize}px ${CFG.fontFamily}`;
        ctx.fillText(ellipsis(node.code, 10), x + w / 2, emphasized ? y + 14 : y + h / 2);
        if (emphasized) {
            ctx.font = `800 ${stateSize}px ${CFG.fontFamily}`;
            const taskText = state.alarm ? '⚠ 报警' : `#${state.taskNo}`;
            ctx.fillStyle = state.alarm ? 'rgba(239,68,68,.28)' : 'rgba(245,158,11,.28)';
            roundRect(ctx, x + 8, y + h - stateSize - 14, w - 16, stateSize + 8, 5);
            ctx.fill();
            ctx.fillStyle = state.alarm ? '#fecdd3' : '#fde68a';
            ctx.fillText(ellipsis(taskText, 10), x + w / 2, y + h - stateSize / 2 - 10);
        } else if (state.occupied) {
            // 占位：右上角一个绿点，安静但可辨
            ctx.fillStyle = '#34d399';
            ctx.beginPath();
            ctx.arc(x + w - 11, y + 12, Math.max(3, 1.6 / Math.max(transform.scale, .18)), 0, Math.PI * 2);
            ctx.fill();
        } else if (offline) {
            ctx.fillStyle = '#64748b';
            ctx.beginPath();
            ctx.arc(x + w - 11, y + 12, Math.max(3, 1.4 / Math.max(transform.scale, .18)), 0, Math.PI * 2);
            ctx.fill();
        }
        ctx.restore();
    }

    function monitorVisual(text, background, border) {
        return { text, background, border };
    }

    function getPointStatusVisual(state) {
        const label = String(state && state.statusLabel || '');
        if (state && state.alarm) return monitorVisual('#fecdd3', 'rgba(239,68,68,.23)', 'rgba(251,113,133,.72)');
        if (state && state.offline) return monitorVisual('#cbd5e1', 'rgba(100,116,139,.18)', 'rgba(148,163,184,.45)');
        if (state && state.colorKey === 'unknown') return monitorVisual('#ddd6fe', 'rgba(139,92,246,.20)', 'rgba(167,139,250,.58)');
        if (/运行|启动|在线|就绪|正常/.test(label)) return monitorVisual('#86efac', 'rgba(34,197,94,.18)', 'rgba(74,222,128,.56)');
        if (/停止|暂停|等待|空闲/.test(label)) return monitorVisual('#fde68a', 'rgba(245,158,11,.20)', 'rgba(251,191,36,.62)');
        return monitorVisual('#7dd3fc', 'rgba(14,165,233,.17)', 'rgba(56,189,248,.54)');
    }

    // 料位包裹图元：有货=绿色实心箱子（盖线+封箱缝），空位=暗色空轮廓
    function drawOccupancy(cx, cy, occupied) {
        const s = 13;
        ctx.save();
        ctx.translate(cx, cy);
        ctx.lineWidth = 1.1;
        if (occupied) {
            ctx.fillStyle = 'rgba(16,185,129,.34)';
            ctx.strokeStyle = '#34d399';
            ctx.shadowColor = 'rgba(52,211,153,.55)';
            ctx.shadowBlur = 5;
        } else {
            ctx.strokeStyle = 'rgba(90,110,140,.5)';
        }
        roundRect(ctx, -s / 2, -s / 2, s, s, s * 0.16);
        if (occupied) ctx.fill();
        ctx.stroke();
        ctx.shadowBlur = 0;
        if (occupied) {
            // 盖线 + 封箱缝，读作纸箱
            ctx.beginPath();
            ctx.moveTo(-s / 2, -s * 0.12);
            ctx.lineTo(s / 2, -s * 0.12);
            ctx.moveTo(0, -s * 0.12);
            ctx.lineTo(0, s / 2);
            ctx.stroke();
        }
        ctx.restore();
    }

    function drawMonitorPill(x, y, width, height, text, visual) {
        ctx.save();
        ctx.fillStyle = visual.background;
        ctx.strokeStyle = visual.border;
        ctx.lineWidth = .8;
        roundRect(ctx, x, y, width, height, height / 2);
        ctx.fill();
        ctx.stroke();
        ctx.fillStyle = visual.text;
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillText(text, x + width / 2, y + height / 2);
        ctx.restore();
    }

    function drawNodeBadge(x, y, width, height, text, color, emphasized, fontSize) {
        ctx.save();
        ctx.fillStyle = emphasized ? color : 'rgba(148,163,184,.10)';
        roundRect(ctx, x, y, width, height, height / 2);
        ctx.fill();
        ctx.strokeStyle = emphasized ? color : 'rgba(148,163,184,.20)';
        ctx.lineWidth = .8;
        ctx.stroke();
        ctx.fillStyle = emphasized ? '#ffffff' : color;
        ctx.font = `750 ${fontSize}px sans-serif`;
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillText(text, x + width / 2, y + height / 2);
        ctx.restore();
    }

    function drawTaskDeleteButton(x, y) {
        ctx.save();
        ctx.fillStyle = 'rgba(239,68,68,.22)';
        ctx.strokeStyle = 'rgba(251,113,133,.78)';
        ctx.lineWidth = 1;
        roundRect(ctx, x, y, 20, 18, 9);
        ctx.fill();
        ctx.stroke();
        ctx.fillStyle = '#fecdd3';
        ctx.font = `850 ${readableFontSize(11, 9, 18)}px sans-serif`;
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillText('×', x + 10, y + 9);
        ctx.restore();
    }

    function readableFontSize(baseSize, minScreenSize, maxSize) {
        const scale = Math.max(transform.scale || 1, 0.01);
        return Math.min(maxSize, Math.max(baseSize, minScreenSize / scale));
    }

    function drawModeHint(width, height) {
        const labels = {
            view: '直接操作：拖动点位移动 · Shift + 拖动点位连线 · 双击修改编码 · 右键删除 · 拖动空白处平移',
            edit: '编辑：拖动点位调整位置 · 双击点位修改编码 · 右键点位删除',
            link: '连线：从起点拖到目标点位创建有向轨道 · 点击轨道即可选中删除'
        };
        ctx.save();
        ctx.font = '11px sans-serif';
        ctx.textAlign = 'left';
        ctx.textBaseline = 'middle';
        const text = labels[mode] + (transform.scale < 0.4 ? '  ·  全览：空/占 · #任务号 · 常/警' : '');
        const textWidth = ctx.measureText(text).width;
        ctx.fillStyle = 'rgba(15,23,42,.74)';
        roundRect(ctx, 10, height - 31, textWidth + 20, 22, 11);
        ctx.fill();
        ctx.fillStyle = '#f8fafc';
        ctx.fillText(text, 20, height - 20);
        ctx.restore();
    }

    function getLinkRoute(link, index) {
        const from = nodes.find(node => node.code === link.from);
        const to = nodes.find(node => node.code === link.to);
        if (!from || !to) return null;
        const fx = from.x + CFG.nodeW / 2, fy = from.y + CFG.nodeH / 2;
        const tx = to.x + CFG.nodeW / 2, ty = to.y + CFG.nodeH / 2;
        const horizontal = Math.abs(tx - fx) >= Math.abs(ty - fy);
        const laneOffset = getLaneOffset(link, index);
        if (horizontal) {
            const direction = tx >= fx ? 1 : -1;
            const start = { x: from.x + (direction > 0 ? CFG.nodeW : 0), y: fy };
            const end = { x: to.x + (direction > 0 ? 0 : CFG.nodeW), y: ty };
            const midX = (start.x + end.x) / 2 + laneOffset;
            return Math.abs(start.y - end.y) < 1
                ? [start, end]
                : [start, { x: midX, y: start.y }, { x: midX, y: end.y }, end];
        }
        const direction = ty >= fy ? 1 : -1;
        const start = { x: fx, y: from.y + (direction > 0 ? CFG.nodeH : 0) };
        const end = { x: tx, y: to.y + (direction > 0 ? 0 : CFG.nodeH) };
        const midY = (start.y + end.y) / 2 + laneOffset;
        return Math.abs(start.x - end.x) < 1
            ? [start, end]
            : [start, { x: start.x, y: midY }, { x: end.x, y: midY }, end];
    }

    function getLaneOffset(link, index) {
        const reverse = links.findIndex((other, otherIndex) => otherIndex !== index && other.from === link.to && other.to === link.from);
        if (reverse < 0) return 0;
        return index < reverse ? -8 : 8;
    }

    function onWheel(event) {
        event.preventDefault();
        const rect = canvas.getBoundingClientRect();
        const mouseX = event.clientX - rect.left, mouseY = event.clientY - rect.top;
        const oldScale = transform.scale;
        transform.scale = Math.min(Math.max(transform.scale * (event.deltaY > 0 ? 0.9 : 1.1), 0.18), 4);
        const ratio = transform.scale / oldScale;
        transform.x = mouseX - (mouseX - transform.x) * ratio;
        transform.y = mouseY - (mouseY - transform.y) * ratio;
        draw();
        notifyViewChanged();
    }

    function onMouseDown(event) {
        if (event.button === 2) return;
        canvas.focus();
        const [mx, my] = canvasCoords(event);
        const node = hitNode(mx, my);
        const linkIndex = node ? -1 : findNearestLink(mx, my, 12);

        if (mode === 'view' && node && hitTaskDelete(node, mx, my)) {
            const state = getFullState(node.code);
            selectNode(node);
            draw();
            if (onTaskDelete) onTaskDelete({ taskNo: Number(state.taskNo), pointCode: node.code });
            return;
        }
        if ((mode === 'link' || (mode === 'view' && (event.shiftKey || event.ctrlKey))) && node) {
            linkDraft = { from: node, toX: mx, toY: my };
            selectNode(node);
            draw();
            return;
        }
        if ((mode === 'edit' || mode === 'view') && node) {
            dragging = true;
            dragMoved = false;
            dragTarget = node;
            dragStart = { x: mx, y: my };
            dragNodeStart = { x: node.x, y: node.y };
            selectNode(node);
            draw();
            return;
        }
        if (node) {
            selectNode(node);
            draw();
            if (mode === 'view' && onNodeClick) {
                onNodeClick({ node: node.code, nodeState: getState(node.code) });
            }
            return;
        }
        if (linkIndex >= 0) {
            selectLink(linkIndex);
            draw();
            return;
        }
        clearSelection();
        panning = true;
        panStart = { x: event.clientX - transform.x, y: event.clientY - transform.y };
        canvas.style.cursor = 'grabbing';
        draw();
    }

    function onMouseMove(event) {
        const [mx, my] = canvasCoords(event);
        if (panning) {
            transform.x = event.clientX - panStart.x;
            transform.y = event.clientY - panStart.y;
            draw();
            return;
        }
        if (dragging && dragTarget) {
            if (!dragMoved && Math.hypot(mx - dragStart.x, my - dragStart.y) < 2.5 / transform.scale) return;
            dragMoved = true;
            dragTarget.x = snap(dragNodeStart.x + mx - dragStart.x);
            dragTarget.y = snap(dragNodeStart.y + my - dragStart.y);
            draw();
            return;
        }
        if (linkDraft) {
            linkDraft.toX = mx;
            linkDraft.toY = my;
            draw();
            return;
        }

        const node = hitNode(mx, my);
        const linkIndex = node ? -1 : findNearestLink(mx, my, 12);
        if (node !== hoverNode || linkIndex !== hoverLink) {
            hoverNode = node;
            hoverLink = linkIndex;
            draw();
        }
        if (node) {
            showNodeTooltip(event, node);
        } else if (linkIndex >= 0) {
            showLinkTooltip(event, links[linkIndex]);
        } else {
            hideTooltip();
        }
        const taskDelete = mode === 'view' && node && hitTaskDelete(node, mx, my);
        canvas.style.cursor = taskDelete ? 'pointer'
            : (node ? ((mode === 'view' && (event.shiftKey || event.ctrlKey)) ? 'crosshair' : ((mode === 'edit' || mode === 'view') ? 'move' : 'pointer'))
                : (linkIndex >= 0 ? 'pointer' : (mode === 'view' ? 'grab' : 'default')));
    }

    function onMouseUp(event) {
        if (panning) {
            panning = false;
            canvas.style.cursor = mode === 'view' ? 'grab' : 'default';
            draw();
            return;
        }
        if (dragging) {
            const clickedNode = dragTarget;
            const moved = dragMoved;
            dragging = false;
            dragMoved = false;
            dragTarget = null;
            if (moved) {
                notifyChanged();
            } else if (mode === 'view' && clickedNode && onNodeClick) {
                onNodeClick({ node: clickedNode.code, nodeState: getState(clickedNode.code) });
            }
            draw();
            return;
        }
        if (!linkDraft) return;
        const [mx, my] = canvasCoords(event);
        const target = hitNode(mx, my);
        if (target && target !== linkDraft.from && addLink(linkDraft.from.code, target.code)) {
            notifyChanged();
        }
        linkDraft = null;
        draw();
    }

    function onMouseLeave() {
        panning = false;
        dragging = false;
        dragMoved = false;
        linkDraft = null;
        hoverNode = null;
        hoverLink = -1;
        hideTooltip();
        if (canvas) canvas.style.cursor = mode === 'view' ? 'grab' : 'default';
        draw();
    }

    function onContext(event) {
        event.preventDefault();
        const [mx, my] = canvasCoords(event);
        const node = hitNode(mx, my);
        if (node && mode !== 'link') {
            mapConfirm('删除点位', '将同时删除「' + node.code + '」关联的全部轨道。').then(ok => {
                if (!ok) return;
                nodes = nodes.filter(item => item !== node);
                links = links.filter(link => link.from !== node.code && link.to !== node.code);
                clearSelection();
                notifyChanged();
                draw();
            });
            return;
        }
        const linkIndex = node ? -1 : findNearestLink(mx, my, 16);
        if (linkIndex >= 0) {
            selectedLink = linkIndex;
            selectedNode = null;
            deleteSelectedLink();
        }
    }

    function onDblClick(event) {
        // 浏览模式也允许改编码；只有连线模式保留双击，避免误触中断连线。
        if (mode === 'link') return;
        const [mx, my] = canvasCoords(event);
        const node = hitNode(mx, my);
        if (!node) return;
        event.preventDefault();
        mapPrompt('修改点位编码', node.code).then(value => {
            if (value === null) return;
            const nextCode = value.trim();
            if (!nextCode || nextCode === node.code) return;
            if (nodes.some(item => item !== node && item.code === nextCode)) {
                mapAlert('编码已存在', '请使用未占用的点位编码。');
                return;
            }
            const oldCode = node.code;
            node.code = nextCode;
            node.id = nextCode;
            links.forEach(link => {
                if (link.from === oldCode) link.from = nextCode;
                if (link.to === oldCode) link.to = nextCode;
            });
            notifySelection();
            notifyChanged();
            draw();
        });
    }

    function onKeyDown(event) {
        if (event.key === 'Delete' || event.key === 'Backspace') {
            if (selectedLink >= 0) {
                event.preventDefault();
                deleteSelectedLink();
            } else if (mode === 'edit' && selectedNode) {
                event.preventDefault();
                const node = selectedNode;
                nodes = nodes.filter(item => item !== node);
                links = links.filter(link => link.from !== node.code && link.to !== node.code);
                clearSelection();
                notifyChanged();
                draw();
            }
        } else if (event.key === 'Escape') {
            linkDraft = null;
            clearSelection();
            draw();
        }
    }

    function deleteSelectedLink() {
        if (selectedLink < 0 || selectedLink >= links.length) return false;
        links.splice(selectedLink, 1);
        selectedLink = -1;
        notifySelection();
        notifyChanged();
        draw();
        return true;
    }

    function selectNode(node) {
        selectedNode = node;
        selectedLink = -1;
        notifySelection();
    }

    function selectLink(index) {
        selectedLink = index;
        selectedNode = null;
        notifySelection();
    }

    function clearSelection(redraw) {
        selectedNode = null;
        selectedLink = -1;
        notifySelection();
        if (redraw !== false) draw();
    }

    function notifySelection() {
        if (onSelectionChanged) onSelectionChanged({
            node: selectedNode ? selectedNode.code : null,
            nodeState: selectedNode ? getState(selectedNode.code) : null,
            link: selectedLink >= 0 && links[selectedLink] ? { ...links[selectedLink] } : null
        });
    }

    function findNearestLink(mx, my, threshold) {
        let bestIndex = -1;
        let bestDistance = threshold || 8;
        links.forEach((link, index) => {
            const route = getLinkRoute(link, index);
            if (!route) return;
            for (let i = 1; i < route.length; i++) {
                const distance = pointToSegmentDistance(mx, my, route[i - 1].x, route[i - 1].y, route[i].x, route[i].y);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestIndex = index;
                }
            }
        });
        return bestIndex;
    }

    function pointToSegmentDistance(px, py, ax, ay, bx, by) {
        const dx = bx - ax, dy = by - ay;
        const lengthSquared = dx * dx + dy * dy;
        if (lengthSquared === 0) return Math.hypot(px - ax, py - ay);
        const ratio = Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / lengthSquared));
        return Math.hypot(px - (ax + ratio * dx), py - (ay + ratio * dy));
    }

    function canvasCoords(event) {
        const rect = canvas.getBoundingClientRect();
        return [
            (event.clientX - rect.left - transform.x) / transform.scale,
            (event.clientY - rect.top - transform.y) / transform.scale
        ];
    }

    function hitNode(mx, my) {
        for (let i = nodes.length - 1; i >= 0; i--) {
            const node = nodes[i];
            if (mx >= node.x && mx <= node.x + CFG.nodeW && my >= node.y && my <= node.y + CFG.nodeH) return node;
        }
        return null;
    }

    function hitTaskDelete(node, mx, my) {
        if (!node || transform.scale < 0.4) return false;
        const state = getFullState(node.code);
        if (!state.hasTask) return false;
        return mx >= node.x + CFG.nodeW - 25 && mx <= node.x + CFG.nodeW
            && my >= node.y + 29 && my <= node.y + 55;
    }

    function autoFit() {
        if (!canvas || nodes.length === 0) return;
        const dpr = window.devicePixelRatio || 1;
        const width = canvas.width / dpr, height = canvas.height / dpr;
        const minX = Math.min(...nodes.map(node => node.x));
        const minY = Math.min(...nodes.map(node => node.y));
        const maxX = Math.max(...nodes.map(node => node.x + CFG.nodeW));
        const maxY = Math.max(...nodes.map(node => node.y + CFG.nodeH));
        const padding = 76;
        const contentWidth = maxX - minX + padding * 2;
        const contentHeight = maxY - minY + padding * 2;
        transform.scale = Math.min(Math.max(Math.min(width / contentWidth, height / contentHeight), 0.24), 1.45);
        transform.x = width / 2 - (minX + maxX) * transform.scale / 2;
        transform.y = height / 2 - (minY + maxY) * transform.scale / 2;
        draw();
        notifyViewChanged();
    }

    function autoReadableView() {
        autoFit();
        if (!canvas || nodes.length === 0 || transform.scale >= 0.68) return;
        const dpr = window.devicePixelRatio || 1;
        const width = canvas.width / dpr, height = canvas.height / dpr;
        const minX = Math.min(...nodes.map(node => node.x));
        const minY = Math.min(...nodes.map(node => node.y));
        const maxX = Math.max(...nodes.map(node => node.x + CFG.nodeW));
        const maxY = Math.max(...nodes.map(node => node.y + CFG.nodeH));
        transform.scale = 0.68;
        transform.x = width / 2 - (minX + maxX) * transform.scale / 2;
        transform.y = height / 2 - (minY + maxY) * transform.scale / 2;
        draw();
        notifyViewChanged();
    }

    function zoomBy(factor) {
        if (!canvas) return;
        const dpr = window.devicePixelRatio || 1;
        const width = canvas.width / dpr, height = canvas.height / dpr;
        const oldScale = transform.scale;
        transform.scale = Math.min(Math.max(oldScale * factor, 0.18), 4);
        const ratio = transform.scale / oldScale;
        transform.x = width / 2 - (width / 2 - transform.x) * ratio;
        transform.y = height / 2 - (height / 2 - transform.y) * ratio;
        draw();
        notifyViewChanged();
    }

    function focusNode(code) {
        if (!canvas) return false;
        const target = nodes.find(node => normCode(node.code) === normCode(code));
        if (!target) return false;
        const dpr = window.devicePixelRatio || 1;
        const width = canvas.width / dpr, height = canvas.height / dpr;
        transform.scale = Math.max(transform.scale, 1.15);
        transform.x = width / 2 - (target.x + CFG.nodeW / 2) * transform.scale;
        transform.y = height / 2 - (target.y + CFG.nodeH / 2) * transform.scale;
        selectNode(target);
        draw();
        notifyViewChanged();
        return true;
    }

    function notifyViewChanged() {
        if (onViewChanged) onViewChanged(transform.scale);
    }

    function resize() {
        if (!canvas || !containerEl) return;
        const dpr = window.devicePixelRatio || 1;
        const rect = containerEl.getBoundingClientRect();
        canvas.width = Math.max(1, Math.floor(rect.width * dpr));
        canvas.height = Math.max(1, Math.floor(rect.height * dpr));
        canvas.style.width = rect.width + 'px';
        canvas.style.height = rect.height + 'px';
        draw();
    }

    function addLink(from, to) {
        if (!from || !to || from === to || !nodes.some(node => node.code === from) || !nodes.some(node => node.code === to)) return false;
        if (links.some(link => link.from === from && link.to === to)) return false;
        links.push({ from, to });
        return true;
    }

    function repairLinks() {
        const before = links.length;
        restoreLegacyLinks();
        const added = links.length - before;
        if (added > 0) {
            notifyChanged();
            draw();
        }
        return added;
    }

    function compactLegacyCoordinates() {
        if (nodes.length < 2) return;
        const minX = Math.min(...nodes.map(node => node.x));
        const minY = Math.min(...nodes.map(node => node.y));
        // 旧布局间距偏大；仅在 schema 迁移时压缩一次，兼顾全貌和点位可读性。
        const factor = 0.76;
        nodes.forEach(node => {
            node.x = minX + (node.x - minX) * factor;
            node.y = minY + (node.y - minY) * factor;
        });
    }

    function findPaths(fromCode, toCode, maxPaths) {
        const startNode = nodes.find(node => normCode(node.code) === normCode(fromCode));
        const endNode = nodes.find(node => normCode(node.code) === normCode(toCode));
        if (!startNode || !endNode) return [];
        if (startNode.code === endNode.code) return [[startNode.code]];

        const outgoing = {};
        links.forEach(link => {
            if (!outgoing[link.from]) outgoing[link.from] = [];
            if (!outgoing[link.from].includes(link.to)) outgoing[link.from].push(link.to);
        });

        const limit = Math.min(Math.max(Number(maxPaths) || 12, 1), 30);
        const paths = [];
        const queue = [[startNode.code]];
        const maxDepth = Math.min(nodes.length, 160);
        let inspected = 0;
        while (queue.length && paths.length < limit && inspected < 12000) {
            inspected++;
            const path = queue.shift();
            const current = path[path.length - 1];
            if (current === endNode.code) {
                paths.push(path);
                continue;
            }
            if (path.length >= maxDepth) continue;
            (outgoing[current] || []).forEach(next => {
                if (!path.includes(next)) queue.push(path.concat(next));
            });
        }
        return paths.sort((a, b) => a.length - b.length || a.join(',').localeCompare(b.join(',')));
    }

    function restoreLegacyLinks() {
        const tolerance = 20;
        nodes.forEach(from => {
            const fx = from.x + CFG.nodeW / 2, fy = from.y + CFG.nodeH / 2;
            let right = null, down = null;
            nodes.forEach(to => {
                if (from === to) return;
                const tx = to.x + CFG.nodeW / 2, ty = to.y + CFG.nodeH / 2;
                if (Math.abs(ty - fy) <= tolerance && tx > fx && (!right || tx < right.x)) right = { node: to, x: tx };
                if (Math.abs(tx - fx) <= tolerance && ty > fy && (!down || ty < down.y)) down = { node: to, y: ty };
            });
            if (right) addLink(from.code, right.node.code);
            if (down) addLink(from.code, down.node.code);
        });
    }

    function findNeighbor(point, direction, all) {
        const row = Number(point.r), col = Number(point.c);
        const candidates = all.filter(item => {
            if (!item || item === point || isLayoutLabel(item)) return false;
            const itemRow = Number(item.r), itemCol = Number(item.c);
            if (direction === 'right') return itemRow === row && itemCol > col;
            if (direction === 'left') return itemRow === row && itemCol < col;
            if (direction === 'down') return itemCol === col && itemRow > row;
            if (direction === 'up') return itemCol === col && itemRow < row;
            return false;
        });
        candidates.sort((a, b) => {
            if (direction === 'right') return Number(a.c) - Number(b.c);
            if (direction === 'left') return Number(b.c) - Number(a.c);
            if (direction === 'down') return Number(a.r) - Number(b.r);
            return Number(b.r) - Number(a.r);
        });
        return candidates[0] || null;
    }

    function isPointCode(value) {
        const code = String(value === null || value === undefined ? '' : value).trim();
        return /^\d+$/.test(code) || /^(?=.*\d)[A-Za-z0-9_.:-]+$/.test(code);
    }

    function isLayoutLabel(item) {
        if (!item) return false;
        if (item.kind === 'label' || item.text !== undefined || item.label !== undefined) return true;
        if (item.kind === 'point') return false;
        return !isPointCode(item.code);
    }

    function notifyChanged() {
        if (onLayoutChanged) onLayoutChanged(getLayout());
    }

    function showNodeTooltip(event, node) {
        const state = getState(node.code);
        setTooltip(event, `${node.code}\n状态：${state.label}\n占位：${state.occupancyLabel}\n任务：${state.taskNo || '无'}\n报警：${state.alarmLabel}`);
    }

    function showLinkTooltip(event, link) {
        setTooltip(event, `${link.from}  →  ${link.to}\n点击选中 · Delete 删除 · 右键直接删除`);
    }

    function setTooltip(event, text) {
        if (!tooltipEl || !containerEl) return;
        const rect = containerEl.getBoundingClientRect();
        tooltipEl.textContent = text;
        tooltipEl.style.display = 'block';
        tooltipEl.style.left = Math.min(event.clientX - rect.left + 14, rect.width - 220) + 'px';
        tooltipEl.style.top = Math.max(8, event.clientY - rect.top - 10) + 'px';
    }

    function hideTooltip() {
        if (tooltipEl) tooltipEl.style.display = 'none';
    }

    function getFullState(code) {
        if (!deviceConnected) {
            return { colorKey: 'offline', statusLabel: '离线', offline: true, occupied: false, alarm: false, hasTask: false, taskNo: '' };
        }
        const state = nodeStates[normCode(code)];
        if (!state) return { colorKey: 'unknown', statusLabel: '待匹配', offline: false, occupied: false, alarm: false, hasTask: false, taskNo: '' };
        const hasTask = state.taskNo && Number(state.taskNo) !== 0;
        const occupied = Number(state.occupancyState) === 1;
        const alarm = (state.alarmCode && Number(state.alarmCode) !== 0) || state.pointStateLabel === '报警';
        const offline = Number(state.pointState) === 2;
        if (alarm) return { colorKey: 'alarm', statusLabel: state.alarmCodeLabel || '报警', offline: false, occupied, alarm: true, hasTask, taskNo: state.taskNo };
        if (occupied) return { colorKey: 'occupied', statusLabel: '有货', offline: false, occupied, alarm: false, hasTask, taskNo: state.taskNo || '' };
        if (hasTask) return { colorKey: 'task', statusLabel: '输送中', offline: false, occupied, alarm: false, hasTask, taskNo: state.taskNo };
        if (offline) return { colorKey: 'offline', statusLabel: '离线', offline: true, occupied, alarm: false, hasTask, taskNo: '' };
        return { colorKey: 'idle', statusLabel: state.pointStateLabel || '空闲', offline: false, occupied, alarm: false, hasTask, taskNo: '' };
    }

    function getState(code) {
        if (!deviceConnected) {
            return { label: '设备离线', offline: true, occupancyLabel: '-', occupied: false, taskNo: '', alarm: false, alarmLabel: '-' };
        }
        const state = nodeStates[normCode(code)];
        if (!state) return { label: '待匹配', offline: false, occupancyLabel: '-', occupied: false, taskNo: '', alarm: false, alarmLabel: '-' };
        return {
            label: state.pointStateLabel || '未知',
            offline: Number(state.pointState) === 2,
            occupancyLabel: state.occupancyStateLabel || '-',
            occupied: Number(state.occupancyState) === 1,
            taskNo: state.taskNo || '',
            alarm: !!((state.alarmCode && Number(state.alarmCode) !== 0) || state.pointStateLabel === '报警'),
            alarmLabel: state.alarmCodeLabel || '无'
        };
    }

    function mapPrompt(title, defaultValue) {
        return new Promise(resolve => {
            const overlay = createDialog(title, `<input class="glass-input" data-map-input style="width:100%;box-sizing:border-box;padding:9px 10px;font-size:13px;" value="">`, '保存', false);
            const input = overlay.querySelector('[data-map-input]');
            input.value = defaultValue || '';
            input.focus();
            input.select();
            const close = value => { overlay.remove(); resolve(value); };
            overlay.querySelector('[data-map-cancel]').onclick = () => close(null);
            overlay.querySelector('[data-map-confirm]').onclick = () => close(input.value);
            input.addEventListener('keydown', event => {
                if (event.key === 'Enter') close(input.value);
                if (event.key === 'Escape') close(null);
            });
        });
    }

    function mapConfirm(title, message) {
        return new Promise(resolve => {
            const overlay = createDialog(title, `<div style="font-size:12px;line-height:1.6;color:#64748b;">${message}</div>`, '删除', true);
            const close = value => { overlay.remove(); resolve(value); };
            overlay.querySelector('[data-map-cancel]').onclick = () => close(false);
            overlay.querySelector('[data-map-confirm]').onclick = () => close(true);
            overlay.addEventListener('click', event => { if (event.target === overlay) close(false); });
        });
    }

    function mapAlert(title, message) {
        const overlay = createDialog(title, `<div style="font-size:12px;line-height:1.6;color:#64748b;">${message}</div>`, '知道了', false, true);
        overlay.querySelector('[data-map-cancel]').style.display = 'none';
        overlay.querySelector('[data-map-confirm]').onclick = () => overlay.remove();
    }

    function createDialog(title, content, confirmText, danger, singleAction) {
        const overlay = document.createElement('div');
        overlay.style.cssText = 'position:absolute;inset:0;z-index:30;display:flex;align-items:center;justify-content:center;background:rgba(15,23,42,.36);backdrop-filter:blur(3px);';
        overlay.innerHTML = `<div style="width:300px;padding:20px;border:1px solid rgba(148,163,184,.25);border-radius:12px;background:#fff;box-shadow:0 20px 50px rgba(15,23,42,.25);">
            <div style="margin-bottom:12px;color:#0f172a;font-size:14px;font-weight:700;">${title}</div>
            ${content}
            <div style="display:flex;justify-content:flex-end;gap:8px;margin-top:16px;">
                <button class="glass-btn-outline" data-map-cancel style="padding:7px 13px;font-size:12px;">取消</button>
                <button class="glass-btn ${danger ? 'btn-disconnect' : 'btn-connect'}" data-map-confirm style="padding:7px 13px;font-size:12px;">${confirmText}</button>
            </div>
        </div>`;
        containerEl.appendChild(overlay);
        return overlay;
    }

    function roundRect(context, x, y, width, height, radius) {
        context.beginPath();
        context.moveTo(x + radius, y);
        context.lineTo(x + width - radius, y);
        context.quadraticCurveTo(x + width, y, x + width, y + radius);
        context.lineTo(x + width, y + height - radius);
        context.quadraticCurveTo(x + width, y + height, x + width - radius, y + height);
        context.lineTo(x + radius, y + height);
        context.quadraticCurveTo(x, y + height, x, y + height - radius);
        context.lineTo(x, y + radius);
        context.quadraticCurveTo(x, y, x + radius, y);
        context.closePath();
    }

    function ellipsis(text, maxLength) {
        const value = String(text);
        return value.length > maxLength ? value.slice(0, Math.max(1, maxLength - 1)) + '…' : value;
    }

    function snap(value) { return Math.round(value / CFG.snapGrid) * CFG.snapGrid; }
    function normCode(value) {
        const number = Number(value);
        return Number.isFinite(number) && String(value).trim() !== '' ? String(number) : String(value).trim();
    }
})();
