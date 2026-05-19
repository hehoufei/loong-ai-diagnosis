# loong-ai-diagnosis 落地设计 V2

> 版本：2026-05-09
> 目标读者：后端开发、平台对接方、测试
> 本文不是"概念设计"，是给开发拿去直接写代码的文档。

---

## 0. 文档定位

这份文档要回答的只有三个问题：

1. 现在代码是什么状态？为什么现有两份设计文档落不了地？
2. 要改成什么样？每一层的代码边界、数据模型、接口契约是什么？
3. 从当前状态走到目标状态，每一步具体做什么、怎么验收？

不讨论团队分工，不讨论"AI 的价值"，不再重复"规则优先模型兜底"这种口号。

---

## 1. 当前代码实际状态（必须先看清楚）

### 1.1 两条并行的诊断链路

项目里现在有 **两套完全独立** 的诊断实现，这是落地最大障碍：

| 维度 | 旧链路 | 新链路 |
|---|---|---|
| 包 | `cn.aimstek.loong.aidiag.{service,rule,controller}` | `cn.aimstek.loong.aidiag.platform.*` |
| 入口 Controller | `DiagnosisController#diagnose` → `/api/v1/diagnosis/task` | `platform/controller/*` → `/api/v1/diagnosis/{analyze,task/*,task-group/*,capability/*}` |
| 请求 DTO | `DiagnoseRequest(taskId, scope)` | `DiagnosisRequest(scene, taskSnapshot, taskEvent, ...)` |
| 编排入口 | `DiagnosisFacade` | `ScenarioDiagnosisService` / `DiagnosisService(platformDiagnosisService)` |
| 上下文 | `rule.DiagnosisContext`（通过 `DiagnosisContextAssembler` 查库拼装） | `platform.model.DiagnosisContext`（由调用方传入快照） |
| 规则引擎 | `ConfigurableRuleEngine` + 13 个 `DiagnoseRule` Bean + 持久化统计 | `RuleMatcher` + YAML 规则配置 |
| LLM 兜底 | `LlmDiagnosisEngine`（有 Prompt 工程） | `DiagnosisOrchestrator` 里硬编码 7 个场景分支 |
| 诊断数据获取 | 主动：`TaskClient/LogClient` 查 DB | 被动：调用方推快照 |

### 1.2 已经暴露的问题

- 两个 `DiagnosisService` 类，一个在 `cn.aimstek.loong.aidiag.service`（`@Primary`）一个在 `platform.service`（`@Service("platformDiagnosisService")`），Bean 名称只能靠手动限定
- 两个 `DiagnosisController`，路径前缀都是 `/api/v1/diagnosis`，只是子路径不同
- `DiagnosisOrchestrator` 里的场景分支方法（`buildPreCheckResult` 等）和 `diagnosis-rules.yml` 里的规则内容重复
- `DiagnosisMapper` 里 `diagnosisMode = needHumanConfirm ? "rule" : "config"` 语义错误
- 多处 `Map.of("scene", context.getScene())` 在 scene 为 null 时会抛 NPE
- `TaskPreCheckService`、`TaskGroupAnalysisService` 等类是空壳，直接转发给 `DiagnosisService`，没有业务

### 1.3 当前能用的资产

不能推翻重来，下面这些要复用：

- `ConfigurableRuleEngine` 规则注册、优先级、统计快照机制
- `LlmDiagnosisEngine` + `DiagnosisResponseMapper` 的 Prompt → 结构化 JSON 解析能力
- `platform/model/{TaskSnapshot, TaskEvent, DiagnosisContext, RootCause, Suggestion}` 域模型
- `DiagnosisRuleProperties` / `DiagnosisCapabilitiesProperties` 的 YAML 绑定
- 能力字典 YAML 中已经定义的 9 条 `functionType`
- `HttpTaskClient` / `JdbcTaskClient` 的数据接入能力

---

## 2. 目标架构

### 2.1 一句话

**单入口、场景分发、规则先行、模型兜底、建议契约化。**

### 2.2 最终分层

```
┌────────────────────────────────────────────────────────┐
│  接入层  RestController                                │
│   - /api/v1/diagnosis/analyze     （统一入口）         │
│   - /api/v1/diagnosis/scenes/{scene} （场景入口语法糖） │
│   - /api/v1/diagnosis/feedback                         │
└───────────────────────┬────────────────────────────────┘
                        │ DiagnosisRequest (scene)
┌───────────────────────▼────────────────────────────────┐
│  编排层  DiagnosisFacade（统一编排，替代现有两个）      │
│   1. 参数校验 + scene 归一化                           │
│   2. 平台数据补全（快照不全时查平台）                  │
│   3. 规则引擎（YAML + Bean 两路合并）                  │
│   4. 命中 → 直接返回；未命中 → LLM 兜底                │
│   5. 建议合并、证据链组装、落库                        │
└───────────────────────┬────────────────────────────────┘
                        │
    ┌───────────────────┼────────────────────┐
    ▼                   ▼                    ▼
┌──────────┐     ┌──────────────┐    ┌──────────────┐
│规则引擎  │     │能力匹配       │    │LLM 兜底      │
│RuleEngine│     │Capability    │    │LlmEngine     │
└──────────┘     └──────────────┘    └──────────────┘
    │                   │                    │
    └───────────────────┼────────────────────┘
                        ▼
              ┌──────────────────┐
              │平台适配 & 数据层   │
              │ PlatformClient   │
              │ SnapshotLoader   │
              │ DiagnosisRepo    │
              └──────────────────┘
```

### 2.3 包结构（在现有基础上演进，不新建顶层包）

```
cn.aimstek.loong.aidiag
├── api                   # 对外契约：@Deprecated 合并到 platform/api
├── platform
│   ├── api               # 保留 DiagnosisApi 作为对外稳定契约
│   ├── controller        # 收敛到 DiagnosisController 一个 + 子路径
│   ├── dto               # 请求响应 DTO（对外）
│   ├── model             # 领域对象（内部）
│   ├── config            # *Properties
│   ├── adapter           # 平台输入 Map→DTO 转换
│   ├── service
│   │   ├── DiagnosisFacade          # 新增：统一编排入口
│   │   ├── RuleEngineBridge         # 新增：桥接两套规则
│   │   ├── LlmFallbackEngine        # 新增：基于现有 LlmDiagnosisEngine
│   │   ├── SnapshotLoader           # 新增：快照不全时回源
│   │   └── DiagnosisRepository      # 新增：落库
│   └── scene             # 新增：各场景的规则和后处理
│       ├── SceneHandler（接口）
│       ├── TaskPreCheckHandler
│       ├── TaskPauseHandler
│       ├── ChangeEndNodeHandler
│       ├── BizDecisionHandler
│       └── ...
└── rule                  # 保留：旧规则 Bean 改造后复用
```

旧的 `cn.aimstek.loong.aidiag.service.DiagnosisService` / `DiagnosisFacade` 整包标记 `@Deprecated`，逐步迁移调用方后删除。

---

## 3. 核心数据契约

### 3.1 DiagnosisRequest（平台 → AI，统一入口）

```json
{
  "requestId": "uuid, 幂等键, 非空",
  "scene": "TASK_PRE_CHECK | TASK_PAUSE_ANALYZE | CHANGE_END_NODE | BIZ_DECISION | TASK_GROUP_ANALYZE | CAPABILITY_MATCH | TASK_EXCEPTION",
  "tenantId": "string, 可空",
  "bizType": "string, 可空",
  "taskNo": "string, scene 涉及单任务时非空",
  "taskGroupCode": "string, scene 涉及任务组时非空",
  "currentStatus": "string, 任务当前状态",
  "taskSnapshot": { /* 见 3.3 */ },
  "taskEvent": { /* 见 3.4, 异常诊断场景非空 */ },
  "context": { "key": "value" },
  "options": {
    "needRootCause": true,
    "needRecommendAction": true,
    "needBizExplain": true,
    "snapshotSource": "REQUEST | PLATFORM"
  }
}
```

**关键规则**：
- `requestId` 作为幂等键，AI 侧必须校验，重复请求直接返回上一次结果
- `scene` 是路由依据，必须枚举（见 3.2），未知 scene 走通用路径
- `options.snapshotSource=PLATFORM` 时，AI 自己通过 `taskNo` 回查平台补全，避免大包传参
- **禁止** 在 DTO 里使用 `Map<String, Object>` 作为核心字段（`context` 例外），所有业务字段必须显式声明

### 3.2 Scene 枚举表

| scene | 触发时机 | 必填字段 | 返回重点 |
|---|---|---|---|
| `TASK_PRE_CHECK` | 平台收到 `/task/addTask` 请求前 | `taskSnapshot` | 字段完整性、能力匹配、起终点合法性 |
| `TASK_GROUP_ANALYZE` | 平台收到 `/task/addTaskGroup` 前 | `taskSnapshot`（含主子任务） | 主子依赖合理性、独立运行冲突 |
| `CHANGE_END_NODE` | 平台触发 `/task/changeTaskEndNode` 前 | `taskNo` + `taskSnapshot`（含候选终点） | 候选终点排序、能力匹配 |
| `BIZ_DECISION` | 任务进入 `WAIT_BIZ_DECISION` | `taskNo` + `currentStatus` | KEEP/CHANGE/CANCEL + 解释 |
| `TASK_PAUSE_ANALYZE` | `pauseTask` 后人工查看 | `taskNo` + `taskEvent` | 暂停原因、恢复建议 |
| `TASK_EXCEPTION` | 任务卡住、超时、PLC 无响应 | `taskNo`（快照可由 AI 回查） | 根因 + 建议动作 |
| `CAPABILITY_MATCH` | 任意 | `taskSnapshot.requiredFunctionList` | 能力匹配度、缺失能力 |

### 3.3 TaskSnapshot（平台任务的最小必要快照）

```java
public class TaskSnapshot {
    // 基础
    private String taskNo;
    private String taskSource;      // WMS / MANUAL / UPSTREAM
    private String taskType;        // N2S / S2N / N2N / S2S / ADD_CONTAINER ...
    private String bizType;
    private String bizPriority;

    // 路径
    private String startNode;
    private String endNode;
    private List<String> candidateEndNodes;   // 改终点场景用

    // 能力
    private List<FunctionRequirement> requiredFunctionList;

    // 载荷
    private List<ContainerInfo> containerList;
    private List<GoodsInfo> goodsInfoList;

    // 运行期
    private String handleState;     // INIT / KEY_PLANNING / PATH_PLANNING
    private String taskState;
    private String currentNode;
    private String currentDevice;
    private String errorCode;
    private String errorMessage;

    // 任务组
    private String taskGroupCode;
    private String taskGroupType;
    private List<TaskSnapshot> mainTasks;
    private List<TaskSnapshot> subTasks;

    // 扩展
    private Map<String, Object> extData;
}
```

统一字段名（解决原接口 `containerInfo`/`containerList` 混用问题）：
- 只保留 `containerList`，后续通过 `PlatformDiagnosisAdapter` 做入参兼容

### 3.4 TaskEvent

```java
public class TaskEvent {
    private String eventId;
    private String eventType;       // 见 EventType 枚举
    private LocalDateTime eventTime;
    private String nodeCode;
    private String deviceCode;
    private String statusBefore;
    private String statusAfter;
    private String errorCode;
    private String errorMessage;
    private Map<String, Object> payload;
}
```

`eventType` 枚举（在代码里定义常量类）：
`TASK_CREATED / TASK_PAUSED / TASK_RESUMED / TASK_CANCELED / END_NODE_CHANGED / BIZ_CONFIRM_REQUIRED / STATE_CHANGED / ERROR / TIMEOUT / PLC_NO_RESPONSE`

### 3.5 DiagnosisResponse（AI → 平台）

```json
{
  "requestId": "回填",
  "diagnosisId": "uuid, 本次诊断唯一标识, 用于反馈",
  "scene": "回填",
  "riskLevel": "LOW | MEDIUM | HIGH",
  "summary": "一句话根因",
  "needHumanConfirm": false,
  "confidence": 0.0,
  "diagnosisMode": "RULE | LLM | HYBRID | FALLBACK",
  "rootCauses": [
    {
      "code": "POINT_CONFLICT",
      "description": "路径点位 P123 被任务 T-456 占用",
      "confidence": 0.9,
      "evidence": ["evt:E-789", "snap:T-456"]
    }
  ],
  "suggestions": [
    {
      "actionCode": "CHANGE_END_NODE",
      "actionDesc": "建议把终点从 P999 改为 P888",
      "priority": 1,
      "payload": { "targetEndNode": "P888", "reason": "..." },
      "platformCallHint": {
        "method": "POST",
        "path": "/task/changeTaskEndNode",
        "bodyTemplate": { "taskNo": "{{taskNo}}", "endNode": "{{payload.targetEndNode}}" }
      }
    }
  ],
  "evidence": [
    { "id": "E-789", "type": "EVENT", "summary": "...", "data": {} }
  ]
}
```

**关键约定**：
- `suggestions[].actionCode` 必须是下面 4 节的枚举
- `platformCallHint` 告诉平台"如果采纳本建议，应该调用哪个接口、怎么拼参数"——这是平台真正能依赖 AI 的关键，原文档里缺失
- `evidence[].id` 可被 `rootCauses[].evidence` 引用，形成证据链

---

## 4. 建议动作（SuggestedAction）契约

这是原文档最大的空白。AI 说"建议改终点"，平台怎么用？必须把每种动作的 `payload` schema 钉死。

| actionCode | 语义 | payload 必填字段 | 平台侧动作 | 幂等性 |
|---|---|---|---|---|
| `KEEP` | 不做任何变更 | - | 无 | 天然幂等 |
| `MANUAL_CONFIRM` | 需要人工业务确认 | `reason`, `confirmOptions` | 弹窗展示给操作员 | 天然幂等 |
| `PAUSE` | 建议暂停任务 | `reason` | `POST /task/pauseTask` | 靠平台 taskNo 保证 |
| `RESUME` | 建议恢复任务 | `reason` | `POST /task/resumeTask` | 同上 |
| `CANCEL` | 建议取消任务 | `reason` | `POST /task/cancelTask` | 同上 |
| `CHANGE_END_NODE` | 改终点 | `targetEndNode`, `reason`, 可选 `alternatives[]` | `POST /task/changeTaskEndNode` | taskNo + targetEndNode 组合 |
| `REPLAN` | 重新规划 | `reason`, 可选 `forceKeyPoints[]` | 平台触发引擎重算 | taskNo + replanId |
| `SPLIT_TASK` | 拆分任务 | `splitStrategy`, `reason` | 平台拆分接口 | 暂不做 |
| `MERGE_TASK` | 合并任务 | `targetTaskNo`, `reason` | 暂不做 | 暂不做 |
| `RELEASE_POINT` | 释放点位 | `pointCodes[]`, `reason` | 平台释放点位接口 | 点位 + taskNo |
| `CHECK_DEVICE` | 提醒检查设备 | `deviceCodes[]`, `checkItems[]` | 工单/告警 | - |
| `CHECK_PLC` | 提醒检查 PLC | `deviceCodes[]` | 工单/告警 | - |
| `LLM_ANALYZE` | AI 侧继续深度分析 | `hints` | AI 内部流转 | - |

**MVP 阶段只实现前 6 个**，其余留协议位。

### 4.1 Suggestion 的执行语义

- AI 绝不调用平台"写"接口
- 平台根据 `suggestions[].actionCode` 决定：自动执行 / 推给人工 / 忽略
- 平台执行后通过 `/api/v1/diagnosis/feedback` 回调 AI，反馈采纳情况
- 反馈结构：`{ diagnosisId, suggestionIndex, adopted, resultCode, message, executedAt }`

---

## 5. 规则引擎设计

### 5.1 两路规则合并

保留现有 `ConfigurableRuleEngine`（Bean 规则）+ `RuleMatcher`（YAML 规则），由 **`RuleEngineBridge`** 统一调用：

```
RuleEngineBridge.evaluate(DiagnosisContext ctx):
  1. 先跑 YAML 规则（基于 scene 快速匹配，如点位冲突、PLC 通信异常）
  2. 未命中 → 跑 Bean 规则（需要复杂逻辑，如 handleState 组合判断）
  3. 有命中 → 用命中规则 build DiagnosisResult
  4. 无命中 → 返回 Optional.empty()
```

### 5.2 YAML 规则 DSL

在现有基础上扩展，支持简单表达式：

```yaml
loong:
  ai:
    diagnosis:
      rules:
        items:
          - ruleCode: POINT_CONFLICT_RULE
            ruleName: 点位冲突告警
            scene: TASK_EXCEPTION         # 或 "*" 表示任意场景
            riskLevel: HIGH
            priority: 10                  # 数字越小越先匹配
            enabled: true
            match:
              # 支持 = != in notIn exists
              - field: "taskEvent.eventType"
                op: "="
                value: "POINT_CONFLICT"
              - field: "taskSnapshot.handleState"
                op: "in"
                value: ["KEY_PLANNING", "PATH_PLANNING"]
            suggestion:
              summary: "任务路径存在点位冲突"
              actionCode: "RELEASE_POINT"
              actionDesc: "释放被占用点位"
              payload:
                pointCodes: "${taskEvent.payload.conflictPoints}"   # 占位符
                reason: "点位冲突"
```

当前 YAML 用的是 `match: {key: value}` 平铺形式，只支持 `=` 匹配。这在 MVP 阶段够用，下一版升级到上面的数组形式。

### 5.3 Bean 规则改造

现有 13 个 `DiagnoseRule` Bean 统一改造：

1. 实现同一个 `SceneAwareRule` 接口，声明适用 scene
2. `DiagnosisContext` 统一使用 `platform.model.DiagnosisContext`（现在用的是 `rule.DiagnosisContext`）
3. `match(ctx)` + `diagnose(ctx)` 返回统一的 `DiagnosisResult`

旧 Bean 规则原有的统计、持久化（`RuleEngineSnapshotService`）保留。

---

## 6. LLM 兜底

### 6.1 触发条件

- 规则未命中
- 规则命中但 `confidence < 0.6`
- `options.forceLlm = true`

### 6.2 Prompt 策略

复用现有 `LlmDiagnosisEngine` + `DiagnosisResponseMapper`，改造两处：

1. Prompt 按 scene 分模板（现在一个 prompt 用所有场景）
2. 输出强制 JSON Schema，解析失败重试 1 次，再失败降级为 FALLBACK 结果

Prompt 模板位置：`src/main/resources/prompts/{scene}.st`（Spring AI 原生支持 StringTemplate）

### 6.3 兜底结果

LLM 也失败时，返回：

```json
{
  "riskLevel": "UNKNOWN",
  "summary": "AI 无法分析此场景，请人工介入",
  "diagnosisMode": "FALLBACK",
  "confidence": 0.0,
  "suggestions": [{"actionCode": "MANUAL_CONFIRM", ...}]
}
```

---

## 7. 数据库设计

### 7.1 MVP 只落 3 张表

其他表（能力字典、规则）全部走 YAML 配置，需要运行时动态配置时再做。

#### `ai_diagnosis_request`

```sql
CREATE TABLE ai_diagnosis_request (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  request_id      VARCHAR(64)  NOT NULL COMMENT '幂等键',
  tenant_id       VARCHAR(32)  DEFAULT NULL,
  scene           VARCHAR(32)  NOT NULL,
  task_no         VARCHAR(64)  DEFAULT NULL,
  task_group_code VARCHAR(64)  DEFAULT NULL,
  current_status  VARCHAR(32)  DEFAULT NULL,
  request_payload JSON         NOT NULL COMMENT '完整 DiagnosisRequest',
  client_ip       VARCHAR(64)  DEFAULT NULL,
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_request_id (request_id),
  KEY idx_task_no (task_no, created_at),
  KEY idx_scene_created (scene, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='诊断请求';
```

#### `ai_diagnosis_result`

```sql
CREATE TABLE ai_diagnosis_result (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  diagnosis_id      VARCHAR(64)  NOT NULL,
  request_id        VARCHAR(64)  NOT NULL,
  tenant_id         VARCHAR(32)  DEFAULT NULL,
  scene             VARCHAR(32)  NOT NULL,
  risk_level        VARCHAR(16)  NOT NULL,
  summary           VARCHAR(1024) DEFAULT NULL,
  diagnosis_mode    VARCHAR(16)  NOT NULL COMMENT 'RULE/LLM/HYBRID/FALLBACK',
  confidence        DECIMAL(3,2) DEFAULT NULL,
  need_human_confirm TINYINT(1)  NOT NULL DEFAULT 0,
  matched_rule_code VARCHAR(64)  DEFAULT NULL,
  result_payload    JSON         NOT NULL COMMENT '完整 DiagnosisResponse',
  elapsed_ms        INT UNSIGNED DEFAULT NULL,
  model_name        VARCHAR(64)  DEFAULT NULL,
  prompt_version    VARCHAR(32)  DEFAULT NULL,
  created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_diagnosis_id (diagnosis_id),
  KEY idx_request_id (request_id),
  KEY idx_risk_scene (risk_level, scene, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='诊断结果';
```

#### `ai_diagnosis_feedback`

```sql
CREATE TABLE ai_diagnosis_feedback (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  diagnosis_id     VARCHAR(64)  NOT NULL,
  suggestion_index INT          NOT NULL DEFAULT 0,
  adopted          TINYINT(1)   NOT NULL COMMENT '0未采纳 1采纳',
  result_code      VARCHAR(32)  DEFAULT NULL,
  message          VARCHAR(512) DEFAULT NULL,
  executed_at      DATETIME     DEFAULT NULL,
  created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_diagnosis_id (diagnosis_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='建议反馈';
```

### 7.2 落库时机

- `DiagnosisFacade` 入口：`INSERT ai_diagnosis_request`（异步，失败不影响主流程）
- `DiagnosisFacade` 出口：`INSERT ai_diagnosis_result`（同步，失败记日志）
- `/feedback` 接口：`INSERT ai_diagnosis_feedback`

---

## 8. 接口清单（MVP）

只给四条，其他场景通过 `/analyze` 的 `scene` 路由。

### 8.1 `POST /api/v1/diagnosis/analyze`

统一入口，所有场景都能走。

**Request**: 见 3.1
**Response**: 见 3.5
**HTTP Code**: 200（业务错误用 `BaseResponse.code`）、400（参数不合法）、503（下游不可用）

### 8.2 `POST /api/v1/diagnosis/scenes/task-pre-check`

语法糖，等价于 `analyze` + `scene=TASK_PRE_CHECK`。

### 8.3 `POST /api/v1/diagnosis/scenes/change-end-node`

同上。

### 8.4 `POST /api/v1/diagnosis/feedback`

```json
{
  "diagnosisId": "...",
  "suggestionIndex": 0,
  "adopted": true,
  "resultCode": "SUCCESS",
  "message": "",
  "executedAt": "2026-05-09T10:00:00"
}
```

### 8.5 废弃

- `POST /api/v1/diagnosis/task`（旧链，跳转到新 analyze 并 Header 打印 `Deprecation: true`）
- `DiagnosisStreamController`（MVP 不做流式）

---

## 9. 现有代码改造清单

按优先级，从高到低：

### P0：阻塞问题，必须先修（1-2 天）

| 改动 | 位置 | 原因 |
|---|---|---|
| `Map.of("scene", context.getScene())` 改 `Collections.singletonMap` 或 null 保护 | `DiagnosisOrchestrator` 各 `buildXxxResult` | scene 为 null 时 NPE |
| 修正 `diagnosisMode` 判断 | `DiagnosisMapper.toResponse` | 语义错误 |
| `DiagnosisOrchestrator` 移除场景硬编码分支，全部走规则引擎 | `DiagnosisOrchestrator.diagnose` | 和 YAML 规则重复 |
| 删除 `emptyResult()` 无用方法 | `DiagnosisMapper` | 死代码 |

### P1：架构收敛（3-5 天）

| 改动 | 做法 |
|---|---|
| 新增 `DiagnosisFacade`（platform 包内） | 统一编排，替代旧 `DiagnosisFacade` 和 `ScenarioDiagnosisService` |
| 新增 `RuleEngineBridge` | 桥接 `ConfigurableRuleEngine` 和 `RuleMatcher` |
| 新增 `SceneHandler` 接口 + 7 个 Handler | 把场景专属逻辑从 `DiagnosisOrchestrator` 拆出来 |
| 旧 `service/DiagnosisService` 改为 `@Deprecated`，内部委托给新 `Facade` | 保持旧接口兼容 |
| 旧 13 个 Bean 规则改造：实现 `SceneAwareRule`，用新 Context | 保留已验证逻辑 |
| 合并两个 `DiagnosisContext` 类 | 保留 `platform.model.DiagnosisContext`，旧 `rule.DiagnosisContext` 重命名为内部转换对象 |

### P2：数据与可观测性（3-5 天）

| 改动 | 做法 |
|---|---|
| 建 3 张表 + `DiagnosisRepository` | 见 7 |
| 入口统一加 traceId（MDC） + 耗时埋点 | 所有日志带 `traceId=xxx requestId=yyy` |
| Micrometer 指标 | `diagnosis_total{scene, mode, result}` / `diagnosis_elapsed_ms` |
| `/feedback` 接口 | 见 8.4 |
| 幂等检查 | `DiagnosisFacade` 开头按 `request_id` 查 `ai_diagnosis_request`，存在则直接返回上次结果 |

### P3：LLM 兜底优化（3 天）

| 改动 | 做法 |
|---|---|
| Prompt 按 scene 拆模板 | `resources/prompts/{scene}.st` |
| 输出 JSON Schema 校验 + 重试 | `DiagnosisResponseMapper` 增强 |
| LLM 超时、降级 | 3s 超时 → FALLBACK 结果 |

---

## 10. 分阶段落地计划

每一阶段都有"可验收"标准，不用时间估（每人节奏不同），只定义终点。

### M1：修血止血（P0 完成）

**验收**：
- `curl` 打各场景不再偶发 NPE
- `diagnosisMode` 字段取值与实际诊断路径一致
- 删除死代码后 `mvn compile` 无 warning

### M2：架构收敛（P1 完成）

**验收**：
- `/api/v1/diagnosis/analyze` 通过 scene 路由到所有 7 种场景
- 旧 `/api/v1/diagnosis/task` 打印 Deprecation 响应头但功能正常
- 两套规则引擎合并后，13 个旧 Bean 规则 + 9 条 YAML 规则都能生效
- 单元测试覆盖每个 scene 至少一个命中 + 一个未命中用例

### M3：数据可观测（P2 完成）

**验收**：
- 每次诊断在 `ai_diagnosis_request` / `ai_diagnosis_result` 有记录
- 同一 `requestId` 第二次调用直接返回缓存结果，`ai_diagnosis_request` 不重复写
- Grafana 看到 `diagnosis_total` / `diagnosis_elapsed_ms` 指标
- `/feedback` 能正确记录采纳情况

### M4：LLM 稳定（P3 完成）

**验收**：
- 规则未命中时走 LLM，解析失败率 < 2%
- LLM 超时 3s 后立即降级，不阻塞请求
- 关闭 LLM 开关（`loong.ai.diagnosis.enable-llm-fallback=false`）时，返回 FALLBACK

### M5：平台联调（写在最后，因为必须前面都完成才能联）

**验收**：
- 平台 4 个场景（pre-check / pause / change-end / biz-decision）接入后，AI 能正确返回结构化建议
- 建议采纳率指标 `> 30%`（验证建议可用）
- 一次完整链路耗时 P95 < 1.5s（规则）/ < 5s（LLM）

---

## 11. 非功能要求

### 11.1 可观测性

每条日志必须带：`traceId`、`requestId`、`scene`、`taskNo`、`ruleCode`（如命中）。
禁止记录：LLM API Key、完整 Prompt（过长且含敏感信息）。

### 11.2 性能

- 规则路径 P95 < 200ms
- LLM 路径 P95 < 3s
- 单机 QPS 目标 50（MVP）

### 11.3 幂等

- 对外：`requestId` 作为幂等键，24h 内重复返回同结果
- 对内：`/feedback` 按 `diagnosisId + suggestionIndex` 幂等

### 11.4 安全

- 所有接口必须有 `tenantId` 校验（MVP 可以先放开，但留字段）
- LLM 输出内容不允许出现调用方 IP、数据库连接串（在 Prompt 阶段过滤）

### 11.5 配置

- 规则配置 `diagnosis-rules.yml` 必须支持热加载（MVP 可以要求重启）
- `enable-llm-fallback` 等开关通过配置中心可动态切换

---

## 12. 平台集成约定（面向 loong-platform）

### 12.1 平台要做的事

1. 在四个关键时机调用 AI：
   - 收到 `/task/addTask` 前 → 调 `POST /api/v1/diagnosis/scenes/task-pre-check`
   - 任务进入 `WAIT_BIZ_DECISION` 后 → 调 `biz-decision`
   - 人工触发改终点 → 调 `change-end-node`
   - 定时巡检发现异常任务 → 调 `analyze` + `scene=TASK_EXCEPTION`

2. 收到响应后：
   - `riskLevel=HIGH` 且 `needHumanConfirm=true` → 必须推人工
   - `suggestions[].platformCallHint` 存在 → 可展示给操作员作为一键操作
   - 执行完建议后必须调 `/feedback` 回写结果

3. 请求里 `requestId` 用平台自己的 traceId，便于两边日志串联

### 12.2 AI 侧保证

- 绝不调用平台"写"接口
- 响应时间 P95 < 3s，超时返回 FALLBACK
- 接口契约一旦发布，MINOR 版本内向后兼容

### 12.3 故障降级

- AI 服务完全不可用时，平台必须能退化为"不调 AI"继续执行原流程
- 平台侧对 AI 调用失败必须有熔断（建议 Resilience4j）

---

## 13. 不在 MVP 范围内的事

明确不做，避免膨胀：

- 流式诊断（SSE）
- 向量知识库检索（`/diagnosis/qa`）
- 规则在线编辑 UI
- 多租户隔离存储
- 诊断结果的多语言
- 建议动作的自动执行
- 复杂任务组全链路优化
- 设备级预测性维护

---

## 14. 风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| 规则和 LLM 结果冲突 | 平台无所适从 | 规则优先，命中后不再调 LLM |
| LLM 输出格式不稳定 | 解析失败 | 强制 JSON Schema + 重试 + FALLBACK |
| 平台传的 snapshot 字段缺失 | 规则匹配不上 | `SnapshotLoader` 按 taskNo 回查补全 |
| 两套规则引擎合并冲突 | 重构风险 | 先建 `RuleEngineBridge` 适配层，旧引擎只读不动，验证后再迁 |
| 建议被无脑执行 | 误操作 | `needHumanConfirm=true` 时平台强制人工，AI 侧建议在 HIGH 风险自动置为 true |

---

## 15. 开放问题（需要业务/平台确认）

每个问题给一个"如果没人回复就按这个做"的默认值：

| 问题 | 默认方案 |
|---|---|
| 平台是否能在 `/task/addTask` 之前先调 AI？同步还是异步？ | 同步调用，超时 3s 降级 |
| `taskSnapshot` 是平台推过来还是 AI 回查？ | MVP 阶段平台推，后续视情况改回查 |
| `tenantId` 是否强制 | MVP 允许空，预留字段 |
| 规则配置改动是否需要审批流 | MVP 直接改 YAML + 重启，后续做配置中心 |
| 诊断结果保留多久 | 默认 90 天，按 `created_at` 归档 |

---

## 16. 给开发的起手指南

如果你现在就要动手写代码，按这个顺序：

1. 先修 P0 四个点（半天）
2. 建 `ai_diagnosis_request` / `ai_diagnosis_result` 两张表，加 `DiagnosisRepository`
3. 写 `DiagnosisFacade`：入参校验 → 幂等查询 → 规则 → 落库
4. 把 `ScenarioDiagnosisService` 内部改为委托 `DiagnosisFacade`
5. 为 `TASK_PRE_CHECK` 一个场景跑通端到端
6. 再铺开其他场景

不要一上来改规则引擎、不要先做 LLM Prompt 优化、不要重构包结构——先让一个场景 **从平台调用到落库到反馈** 跑通，再扩。

---

## 17. 和原两份文档的主要区别

| 维度 | 原文档 | 本文档 |
|---|---|---|
| 是否承认现状 | 回避，像全新设计 | 第 1 节直接摆出两条并行链路 |
| 字段定义 | 只给字段名 | 给类型、约束、场景必填关系 |
| 接口契约 | 只给 URL | 给完整 JSON Schema |
| Suggestion 契约 | 没有 | 第 4 节完整表格 + platformCallHint |
| 数据库 | 只给表名 | 完整 DDL + 索引 + 落库时机 |
| 规则引擎 | 绕开不谈 | 明确两路合并方案 |
| 迁移路径 | 没有 | 第 9 节 P0/P1/P2/P3 清单 |
| 验收标准 | 时间里程碑 | 每阶段可观测的终点条件 |
| 风险 | 列表式口号 | 每条给对策 |
| 开放问题 | 列完就完 | 每条给默认方案 |

本文档可以直接作为需求评审和开发排期的输入。
