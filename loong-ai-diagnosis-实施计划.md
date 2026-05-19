# loong-ai-diagnosis 实施计划

> 目标：基于 `loong-platform` 的架构、接口与任务模型，将 `loong-ai-diagnosis` 重构为可落地的智能诊断与决策辅助服务。
>
> 适用范围：任务预检、异常诊断、改终点建议、业务确认建议、任务组分析、能力匹配、知识问答。

---

## 1. 项目目标

### 1.1 最终目标
将 `loong-ai-diagnosis` 从“通用 AI 能力服务”改造为：

- 可被 `loong-platform` 调用的智能诊断中台
- 提供结构化的诊断结果与建议动作
- 不直接执行平台业务动作，只输出建议
- 兼容任务、任务组、设备、节点、能力点位等调度场景

### 1.2 第一阶段交付目标
先完成最小可用版本（MVP），支持以下 4 个高频场景：

1. 任务创建前预检
2. 任务暂停诊断
3. 修改终点建议
4. 业务确认建议

---

## 2. 实施原则

### 2.1 平台负责执行，AI 负责建议
- `loong-platform` 负责任务创建、修改、暂停、恢复、下发、状态流转
- `loong-ai-diagnosis` 负责分析、诊断、推荐、解释

### 2.2 规则优先，模型兜底
- 先做硬规则校验
- 再做能力匹配与知识检索
- 最后交给模型做语义分析与总结

### 2.3 结构化输出优先
AI 结果必须结构化，不能只返回自然语言。

必须包含：
- `riskLevel`
- `rootCauses`
- `suggestions`
- `evidence`
- `needHumanConfirm`
- `confidence`

---

## 3. 总体推进路线

### 阶段 1：模型统一与接口对齐
目标：先把平台任务与 AI 诊断模型统一起来。

输出物：
- 统一诊断请求/响应模型
- 任务快照模型
- 事件模型
- 能力字典模型
- 规则模型
- 接口草案

### 阶段 2：平台适配层与规则引擎
目标：把平台数据接进来，并完成基础判断能力。

输出物：
- 平台任务适配器
- 平台事件适配器
- 规则校验器
- 能力匹配器
- 预检服务

### 阶段 3：知识库与相似案例召回
目标：增强可解释性与稳定性。

输出物：
- 文档知识库
- 规则知识库
- 历史案例库
- 向量召回能力

### 阶段 4：闭环联动
目标：让平台可以消费 AI 建议。

输出物：
- 诊断建议回传平台
- 采纳结果记录
- 反馈闭环
- 诊断复盘

---

## 4. 组织实施分工

### 4.1 后端负责人
职责：
- 重构服务结构
- 设计 DTO/VO/Entity
- 实现平台适配层
- 实现诊断接口
- 落库与幂等控制

### 4.2 业务/产品负责人
职责：
- 明确任务类型与场景
- 定义高频诊断场景
- 校验建议动作是否符合业务
- 确认状态机流转规则

### 4.3 算法/AI 负责人
职责：
- 设计 Prompt
- 设计规则 + 模型混合策略
- 调整知识库召回策略
- 输出结构化推理结果

### 4.4 测试负责人
职责：
- 设计场景用例
- 覆盖异常场景
- 验证平台接口联动
- 验证建议动作准确性

---

## 5. 任务拆解

### 5.1 第一步：统一领域模型

#### 需要定义的核心模型
- `TaskSnapshot`
- `TaskEvent`
- `DiagnosisRequest`
- `DiagnosisResult`
- `DiagnosisSuggestion`
- `RootCause`
- `CapabilityItem`
- `DiagnosisRule`

#### 统一字段建议
- `taskNo`
- `taskType`
- `taskSource`
- `bizType`
- `groupCode`
- `groupType`
- `startNode`
- `endNode`
- `requiredFunctionList`
- `containerList`
- `goodsInfoList`
- `extData`
- `status`
- `currentNode`
- `errorCode`
- `errorMessage`

#### 完成标准
- 所有诊断接口使用统一模型
- 平台任务数据可映射进 AI 模型
- 模型字段不再依赖平台原始 JSON 结构

---

### 5.2 第二步：设计接口边界

#### 新增 AI 接口
- `POST /diagnosis/analyze`
- `POST /diagnosis/task/pre-check`
- `POST /diagnosis/task/recommend-end-node`
- `POST /diagnosis/task/biz-decision`
- `POST /diagnosis/task-group/analyze`
- `POST /diagnosis/capability/match`
- `POST /diagnosis/qa`
- `POST /diagnosis/feedback`

#### 完成标准
- 平台可以通过统一入口调用 AI
- 每个接口都有明确职责
- 每个接口返回结构化结果

---

### 5.3 第三步：设计数据库表

#### 必须落地的表
- `ai_diagnosis_task_snapshot`
- `ai_diagnosis_task_event`
- `ai_diagnosis_request`
- `ai_diagnosis_result`
- `ai_capability_dictionary`
- `ai_diagnosis_rule`
- `ai_knowledge_document`
- `ai_knowledge_chunk`

#### 完成标准
- 诊断请求与结果可追溯
- 平台任务快照可复盘
- 能力字典可维护
- 规则可配置
- 知识库可检索

---

### 5.4 第四步：建立平台适配层

#### 适配器职责
- 将平台任务 JSON 转成 `TaskSnapshot`
- 将平台事件转成 `TaskEvent`
- 将平台能力字段转成能力字典查询条件
- 将平台状态机信息转成诊断上下文

#### 完成标准
- AI 服务不直接依赖平台表结构
- 平台数据变更不会导致 AI 核心逻辑大改
- 所有平台输入都可通过适配器归一化

---

### 5.5 第五步：先做规则引擎

#### 规则优先处理场景
- 必填字段缺失
- 任务类型与节点不匹配
- `requiredFunctionList` 冲突
- `containerList` 与 `goodsInfoList` 约束不满足
- 任务组主子任务关系不合理
- 终点不可达或不具备能力

#### 完成标准
- 规则校验能独立给出诊断结果
- 不依赖大模型也能完成基础判断
- 规则命中可审计

---

### 5.6 第六步：再接知识库与模型

#### 知识库内容
- 平台接口文档
- 调度规则文档
- 历史异常案例
- 操作手册
- 设备与节点能力说明

#### 模型输出职责
- 根因总结
- 建议动作排序
- 人类可读解释
- 风险级别判断补充

#### 完成标准
- 模型不做硬规则判断，只做语义补全与解释
- 输出必须和规则引擎结果合并

---

## 6. MVP 实施范围

### 6.1 第一版只做的能力
1. 任务预检
2. 任务暂停诊断
3. 改终点推荐
4. 业务确认建议

### 6.2 第一版不做的能力
- 自动改任务状态
- 自动下发设备指令
- 自动重规划全链路
- 复杂多任务协同优化
- 深度学习型预测优化

### 6.3 MVP 成功标准
- 平台可以调用 AI 接口
- AI 返回结构化建议
- 建议能被业务人员理解
- 关键异常场景能给出可用建议

---

## 7. 接口落地顺序

### 第 1 批
- `POST /diagnosis/task/pre-check`
- `POST /diagnosis/analyze`

### 第 2 批
- `POST /diagnosis/task/recommend-end-node`
- `POST /diagnosis/task/biz-decision`

### 第 3 批
- `POST /diagnosis/task-group/analyze`
- `POST /diagnosis/capability/match`

### 第 4 批
- `POST /diagnosis/qa`
- `POST /diagnosis/feedback`

---

## 8. 数据表落地顺序

### 第 1 批建表
- `ai_diagnosis_request`
- `ai_diagnosis_result`
- `ai_diagnosis_task_snapshot`
- `ai_diagnosis_task_event`

### 第 2 批建表
- `ai_capability_dictionary`
- `ai_diagnosis_rule`

### 第 3 批建表
- `ai_knowledge_document`
- `ai_knowledge_chunk`

---

## 9. 工程结构落地建议

```text
com.xxx.loong.ai.diagnosis
├── api
├── application
├── domain
├── infrastructure
├── interface
└── common
```

### 推荐包职责
- `api`：Controller
- `application`：业务编排
- `domain`：领域模型与策略
- `infrastructure`：数据库、模型、平台对接
- `interface`：DTO/VO/Assembler
- `common`：通用异常、返回体、工具类

---

## 10. 风险与控制点

### 10.1 最大风险
- AI 直接参与执行控制导致业务不可控
- 平台字段不统一导致适配成本高
- 知识库不完整导致结果不稳定
- 规则和模型边界不清导致结果混乱

### 10.2 控制措施
- AI 只输出建议，不执行动作
- 平台任务统一快照化
- 规则优先，模型兜底
- 全链路留痕可审计

---

## 11. 推荐里程碑

### M1：一周内
- 完成模型和接口定义
- 完成表结构草案
- 完成平台字段映射表

### M2：两周内
- 完成适配层
- 完成预检与诊断接口
- 完成基础规则引擎

### M3：三周内
- 完成知识库接入
- 完成相似案例召回
- 完成业务确认与改终点建议

### M4：四周内
- 完成平台联调
- 完成日志审计
- 完成首批场景上线

---

## 12. 需要立即确认的事项

1. `loong-platform` 是否已有任务状态机定义
2. `requiredFunctionList` 是否有标准字典表
3. 平台是否已有节点/设备能力表
4. 平台是否已有任务事件表
5. AI 服务是否允许接收平台任务快照全文
6. 第一阶段优先支持哪些任务类型

---

## 13. 最终建议

建议按照以下顺序推进：

1. 先定统一诊断协议
2. 再定任务快照和事件模型
3. 再补数据库表
4. 再实现平台适配层
5. 再做规则引擎
6. 最后接模型和知识库

---

## 14. 输出物清单

实施完成后，至少应该形成以下交付物：

- 设计文档
- 接口文档
- 表结构 SQL
- DTO/VO 定义
- 规则清单
- 知识库导入方案
- 联调说明
- 场景测试用例

---

## 15. 结论

这套方案是可落地的，但前提是：

- 先统一平台任务模型
- 先建立 AI 诊断协议
- 先做规则和适配层
- 再接知识库与大模型

这样 `loong-ai-diagnosis` 才能真正成为 `loong-platform` 的智能诊断能力层，而不是一个孤立的 AI 工具服务。
