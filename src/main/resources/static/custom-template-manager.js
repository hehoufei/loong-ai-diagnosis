/**
 * 自定义模板管理器
 * 负责：加载自定义模板、渲染到左侧面板、打开管理弹窗、CRUD 操作、构建 payload
 */

const CTM_API = '/api/v1/custom-templates';
let customTemplates = [];

// ============ API 层 ============

async function ctmFetch(url, options = {}) {
    const resp = await fetch(url, { headers: { 'Content-Type': 'application/json' }, ...options });
    return resp.json();
}

async function ctmLoadAll() {
    try {
        const res = await ctmFetch(CTM_API);
        if (res.code === 200 || res.code === '200') {
            customTemplates = res.data || [];
        } else {
            console.warn('加载自定义模板失败:', res.msg);
            customTemplates = [];
        }
    } catch (e) {
        console.warn('加载自定义模板异常:', e);
        customTemplates = [];
    }
    ctmRenderPanel();
}

async function ctmSave(tpl) {
    const res = await ctmFetch(CTM_API, { method: 'POST', body: JSON.stringify(tpl) });
    if (res.code === 200 || res.code === '200') {
        await ctmLoadAll();
        return res.data;
    } else {
        showToast('保存失败: ' + (res.msg || ''), 'error');
        return null;
    }
}

async function ctmUpdate(id, tpl) {
    const res = await ctmFetch(CTM_API + '/' + id, { method: 'PUT', body: JSON.stringify(tpl) });
    if (res.code === 200 || res.code === '200') {
        await ctmLoadAll();
        return res.data;
    } else {
        showToast('更新失败: ' + (res.msg || ''), 'error');
        return null;
    }
}

async function ctmDelete(id) {
    const confirmed = await showConfirm('确定删除此模板？', { title: '删除确认', okText: '删除', cancelText: '取消' });
    if (!confirmed) return;
    const res = await ctmFetch(CTM_API + '/' + id, { method: 'DELETE' });
    if (res.code === 200 || res.code === '200') {
        await ctmLoadAll();
    } else {
        showToast('删除失败: ' + (res.msg || ''), 'error');
    }
}

// ============ 左侧面板渲染 ============

function ctmRenderPanel() {
    const container = document.getElementById('customTemplateGroups');
    if (!container) return;
    if (customTemplates.length === 0) {
        container.innerHTML = '';
        return;
    }
    // 按 group 分组
    const groups = {};
    customTemplates.forEach(t => {
        const g = t.group || '自定义模板';
        if (!groups[g]) groups[g] = [];
        groups[g].push(t);
    });
    let html = '';
    for (const [groupName, tpls] of Object.entries(groups)) {
        html += `<div class="template-group"><div class="template-group-title">🔧 ${groupName}</div>`;
        tpls.forEach(t => {
            const tag = t.type === 'group' ? 'GROUP' : (t.taskType || 'N2N');
            const tagClass = t.type === 'group' ? 'style="background:rgba(245,158,11,0.1);color:#d97706;"' : '';
            html += `<div class="template-card" onclick="ctmAddToQueue('${t.id}')">
                <span class="t-icon">${t.icon || '📋'}</span>
                <span class="t-name">${t.name}</span>
                <span class="t-tag" ${tagClass}>${tag}</span>
            </div>`;
        });
        html += '</div>';
    }
    container.innerHTML = html;
}

// ============ 自定义模板加入队列 ============

function ctmAddToQueue(tplId) {
    const tpl = customTemplates.find(t => t.id === tplId);
    if (!tpl) { showToast('模板未找到', 'error'); return; }

    if (tpl.type === 'group') {
        const data = {
            type: 'group',
            tplName: tpl.name,
            groupType: tpl.groupType || 'HALF_ADD_CONTAINER',
            _customTpl: tpl,
            // 主任务
            mainStart: tpl.mainTask?.defaultStartNode || '',
            mainEnd: tpl.mainTask?.defaultEndNode || '',
            mainTaskType: tpl.mainTask?.taskType || 'N2N',
            mainIndependent: tpl.mainTask?.independentRun || 'NO',
            mainFuncs: (tpl.mainTask?.functions || []).map(f => f.functionType),
            mainTargetEnd: '',
            // 子任务
            subStart: tpl.subTask?.defaultStartNode || '',
            subEnd: tpl.subTask?.defaultEndNode || '',
            subTaskType: tpl.subTask?.taskType || 'N2N',
            subIndependent: tpl.subTask?.independentRun || 'NO',
            subFunc: (tpl.subTask?.functions || []).length > 0 ? tpl.subTask.functions[0].functionType : 'NONE',
            subTargetEnd: '',
            // 额外字段值
            _extraValues: {}
        };
        const item = { id: genId(), tpl: '__custom_group__', data };
        queue.push(item);
    } else {
        const data = {
            type: 'single',
            tplName: tpl.name,
            taskSource: tpl.taskSource || 'WMS',
            taskType: tpl.taskType || 'N2N',
            bizType: tpl.bizType || '默认',
            bizPriority: tpl.bizPriority || 0,
            independentRun: tpl.independentRun || '',
            startNode: tpl.defaultStartNode || '',
            endNode: tpl.defaultEndNode || '',
            funcs: (tpl.functions || []).map(f => f.functionType),
            remark: tpl.remark || '',
            preStartTaskNo: tpl.preStartTaskNo || '',
            preEndTaskNo: tpl.preEndTaskNo || '',
            expectedStartTime: tpl.expectedStartTime || '',
            expectedFinishTime: tpl.expectedFinishTime || '',
            _customTpl: tpl,
            _extraValues: {}
        };
        // 初始化额外字段默认值
        if (tpl.extraFields) {
            tpl.extraFields.forEach(ef => {
                data._extraValues[ef.key] = ef.defaultValue || '';
            });
        }
        const item = { id: genId(), tpl: '__custom_single__', data };
        queue.push(item);
    }
    renderQueue();
}

// ============ 构建自定义模板 Payload ============

function buildCustomSinglePayload(data) {
    const tpl = data._customTpl;
    const funcs = (tpl.functions || []).map(f => {
        const item = { functionType: f.functionType };
        if (f.extData && Object.keys(f.extData).length > 0) {
            const ext = { ...f.extData };
            // 替换额外字段中引用的值
            if (tpl.extraFields) {
                tpl.extraFields.forEach(ef => {
                    if (ef.targetFunction === f.functionType && data._extraValues[ef.key] !== undefined) {
                        ext[ef.key] = data._extraValues[ef.key];
                    }
                });
            }
            item.extData = ext;
        }
        return item;
    });

    // 也支持通过 UI 勾选的 funcs（和已有模板行为一致）
    const uiFuncs = (data.funcs || []).filter(ft => !funcs.find(f => f.functionType === ft));
    uiFuncs.forEach(ft => funcs.push({ functionType: ft }));

    const containerInfo = tpl.containerInfo || { containerCode: 'C_1223', containerType: 'PALLET', size: { length: '1160', width: '1160', height: '1600', unit: 'mm' }, weight: { value: '500', unit: 'kg' } };
    const goodsInfoList = (tpl.goodsInfoList && tpl.goodsInfoList.length > 0) ? tpl.goodsInfoList : [{ ...DEFAULT_GOODS }];

    return {
        taskNo: genTaskNo(),
        taskSource: data.taskSource || 'WMS',
        taskType: data.taskType,
        bizType: data.bizType || '默认',
        bizPriority: data.bizPriority || 0,
        independentRun: data.independentRun || '',
        preStartTaskNo: data.preStartTaskNo || '',
        preEndTaskNo: data.preEndTaskNo || '',
        startNode: data.startNode,
        endNode: data.endNode,
        requiredFunctionList: funcs,
        containerList: [containerInfo],
        goodsInfoList: goodsInfoList,
        expectedStartTime: data.expectedStartTime || '',
        expectedFinishTime: data.expectedFinishTime || '',
        remark: data.remark || ''
    };
}

function buildCustomGroupPayload(data) {
    const tpl = data._customTpl;
    const taskNo = genTaskNo();

    function buildFuncListFromConfig(taskConfig, extraValues) {
        if (!taskConfig || !taskConfig.functions) return [];
        return taskConfig.functions.map(f => {
            const item = { functionType: f.functionType };
            if (f.extData && Object.keys(f.extData).length > 0) {
                const ext = { ...f.extData };
                if (taskConfig.extraFields) {
                    taskConfig.extraFields.forEach(ef => {
                        if (ef.targetFunction === f.functionType && extraValues[ef.key] !== undefined) {
                            ext[ef.key] = extraValues[ef.key];
                        }
                    });
                }
                item.extData = ext;
            }
            return item;
        });
    }

    const mainFuncList = buildFuncListFromConfig(tpl.mainTask, data._extraValues || {});
    const subFuncList = buildFuncListFromConfig(tpl.subTask, data._extraValues || {});

    const mainContainer = tpl.mainTask?.containerInfo || PALLET_MOTHER;
    const mainGoods = (tpl.mainTask?.goodsInfoList && tpl.mainTask.goodsInfoList.length > 0) ? tpl.mainTask.goodsInfoList : [{ ...DEFAULT_GOODS }];
    const subContainer = tpl.subTask?.containerInfo || PALLET_MOTHER;
    const subGoods = (tpl.subTask?.goodsInfoList && tpl.subTask.goodsInfoList.length > 0) ? tpl.subTask.goodsInfoList : [{ ...DEFAULT_GOODS }];

    // 容器字段名：containerList(数组) 或 containerInfo(单对象)
    const mainContainerFieldName = tpl.mainTask?.containerFieldName || 'containerList';
    const subContainerFieldName = tpl.subTask?.containerFieldName || 'containerList';

    // 构建主任务对象
    const mainTaskObj = {
        taskNo: taskNo + '_M',
        taskSource: tpl.mainTask?.taskSource || 'WMS',
        taskType: data.mainTaskType,
        bizType: tpl.mainTask?.bizType || '默认',
        bizPriority: tpl.mainTask?.bizPriority || 0,
        preStartTaskNo: tpl.mainTask?.preStartTaskNo || '',
        preEndTaskNo: tpl.mainTask?.preEndTaskNo || '',
        startNode: data.mainStart,
        endNode: data.mainEnd || '',
        independentRun: data.mainIndependent,
        requiredFunctionList: mainFuncList,
        goodsInfoList: mainGoods,
        expectedStartTime: tpl.mainTask?.expectedStartTime || '',
        expectedFinishTime: tpl.mainTask?.expectedFinishTime || '',
        remark: tpl.mainTask?.remark || '主任务'
    };
    if (mainContainerFieldName === 'containerInfo') {
        mainTaskObj.containerInfo = mainContainer;
    } else {
        mainTaskObj.containerList = [mainContainer];
    }

    // 构建子任务对象
    const subTaskObj = {
        taskNo: taskNo + '_S',
        taskSource: tpl.subTask?.taskSource || 'WMS',
        taskType: data.subTaskType,
        bizType: tpl.subTask?.bizType || '默认',
        bizPriority: tpl.subTask?.bizPriority || 0,
        preStartTaskNo: tpl.subTask?.preStartTaskNo || '',
        preEndTaskNo: tpl.subTask?.preEndTaskNo || '',
        startNode: data.subStart,
        endNode: data.subEnd || '',
        independentRun: data.subIndependent,
        requiredFunctionList: subFuncList,
        goodsInfoList: subGoods,
        expectedStartTime: tpl.subTask?.expectedStartTime || '',
        expectedFinishTime: tpl.subTask?.expectedFinishTime || '',
        remark: tpl.subTask?.remark || '子任务'
    };
    if (subContainerFieldName === 'containerInfo') {
        subTaskObj.containerInfo = subContainer;
    } else {
        subTaskObj.containerList = [subContainer];
    }

    return {
        groupCode: genGroupCode(),
        groupType: data.groupType,
        taskSource: tpl.taskSource || 'WMS',
        bizPriority: tpl.bizPriority || 0,
        mainTaskList: [mainTaskObj],
        subTaskList: [subTaskObj]
    };
}

// ============ 页面加载时初始化 ============
document.addEventListener('DOMContentLoaded', () => { ctmLoadAll(); });
