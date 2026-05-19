# Tasks: loong-ai-diagnosis 重构

> 本任务列表按 M1-M6 阶段顺序组织，每个阶段可独立验收。

---

## M1: 清理与骨架

- [ ] 1. 删除旧代码与无用模块
  - [x] 1.1 删除 `platform/*` 整个子包
  - [~] 1.2 删除 `experiment/*` 整个子包
  - [~] 1.3 删除 `tool/*` 整个子包
  - [~] 1.4 删除无用的服务类：`AgentChatService`、`DiagnosisStreamService`、`GlobalDiagnosisService`、`GlobalDiagnosisContextAssembler`、`GlobalDiagnosisContext`、`BlockageAnalyzer`
  - [~] 1.5 删除知识库检索相关：`BM25SearchService`、`HybridSearchService`、`DocSearchService`
  - [~] 1.6 删除旧的 Controller：`AgentChatController`、`DiagnosisStreamController`、`DocumentEtlController`、`ExperimentController`
  - [~] 1.7 删除旧的 13 条规则类（rule/rules/*）
  - [~] 1.8 删除旧的 `rule/DiagnosisContext.java`
  - [~] 1.9 删除无用的 DTO：`TaskDetail`、`RelevantDoc`、`PointConflict`、`ResourceBottleneck`、`TaskRelationEdge` 等
  - [~] 1.10 删除旧的 Client：`HttpTaskClient`、`JdbcTaskClient`、`MockTaskClient`、`TaskClient`、`LogClient`、`HttpLogClient`、`JdbcLogClient`、`MockLogClient`
  - [~] 1.11 删除规则引擎辅助类：`RuleEngineSnapshotService`、`RuleEngineBootstrapService`、`RulePriorityService`、`RuleStatisticsService`、`ExpressionRuleRegistryService`
  - [~] 1.12 删除无用配置类：`DiagnosisPromptProperties`、`AgentConfig`、`AgentProperties`、`ChatMemoryConfig`、`VectorStoreConfig`
  - _Requirements: REQ-9_

- [ ] 2. 搭建新包结构骨架
  - [~] 2.1 创建新包目录：`facade/`、`client/`、`client/dto/`、`context/`、`llm/`
  - [~] 2.2 更新 `DiagnoseRequest` DTO（只保留 taskId 字段）
  - [~] 2.3 更新 `DiagnoseResponse` DTO（保留 summary/rootCauses/actions/diagnosisMode/confidence/traceId）
  - [~] 2.4 `DiagnosisController` 只保留 `POST /api/v1/diagnosis/analyze`，其他接口删除
  - [~] 2.5 `DiagnosisController` 返回占位响应（让项目能编译）
  - [~] 2.6 执行 `mvn compile` 确保工程编译通过
  - _Requirements: REQ-1_

---

## M2: 数据获取层

- [ ] 3. 实现平台 DTO
  - [~] 3.1 创建 `PlatformTask`（对应 sc_task，约 20 个字段）
  - [~] 3.2 创建 `PlatformTaskItem`（对应 sc_task_item，含嵌套 commands）
  - [~] 3.3 创建 `PlatformCommand`（对应 sc_command）
  - [~] 3.4 创建 `PlatformTaskRelation`（子任务依赖）
  - [~] 3.5 创建 `PlatformTaskGroup`（对应 sc_task_group_info）
  - [~] 3.6 创建 `PlatformDeviceStatus`（设备状态，可选字段）
  - _Requirements: REQ-2_

- [ ] 4. 实现 EnumReverseMapper
  - [~] 4.1 定义任务状态反向映射表（中文 → code）
  - [~] 4.2 定义子任务状态反向映射表
  - [~] 4.3 定义指令状态反向映射表
  - [~] 4.4 定义其他枚举映射（paused、scheduleChannel、splitFinish 等）
  - [~] 4.5 提供 `toXxxCode` 系列方法，已是 code 时原样返回
  - _Requirements: REQ-2_

- [ ] 5. 实现 PlatformClient
  - [~] 5.1 定义 `PlatformClient` 接口（queryTask/queryTaskItemBundle/queryTaskGroup/queryDevice）
  - [~] 5.2 定义内部类 `TaskItemBundle`
  - [~] 5.3 实现 `HttpPlatformClient.queryTask`（调用 `/task/queryTaskByTaskNo`）
  - [~] 5.4 实现 `HttpPlatformClient.queryTaskItemBundle`（调用 `/api/admin/scheduler/item/getTaskItemDetails`，应用反向映射）
  - [~] 5.5 实现 `HttpPlatformClient.queryTaskGroup`（调用 `/api/admin/scheduler/task/getTasksByGroupCode`，应用反向映射）
  - [~] 5.6 实现 `HttpPlatformClient.queryDevice`（调用 `/dcs/queryDeviceByDeviceCode`）
  - [~] 5.7 配置 RestTemplate 超时（连接3s、读取5s）
  - [~] 5.8 404 抛 `TASK_NOT_FOUND`，5xx 抛 `PLATFORM_UNAVAILABLE`
  - [~] 5.9 queryTaskGroup/queryDevice 失败时吞异常，仅日志告警
  - _Requirements: REQ-2_

- [ ] 6. 创建配置绑定
  - [~] 6.1 创建 `PlatformProperties`（绑定 `loong.platform.*`）
  - [~] 6.2 更新 `DiagnosisProperties`（绑定 `loong.ai.diagnosis.*`，包含 enable-llm-fallback、llm-timeout-ms、confidence）
  - [~] 6.3 创建 `diagnosis-core.yml` 配置文件
  - [~] 6.4 更新 `application.yml`，`import` 引入 diagnosis-core.yml
  - _Requirements: REQ-8_

- [ ] 7. 手工验证 M2 阶段
  - [~] 7.1 给定真实 taskNo，日志打印拉到的 PlatformTask
  - [~] 7.2 日志打印 items 和 commands 数量
  - [~] 7.3 验证中文枚举已正确反向映射为 code
  - _Requirements: REQ-2_

---

## M3: Context 与规则引擎

- [ ] 8. 实现 DiagnosisContext
  - [~] 8.1 定义 `DiagnosisContext` 结构（task/items/relations/group/now/traceId）
  - [~] 8.2 实现 `allCommands()` 方法
  - [~] 8.3 实现 `activeItems()` 方法
  - [~] 8.4 实现 `runningItems()` 方法
  - [~] 8.5 实现 `stuckSeconds()` 方法
  - [~] 8.6 实现 `isTerminal()` 方法
  - [~] 8.7 实现 `isGroupTask()` 方法
  - _Requirements: REQ-3_

- [ ] 9. 实现 DiagnosisContextAssembler
  - [~] 9.1 从 `PlatformClient` 组装 DiagnosisContext
  - [~] 9.2 任务不存在时抛 `TASK_NOT_FOUND`
  - [~] 9.3 groupCode 非空时查询任务组（失败不阻断）
  - _Requirements: REQ-3_

- [ ] 10. 改造规则引擎基础设施
  - [~] 10.1 更新 `DiagnoseRule` 接口签名（使用新版 Context）
  - [~] 10.2 改造 `AbstractDiagnoseRule`（新版模板变量：taskNo/taskState/stuckSeconds/stuckMinutes/itemCount/runningItemCount/errorMessage）
  - [~] 10.3 改造 `ConfigurableRuleEngine`（移除落库依赖，统计用 `ConcurrentHashMap<String, AtomicLong>`）
  - [~] 10.4 保留并验证 `RuleProperties`（YAML 绑定）
  - _Requirements: REQ-4_

- [ ] 11. 实现第一条规则 WaitSplitStuckRule
  - [~] 11.1 实现 `WaitSplitStuckRule.match`（task_state=WAIT_SPLIT 且卡住超阈值，排除任务组未拆完场景）
  - [~] 11.2 实现 `WaitSplitStuckRule.diagnose`（调用 `buildResponseFromConfig`）
  - [~] 11.3 创建 `diagnosis-rules.yml` 配置文件，配置 wait-split-stuck 规则
  - _Requirements: REQ-5, REQ-4_

- [ ] 12. 实现 FallbackRule
  - [~] 12.1 `FallbackRule.match` 永远返回 true，优先级 -100
  - [~] 12.2 `FallbackRule.diagnose` 返回 null（表示规则层无结论）
  - _Requirements: REQ-5_

- [ ] 13. 接入 DiagnosisFacade
  - [~] 13.1 创建 `DiagnosisFacade`（在 `facade/` 包下）
  - [~] 13.2 实现编排逻辑：校验 → 组装 Context → 终态判断 → 规则引擎
  - [~] 13.3 MDC 设置 traceId
  - [~] 13.4 更新 `DiagnosisController` 调用 DiagnosisFacade
  - _Requirements: REQ-7_

- [ ] 14. 手工验证 M3 阶段
  - [~] 14.1 构造一个 WAIT_SPLIT 卡住的任务
  - [~] 14.2 调用 `POST /api/v1/diagnosis/analyze` 验证规则命中
  - [~] 14.3 检查返回的 summary/rootCauses/actions 符合预期
  - _Requirements: REQ-4, REQ-5_

---

## M4: 规则全量铺开

- [ ] 15. 实现剩余 9 条规则
  - [~] 15.1 `WaitPlanStuckRule`（优先级90）
  - [~] 15.2 `RunningButItemsWaitingRule`（优先级85）
  - [~] 15.3 `CommandNotSentRule`（优先级80）
  - [~] 15.4 `PlcNoResponseRule`（优先级80）
  - [~] 15.5 `CommandTimeoutRule`（优先级75）
  - [~] 15.6 `AllItemsDoneButTaskNotFinishedRule`（优先级70）
  - [~] 15.7 `HasCancelledItemsRule`（优先级60）
  - [~] 15.8 `TaskPausedRule`（优先级50）
  - [~] 15.9 `TaskGroupNotSplitRule`（优先级40）
  - _Requirements: REQ-5_

- [ ] 16. 补全 YAML 规则配置
  - [~] 16.1 为 9 条新规则分别添加 YAML 配置（summary/root-causes/actions/params）
  - _Requirements: REQ-5_

- [ ] 17. 规则单元测试
  - [~] 17.1 为每条规则写一个测试用例（Mock PlatformClient 返回对应场景数据）
  - [~] 17.2 验证 RuleEngine.evaluate 返回预期规则
  - _Requirements: REQ-5_

---

## M5: LLM 兜底

- [ ] 18. 实现 PromptTemplateLoader
  - [~] 18.1 创建 `src/main/resources/prompts/` 目录
  - [~] 18.2 编写 `system-role.txt`（系统角色设定）
  - [~] 18.3 编写 `business-context.txt`（基于新版状态机的 WCS 业务背景）
  - [~] 18.4 编写 `decision-tree.txt`（诊断决策树）
  - [~] 18.5 编写 `output-format.txt`（JSON 输出格式要求）
  - [~] 18.6 实现 `PromptTemplateLoader` 加载并缓存模板
  - [~] 18.7 实现 `buildUserPrompt(ctx)` 方法
  - [~] 18.8 实现 `renderTaskData(ctx)` 方法（结构化文本输出）
  - [~] 18.9 实现异常信号自动检测逻辑（Command 超时、子任务暂停等）
  - _Requirements: REQ-6_

- [ ] 19. 改造 LlmDiagnosisEngine
  - [~] 19.1 适配新版 DiagnosisContext
  - [~] 19.2 使用 PromptTemplateLoader 构建 prompt
  - [~] 19.3 超时控制（默认 5000ms）
  - [~] 19.4 重试 1 次
  - [~] 19.5 保留 `DiagnosisResponseMapper` 解析 JSON 响应
  - _Requirements: REQ-6_

- [ ] 20. DiagnosisFacade 接入 LLM 兜底
  - [~] 20.1 规则未命中时调用 LlmDiagnosisEngine
  - [~] 20.2 `enable-llm-fallback=false` 时跳过 LLM
  - [~] 20.3 LLM 失败时降级 fallback 响应
  - [~] 20.4 设置正确的 diagnosisMode 和 confidence
  - _Requirements: REQ-7, REQ-6_

- [ ] 21. 手工验证 M5 阶段
  - [~] 21.1 构造一个不在规则覆盖范围的场景
  - [~] 21.2 验证走 LLM 路径并返回结构化结果
  - [~] 21.3 验证 LLM 超时时降级 fallback
  - _Requirements: REQ-6_

---

## M6: 平台数据代理接口（前端查询用）

> 这些接口是对 loong-platform 现有接口的透传代理，供前端页面（任务查询、子任务/指令查看、地图等）直接调用。
> **不涉及诊断逻辑，不修改 loong-platform，只做 HTTP 代理转发。**

- [x] 22. 实现 PlatformProxyController
  - [x] 22.1 创建 `controller/PlatformProxyController`，路由前缀 `/api/v1/platform`
  - [x] 22.2 `POST /api/v1/platform/task/page` → 代理 `POST /api/admin/scheduler/task/pageTasks`（任务分页查询，支持状态/类型/来源等过滤）
  - [x] 22.3 `GET /api/v1/platform/task/detail?taskNo=xxx` → 代理 `GET /api/admin/scheduler/task/detail/getTaskDetail`（大任务详情，含任务组和关联任务）
  - [x] 22.4 `GET /api/v1/platform/task/items?taskNo=xxx` → 代理 `GET /api/admin/scheduler/item/getTaskItemDetails`（子任务+指令+依赖关系）
  - [x] 22.5 `GET /api/v1/platform/task/group?groupCode=xxx` → 代理 `GET /api/admin/scheduler/task/getTasksByGroupCode`（任务组信息）
  - [x] 22.6 `GET /api/v1/platform/task/queryParams` → 代理 `GET /api/admin/scheduler/getTaskQueryParam`（查询下拉参数：状态/类型/来源枚举）
  - [x] 22.7 代理接口统一使用 `PlatformProperties.baseUrl`，超时复用已有 RestTemplate 配置
  - [x] 22.8 代理接口失败时返回 503，不影响诊断主链路
  - _Requirements: REQ-10_

- [x] 23. 实现地图代理接口
  - [x] 23.1 `POST /api/v1/map/nodeList` → 代理 loong-platform 地图节点列表接口（`map.html` 使用）
  - [x] 23.2 `POST /api/v1/map/viewDetail` → 代理 loong-platform 地图视图详情接口（`map.html` 使用）
  - [x] 23.3 地图接口 baseUrl 可单独配置（`loong.platform.map-base-url`，默认与 platform baseUrl 相同）
  - _Requirements: REQ-10_

- [x] 24. 补充规则管理后端接口
  - [x] 24.1 `GET /api/v1/rules` → 返回当前所有规则列表（名称/优先级/启用状态/命中次数/平均耗时/最后命中时间）
  - [x] 24.2 `GET /api/v1/rules/stats` → 返回规则引擎统计（活跃规则数/总诊断次数/平均耗时/最近命中规则）
  - [x] 24.3 `PUT /api/v1/rules/{name}/priority` → 更新规则优先级（内存生效，不持久化）
  - [x] 24.4 `PUT /api/v1/rules/{name}/enabled` → 启用/禁用规则（内存生效）
  - [x] 24.5 `POST /api/v1/rules/reload` → 重新从 YAML 加载规则配置
  - [x] 24.6 `POST /api/v1/rules/reset-stats` → 重置命中统计计数器
  - [x] 24.7 在 `ConfigurableRuleEngine` 中暴露统计数据（命中次数、平均耗时、最后命中时间）
  - _Requirements: REQ-11_

- [x] 25. 补充 Agent 对话后端接口（chat.html 使用）
  - [x] 25.1 `POST /api/v2/diagnosis/chat` → SSE 流式对话接口（接收 `{sessionId, message}`，返回 `text/event-stream`）
  - [x] 25.2 SSE 事件类型：`thinking`（思考过程）、`tool_call`（工具调用）、`tool_result`（工具结果）、`text`（最终回复）、`error`、`done`
  - [x] 25.3 Agent 内置工具：`diagnose_task(taskNo)` 调用 DiagnosisFacade、`query_task_detail(taskNo)` 调用 PlatformClient
  - [x] 25.4 `GET /api/v2/diagnosis/chat/models` → 返回可用模型列表（从配置读取）
  - [x] 25.5 `POST /api/v2/diagnosis/chat/models/switch` → 切换当前使用的模型
  - [x] 25.6 `GET /api/v2/diagnosis/chat/env` → 返回当前环境信息（环境名/模型名）
  - _Requirements: REQ-12_

---

## M7: 前端联调与收尾

- [ ] 26. 日志与 traceId 完善
  - [~] 26.1 添加 `DIAG_START`、`PLATFORM_CALL`、`RULE_MATCHED`、`DIAG_END` 日志
  - [~] 26.2 MDC traceId 贯穿所有日志
  - _Requirements: NFR-2_

- [ ] 27. 异常处理完善
  - [~] 27.1 `GlobalExceptionHandler` 统一处理 `AiDiagnosisException`
  - [~] 27.2 参数校验错误返回 400
  - [~] 27.3 任务不存在返回 404
  - [~] 27.4 平台不可达返回 503
  - _Requirements: NFR-4_

- [ ] 28. 前端联调
  - [x] 28.1 `index.html`：确认调用 `/api/v1/diagnosis/analyze`，展示 rule/llm/fallback 三种路径结果
  - [~] 28.2 `rules.html`：联调规则列表、统计、优先级编辑、启用/禁用、重载、重置统计
  - [~] 28.3 `chat.html`：联调 SSE 流式对话、模型切换、文档导入（`/api/v2/documents/*` 保留原有逻辑）
  - [x] 28.4 `map.html`：联调地图节点列表和视图详情代理接口
  - [x] 28.5 新增 `task.html`：任务查询页面，调用 `/api/v1/platform/task/page` 分页查询，点击任务号展示大任务详情+子任务+指令树
  - _Requirements: REQ-1, REQ-10, REQ-11, REQ-12_

- [ ] 29. 文档与 Swagger
  - [~] 29.1 确保 Swagger UI 能访问 `/swagger-ui.html`
  - [~] 29.2 DiagnoseRequest/Response 的 OpenAPI 注解完善
  - [~] 29.3 PlatformProxyController 补充 OpenAPI 注解
  - [~] 29.4 更新 README，说明如何启动和配置
  - _Requirements: REQ-1, REQ-8_
