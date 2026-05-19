# loong-platform 业务理解与诊断工具设计分析

> 这份文档是写落地设计之前的"思考过程"输出。
> 目的：确保对 loong-platform 的业务模型、任务生命周期、数据获取方式理解正确，再动手设计诊断工具。

---

## 1. loong-platform 是什么

一个**智能物流设备控制系统**（WCS），负责：

- 接收上游系统（WMS/MES）下发的仓储任务
- 将大任务拆分为子任务（按设备类型/路径段）
- 将子任务分配给具体设备
- 生成设备指令（Command）下发给 PLC/机器人
- 收集设备反馈，驱动状态流转直到任务完成

技术栈：Spring Boot 3.5.6 + Java 21 + MyBatis-Flex + Quartz + Actor 模型（DCS 层）

---

## 2. 核心实体关系

```
sc_task（大任务）
  │
  │ 1:N（引擎拆分后生成）
  ▼
sc_task_item（子任务）
  │
  │ 1:N（设备调度时生成）
  ▼
sc_command（指令）
  │
  │ 下发给物理设备
  ▼
PLC / 机器人 / 输送线
```

另外还有：
- `sc_task_group_info`：任务组元信息（拆垛/码垛场景，多个大任务协同）
- `sc_device_lock`：设备互斥锁
- `ActionRelation`：子任务之间的执行依赖关系（DAG）

---

## 3. 任务生命周期（状态机）

### 3.1 大任务 task_state

```
WAIT_SPLIT → WAIT_PLAN → RUNNING → SUCCESS
                                  → MANUAL_SUCCESS
                                  → CANCEL
```

| 状态 | 含义 | 触发条件 |
|---|---|---|
| `WAIT_SPLIT` | 等待引擎路径规划 | addTask 后立即设置 |
| `WAIT_PLAN` | 已拆分子任务，等待设备分配 | fillTaskSegmentList 成功后 |
| `RUNNING` | 有子任务开始执行 | 第一个子任务进入 RUNNING |
| `SUCCESS` | 所有子任务完成 | 最后一个子任务完成 |
| `CANCEL` | 被取消 | 人工或系统取消 |

### 3.2 子任务 task_item_state

```
WAIT_SPLIT → WAIT_PLAN → RUNNING → SUCCESS
                                  → CANCEL
                                  → MANUAL_SUCCESS
```

注意：子任务还有 `paused` 字段（YES/NO），暂停是独立于状态的标记。

### 3.3 指令 command_state

```
WAIT → SENT → ACKED → SUCCESS
                    → FAILED
                    → TIMEOUT
```

### 3.4 关键发现：没有 handleState

旧的 loong-ai-diagnosis 代码里大量使用 `handleState`（INIT / KEY_PLANNING / PATH_PLANNING），这是**旧版本的概念**。

新版 loong-platform 里：
- 没有 `handleState` 字段
- 用 `task_state = WAIT_SPLIT` 对应旧的 INIT
- 用 `task_state = WAIT_PLAN` 对应旧的 KEY_PLANNING + PATH_PLANNING 完成后
- 引擎规划是一步完成的（`fillTaskSegmentList` 调引擎 → 拿到 segmentList → 拆子任务 → 状态变 WAIT_PLAN）

**这意味着旧诊断规则里的 `handle-state-init`、`handle-state-key-planning` 等规则需要重新映射到新状态。**

---

## 4. 任务卡住的典型场景（诊断工具要分析的）

基于对代码的理解，任务可能卡在以下阶段：

### 4.1 卡在 WAIT_SPLIT（引擎规划失败）

**现象**：大任务创建后长时间停在 WAIT_SPLIT
**可能原因**：
- 引擎服务不可用或超时
- 起终点之间无可达路径
- 引擎返回空结果
- 任务组场景下等待整体拆分（`GroupTaskSplitJob` 定时触发）
- 虚拟线程异步调用失败，无补偿

**诊断数据需求**：
- 大任务的 `task_state`、`create_time`（判断等待时长）
- 是否有 `group_code`（任务组需要等 GroupTaskSplitJob）
- 引擎调用日志（如果有）

### 4.2 卡在 WAIT_PLAN（设备分配未触发）

**现象**：子任务已生成但全部停在 WAIT_PLAN
**可能原因**：
- `TaskItemAssignJob` 定时任务未运行或暂停
- 引擎返回无可用设备
- ActionRelation 依赖未满足（前序子任务未完成）
- 设备全部被锁定

**诊断数据需求**：
- 子任务列表及状态
- ActionRelation 依赖关系
- 设备锁状态

### 4.3 卡在 RUNNING（子任务执行中但不推进）

**现象**：子任务 RUNNING 但长时间无进展
**可能原因**：
- 指令下发失败（Command 停在 WAIT 或 SENT）
- 设备故障（Actor 进入 FAULT 状态）
- PLC 未响应（Command 有 plcTaskNo 但无完成反馈）
- 点位冲突（设备无法移动）
- 设备暂停（paused=ON）
- 执行超时

**诊断数据需求**：
- 当前 RUNNING 的子任务
- 该子任务下的 Command 列表及状态
- Command 的 `start_time`（判断超时）
- 设备锁信息
- 设备暂停状态

### 4.4 子任务全部完成但大任务未完成

**现象**：所有子任务 SUCCESS 但大任务仍 RUNNING/WAIT_PLAN
**可能原因**：
- 状态回写逻辑异常
- 有系统添加的子任务（SYS_ADD）未完成
- ActionRelation 中还有未执行的节点

**诊断数据需求**：
- 所有子任务状态
- 是否有 parent_task_no 指向的系统添加任务
- ActionRelation 剩余未完成数

### 4.5 任务组未拆分

**现象**：任务组下的任务全部停在 WAIT_SPLIT
**可能原因**：
- `GroupTaskSplitJob` 未运行
- 主/辅任务数量未达标（`mainSize`/`subSize`）
- 引擎组拆分失败

**诊断数据需求**：
- `sc_task_group_info` 的 `split_finish` 状态
- 组内任务数量 vs 配置的 `mainSize`/`subSize`

---

## 5. loong-platform 对外暴露的全部 HTTP 接口

诊断工具通过 HTTP 调用 loong-platform 获取数据。以下是完整的接口清单：

### 5.1 TaskController（`/task`）— 核心业务接口

| 接口 | 方法 | 用途 | 诊断可用 |
|---|---|---|---|
| `/task/queryTaskByTaskNo?taskNo=xxx` | POST | 查询大任务（返回 TaskDTO，原始 code） | ✅ 核心 |
| `/task/queryTaskItemByTaskNo?taskNo=xxx` | POST | 查询大任务下所有子任务列表 | ✅ 核心 |
| `/task/addTask` | POST | 创建任务 | ❌ |
| `/task/pauseTask?taskNo=xxx` | POST | 暂停任务 | ❌ |
| `/task/resumeTask?taskNo=xxx` | POST | 恢复任务 | ❌ |
| `/task/changeTaskEndNode` | POST | 修改终点 | ❌ |
| `/task/addTaskGroup` | POST | 创建任务组 | ❌ |
| `/task/updateTask` | POST | 更新任务 | ❌ |
| `/task/bizUpdateTask` | POST | 业务更新任务 | ❌ |
| `/task/bizConfirmTask` | POST | 业务确认 | ❌ |
| `/task/executeGraphByTaskItemNo?taskItemNo=xxx` | POST | 执行 Graph 子任务 | ❌ |
| `/task/executeActionByTaskItemNo?taskItemNo=xxx` | POST | 执行 Action 子任务 | ❌ |
| `/task/clearAllDevice` | POST | 清理设备内存 | ❌ |
| `/task/refreshAllDevice` | POST | 重新初始化设备 | ❌ |

### 5.2 EngineDataController（`/task`）— 引擎数据查询

| 接口 | 方法 | 用途 | 诊断可用 |
|---|---|---|---|
| `/task/queryTaskDataByTaskNo?taskNo=xxx` | POST | 查询引擎任务信息（EngineTaskDTO，含子任务列表） | ✅ 有用 |
| `/task/queryAllActivateTask` | POST | 查询所有活动任务 | ⚠️ 全局分析用 |
| `/task/queryActivateTaskAndAllDevice` | POST | 活跃任务 + 设备状态 | ⚠️ 全局分析用 |
| `/task/queryBatchCommand?commandBatchNo=xxx` | POST | 查询指令批次执行记录 | ✅ 有用 |

### 5.3 EngineController（`/task`）— 引擎操作

| 接口 | 方法 | 用途 | 诊断可用 |
|---|---|---|---|
| `/task/splitTaskSegmentByTaskNo?taskNo=xxx` | POST | 按任务号拆分 | ❌ 写操作 |
| `/task/getDevicePlanTask` | POST | 引擎设备任务分配 | ⚠️ 可用于分析设备分配 |
| `/task/getDevicePlanTaskByDeviceCode?deviceCode=xxx` | POST | 按设备查分配 | ⚠️ |
| `/task/getDevicePlanTaskByTaskItemNo?taskItemNo=xxx` | POST | 按子任务查分配 | ⚠️ |

### 5.4 SchedulerAdminController（`/api/admin/scheduler`）— 管理后台

| 接口 | 方法 | 用途 | 诊断可用 |
|---|---|---|---|
| `/api/admin/scheduler/task/pageTasks` | POST | 任务分页查询 | ⚠️ |
| `/api/admin/scheduler/task/detail/getTaskDetail?taskNo=xxx` | GET | 大任务详情（含任务组、关联任务，**枚举已翻译**） | ✅ 补充 |
| `/api/admin/scheduler/item/getTaskItemDetails?taskNo=xxx` | GET | 子任务 + Command + ActionRelation（**枚举已翻译**） | ✅ 核心 |
| `/api/admin/scheduler/item/getTaskItemByNo?taskItemNo=xxx` | GET | 单个子任务详情 | ✅ |
| `/api/admin/scheduler/item/getCommand?id=xxx` | GET | 单个指令详情 | ✅ |
| `/api/admin/scheduler/task/getTasksByGroupCode?groupCode=xxx` | GET | 任务组下所有任务 | ✅ |
| `/api/admin/scheduler/task/queryDeviceByCodeOrName` | GET | 查设备 | ⚠️ |
| `/api/admin/scheduler/getTaskQueryParam` | GET | 查询下拉参数（枚举列表） | ❌ |

### 5.5 DcsQueryController（`/dcs`）— 设备查询

| 接口 | 方法 | 用途 | 诊断可用 |
|---|---|---|---|
| `/dcs/queryDeviceByDeviceCode?deviceCode=xxx` | POST | 查设备详情（DcsDeviceDTO） | ✅ 有用 |
| `/dcs/queryAllDevice` | POST | 查所有设备 | ⚠️ |

### 5.6 MonitorController（`/aiops`）— 地图监控

| 接口 | 方法 | 用途 | 诊断可用 |
|---|---|---|---|
| `/aiops/queryDeviceStatusInfoList` | POST | 设备状态列表 | ⚠️ |
| `/aiops/queryDeviceStatusInfoByDeviceCode?deviceCode=xxx` | POST | 设备状态详情 | ✅ 有用 |
| `/aiops/queryActivateTaskInfoList` | POST | 活跃任务列表 | ⚠️ |

### 5.7 CommandAdminController（`/admin/dcs`）— 指令管理

| 接口 | 方法 | 用途 | 诊断可用 |
|---|---|---|---|
| `/admin/dcs/issueDeviceCommand` | POST | 下发指令 | ❌ 写操作 |
| `/admin/dcs/debugDeviceCommand` | POST | 调试指令 | ❌ |
| `/admin/dcs/processCommandAck` | POST | 下发 ACK | ❌ |
| `/admin/dcs/getColletData` | POST | 获取采集数据 | ❌ |

### 5.8 诊断工具的数据获取策略（结论）

**核心调用链（一次诊断需要调的接口）**：

```
1. POST /task/queryTaskByTaskNo?taskNo=xxx
   → 拿到大任务原始数据（TaskDTO，含 task_state、group_code、start_node、end_node 等）

2. GET /api/admin/scheduler/item/getTaskItemDetails?taskNo=xxx
   → 拿到子任务列表 + Command 列表 + ActionRelation 依赖关系
   → 注意：枚举已翻译为中文，需要做反向映射或直接用中文匹配

3. （可选）如果有 group_code：
   GET /api/admin/scheduler/task/getTasksByGroupCode?groupCode=xxx
   → 拿到任务组内所有任务状态

4. （可选）如果需要设备状态：
   POST /dcs/queryDeviceByDeviceCode?deviceCode=xxx
   → 拿到设备详情
```

**关键发现**：
- `getTaskItemDetails` 是最有价值的接口，一次返回子任务 + Command + 依赖关系
- 但它做了枚举翻译（task_state 返回"运行中"而不是"RUNNING"）
- 解决方案：诊断工具内部维护一份枚举映射表（中文→code），或者优先用 `/task/queryTaskByTaskNo` + `/task/queryTaskItemByTaskNo` 获取原始 code 数据，再用 `getTaskItemDetails` 补充 Command 和 ActionRelation

**不需要 loong-platform 新增接口**，现有接口已经够用。

---

## 6. loong-ai-diagnosis 现有设计的定位

### 6.1 它应该是什么

一个**无状态的诊断工具**：
- 输入：taskNo（或 groupCode）
- 过程：调 loong-platform HTTP 接口获取任务全貌 → 规则分析 → 必要时 LLM 兜底
- 输出：结构化诊断结论（卡在哪、为什么、建议怎么做）
- 不落库、不记录、用完即走

### 6.2 现有代码中可复用的部分

| 组件 | 位置 | 复用价值 |
|---|---|---|
| `HttpTaskClient` | `client/` | HTTP 调用 loong-platform 的基础能力 |
| `ConfigurableRuleEngine` + 13 个 Bean 规则 | `rule/` | 规则匹配框架（优先级、统计、热加载） |
| `LlmDiagnosisEngine` | `service/` | LLM 调用 + Prompt 工程 |
| `DiagnosisResponseMapper` | `service/` | LLM 输出 JSON 解析 |
| `DiagnosisContextAssembler` | `service/` | 数据拼装逻辑（需要改造适配新接口） |
| `SystemPromptProvider` | `service/` | Prompt 管理 |
| `DocSearchService` / `HybridSearchService` | `service/` | 知识库检索（可选） |

### 6.3 现有代码中需要推翻的部分

| 组件 | 原因 |
|---|---|
| `platform/` 整个子包 | 设计方向错误：试图做"平台诊断中台"，引入了大量不需要的概念（场景分发、能力匹配、YAML 规则、多 Controller） |
| `DiagnosisRequest` / `DiagnosisResponse`（platform 包） | 过度设计，诊断工具不需要这么复杂的入参 |
| `DiagnosisOrchestrator` | 硬编码场景分支 + YAML 规则重复 |
| `ScenarioDiagnosisService` / `DiagnosisService`（platform） | 两套并存的入口 |
| 旧 `DiagnosisContext`（rule 包） | 字段基于旧版 handleState 概念，需要重新设计 |

### 6.4 13 条旧规则的映射

旧规则基于旧版概念（handleState、taskItems、tickets），需要映射到新版：

| 旧规则 | 旧概念 | 新版对应 |
|---|---|---|
| `HandleStateInitRule` | handleState=INIT | task_state=WAIT_SPLIT |
| `HandleStateKeyPlanningRule` | handleState=KEY_PLANNING | task_state=WAIT_SPLIT（引擎调用中） |
| `HandleStateUnknownRule` | handleState 未知 | 不再需要 |
| `NoTaskItemsRule` | 无子任务 | task_state=WAIT_PLAN 但 taskItem 为空 |
| `AllCompletedWaitingWmsRule` | 全部完成等 WMS | 所有子任务 SUCCESS + 大任务仍 RUNNING |
| `AllCompletedStateAbnormalRule` | 全完成但状态异常 | 同上 |
| `AllItemsInitRule` | 子任务全 INIT | 所有子任务 WAIT_PLAN |
| `PointConflictRule` | 点位冲突 | 需要查设备锁/点位占用 |
| `TaskItemCancelledRule` | 子任务被取消 | task_item_state=CANCEL |
| `TicketTimeoutRule` | 执行单超时 | Command SENT/ACKED 超时 |
| `PlcCommunicationRule` | PLC 未响应 | Command SENT 但无 plcTaskNo |
| `ErrorMessageRule` | 错误信息 | 任务/子任务/指令中的错误字段 |
| `FallbackRule` | 兜底 | 保留 |

---

## 7. 诊断工具的数据获取策略

### 7.1 一次诊断需要的数据

给定一个 `taskNo`，诊断工具需要：

1. **大任务信息**：task_state、create_time、start_time、group_code、paused、start_node、end_node、required_function_list
2. **子任务列表**：task_item_state、device_code、start_time、finish_time、paused、pre_task_item_no
3. **指令列表**：command_state、device_code、plc_task_no、start_time、finish_time、retry_count
4. **子任务依赖关系**：ActionRelation（谁依赖谁）
5. **任务组信息**（如果有 group_code）：split_finish、mainSize、subSize、组内其他任务状态

### 7.2 调用方案（确定）

调用 3 个接口即可覆盖所有诊断数据：

```
步骤 1: POST /task/queryTaskByTaskNo?taskNo=xxx
  → TaskDTO（原始 code，含 task_state、group_code、planSegmentList 等）

步骤 2: GET /api/admin/scheduler/item/getTaskItemDetails?taskNo=xxx
  → RootTaskItem（子任务列表 + Command 列表 + ActionRelation）
  → 注意枚举已翻译，需要反向映射

步骤 3（可选）: GET /api/admin/scheduler/task/getTasksByGroupCode?groupCode=xxx
  → 任务组内所有任务（仅当 group_code 非空时调用）
```

不需要 loong-platform 新增接口。

---

## 8. 诊断分析流程设计思路

```
用户输入 taskNo
    │
    ▼
[数据获取] 调 loong-platform 接口，拿到任务全貌
    │
    ▼
[上下文构建] 组装 DiagnosisContext（大任务 + 子任务 + 指令 + 依赖关系）
    │
    ▼
[规则引擎] 按优先级逐条匹配规则
    │
    ├── 命中 → 直接返回结论（confidence=0.95）
    │
    └── 未命中 → [LLM 兜底]
                    │
                    ▼
              组装 Prompt（系统角色 + 业务背景 + 决策树 + 任务数据）
                    │
                    ▼
              调用大模型，解析 JSON 输出
                    │
                    ▼
              返回结论（confidence=0.7）
```

### 8.1 规则引擎的设计原则

- 规则按"任务卡在哪个阶段"分组
- 每条规则有明确的匹配条件和结论
- 规则配置在独立 YAML 文件中，不堆在 application.yml
- 保留现有 `ConfigurableRuleEngine` 的框架（优先级、统计、热加载）
- 规则的 `match` 条件基于新版状态枚举

### 8.2 LLM 兜底的设计原则

- Prompt 包含：WCS 业务背景 + 诊断决策树 + 当前任务数据
- 输出必须是结构化 JSON
- 超时 3s 降级
- 可通过配置开关关闭

---

## 9. 关于"单任务 vs 任务组"

从 loong-platform 的设计看：

- **单任务**：一个 `taskNo`，拆分为多个子任务，子任务按依赖关系执行
- **任务组**：一个 `groupCode` 下有多个大任务（MAIN + SUB + SYS_ADD），它们之间有协同关系

诊断工具的入口应该是：
- 输入 `taskNo` → 诊断这个大任务为什么卡住
- 如果这个任务属于某个任务组，自动关联分析组内其他任务的状态

不需要单独的"任务组诊断"入口，因为用户关心的始终是"某个具体任务为什么不动了"，任务组只是分析过程中的上下文。

---

## 10. 配置文件拆分方案

```
src/main/resources/
├── application.yml              # Spring 核心配置（port、datasource、ai 模型参数、platform 地址）
├── diagnosis-rules.yml          # 诊断规则配置（按场景分组）
└── prompts/                     # LLM Prompt 模板
    ├── system-role.txt          # 系统角色设定
    ├── business-context.txt     # WCS 业务背景
    └── decision-tree.txt        # 诊断决策树
```

`diagnosis-rules.yml` 通过 `spring.config.import` 引入，规则按"任务卡在哪个阶段"分组。

---

## 11. 待确认的问题

| # | 问题 | 影响 | 默认方案 |
|---|---|---|---|
| 1 | loong-platform 是否有查 Command 的 HTTP 接口？ | 决定能否分析到指令层面 | 先只分析到子任务层面，Command 层后续补 |
| 2 | `/task/queryTaskByTaskNo` 返回的 TaskDTO 是否包含 `planSegmentList`、`engineSplitResult`？ | 决定能否分析引擎规划结果 | 先不依赖这些字段 |
| 3 | loong-platform 的 base URL 是什么？是否有认证？ | 配置问题 | 配置在 application.yml 的 `platform.api.base-url` |
| 4 | 诊断工具是否需要支持流式返回（SSE）？ | 前端体验 | MVP 不做，同步返回 |
| 5 | 现有前端（Tauri 桌面应用）的诊断入口长什么样？ | 决定返回格式 | 保持现有 `DiagnoseResponse` 格式兼容 |

---

## 12. 下一步

这份文档确认无误后，我会基于以上理解写一份**精简的落地设计文档**，内容包括：

1. 诊断工具的接口定义（输入 taskNo，输出结论）
2. 数据获取层设计（调 loong-platform 哪些接口、怎么组装）
3. DiagnosisContext 新设计（基于新版状态枚举）
4. 规则配置 YAML 格式（基于新版业务场景）
5. LLM Prompt 设计（基于新版决策树）
6. 代码改造清单（保留什么、删什么、改什么）
