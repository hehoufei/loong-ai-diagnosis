# loong-ai-diagnosis 基于 loong-platform 的重构设计文档

> 版本：V1.0  
> 日期：2026-05-08  
> 目标：依据 `loong-platform` 的架构、接口与表结构设计，重构 `loong-ai-diagnosis` 的功能定位、领域模型、接口协议、数据结构与实施路径，使其成为 `loong-platform` 的智能诊断与决策辅助能力服务。

---

## 1. 背景与目标

### 1.1 背景
当前 `loong-ai-diagnosis` 已具备基础 AI 能力，包括：

- 大模型调用（ZhipuAI / OpenAI 兼容协议）
- 文档解析（Tika）
- 向量检索（VectorStore）
- 数据持久化（JDBC + MySQL）
- OpenAPI 文档能力

但从系统定位上看，它更像一个“通用 AI 能力服务”，而不是与 `loong-platform` 完整对齐的“平台智能能力层”。

而 `loong-platform` 的调度接口样例已经明确体现出以下核心特征：

- 任务编排与流转
- 任务组与主子任务关系
- 设备/节点能力匹配
- 业务确认与终点修改
- 暂停、重规划、下发与执行闭环
- 多种物流/机器人/输送/拆合托/检测场景

因此，`loong-ai-diagnosis` 的最佳定位应升级为：

> **loong-platform 的智能诊断与决策辅助子系统**

### 1.2 重构目标
本次重构目标是让 `loong-ai-diagnosis` 支持以下能力：

1. 对平台任务进行事前校验与可行性分析
2. 对任务执行异常进行诊断、归因与建议输出
3. 对暂停、改终点、业务确认、重规划等操作提供智能辅助
4. 对任务组主子任务结构进行合理性分析
5. 对能力点位、节点、设备进行匹配判定
6. 对历史事件、日志、工单、知识库进行检索推理
7. 统一输出结构化建议，供 `loong-platform` 决策执行

---

## 2. 现状分析

### 2.1 `loong-ai-diagnosis` 当前技术基础
从 `pom.xml` 可见，当前服务具备以下基础：

- Spring Boot 3.4.4
- Java 17
- Spring AI 1.0.0
- `spring-ai-starter-model-zhipuai`
- `spring-ai-starter-model-openai`
- `spring-ai-vector-store`
- `spring-ai-tika-document-reader`
- JDBC/MySQL
- springdoc-openapi
- jqwik 测试能力

这意味着它已经有成为 AI 中台的基础，但缺少与平台业务模型对齐的领域层、适配层和统一协议层。

### 2.2 `loong-platform` 接口样例分析
从已提供的接口文档可以提炼出平台核心业务接口：

- `/task/getDevicePlanTask`
- `/engine/queryEngineContent`
- `/task/pauseTask`
- `/task/changeTaskEndNode`
- `/task/bizConfirmTask`
- `/adaptor/api/wcs/order`
- `/task/addTask`
- `/task/addTaskGroup`

这些接口覆盖了：

- 任务创建
- 任务组创建
- 任务暂停
- 终点修改
- 业务确认
- WCS 下发适配
- 引擎内容查询
- 设备计划任务获取

### 2.3 当前数据结构特征
平台任务样例中反复出现的关键字段包括：

- `taskNo`
- `taskSource`
- `taskType`
- `bizType`
- `bizPriority`
- `startNode`
- `endNode`
- `requiredFunctionList`
- `containerList` / `containerInfo`
- `goodsInfoList`
- `expectedStartTime`
- `expectedFinishTime`
- `remark`
- `groupCode`
- `groupType`
- `mainTaskList`
- `subTaskList`
- `extData`

其中最关键的是：

- `requiredFunctionList`：能力需求表达
- `mainTaskList` / `subTaskList`：任务组编排
- `extData`：特定场景参数扩展

---

## 3. 重构原则

### 3.1 职责边界原则
`loong-platform` 负责“执行与控制”，`loong-ai-diagnosis` 负责“分析与建议”。

#### 平台职责
- 创建/修改/暂停/恢复/下发任务
- 控制任务状态机
- 管理节点、设备、能力点位
- 任务组编排与执行
- 审计、幂等、权限

#### AI 诊断职责
- 任务可行性分析
- 风险评估
- 根因分析
- 改终点/重规划建议
- 业务确认建议
- 能力匹配判断
- 知识检索与解释

### 3.2 结构化优先原则
AI 服务输出必须尽量结构化，不能只返回自然语言。

要求输出包含：
- 风险等级
- 根因列表
- 建议动作
- 置信度
- 证据片段
- 是否需要人工确认

### 3.3 规则优先、AI 兜底原则
对于调度业务，不建议完全依赖大模型。

推荐链路：
1. 硬规则校验
2. 业务规则判定
3. 知识库召回
4. LLM 解释与补全
5. 结构化结果输出

### 3.4 平台中心化原则
所有业务动作最终由 `loong-platform` 决策执行，`loong-ai-diagnosis` 不直接修改平台状态。

---

## 4. 重构后的系统定位

### 4.1 新定位
`loong-ai-diagnosis` 定位为：

> **调度平台智能诊断服务（Scheduling Intelligent Diagnosis Service）**

### 4.2 服务能力边界
它应提供以下四类能力：

1. **事前校验**：任务创建前的规则/能力/参数检查
2. **事中诊断**：任务暂停、异常、失败、阻塞后的分析
3. **事后归因**：对执行结果、失败原因、人工决策进行复盘
4. **知识辅助**：自然语言问答、规则解释、相似案例检索

---

## 5. 领域模型设计

### 5.1 核心领域对象

#### 5.1.1 TaskSnapshot
用于表示平台任务的标准化快照。

包含：
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
- `expectedStartTime`
- `expectedFinishTime`
- `remark`

#### 5.1.2 TaskEvent
表示任务执行过程中的事件。

包含：
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

#### 5.1.3 DiagnosisRequest
AI 服务统一入参。

包含：
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

#### 5.1.4 DiagnosisResult
AI 服务统一出参。

包含：
- `diagnosisId`
- `riskLevel`
- `summary`
- `rootCauses`
- `suggestions`
- `evidence`
- `needHumanConfirm`
- `confidence`

#### 5.1.5 DiagnosisSuggestion
建议动作模型。

建议动作类型包括：
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

## 6. 与 loong-platform 的接口对齐设计

### 6.1 平台现有接口与 AI 诊断映射

| loong-platform 接口 | 业务含义 | AI 侧建议能力 |
|---|---|---|
| `/task/addTask` | 创建任务 | 任务预检、能力匹配、风险提示 |
| `/task/addTaskGroup` | 创建任务组 | 任务组结构分析、主子任务合理性检查 |
| `/task/pauseTask` | 暂停任务 | 暂停原因分析、恢复建议 |
| `/task/changeTaskEndNode` | 修改终点 | 改终点推荐、路径/能力匹配分析 |
| `/task/bizConfirmTask` | 业务确认 | 决策建议、风险说明 |
| `/task/getDevicePlanTask` | 获取设备任务 | 设备任务适配分析、节点建议 |
| `/engine/queryEngineContent` | 查询引擎内容 | 知识检索、规则解释、相似案例召回 |
| `/adaptor/api/wcs/order` | 下发 WCS 订单 | 下发前检查、执行能力预测 |

### 6.2 AI 服务建议新增接口

#### 6.2.1 统一诊断接口
`POST /diagnosis/analyze`

用于：
- 异常分析
- 风险识别
- 任务上下文诊断

#### 6.2.2 任务预检接口
`POST /diagnosis/task/pre-check`

用于：
- 创建任务前检查字段完整性
- 校验任务类型与节点能力匹配
- 校验容器与货物约束

#### 6.2.3 改终点建议接口
`POST /diagnosis/task/recommend-end-node`

用于：
- 针对修改终点场景推荐候选节点
- 说明风险与影响范围

#### 6.2.4 业务确认建议接口
`POST /diagnosis/task/biz-decision`

用于：
- 提供 KEEP/CHANGE/CANCEL/REPLAN 等建议
- 适用于 WAIT_BIZ_DECISION 场景

#### 6.2.5 任务组分析接口
`POST /diagnosis/task-group/analyze`

用于：
- 主任务/子任务结构判断
- 独立运行与依赖关系分析
- 任务编排冲突检测

#### 6.2.6 能力匹配接口
`POST /diagnosis/capability/match`

用于：
- 匹配 `requiredFunctionList`
- 校验节点/设备是否具备执行能力

#### 6.2.7 问答接口
`POST /diagnosis/qa`

用于：
- 运维/调度人员自然语言问答
- 规则解释
- 历史案例检索

---

## 7. 任务模型重构建议

### 7.1 当前问题
当前任务样例存在以下不统一问题：

- `containerInfo` 与 `containerList` 并存
- `requiredFunctionList` 中存在空对象
- 不同任务类型字段可选性差异大
- `extData` 的结构不统一
- 容器、货物、节点、功能点之间边界略混乱

### 7.2 建议统一模型
建议统一为以下三层：

#### 7.2.1 TaskBase
基础任务信息：
- `taskNo`
- `taskSource`
- `taskType`
- `bizType`
- `bizPriority`
- `startNode`
- `endNode`
- `remark`

#### 7.2.2 TaskPayload
任务执行负载：
- `requiredFunctionList`
- `containerList`
- `goodsInfoList`
- `extData`

#### 7.2.3 TaskRuntime
运行态信息：
- `status`
- `currentNode`
- `currentDevice`
- `lastEvent`
- `errorCode`
- `errorMessage`
- `bizDecision`

### 7.3 requiredFunctionList 统一字典化
建议将以下能力定义为统一字典：

- `SCANNER_NODE`
- `WEIGHING_NODE`
- `SHAPE_DETECTION`
- `WAIT_BIZ_DECISION`
- `WHOLE_DISASSEMBLE_PALLET_NODE`
- `WHOLE_PLACE_NODE`
- `DISASSEMBLE_BY_PIECE_NODE`
- `ASSEMBLE_BY_PIECE_NODE`
- `SCANNER_RFID_NODE`
- `FILM_WRAPPING_BAGGING_NODE`
- `DISASSEMBLE_SUB_PALLET_NODE`
- `REMOVE_ADD_GOODS`

每个能力项建议包含：
- `functionType`
- `functionName`
- `category`
- `description`
- `supportedTaskTypes`
- `supportedNodeTypes`
- `extDataSchema`
- `isCritical`

---

## 8. 数据表结构设计建议

> 以下表结构建议用于 `loong-ai-diagnosis` 对齐 `loong-platform` 的标准业务模型。若平台已有对应表，可通过适配层映射，不必完全复制。

### 8.1 任务快照表 `ai_diagnosis_task_snapshot`
用于保存诊断时的任务快照。

#### 建议字段
- `id` bigint PK
- `tenant_id` varchar(64)
- `task_no` varchar(64)
- `task_group_code` varchar(64)
- `task_type` varchar(32)
- `biz_type` varchar(64)
- `task_source` varchar(32)
- `start_node` varchar(128)
- `end_node` varchar(128)
- `required_functions_json` json
- `container_json` json
- `goods_json` json
- `ext_data_json` json
- `status` varchar(32)
- `version` int
- `created_at` datetime
- `updated_at` datetime

### 8.2 任务事件表 `ai_diagnosis_task_event`
用于存储任务执行事件。

#### 建议字段
- `id` bigint PK
- `tenant_id` varchar(64)
- `task_no` varchar(64)
- `event_type` varchar(64)
- `node_code` varchar(128)
- `device_code` varchar(128)
- `status_before` varchar(32)
- `status_after` varchar(32)
- `error_code` varchar(64)
- `error_message` varchar(512)
- `payload_json` json
- `event_time` datetime
- `created_at` datetime

### 8.3 诊断请求表 `ai_diagnosis_request`
用于记录每次诊断调用。

#### 建议字段
- `id` bigint PK
- `request_id` varchar(64)
- `tenant_id` varchar(64)
- `scene` varchar(64)
- `task_no` varchar(64)
- `task_group_code` varchar(64)
- `current_status` varchar(32)
- `input_json` json
- `created_at` datetime

### 8.4 诊断结果表 `ai_diagnosis_result`
用于存储 AI 输出结果。

#### 建议字段
- `id` bigint PK
- `diagnosis_id` varchar(64)
- `request_id` varchar(64)
- `tenant_id` varchar(64)
- `risk_level` varchar(16)
- `summary` varchar(1024)
- `root_causes_json` json
- `suggestions_json` json
- `evidence_json` json
- `need_human_confirm` tinyint
- `confidence` decimal(5,2)
- `model_name` varchar(64)
- `prompt_version` varchar(32)
- `created_at` datetime

### 8.5 知识文档表 `ai_knowledge_document`
用于存储平台手册、接口说明、规则说明、案例说明等文档元数据。

#### 建议字段
- `id` bigint PK
- `tenant_id` varchar(64)
- `doc_type` varchar(32)
- `title` varchar(255)
- `source_url` varchar(512)
- `file_path` varchar(512)
- `content_hash` varchar(128)
- `status` varchar(32)
- `created_at` datetime
- `updated_at` datetime

### 8.6 向量切片表 `ai_knowledge_chunk`
用于知识库向量检索。

#### 建议字段
- `id` bigint PK
- `document_id` bigint
- `chunk_index` int
- `chunk_text` text
- `embedding_id` varchar(128)
- `metadata_json` json
- `created_at` datetime

### 8.7 能力字典表 `ai_capability_dictionary`
用于维护 `requiredFunctionList` 对应的能力词典。

#### 建议字段
- `id` bigint PK
- `function_type` varchar(64)
- `function_name` varchar(128)
- `category` varchar(64)
- `description` varchar(512)
- `supported_task_types` varchar(256)
- `supported_node_types` varchar(256)
- `ext_schema_json` json
- `is_critical` tinyint
- `status` varchar(32)
- `created_at` datetime
- `updated_at` datetime

### 8.8 规则配置表 `ai_diagnosis_rule`
用于存储硬规则与业务规则。

#### 建议字段
- `id` bigint PK
- `rule_code` varchar(64)
- `rule_name` varchar(128)
- `scene` varchar(64)
- `rule_type` varchar(32)
- `condition_json` json
- `action_json` json
- `priority` int
- `status` varchar(32)
- `created_at` datetime
- `updated_at` datetime

---

## 9. 接口协议设计

### 9.1 统一诊断请求示例

```json
{
  "requestId": "REQ_20260508_0001",
  "scene": "TASK_EXCEPTION",
  "tenantId": "default",
  "bizType": "WMS",
  "taskNo": "ZDYWXJCTASK_1774850731892",
  "taskGroupCode": "GROUP_20260508_001",
  "currentStatus": "WAIT_BIZ_DECISION",
  "taskSnapshot": {
    "taskType": "N2S",
    "startNode": "ND_19000",
    "endNode": "SL_-WH_HSMD-SA_LK-AL_01-L-02-01-0005-01",
    "requiredFunctionList": [
      { "functionType": "SCANNER_NODE" },
      { "functionType": "WEIGHING_NODE" }
    ],
    "containerList": [],
    "goodsInfoList": [],
    "extData": {}
  },
  "taskEvent": {
    "eventType": "TASK_PAUSED",
    "eventTime": "2026-05-08T10:00:00Z",
    "nodeCode": "ND_19000",
    "deviceCode": "DEV_001",
    "errorCode": "NODE_UNREACHABLE",
    "errorMessage": "终点不可达"
  },
  "context": {
    "logs": [],
    "history": [],
    "platformHint": {}
  },
  "options": {
    "needRootCause": true,
    "needRecommendAction": true,
    "needBizExplain": true
  }
}
```

### 9.2 统一诊断结果示例

```json
{
  "requestId": "REQ_20260508_0001",
  "diagnosisId": "DIA_20260508_0001",
  "scene": "TASK_EXCEPTION",
  "riskLevel": "HIGH",
  "summary": "当前任务终点与能力要求不匹配，建议修改终点或补齐能力点。",
  "rootCauses": [
    {
      "code": "NODE_CAPABILITY_MISMATCH",
      "description": "终点节点缺少 WEIGHING_NODE 能力",
      "confidence": 0.93
    }
  ],
  "suggestions": [
    {
      "actionType": "CHANGE_END_NODE",
      "actionDesc": "建议修改任务终点",
      "payload": {
        "endNode": "ND_21017"
      }
    }
  ],
  "evidence": [
    {
      "type": "TASK_SNAPSHOT",
      "content": "requiredFunctionList 包含 SCANNER_NODE 和 WEIGHING_NODE"
    }
  ],
  "needHumanConfirm": true,
  "confidence": 0.93
}
```

---

## 10. 场景化功能设计

### 10.1 任务创建前预检
#### 输入
- 任务草稿
- 节点信息
- 容器/货物信息
- requiredFunctionList

#### 输出
- 字段缺失
- 任务类型是否合理
- 节点/设备能力是否匹配
- 是否存在冲突
- 是否建议改任务类型或改节点

### 10.2 任务暂停诊断
#### 输入
- 任务状态
- 暂停事件
- 最近日志
- 当前节点状态

#### 输出
- 暂停原因
- 是否可恢复
- 推荐恢复策略
- 是否应人工介入

### 10.3 修改终点建议
#### 输入
- 原任务快照
- 原终点
- 候选终点
- 节点能力字典

#### 输出
- 候选终点排序
- 每个终点的风险与理由
- 是否需要同步修改能力列表

### 10.4 业务确认建议
#### 输入
- 业务确认事件
- 当前任务状态
- 历史类似任务

#### 输出
- 建议动作（KEEP/CHANGE/CANCEL/REPLAN）
- 解释原因
- 风险等级

### 10.5 任务组分析
#### 输入
- `groupCode`
- `mainTaskList`
- `subTaskList`
- 依赖关系

#### 输出
- 主子任务链路是否合理
- 独立运行是否冲突
- 是否存在重复节点
- 是否需要拆组/合组

---

## 11. 服务分层设计

### 11.1 API 层
负责接收请求并返回统一响应。

建议 Controller：
- `DiagnosisController`
- `TaskPreCheckController`
- `TaskGroupController`
- `CapabilityController`
- `QAController`
- `FeedbackController`

### 11.2 应用层
负责编排流程。

建议 Service：
- `DiagnosisService`
- `TaskPreCheckService`
- `TaskGroupAnalysisService`
- `CapabilityMatchService`
- `RecommendationService`

### 11.3 领域层
负责规则、模型、策略。

建议组件：
- `DiagnosisPolicy`
- `RiskAssessmentPolicy`
- `CapabilityMatchPolicy`
- `TaskConsistencyPolicy`

### 11.4 基础设施层
负责对接外部系统。

建议组件：
- `PlatformClient`
- `LLMClient`
- `VectorStoreClient`
- `DocumentParser`
- `KnowledgeRepository`
- `DiagnosisRepository`

---

## 12. 与平台表结构的对齐建议

### 12.1 不建议完全复制平台表
`loong-ai-diagnosis` 不应直接复制 `loong-platform` 全量表结构，而应建立“诊断侧最小必要模型”。

### 12.2 建议通过适配层映射
例如：
- 平台任务表 → 诊断任务快照表
- 平台事件表 → 诊断任务事件表
- 平台能力配置表 → 能力字典表
- 平台规则表 → 诊断规则表

### 12.3 推荐的同步方式
- 实时接口拉取
- 事件消息订阅
- 批量同步
- 按需回查

---

## 13. AI 推理策略设计

### 13.1 推理流程
1. 接收平台任务上下文
2. 标准化数据
3. 执行规则校验
4. 执行能力匹配
5. 执行知识检索
6. 调用大模型做语义分析
7. 合并规则、检索与模型结果
8. 输出结构化建议

### 13.2 Prompt 策略
建议使用“业务模板 + 约束模板 + 输出模板”组合：

- 业务模板：任务类型、场景、节点、能力
- 约束模板：禁止臆测、必须结构化输出
- 输出模板：风险、根因、建议、证据

### 13.3 模型选择策略
- 常规问答：OpenAI 兼容模型
- 国内能力分析：ZhipuAI
- 复杂任务分析：支持工具调用的模型
- 知识检索：向量召回 + rerank（如后续引入）

---

## 14. 风险控制设计

### 14.1 不能让 AI 直接改状态
AI 只能建议，不能直接执行：
- 改终点
- 暂停任务
- 重新下发
- 业务确认通过

### 14.2 必须保留审计链路
每次诊断都要记录：
- 请求内容
- 规则命中
- 模型输出
- 最终建议
- 是否采纳
- 采纳后结果

### 14.3 幂等控制
诊断请求要支持：
- `requestId`
- `taskNo`
- `scene`
- `eventId`

避免重复分析导致结果污染。

---

## 15. 实施路线图

### 阶段 1：统一模型与接口
目标：完成基础对齐。

交付内容：
- 统一诊断请求/响应模型
- 统一 `requiredFunctionList` 字典
- 统一任务快照模型
- 新增基础诊断接口

### 阶段 2：规则与适配层建设
目标：完成平台对接。

交付内容：
- 平台任务适配器
- 平台事件适配器
- 规则引擎
- 任务预检能力
- 改终点建议能力

### 阶段 3：知识库与案例库
目标：增强可解释性与准确率。

交付内容：
- 文档知识库
- 历史事件库
- 相似案例召回
- 经验规则沉淀

### 阶段 4：闭环联动
目标：形成平台智能辅助闭环。

交付内容：
- 任务暂停诊断联动
- 业务确认联动
- 自动建议生成
- 采纳效果反馈

---

## 16. 推荐的目录结构

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

## 17. 最终结论

基于 `loong-platform` 的架构、接口与表结构设计，`loong-ai-diagnosis` 应从“AI 通用工具服务”重构为“平台智能诊断中台服务”，其核心改造方向如下：

1. **统一任务诊断协议**，接入平台任务、事件、日志与状态
2. **建立领域模型**，覆盖任务快照、事件、诊断结果、建议动作
3. **搭建平台适配层**，映射平台接口和表结构
4. **构建规则引擎 + 知识检索 + LLM 的混合推理体系**
5. **输出结构化建议**，由 `loong-platform` 决策执行
6. **形成任务预检、异常诊断、业务确认、改终点、任务组分析等核心能力**

---

## 18. 后续建议
如果继续推进，下一步建议补充以下内容：

- `loong-platform` 实际表结构与字段映射表
- `loong-platform` 任务状态机设计
- `loong-ai-diagnosis` DTO / VO / Entity 的详细定义
- 关键接口的 OpenAPI 草案
- 数据库建表 SQL
- 诊断规则样例
- 典型场景流程图

