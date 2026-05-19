# loong-ai-diagnosis 可落地设计文档

> 版本：V1.0
> 日期：2026-05-08
> 目标：基于 `loong-platform` 的架构、接口与数据模型，对 `loong-ai-diagnosis` 做可实施重构，使其成为调度平台的智能诊断与决策辅助服务。

---

## 1. 结论先行

当前已有文档**方向是对的，但还不够落地**。  
要做到可实施，必须补齐以下四件事：

1. **明确边界**：`loong-platform` 负责执行，`loong-ai-diagnosis` 只负责分析与建议
2. **统一输入输出**：用标准诊断协议接平台任务、事件、日志、规则
3. **拆分实施阶段**：先做预检与诊断，再做知识库与自动推荐
4. **落到表、接口、流程、任务**：每个模块都要有具体数据结构和交付物

本文件给出一版能直接进入开发的设计。

---

## 2. 项目定位

### 2.1 `loong-platform`
定位为调度与执行平台，负责：

- 任务创建、修改、暂停、恢复、确认
- 任务组编排
- 引擎调度
- 节点/设备/能力管理
- WCS/WMS 对接
- 状态机流转
- 审计与幂等

### 2.2 `loong-ai-diagnosis`
定位为平台智能诊断服务，负责：

- 任务预检
- 任务异常诊断
- 改终点建议
- 业务确认建议
- 任务组合理性分析
- 能力匹配
- 知识检索与规则解释

### 2.3 不做的事
`loong-ai-diagnosis` **不直接做**：

- 创建/修改平台任务
- 改变平台任务状态
- 直接下发设备指令
- 直接接管调度闭环

最终动作必须由 `loong-platform` 执行。

---

## 3. 当前可落地的问题拆解

从你提供的接口文档看，平台任务场景已经非常明确，主要分为：

- 单任务：`/task/addTask`
- 任务组：`/task/addTaskGroup`
- 暂停：`/task/pauseTask`
- 改终点：`/task/changeTaskEndNode`
- 业务确认：`/task/bizConfirmTask`
- 任务查询：`/task/getDevicePlanTask`
- 引擎查询：`/engine/queryEngineContent`
- WCS 下发：`/adaptor/api/wcs/order`

这些接口说明平台已经有完整业务闭环，因此 AI 服务最合适的落点是：

> 在任务创建前、执行中、暂停后、确认时，提供标准化诊断建议。

---

## 4. 重构目标

### 4.1 第一阶段目标
在不影响 `loong-platform` 主链路的前提下，实现：

- 任务预检
- 任务异常诊断
- 改终点推荐
- 业务确认建议
- 能力匹配校验

### 4.2 第二阶段目标
在第一阶段基础上，增加：

- 任务组分析
- 相似案例检索
- 知识问答
- 风险预警
- 自动建议模板化

### 4.3 第三阶段目标
形成闭环能力：

- 诊断结果记录
- 建议采纳率统计
- 规则优化
- 知识库持续迭代

---

## 5. 落地架构

### 5.1 系统分层

#### A. 平台层 `loong-platform`
职责：
- 接入外部系统
- 生成任务/任务组
- 维护任务状态机
- 执行调度动作
- 存储业务主数据

#### B. 诊断层 `loong-ai-diagnosis`
职责：
- 接收平台上下文
- 做规则和能力分析
- 调用模型推理
- 输出结构化建议

#### C. 知识层
内容：
- 调度规则
- 历史任务案例
- 异常工单
- 接口说明
- 设备能力说明

---

## 6. 必须统一的领域对象

### 6.1 TaskSnapshot
平台任务快照，所有诊断都以它作为基础输入。

字段建议：
- `taskNo`
- `taskSource`
- `taskType`
- `bizType`
- `bizPriority`
- `groupCode`
- `groupType`
- `startNode`
- `endNode`
- `requiredFunctionList`
- `containerList`
- `goodsInfoList`
- `extData`
- `status`
- `remark`

### 6.2 TaskEvent
任务事件对象。

字段建议：
- `eventId`
- `taskNo`
- `eventType`
- `eventTime`
- `nodeCode`
- `deviceCode`
- `statusBefore`
- `statusAfter`
- `errorCode`
- `errorMessage`
- `payload`

### 6.3 DiagnosisRequest
统一诊断请求。

字段建议：
- `requestId`
- `scene`
- `tenantId`
- `bizType`
- `taskNo`
- `taskGroupCode`
- `currentStatus`
- `taskSnapshot`
- `taskEvent`
- `context`
- `options`

### 6.4 DiagnosisResult
统一诊断结果。

字段建议：
- `diagnosisId`
- `riskLevel`
- `summary`
- `rootCauses`
- `suggestions`
- `evidence`
- `needHumanConfirm`
- `confidence`

### 6.5 SuggestedAction
建议动作枚举：
- `KEEP`
- `PAUSE`
- `RESUME`
- `CHANGE_END_NODE`
- `REPLAN`
- `SPLIT_TASK`
- `MERGE_TASK`
- `MANUAL_CONFIRM`
- `CANCEL`

---

## 7. 平台接口与 AI 能力的映射

| 平台接口 | 平台动作 | AI 侧能力 |
|---|---|---|
| `/task/addTask` | 创建任务 | 任务预检、能力匹配、风险提示 |
| `/task/addTaskGroup` | 创建任务组 | 任务组分析、主子任务一致性检查 |
| `/task/pauseTask` | 暂停任务 | 暂停原因分析、恢复建议 |
| `/task/changeTaskEndNode` | 修改终点 | 改终点推荐、路径/能力匹配 |
| `/task/bizConfirmTask` | 业务确认 | 决策建议、风险说明 |
| `/task/getDevicePlanTask` | 查询设备任务 | 任务适配分析、节点建议 |
| `/engine/queryEngineContent` | 查询引擎内容 | 规则解释、相似案例召回 |
| `/adaptor/api/wcs/order` | WCS 下发 | 下发前校验、执行风险评估 |

---

## 8. 建议新增的 AI 接口

### 8.1 统一诊断接口
`POST /diagnosis/analyze`

用途：
- 异常诊断
- 根因分析
- 风险评估
- 推荐动作输出

### 8.2 任务预检接口
`POST /diagnosis/task/pre-check`

用途：
- 创建任务前校验
- 字段完整性检查
- 节点/能力匹配检查
- 容器/货物约束检查

### 8.3 改终点推荐接口
`POST /diagnosis/task/recommend-end-node`

用途：
- 基于任务与能力约束推荐终点
- 返回候选节点和排序理由

### 8.4 业务确认接口
`POST /diagnosis/task/biz-decision`

用途：
- 对 `WAIT_BIZ_DECISION` 场景给出建议
- 输出 `KEEP / CHANGE / REPLAN / CANCEL`

### 8.5 任务组分析接口
`POST /diagnosis/task-group/analyze`

用途：
- 分析主任务/子任务的依赖与顺序
- 判断独立运行是否冲突

### 8.6 能力匹配接口
`POST /diagnosis/capability/match`

用途：
- 校验 `requiredFunctionList`
- 校验节点、设备、任务类型匹配情况

### 8.7 问答接口
`POST /diagnosis/qa`

用途：
- 调度人员问答
- 规则解释
- 历史案例检索

---

## 9. 任务模型落地设计

### 9.1 必须统一的字段问题
当前接口样例中存在这些不一致：

- `containerInfo` 和 `containerList` 混用
- `requiredFunctionList` 中有空对象
- 同类任务字段可选性不统一
- `extData` 结构不固定
- 单位和数值类型混乱

### 9.2 统一模型拆分
建议统一成三层：

#### TaskBase
- `taskNo`
- `taskSource`
- `taskType`
- `bizType`
- `bizPriority`
- `startNode`
- `endNode`
- `remark`

#### TaskPayload
- `requiredFunctionList`
- `containerList`
- `goodsInfoList`
- `extData`

#### TaskRuntime
- `status`
- `currentNode`
- `currentDevice`
- `lastEvent`
- `errorCode`
- `errorMessage`
- `bizDecision`

---

## 10. 表结构设计

> 目标不是复制 `loong-platform` 全量表，而是在 AI 侧建立“最小必要诊断表”。

### 10.1 任务快照表 `ai_diagnosis_task_snapshot`
用途：保存任务快照。

字段建议：
- `id`
- `tenant_id`
- `task_no`
- `task_group_code`
- `task_type`
- `biz_type`
- `task_source`
- `start_node`
- `end_node`
- `required_functions_json`
- `container_json`
- `goods_json`
- `ext_data_json`
- `status`
- `version`
- `created_at`
- `updated_at`

### 10.2 任务事件表 `ai_diagnosis_task_event`
用途：保存任务执行事件。

字段建议：
- `id`
- `tenant_id`
- `task_no`
- `event_type`
- `node_code`
- `device_code`
- `status_before`
- `status_after`
- `error_code`
- `error_message`
- `payload_json`
- `event_time`
- `created_at`

### 10.3 诊断请求表 `ai_diagnosis_request`
用途：保存每次诊断输入。

字段建议：
- `id`
- `request_id`
- `tenant_id`
- `scene`
- `task_no`
- `task_group_code`
- `current_status`
- `input_json`
- `created_at`

### 10.4 诊断结果表 `ai_diagnosis_result`
用途：保存诊断输出。

字段建议：
- `id`
- `diagnosis_id`
- `request_id`
- `tenant_id`
- `risk_level`
- `summary`
- `root_causes_json`
- `suggestions_json`
- `evidence_json`
- `need_human_confirm`
- `confidence`
- `model_name`
- `prompt_version`
- `created_at`

### 10.5 能力字典表 `ai_capability_dictionary`
用途：维护能力点位字典。

字段建议：
- `id`
- `function_type`
- `function_name`
- `category`
- `description`
- `supported_task_types`
- `supported_node_types`
- `ext_schema_json`
- `is_critical`
- `status`
- `created_at`
- `updated_at`

### 10.6 规则配置表 `ai_diagnosis_rule`
用途：维护硬规则和业务规则。

字段建议：
- `id`
- `rule_code`
- `rule_name`
- `scene`
- `rule_type`
- `condition_json`
- `action_json`
- `priority`
- `status`
- `created_at`
- `updated_at`

---

## 11. 关键流程设计

### 11.1 任务创建前预检流程
1. `loong-platform` 组装任务草稿
2. 调用 `POST /diagnosis/task/pre-check`
3. `loong-ai-diagnosis` 执行：
   - 字段检查
   - 能力匹配
   - 节点检查
   - 容器/货物检查
4. 返回预检结果
5. 平台决定是否允许创建任务

### 11.2 任务异常诊断流程
1. 任务执行异常
2. 平台收集事件、日志、节点状态
3. 调用 `POST /diagnosis/analyze`
4. AI 输出根因、风险、建议动作
5. 平台根据建议选择：暂停/改终点/重规划/人工介入

### 11.3 业务确认流程
1. 任务进入 `WAIT_BIZ_DECISION`
2. 平台调用 `POST /diagnosis/task/biz-decision`
3. AI 给出建议动作和解释
4. 人工或平台策略决定最终动作

### 11.4 改终点流程
1. 平台收到改终点需求
2. 调用 `POST /diagnosis/task/recommend-end-node`
3. AI 返回候选终点和推荐理由
4. 平台执行改终点或人工确认

---

## 12. AI 推理策略

### 12.1 推理顺序
推荐严格按以下顺序：

1. **规则校验**：字段、状态、能力、节点约束
2. **知识检索**：历史案例、文档、工单
3. **模型推理**：总结根因、解释现象、推荐动作
4. **结构化输出**：统一 JSON 响应

### 12.2 为什么不能直接让 LLM 决策
因为调度系统要求：
- 可控
- 可审计
- 可回溯
- 可复现

所以 AI 必须是“辅助决策”，不能是“直接执行”。

---

## 13. 服务模块拆分

### 13.1 Controller 层
建议新增：
- `DiagnosisController`
- `TaskPreCheckController`
- `TaskGroupController`
- `CapabilityController`
- `QAController`
- `FeedbackController`

### 13.2 Service 层
建议新增：
- `DiagnosisService`
- `TaskPreCheckService`
- `TaskGroupAnalysisService`
- `CapabilityMatchService`
- `RecommendationService`

### 13.3 Domain 层
建议新增：
- `TaskSnapshot`
- `TaskEvent`
- `DiagnosisRequest`
- `DiagnosisResult`
- `DiagnosisPolicy`
- `RiskAssessmentPolicy`
- `CapabilityMatchPolicy`

### 13.4 Infrastructure 层
建议新增：
- `PlatformClient`
- `LLMClient`
- `VectorStoreClient`
- `DocumentParser`
- `KnowledgeRepository`
- `DiagnosisRepository`
- `RuleRepository`

---

## 14. 目录结构建议

```text
com.xxx.loong.ai.diagnosis
├── api
│   ├── DiagnosisController
│   ├── TaskPreCheckController
│   ├── TaskGroupController
│   ├── CapabilityController
│   ├── QAController
│   └── FeedbackController
├── application
│   ├── DiagnosisService
│   ├── TaskPreCheckService
│   ├── TaskGroupAnalysisService
│   ├── CapabilityMatchService
│   └── RecommendationService
├── domain
│   ├── model
│   ├── policy
│   ├── service
│   └── event
├── infrastructure
│   ├── llm
│   ├── vectorstore
│   ├── platformclient
│   ├── repository
│   ├── parser
│   └── mapper
└── interface
    ├── dto
    ├── vo
    └── assembler
```

---

## 15. 实施阶段与交付物

### 阶段 1：模型与接口统一
交付：
- 统一诊断请求/响应模型
- 统一任务快照模型
- 能力字典
- 诊断接口草案

### 阶段 2：平台适配与规则引擎
交付：
- 平台任务适配器
- 平台事件适配器
- 规则校验引擎
- 任务预检
- 改终点建议

### 阶段 3：知识库与案例库
交付：
- 文档知识库
- 历史案例库
- 相似任务召回
- 规则解释能力

### 阶段 4：联动闭环
交付：
- 诊断结果记录
- 建议采纳率统计
- 闭环反馈
- 规则持续优化

---

## 16. 最小可实施版本（MVP）

如果要尽快落地，建议只做这 5 个能力：

1. `POST /diagnosis/task/pre-check`
2. `POST /diagnosis/analyze`
3. `POST /diagnosis/task/recommend-end-node`
4. `POST /diagnosis/task/biz-decision`
5. `POST /diagnosis/capability/match`

### MVP 的输入
- 平台任务 JSON
- 事件 JSON
- 节点信息
- 能力字典

### MVP 的输出
- 风险等级
- 根因
- 建议动作
- 置信度
- 是否人工确认

---

## 17. 落地判断标准

一份设计文档是否可落地，标准不是“写得完整”，而是是否满足：

- 有明确边界
- 有统一数据模型
- 有可执行接口
- 有表结构
- 有流程
- 有阶段计划
- 有最小版本

本设计已经按这 7 项补齐，所以**可以作为实施文档的基础版本**。

---

## 18. 下一步建议

如果你要继续推进，我建议下一步直接补下面三类内容：

1. **接口详细定义**：每个接口的请求/响应 JSON、字段说明、校验规则
2. **建表 SQL**：把上面的表结构直接转成 MySQL DDL
3. **平台字段映射表**：把 `loong-platform` 现有任务字段映射到 AI 模型字段

---

## 19. 接口详细定义

本节给出 MVP 阶段建议实现的接口定义。接口设计遵循一个原则：**平台只传标准化输入，诊断服务只返回结构化结果**。

### 19.1 任务预检接口

#### `POST /diagnosis/task/pre-check`

**用途**
- 在任务创建前做预检
- 校验字段、能力、节点、容器、货物等是否满足任务创建条件

**请求体建议**

```json
{
  "requestId": "REQ-202605090001",
  "tenantId": "TENANT_A",
  "taskNo": "TASK_001",
  "taskGroupCode": "GROUP_001",
  "bizType": "INBOUND",
  "taskType": "N2N",
  "currentStatus": "DRAFT",
  "taskSnapshot": {
    "taskNo": "TASK_001",
    "taskSource": "WMS",
    "bizType": "INBOUND",
    "taskType": "N2N",
    "startNode": "ND_A",
    "endNode": "ND_B",
    "requiredFunctionList": ["SCANNER_NODE", "WEIGHING_NODE"],
    "containerList": [],
    "goodsInfoList": [],
    "extData": {}
  }
}
```

**响应体建议**

```json
{
  "requestId": "REQ-202605090001",
  "taskNo": "TASK_001",
  "pass": true,
  "riskLevel": "LOW",
  "summary": "任务预检通过",
  "rootCauses": [],
  "suggestions": ["可创建任务"],
  "confidence": 0.98
}
```

**校验重点**
- `taskNo` 是否为空
- `startNode` / `endNode` 是否存在
- `requiredFunctionList` 是否与节点能力冲突
- 货物、容器、数量是否完整
- 是否存在明显重复或越界数据

---

### 19.2 任务异常诊断接口

#### `POST /diagnosis/analyze`

**用途**
- 对任务执行异常、卡住、状态异常进行诊断
- 输出根因、风险、建议动作、证据链

**请求体建议**

```json
{
  "requestId": "REQ-202605090002",
  "tenantId": "TENANT_A",
  "taskNo": "TASK_001",
  "scene": "TASK_STUCK",
  "currentStatus": "WAIT_PLAN",
  "taskSnapshot": {
    "taskNo": "TASK_001",
    "status": "WAIT_PLAN",
    "startNode": "ND_A",
    "endNode": "ND_B",
    "requiredFunctionList": ["SCANNER_NODE"]
  },
  "taskEvent": {
    "eventType": "TASK_WAIT_PLAN_TIMEOUT",
    "statusBefore": "WAIT_SPLIT",
    "statusAfter": "WAIT_PLAN",
    "eventTime": "2026-05-09T10:00:00"
  },
  "options": {
    "needRootCause": true,
    "needRecommendAction": true,
    "needBizExplain": true
  }
}
```

**响应体建议**

```json
{
  "requestId": "REQ-202605090002",
  "taskNo": "TASK_001",
  "sceneCode": "WAIT_PLAN_TIMEOUT",
  "sceneName": "任务停留在WAIT_PLAN",
  "riskLevel": "HIGH",
  "confidence": 0.92,
  "summary": "任务已拆分但子任务未完成生成或未推进",
  "rootCauses": [
    {
      "causeCode": "TASK_ITEM_GEN_FAIL",
      "causeName": "子任务生成失败",
      "probability": 0.78,
      "evidence": ["planSegmentList为空", "taskItemList为空"]
    }
  ],
  "suggestions": ["检查拆分链路", "检查任务组补加逻辑", "检查状态回写"],
  "needHumanConfirm": false
}
```

**校验重点**
- 状态是否与场景匹配
- 事件是否完整
- 是否存在足够证据支持根因推断
- 是否需要人工确认

---

### 19.3 改终点推荐接口

#### `POST /diagnosis/task/recommend-end-node`

**用途**
- 当任务卡住、路径不通或业务需要改终点时，推荐候选终点

**请求体建议**

```json
{
  "requestId": "REQ-202605090003",
  "taskNo": "TASK_001",
  "currentNode": "ND_A",
  "currentStatus": "WAIT_BIZ_DECISION",
  "taskSnapshot": {
    "startNode": "ND_A",
    "endNode": "ND_B",
    "taskType": "N2N"
  }
}
```

**响应体建议**

```json
{
  "requestId": "REQ-202605090003",
  "taskNo": "TASK_001",
  "recommendations": [
    {
      "nodeCode": "ND_C",
      "reason": "能力匹配更优，且路径可达",
      "score": 0.96
    }
  ]
}
```

---

### 19.4 业务确认建议接口

#### `POST /diagnosis/task/biz-decision`

**用途**
- 对 `WAIT_BIZ_DECISION` 场景给出建议动作
- 输出 `KEEP / CHANGE / REPLAN / CANCEL`

**请求体建议**

```json
{
  "requestId": "REQ-202605090004",
  "taskNo": "TASK_001",
  "currentStatus": "WAIT_BIZ_DECISION",
  "taskSnapshot": {
    "taskNo": "TASK_001",
    "status": "WAIT_BIZ_DECISION",
    "startNode": "ND_A",
    "endNode": "ND_B"
  },
  "taskEvent": {
    "eventType": "BIZ_DECISION_REQUIRED",
    "statusBefore": "RUNNING",
    "statusAfter": "WAIT_BIZ_DECISION"
  }
}
```

**响应体建议**

```json
{
  "requestId": "REQ-202605090004",
  "taskNo": "TASK_001",
  "suggestedAction": "REPLAN",
  "summary": "当前终点能力不足，建议重规划路径",
  "confidence": 0.89,
  "evidence": ["requiredFunctionList与节点能力不匹配"],
  "humanExplain": "该任务当前停在人工确认点，继续执行可能导致后续设备无法接单"
}
```

---

### 19.5 能力匹配接口

#### `POST /diagnosis/capability/match`

**用途**
- 校验 `requiredFunctionList`
- 校验节点、设备、任务类型匹配情况

**请求体建议**

```json
{
  "requestId": "REQ-202605090005",
  "taskNo": "TASK_001",
  "taskType": "N2N",
  "requiredFunctionList": ["SCANNER_NODE", "WEIGHING_NODE"],
  "nodeCodes": ["ND_A", "ND_B"],
  "deviceCodes": ["DV_001", "DV_002"]
}
```

**响应体建议**

```json
{
  "requestId": "REQ-202605090005",
  "taskNo": "TASK_001",
  "matched": true,
  "unmatchedFunctions": [],
  "capabilityScore": 0.97,
  "summary": "能力匹配通过"
}
```

---

### 19.6 任务组分析接口

#### `POST /diagnosis/task-group/analyze`

**用途**
- 分析主任务/子任务的依赖与顺序
- 判断独立运行是否冲突

**请求体建议**

```json
{
  "requestId": "REQ-202605090006",
  "taskGroupCode": "GROUP_001",
  "mainTasks": [
    { "taskNo": "TASK_MAIN_001", "status": "RUNNING" }
  ],
  "subTasks": [
    { "taskNo": "TASK_SUB_001", "status": "WAIT" }
  ],
  "independentRun": false
}
```

**响应体建议**

```json
{
  "requestId": "REQ-202605090006",
  "taskGroupCode": "GROUP_001",
  "conflict": true,
  "summary": "主任务与子任务存在顺序依赖冲突",
  "suggestions": ["检查 groupRole", "检查 preTaskItemNo", "检查系统补加任务" ]
}
```

---

### 19.7 问答接口

#### `POST /diagnosis/qa`

**用途**
- 调度人员问答
- 规则解释
- 历史案例检索

**请求体建议**

```json
{
  "requestId": "REQ-202605090007",
  "question": "为什么任务会卡在WAIT_PLAN？",
  "taskNo": "TASK_001",
  "context": {
    "taskState": "WAIT_PLAN"
  }
}
```

**响应体建议**

```json
{
  "requestId": "REQ-202605090007",
  "answer": "任务停在WAIT_PLAN通常表示路径已规划但子任务未生成或未推进。常见原因包括拆分链路异常、系统补加任务失败、状态回写失败。",
  "references": ["WAIT_PLAN_TIMEOUT", "TASK_ITEM_GEN_FAIL"]
}
```

---

## 20. 诊断侧表结构设计

> 目标不是复制 `loong-platform` 全量表，而是在 AI 侧建立“最小必要诊断表”。

### 20.1 任务快照表 `ai_diagnosis_task_snapshot`

用途：保存任务快照。

**字段建议**
- `id`
- `tenant_id`
- `task_no`
- `task_group_code`
- `task_type`
- `biz_type`
- `task_source`
- `start_node`
- `end_node`
- `required_functions_json`
- `container_json`
- `goods_json`
- `ext_data_json`
- `status`
- `version`
- `created_at`
- `updated_at`

**设计说明**
- 用于存储任务的标准化静态快照
- 便于预检、诊断和历史回放

### 20.2 任务事件表 `ai_diagnosis_task_event`

用途：保存任务执行事件。

**字段建议**
- `id`
- `tenant_id`
- `task_no`
- `event_type`
- `node_code`
- `device_code`
- `status_before`
- `status_after`
- `error_code`
- `error_message`
- `payload_json`
- `event_time`
- `created_at`

**设计说明**
- 记录任务状态变化和设备事件
- 是诊断证据链的核心来源

### 20.3 诊断请求表 `ai_diagnosis_request`

用途：保存每次诊断输入。

**字段建议**
- `id`
- `request_id`
- `tenant_id`
- `scene`
- `task_no`
- `task_group_code`
- `current_status`
- `input_json`
- `created_at`

**设计说明**
- 便于审计、复现和排查

### 20.4 诊断结果表 `ai_diagnosis_result`

用途：保存诊断输出。

**字段建议**
- `id`
- `diagnosis_id`
- `request_id`
- `tenant_id`
- `risk_level`
- `summary`
- `root_causes_json`
- `suggestions_json`
- `evidence_json`
- `need_human_confirm`
- `confidence`
- `model_name`
- `prompt_version`
- `created_at`

**设计说明**
- 用于记录每次诊断结论和模型版本
- 支持效果评估和回放

### 20.5 能力字典表 `ai_capability_dictionary`

用途：维护能力点位字典。

**字段建议**
- `id`
- `function_type`
- `function_name`
- `category`
- `description`
- `supported_task_types`
- `supported_node_types`
- `ext_schema_json`
- `is_critical`
- `status`
- `created_at`
- `updated_at`

**设计说明**
- 统一能力定义，供预检和匹配使用

### 20.6 规则配置表 `ai_diagnosis_rule`

用途：维护硬规则和业务规则。

**字段建议**
- `id`
- `rule_code`
- `rule_name`
- `scene`
- `rule_type`
- `condition_json`
- `action_json`
- `priority`
- `status`
- `created_at`
- `updated_at`

**设计说明**
- 用于配置规则引擎
- 支持硬规则、经验规则、表达式规则

---

## 21. 建表 SQL 草案

下面给出核心表的 MySQL DDL 草案，可作为后续初始化脚本的基础。

### 21.1 `ai_diagnosis_task_snapshot`

```sql
CREATE TABLE ai_diagnosis_task_snapshot (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id VARCHAR(64) NOT NULL,
  task_no VARCHAR(64) NOT NULL,
  task_group_code VARCHAR(64) NULL,
  task_type VARCHAR(32) NULL,
  biz_type VARCHAR(32) NULL,
  task_source VARCHAR(32) NULL,
  start_node VARCHAR(64) NULL,
  end_node VARCHAR(64) NULL,
  required_functions_json JSON NULL,
  container_json JSON NULL,
  goods_json JSON NULL,
  ext_data_json JSON NULL,
  status VARCHAR(32) NOT NULL,
  version BIGINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_tenant_task_no (tenant_id, task_no),
  KEY idx_task_group_code (task_group_code),
  KEY idx_status (status),
  KEY idx_created_at (created_at)
);
```

### 21.2 `ai_diagnosis_task_event`

```sql
CREATE TABLE ai_diagnosis_task_event (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id VARCHAR(64) NOT NULL,
  task_no VARCHAR(64) NOT NULL,
  event_type VARCHAR(64) NOT NULL,
  node_code VARCHAR(64) NULL,
  device_code VARCHAR(64) NULL,
  status_before VARCHAR(32) NULL,
  status_after VARCHAR(32) NULL,
  error_code VARCHAR(64) NULL,
  error_message VARCHAR(512) NULL,
  payload_json JSON NULL,
  event_time DATETIME NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_tenant_task_no (tenant_id, task_no),
  KEY idx_event_type (event_type),
  KEY idx_event_time (event_time)
);
```

### 21.3 `ai_diagnosis_request`

```sql
CREATE TABLE ai_diagnosis_request (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  request_id VARCHAR(64) NOT NULL,
  tenant_id VARCHAR(64) NOT NULL,
  scene VARCHAR(64) NULL,
  task_no VARCHAR(64) NULL,
  task_group_code VARCHAR(64) NULL,
  current_status VARCHAR(32) NULL,
  input_json JSON NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_request_id (request_id),
  KEY idx_task_no (task_no),
  KEY idx_scene (scene),
  KEY idx_created_at (created_at)
);
```

### 21.4 `ai_diagnosis_result`

```sql
CREATE TABLE ai_diagnosis_result (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  diagnosis_id VARCHAR(64) NOT NULL,
  request_id VARCHAR(64) NOT NULL,
  tenant_id VARCHAR(64) NOT NULL,
  risk_level VARCHAR(16) NOT NULL,
  summary VARCHAR(512) NULL,
  root_causes_json JSON NULL,
  suggestions_json JSON NULL,
  evidence_json JSON NULL,
  need_human_confirm TINYINT(1) NOT NULL DEFAULT 0,
  confidence DECIMAL(5,4) NOT NULL DEFAULT 0.0000,
  model_name VARCHAR(64) NULL,
  prompt_version VARCHAR(64) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_diagnosis_id (diagnosis_id),
  KEY idx_request_id (request_id),
  KEY idx_risk_level (risk_level),
  KEY idx_created_at (created_at)
);
```

### 21.5 `ai_capability_dictionary`

```sql
CREATE TABLE ai_capability_dictionary (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  function_type VARCHAR(64) NOT NULL,
  function_name VARCHAR(128) NOT NULL,
  category VARCHAR(64) NULL,
  description VARCHAR(512) NULL,
  supported_task_types JSON NULL,
  supported_node_types JSON NULL,
  ext_schema_json JSON NULL,
  is_critical TINYINT(1) NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_function_type (function_type),
  KEY idx_category (category),
  KEY idx_status (status)
);
```

### 21.6 `ai_diagnosis_rule`

```sql
CREATE TABLE ai_diagnosis_rule (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  rule_code VARCHAR(64) NOT NULL,
  rule_name VARCHAR(128) NOT NULL,
  scene VARCHAR(64) NOT NULL,
  rule_type VARCHAR(32) NOT NULL,
  condition_json JSON NOT NULL,
  action_json JSON NOT NULL,
  priority INT NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_rule_code (rule_code),
  KEY idx_scene (scene),
  KEY idx_priority (priority),
  KEY idx_status (status)
);
```

---

## 22. `loong-platform` → `loong-ai-diagnosis` 字段映射表

为了让诊断服务与平台数据对齐，建议统一映射如下。

### 22.1 任务字段映射

| `loong-platform` 字段 | `loong-ai-diagnosis` 字段 | 说明 |
|---|---|---|
| `taskNo` | `taskNo` | 任务唯一标识 |
| `taskState` | `status` / `currentStatus` | 任务当前状态 |
| `taskType` | `taskType` | 任务类型 |
| `bizType` | `bizType` | 业务类型 |
| `taskSource` | `taskSource` | 任务来源 |
| `groupCode` | `taskGroupCode` | 任务组编码 |
| `startNode` | `startNode` | 起点 |
| `endNode` | `endNode` | 终点 |
| `requiredFunctionList` | `requiredFunctionList` | 能力要求 |
| `containerList` | `containerList` | 容器信息 |
| `goodsInfoList` | `goodsInfoList` | 货物信息 |
| `reportData` | `extData` / `payload` | 扩展信息 |

### 22.2 子任务字段映射

| `loong-platform` 字段 | `loong-ai-diagnosis` 字段 | 说明 |
|---|---|---|
| `taskItemNo` | `taskItemNo` | 子任务编号 |
| `taskItemState` | `taskItemStatus` | 子任务状态 |
| `deviceCode` | `deviceCode` | 执行设备 |
| `functionType` | `functionType` | 功能类型 |
| `preTaskItemNo` | `preTaskItemNo` | 前置子任务 |
| `planIndex` | `planIndex` | 规划索引 |
| `issuedIndex` | `issuedIndex` | 下发索引 |
| `executedIndex` | `executedIndex` | 执行索引 |

### 22.3 指令字段映射

| `loong-platform` 字段 | `loong-ai-diagnosis` 字段 | 说明 |
|---|---|---|
| `commandNo` | `commandNo` | 指令编号 |
| `commandState` | `commandStatus` | 指令状态 |
| `ackState` | `ackStatus` | ACK 状态 |
| `retryCount` | `retryCount` | 重试次数 |
| `execResult` | `execResult` | 执行结果 |

### 22.4 设备字段映射

| `loong-platform` 字段 | `loong-ai-diagnosis` 字段 | 说明 |
|---|---|---|
| `deviceCode` | `deviceCode` | 设备编码 |
| `deviceType` | `deviceType` | 设备类型 |
| `lockState` | `lockState` | 锁状态 |
| `runningTask` | `runningTaskNo` | 当前运行任务 |
| `queue` | `queueSnapshot` | 队列快照 |
| `runtimeDeviceState` | `runtimeState` | 设备实时状态 |

---

## 23. 结语

`loong-ai-diagnosis` 要真正落地，关键不是再加一个 AI 能力接口，而是把它改造成 **“调度平台可依赖的诊断中台”**。  
只有做到“平台执行、AI 建议、规则兜底、结果结构化”，这套设计才可以进入开发和联调。
