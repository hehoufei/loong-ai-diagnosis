# Requirements: loong-ai-diagnosis 重构

## 概述

loong-ai-diagnosis 是一个 WCS 任务诊断工具。输入一个 `taskNo`，输出"这个任务为什么卡住、该怎么办"。

### 核心定位
- **是什么**：任务诊断小工具
- **不是什么**：不是诊断中台、不是决策系统、不调平台写接口
- **运行模式**：无状态、不落库、不记录、用完即走
- **数据来源**：HTTP 调 loong-platform 现有接口拿任务全貌
- **分析链路**：规则引擎优先 → 未命中走 LLM 兜底

---

## 功能需求

### REQ-1: REST API 入口
**描述**: 提供单一 REST 接口用于任务诊断

**验收标准**:
- [ ] AC-1.1: 提供 `POST /api/v1/diagnosis/analyze` 接口
- [ ] AC-1.2: 入参只需 `taskId`（任务号）
- [ ] AC-1.3: 出参包含 `summary`（一句话根因）、`rootCauses`（根因列表）、`actions`（建议动作）、`diagnosisMode`（rule/llm/fallback）、`confidence`（置信度）、`traceId`（链路追踪ID）
- [ ] AC-1.4: 支持 Swagger/OpenAPI 文档

---

### REQ-2: 平台数据获取层
**描述**: 通过 HTTP 调用 loong-platform 接口获取任务数据

**验收标准**:
- [ ] AC-2.1: 实现 `PlatformClient` 接口，包含 `queryTask`、`queryTaskItemBundle`、`queryTaskGroup`、`queryDevice` 方法
- [ ] AC-2.2: `queryTask` 调用 `POST /task/queryTaskByTaskNo` 获取大任务信息
- [ ] AC-2.3: `queryTaskItemBundle` 调用 `GET /api/admin/scheduler/item/getTaskItemDetails` 获取子任务+指令+依赖关系
- [ ] AC-2.4: `queryTaskGroup` 调用 `GET /api/admin/scheduler/task/getTasksByGroupCode` 获取任务组信息
- [ ] AC-2.5: 实现 `EnumReverseMapper` 将中文枚举（如"运行中"）反向映射为 code（如"RUNNING"）
- [ ] AC-2.6: HTTP 超时配置：连接 3s、读取 5s
- [ ] AC-2.7: 任务不存在时抛 `TASK_NOT_FOUND` 异常
- [ ] AC-2.8: 平台不可达时抛 `PLATFORM_UNAVAILABLE` 异常
- [ ] AC-2.9: `queryTaskGroup`、`queryDevice` 失败时不阻断主链路，仅日志告警

---

### REQ-3: 诊断上下文
**描述**: 组装规则引擎和 LLM 共用的诊断上下文

**验收标准**:
- [ ] AC-3.1: `DiagnosisContext` 包含：`traceId`、`task`（大任务）、`items`（子任务列表）、`relations`（依赖关系）、`group`（任务组）、`now`（当前时间）
- [ ] AC-3.2: 提供便捷方法 `allCommands()`（所有指令扁平化）
- [ ] AC-3.3: 提供便捷方法 `activeItems()`（非终态子任务）
- [ ] AC-3.4: 提供便捷方法 `runningItems()`（RUNNING 状态子任务）
- [ ] AC-3.5: 提供便捷方法 `stuckSeconds()`（计算卡住时长）
- [ ] AC-3.6: 提供便捷方法 `isTerminal()`（判断是否终态）
- [ ] AC-3.7: 提供便捷方法 `isGroupTask()`（判断是否任务组任务）
- [ ] AC-3.8: `DiagnosisContextAssembler` 从 `PlatformClient` 数据组装 Context

---

### REQ-4: 规则引擎
**描述**: 基于新版状态机的规则引擎，优先匹配规则返回诊断结果

**验收标准**:
- [ ] AC-4.1: `DiagnoseRule` 接口使用新版 `DiagnosisContext`
- [ ] AC-4.2: `AbstractDiagnoseRule` 支持新版模板变量：`{taskNo}`、`{taskState}`、`{stuckSeconds}`、`{stuckMinutes}`、`{itemCount}`、`{runningItemCount}`、`{errorMessage}`
- [ ] AC-4.3: `ConfigurableRuleEngine` 去掉落库依赖，统计用内存 Map
- [ ] AC-4.4: 规则按优先级排序执行，首个命中即返回
- [ ] AC-4.5: 规则配置通过 `diagnosis-rules.yml` 管理

---

### REQ-5: 10 条诊断规则
**描述**: 实现覆盖主要卡住场景的 10 条规则

**验收标准**:
- [ ] AC-5.1: `wait-split-stuck`（优先级100）：`task_state=WAIT_SPLIT` 且卡住超阈值（默认60s）
- [ ] AC-5.2: `wait-plan-stuck`（优先级90）：`task_state=WAIT_PLAN` 且所有子任务都不是 RUNNING
- [ ] AC-5.3: `running-but-items-waiting`（优先级85）：`task_state=RUNNING` 但所有子任务都是 WAIT_*
- [ ] AC-5.4: `command-not-sent`（优先级80）：存在 RUNNING 子任务但其下 Command 全部 WAIT
- [ ] AC-5.5: `plc-no-response`（优先级80）：存在 Command 处于 SENT 状态但无 plcTaskNo
- [ ] AC-5.6: `command-timeout`（优先级75）：存在 Command 的 startTime 距今超阈值（默认300s）仍未完成
- [ ] AC-5.7: `all-items-done-but-task-not-finished`（优先级70）：所有子任务 SUCCESS 但大任务仍 RUNNING/WAIT_PLAN
- [ ] AC-5.8: `has-cancelled-items`（优先级60）：大任务未终态但存在 CANCEL 的子任务
- [ ] AC-5.9: `task-paused`（优先级50）：大任务或所有 RUNNING 子任务被暂停
- [ ] AC-5.10: `task-group-not-split`（优先级40）：任务组长时间未完成拆分
- [ ] AC-5.11: `fallback`（优先级-100）：兜底规则，永远命中

---

### REQ-6: LLM 兜底
**描述**: 规则未命中时调用 LLM 进行智能分析

**验收标准**:
- [ ] AC-6.1: `PromptTemplateLoader` 从 `resources/prompts/` 加载模板文件
- [ ] AC-6.2: 提供 4 个 prompt 文件：`system-role.txt`、`business-context.txt`、`decision-tree.txt`、`output-format.txt`
- [ ] AC-6.3: `renderTaskData` 方法将 Context 序列化为结构化文本
- [ ] AC-6.4: LLM 超时配置（默认5000ms）
- [ ] AC-6.5: LLM 调用失败时降级为 fallback 响应
- [ ] AC-6.6: 支持通过配置开关 `enable-llm-fallback` 控制是否启用 LLM

---

### REQ-7: 编排层
**描述**: DiagnosisFacade 作为唯一编排入口

**验收标准**:
- [ ] AC-7.1: 校验 taskId 非空
- [ ] AC-7.2: 组装 DiagnosisContext
- [ ] AC-7.3: 已终态任务直接返回"无需诊断"
- [ ] AC-7.4: 规则引擎优先执行
- [ ] AC-7.5: 规则未命中且启用 LLM 时调用 LLM
- [ ] AC-7.6: LLM 失败时返回 fallback 响应
- [ ] AC-7.7: 设置 `diagnosisMode`（rule/llm/fallback）和 `confidence`
- [ ] AC-7.8: MDC 贯穿 traceId 用于日志追踪

---

### REQ-8: 配置管理
**描述**: 清晰的配置文件布局

**验收标准**:
- [ ] AC-8.1: `application.yml` 包含 Spring 框架和外部服务连接配置
- [ ] AC-8.2: `diagnosis-core.yml` 包含 `loong.ai.diagnosis.*` 和 `loong.platform.*` 配置
- [ ] AC-8.3: `diagnosis-rules.yml` 包含规则配置
- [ ] AC-8.4: 支持环境变量覆盖：`LLM_API_KEY`、`LLM_MODEL`、`PLATFORM_URL`

---

### REQ-9: 代码清理
**描述**: 删除旧代码，保持代码库整洁

**验收标准**:
- [ ] AC-9.1: 删除 `platform/*` 整个子包
- [ ] AC-9.2: 删除 `experiment/*` 整个子包
- [ ] AC-9.3: 删除 `tool/*` 整个子包
- [ ] AC-9.4: 删除 `AgentChatService`、`DiagnosisStreamService`、`GlobalDiagnosisService` 等无用服务
- [ ] AC-9.5: 删除旧的 13 条规则
- [ ] AC-9.6: 删除旧的 `rule/DiagnosisContext.java`
- [ ] AC-9.7: 删除无用的 DTO 和 Controller

---

## 非功能需求

### NFR-1: 性能
- 单次诊断 P95 < 2s（规则路径）
- 单次诊断 P95 < 8s（LLM 路径）
- HTTP 调平台可并行请求

### NFR-2: 日志
- 每次诊断打印：`DIAG_START`、`PLATFORM_CALL`、`RULE_MATCHED`、`DIAG_END`
- MDC 用 `traceId` 贯穿

### NFR-3: 安全
- LLM API Key 走环境变量，不提交仓库
- 平台错误信息可透传（内部运维使用）

### NFR-4: 熔断降级
- 平台不可达：抛 `PLATFORM_UNAVAILABLE`
- LLM 超时/异常：自动降级 fallback

---

### REQ-10: 平台数据代理接口
**描述**: 提供对 loong-platform 查询接口的透传代理，供前端任务查询页面使用。不涉及诊断逻辑，不调用 loong-platform 写接口。

**验收标准**:
- [ ] AC-10.1: `POST /api/v1/platform/task/page` 代理任务分页查询，支持状态/类型/来源过滤
- [ ] AC-10.2: `GET /api/v1/platform/task/detail?taskNo=xxx` 代理大任务详情（含任务组、关联任务）
- [ ] AC-10.3: `GET /api/v1/platform/task/items?taskNo=xxx` 代理子任务+指令+依赖关系查询
- [ ] AC-10.4: `GET /api/v1/platform/task/group?groupCode=xxx` 代理任务组信息查询
- [ ] AC-10.5: `GET /api/v1/platform/task/queryParams` 代理查询下拉参数（状态/类型/来源枚举）
- [ ] AC-10.6: `POST /api/v1/map/nodeList` 和 `POST /api/v1/map/viewDetail` 代理地图接口（供 map.html 使用）
- [ ] AC-10.7: 代理接口失败时返回 503，不影响诊断主链路

---

### REQ-11: 规则管理接口
**描述**: 为 rules.html 提供规则引擎的运行时管理接口，支持查看规则列表、统计、动态调整优先级/启用状态。

**验收标准**:
- [ ] AC-11.1: `GET /api/v1/rules` 返回所有规则（名称/优先级/启用状态/命中次数/平均耗时/最后命中时间）
- [ ] AC-11.2: `GET /api/v1/rules/stats` 返回引擎统计（活跃规则数/总诊断次数/平均耗时/最近命中规则）
- [ ] AC-11.3: `PUT /api/v1/rules/{name}/priority` 动态更新规则优先级（内存生效）
- [ ] AC-11.4: `PUT /api/v1/rules/{name}/enabled` 动态启用/禁用规则（内存生效）
- [ ] AC-11.5: `POST /api/v1/rules/reload` 从 YAML 重新加载规则配置
- [ ] AC-11.6: `POST /api/v1/rules/reset-stats` 重置命中统计

---

### REQ-12: Agent 对话接口
**描述**: 为 chat.html 提供 SSE 流式对话接口，Agent 内置任务诊断和查询工具。

**验收标准**:
- [ ] AC-12.1: `POST /api/v2/diagnosis/chat` 接收 `{sessionId, message}`，返回 `text/event-stream`
- [ ] AC-12.2: SSE 事件类型包含：`thinking`、`tool_call`、`tool_result`、`text`、`error`、`done`
- [ ] AC-12.3: Agent 内置工具：`diagnose_task(taskNo)` 调用 DiagnosisFacade、`query_task_detail(taskNo)` 调用 PlatformClient
- [ ] AC-12.4: `GET /api/v2/diagnosis/chat/models` 返回可用模型列表
- [ ] AC-12.5: `POST /api/v2/diagnosis/chat/models/switch` 切换当前模型
- [ ] AC-12.6: `GET /api/v2/diagnosis/chat/env` 返回当前环境信息（环境名/模型名）

---

## 不在 MVP 范围

- 知识库检索（BM25 / 向量 / 混合检索）
- 历史案例召回
- 任务组的全局关联分析
- 流式诊断（SSE）
- 诊断规则的运行时管理 UI
- 诊断历史记录
- 多任务批量诊断
