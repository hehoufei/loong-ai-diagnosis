# Technical Design: loong-ai-diagnosis 重构

## 1. 目标架构

```
┌─────────────────────────────────────────┐
│  REST 入口  /api/v1/diagnosis/analyze   │
└─────────────────────────────────────────┘
                 │ { taskId }
                 ▼
┌─────────────────────────────────────────┐
│  DiagnosisFacade（唯一编排入口）        │
│   1. 校验 taskId                        │
│   2. 数据获取（PlatformClient）         │
│   3. 组装 DiagnosisContext              │
│   4. 规则引擎（RuleEngine）             │
│   5. 命中 → 返回；未命中 → LLM 兜底     │
│   6. 结果归一化                         │
└─────────────────────────────────────────┘
      │                   │
      ▼                   ▼
┌──────────────┐   ┌────────────────────┐
│PlatformClient│   │LlmDiagnosisEngine  │
│ HTTP 调平台  │   │ Prompt + LLM       │
└──────────────┘   └────────────────────┘
      │
      ▼
  loong-platform
  - /task/queryTaskByTaskNo
  - /api/admin/scheduler/item/getTaskItemDetails
  - /api/admin/scheduler/task/getTasksByGroupCode
```

---

## 2. 包结构

```
cn.aimstek.loong.aidiag
├── AIDiagnosisApplication            # 启动类
├── controller
│   └── DiagnosisController           # 唯一 REST 入口
├── facade
│   └── DiagnosisFacade               # 唯一编排入口
├── dto
│   ├── DiagnoseRequest               # 入参
│   ├── DiagnoseResponse              # 出参
│   └── RootCauseItem
├── client                            # 平台数据访问层
│   ├── PlatformClient                # 接口
│   ├── HttpPlatformClient            # HTTP 实现
│   ├── EnumReverseMapper             # 中文枚举反向映射
│   └── dto                           # 平台返回 DTO
│       ├── PlatformTask
│       ├── PlatformTaskItem
│       ├── PlatformCommand
│       ├── PlatformTaskRelation
│       └── PlatformTaskGroup
├── context
│   ├── DiagnosisContext              # 诊断上下文
│   └── DiagnosisContextAssembler     # 上下文组装器
├── rule
│   ├── DiagnoseRule                  # 规则接口
│   ├── AbstractDiagnoseRule          # 规则基类
│   ├── RuleEngine                    # 规则引擎接口
│   ├── ConfigurableRuleEngine        # 规则引擎实现
│   ├── RuleProperties                # 规则配置绑定
│   └── rules/                        # 具体规则
│       ├── WaitSplitStuckRule
│       ├── WaitPlanStuckRule
│       ├── RunningButItemsWaitingRule
│       ├── CommandNotSentRule
│       ├── PlcNoResponseRule
│       ├── CommandTimeoutRule
│       ├── AllItemsDoneButTaskNotFinishedRule
│       ├── HasCancelledItemsRule
│       ├── TaskPausedRule
│       ├── TaskGroupNotSplitRule
│       └── FallbackRule
├── llm
│   ├── LlmDiagnosisEngine            # LLM 兜底引擎
│   ├── LlmClient                     # LLM 客户端接口
│   ├── SpringAiLlmClient             # Spring AI 实现
│   ├── PromptTemplateLoader          # Prompt 模板加载器
│   └── DiagnosisResponseMapper       # LLM 响应解析
├── config
│   ├── DiagnosisProperties           # loong.ai.diagnosis.* 配置
│   ├── PlatformProperties            # loong.platform.* 配置
│   ├── RestTemplateConfig            # HTTP 客户端配置
│   └── ModelConfig                   # LLM 模型配置
├── common
│   ├── BaseResponse
│   └── Response
└── exception
    ├── AiDiagnosisException
    └── GlobalExceptionHandler
```

---

## 3. 数据模型

### 3.1 入参 DiagnoseRequest

```java
public class DiagnoseRequest {
    @NotBlank
    private String taskId;
}
```

### 3.2 出参 DiagnoseResponse

```java
public class DiagnoseResponse {
    private String summary;
    private List<RootCauseItem> rootCauses;
    private List<String> actions;
    private String diagnosisMode;  // rule / llm / fallback
    private Double confidence;     // 0.0-1.0
    private String traceId;
}

public class RootCauseItem {
    private String title;
    private String description;
}
```

### 3.3 平台 DTO

```java
// PlatformTask - 对应 sc_task
public class PlatformTask {
    private String taskNo;
    private String rootTaskNo;
    private String parentTaskNo;
    private String groupCode;
    private String groupType;
    private String groupRole;       // MAIN / SUB / SYS_ADD
    private String taskSource;
    private String bizType;
    private String taskType;
    private String taskState;       // WAIT_SPLIT / WAIT_PLAN / RUNNING / SUCCESS / CANCEL
    private String paused;          // YES / NO
    private String startNode;
    private String endNode;
    private Integer bizPriority;
    private LocalDateTime createTime;
    private LocalDateTime plannedTime;
    private LocalDateTime startTime;
    private LocalDateTime finishTime;
    private List<Object> planSegmentList;
    private List<String> planFullPath;
    private List<Object> requiredFunctionList;
    private String errorMessage;
}

// PlatformTaskItem - 对应 sc_task_item
public class PlatformTaskItem {
    private String taskNo;
    private String taskItemNo;
    private String preTaskItemNo;
    private String deviceCode;
    private String deviceType;
    private String taskItemState;   // WAIT_SPLIT / WAIT_PLAN / RUNNING / SUCCESS / CANCEL
    private String taskAction;
    private String scheduleChannel; // ENGINE / BIZ / SYSTEM
    private String paused;
    private String startNode;
    private String endNode;
    private LocalDateTime startTime;
    private LocalDateTime finishTime;
    private List<PlatformCommand> commands;
}

// PlatformCommand - 对应 sc_command
public class PlatformCommand {
    private String taskItemNo;
    private String commandNo;
    private String plcTaskNo;
    private String deviceCode;
    private String deviceType;
    private String commandType;
    private String commandState;    // WAIT / SENT / ACKED / SUCCESS / FAILED / TIMEOUT
    private String commandDetail;
    private String commandResult;
    private LocalDateTime startTime;
    private LocalDateTime finishTime;
}

// PlatformTaskRelation - 子任务依赖
public class PlatformTaskRelation {
    private String fromTaskItemNo;
    private String toTaskItemNo;
}

// PlatformTaskGroup - 对应 sc_task_group_info
public class PlatformTaskGroup {
    private String groupCode;
    private String groupType;
    private Integer mainSize;
    private Integer subSize;
    private String splitFinish;     // FINISH / NOT_FINISH
    private LocalDateTime splitFinishTime;
    private List<PlatformTask> tasksInGroup;
}
```

### 3.4 DiagnosisContext

```java
@Data
@Builder
public class DiagnosisContext {
    private String traceId;
    private PlatformTask task;
    private List<PlatformTaskItem> items;
    private List<PlatformTaskRelation> relations;
    private PlatformTaskGroup group;
    private LocalDateTime now;

    // 便捷方法
    public List<PlatformCommand> allCommands() { ... }
    public List<PlatformTaskItem> activeItems() { ... }
    public List<PlatformTaskItem> runningItems() { ... }
    public long stuckSeconds() { ... }
    public boolean isTerminal() { ... }
    public boolean isGroupTask() { ... }
}
```

---

## 4. 接口设计

### 4.1 PlatformClient

```java
public interface PlatformClient {
    PlatformTask queryTask(String taskNo);
    TaskItemBundle queryTaskItemBundle(String taskNo);
    PlatformTaskGroup queryTaskGroup(String groupCode);
    PlatformDeviceStatus queryDevice(String deviceCode);

    @Data
    class TaskItemBundle {
        List<PlatformTaskItem> items;
        List<PlatformTaskRelation> relations;
    }
}
```

### 4.2 HTTP 调用映射

| 方法 | 平台接口 | 备注 |
|---|---|---|
| `queryTask` | `POST /task/queryTaskByTaskNo?taskNo=xxx` | 返回原始 code |
| `queryTaskItemBundle` | `GET /api/admin/scheduler/item/getTaskItemDetails?taskNo=xxx` | 需反向映射中文枚举 |
| `queryTaskGroup` | `GET /api/admin/scheduler/task/getTasksByGroupCode?groupCode=xxx` | 需反向映射 |
| `queryDevice` | `POST /dcs/queryDeviceByDeviceCode?deviceCode=xxx` | 可选 |

### 4.3 EnumReverseMapper

```java
@Component
public class EnumReverseMapper {
    private static final Map<String, String> TASK_STATE_REVERSE = Map.of(
        "等待拆分", "WAIT_SPLIT",
        "等待规划", "WAIT_PLAN",
        "运行中",   "RUNNING",
        "完成",     "SUCCESS",
        "手工完成", "MANUAL_SUCCESS",
        "取消",     "CANCEL",
        "暂停",     "PAUSED"
    );

    private static final Map<String, String> COMMAND_STATE_REVERSE = Map.of(
        "等待",    "WAIT",
        "已发送",  "SENT",
        "已确认",  "ACKED",
        "成功",    "SUCCESS",
        "失败",    "FAILED",
        "超时",    "TIMEOUT"
    );

    public String toTaskStateCode(String chineseOrCode) { ... }
    public String toCommandStateCode(String chineseOrCode) { ... }
}
```

---

## 5. 规则引擎设计

### 5.1 规则接口

```java
public interface DiagnoseRule {
    String getName();
    int getPriority();
    boolean isEnabled();
    boolean match(DiagnosisContext context);
    DiagnoseResponse diagnose(DiagnosisContext context);
}
```

### 5.2 规则清单

| 规则名 | 优先级 | 匹配条件 |
|---|---|---|
| `wait-split-stuck` | 100 | `task_state=WAIT_SPLIT` 且卡住 > 60s |
| `wait-plan-stuck` | 90 | `task_state=WAIT_PLAN` 且无 RUNNING 子任务 |
| `running-but-items-waiting` | 85 | `task_state=RUNNING` 但子任务全是 WAIT_* |
| `command-not-sent` | 80 | RUNNING 子任务的 Command 全是 WAIT |
| `plc-no-response` | 80 | Command SENT 但无 plcTaskNo |
| `command-timeout` | 75 | Command SENT/ACKED 超 300s 未完成 |
| `all-items-done-but-task-not-finished` | 70 | 子任务全 SUCCESS 但大任务未完成 |
| `has-cancelled-items` | 60 | 存在 CANCEL 的子任务 |
| `task-paused` | 50 | 任务或子任务被暂停 |
| `task-group-not-split` | 40 | 任务组未完成拆分 |
| `fallback` | -100 | 兜底，永远命中 |

### 5.3 规则配置 (diagnosis-rules.yml)

```yaml
rule-engine:
  rules:
    wait-split-stuck:
      enabled: true
      priority: 100
      description: "大任务长时间停在 WAIT_SPLIT"
      params:
        threshold-seconds: "60"
      output:
        summary: "任务卡在等待拆分阶段 {stuckMinutes} 分钟"
        root-causes:
          - title: "引擎未返回拆分结果"
            description: "大任务 {taskNo} 创建后 {stuckSeconds} 秒仍为 WAIT_SPLIT"
        actions:
          - "检查引擎服务是否可达"
          - "检查起终点路径可达性"
```

---

## 6. LLM 兜底设计

### 6.1 Prompt 文件结构

```
src/main/resources/prompts/
├── system-role.txt          # 系统角色设定
├── business-context.txt     # WCS 业务背景
├── decision-tree.txt        # 诊断决策树
└── output-format.txt        # 输出格式要求
```

### 6.2 PromptTemplateLoader

```java
@Component
public class PromptTemplateLoader {
    public String getSystemPrompt() { ... }
    public String buildUserPrompt(DiagnosisContext ctx) { ... }
    private String renderTaskData(DiagnosisContext ctx) { ... }
}
```

### 6.3 renderTaskData 输出格式

```
## 大任务
- 任务号: WMS_TASK_001
- 状态: RUNNING
- 已卡住: 325 秒
...

## 子任务列表（共 3 个）
| 序号 | 子任务号 | 状态 | 设备 | ... |
...

## 异常信号
- 指令 CMD_002 状态 SENT 已持续 280 秒未完成
```

---

## 7. 配置设计

### 7.1 application.yml

```yaml
server:
  port: 18080

spring:
  application:
    name: loong-ai-diagnosis
  config:
    import:
      - classpath:diagnosis-rules.yml
      - classpath:diagnosis-core.yml
  ai:
    zhipuai:
      api-key: ${LLM_API_KEY:xxx}
      chat:
        options:
          model: ${LLM_MODEL:glm-4.7-flash}
          temperature: 0.1
```

### 7.2 diagnosis-core.yml

```yaml
loong:
  ai:
    diagnosis:
      enable-llm-fallback: true
      llm-timeout-ms: 5000
      llm-max-retry: 1
      confidence:
        rule-match: 0.95
        llm-match: 0.7
        fallback: 0.3
  platform:
    base-url: ${PLATFORM_URL:http://127.0.0.1:18081}
    connect-timeout-ms: 3000
    read-timeout-ms: 5000
```

---

## 8. 错误处理

| 场景 | 异常 | HTTP 状态码 |
|---|---|---|
| taskId 为空 | `VALIDATION_ERROR` | 400 |
| 任务不存在 | `TASK_NOT_FOUND` | 404 |
| 平台不可达 | `PLATFORM_UNAVAILABLE` | 503 |
| LLM 超时 | 降级 fallback | 200 |

---

## 10. 平台代理接口设计

### 10.1 PlatformProxyController

```java
@RestController
@RequestMapping("/api/v1/platform")
public class PlatformProxyController {
    // 透传代理，不做业务逻辑，直接转发到 loong-platform
    // GET /api/v1/platform/task/detail?taskNo=xxx
    // GET /api/v1/platform/task/items?taskNo=xxx
    // GET /api/v1/platform/task/group?groupCode=xxx
    // POST /api/v1/platform/task/page
    // GET /api/v1/platform/task/queryParams
}

@RestController
@RequestMapping("/api/v1/map")
public class MapProxyController {
    // POST /api/v1/map/nodeList  → loong-platform 地图节点
    // POST /api/v1/map/viewDetail → loong-platform 地图视图
}
```

### 10.2 代理接口映射表

| 本地接口 | 代理目标（loong-platform） | 说明 |
|---|---|---|
| `POST /api/v1/platform/task/page` | `POST /api/admin/scheduler/task/pageTasks` | 任务分页，支持状态/类型/来源过滤 |
| `GET /api/v1/platform/task/detail` | `GET /api/admin/scheduler/task/detail/getTaskDetail` | 大任务详情+任务组+关联任务 |
| `GET /api/v1/platform/task/items` | `GET /api/admin/scheduler/item/getTaskItemDetails` | 子任务+指令+依赖关系 |
| `GET /api/v1/platform/task/group` | `GET /api/admin/scheduler/task/getTasksByGroupCode` | 任务组信息 |
| `GET /api/v1/platform/task/queryParams` | `GET /api/admin/scheduler/getTaskQueryParam` | 查询下拉枚举 |
| `POST /api/v1/map/nodeList` | loong-platform 地图节点接口 | map.html 使用 |
| `POST /api/v1/map/viewDetail` | loong-platform 地图视图接口 | map.html 使用 |

---

## 11. 规则管理接口设计

### 11.1 RuleManagementController

```java
@RestController
@RequestMapping("/api/v1/rules")
public class RuleManagementController {
    // GET  /api/v1/rules          → 规则列表（含运行时统计）
    // GET  /api/v1/rules/stats    → 引擎整体统计
    // PUT  /api/v1/rules/{name}/priority  → 动态调整优先级
    // PUT  /api/v1/rules/{name}/enabled   → 启用/禁用
    // POST /api/v1/rules/reload   → 重载 YAML 配置
    // POST /api/v1/rules/reset-stats → 重置统计
}
```

### 11.2 规则运行时统计数据结构

```java
// ConfigurableRuleEngine 中维护
Map<String, RuleStats> statsMap;  // ruleName → stats

class RuleStats {
    AtomicLong hitCount;          // 命中次数
    AtomicLong totalElapsedMs;    // 累计耗时
    volatile LocalDateTime lastHitTime; // 最后命中时间
}
```

---

## 12. Agent 对话接口设计

### 12.1 AgentChatController

```java
@RestController
@RequestMapping("/api/v2/diagnosis")
public class AgentChatController {
    // POST /api/v2/diagnosis/chat          → SSE 流式对话
    // GET  /api/v2/diagnosis/chat/models   → 可用模型列表
    // POST /api/v2/diagnosis/chat/models/switch → 切换模型
    // GET  /api/v2/diagnosis/chat/env      → 环境信息
}
```

### 12.2 SSE 事件格式

```
event: thinking
data: {"data":{"content":"正在分析任务状态..."}}

event: tool_call
data: {"data":{"tool":"diagnose_task","args":{"taskNo":"WMS_001"}}}

event: tool_result
data: {"data":{"tool":"diagnose_task","result":"规则命中: wait-split-stuck"}}

event: text
data: {"data":{"content":"根据分析，任务卡在等待拆分阶段..."}}

event: done
data: {}
```

### 12.3 Agent 内置工具

| 工具名 | 参数 | 说明 |
|---|---|---|
| `diagnose_task` | `taskNo: String` | 调用 DiagnosisFacade 执行完整诊断 |
| `query_task_detail` | `taskNo: String` | 调用 PlatformClient 查询任务详情 |
| `query_task_items` | `taskNo: String` | 查询子任务和指令列表 |

```
DIAG_START traceId=xxx taskId=xxx
PLATFORM_CALL traceId=xxx api=queryTask elapsedMs=xxx
RULE_MATCHED traceId=xxx rule=wait-split-stuck
DIAG_END traceId=xxx mode=rule elapsedMs=xxx
```
