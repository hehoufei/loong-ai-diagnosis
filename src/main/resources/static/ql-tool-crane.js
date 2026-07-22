// 青龙调试工具 - 堆垛机面板
(function () {
    const TYPE = 'STACKER_CRANE';

    function fld(label, id) {
        return `<div class="ql-field" id="wrap_${id}"><span class="k">${label}</span><span class="v" id="${id}">--</span></div>`;
    }

    function render(d) {
        const top = `
        <div class="ql-section-title">车体状态 <span class="ql-refresh-time" id="c_refresh"></span></div>
        <div class="ql-grid">
            ${fld('工作模式','c_workMode')}${fld('任务号','c_taskNo')}${fld('任务事件','c_taskStatus')}
            ${fld('当前层号','c_rowStation')}${fld('载货台','c_dockState')}${fld('行走脉冲','c_hPulse')}
            ${fld('提升脉冲','c_vPulse')}${fld('报警','c_alarm')}
        </div>
        <div class="ql-section-title" style="margin-top:10px;">货叉1</div>
        <div class="ql-grid">
            ${fld('伸叉脉冲','c_f1_pulse')}${fld('列位置','c_f1_col')}${fld('位置有效','c_f1_valid')}
            ${fld('位置反馈','c_f1_back')}${fld('有货','c_f1_load')}${fld('动作','c_f1_active')}
        </div>
        <div class="ql-section-title" style="margin-top:10px;">货叉2</div>
        <div class="ql-grid">
            ${fld('伸叉脉冲','c_f2_pulse')}${fld('列位置','c_f2_col')}${fld('位置有效','c_f2_valid')}
            ${fld('位置反馈','c_f2_back')}${fld('有货','c_f2_load')}${fld('动作','c_f2_active')}
        </div>
        <div class="ql-section-title" style="margin-top:10px;">指令反馈</div>
        <div class="ql-grid">
            ${fld('任务类型','c_r_cmd')}${fld('任务号','c_r_taskNo')}${fld('结果','c_r_type')}${fld('错误码','c_r_code')}
        </div>`;

        const bottom = `
        <div class="ql-ops-title">任务下发</div>
        <div class="ql-tabs" id="c_tabs">
            <div class="ql-tab active" data-tab="carry" onclick="craneTab('carry')">搬运</div>
            <div class="ql-tab" data-tab="move" onclick="craneTab('move')">行走</div>
            <div class="ql-tab" data-tab="pickup" onclick="craneTab('pickup')">取货</div>
            <div class="ql-tab" data-tab="dropoff" onclick="craneTab('dropoff')">放货</div>
            <div class="ql-tab" data-tab="clear" onclick="craneTab('clear')">清除</div>
        </div>
        <div id="c_form"></div>`;

        return { top, bottom };
    }

    function inp(label, id, val) {
        return `<div class="ql-ops-item"><label>${label}</label><input class="glass-input" type="number" id="${id}" value="${val||0}" min="0"></div>`;
    }
    function pos3(prefix, title) { return inp(title+'排',prefix+'_line')+inp(title+'列',prefix+'_col')+inp(title+'层',prefix+'_row'); }
    function cargo3(prefix) { return inp('货宽',prefix+'_w')+inp('货高',prefix+'_h')+inp('货深',prefix+'_d'); }
    function btn(fn, label) { return `<div class="ql-ops-item"><label>&nbsp;</label><button class="glass-btn btn-connect" onclick="${fn}">${label||'下发'}</button></div>`; }

    const forms = {
        carry: ()=>`<div class="ql-ops-row">${inp('任务号','ct_no',1)}${pos3('ct_p','取货')}${pos3('ct_d','放货')}${cargo3('ct')}${btn("craneSubmit('carry')")}</div>`,
        move: ()=>`<div class="ql-ops-row">${inp('任务号','mt_no',1)}${pos3('mt_t','目标')}${cargo3('mt')}${btn("craneSubmit('move')")}</div>`,
        pickup: ()=>`<div class="ql-ops-row">${inp('任务号','pt_no',1)}${pos3('pt_p','取货')}${cargo3('pt')}${btn("craneSubmit('pickup')")}</div>`,
        dropoff: ()=>`<div class="ql-ops-row">${inp('任务号','dt_no',1)}${pos3('dt_d','放货')}${cargo3('dt')}${btn("craneSubmit('dropoff')")}</div>`,
        clear: ()=>`<div class="ql-ops-row">${inp('任务号','clt_no',1)}${btn("craneSubmit('clear')","清除")}</div>`
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
        try{await api(`/crane/${d.deviceId}/${path}`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});toast('已下发 #'+body.taskNo);}catch(e){toast(e.message,false);}
    };

    function setText(id,val){const e=document.getElementById(id);if(e) e.textContent=(val===null||val===undefined||val==='')?'--':val;}
    function setField(id,val,cls){setText(id,val);const w=document.getElementById('wrap_'+id);if(w){w.classList.remove('alarm','success','warn');if(cls)w.classList.add(cls);}}

    function updateStatus(s) {
        setText('c_refresh', s.refreshTime||'');
        setField('c_workMode',s.workModeLabel,s.workMode===1?'success':s.workMode===2?'warn':'');
        setText('c_taskNo',s.taskNo); setText('c_taskStatus',s.taskStatusLabel); setText('c_rowStation',s.rowStation);
        setField('c_dockState',s.dockStateLabel,s.dockState===1?'success':'');
        setText('c_hPulse',s.dockHorizontalPulse); setText('c_vPulse',s.dockVerticalPulse);
        const hasAlarm=s.alarmMessage&&s.alarmMessage!=='无'&&s.alarmMessage!=='0';
        setField('c_alarm',hasAlarm?s.alarmMessage:'无',hasAlarm?'alarm':'');
        const f1=s.fork1||{},f2=s.fork2||{},r=s.result||{};
        setText('c_f1_pulse',f1.pulse);setText('c_f1_col',f1.colStation);setField('c_f1_valid',f1.colValidLabel,f1.colValid?'success':'');
        setText('c_f1_back',f1.colBackLabel);setField('c_f1_load',f1.hasLoadLabel,f1.hasLoad?'success':'');setText('c_f1_active',f1.activeLabel);
        setText('c_f2_pulse',f2.pulse);setText('c_f2_col',f2.colStation);setField('c_f2_valid',f2.colValidLabel,f2.colValid?'success':'');
        setText('c_f2_back',f2.colBackLabel);setField('c_f2_load',f2.hasLoadLabel,f2.hasLoad?'success':'');setText('c_f2_active',f2.activeLabel);
        setText('c_r_cmd',r.commandType);setText('c_r_taskNo',r.taskNo);
        setField('c_r_type',r.resultType,r.resultType===1?'success':r.resultType===2?'alarm':'');
        setField('c_r_code',r.resultCode,r.resultCode&&r.resultCode!==0?'alarm':'');
    }

    window.qlPanels[TYPE] = {
        render, init(){window.craneTab('carry');},
        onConnected(d){startPolling(async()=>{const s=await api(`/crane/${d.deviceId}/status`);updateStatus(s);});}
    };
})();
