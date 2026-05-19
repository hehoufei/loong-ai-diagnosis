# loong-platform × loong-ai-diagnosis 架构与接口对齐分析

## 1. 结论

`loong-platform` 更适合作为调度与任务编排中枢，`loong-ai-diagnosis` 更适合作为平台下的智能诊断与决策辅助服务。

推荐定位如下：

- `loong-platform` 负责任务创建、任务组管理、状态流转、节点/设备管理、执行控制、审计与回调。
- `loong-ai-diagnosis` 负责任务可行性分析、异常根因分析、风险评估、终点修改建议、业务确认建议、相似案例检索与自然语言解释。

---

## 2. 当前 `loong-ai-diagnosis` 的能力判断

从现有 `pom.xml` 可以看出，`loong-ai-diagnosis` 已具备以下基础能力：

- 大模型调用能力
- 向量检索能力
- 文档解析能力
- JDBC / MySQL 持久化能力
- OpenAPI 接口能力

这意味着它已经具备做“智能分析服务”的基础，但还缺少与调度平台对接的统一领域模型和标准诊断接口。

---

## 3. `loong-platform` 侧的核心业务特征

根据接口样例，`loong-platform` 已经覆盖了这些核心场景：

- `/task/getDevicePlanTask`
- `/engine/queryEngineContent`
- `/task/pauseTask`
- `/task/changeTaskEndNode`
- `/task/bizConfirmTask`
- `/task/addTask`
- `/task/addTaskGroup`
- `/adaptor/api/wcs/order`

说明它不仅是任务管理系统，更接近：

- 调度中心
- 任务编排中心
- 设备/节点能力路由中心
- 人工确认与异常处理中心

---

## 4. 任务模型特征

接口样例中的任务结构已经表现出明显的平台化特征，主要字段包括：

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
- `extData`
- `mainTaskList`
- `subTaskList`

其中最关键的是 `requiredFunctionList`，它本质上不是普通参数，而是：

- 任务能力声明
- 节点能力匹配条件
- 流程约束条件
- 设备路由条件

---

## 5. `loong-ai-diagnosis` 应该怎么改造

### 5.1 定位升级

建议从“AI 能力服务”升级为“平台智能诊断中台”。

### 5.2 推荐模块划分

#### API 层
建议新增统一诊断接口，例如：

- `POST /diagnosis/analyze`
- `POST /diagnosis/recommend`
- `POST /diagnosis/risk`
- `POST /diagnosis/trace`
- `POST /diagnosis/qa`
- `POST /diagnosis/capability/match`

#### 应用层
负责：

- 参数标准化
- 任务上下文拼装
- 规则校验
- 知识库检索
- LLM 推理
- 结果合并

#### 领域层
建议定义：

- `DiagnosisRequest`
- `DiagnosisContext`
- `TaskSnapshot`
- `TaskEvent`
- `DiagnosisResult`
- `DiagnosisSuggestion`
- `RootCause`
- `RiskLevel`

#### 基础设施层
负责对接：

- 模型供应商
- 向量库
- 文档解析器
- 平台接口
- 数据库

#### 适配层
负责把 `loong-platform` 的任务、事件、日志转成标准诊断上下文。

---

## 6. 推荐的接口设计方向

### 6.1 统一诊断入口

`POST /diagnosis/analyze`

用于对任务执行状态、异常、日志、节点信息进行统一分析。

输出建议包含：

- 风险等级
- 根因分析
- 推荐动作
- 证据片段
- 是否需要人工确认

### 6.2 能力匹配接口

`POST /diagnosis/capability/match`

用于判断任务与节点/设备能力是否匹配。

### 6.3 业务确认辅助接口

`POST /diagnosis/task/biz-decision`

用于 `WAIT_BIZ_DECISION` 场景下的决策建议。

### 6.4 改终点建议接口

`POST /diagnosis/task/recommend-end-node`

用于辅助 `changeTaskEndNode` 场景。

### 6.5 任务组分析接口

`POST /diagnosis/task-group/analyze`

用于分析主任务 / 子任务的编排合理性。

---

## 7. 推荐的诊断输出结构

建议统一输出结构，例如：

```json
{
  "requestId": "REQ_20260508_0001",
  "diagnosisId": "DIA_20260508_0001",
  "scene": "TASK_EXCEPTION",
  "riskLevel": "HIGH",
  "summary": "当前任务终点与节点能力不匹配，建议改终点或补充能力点。",
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
      "content": "requiredFunctionList 包含 WEIGHING_NODE"
    }
  ],
  "needHumanConfirm": true
}
```

---

## 8. `requiredFunctionList` 的平台化建议

建议把 `requiredFunctionList` 统一做成能力字典表，字段包括：

- `functionType`
- `functionName`
- `description`
- `category`
- `supportedTaskTypes`
- `supportedNodeTypes`
- `requiredExtDataSchema`
- `isCritical`
- `isOptional`

这样平台和 AI 可以共用同一套能力语义。

---

## 9. 任务模型标准化建议

建议统一成三层模型：

### TaskBase
- `taskNo`
- `taskSource`
- `taskType`
- `bizType`
- `bizPriority`
- `startNode`
- `endNode`
- `remark`

### TaskPayload
- `requiredFunctionList`
- `containerList`
- `goodsInfoList`
- `extData`

### TaskRuntime
- `status`
- `currentNode`
- `currentDevice`
- `lastEvent`
- `errorCode`
- `bizDecision`

---

## 10. 推荐的调用链

典型流程建议如下：

1. `loong-platform` 接收任务或发现异常
2. 汇聚任务快照、日志、设备状态、事件
3. 调用 `loong-ai-diagnosis /diagnosis/analyze`
4. AI 返回根因、风险、建议动作
5. 平台根据建议执行暂停、改终点、业务确认或人工介入

---

## 11. 改造优先级建议

### 第一优先级
- `pauseTask`
- `changeTaskEndNode`
- `bizConfirmTask`

### 第二优先级
- `addTask`
- `addTaskGroup`

### 第三优先级
- `getDevicePlanTask`
- `queryEngineContent`

---

## 12. 最终建议

遵循原则：

- 平台管流程
- AI 管判断
- 平台管执行
- AI 管建议

这是最稳定、最容易落地、也最适合后续扩展的改造方式。

---

## 13. 后续可继续输出的内容

如果需要，下一步可以继续补充：

1. `loong-platform` → `loong-ai-diagnosis` 接口对齐表
2. 任务模型统一设计稿
3. `loong-ai-diagnosis` 的包结构重构方案
4. 逐接口 DTO 设计建议
5. AI 诊断流程图与状态机设计
