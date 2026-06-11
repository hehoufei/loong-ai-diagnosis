/**
 * 模板管理器弹窗 UI
 * 职责：打开/关闭弹窗、模板列表展示、新增/编辑表单
 */

let ctmModalEl = null;
let ctmEditingId = null;

// 默认容器模板
const CTM_DEFAULT_CONTAINER = {
    containerCode: "C_1223",
    containerType: "PALLET",
    size: { length: "1160", width: "1160", height: "1600", unit: "mm" },
    weight: { value: "500", unit: "kg" },
    innerSize: { length: "1100", width: "1100", height: "1500", unit: "mm" },
    loadHeightOffset: { length: "50", unit: "mm" }
};

// 默认货物模板
const CTM_DEFAULT_GOODS = {
    goodsCode: "GS_1223",
    goodsType: "GOODS",
    size: { length: "800", width: "900", height: "1000", unit: "mm" },
    weight: { value: "200", unit: "kg" }
};

// ============ 打开/关闭 ============
function openTemplateManager() {
    if (!ctmModalEl) ctmCreateModal();
    ctmModalEl.classList.add('show');
    ctmRenderModalList();
}

function closeTemplateManager() {
    if (ctmModalEl) ctmModalEl.classList.remove('show');
    ctmEditingId = null;
}

// ============ 创建 Modal DOM ============
function ctmCreateModal() {
    const overlay = document.createElement('div');
    overlay.className = 'ctm-overlay';
    overlay.innerHTML = `
        <div class="ctm-modal">
            <div class="ctm-modal-head">
                <div class="ctm-modal-title">⚙️ 模板管理器</div>
                <div class="ctm-modal-actions">
                    <button class="ctm-btn primary" onclick="ctmShowForm(null)">＋ 新建模板</button>
                    <button class="ctm-btn close" onclick="closeTemplateManager()">✕ 关闭</button>
                </div>
            </div>
            <div class="ctm-modal-body">
                <div id="ctmListView"></div>
                <div id="ctmFormView" style="display:none;"></div>
            </div>
        </div>
    `;
    document.body.appendChild(overlay);
    ctmModalEl = overlay;
}

// ============ 模板列表视图 ============
function ctmRenderModalList() {
    const listEl = document.getElementById('ctmListView');
    const formEl = document.getElementById('ctmFormView');
    if (!listEl) return;
    formEl.style.display = 'none';
    listEl.style.display = 'block';

    if (customTemplates.length === 0) {
        listEl.innerHTML = '<div class="ctm-empty">暂无自定义模板，点击「新建模板」开始配置</div>';
        return;
    }

    let html = '<table class="ctm-table"><thead><tr><th>图标</th><th>名称</th><th>分组</th><th>类型</th><th>任务类型</th><th>起点</th><th>终点</th><th>操作</th></tr></thead><tbody>';
    customTemplates.forEach(t => {
        const typeLabel = t.type === 'group' ? '<span style="color:#d97706;font-weight:700;">GROUP</span>' : `<span style="color:var(--color-primary);font-weight:700;">${t.taskType||'N2N'}</span>`;
        const start = t.type === 'group' ? (t.mainTask?.defaultStartNode || '-') : (t.defaultStartNode || '-');
        const end = t.type === 'group' ? (t.mainTask?.defaultEndNode || '-') : (t.defaultEndNode || '-');
        html += `<tr>
            <td>${t.icon || '📋'}</td>
            <td style="font-weight:600;">${t.name}</td>
            <td>${t.group || '自定义模板'}</td>
            <td>${t.type === 'group' ? '任务组' : '单任务'}</td>
            <td>${typeLabel}</td>
            <td style="font-family:var(--font-mono);font-size:11px;">${start}</td>
            <td style="font-family:var(--font-mono);font-size:11px;">${end}</td>
            <td>
                <button class="ctm-btn-sm" onclick="ctmShowForm('${t.id}')">✏️ 编辑</button>
                <button class="ctm-btn-sm del" onclick="ctmDeleteAndRefresh('${t.id}')">🗑️</button>
            </td>
        </tr>`;
    });
    html += '</tbody></table>';
    listEl.innerHTML = html;
}

async function ctmDeleteAndRefresh(id) {
    await ctmDelete(id);
    ctmRenderModalList();
}

// ============ 新增/编辑表单 ============
function ctmShowForm(id) {
    ctmEditingId = id;
    const listEl = document.getElementById('ctmListView');
    const formEl = document.getElementById('ctmFormView');
    listEl.style.display = 'none';
    formEl.style.display = 'block';

    let tpl = null;
    if (id) tpl = customTemplates.find(t => t.id === id);

    // 填充默认值
    const name = tpl?.name || '';
    const icon = tpl?.icon || '📋';
    const group = tpl?.group || '自定义模板';
    const type = tpl?.type || 'single';
    // 单任务
    const taskType = tpl?.taskType || 'S2N';
    const startNode = tpl?.defaultStartNode || '';
    const endNode = tpl?.defaultEndNode || '';
    const independentRun = tpl?.independentRun || '';
    const remark = tpl?.remark || '';
    const taskSource = tpl?.taskSource || 'WMS';
    const bizType = tpl?.bizType || '默认';
    const bizPriority = tpl?.bizPriority ?? 0;
    const preStartTaskNo = tpl?.preStartTaskNo || '';
    const preEndTaskNo = tpl?.preEndTaskNo || '';
    const expectedStartTime = tpl?.expectedStartTime || '';
    const expectedFinishTime = tpl?.expectedFinishTime || '';
    const functions = tpl?.functions || [];
    const extraFields = tpl?.extraFields || [];
    const containerInfo = tpl?.containerInfo || null;
    const goodsInfoList = tpl?.goodsInfoList || null;
    // 任务组
    const groupType = tpl?.groupType || 'HALF_ADD_CONTAINER';
    const groupBizPriority = tpl?.bizPriority ?? 0;
    const mainTask = tpl?.mainTask || { taskType: 'N2N', defaultStartNode: '', defaultEndNode: '', independentRun: 'NO', functions: [], containerInfo: null, goodsInfoList: null };
    const subTask = tpl?.subTask || { taskType: 'N2N', defaultStartNode: '', defaultEndNode: '', independentRun: 'NO', functions: [], containerInfo: null, goodsInfoList: null };

    const containerJson = JSON.stringify(containerInfo || CTM_DEFAULT_CONTAINER, null, 2);
    const goodsJson = JSON.stringify(goodsInfoList || [CTM_DEFAULT_GOODS], null, 2);
    const mainContainerJson = JSON.stringify(mainTask.containerInfo || CTM_DEFAULT_CONTAINER, null, 2);
    const mainGoodsJson = JSON.stringify(mainTask.goodsInfoList || [CTM_DEFAULT_GOODS], null, 2);
    const subContainerJson = JSON.stringify(subTask.containerInfo || CTM_DEFAULT_CONTAINER, null, 2);
    const subGoodsJson = JSON.stringify(subTask.goodsInfoList || [CTM_DEFAULT_GOODS], null, 2);

    formEl.innerHTML = `
        <div class="ctm-form-head">
            <button class="ctm-btn" onclick="ctmRenderModalList()">← 返回列表</button>
            <span style="font-weight:700;font-size:14px;">${id ? '编辑模板' : '新建模板'}</span>
        </div>
        <div class="ctm-form">
            <!-- 基本信息 -->
            <div class="ctm-section-title">📌 基本信息</div>
            <div class="ctm-form-row">
                <div class="ctm-form-field"><label>模板名称 *</label><input id="ctmF_name" value="${name}" placeholder="如：缠膜机任务"></div>
                <div class="ctm-form-field" style="width:80px;"><label>图标</label><input id="ctmF_icon" value="${icon}" style="text-align:center;font-size:18px;"></div>
                <div class="ctm-form-field"><label>分组</label><input id="ctmF_group" value="${group}" placeholder="自定义模板"></div>
                <div class="ctm-form-field" style="width:120px;"><label>模板类型</label>
                    <select id="ctmF_type" onchange="ctmToggleType(this.value)">
                        <option value="single" ${type==='single'?'selected':''}>单任务</option>
                        <option value="group" ${type==='group'?'selected':''}>任务组</option>
                    </select>
                </div>
            </div>

            <!-- ========== 单任务配置 ========== -->
            <div id="ctmSingleSection" style="${type==='group'?'display:none':''}">
                <div class="ctm-section-title">📦 单任务配置</div>
                <div class="ctm-form-row">
                    <div class="ctm-form-field"><label>任务类型 (taskType)</label>
                        <select id="ctmF_taskType">
                            <option ${taskType==='N2N'?'selected':''}>N2N</option>
                            <option ${taskType==='N2S'?'selected':''}>N2S</option>
                            <option ${taskType==='S2N'?'selected':''}>S2N</option>
                            <option ${taskType==='S2S'?'selected':''}>S2S</option>
                        </select>
                    </div>
                    <div class="ctm-form-field"><label>默认起点 (startNode)</label><input id="ctmF_startNode" value="${startNode}"></div>
                    <div class="ctm-form-field"><label>默认终点 (endNode)</label><input id="ctmF_endNode" value="${endNode}"></div>
                </div>
                <div class="ctm-form-row">
                    <div class="ctm-form-field"><label>任务来源 (taskSource)</label><input id="ctmF_taskSource" value="${taskSource}" placeholder="WMS"></div>
                    <div class="ctm-form-field"><label>业务类型 (bizType)</label><input id="ctmF_bizType" value="${bizType}" placeholder="默认"></div>
                    <div class="ctm-form-field" style="width:100px;"><label>优先级 (bizPriority)</label><input id="ctmF_bizPriority" type="number" value="${bizPriority}"></div>
                    <div class="ctm-form-field"><label>独立运行 (independentRun)</label>
                        <select id="ctmF_independentRun">
                            <option value="" ${independentRun===''?'selected':''}>空</option>
                            <option value="YES" ${independentRun==='YES'?'selected':''}>YES</option>
                            <option value="NO" ${independentRun==='NO'?'selected':''}>NO</option>
                        </select>
                    </div>
                </div>
                <div class="ctm-form-row">
                    <div class="ctm-form-field"><label>备注 (remark)</label><input id="ctmF_remark" value="${remark}"></div>
                    <div class="ctm-form-field"><label>前置开始任务号 (preStartTaskNo)</label><input id="ctmF_preStartTaskNo" value="${preStartTaskNo}" placeholder="可为空"></div>
                    <div class="ctm-form-field"><label>前置结束任务号 (preEndTaskNo)</label><input id="ctmF_preEndTaskNo" value="${preEndTaskNo}" placeholder="可为空"></div>
                </div>
                <div class="ctm-form-row">
                    <div class="ctm-form-field"><label>期望开始时间 (expectedStartTime)</label><input id="ctmF_expectedStartTime" value="${expectedStartTime}" placeholder="可为空"></div>
                    <div class="ctm-form-field"><label>期望完成时间 (expectedFinishTime)</label><input id="ctmF_expectedFinishTime" value="${expectedFinishTime}" placeholder="可为空"></div>
                </div>

                <div class="ctm-section-title">🔧 功能列表 (requiredFunctionList)</div>
                <div id="ctmFuncList"></div>
                <button class="ctm-btn" onclick="ctmAddFunc('ctmFuncList')" style="margin-top:6px;">＋ 添加功能</button>

                <div class="ctm-section-title">📦 容器信息 (containerInfo) <span style="font-weight:400;font-size:10px;color:var(--color-text-muted);">JSON 格式</span></div>
                <textarea id="ctmF_containerInfo" class="ctm-json-editor" rows="8">${ctmEscapeHtml(containerJson)}</textarea>

                <div class="ctm-section-title">📋 货物列表 (goodsInfoList) <span style="font-weight:400;font-size:10px;color:var(--color-text-muted);">JSON 数组格式</span></div>
                <textarea id="ctmF_goodsInfoList" class="ctm-json-editor" rows="8">${ctmEscapeHtml(goodsJson)}</textarea>

                <div class="ctm-section-title">📝 额外字段 (extraFields) <span style="font-weight:400;font-size:10px;color:var(--color-text-muted);">队列卡片上额外展示的可编辑参数</span></div>
                <div id="ctmExtraList"></div>
                <button class="ctm-btn" onclick="ctmAddExtra('ctmExtraList')" style="margin-top:6px;">＋ 添加字段</button>
            </div>

            <!-- ========== 任务组配置 ========== -->
            <div id="ctmGroupSection" style="${type==='single'?'display:none':''}">
                <div class="ctm-section-title">📋 任务组配置</div>
                <div class="ctm-form-row">
                    <div class="ctm-form-field"><label>组类型 (groupType)</label>
                        <select id="ctmF_groupType">
                            <option ${groupType==='HALF_ADD_CONTAINER'?'selected':''} value="HALF_ADD_CONTAINER">HALF_ADD_CONTAINER</option>
                            <option ${groupType==='ADD_CONTAINER'?'selected':''} value="ADD_CONTAINER">ADD_CONTAINER</option>
                            <option ${groupType==='HALF_REMOVE_ADD_CONTAINER'?'selected':''} value="HALF_REMOVE_ADD_CONTAINER">HALF_REMOVE_ADD_CONTAINER</option>
                            <option ${groupType==='REMOVE_ADD_CONTAINER'?'selected':''} value="REMOVE_ADD_CONTAINER">REMOVE_ADD_CONTAINER</option>
                            <option ${groupType==='REMOVE_CONTAINER'?'selected':''} value="REMOVE_CONTAINER">REMOVE_CONTAINER</option>
                            <option ${groupType==='REMOVE_ADD_GOODS'?'selected':''} value="REMOVE_ADD_GOODS">REMOVE_ADD_GOODS</option>
                            <option ${groupType==='HALF_REMOVE_ADD_GOODS'?'selected':''} value="HALF_REMOVE_ADD_GOODS">HALF_REMOVE_ADD_GOODS</option>
                        </select>
                    </div>
                    <div class="ctm-form-field" style="width:100px;"><label>组优先级</label><input id="ctmF_groupBizPriority" type="number" value="${groupBizPriority}"></div>
                    <div class="ctm-form-field"><label>组 taskSource</label><input id="ctmF_groupTaskSource" value="${tpl?.taskSource || 'WMS'}" placeholder="WMS"></div>
                </div>
                <div style="display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-top:8px;">
                    <!-- 主任务 -->
                    <div style="padding:10px;background:rgba(99,102,241,0.04);border:1px solid rgba(99,102,241,0.12);border-radius:10px;">
                        <div style="font-size:12px;font-weight:700;color:var(--color-primary);margin-bottom:8px;">🔵 主任务</div>
                        <div class="ctm-form-field"><label>任务类型</label><select id="ctmF_mainTaskType"><option ${mainTask.taskType==='N2N'?'selected':''}>N2N</option><option ${mainTask.taskType==='N2S'?'selected':''}>N2S</option><option ${mainTask.taskType==='S2N'?'selected':''}>S2N</option><option ${mainTask.taskType==='S2S'?'selected':''}>S2S</option></select></div>
                        <div class="ctm-form-field"><label>默认起点</label><input id="ctmF_mainStart" value="${mainTask.defaultStartNode||''}"></div>
                        <div class="ctm-form-field"><label>默认终点</label><input id="ctmF_mainEnd" value="${mainTask.defaultEndNode||''}"></div>
                        <div class="ctm-form-field"><label>独立运行</label><select id="ctmF_mainIndependent"><option value="NO" ${mainTask.independentRun==='NO'?'selected':''}>NO</option><option value="YES" ${mainTask.independentRun==='YES'?'selected':''}>YES</option><option value="" ${mainTask.independentRun===''?'selected':''}>空</option></select></div>
                        <div class="ctm-form-field"><label>taskSource</label><input id="ctmF_mainTaskSource" value="${mainTask.taskSource||'WMS'}"></div>
                        <div class="ctm-form-field"><label>bizType</label><input id="ctmF_mainBizType" value="${mainTask.bizType||'默认'}"></div>
                        <div class="ctm-form-field"><label>bizPriority</label><input id="ctmF_mainBizPriority" type="number" value="${mainTask.bizPriority||0}"></div>
                        <div class="ctm-form-field"><label>remark</label><input id="ctmF_mainRemark" value="${mainTask.remark||'主任务'}"></div>
                        <div class="ctm-form-field"><label>preStartTaskNo</label><input id="ctmF_mainPreStart" value="${mainTask.preStartTaskNo||''}" placeholder="可为空"></div>
                        <div class="ctm-form-field"><label>preEndTaskNo</label><input id="ctmF_mainPreEnd" value="${mainTask.preEndTaskNo||''}" placeholder="可为空"></div>
                        <div class="ctm-form-field"><label>expectedStartTime</label><input id="ctmF_mainExpStart" value="${mainTask.expectedStartTime||''}" placeholder="可为空"></div>
                        <div class="ctm-form-field"><label>expectedFinishTime</label><input id="ctmF_mainExpEnd" value="${mainTask.expectedFinishTime||''}" placeholder="可为空"></div>
                        <div style="margin-top:6px;"><label style="font-size:11px;font-weight:600;">功能列表</label><div id="ctmMainFuncList"></div><button class="ctm-btn" onclick="ctmAddFunc('ctmMainFuncList')" style="margin-top:4px;font-size:11px;">＋ 添加</button></div>
                        <div style="margin-top:8px;"><label style="font-size:11px;font-weight:600;">容器字段名</label><select id="ctmF_mainContainerField" style="height:28px;font-size:11px;border:1px solid rgba(99,102,241,0.12);border-radius:6px;padding:0 6px;"><option value="containerList" ${(mainTask.containerFieldName||'containerList')==='containerList'?'selected':''}>containerList (数组)</option><option value="containerInfo" ${mainTask.containerFieldName==='containerInfo'?'selected':''}>containerInfo (单对象)</option></select></div>
                        <div style="margin-top:6px;"><label style="font-size:11px;font-weight:600;">容器 (JSON)</label><textarea id="ctmF_mainContainer" class="ctm-json-editor" rows="5">${ctmEscapeHtml(mainContainerJson)}</textarea></div>
                        <div style="margin-top:6px;"><label style="font-size:11px;font-weight:600;">货物 (JSON 数组)</label><textarea id="ctmF_mainGoods" class="ctm-json-editor" rows="5">${ctmEscapeHtml(mainGoodsJson)}</textarea></div>
                    </div>
                    <!-- 子任务 -->
                    <div style="padding:10px;background:rgba(16,185,129,0.04);border:1px solid rgba(16,185,129,0.12);border-radius:10px;">
                        <div style="font-size:12px;font-weight:700;color:var(--color-success);margin-bottom:8px;">🟢 子任务</div>
                        <div class="ctm-form-field"><label>任务类型</label><select id="ctmF_subTaskType"><option ${subTask.taskType==='N2N'?'selected':''}>N2N</option><option ${subTask.taskType==='N2S'?'selected':''}>N2S</option><option ${subTask.taskType==='S2N'?'selected':''}>S2N</option><option ${subTask.taskType==='S2S'?'selected':''}>S2S</option></select></div>
                        <div class="ctm-form-field"><label>默认起点</label><input id="ctmF_subStart" value="${subTask.defaultStartNode||''}"></div>
                        <div class="ctm-form-field"><label>默认终点</label><input id="ctmF_subEnd" value="${subTask.defaultEndNode||''}"></div>
                        <div class="ctm-form-field"><label>独立运行</label><select id="ctmF_subIndependent"><option value="NO" ${subTask.independentRun==='NO'?'selected':''}>NO</option><option value="YES" ${subTask.independentRun==='YES'?'selected':''}>YES</option><option value="" ${subTask.independentRun===''?'selected':''}>空</option></select></div>
                        <div class="ctm-form-field"><label>taskSource</label><input id="ctmF_subTaskSource" value="${subTask.taskSource||'WMS'}"></div>
                        <div class="ctm-form-field"><label>bizType</label><input id="ctmF_subBizType" value="${subTask.bizType||'默认'}"></div>
                        <div class="ctm-form-field"><label>bizPriority</label><input id="ctmF_subBizPriority" type="number" value="${subTask.bizPriority||0}"></div>
                        <div class="ctm-form-field"><label>remark</label><input id="ctmF_subRemark" value="${subTask.remark||'子任务'}"></div>
                        <div class="ctm-form-field"><label>preStartTaskNo</label><input id="ctmF_subPreStart" value="${subTask.preStartTaskNo||''}" placeholder="可为空"></div>
                        <div class="ctm-form-field"><label>preEndTaskNo</label><input id="ctmF_subPreEnd" value="${subTask.preEndTaskNo||''}" placeholder="可为空"></div>
                        <div class="ctm-form-field"><label>expectedStartTime</label><input id="ctmF_subExpStart" value="${subTask.expectedStartTime||''}" placeholder="可为空"></div>
                        <div class="ctm-form-field"><label>expectedFinishTime</label><input id="ctmF_subExpEnd" value="${subTask.expectedFinishTime||''}" placeholder="可为空"></div>
                        <div style="margin-top:6px;"><label style="font-size:11px;font-weight:600;">功能列表</label><div id="ctmSubFuncList"></div><button class="ctm-btn" onclick="ctmAddFunc('ctmSubFuncList')" style="margin-top:4px;font-size:11px;">＋ 添加</button></div>
                        <div style="margin-top:8px;"><label style="font-size:11px;font-weight:600;">容器字段名</label><select id="ctmF_subContainerField" style="height:28px;font-size:11px;border:1px solid rgba(99,102,241,0.12);border-radius:6px;padding:0 6px;"><option value="containerList" ${(subTask.containerFieldName||'containerList')==='containerList'?'selected':''}>containerList (数组)</option><option value="containerInfo" ${subTask.containerFieldName==='containerInfo'?'selected':''}>containerInfo (单对象)</option></select></div>
                        <div style="margin-top:6px;"><label style="font-size:11px;font-weight:600;">容器 (JSON)</label><textarea id="ctmF_subContainer" class="ctm-json-editor" rows="5">${ctmEscapeHtml(subContainerJson)}</textarea></div>
                        <div style="margin-top:6px;"><label style="font-size:11px;font-weight:600;">货物 (JSON 数组)</label><textarea id="ctmF_subGoods" class="ctm-json-editor" rows="5">${ctmEscapeHtml(subGoodsJson)}</textarea></div>
                    </div>
                </div>
            </div>

            <div style="margin-top:16px;display:flex;gap:8px;justify-content:flex-end;">
                <button class="ctm-btn" onclick="ctmRenderModalList()">取消</button>
                <button class="ctm-btn primary" onclick="ctmSubmitForm()">💾 保存模板</button>
            </div>
        </div>
    `;

    // 渲染动态列表
    ctmRenderFuncItems('ctmFuncList', functions);
    ctmRenderExtraItems('ctmExtraList', extraFields);
    if (type === 'group') {
        ctmRenderFuncItems('ctmMainFuncList', mainTask.functions || []);
        ctmRenderFuncItems('ctmSubFuncList', subTask.functions || []);
    }
}

// ============ 工具函数 ============
function ctmEscapeHtml(str) {
    return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

function ctmParseJson(str, fallback) {
    try { return JSON.parse(str); } catch(e) { return fallback; }
}

// ============ 切换单任务/任务组 ============
function ctmToggleType(val) {
    document.getElementById('ctmSingleSection').style.display = val === 'single' ? '' : 'none';
    document.getElementById('ctmGroupSection').style.display = val === 'group' ? '' : 'none';
}

// ============ 功能列表管理 ============
function ctmRenderFuncItems(containerId, items) {
    const el = document.getElementById(containerId);
    if (!el) return;
    el.innerHTML = items.map((f, i) => `
        <div class="ctm-func-row" data-idx="${i}">
            <input class="ctm-func-type" value="${f.functionType || ''}" placeholder="functionType (如 FILM_WRAPPING_BAGGING_NODE)" style="flex:1;">
            <input class="ctm-func-ext" value='${JSON.stringify(f.extData || {}).replace(/'/g, "&#39;")}' placeholder='extData JSON (如 {"KEY":"VAL"})' style="flex:1;">
            <button class="ctm-btn-sm del" onclick="this.parentElement.remove()">✕</button>
        </div>
    `).join('');
}

function ctmAddFunc(containerId) {
    const el = document.getElementById(containerId);
    if (!el) return;
    const div = document.createElement('div');
    div.className = 'ctm-func-row';
    div.innerHTML = `
        <input class="ctm-func-type" value="" placeholder="functionType" style="flex:1;">
        <input class="ctm-func-ext" value="{}" placeholder='extData JSON' style="flex:1;">
        <button class="ctm-btn-sm del" onclick="this.parentElement.remove()">✕</button>
    `;
    el.appendChild(div);
}

function ctmCollectFuncs(containerId) {
    const el = document.getElementById(containerId);
    if (!el) return [];
    const rows = el.querySelectorAll('.ctm-func-row');
    const result = [];
    rows.forEach(row => {
        const ft = row.querySelector('.ctm-func-type').value.trim();
        const extStr = row.querySelector('.ctm-func-ext').value.trim();
        if (!ft) return;
        let extData = {};
        try { extData = JSON.parse(extStr); } catch(e) { /* ignore */ }
        result.push({ functionType: ft, extData });
    });
    return result;
}

// ============ 额外字段管理 ============
function ctmRenderExtraItems(containerId, items) {
    const el = document.getElementById(containerId);
    if (!el) return;
    el.innerHTML = items.map((f, i) => `
        <div class="ctm-extra-row" data-idx="${i}">
            <input class="ctm-extra-key" value="${f.key || ''}" placeholder="key" style="width:120px;">
            <input class="ctm-extra-label" value="${f.label || ''}" placeholder="显示名称" style="width:120px;">
            <input class="ctm-extra-default" value="${f.defaultValue || ''}" placeholder="默认值" style="width:100px;">
            <select class="ctm-extra-fieldtype" style="width:80px;">
                <option value="text" ${f.fieldType==='text'?'selected':''}>text</option>
                <option value="number" ${f.fieldType==='number'?'selected':''}>number</option>
                <option value="select" ${f.fieldType==='select'?'selected':''}>select</option>
            </select>
            <input class="ctm-extra-target" value="${f.targetFunction || ''}" placeholder="目标function" style="width:140px;">
            <button class="ctm-btn-sm del" onclick="this.parentElement.remove()">✕</button>
        </div>
    `).join('');
}

function ctmAddExtra(containerId) {
    const el = document.getElementById(containerId);
    if (!el) return;
    const div = document.createElement('div');
    div.className = 'ctm-extra-row';
    div.innerHTML = `
        <input class="ctm-extra-key" value="" placeholder="key" style="width:120px;">
        <input class="ctm-extra-label" value="" placeholder="显示名称" style="width:120px;">
        <input class="ctm-extra-default" value="" placeholder="默认值" style="width:100px;">
        <select class="ctm-extra-fieldtype" style="width:80px;">
            <option value="text">text</option>
            <option value="number">number</option>
            <option value="select">select</option>
        </select>
        <input class="ctm-extra-target" value="" placeholder="目标function" style="width:140px;">
        <button class="ctm-btn-sm del" onclick="this.parentElement.remove()">✕</button>
    `;
    el.appendChild(div);
}

function ctmCollectExtras(containerId) {
    const el = document.getElementById(containerId);
    if (!el) return [];
    const rows = el.querySelectorAll('.ctm-extra-row');
    const result = [];
    rows.forEach(row => {
        const key = row.querySelector('.ctm-extra-key').value.trim();
        const label = row.querySelector('.ctm-extra-label').value.trim();
        const defaultValue = row.querySelector('.ctm-extra-default').value.trim();
        const fieldType = row.querySelector('.ctm-extra-fieldtype').value;
        const targetFunction = row.querySelector('.ctm-extra-target').value.trim();
        if (!key) return;
        result.push({ key, label, defaultValue, fieldType, targetFunction });
    });
    return result;
}

// ============ 提交表单 ============
async function ctmSubmitForm() {
    const name = document.getElementById('ctmF_name').value.trim();
    if (!name) { alert('请输入模板名称'); return; }

    const type = document.getElementById('ctmF_type').value;
    const tpl = {
        name,
        icon: document.getElementById('ctmF_icon').value.trim() || '📋',
        group: document.getElementById('ctmF_group').value.trim() || '自定义模板',
        type
    };

    if (type === 'single') {
        tpl.taskType = document.getElementById('ctmF_taskType').value;
        tpl.defaultStartNode = document.getElementById('ctmF_startNode').value.trim();
        tpl.defaultEndNode = document.getElementById('ctmF_endNode').value.trim();
        tpl.taskSource = document.getElementById('ctmF_taskSource').value.trim() || 'WMS';
        tpl.bizType = document.getElementById('ctmF_bizType').value.trim() || '默认';
        tpl.bizPriority = parseInt(document.getElementById('ctmF_bizPriority').value) || 0;
        tpl.independentRun = document.getElementById('ctmF_independentRun').value;
        tpl.remark = document.getElementById('ctmF_remark').value.trim();
        tpl.preStartTaskNo = document.getElementById('ctmF_preStartTaskNo').value.trim();
        tpl.preEndTaskNo = document.getElementById('ctmF_preEndTaskNo').value.trim();
        tpl.expectedStartTime = document.getElementById('ctmF_expectedStartTime').value.trim();
        tpl.expectedFinishTime = document.getElementById('ctmF_expectedFinishTime').value.trim();
        tpl.functions = ctmCollectFuncs('ctmFuncList');
        tpl.extraFields = ctmCollectExtras('ctmExtraList');
        // 容器和货物 JSON
        const containerStr = document.getElementById('ctmF_containerInfo').value.trim();
        tpl.containerInfo = ctmParseJson(containerStr, CTM_DEFAULT_CONTAINER);
        const goodsStr = document.getElementById('ctmF_goodsInfoList').value.trim();
        tpl.goodsInfoList = ctmParseJson(goodsStr, [CTM_DEFAULT_GOODS]);
    } else {
        tpl.groupType = document.getElementById('ctmF_groupType').value;
        tpl.bizPriority = parseInt(document.getElementById('ctmF_groupBizPriority').value) || 0;
        tpl.taskSource = document.getElementById('ctmF_groupTaskSource').value.trim() || 'WMS';
        tpl.mainTask = {
            taskType: document.getElementById('ctmF_mainTaskType').value,
            defaultStartNode: document.getElementById('ctmF_mainStart').value.trim(),
            defaultEndNode: document.getElementById('ctmF_mainEnd').value.trim(),
            independentRun: document.getElementById('ctmF_mainIndependent').value,
            taskSource: document.getElementById('ctmF_mainTaskSource').value.trim() || 'WMS',
            bizType: document.getElementById('ctmF_mainBizType').value.trim() || '默认',
            bizPriority: parseInt(document.getElementById('ctmF_mainBizPriority').value) || 0,
            remark: document.getElementById('ctmF_mainRemark').value.trim() || '主任务',
            preStartTaskNo: document.getElementById('ctmF_mainPreStart').value.trim(),
            preEndTaskNo: document.getElementById('ctmF_mainPreEnd').value.trim(),
            expectedStartTime: document.getElementById('ctmF_mainExpStart').value.trim(),
            expectedFinishTime: document.getElementById('ctmF_mainExpEnd').value.trim(),
            functions: ctmCollectFuncs('ctmMainFuncList'),
            containerFieldName: document.getElementById('ctmF_mainContainerField').value,
            containerInfo: ctmParseJson(document.getElementById('ctmF_mainContainer').value.trim(), CTM_DEFAULT_CONTAINER),
            goodsInfoList: ctmParseJson(document.getElementById('ctmF_mainGoods').value.trim(), [CTM_DEFAULT_GOODS])
        };
        tpl.subTask = {
            taskType: document.getElementById('ctmF_subTaskType').value,
            defaultStartNode: document.getElementById('ctmF_subStart').value.trim(),
            defaultEndNode: document.getElementById('ctmF_subEnd').value.trim(),
            independentRun: document.getElementById('ctmF_subIndependent').value,
            taskSource: document.getElementById('ctmF_subTaskSource').value.trim() || 'WMS',
            bizType: document.getElementById('ctmF_subBizType').value.trim() || '默认',
            bizPriority: parseInt(document.getElementById('ctmF_subBizPriority').value) || 0,
            remark: document.getElementById('ctmF_subRemark').value.trim() || '子任务',
            preStartTaskNo: document.getElementById('ctmF_subPreStart').value.trim(),
            preEndTaskNo: document.getElementById('ctmF_subPreEnd').value.trim(),
            expectedStartTime: document.getElementById('ctmF_subExpStart').value.trim(),
            expectedFinishTime: document.getElementById('ctmF_subExpEnd').value.trim(),
            functions: ctmCollectFuncs('ctmSubFuncList'),
            containerFieldName: document.getElementById('ctmF_subContainerField').value,
            containerInfo: ctmParseJson(document.getElementById('ctmF_subContainer').value.trim(), CTM_DEFAULT_CONTAINER),
            goodsInfoList: ctmParseJson(document.getElementById('ctmF_subGoods').value.trim(), [CTM_DEFAULT_GOODS])
        };
    }

    if (ctmEditingId) {
        await ctmUpdate(ctmEditingId, tpl);
    } else {
        await ctmSave(tpl);
    }
    ctmRenderModalList();
}
