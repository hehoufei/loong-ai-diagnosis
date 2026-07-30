// 青龙调试工具 - 堆垛机面板
(function () {
    const TYPE = 'STACKER_CRANE';
    let lastMotion = null;
    let motionTimer = null;

    function fld(label, id, extraClass) {
        return `<div class="crane-data-item ${extraClass || ''}" id="wrap_${id}"><span class="k">${label}</span><span class="v" id="${id}">--</span></div>`;
    }

    function rack(side, label) {
        return `<div class="crane-rack crane-rack-${side}" data-rack-side="${side}">
            <div class="crane-rack-title"><span>${label}</span><b id="c_${side}_rack_state">待定位</b></div>
            <div class="crane-rack-columns" aria-hidden="true">${Array.from({length: 4}, (_, i) => `<span data-col-label="${i}">--</span>`).join('')}</div>
            <div class="crane-rack-grid" role="grid" aria-label="${label}当前位置邻近货位">
                ${Array.from({length: 20}, (_, i) => `<i role="gridcell" data-rack-cell="${side}" data-row-index="${Math.floor(i / 4)}" data-col-index="${i % 4}"><span></span></i>`).join('')}
            </div>
            <div class="crane-rack-levels" aria-hidden="true">${Array.from({length: 5}, (_, i) => `<span data-level-label="${i}">--</span>`).join('')}</div>
        </div>`;
    }

    function render(d) {
        const top = `
        <div class="crane-console" id="crane_console">
            <div class="crane-console-head">
                <div class="crane-console-title">
                    <span class="crane-live-mark"><i></i></span>
                    <div><b>堆垛机实时工况</b><small>STACKER CRANE DIGITAL PANEL</small></div>
                </div>
                <div class="crane-console-refresh"><span>最后采样</span><b id="c_refresh">等待连接</b></div>
            </div>

            <div class="crane-console-body">
                <section class="crane-machine-panel">
                    <div class="crane-panel-heading">
                        <div><b>巷道运行视图</b><small>位置、载货台及双货叉状态</small></div>
                        <div class="crane-axis-values">
                            <span id="c_h_motion"><i></i>行走 <canvas class="crane-spark" id="c_h_spark" width="54" height="16"></canvas><b id="c_hPulse">--</b><em id="c_h_motion_text">停止</em></span>
                            <span id="c_v_motion"><i></i>提升 <canvas class="crane-spark" id="c_v_spark" width="54" height="16"></canvas><b id="c_vPulse">--</b><em id="c_v_motion_text">停止</em></span>
                        </div>
                    </div>
                    <div class="crane-status-ribbon crane-machine-status" aria-label="堆垛机实时状态">
                        ${fld('工作模式','c_workMode','crane-primary-state')}
                        ${fld('任务号','c_taskNo')}
                        ${fld('任务阶段','c_taskStatus')}
                        ${fld('当前层','c_rowStation')}
                        ${fld('货载状态','c_cargoState')}
                        ${fld('报警状态','c_alarm','crane-alarm-state')}
                    </div>
                    <div class="crane-phase-track" id="c_phase_track" aria-label="任务阶段进度">
                        <div class="crane-phase-step" data-phase="idle"><i></i><span>待命</span></div>
                        <div class="crane-phase-step" data-phase="pickup"><i></i><span>取货</span></div>
                        <div class="crane-phase-step" data-phase="travel"><i></i><span>行走</span></div>
                        <div class="crane-phase-step" data-phase="dropoff"><i></i><span>放货</span></div>
                        <div class="crane-phase-step" data-phase="done"><i></i><span>完成</span></div>
                    </div>
                    <div class="crane-machine-layout">
                        <section class="crane-fork-monitor" id="c_fork_card_1">
                            <div class="crane-fork-monitor-title"><span>FORK 01</span><b>货叉 1</b><em id="c_f1_summary">等待状态</em></div>
                            <div class="crane-fork-monitor-data">
                                ${fld('伸叉脉冲','c_f1_pulse')}${fld('列位置','c_f1_col')}${fld('位置有效','c_f1_valid')}
                                ${fld('位置反馈','c_f1_back')}${fld('载货检测','c_f1_load')}${fld('当前动作','c_f1_active')}
                            </div>
                        </section>
                        <div class="crane-machine-view" role="img" aria-label="堆垛机巷道、立柱、载货台及双货叉实时示意">
                            <div class="crane-rail crane-rail-top"></div>
                            <div class="crane-rail crane-rail-bottom"></div>
                            ${rack('left','左侧货架')}
                            ${rack('right','右侧货架')}
                            <div class="crane-aisle-label">巷道</div>
                            <div class="crane-motion-overlay" id="c_motion_overlay" aria-live="polite"><i></i><b id="c_motion_label">等待设备运动</b></div>
                            <div class="crane-mast">
                                <span class="crane-mast-line"></span>
                                <div class="crane-carriage" id="c_visual_carriage">
                                    <div class="crane-carriage-head">
                                        <span>载货台</span><b id="c_viz_dock">--</b>
                                    </div>
                                    <div class="crane-fork-beam crane-fork-beam-one" id="c_visual_fork1"><i></i><span>货叉 1</span></div>
                                    <div class="crane-load-unit" id="c_visual_load"><span>LOAD</span></div>
                                    <div class="crane-fork-beam crane-fork-beam-two" id="c_visual_fork2"><i></i><span>货叉 2</span></div>
                                </div>
                            </div>
                            <div class="crane-coordinate-readout" aria-label="当前巷道坐标">
                                <span><small>排</small><b id="c_viz_line">--</b></span>
                                <span><small>列</small><b id="c_viz_col">--</b></span>
                                <span><small>层</small><b id="c_viz_row">--</b></span>
                            </div>
                            <div class="crane-task-tag"><span>当前任务</span><b id="c_viz_task">--</b></div>
                        </div>
                        <section class="crane-fork-monitor" id="c_fork_card_2">
                            <div class="crane-fork-monitor-title"><span>FORK 02</span><b>货叉 2</b><em id="c_f2_summary">等待状态</em></div>
                            <div class="crane-fork-monitor-data">
                                ${fld('伸叉脉冲','c_f2_pulse')}${fld('列位置','c_f2_col')}${fld('位置有效','c_f2_valid')}
                                ${fld('位置反馈','c_f2_back')}${fld('载货检测','c_f2_load')}${fld('当前动作','c_f2_active')}
                            </div>
                        </section>
                    </div>
                </section>

                <aside class="crane-detail-panel">
                    <div class="crane-panel-heading"><div><b>执行与反馈</b><small>任务结果和设备信号</small></div></div>
                    <div class="crane-result-block">
                        ${fld('指令类型','c_r_cmd')}
                        ${fld('反馈任务号','c_r_taskNo')}
                        ${fld('执行结果','c_r_type')}
                        ${fld('错误码','c_r_code')}
                    </div>
                    <div class="crane-alarm-banner" id="c_alarm_banner">
                        <span class="crane-alarm-icon">!</span>
                        <div><small>设备报警</small><b id="c_alarm_detail">无报警</b></div>
                    </div>
                </aside>
                <section class="crane-command-deck">
                    <div class="crane-task-head">
                        <div class="crane-command-deck-title"><span class="crane-console-code">MISSION CONTROL</span><div><b>设备任务控制</b><span>选择动作、设置位置并确认执行</span></div></div>
                        <div class="ql-tabs" id="c_tabs">
                            <div class="ql-tab active" data-tab="carry" onclick="craneTab('carry')">搬运</div>
                            <div class="ql-tab" data-tab="move" onclick="craneTab('move')">行走</div>
                            <div class="ql-tab" data-tab="pickup" onclick="craneTab('pickup')">取货</div>
                            <div class="ql-tab" data-tab="dropoff" onclick="craneTab('dropoff')">放货</div>
                            <div class="ql-tab crane-clear-tab" data-tab="clear" onclick="craneTab('clear')">清除任务</div>
                        </div>
                    </div>
                    <div class="crane-command-form" id="c_form"></div>
                </section>
            </div>

        </div>`;

        return { top, bottom: '' };
    }

    function inp(label, id, val, unit) {
        return `<label class="crane-number-field"><span>${label}</span><div><input class="glass-input" type="number" id="${id}" value="${val||0}" min="0">${unit?`<em>${unit}</em>`:''}</div></label>`;
    }

    function positionBlock(title, subtitle, prefix, tone) {
        return `<section class="crane-position-block ${tone||''}">
            <div class="crane-position-title"><span>${title}</span><small>${subtitle}</small></div>
            <div class="crane-position-fields">
                ${inp('排',prefix+'_line',0,'排')}${inp('列',prefix+'_col',0,'列')}${inp('层',prefix+'_row',0,'层')}
            </div>
        </section>`;
    }

    function taskMeta(prefix, noId) {
        return `<div class="crane-task-meta">
            <div class="crane-task-meta-title"><b>任务参数</b><span>尺寸单位按现场协议约定</span></div>
            <div class="crane-task-meta-grid">
                ${inp('任务号',noId,1,'#')}${inp('货宽',prefix+'_w',0)}${inp('货高',prefix+'_h',0)}${inp('货深',prefix+'_d',0)}
            </div>
        </div>`;
    }

    function executeButton(key, label, note, danger) {
        return `<button class="crane-execute-btn ${danger?'danger':''}" onclick="craneSubmit('${key}')">
            <span>${label}</span><small>${note}</small>
        </button>`;
    }

    function taskForm(typeLabel, route, meta, action) {
        return `<div class="crane-task-layout">
            <div class="crane-task-route">
                <div class="crane-task-type"><span>当前任务类型</span><b>${typeLabel}</b></div>
                ${route}
            </div>
            <aside class="crane-task-action">${meta}${action}</aside>
        </div>`;
    }

    const forms = {
        carry: ()=>taskForm('执行搬运任务',
            `<div class="crane-position-flow">${positionBlock('起始位置','取货货位','ct_p','pickup')}<div class="crane-route-arrow"><i></i><span>搬运至</span></div>${positionBlock('目标位置','放货货位','ct_d','dropoff')}</div>`,
            taskMeta('ct','ct_no'),executeButton('carry','确认执行','下发搬运任务')),
        move: ()=>taskForm('执行行走任务',
            `<div class="crane-position-flow single">${positionBlock('目标位置','设备移动目标','mt_t','target')}</div>`,
            taskMeta('mt','mt_no'),executeButton('move','确认行走','仅移动设备位置')),
        pickup: ()=>taskForm('执行取货任务',
            `<div class="crane-position-flow single">${positionBlock('取货位置','货叉取货目标','pt_p','pickup')}</div>`,
            taskMeta('pt','pt_no'),executeButton('pickup','确认取货','执行货叉取货')),
        dropoff: ()=>taskForm('执行放货任务',
            `<div class="crane-position-flow single">${positionBlock('放货位置','货叉放货目标','dt_d','dropoff')}</div>`,
            taskMeta('dt','dt_no'),executeButton('dropoff','确认放货','执行货叉放货')),
        clear: ()=>`<div class="crane-clear-task">
            <div class="crane-clear-copy"><span>!</span><div><b>清除设备任务</b><small>仅在确认当前任务需要终止时操作，系统会再次要求确认。</small></div></div>
            <div class="crane-clear-action">${inp('待清除任务号','clt_no',1,'#')}${executeButton('clear','清除任务','危险操作，请谨慎','danger')}</div>
        </div>`
    };

    window.craneTab = function(key) {
        document.querySelectorAll('#c_tabs .ql-tab').forEach(t=>t.classList.toggle('active',t.dataset.tab===key));
        document.getElementById('c_form').innerHTML = forms[key]();
    };

    function v(id){const e=document.getElementById(id);return e?Number(e.value||0):0;}

    window.craneSubmit = async function(key) {
        const d=state.current; if(!d) return;
        const noId={carry:'ct_no',move:'mt_no',pickup:'pt_no',dropoff:'dt_no',clear:'clt_no'}[key];
        if(v(noId)<=0){toast('任务号须>0',false);return;}
        let path, body;
        if(key==='carry'){path='carry-task';body={taskNo:v('ct_no'),pickupLine:v('ct_p_line'),pickupCol:v('ct_p_col'),pickupRow:v('ct_p_row'),dropoffLine:v('ct_d_line'),dropoffCol:v('ct_d_col'),dropoffRow:v('ct_d_row'),cargoWidth:v('ct_w'),cargoHeight:v('ct_h'),cargoDepth:v('ct_d')};}
        else if(key==='move'){path='move-task';body={taskNo:v('mt_no'),targetLine:v('mt_t_line'),targetCol:v('mt_t_col'),targetRow:v('mt_t_row'),cargoWidth:v('mt_w'),cargoHeight:v('mt_h'),cargoDepth:v('mt_d')};}
        else if(key==='pickup'){path='pickup-task';body={taskNo:v('pt_no'),pickupLine:v('pt_p_line'),pickupCol:v('pt_p_col'),pickupRow:v('pt_p_row'),cargoWidth:v('pt_w'),cargoHeight:v('pt_h'),cargoDepth:v('pt_d')};}
        else if(key==='dropoff'){path='dropoff-task';body={taskNo:v('dt_no'),dropoffLine:v('dt_d_line'),dropoffCol:v('dt_d_col'),dropoffRow:v('dt_d_row'),cargoWidth:v('dt_w'),cargoHeight:v('dt_h'),cargoDepth:v('dt_d')};}
        else{path='clear-task';body={taskNo:v('clt_no')};}
        const ok=await qlConfirm('下发确认','任务号'+body.taskNo+'，确认下发？'); if(!ok) return;
        try{
            await api(`/crane/${d.deviceId}/${path}`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
            if(key==='move'||key==='carry') showCommandMotion(key);
            toast('已下发 #'+body.taskNo);
        }catch(e){toast(e.message,false);}
    };

    function setText(id,val){const e=document.getElementById(id);if(e) e.textContent=(val===null||val===undefined||val==='')?'--':val;}

    // 微交互：数值变化时闪一下
    function flash(el){ if(!el) return; el.classList.remove('crane-flash-anim'); void el.offsetWidth; el.classList.add('crane-flash-anim'); }
    // 微交互：整数平滑滚动（count-up）
    function animateNum(el, from, to, dur){
        if(el._numRaf) cancelAnimationFrame(el._numRaf);
        const start=performance.now(), span=to-from;
        const step=now=>{
            const t=Math.min(1,(now-start)/dur);
            const eased=1-Math.pow(1-t,3);
            el.textContent=Math.round(from+span*eased);
            if(t<1) el._numRaf=requestAnimationFrame(step); else el._numRaf=null;
        };
        el._numRaf=requestAnimationFrame(step);
    }
    // 关键数值：数字则滚动+闪烁，文本则直接换+闪烁
    function setSmartNum(id,val){
        const el=document.getElementById(id); if(!el) return;
        const empty=val===null||val===undefined||val==='';
        const newNum=Number(val);
        if(!empty && Number.isFinite(newNum)){
            const oldNum=Number(el.dataset.num);
            if(el.dataset.num!==undefined && Number.isFinite(oldNum) && oldNum!==newNum){
                flash(el); animateNum(el, oldNum, newNum, 500);
            } else { el.textContent=newNum; }
            el.dataset.num=String(newNum);
        } else {
            const txt=empty?'--':String(val);
            if(el.textContent!==txt && el.textContent!=='--' && txt!=='--') flash(el);
            el.textContent=txt; delete el.dataset.num;
        }
    }
    function setField(id,val,cls){setText(id,val);const w=document.getElementById('wrap_'+id);if(w){w.classList.remove('alarm','success','warn');if(cls)w.classList.add(cls);}}
    function setClass(id, className, on) {
        const e = document.getElementById(id);
        if (e) e.classList.toggle(className, !!on);
    }

    function clearMotionClasses() {
        const view = document.querySelector('.crane-machine-view');
        if (view) view.classList.remove('is-traveling','is-lifting','motion-forward','motion-reverse','motion-up','motion-down','motion-pending');
        setClass('c_h_motion','active',false);
        setClass('c_v_motion','active',false);
        setText('c_h_motion_text','停止');
        setText('c_v_motion_text','停止');
    }

    function showMotion(options) {
        const view = document.querySelector('.crane-machine-view');
        if (!view) return;
        clearTimeout(motionTimer);
        clearMotionClasses();
        void view.offsetWidth;
        if (options.pending) view.classList.add('motion-pending');
        if (options.travel) view.classList.add('is-traveling',options.horizontalDelta < 0 ? 'motion-reverse' : 'motion-forward');
        if (options.lift) view.classList.add('is-lifting',options.verticalDelta < 0 ? 'motion-down' : 'motion-up');
        setClass('c_h_motion','active',options.travel);
        setClass('c_v_motion','active',options.lift);
        setText('c_h_motion_text',options.travel?(options.horizontalDelta<0?'反向':'正向'):'停止');
        setText('c_v_motion_text',options.lift?(options.verticalDelta<0?'下降':'上升'):'停止');
        setText('c_motion_label',options.label||'设备运动中');
        motionTimer=setTimeout(clearMotionClasses,options.pending?2200:1600);
    }

    function showCommandMotion(key) {
        showMotion({pending:true,label:key==='move'?'行走指令已下发 · 等待位移反馈':'搬运指令已下发 · 等待设备动作'});
    }

    function updateMotion(s) {
        const horizontal = Number(s.dockHorizontalPulse);
        const vertical = Number(s.dockVerticalPulse);
        if (!Number.isFinite(horizontal) || !Number.isFinite(vertical)) return;
        if (lastMotion) {
            const horizontalDelta = horizontal - lastMotion.horizontal;
            const verticalDelta = vertical - lastMotion.vertical;
            if (horizontalDelta || verticalDelta) {
                const travel = horizontalDelta !== 0;
                const lift = verticalDelta !== 0;
                showMotion({
                    travel,lift,horizontalDelta,verticalDelta,
                    label:travel&&lift?'行走与提升同步运动':travel?'堆垛机正在行走':'载货台正在升降'
                });
            }
        }
        lastMotion={horizontal,vertical};
    }

    function validCoordinate(value) {
        const n = Number(value);
        return Number.isFinite(n) && n > 0 ? Math.round(n) : 0;
    }

    function coordinateSide(f1, f2) {
        const feedback = `${f1.colBackLabel || ''} ${f2.colBackLabel || ''}`;
        const left = feedback.match(/左[一二]排/)?.[0];
        const right = feedback.match(/右[一二]排/)?.[0];
        if (left && right) return { key:'both', label:`${left} / ${right}` };
        if (left) return { key:'left', label:left };
        if (right) return { key:'right', label:right };
        return { key:'center', label:feedback.includes('中位') ? '中位' : '--' };
    }

    function updateRackCoordinates(side, currentCol, currentLevel, activeSide) {
        const rackEl = document.querySelector(`[data-rack-side="${side}"]`);
        if (!rackEl) return;
        const hasCoordinate = currentCol > 0 && currentLevel > 0;
        const colValues = Array.from({length:4}, (_, i) => hasCoordinate ? Math.max(1, currentCol - 1 + i) : 0);
        const levelValues = Array.from({length:5}, (_, i) => hasCoordinate ? Math.max(1, currentLevel + 2 - i) : 0);

        const sideActive = activeSide === side || activeSide === 'both';
        rackEl.classList.toggle('is-current-side', sideActive);
        rackEl.querySelectorAll('[data-col-label]').forEach((el, i) => { el.textContent = colValues[i] ? `${colValues[i]}列` : '--'; });
        rackEl.querySelectorAll('[data-level-label]').forEach((el, i) => { el.textContent = levelValues[i] ? `${levelValues[i]}层` : '--'; });
        rackEl.querySelectorAll('[data-rack-cell]').forEach(cell => {
            const rowIndex = Number(cell.dataset.rowIndex);
            const colIndex = Number(cell.dataset.colIndex);
            const col = colValues[colIndex];
            const level = levelValues[rowIndex];
            const current = hasCoordinate && sideActive && col === currentCol && level === currentLevel;
            cell.classList.toggle('is-current-position', current);
            cell.setAttribute('aria-label', col && level ? `${side === 'left' ? '左侧' : '右侧'} ${col}列 ${level}层${current ? '，当前位置' : ''}` : '坐标未连接');
            const marker = cell.querySelector('span');
            if (marker) marker.textContent = current ? '当前' : '';
        });
        setText(`c_${side}_rack_state`, sideActive ? '当前作业侧' : (hasCoordinate ? '邻近货位' : '待定位'));
    }

    function updateCoordinateView(s, f1, f2) {
        const currentLevel = validCoordinate(s.rowStation);
        const currentCol = validCoordinate(f1.colStation) || validCoordinate(f2.colStation);
        const side = coordinateSide(f1, f2);
        setSmartNum('c_viz_line', side.label);
        setSmartNum('c_viz_col', currentCol || '--');
        setSmartNum('c_viz_row', currentLevel || '--');
        updateRackCoordinates('left', currentCol, currentLevel, side.key);
        updateRackCoordinates('right', currentCol, currentLevel, side.key);

        const carriage = document.getElementById('c_visual_carriage');
        if (carriage) {
            carriage.classList.toggle('is-positioned', currentLevel > 0);
            carriage.dataset.position = currentCol && currentLevel ? `${side.label} ${currentCol}列 ${currentLevel}层` : '坐标未就绪';
        }
    }

    const sparkData = { h:[], v:[] };
    let lastSparkPulse = { h:null, v:null };
    function pushSpark(key, value) {
        const num = Number(value);
        if (!Number.isFinite(num)) return;
        const prev = lastSparkPulse[key];
        lastSparkPulse[key] = num;
        if (prev !== null) {
            const buf = sparkData[key];
            buf.push(Math.min(999999, Math.abs(num - prev)));
            if (buf.length > 30) buf.shift();
        }
        drawSpark(key);
    }
    function drawSpark(key) {
        const cv = document.getElementById('c_' + key + '_spark');
        if (!cv || !cv.getContext) return;
        const ctx = cv.getContext('2d'), w = cv.width, h = cv.height;
        ctx.clearRect(0, 0, w, h);
        const data = sparkData[key];
        if (data.length < 2) return;
        const max = Math.max(1, ...data);
        const color = key === 'h' ? '#38bdf8' : '#a78bfa';
        const pts = data.map((d, i) => [i / (data.length - 1) * (w - 2) + 1, h - 1 - (d / max) * (h - 4)]);
        const grad = ctx.createLinearGradient(0, 0, 0, h);
        grad.addColorStop(0, key === 'h' ? 'rgba(56,189,248,.34)' : 'rgba(167,139,250,.34)');
        grad.addColorStop(1, 'rgba(0,0,0,0)');
        ctx.beginPath(); ctx.moveTo(pts[0][0], h);
        pts.forEach(p => ctx.lineTo(p[0], p[1]));
        ctx.lineTo(pts[pts.length - 1][0], h); ctx.closePath();
        ctx.fillStyle = grad; ctx.fill();
        ctx.beginPath();
        pts.forEach((p, i) => i ? ctx.lineTo(p[0], p[1]) : ctx.moveTo(p[0], p[1]));
        ctx.strokeStyle = color; ctx.lineWidth = 1.4; ctx.lineJoin = 'round'; ctx.stroke();
    }
    function clearSpark() {
        sparkData.h.length = 0; sparkData.v.length = 0;
        lastSparkPulse = { h:null, v:null };
        drawSpark('h'); drawSpark('v');
    }

    // 任务阶段进度（按 taskStatusLabel 关键字 + 任务号推断，coarse-grained）
    const PHASES = ['idle', 'pickup', 'travel', 'dropoff', 'done'];
    function resolvePhase(s) {
        const label = String(s.taskStatusLabel || '');
        const taskNo = s.taskNo;
        const hasTask = taskNo && taskNo !== 0 && taskNo !== '0' && taskNo !== '--';
        if (s.taskStatus === 5 || /完成|成功/.test(label)) return 'done';
        if (!hasTask) return 'idle';
        if (/放货|卸货|卸料/.test(label)) return 'dropoff';
        if (/取货|取料|叉取|拣货/.test(label)) return 'pickup';
        if (/行走|搬运|移动|运行|前往|运输/.test(label)) return 'travel';
        return 'idle';
    }
    function updatePhaseTrack(s) {
        const track = document.getElementById('c_phase_track');
        if (!track) return;
        const idx = PHASES.indexOf(resolvePhase(s));
        track.querySelectorAll('.crane-phase-step').forEach(step => {
            const i = PHASES.indexOf(step.dataset.phase);
            step.classList.toggle('is-active', i === idx);
            step.classList.toggle('is-done', i < idx);
        });
    }

    function updateStatus(s) {
        setText('c_refresh', s.refreshTime||'');
        const running = s.workMode === 3;
        const standby = s.workMode === 2;
        const modeAlarm = s.workMode === 4 || s.workMode === 5 || s.workMode === 7;
        setField('c_workMode',s.workModeLabel,modeAlarm?'alarm':running?'success':standby?'warn':'');
        setSmartNum('c_taskNo',s.taskNo || '--');
        setField('c_taskStatus',s.taskStatusLabel,s.taskStatus===4||s.taskStatus===6||s.taskStatus===7?'warn':s.taskStatus===5?'success':'');
        setSmartNum('c_rowStation',s.rowStation);
        setText('c_hPulse',s.dockHorizontalPulse); setText('c_vPulse',s.dockVerticalPulse);
        updateMotion(s);
        pushSpark('h', s.dockHorizontalPulse); pushSpark('v', s.dockVerticalPulse);
        updatePhaseTrack(s);
        const alarmList=Array.isArray(s.alarmList)?s.alarmList.filter(Boolean):[];
        const hasAlarm=alarmList.length>0||(s.alarmMessage&&s.alarmMessage!=='无'&&s.alarmMessage!=='0');
        // 状态条空间有限：单条显示全名，多条显示"首条 +N"，完整清单在报警横幅
        const alarmBrief=alarmList.length>1
            ? alarmList[0]+' +'+(alarmList.length-1)
            : (alarmList[0]||s.alarmMessage||'无');
        setField('c_alarm',hasAlarm?alarmBrief:'无',hasAlarm?'alarm':'');
        const alarmTitle=document.getElementById('wrap_c_alarm');
        if(alarmTitle) alarmTitle.title=hasAlarm?alarmList.join('\n')||s.alarmMessage:'';
        const f1=s.fork1||{},f2=s.fork2||{},r=s.result||{};
        const f1Loaded=f1.hasLoad===2,f2Loaded=f2.hasLoad===2;
        const f1Active=f1.active===2||f1.active===3,f2Active=f2.active===2||f2.active===3;
        // 货载状态：由双货叉载货检测汇总，有货时绿色高亮
        const anyLoad=f1Loaded||f2Loaded;
        const cargoText=f1Loaded&&f2Loaded?'双叉有货'
            :f1Loaded?'货叉1有货'
            :f2Loaded?'货叉2有货'
            :((f1.hasLoad||f2.hasLoad)?'无货':'--');
        setField('c_cargoState',cargoText,anyLoad?'success':'');
        setText('c_f1_pulse',f1.pulse);setText('c_f1_col',f1.colStation);setField('c_f1_valid',f1.colValidLabel,f1.colValid===2?'success':'warn');
        setText('c_f1_back',f1.colBackLabel);setField('c_f1_load',f1.hasLoadLabel,f1Loaded?'success':'');setField('c_f1_active',f1.activeLabel,f1Active?'warn':'');
        setText('c_f2_pulse',f2.pulse);setText('c_f2_col',f2.colStation);setField('c_f2_valid',f2.colValidLabel,f2.colValid===2?'success':'warn');
        setText('c_f2_back',f2.colBackLabel);setField('c_f2_load',f2.hasLoadLabel,f2Loaded?'success':'');setField('c_f2_active',f2.activeLabel,f2Active?'warn':'');
        setText('c_r_cmd',r.commandType);setSmartNum('c_r_taskNo',r.taskNo);
        const resultText=String(r.resultType||'').toUpperCase();
        setField('c_r_type',r.resultType,/SUCCESS|完成|成功/.test(resultText)?'success':/FAIL|ERROR|失败|异常/.test(resultText)?'alarm':'');
        setField('c_r_code',r.resultCode,r.resultCode&&r.resultCode!==0?'alarm':'');

        setText('c_viz_dock',s.dockStateLabel);
        updateCoordinateView(s,f1,f2);
        setText('c_viz_task',s.taskNo || '无任务');
        // 报警横幅：列出全部报警条目（含位号），方便现场逐条排查
        const banner=document.getElementById('c_alarm_detail');
        if(banner){
            if(hasAlarm&&alarmList.length){
                const codes=Array.isArray(s.alarmCodes)?s.alarmCodes:[];
                banner.innerHTML=alarmList.map((m,i)=>
                    `<span class="crane-alarm-line"><em>${codes[i]!==undefined?codes[i]:'-'}</em>${m}</span>`).join('');
            } else {
                banner.textContent=hasAlarm?(s.alarmMessage||'有报警'):'系统正常';
            }
        }
        setText('c_f1_summary',(f1.activeLabel||'--')+' · '+(f1.hasLoadLabel||'--'));
        setText('c_f2_summary',(f2.activeLabel||'--')+' · '+(f2.hasLoadLabel||'--'));
        setClass('crane_console','is-running',running);
        setClass('crane_console','has-alarm',hasAlarm||modeAlarm);
        setClass('c_alarm_banner','active',hasAlarm);
        setClass('c_visual_load','has-load',f1Loaded||f2Loaded);
        setClass('c_visual_fork1','active',f1Active);
        setClass('c_visual_fork2','active',f2Active);
        setClass('c_fork_card_1','has-load',f1Loaded);
        setClass('c_fork_card_1','is-active',f1Active);
        setClass('c_fork_card_2','has-load',f2Loaded);
        setClass('c_fork_card_2','is-active',f2Active);
    }

    window.qlPanels[TYPE] = {
        render, init(){window.craneTab('carry');},
        onConnected(d){
            lastMotion=null;
            clearSpark();
            startPolling(async()=>{const s=await api(`/crane/${d.deviceId}/status`);updateStatus(s);});
        },
        onDisconnected(){
            lastMotion=null;
            clearTimeout(motionTimer);
            clearMotionClasses();
            clearSpark();
            updateCoordinateView({rowStation:0},{},{});
            setText('c_refresh','等待连接');
        }
    };
})();
