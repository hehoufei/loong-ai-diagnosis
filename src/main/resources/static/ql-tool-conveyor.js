// 青龙调试工具 - 输送线面板（重构版）
(function () {
    const TYPE = 'CONVEYOR_LINE';

    // 数据表 Tab 定义
    const TABS = [
        { key: 'node-states', label: '点位状态', cols: [
            ['index', '索引'], ['pointCode', '货位号'], ['pointStateLabel', '货位状态'], ['taskNo', '任务号'],
            ['occupancyDataLabel', '光电'], ['occupancyStateLabel', '占位'], ['sensorStatus', '传感器'],
            ['transStatus', '输送状态'], ['alarmCodeLabel', '报警'] ] },
        { key: 'trans-tasks', label: '输送任务', ops: true, cols: [
            ['index', '索引'], ['sysLockerLabel', '读写锁'], ['taskNo', '任务号'], ['taskTypeLabel', '任务类型'],
            ['taskParam', '任务参数'], ['startPoint', '起始点'], ['endPoint', '结束点'],
            ['userData', '用户数据'], ['updateTimes', '更新次数'] ] },
        { key: 'trans-traces', label: '输送轨迹', cols: [
            ['index', '索引'], ['sysLockerLabel', '读写锁'], ['taskNo', '任务号'], ['userData', '用户数据'],
            ['tracePoints', '轨迹点', v => (v || []).join(' → ')], ['updateTimes', '更新次数'] ] },
        { key: 'trans-task-states', label: '任务状态', cols: [
            ['index', '索引'], ['taskNo', '任务号'], ['taskTypeLabel', '任务类型'], ['taskParam', '参数'],
            ['startPoint', '起点'], ['endPoint', '终点'], ['userData', '用户数据'],
            ['updateTimes', '更新次数'], ['taskStateLabel', '任务状态'] ] },
        { key: 'stand-tasks', label: '单机任务', cols: [
            ['index', '索引'], ['sysLockerLabel', '读写锁'], ['taskNo', '任务号'], ['taskTypeLabel', '任务类型'],
            ['pointCode', '货位号'], ['actionTypeLabel', '动作类型'], ['actionParam1', '参数1'], ['actionParam2', '参数2'] ] },
        { key: 'stand-task-states', label: '单机状态', cols: [
            ['index', '索引'], ['taskNo', '任务号'], ['taskTypeLabel', '任务类型'], ['pointCode', '货位号'],
            ['actionTypeLabel', '动作类型'], ['actionParam1', '参数1'], ['actionParam2', '参数2'],
            ['taskStateLabel', '任务状态'], ['taskResult1', '结果1'], ['taskResult2', '结果2'] ] },
        { key: 'request-states', label: '请求信号', cols: [
            ['index', '索引'], ['pointCode', '货位号'], ['state', '请求状态'], ['source', '请求来源'], ['data', '请求数据'] ] },
        { key: 'shape-states', label: '外形检测', cols: [
            ['index', '索引'], ['pointCode', '货位号'], ['detectState', '检测结果'],
            ['detectionResultX', 'X'], ['detectionResultY', 'Y'], ['detectionResultZ', 'Z'], ['detectionData', '数据'] ] },
        { key: 'delete-history', label: '删除记录', history: true, offline: true, static: true, cols: [
            ['deletedAt', '时间', v => String(v || '').replace('T', ' ').replace(/\.\d+(?=[+Z-])/, '')],
            ['taskNo', '任务号'], ['pointCode', '点位'],
            ['source', '来源', v => ({ MAP: '点位图', TASK_TABLE: '输送任务表', TASK_FORM: '任务面板' }[v] || v || '-')],
            ['action', '动作', v => v === 'CLEAR' ? '清理槽位' : '删除指令'],
            ['result', '结果', v => v === 'SUCCESS' ? '成功' : '失败'], ['message', '说明'] ] }
    ];

    // 地图是现场调试的主视图；任务和明细表作为辅助信息按需切换。
    let activeView = 'map';
    let tableFilter = '';
    let lastRows = [];
    let taskPointPickTarget = null;
    let taskRouteOptions = [];
    const layoutSaveTimers = new Map();

    // ---------- 布局持久化 ----------
    const LAYOUT_SCHEMA = '4';
    function layoutKey(deviceId) { return 'ql_layout_' + deviceId; }
    function layoutSchemaKey(deviceId) { return 'ql_layout_schema_' + deviceId; }
    function loadLayout(deviceId) {
        try { return JSON.parse(localStorage.getItem(layoutKey(deviceId)) || 'null'); } catch (e) { return null; }
    }
    function loadLayoutSchema(deviceId) { return localStorage.getItem(layoutSchemaKey(deviceId)); }
    function saveLayout(deviceId, layout) { localStorage.setItem(layoutKey(deviceId), JSON.stringify(layout)); }
    function markCurrentLayout(deviceId) { localStorage.setItem(layoutSchemaKey(deviceId), LAYOUT_SCHEMA); }
    function resetLayoutSchema(deviceId) { localStorage.removeItem(layoutSchemaKey(deviceId)); }
    function autoKey(deviceId) { return 'ql_autolink_' + deviceId; }
    function loadAuto(deviceId) { const v = localStorage.getItem(autoKey(deviceId)); return v === null ? true : v === '1'; }
    function saveAuto(deviceId, on) { localStorage.setItem(autoKey(deviceId), on ? '1' : '0'); }

    function scheduleServerLayoutSave(deviceId, layout) {
        clearTimeout(layoutSaveTimers.get(deviceId));
        layoutSaveTimers.set(deviceId, setTimeout(() => {
            persistLayout(deviceId, layout);
            layoutSaveTimers.delete(deviceId);
        }, 300));
    }

    async function persistLayout(deviceId, layout) {
        try {
            await api(`/conveyor/${deviceId}/layout`, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(layout || [])
            });
            return true;
        } catch (e) {
            toast('布局已保存在当前浏览器，但服务器保存失败：' + e.message, false);
            return false;
        }
    }

    async function syncLayoutFromServer(deviceId) {
        try {
            const snapshot = await api(`/conveyor/${deviceId}/layout`);
            if (!snapshot) return;
            if (!state.current || state.current.deviceId !== deviceId || activeView !== 'map') return;
            if (snapshot.exists) {
                const serverLayout = Array.isArray(snapshot.layout) ? snapshot.layout : [];
                saveLayout(deviceId, serverLayout);
                markCurrentLayout(deviceId);
                if (window.qlMap) window.qlMap.setLayout(serverLayout);
                const empty = document.getElementById('cv_map_empty');
                if (empty) empty.hidden = serverLayout.length > 0;
                refreshMap();
                return;
            }
            const browserLayout = loadLayout(deviceId);
            if (browserLayout && browserLayout.length) {
                await persistLayout(deviceId, browserLayout);
                toast('已将浏览器中的旧布局迁移到服务器');
            }
        } catch (e) {
            console.warn('[ql-tool] 服务器布局加载失败，继续使用浏览器缓存', e);
        }
    }

    function inferredDirs(p, posSet) {
        const dirs = [];
        if (posSet.has(p.r + ',' + (p.c + 1))) dirs.push('right');
        if (posSet.has((p.r + 1) + ',' + p.c)) dirs.push('down');
        return dirs;
    }

    function parseCell(raw) {
        let s = String(raw === null || raw === undefined ? '' : raw).trim();
        if (!s) return null;
        const dirs = [];
        const m = s.match(/[<>^vV↑↓←→]+$/);
        let code = s;
        if (m) {
            code = s.slice(0, s.length - m[0].length).trim();
            for (const ch of m[0]) {
                if (ch === '>' || ch === '→') dirs.push('right');
                else if (ch === '<' || ch === '←') dirs.push('left');
                else if (ch === '^' || ch === '↑') dirs.push('up');
                else if (ch === 'v' || ch === 'V' || ch === '↓') dirs.push('down');
            }
        }
        if (!code) return null;
        if (!isImportedPointCode(code)) return { kind: 'label', text: code };
        return { kind: 'point', code: code, dirs: dirs };
    }

    function isImportedPointCode(value) {
        const code = String(value === null || value === undefined ? '' : value).trim();
        // PLC 点位通常是纯数字或含数字的 ASCII 编码；中文/带空格内容按地图说明文字处理。
        return /^\d+$/.test(code) || /^(?=.*\d)[A-Za-z0-9_.:-]+$/.test(code);
    }

    function isLayoutPoint(item) {
        if (!item || item.kind === 'label') return false;
        return item.kind === 'point' || isImportedPointCode(item.code);
    }

    function parseLayout(text) {
        text = text.replace(/^\uFEFF/, '');
        const lines = text.split(/\r?\n/);
        const layout = [];
        lines.forEach((line, r) => {
            if (line === undefined) return;
            const cells = line.split(line.indexOf('\t') >= 0 ? '\t' : ',');
            cells.forEach((cell, c) => {
                const p = parseCell(cell);
                if (!p) return;
                if (p.kind === 'label') layout.push({ kind: 'label', text: p.text, r: r, c: c });
                else layout.push({ kind: 'point', code: p.code, r: r, c: c, dirs: p.dirs });
            });
        });
        return layout;
    }

    function render(d) {
        const tabs = [{ key: 'map', label: '点位图' }].concat(TABS);
        const tabsHtml = tabs.map(t =>
            `<div class="ql-tab ${t.key === activeView ? 'active' : ''}" data-tab="${t.key}" onclick="cvTab('${t.key}')">${t.label}</div>`).join('');

        const top = `
        <div class="ql-cap-bar" id="cv_caps"><span class="ql-cap"><span class="k">能力表</span><span class="v">连接后加载</span></span></div>
        <div class="ql-tabs" id="cv_tabs">${tabsHtml}</div>
        <div id="cv_content"><div class="ql-map-empty">请连接设备</div></div>`;

        const bottom = `
        <div style="display:flex;gap:24px;flex-wrap:wrap;">
            <div style="flex:1;min-width:300px;">
                <div class="ql-ops-title">下发输送任务</div>
                <div class="ql-ops-row">
                    ${inp('任务号','cv_t_no','number','',{min:1})}
                    ${pointInp('起点','cv_t_start','start')}${pointInp('终点','cv_t_end','end')}
                    <div class="ql-ops-item ql-trace-item"><label>轨迹（自动生成）</label><input class="glass-input" type="text" id="cv_t_trace" value="" readonly placeholder="选择起终点后生成"></div>
                    ${inp('参数','cv_t_param')}${inp('数据','cv_t_user')}
                    <div class="ql-ops-item"><label>&nbsp;</label><button class="glass-btn btn-connect" onclick="cvSubmitTrans()">下发</button></div>
                    <div class="ql-ops-item"><label>&nbsp;</label><button class="glass-btn btn-disconnect" onclick="cvRemoveTrans()">删除</button></div>
                </div>
                <div class="ql-route-picker" id="cv_route_picker" hidden><div class="ql-route-picker-head"><span id="cv_route_status">正在计算路径</span><select class="glass-input" id="cv_route_select" onchange="cvChooseTaskRoute(this.value)"></select></div><div class="ql-route-preview" id="cv_route_preview"></div></div>
            </div>
            <div style="min-width:260px;">
                <div class="ql-ops-title">下发单机任务</div>
                <div class="ql-ops-row">
                    ${inp('任务号','cv_s_no','number','',{min:1})}
                    ${inp('点位','cv_s_point')}${inp('动作','cv_s_action')}
                    ${inp('参数1','cv_s_p1')}${inp('参数2','cv_s_p2')}
                    <div class="ql-ops-item"><label>&nbsp;</label><button class="glass-btn btn-connect" onclick="cvSubmitStand()">下发</button></div>
                </div>
            </div>
        </div>
        <input type="file" id="cv_layout_file" accept=".xlsx,.xls,.csv,.txt,.tsv" style="display:none" onchange="cvImportLayout(event)">`;

        return { top, bottom };
    }

    function inp(label, id, type, width, opts) {
        const t = type || 'number';
        const w = width ? ` style="width:${width}px"` : '';
        const min = opts && opts.min !== undefined ? ` min="${opts.min}"` : '';
        const val = t === 'text' ? '' : '0';
        return `<div class="ql-ops-item"><label>${label}</label><input class="glass-input" type="${t}" id="${id}" value="${val}"${w}${min}></div>`;
    }

    function pointInp(label, id, target) {
        return `<div class="ql-ops-item ql-point-pick"><label>${label}</label><div class="ql-point-input"><input class="glass-input" type="text" id="${id}" value="" placeholder="请选择${label}" oninput="cvTaskPointChanged()"><button type="button" onclick="cvPickTaskPoint('${target}')" title="从地图选择${label}">⌖ 选点</button></div></div>`;
    }

    // ---------- Tab 切换 ----------
    window.cvTab = function (key) {
        activeView = key;
        tableFilter = '';
        document.querySelectorAll('#cv_tabs .ql-tab').forEach(t => t.classList.toggle('active', t.dataset.tab === key));
        if (key === 'map') {
            renderMapShell();
            renderMapGrid();
            refreshMap();
        } else {
            // 首次切换到表格：渲染骨架
            const tab = TABS.find(t => t.key === key);
            lastRows = [];
            buildTableSkeleton(tab);
            refreshTable();
        }
    };

    window.cvFilter = function (q) {
        tableFilter = q.trim().toLowerCase();
        // 过滤时需要重建表格（因为行数变了）
        const tab = TABS.find(t => t.key === activeView);
        if (tab) renderFullTable(tab, getFilteredRows());
    };

    function getFilteredRows() {
        if (!tableFilter) return lastRows;
        return lastRows.filter(r => {
            const pc = String(r.pointCode || '').toLowerCase();
            const tn = String(r.taskNo || '').toLowerCase();
            const idx = String(r.index || '');
            return pc.includes(tableFilter) || tn.includes(tableFilter) || idx.includes(tableFilter);
        });
    }

    // 构建表格骨架（表头 + 空行结构），后续只更新 cell 内容
    function buildTableSkeleton(tab) {
        document.getElementById('cv_content').innerHTML =
            `<div class="ql-table-toolbar">
                <input class="glass-input" type="text" placeholder="搜索（货位号/任务号）…" id="cv_filter" oninput="cvFilter(this.value)">
                <span class="count" id="cv_count"></span>
                ${tab.history ? '<button class="glass-btn-outline ql-map-delete" onclick="cvClearDeleteHistory()">清空删除记录</button>' : ''}
            </div>
            <div class="ql-table-wrap"><table class="glass-table" id="cv_table"><thead><tr>${
                tab.cols.map(c => `<th>${c[1]}</th>`).join('') + (tab.ops ? '<th>操作</th>' : '')
            }</tr></thead><tbody id="cv_tbody"><tr><td colspan="${tab.cols.length + (tab.ops ? 1 : 0)}" style="text-align:center;color:var(--color-text-muted);padding:24px;">加载中…</td></tr></tbody></table></div>`;
    }

    // 全量渲染表体（仅在行数变化或过滤时调用）
    function renderFullTable(tab, rows) {
        const tbody = document.getElementById('cv_tbody');
        if (!tbody) return;
        const countEl = document.getElementById('cv_count');
        if (countEl) countEl.textContent = `${rows.length} / ${lastRows.length} 条`;

        if (rows.length === 0) {
            tbody.innerHTML = `<tr><td colspan="${tab.cols.length + (tab.ops ? 1 : 0)}" style="text-align:center;color:var(--color-text-muted);padding:24px;">无数据</td></tr>`;
            return;
        }
        tbody.innerHTML = rows.map((r, ri) => {
            const tds = tab.cols.map((c, ci) => {
                const val = r[c[0]];
                const fmt = c[2];
                const display = fmt ? fmt(val) : (val === null || val === undefined || val === '' ? '' : String(val));
                return `<td data-ri="${ri}" data-ci="${ci}">${display}</td>`;
            }).join('');
            const ops = tab.ops
                ? `<td style="white-space:nowrap;">
                    <a href="javascript:;" style="color:#dc2626;font-size:12.5px;" onclick="cvRowRemove(${r.taskNo})">删除</a>
                    <a href="javascript:;" style="color:#059669;font-size:12.5px;margin-left:8px;" onclick="cvRowClear(${r.taskNo})">清理</a></td>`
                : '';
            return `<tr data-row="${ri}">${tds}${ops}</tr>`;
        }).join('');
    }

    // 静默填充：逐格对比，只更新有变化的 cell（不重建 DOM）
    function patchTable(tab, rows) {
        const tbody = document.getElementById('cv_tbody');
        if (!tbody) return;
        const existingRows = tbody.querySelectorAll('tr[data-row]');

        // 如果行数变了（首次加载 or 过滤变了），走全量渲染
        if (existingRows.length !== rows.length) {
            renderFullTable(tab, rows);
            return;
        }

        // 逐行逐列 diff 填值
        for (let ri = 0; ri < rows.length; ri++) {
            const r = rows[ri];
            const tr = existingRows[ri];
            const cells = tr.querySelectorAll('td[data-ci]');
            for (let ci = 0; ci < tab.cols.length; ci++) {
                const c = tab.cols[ci];
                const val = r[c[0]];
                const fmt = c[2];
                const display = fmt ? fmt(val) : (val === null || val === undefined || val === '' ? '' : String(val));
                const cell = cells[ci];
                if (cell && cell.textContent !== display) {
                    cell.textContent = display;
                }
            }
            // 更新操作列里的 taskNo（以防任务号变了）
            if (tab.ops) {
                const opsCell = tr.lastElementChild;
                if (opsCell && !opsCell.hasAttribute('data-ci')) {
                    const newTaskNo = r.taskNo || 0;
                    opsCell.innerHTML = `<a href="javascript:;" style="color:#dc2626;font-size:12.5px;" onclick="cvRowRemove(${newTaskNo})">删除</a>
                        <a href="javascript:;" style="color:#059669;font-size:12.5px;margin-left:8px;" onclick="cvRowClear(${newTaskNo})">清理</a>`;
                }
            }
        }
        // 更新计数
        const countEl = document.getElementById('cv_count');
        if (countEl) countEl.textContent = `${rows.length} / ${lastRows.length} 条`;
    }

    // ---------- 点位地图（工业拓扑 Canvas）----------
    function renderMapShell() {
        const d = state.current;
        const layout = loadLayout(d.deviceId);
        document.getElementById('cv_content').innerHTML = `
            <div class="ql-map-head">
                <div class="ql-map-heading"><strong>输送拓扑</strong><span>查看点位状态与轨道关系</span></div>
                <div class="ql-map-search"><input class="glass-input" id="cv_map_search" type="search" placeholder="输入点位号定位" onkeydown="if(event.key==='Enter')cvLocatePoint()"><button class="glass-btn-outline" onclick="cvLocatePoint()">定位</button></div>
                <span class="ql-map-pick-notice" id="cv_map_pick_notice" hidden></span>
                <span class="ql-map-live" id="cv_mapstat">等待连接设备</span>
            </div>
            <div class="ql-map-toolbar">
                <div class="ql-map-direct-hint">拖动点位移动 <span>·</span> Shift＋拖动点位连线 <span>·</span> 双击修改编码 <span>·</span> 右键删除</div>
                <div class="ql-map-toolgroup ql-map-file-tools">
                    <button class="glass-btn-outline" onclick="document.getElementById('cv_layout_file').click()">导入布局</button>
                    <button class="glass-btn-outline" onclick="cvRepairLinks()">自动补线</button>
                    <button class="glass-btn-outline ql-map-delete" onclick="cvClearLayout()">清空布局</button>
                </div>
            </div>
            <div class="ql-map-workspace">
                <div class="ql-map-stage">
                    <div id="cv_mapcanvas"></div>
                    <div class="ql-map-empty" id="cv_map_empty" ${layout && layout.length ? 'hidden' : ''}><b>还没有点位布局</b><small>导入 Excel、CSV 或 TXT 文件即可生成拓扑</small><button class="glass-btn btn-connect" onclick="document.getElementById('cv_layout_file').click()">导入布局文件</button></div>
                    <div class="ql-map-zoom" aria-label="缩放控制"><button onclick="cvMapZoom(-1)" title="缩小">−</button><span id="cv_map_zoom">100%</span><button onclick="cvMapZoom(1)" title="放大">＋</button><button class="fit" onclick="cvFitMap()" title="显示完整布局">全览</button></div>
                </div>
                <aside class="ql-map-inspector">
                    <div class="ql-map-inspector-title">当前选择</div>
                    <div class="ql-map-selection" id="cv_map_selection"><span class="ql-map-selection-icon">⌖</span><b>未选择对象</b><small>点击点位或轨道查看详情</small></div>
                    <button class="glass-btn-outline ql-map-delete ql-map-delete-selected" id="btn_delete_link" onclick="cvDeleteSelectedLink()" disabled>删除选中轨道</button>
                    <div class="ql-map-inspector-divider"></div>
                    <div class="ql-map-inspector-title">状态图例</div>
                    <div class="ql-map-legend"><span class="ql-legend-item"><i class="ql-legend-dot ql-legend-idle"></i>空闲</span><span class="ql-legend-item"><i class="ql-legend-dot ql-legend-load"></i>有货</span><span class="ql-legend-item"><i class="ql-legend-dot ql-legend-task"></i>任务中</span><span class="ql-legend-item"><i class="ql-legend-dot ql-legend-alarm"></i>报警</span></div>
                    <div class="ql-map-inspector-divider"></div>
                    <div class="ql-map-tips"><b>操作提示</b><span>任务号旁 × 可直接删除任务</span><span>滚轮缩放画布</span><span>拖拽空白区域平移</span><span>Delete 删除选中轨道</span></div>
                </aside>
            </div>`;

        const container = document.getElementById('cv_mapcanvas');
        if (!container) return;
        window.qlMap.init(container);
        window.qlMap.onChanged(newLayout => {
            saveLayout(d.deviceId, newLayout);
            markCurrentLayout(d.deviceId);
            scheduleServerLayoutSave(d.deviceId, newLayout);
            const empty = document.getElementById('cv_map_empty');
            if (empty) empty.hidden = newLayout.length > 0;
        });
        window.qlMap.onSelectionChanged(updateMapSelection);
        window.qlMap.onViewChanged(updateMapZoom);
        window.qlMap.onTaskDelete(task => {
            if (task && task.taskNo > 0) window.cvMapRemoveTask(task.taskNo, task.pointCode);
        });
        window.qlMap.onNodeClick(handleTaskPointPick);
        if (layout && layout.length > 0) {
            const migrateLegacy = loadLayoutSchema(d.deviceId) !== LAYOUT_SCHEMA;
            window.qlMap.setLayout(layout, {
                migrateLegacy: migrateLegacy,
                repairEmptyLinks: migrateLegacy
            });
            // 迁移完成后持久化紧凑坐标和恢复出的轨道；以后用户主动删除全部轨道也不会被补回。
            saveLayout(d.deviceId, window.qlMap.getLayout());
            markCurrentLayout(d.deviceId);
        }
        updateMapToolbar();
        updateMapZoom(window.qlMap.getZoom());
        updateTaskPointNotice();
        syncLayoutFromServer(d.deviceId);
    }

    function renderMapGrid() {
        const empty = document.getElementById('cv_map_empty');
        const d = state.current;
        const layout = d && loadLayout(d.deviceId);
        if (empty) empty.hidden = !!(layout && layout.length);
    }

    function layoutFromGrid(grid) {
        const layout = [];
        grid.forEach((row, r) => {
            (row || []).forEach((cell, c) => {
                const point = parseCell(cell);
                if (!point) return;
                if (point.kind === 'label') layout.push({ kind: 'label', text: point.text, r: r, c: c });
                else layout.push({ kind: 'point', code: point.code, r: r, c: c, dirs: point.dirs });
            });
        });
        return layout;
    }

    async function applyLayout(layout) {
        if (!layout || layout.length === 0) { toast('未解析到有效货位号', false); return; }
        resetLayoutSchema(state.current.deviceId);
        saveLayout(state.current.deviceId, layout);
        markCurrentLayout(state.current.deviceId);
        await persistLayout(state.current.deviceId, layout);
        renderMapShell();
        refreshMap();
        const pointCount = layout.filter(isLayoutPoint).length;
        const labelCount = layout.length - pointCount;
        toast('已导入 ' + pointCount + ' 个点位' + (labelCount ? '、' + labelCount + ' 条文字说明' : '') + '，并生成可编辑拓扑');
    }

    window.cvImportLayout = function (ev) {
        const file = ev.target.files && ev.target.files[0];
        if (!file) return;
        const isExcel = /\.(xlsx|xls)$/i.test(file.name);
        const reader = new FileReader();
        if (isExcel) {
            if (typeof XLSX === 'undefined') { toast('Excel 解析库未加载，请改用 CSV', false); ev.target.value = ''; return; }
            reader.onload = function () {
                try {
                    const workbook = XLSX.read(new Uint8Array(reader.result), { type: 'array' });
                    const sheet = workbook.Sheets[workbook.SheetNames[0]];
                    const grid = XLSX.utils.sheet_to_json(sheet, { header: 1, raw: true, defval: '', blankrows: true });
                    applyLayout(layoutFromGrid(grid));
                } catch (e) { toast('Excel 解析失败：' + e.message, false); }
            };
            reader.readAsArrayBuffer(file);
        } else {
            reader.onload = function () { applyLayout(parseLayout(String(reader.result))); };
            reader.readAsText(file, 'UTF-8');
        }
        ev.target.value = '';
    };

    window.cvClearLayout = async function () {
        const ok = await qlConfirm('确认清空布局', '将清空当前设备保存在服务器和浏览器中的点位布局，是否继续？');
        if (!ok) return;
        try {
            await api(`/conveyor/${state.current.deviceId}/layout`, { method: 'DELETE' });
        } catch (e) {
            toast('服务器布局清理失败：' + e.message, false);
            return;
        }
        localStorage.removeItem(layoutKey(state.current.deviceId));
        resetLayoutSchema(state.current.deviceId);
        renderMapShell();
        toast('已清除布局');
    };

    window.cvFitMap = function () {
        if (window.qlMap) window.qlMap.fitToView();
    };

    window.cvMapZoom = function (direction) {
        if (window.qlMap) window.qlMap.zoomBy(direction > 0 ? 1.2 : 1 / 1.2);
    };

    window.cvLocatePoint = function () {
        const input = document.getElementById('cv_map_search');
        const code = input ? input.value.trim() : '';
        if (!code) { toast('请输入要定位的点位号', false); return; }
        if (!window.qlMap || !window.qlMap.focusNode(code)) toast('没有找到点位「' + code + '」', false);
    };

    window.cvPickTaskPoint = function (target) {
        taskPointPickTarget = target === 'end' ? 'end' : 'start';
        if (activeView !== 'map') window.cvTab('map');
        if (window.qlMap) window.qlMap.setMode('view');
        if (window.qlMap) window.qlMap.clearSelection();
        const input = document.getElementById(taskPointPickTarget === 'start' ? 'cv_t_start' : 'cv_t_end');
        if (input) input.value = '';
        window.cvTaskPointChanged();
        updateMapToolbar();
        updateTaskPointNotice();
        toast('请在地图中点击一个点位作为' + (taskPointPickTarget === 'start' ? '起点' : '终点'));
        setTimeout(() => {
            const stage = document.querySelector('.ql-map-stage');
            if (stage) stage.scrollIntoView({ behavior: 'smooth', block: 'center' });
        }, 50);
    };

    function updateTaskPointNotice() {
        const notice = document.getElementById('cv_map_pick_notice');
        if (!notice) return;
        notice.hidden = !taskPointPickTarget;
        notice.textContent = taskPointPickTarget ? '点击点位选择' + (taskPointPickTarget === 'start' ? '起点' : '终点') : '';
    }

    function handleTaskPointPick(selection) {
        if (!taskPointPickTarget || !selection || !selection.node) return;
        const pickedTarget = taskPointPickTarget;
        const input = document.getElementById(pickedTarget === 'start' ? 'cv_t_start' : 'cv_t_end');
        if (input) input.value = selection.node;
        taskPointPickTarget = null;
        updateTaskPointNotice();
        window.cvTaskPointChanged();
        toast('已选择' + (pickedTarget === 'start' ? '起点' : '终点') + '：' + selection.node);
        setTimeout(() => {
            const panel = document.querySelector('.ql-content-bottom');
            if (panel) panel.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
        }, 180);
    }

    window.cvTaskPointChanged = function () {
        const start = txt('cv_t_start').trim();
        const end = txt('cv_t_end').trim();
        const traceInput = document.getElementById('cv_t_trace');
        const picker = document.getElementById('cv_route_picker');
        const status = document.getElementById('cv_route_status');
        const select = document.getElementById('cv_route_select');
        const preview = document.getElementById('cv_route_preview');
        taskRouteOptions = [];
        if (traceInput) traceInput.value = '';
        if (!start || !end || start === '0' || end === '0') { if (picker) picker.hidden = true; return; }
        if (!window.qlMap || typeof window.qlMap.findPaths !== 'function') { if (picker) picker.hidden = true; return; }

        taskRouteOptions = window.qlMap.findPaths(start, end, 20);
        if (picker) picker.hidden = false;
        if (!taskRouteOptions.length) {
            if (status) status.textContent = '未找到符合轨道方向的可达路径';
            if (select) select.innerHTML = '';
            if (preview) preview.textContent = '请检查起终点或轨道连线方向';
            return;
        }
        if (status) status.textContent = taskRouteOptions.length === 1 ? '已找到唯一路径' : `找到 ${taskRouteOptions.length} 条路径，请选择`;
        if (select) select.innerHTML = taskRouteOptions.map((path, index) => `<option value="${index}">路径 ${index + 1} · ${path.length} 个点</option>`).join('');
        window.cvChooseTaskRoute(0);
    };

    window.cvChooseTaskRoute = function (index) {
        const route = taskRouteOptions[Number(index)] || taskRouteOptions[0];
        if (!route) return;
        const value = route.join(',');
        const traceInput = document.getElementById('cv_t_trace');
        const preview = document.getElementById('cv_route_preview');
        if (traceInput) traceInput.value = value;
        if (preview) preview.textContent = value;
    };

    window.cvRepairLinks = function () {
        if (!window.qlMap) return;
        const added = window.qlMap.repairLinks();
        if (added > 0) toast('已按同行/同列最近点补全 ' + added + ' 条连线');
        else toast('当前布局没有可补全的连线', false);
    };

    window.cvMapSelect = function () {
        window.qlMap.setMode('view');
        updateMapToolbar();
        toast('选择模式：点击轨道即可选中');
    };

    window.cvToggleEdit = function () {
        window.qlMap.setMode(window.qlMap.getMode() === 'edit' ? 'view' : 'edit');
        updateMapToolbar();
        toast(window.qlMap.getMode() === 'edit' ? '编辑模式：拖动点位，双击修改编码' : '已切换为选择模式');
    };

    window.cvToggleLink = function () {
        window.qlMap.setMode(window.qlMap.getMode() === 'link' ? 'view' : 'link');
        updateMapToolbar();
        toast(window.qlMap.getMode() === 'link' ? '连线模式：从一个点位拖向目标点位' : '已切换为选择模式');
    };

    window.cvDeleteSelectedLink = function () {
        if (!window.qlMap || !window.qlMap.deleteSelectedLink()) {
            toast('请先点击一条轨道再删除', false);
            return;
        }
        updateMapToolbar();
        toast('已删除选中连线');
    };

    function updateMapSelection(selection) {
        const button = document.getElementById('btn_delete_link');
        if (button) button.disabled = !selection.link;
        const detail = document.getElementById('cv_map_selection');
        if (!detail) return;
        if (selection.node) {
            const pointState = selection.nodeState || {};
            if (pointState.offline) {
                detail.className = 'ql-map-selection has-selection';
                detail.innerHTML = `<span class="ql-map-selection-icon">▦</span><small>点位</small><b>${selection.node}</b><div class="ql-map-state-grid"><span>● 设备离线</span></div><em>实时状态已清空，连接设备后自动恢复</em>`;
                return;
            }
            const taskHtml = pointState.taskNo
                ? `<button type="button" class="ql-map-task-delete" title="删除输送任务 ${pointState.taskNo}"><span>任务 ${pointState.taskNo}</span><b>删除</b></button>`
                : '<span class="task-state">无任务</span>';
            detail.className = 'ql-map-selection has-selection';
            detail.innerHTML = `<span class="ql-map-selection-icon">▦</span><small>点位</small><b>${selection.node}</b><div class="ql-map-state-grid"><span class="${pointState.occupied?'is-occupied':''}">${pointState.occupied?'● 已占位':'○ 空位'}</span>${taskHtml}<span class="${pointState.alarm?'is-alarm':'is-normal'}">${pointState.alarm?'⚠ 有报警':'✓ 无报警'}</span></div><em>${pointState.taskNo?'点击任务右侧“删除”可直接删除输送任务':'双击可修改点位编号'}</em>`;
            const taskDeleteButton = detail.querySelector('.ql-map-task-delete');
            if (taskDeleteButton) {
                taskDeleteButton.onclick = () => window.cvMapRemoveTask(Number(pointState.taskNo), String(selection.node));
            }
        } else if (selection.link) {
            detail.className = 'ql-map-selection has-selection';
            detail.innerHTML = `<span class="ql-map-selection-icon">→</span><small>输送轨道</small><b>${selection.link.from} → ${selection.link.to}</b><em>按 Delete 可快速删除</em>`;
        } else {
            detail.className = 'ql-map-selection';
            detail.innerHTML = '<span class="ql-map-selection-icon">⌖</span><b>未选择对象</b><small>点击点位或轨道查看详情</small>';
        }
    }

    function updateMapZoom(scale) {
        const label = document.getElementById('cv_map_zoom');
        if (label) label.textContent = Math.round((scale || 1) * 100) + '%';
    }

    function updateMapToolbar() {
        if (!window.qlMap) return;
        const current = window.qlMap.getMode();
        const active = { view: 'btn_map_select', edit: 'btn_editmap', link: 'btn_linkmap' };
        Object.keys(active).forEach(key => {
            const button = document.getElementById(active[key]);
            if (button) button.classList.toggle('is-active', key === current);
        });
        const deleteButton = document.getElementById('btn_delete_link');
        if (deleteButton) deleteButton.disabled = !window.qlMap.hasSelectedLink();
    }

    window.cvToggleAuto = function () {
        const on = document.getElementById('cv_autolink') && document.getElementById('cv_autolink').checked;
        saveAuto(state.current.deviceId, on);
        const layout = loadLayout(state.current.deviceId);
        if (layout && layout.length > 0) window.qlMap.setLayout(layout);
    };

    async function refreshMap() {
        const d = state.current;
        if (!d || !d.connected || activeView !== 'map') return;
        let nodes;
        try { nodes = await api(`/conveyor/${d.deviceId}/node-states`); } catch (e) { return; }
        // 断开时可能仍有一个已发出的读取请求在返回；不能让迟到响应恢复旧状态。
        if (!d.connected || state.current !== d || activeView !== 'map') return;
        window.qlMap.setConnectionState(true);
        window.qlMap.updateStates(nodes || []);
        const statEl = document.getElementById('cv_mapstat');
        if (statEl && nodes) {
            const layout = loadLayout(d.deviceId) || [];
            const pointLayout = layout.filter(isLayoutPoint);
            const matched = nodes.filter(node => pointLayout.some(point => normCode(point.code) === normCode(node.pointCode))).length;
            const occupied = nodes.filter(node => Number(node.occupancyState) === 1).length;
            const tasks = nodes.filter(node => node.taskNo && Number(node.taskNo) !== 0).length;
            const alarms = nodes.filter(node => node.alarmCode && Number(node.alarmCode) !== 0).length;
            statEl.textContent = `在线 · 点位 ${matched}/${pointLayout.length} · 任务 ${tasks} · 占位 ${occupied} · 报警 ${alarms}`;
            statEl.classList.add('online');
        }
    }

    function normCode(v) {
        const n = Number(v);
        return Number.isFinite(n) && String(v).trim() !== '' ? String(n) : String(v).trim();
    }

    // ---------- 数据表（含过滤） ----------
    async function refreshTable() {
        const d = state.current;
        if (!d || activeView === 'map') return;
        const tab = TABS.find(t => t.key === activeView);
        if (!tab) return;
        if (!d.connected && !tab.offline) return;
        try {
            const rows = await api(`/conveyor/${d.deviceId}/${tab.key}`) || [];
            if (state.current !== d || activeView !== tab.key || (!d.connected && !tab.offline)) return;
            // 数据完整性检查：如果返回数据缺字段，跳过本次更新
            if (rows.length > 0) {
                const firstRow = rows[0];
                const allFieldsPresent = tab.cols.every(c => firstRow.hasOwnProperty(c[0]));
                if (!allFieldsPresent) {
                    console.warn('[ql-tool] 数据不完整，跳过本次更新', Object.keys(firstRow));
                    return;
                }
            }
            lastRows = rows;
            // 静默填充：只更新变化的 cell，不重建 DOM
            const filtered = getFilteredRows();
            patchTable(tab, filtered);
        } catch (e) { /* 读取失败静默，保持当前显示不变 */ }
    }

    // （renderFilteredTable 和 renderTable 已被 patchTable + renderFullTable 替代）

    // ---------- 任务下发（含验证和确认） ----------
    function num(id) { const e = document.getElementById(id); return e ? Number(e.value || 0) : 0; }
    function txt(id) { const e = document.getElementById(id); return e ? (e.value || '') : ''; }

    window.cvSubmitTrans = async function () {
        const d = state.current; if (!d) return;
        const taskNo = num('cv_t_no');
        if (taskNo <= 0) { toast('请输入有效的任务号（>0）', false); return; }
        const startPoint = num('cv_t_start'), endPoint = num('cv_t_end');
        if (!Number.isFinite(startPoint) || startPoint <= 0 || !Number.isFinite(endPoint) || endPoint <= 0) {
            toast('请通过地图选择有效的起点和终点', false); return;
        }
        const rawTrace = txt('cv_t_trace').split(/[,，\s]+/).filter(Boolean);
        const trace = rawTrace.map(value => Number(value));
        if (!trace.length || trace.some(value => !Number.isFinite(value))) {
            toast('未生成有效轨迹，请重新选择起点和终点', false); return;
        }
        const body = { taskNo: taskNo, tracePoints: trace, startPoint: startPoint,
            endPoint: endPoint, taskParam: num('cv_t_param'), userData: num('cv_t_user'), updateTimes: 1 };

        const ok = await qlConfirm('确认下发输送任务',
            `任务号: ${taskNo}，起点: ${body.startPoint}，终点: ${body.endPoint}，轨迹: ${trace.length}个点`);
        if (!ok) return;

        try {
            await api(`/conveyor/${d.deviceId}/trans-task`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
            toast('已下发输送任务 #' + taskNo); refreshActive();
        } catch (e) { toast('下发失败：' + e.message, false); }
    };

    window.cvRemoveTrans = async function () {
        const d = state.current; if (!d) return;
        const taskNo = num('cv_t_no');
        if (taskNo <= 0) { toast('请输入要删除的任务号', false); return; }
        await confirmDeleteTransTask(taskNo, '', 'TASK_FORM');
    };

    window.cvRowRemove = async function (taskNo) {
        await confirmDeleteTransTask(taskNo, '', 'TASK_TABLE');
    };
    window.cvMapRemoveTask = async function (taskNo, pointCode) {
        await confirmDeleteTransTask(taskNo, pointCode, 'MAP');
    };
    window.cvRowClear = async function (taskNo) {
        const ok = await qlConfirm('确认清理', `清理输送任务 #${taskNo}（清零任务槽+轨迹槽）？`);
        if (!ok) return;
        delTask(state.current.deviceId, taskNo, true, { source: 'TASK_TABLE' });
    };

    async function delTask(deviceId, taskNo, clear, meta) {
        try {
            const params = new URLSearchParams();
            params.set('source', meta && meta.source ? meta.source : 'TRANSPORT_TASK');
            if (meta && meta.pointCode) params.set('pointCode', meta.pointCode);
            await api(`/conveyor/${deviceId}/trans-task/${taskNo}${clear ? '/clear' : ''}?${params}`, { method: 'DELETE' });
            toast(clear ? '已清理任务数据 #' + taskNo : '已下发删除指令 #' + taskNo);
            refreshActive();
        } catch (e) { toast('操作失败：' + e.message, false); }
    }

    async function confirmDeleteTransTask(taskNo, pointCode, source) {
        const d = state.current;
        const normalizedTaskNo = Number(taskNo);
        if (!d || !Number.isFinite(normalizedTaskNo) || normalizedTaskNo <= 0) {
            toast('未获取到有效的输送任务号', false);
            return;
        }
        const pointText = pointCode ? `\n所在点位：${pointCode}` : '';
        const ok = await qlConfirm('确认删除输送任务', `将删除任务号 ${normalizedTaskNo}${pointText}，是否确认？`);
        if (!ok) return;
        await delTask(d.deviceId, normalizedTaskNo, false, {
            source: source || 'TRANSPORT_TASK',
            pointCode: pointCode || ''
        });
    }

    window.cvClearDeleteHistory = async function () {
        const d = state.current;
        if (!d) return;
        const ok = await qlConfirm('确认清空删除记录', '只清理本工具保存的 JSON 审计记录，不会再次操作 PLC。是否继续？');
        if (!ok) return;
        try {
            const count = await api(`/conveyor/${d.deviceId}/delete-history`, { method: 'DELETE' });
            toast('已清空 ' + (count || 0) + ' 条删除记录');
            refreshTable();
        } catch (e) {
            toast('清空记录失败：' + e.message, false);
        }
    };

    window.cvSubmitStand = async function () {
        const d = state.current; if (!d) return;
        const taskNo = num('cv_s_no');
        if (taskNo <= 0) { toast('请输入有效的任务号（>0）', false); return; }
        const body = { taskNo: taskNo, pointCode: num('cv_s_point'), actionType: num('cv_s_action'),
            actionParam1: num('cv_s_p1'), actionParam2: num('cv_s_p2') };

        const ok = await qlConfirm('确认下发单机任务',
            `任务号: ${taskNo}，点位: ${body.pointCode}，动作: ${body.actionType}`);
        if (!ok) return;

        try {
            await api(`/conveyor/${d.deviceId}/stand-task`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
            toast('已下发单机任务 #' + taskNo); refreshActive();
        } catch (e) { toast('下发失败：' + e.message, false); }
    };

    function refreshActive() {
        if (activeView === 'map') {
            refreshMap();
            return;
        }
        const tab = TABS.find(t => t.key === activeView);
        if (!tab || !tab.static) refreshTable();
    }

    async function loadCaps(d) {
        try {
            const c = await api(`/conveyor/${d.deviceId}/capacities`);
            const items = [['点位', c.pointSize], ['任务', c.taskSize], ['轨迹', c.traceSize],
                ['请求', c.requestSize], ['外检', c.shapeSize], ['单机', c.actionSize], ['默认路径', c.defaultTraceSize]];
            document.getElementById('cv_caps').innerHTML = items.map(i =>
                `<span class="ql-cap"><span class="k">${i[0]}</span><span class="v">${i[1]}</span></span>`).join('');
        } catch (e) { /* 忽略 */ }
    }

    function clearRuntimeState() {
        taskPointPickTarget = null;
        taskRouteOptions = [];
        if (window.qlMap) window.qlMap.setConnectionState(false);

        const statEl = document.getElementById('cv_mapstat');
        if (statEl) {
            statEl.textContent = '设备已离线 · 实时状态已清空';
            statEl.classList.remove('online');
        }
        const caps = document.getElementById('cv_caps');
        if (caps) {
            caps.innerHTML = '<span class="ql-cap"><span class="k">能力表</span><span class="v">连接后加载</span></span>';
        }

        const tab = TABS.find(t => t.key === activeView);
        if (activeView !== 'map' && tab && !tab.offline) {
            lastRows = [];
            renderFullTable(tab, []);
            const tbody = document.getElementById('cv_tbody');
            if (tbody) {
                tbody.innerHTML = `<tr><td colspan="${tab.cols.length + (tab.ops ? 1 : 0)}" style="text-align:center;color:var(--color-text-muted);padding:24px;">设备未连接，暂无实时数据</td></tr>`;
            }
        }
    }

    window.qlPanels[TYPE] = {
        render: render,
        init: function (d) {
            if (activeView === 'map') { renderMapShell(); renderMapGrid(); }
            else {
                const tab = TABS.find(t => t.key === activeView);
                if (tab) { buildTableSkeleton(tab); refreshTable(); }
            }
            if (window.qlMap) window.qlMap.setConnectionState(!!(d && d.connected));
            if (!d || !d.connected) clearRuntimeState();
        },
        onConnected: function (d) {
            loadCaps(d);
            if (activeView === 'map') {
                renderMapShell();
                renderMapGrid();
                if (window.qlMap) window.qlMap.setConnectionState(true);
            }
            else {
                const tab = TABS.find(t => t.key === activeView);
                if (tab) { buildTableSkeleton(tab); refreshTable(); }
            }
            startPolling(async () => { refreshActive(); });
        },
        onDisconnected: function () {
            clearRuntimeState();
        }
    };
})();
