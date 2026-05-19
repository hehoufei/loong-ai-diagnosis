# loong-ai-diagnosis 实施设计

> 版本：2026-05-09
> 前置阅读：`loong-platform-业务理解与诊断工具设计分析.md`
> 本文档作用：把分析文档里的理解落成开发可以直接照着写代码的设计。

---

## 0. 再次确认定位

- **是什么**：一个任务诊断小工具。输入一个 `taskNo`，输出"这个任务为什么卡住、该怎么办"
- **不是什么**：不是诊断中台、不是决策系统、不调平台写接口
- **运行模式**：无状态、不落库、不记录、用完即走
- **数据来源**：HTTP 调 loong-platform 现有接口拿任务全貌
- **分析链路**：规则引擎优先 → 未命中走 LLM 兜底
- **入口**：一个 REST 接口 `POST /api/v1/diagnosis/analyze`，前端运维 UI 输入 taskNo 触发

这个定位和旧 `DiagnosisFacade` 的模型完全一致。**改动的本质是数据来源和规则内容，而不是架构**。

---

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

整个工程**只有一个诊断入口、一套 DTO、一套 Context、一套规则引擎**。

---

## 2. 包结构（目标）

```
cn.aimstek.loong.aidiag
├── AIDiagnosisApplication            # 启动类
├── controller
│   └── DiagnosisController           # 唯一 REST 入口
├── facade
│   └── DiagnosisFacade               # 唯一编排入口
├── dto
│   ├── DiagnoseRequest               # 入参（只有 taskId）
│   ├── DiagnoseResponse              # 出参（summary/rootCauses/actions 等）
│   └── RootCauseItem
├── client                            # 平台数据访问层
│   ├── PlatformClient                # 接口
│   ├── HttpPlatformClient            # HTTP 实现
│   └── dto                           # 平台返回 DTO（PlatformTask, PlatformTaskItem, PlatformCommand, PlatformTaskRelation, PlatformTaskGroup）
├── context
│   ├── DiagnosisContext              # 规则/LLM 共用的诊断上下文
│   └── DiagnosisContextAssembler     # 从 PlatformClient 数据组装 Context
├── rule
│   ├── DiagnoseRule                  # 规则接口（保留）
│   ├── AbstractDiagnoseRule          # 规则基类（改造，去掉旧字段）
│   ├── RuleEngine                    # 规则引擎（保留 ConfigurableRuleEngine）
│   ├── RuleProperties                # 规则 YAML 配置绑定
│   └── rules/                        # 具体规则 Bean（按新版状态机重写）
│       ├── WaitSplitStuckRule
│       ├── WaitPlanStuckRule
│       ├── RunningStuckRule
│       ├── CommandNotSentRule
│       ├── PlcNoResponseRule
│       ├── CommandTimeoutRule
│       ├── AllItemsSuccessButTaskNotFinishedRule
│       ├── TaskItemCancelledRule
│       ├── TaskPausedRule
│       ├── TaskGroupNotSplitRule
│       └── FallbackRule
├── llm
│   ├── LlmDiagnosisEngine            # LLM 兜底引擎（保留，调整 Prompt）
│   ├── LlmClient                     # LLM 客户端（保留）
│   └── PromptTemplateLoader          # 从 resources/prompts/ 加载模板
├── config
│   ├── DiagnosisProperties           # loong.ai.diagnosis.* 配置
│   ├── PlatformProperties            # loong.platform.* 配置
│   └── 其他（RestTemplateConfig / ModelConfig 保留）
├── common                            # 通用响应（保留）
└── exception                         # 异常处理（保留）
```

**要删的顶层包**：
- `platform/*`（整个子包删除，是上一轮设计的错误产物）
- `experiment/*`（实验性代码，不进 MVP）
- `tool/*`（Agent 工具，本工具不走 Agent 模式）

**要保留但精简的包**：
- `service/*`：合并 `DiagnosisFacade`、`DiagnosisContextAssembler` 迁移到上面新包；删除 `GlobalDiagnosisService`、`DiagnosisStreamService`、`AgentChatService`、`BlockageAnalyzer` 等无用服务
- `rule/rules/*`：13 条老规则全部推翻，按新状态机重写

---

## 3. 数据契约

### 3.1 入参：DiagnoseRequest

```java
public class DiagnoseRequest {
    /** 任务号（对应 loong-platform 的 task_no） */
    @NotBlank
    private String taskId;
}
```

**刻意简化**：没有 scope、timeWindowMinutes、env。MVP 就是"给一个 taskNo，告诉我它为什么卡住"，一个字段够了。

### 3.2 出参：DiagnoseResponse

```java
public class DiagnoseResponse {
    /** 一句话根因总结 */
    private String summary;
    
    /** 根因列表 */
    private List<RootCauseItem> rootCauses;
    
    /** 建议动作列表（纯文本，不强制结构化） */
    private List<String> actions;
    
    /** 诊断模式：rule / llm / fallback */
    private String diagnosisMode;
    
    /** 置信度 0.0-1.0 */
    private Double confidence;
    
    /** 链路追踪ID */
    private String traceId;
}

public class RootCauseItem {
    private String title;
    private String description;
}
```

保持和旧版前端兼容，不动出参结构。

### 3.3 平台返回 DTO（内部使用）

放在 `client/dto/` 下，字段对应 loong-platform 新版概念：

```java
// 对应 sc_task
public class PlatformTask {
    private String taskNo;
    private String rootTaskNo;
    private String parentTaskNo;
    private String groupCode;
    private String groupType;
    private String groupRole;            // MAIN / SUB / SYS_ADD
    private String taskSource;
    private String bizType;
    private String taskType;
    private String taskState;            // WAIT_SPLIT / WAIT_PLAN / RUNNING / SUCCESS / CANCEL
    private String paused;               // YES / NO
    private String startNode;
    private String endNode;
    private Integer bizPriority;
    private LocalDateTime createTime;
    private LocalDateTime plannedTime;
    private LocalDateTime startTime;
    private LocalDateTime finishTime;
    private List<Object> planSegmentList;      // 引擎拆分的 segment，不定结构
    private List<String> planFullPath;         // 规划全路径
    private List<Object> requiredFunctionList; // 能力需求
    private String errorMessage;               // 错误信息（来自 extData 或其他字段）
}

// 对应 sc_task_item
public class PlatformTaskItem {
    private String taskNo;
    private String taskItemNo;
    private String preTaskItemNo;
    private String deviceCode;
    private String deviceType;
    private String taskItemState;        // WAIT_SPLIT / WAIT_PLAN / RUNNING / SUCCESS / CANCEL
    private String taskAction;
    private String scheduleChannel;      // ENGINE / BIZ / SYSTEM
    private String paused;
    private String startNode;
    private String endNode;
    private LocalDateTime startTime;
    private LocalDateTime finishTime;
    private List<PlatformCommand> commands;    // 该子任务下的指令（从 getTaskItemDetails 拼进来）
}

// 对应 sc_command
public class PlatformCommand {
    private String taskItemNo;
    private String commandNo;
    private String plcTaskNo;
    private String deviceCode;
    private String deviceType;
    private String commandType;
    private String commandState;         // WAIT / SENT / ACKED / SUCCESS / FAILED / TIMEOUT
    private String commandDetail;
    private String commandResult;
    private LocalDateTime startTime;
    private LocalDateTime finishTime;
}

// 子任务之间的依赖（来自 getTaskItemDetails 的 taskItemRelations）
public class PlatformTaskRelation {
    private String fromTaskItemNo;   // 前序
    private String toTaskItemNo;     // 后继
}

// 对应 sc_task_group_info
public class PlatformTaskGroup {
    private String groupCode;
    private String groupType;
    private Integer mainSize;
    private Integer subSize;
    private String splitFinish;          // FINISH / NOT_FINISH
    private LocalDateTime splitFinishTime;
    private List<PlatformTask> tasksInGroup;   // 组内所有任务（含 MAIN/SUB/SYS_ADD）
}
```

---

## 4. 数据获取层（PlatformClient）

### 4.1 接口

```java
public interface PlatformClient {
    /** 查大任务（原始 code） */
    PlatformTask queryTask(String taskNo);

    /** 查子任务 + 指令 + 依赖关系（需要反向映射中文枚举到 code） */
    TaskItemBundle queryTaskItemBundle(String taskNo);

    /** 查任务组（仅当大任务有 groupCode 时调用） */
    PlatformTaskGroup queryTaskGroup(String groupCode);

    /** 查设备状态（按需调用） */
    PlatformDeviceStatus queryDevice(String deviceCode);

    class TaskItemBundle {
        List<PlatformTaskItem> items;
        List<PlatformTaskRelation> relations;
    }
}
```

### 4.2 HTTP 实现（调用映射）

| PlatformClient 方法 | loong-platform 接口 | 备注 |
|---|---|---|
| `queryTask` | `POST /task/queryTaskByTaskNo?taskNo=xxx` | 返回原始 code |
| `queryTaskItemBundle` | `GET /api/admin/scheduler/item/getTaskItemDetails?taskNo=xxx` | 枚举已翻译为中文，需反向映射 |
| `queryTaskGroup` | `GET /api/admin/scheduler/task/getTasksByGroupCode?groupCode=xxx` | 枚举已翻译 |
| `queryDevice` | `POST /dcs/queryDeviceByDeviceCode?deviceCode=xxx` | 可选 |

### 4.3 中文枚举反向映射

loong-platform 的 admin 接口把 code 翻译成中文（比如 `RUNNING` → `运行中`）。`HttpPlatformClient` 内部维护一个 `EnumReverseMapper`：

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
    
    // ... 其他枚举
    
    public String toTaskStateCode(String chineseOrCode) {
        if (chineseOrCode == null) return null;
        // 兼容：如果已经是 code 就原样返回
        return TASK_STATE_REVERSE.getOrDefault(chineseOrCode, chineseOrCode);
    }
    
    public String toCommandStateCode(String chineseOrCode) { /* ... */ }
    // ...
}
```

**关键原则**：`DiagnosisContext` 里存的一律是 **code**，不是中文。规则匹配时只匹配 code。中文是 HTTP 响应反序列化阶段就被翻译回 code。

### 4.4 失败处理

- HTTP 超时：连接 3s、读取 5s
- 404 / 任务不存在：抛 `AiDiagnosisException("TASK_NOT_FOUND")`
- 5xx / 网络异常：抛 `AiDiagnosisException("PLATFORM_UNAVAILABLE")`
- `queryTaskGroup`、`queryDevice` 属于可选数据，失败时吞掉日志告警，不阻断主诊断链路

### 4.5 注意事项

- `/task/queryTaskByTaskNo` 返回的 `TaskDTO` 是 MyBatis-Flex 直接映射的实体，字段完整（含 `paused`、`planSegmentList`、`engineSplitResult` 等 JSON 字段）。但 JSON 字段的反序列化需要注意类型——`planSegmentList` 在 Java 侧是 `List<Segment>`，HTTP JSON 传输后变成 `List<Map>`，`HttpPlatformClient` 里统一用 `List<Object>` 接收即可，规则不依赖这些复杂结构的内部字段。
- `/api/admin/scheduler/item/getTaskItemDetails` 返回的 `RootTaskItem` 中，`taskItemVos` 里每个 `TaskItemVo` 已经内嵌了 `List<CommandVo> commands`，不需要额外调接口拿 Command。
- 枚举翻译只发生在 admin 接口（`/api/admin/scheduler/*`），`/task/queryTaskByTaskNo` 返回的是原始 code。所以 `queryTask` 不需要反向映射，`queryTaskItemBundle` 需要。

---

## 5. DiagnosisContext（诊断上下文）

### 5.1 结构

```java
@Data
@Builder
public class DiagnosisContext {
    /** 链路追踪ID */
    private String traceId;
    
    /** 大任务（必有） */
    private PlatformTask task;
    
    /** 子任务列表（按 id 排序；可能为空） */
    private List<PlatformTaskItem> items;
    
    /** 子任务依赖关系（可能为空） */
    private List<PlatformTaskRelation> relations;
    
    /** 任务组信息（仅当 task.groupCode 非空时有值） */
    private PlatformTaskGroup group;
    
    /** 当前服务器时间（用于计算卡住时长，统一时间基准） */
    private LocalDateTime now;
    
    // ===== 便捷查询方法 =====
    
    /** 所有子任务的指令扁平化 */
    public List<PlatformCommand> allCommands() { ... }
    
    /** 非终态的子任务（taskItemState 不是 SUCCESS/MANUAL_SUCCESS/CANCEL） */
    public List<PlatformTaskItem> activeItems() { ... }
    
    /** 当前 RUNNING 的子任务（通常是分析重点） */
    public List<PlatformTaskItem> runningItems() { ... }
    
    /** 计算任务卡住时长（秒） */
    public long stuckSeconds() {
        LocalDateTime since = task.getStartTime() != null ? task.getStartTime() : task.getCreateTime();
        return Duration.between(since, now).getSeconds();
    }
    
    /** 判断大任务是否已终态 */
    public boolean isTerminal() {
        return Set.of("SUCCESS", "MANUAL_SUCCESS", "CANCEL").contains(task.getTaskState());
    }
    
    /** 判断是否任务组任务 */
    public boolean isGroupTask() {
        return task.getGroupCode() != null && !task.getGroupCode().isBlank();
    }
}
```

### 5.2 组装器 DiagnosisContextAssembler

```java
@Service
@RequiredArgsConstructor
public class DiagnosisContextAssembler {
    private final PlatformClient platformClient;

    public DiagnosisContext assemble(String taskNo, String traceId) {
        PlatformTask task = platformClient.queryTask(taskNo);
        if (task == null) {
            throw new AiDiagnosisException("TASK_NOT_FOUND", "任务不存在: " + taskNo);
        }
        PlatformClient.TaskItemBundle bundle = platformClient.queryTaskItemBundle(taskNo);
        PlatformTaskGroup group = null;
        if (task.getGroupCode() != null && !task.getGroupCode().isBlank()) {
            try {
                group = platformClient.queryTaskGroup(task.getGroupCode());
            } catch (Exception e) {
                log.warn("查询任务组失败, groupCode={}, err={}", task.getGroupCode(), e.getMessage());
            }
        }
        return DiagnosisContext.builder()
                .traceId(traceId)
                .task(task)
                .items(bundle.items)
                .relations(bundle.relations)
                .group(group)
                .now(LocalDateTime.now())
                .build();
    }
}
```

---

## 6. 规则引擎

### 6.1 保留的基础设施

- `DiagnoseRule` 接口（**改动**：`match` 和 `diagnose` 方法的参数类型从 `cn.aimstek.loong.aidiag.rule.DiagnosisContext` 改为 `cn.aimstek.loong.aidiag.context.DiagnosisContext`，这是一个 breaking change，所有规则实现类都要跟着改）
- `AbstractDiagnoseRule` 基类（改造：移除老字段 `handleState`、`taskItems`、`tickets` 相关的模板变量；增加基于新版 Context 的工具方法）
- `ConfigurableRuleEngine`（规则注册、优先级、启停控制，保留核心逻辑；**改造**：移除构造函数中对 `RuleEngineSnapshotService`、`RuleEngineBootstrapService`、`RulePriorityService`、`RuleStatisticsService` 的依赖，这些辅助类全部删除，统计功能如果需要就用内存 Map 简单记录）
- `RuleProperties`（YAML 绑定，保留）

### 6.2 改造点

**DiagnoseRule 接口签名变更**：

```java
// 旧签名
boolean match(cn.aimstek.loong.aidiag.rule.DiagnosisContext context);
DiagnoseResponse diagnose(cn.aimstek.loong.aidiag.rule.DiagnosisContext context);

// 新签名
boolean match(cn.aimstek.loong.aidiag.context.DiagnosisContext context);
DiagnoseResponse diagnose(cn.aimstek.loong.aidiag.context.DiagnosisContext context);
```

由于旧的 `rule.DiagnosisContext` 会被删除，这个改动是强制的。所有规则实现类（包括 `ExpressionRule`）都要跟着改 import。

**AbstractDiagnoseRule 的模板变量改造**：

```java
protected String resolveTemplate(String template, DiagnosisContext ctx) {
    String r = template;
    r = r.replace("{taskNo}", n(ctx.getTask().getTaskNo()));
    r = r.replace("{taskState}", n(ctx.getTask().getTaskState()));
    r = r.replace("{stuckSeconds}", String.valueOf(ctx.stuckSeconds()));
    r = r.replace("{stuckMinutes}", String.valueOf(ctx.stuckSeconds() / 60));
    r = r.replace("{itemCount}", String.valueOf(ctx.getItems().size()));
    r = r.replace("{runningItemCount}", String.valueOf(ctx.runningItems().size()));
    r = r.replace("{errorMessage}", n(ctx.getTask().getErrorMessage()));
    return r;
}
```

### 6.3 YAML 规则配置（`diagnosis-rules.yml`）

通过 `spring.config.import: classpath:diagnosis-rules.yml` 引入。格式和现有 `rule-engine.rules.*` 兼容：

```yaml
rule-engine:
  doc-search:
    enabled: false              # MVP 阶段先不启用知识库检索
  rules:
    wait-split-stuck:
      enabled: true
      priority: 100             # 数字越大越优先
      description: "大任务长时间停在 WAIT_SPLIT（等待引擎规划）"
      params:
        threshold-seconds: "60"
      output:
        summary: "任务卡在等待拆分阶段 {stuckMinutes} 分钟，引擎规划未完成"
        root-causes:
          - title: "引擎未返回拆分结果"
            description: "大任务 {taskNo} 创建后 {stuckSeconds} 秒，task_state 仍为 WAIT_SPLIT"
        actions:
          - "检查引擎服务是否可达"
          - "检查起终点 {task.startNode} → {task.endNode} 是否存在可达路径"
          - "查看调度层 fillTaskSegmentList 是否抛异常"

    wait-plan-stuck:
      enabled: true
      priority: 90
      description: "子任务全部停在 WAIT_PLAN，设备分配未触发"
      params:
        threshold-seconds: "60"
      # ... 同上

    # 其他规则 ...
    
    fallback:
      enabled: true
      priority: -100            # 最低优先级，兜底
      description: "未命中任何规则，交给 LLM 分析"
```

### 6.4 规则清单（MVP 10 条）

按触发阶段分组：

| 规则名 | 匹配条件 | 风险等级 |
|---|---|---|
| `wait-split-stuck` | `task_state=WAIT_SPLIT` 且卡住 > 阈值 | HIGH |
| `wait-plan-stuck` | `task_state=WAIT_PLAN` 且所有子任务都不是 RUNNING | MEDIUM |
| `running-but-items-waiting` | `task_state=RUNNING` 但所有子任务都是 WAIT_* | HIGH |
| `command-not-sent` | 存在 RUNNING 子任务，但其下 Command 全部 WAIT | HIGH |
| `plc-no-response` | 存在 Command 处于 SENT 状态但无 `plcTaskNo` | HIGH |
| `command-timeout` | 存在 Command 的 `start_time` 距今超阈值仍未完成 | HIGH |
| `all-items-done-but-task-not-finished` | 所有子任务 SUCCESS，但大任务仍 RUNNING/WAIT_PLAN | MEDIUM |
| `has-cancelled-items` | 存在 `task_item_state=CANCEL` 的子任务 | MEDIUM |
| `task-paused` | `task.paused=YES` 或所有 RUNNING 子任务 `paused=YES` | LOW |
| `task-group-not-split` | 任务组的 `split_finish=NOT_FINISH` 且组内任务都在 WAIT_SPLIT | MEDIUM |
| `fallback` | 兜底，优先级最低 | - |

每条规则一个 Bean 类，在 `rule/rules/` 下。规则 Bean 只写 `match` 逻辑，输出完全由 YAML 配置，通过 `buildResponseFromConfig(ctx)` 生成。

### 6.5 示例规则实现

```java
@Component
@Slf4j
public class WaitSplitStuckRule extends AbstractDiagnoseRule {
    @Override public String getName() { return "wait-split-stuck"; }
    @Override public int getPriority() { return 100; }

    @Override
    public boolean match(DiagnosisContext ctx) {
        if (!"WAIT_SPLIT".equals(ctx.getTask().getTaskState())) return false;
        long threshold = getIntParam("threshold-seconds", 60);
        return ctx.stuckSeconds() >= threshold;
    }

    @Override
    public DiagnoseResponse diagnose(DiagnosisContext ctx) {
        DiagnoseResponse r = buildResponseFromConfig(ctx);
        if (r != null) return r;
        // 兜底：YAML 没配置时的硬编码输出
        return buildResponse(
                "任务卡在 WAIT_SPLIT 阶段",
                "引擎未返回拆分结果",
                "等待时长：" + ctx.stuckSeconds() + "秒",
                "检查引擎服务", "检查起终点路径可达性");
    }
}
```

### 6.6 全部 10 条规则的 match 伪代码

#### ① wait-split-stuck（优先级 100）

```java
boolean match(ctx):
    task.taskState == "WAIT_SPLIT"
    AND stuckSeconds() >= threshold(60)
    // 排除任务组场景：如果有 groupCode 且组未拆完，属于正常等待
    AND NOT (task.groupCode != null AND group != null AND group.splitFinish == "NOT_FINISH")
```

#### ② wait-plan-stuck（优先级 90）

```java
boolean match(ctx):
    task.taskState == "WAIT_PLAN"
    AND items 不为空
    AND items 中没有任何一个 taskItemState 为 "RUNNING" 或 "SUCCESS"
    AND stuckSeconds() >= threshold(60)
```

#### ③ running-but-items-waiting（优先级 85）

```java
boolean match(ctx):
    task.taskState == "RUNNING"
    AND items 不为空
    AND items 中所有非终态的子任务 taskItemState 都是 "WAIT_PLAN" 或 "WAIT_SPLIT"
    // 大任务标记 RUNNING 但子任务全在等待，说明状态不一致
```

#### ④ command-not-sent（优先级 80）

```java
boolean match(ctx):
    存在至少一个子任务 taskItemState == "RUNNING"
    AND 该子任务下所有 Command 的 commandState 都是 "WAIT"
    AND 该子任务 startTime 距今 >= threshold(30s)
    // 子任务已 RUNNING 但指令没下发出去
```

#### ⑤ plc-no-response（优先级 80）

```java
boolean match(ctx):
    存在至少一个 Command:
        commandState == "SENT"
        AND (plcTaskNo == null OR plcTaskNo.isBlank())
        AND startTime 距今 >= threshold(30s)
    // 指令已发送但 PLC 没有分配电气号
```

#### ⑥ command-timeout（优先级 75）

```java
boolean match(ctx):
    存在至少一个 Command:
        commandState IN ("SENT", "ACKED")
        AND startTime != null
        AND startTime 距今 >= threshold(300s)  // 默认 5 分钟
        AND finishTime == null
    // 指令已下发/已确认但长时间未完成
```

#### ⑦ all-items-done-but-task-not-finished（优先级 70）

```java
boolean match(ctx):
    task.taskState IN ("RUNNING", "WAIT_PLAN")
    AND items 不为空
    AND items 中所有子任务 taskItemState 都是 "SUCCESS" 或 "MANUAL_SUCCESS"
    // 子任务全完成但大任务没有流转到 SUCCESS
```

#### ⑧ has-cancelled-items（优先级 60）

```java
boolean match(ctx):
    task.taskState NOT IN ("SUCCESS", "MANUAL_SUCCESS", "CANCEL")
    AND items 中存在至少一个 taskItemState == "CANCEL"
    // 大任务还在跑，但有子任务被取消了
```

#### ⑨ task-paused（优先级 50）

```java
boolean match(ctx):
    task.paused == "YES"
    OR (
        runningItems() 不为空
        AND runningItems() 中所有子任务 paused == "YES"
    )
    // 大任务被暂停，或者所有正在执行的子任务都被暂停
```

#### ⑩ task-group-not-split（优先级 40）

```java
boolean match(ctx):
    task.groupCode != null
    AND group != null
    AND group.splitFinish == "NOT_FINISH"
    AND group.tasksInGroup 中所有任务 taskState 都是 "WAIT_SPLIT"
    AND group.splitFinishTime == null
    AND (group 创建时间距今 >= threshold(120s))
    // 任务组长时间未完成拆分
```

#### ⑪ fallback（优先级 -100）

```java
boolean match(ctx):
    return true  // 永远命中，兜底
```

`fallback` 规则的 `diagnose` 方法返回 null（表示"规则层无结论"），由 `DiagnosisFacade` 决定是走 LLM 还是返回 fallback 响应。或者也可以让 fallback 规则直接返回一个"建议使用 AI 深度分析"的响应，取决于 `enable-llm-fallback` 配置。

### 6.6 引擎执行流程

保留 `ConfigurableRuleEngine` 的核心逻辑：
1. 启动时按优先级排序所有 Bean 规则
2. `evaluate(ctx)` 按序调用 `match(ctx)`
3. 首个命中的规则调 `diagnose(ctx)` 返回结果
4. 未命中返回 null，交给 LLM

---

## 7. LLM 兜底

### 7.1 保留的组件

- `LlmDiagnosisEngine`（服务入口）
- `LlmClient` + `SpringAiLlmClient`（LLM 客户端）
- `DiagnosisResponseMapper`（解析 LLM JSON 输出）

### 7.2 改造点

**Prompt 从代码移到文件**：

```
src/main/resources/prompts/
├── system-role.txt          # 系统角色设定
├── business-context.txt     # WCS 业务背景（基于新版状态机）
├── decision-tree.txt        # 诊断决策树
└── output-format.txt        # 输出格式要求（JSON schema）
```

加载器：

```java
@Component
public class PromptTemplateLoader {
    @Value("classpath:prompts/system-role.txt")
    private Resource systemRoleRes;
    // ...
    
    @PostConstruct
    public void init() {
        this.systemRole = readAsString(systemRoleRes);
        // ...
    }
    
    public String getSystemPrompt() { return systemRole; }
    public String buildUserPrompt(DiagnosisContext ctx) {
        return businessContext + "\n\n" + decisionTree 
             + "\n\n## 当前任务数据\n" + renderTaskData(ctx)
             + "\n\n" + outputFormat;
    }
    
    private String renderTaskData(DiagnosisContext ctx) {
        // 把 task / items / commands / relations / group 序列化为结构化文本
    }
}
```

**业务背景改写**（`business-context.txt`）基于新版概念：

```
你是 WCS 任务排障专家，帮运维定位任务为什么卡住。

## WCS 核心模型
- 大任务 sc_task：业务下发的任务，状态 WAIT_SPLIT → WAIT_PLAN → RUNNING → SUCCESS
- 子任务 sc_task_item：大任务被引擎拆分成的子单元，绑定具体设备
- 指令 sc_command：子任务调度时生成，下发给 PLC/机器人

## 任务流转
1. WAIT_SPLIT：业务刚下发，等待引擎路径规划
2. WAIT_PLAN：引擎已拆出子任务，等待设备分配
3. RUNNING：有子任务开始执行
4. SUCCESS：所有子任务完成
...
```

### 7.3 LLM 调用配置

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
```

LLM 调用失败或超时：降级为一个固定的 `FALLBACK` 响应（`summary=AI 无法分析此任务，请人工介入`）。

### 7.4 renderTaskData 格式

`PromptTemplateLoader.renderTaskData(ctx)` 把诊断上下文序列化为 LLM 可读的结构化文本。格式如下：

```
## 大任务
- 任务号: WMS_TASK_001
- 状态: RUNNING
- 任务类型: N2N
- 起点: ND_A01
- 终点: ND_B05
- 创建时间: 2026-05-09 10:00:00
- 开始时间: 2026-05-09 10:00:05
- 已卡住: 325 秒
- 是否暂停: NO
- 任务组: GROUP_001（类型: 码垛，拆分状态: FINISH）

## 子任务列表（共 3 个）
| 序号 | 子任务号 | 状态 | 设备 | 动作 | 起点 | 终点 | 开始时间 | 完成时间 | 暂停 |
|---|---|---|---|---|---|---|---|---|---|
| 1 | ITEM_001 | SUCCESS | DV_CRANE_01 | MOVE | ND_A01 | ND_A02 | 10:00:05 | 10:00:30 | NO |
| 2 | ITEM_002 | RUNNING | DV_CONV_01 | MOVE | ND_A02 | ND_B03 | 10:00:31 | - | NO |
| 3 | ITEM_003 | WAIT_PLAN | DV_CONV_02 | MOVE | ND_B03 | ND_B05 | - | - | NO |

## 子任务依赖关系
- ITEM_001 → ITEM_002 → ITEM_003（串行）

## 指令列表（子任务 ITEM_002 下）
| 指令号 | 状态 | 设备 | PLC号 | 类型 | 起点 | 终点 | 开始时间 | 完成时间 |
|---|---|---|---|---|---|---|---|---|
| CMD_001 | SUCCESS | DV_CONV_01 | 12345 | MOVE | ND_A02 | ND_A03 | 10:00:31 | 10:00:45 |
| CMD_002 | SENT | DV_CONV_01 | 12346 | MOVE | ND_A03 | ND_B03 | 10:00:46 | - |

## 异常信号
- 指令 CMD_002 状态 SENT 已持续 280 秒未完成
- 子任务 ITEM_003 处于 WAIT_PLAN 等待前序完成
```

**渲染规则**：
- 只渲染非终态子任务的指令（已完成的子任务指令不展示，减少 token）
- 如果子任务超过 10 个，只展示前 5 个 + 最后 2 个 + 当前 RUNNING 的，中间用 `...（省略 N 个已完成子任务）` 代替
- 时间格式统一用 `HH:mm:ss`（同一天）或 `MM-dd HH:mm:ss`（跨天）
- 末尾的"异常信号"段由代码自动检测生成，帮助 LLM 聚焦关键问题：
  - Command 超时（SENT/ACKED 超过 60s）
  - 子任务暂停
  - 设备锁定
  - 任务组未拆分

### 7.5 output-format.txt 内容

```
请严格按以下 JSON 格式输出诊断结果，不要输出任何其他文字：

{
  "summary": "一句话说明任务卡在哪个阶段及原因",
  "rootCauses": [
    {
      "title": "根因标题",
      "description": "详细描述，包括判断依据和引用的数据"
    }
  ],
  "actions": [
    "具体操作建议1",
    "具体操作建议2"
  ]
}

要求：
- summary 不超过 100 字
- rootCauses 1-3 条
- actions 1-5 条
- 不要返回任务原始数据
- 不要用 markdown 代码块包裹
```

---

## 8. DiagnosisFacade 编排逻辑

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class DiagnosisFacade {
    private final DiagnosisContextAssembler assembler;
    private final RuleEngine ruleEngine;
    private final LlmDiagnosisEngine llmEngine;
    private final DiagnosisProperties properties;

    public DiagnoseResponse diagnose(DiagnoseRequest request) {
        String traceId = UUID.randomUUID().toString();
        MDC.put("traceId", traceId);
        try {
            // 1. 参数校验
            if (!StringUtils.hasText(request.getTaskId())) {
                throw new AiDiagnosisException("VALIDATION_ERROR", "taskId不能为空");
            }

            // 2. 组装上下文（HTTP 调平台）
            DiagnosisContext ctx = assembler.assemble(request.getTaskId(), traceId);

            // 3. 已完成任务不分析
            if (ctx.isTerminal()) {
                return buildTerminalResponse(ctx, traceId);
            }

            // 4. 规则引擎
            DiagnoseResponse rule = ruleEngine.evaluate(ctx);
            if (rule != null) {
                rule.setDiagnosisMode("rule");
                rule.setConfidence(properties.getConfidence().getRuleMatch());
                rule.setTraceId(traceId);
                return rule;
            }

            // 5. LLM 兜底（可选）
            if (properties.isEnableLlmFallback()) {
                try {
                    DiagnoseResponse llm = llmEngine.diagnose(ctx);
                    llm.setDiagnosisMode("llm");
                    llm.setConfidence(properties.getConfidence().getLlmMatch());
                    llm.setTraceId(traceId);
                    return llm;
                } catch (Exception e) {
                    log.warn("LLM 诊断失败, 降级 fallback", e);
                }
            }

            // 6. 完全兜底
            return buildFallbackResponse(ctx, traceId);
        } finally {
            MDC.remove("traceId");
        }
    }
    
    private DiagnoseResponse buildTerminalResponse(DiagnosisContext ctx, String traceId) {
        DiagnoseResponse r = new DiagnoseResponse();
        r.setSummary("任务已处于终态（" + ctx.getTask().getTaskState() + "），无需诊断");
        r.setDiagnosisMode("rule");
        r.setConfidence(1.0);
        r.setTraceId(traceId);
        return r;
    }
    
    private DiagnoseResponse buildFallbackResponse(DiagnosisContext ctx, String traceId) {
        DiagnoseResponse r = new DiagnoseResponse();
        r.setSummary("AI 无法分析此任务，建议人工介入");
        r.setDiagnosisMode("fallback");
        r.setConfidence(properties.getConfidence().getFallback());
        r.setTraceId(traceId);
        return r;
    }
}
```

---

## 9. REST 入口

```java
@RestController
@RequestMapping("/api/v1/diagnosis")
@RequiredArgsConstructor
public class DiagnosisController {
    private final DiagnosisFacade facade;

    @PostMapping("/analyze")
    public Response<DiagnoseResponse> analyze(@RequestBody @Valid DiagnoseRequest request) {
        return BaseResponse.success(facade.diagnose(request));
    }
}
```

只有这一个接口。

---

## 10. 配置文件布局

### 10.1 application.yml（Spring 框架+外部服务连接）

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

springdoc:
  api-docs:
    enabled: true
  swagger-ui:
    enabled: true
```

**删除**：datasource（不需要数据库）、spring.servlet.multipart（不处理文件）、ai.retry（移到 diagnosis-core.yml）、map.api.*（保留但说明用途）

### 10.2 diagnosis-core.yml（诊断工具自身配置）

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

### 10.3 diagnosis-rules.yml（规则配置）

见 6.3。

### 10.4 prompts/ 目录

```
prompts/
├── system-role.txt
├── business-context.txt
├── decision-tree.txt
└── output-format.txt
```

纯文本文件，运维可以直接编辑。

---

## 11. 代码改造清单

### 11.1 删除（直接 rm）

| 目录/文件 | 原因 |
|---|---|
| `platform/*`（整个子包，17 个文件） | 上一轮错误设计，完全不要 |
| `experiment/*` | 实验代码 |
| `tool/*` | Agent 工具，不走 Agent 模式 |
| `service/AgentChatService.java` | Agent 聊天，不做 |
| `service/DiagnosisStreamService.java` | 流式诊断，MVP 不做 |
| `service/GlobalDiagnosisService.java` / `GlobalDiagnosisContextAssembler.java` / `GlobalDiagnosisContext.java` | 任务组全局诊断，用 `PlatformTaskGroup` 接进 Context 替代 |
| `service/BlockageAnalyzer.java` | 旧概念，不需要 |
| `service/BM25SearchService.java` / `HybridSearchService.java` / `DocSearchService.java` | 知识库检索 MVP 不做，后续需要时再加 |
| `service/LlmDiagnosisEngine.java` 里硬编码的 Prompt | 移到 `prompts/` 目录 |
| `controller/AgentChatController.java` / `DiagnosisStreamController.java` / `DocumentEtlController.java` / `ExperimentController.java` | MVP 不提供 |
| `rule/rules/*`（13 条旧规则） | 基于老状态机 handleState，全部重写 |
| `rule/DiagnosisContext.java` | 字段基于老模型，用新的替代 |
| `dto/TaskDetail.java` / `dto/RelevantDoc.java` / `dto/PointConflict.java` / `dto/ResourceBottleneck.java` / `dto/TaskRelationEdge.java` / `dto/TaskRelationSnapshot.java` | 用 `client/dto/Platform*` 替代 |
| `client/HttpTaskClient.java` / `JdbcTaskClient.java` / `MockTaskClient.java` / `TaskClient.java` / `LogClient.java` / `HttpLogClient.java` / `JdbcLogClient.java` / `MockLogClient.java` | 重写为 `PlatformClient` / `HttpPlatformClient` |

### 11.2 保留（不动或微调）

| 目录/文件 | 备注 |
|---|---|
| `AIDiagnosisApplication.java` | 启动类 |
| `common/BaseResponse.java` / `Response.java` | 通用响应 |
| `exception/*` | 异常处理 |
| `dto/DiagnoseRequest.java` | 字段精简到只保留 taskId（或保留 scope 但 ignore） |
| `dto/DiagnoseResponse.java` | 字段保持，去掉 relevantDocs、relationSnapshot、analysisScope |
| `dto/RootCauseItem.java` | 保留 |
| `config/RestTemplateConfig.java` / `ModelConfig.java` / `EnvConfig.java` | 保留 |
| `rule/DiagnoseRule.java` | 接口不变 |
| `rule/RuleEngine.java` / `ConfigurableRuleEngine.java` | 保留（ConfigurableRuleEngine 改造后不调用已删除的 `RuleEngineSnapshotService` 等持久化类） |
| `rule/ExpressionRule.java` | 保留（表达式规则基础设施） |
| `rule/RuleStatistics.java` | 保留（但只存内存，不落库） |
| `client/LlmClient.java` / `SpringAiLlmClient.java` | LLM 客户端 |
| `controller/MapProxyController.java` | 保留（如果前端 UI 依赖地图） |
| `controller/RuleManageController.java` / `EnvController.java` | 保留（运维查看规则/环境） |

### 11.3 改造（重点改造文件）

| 文件 | 改造内容 |
|---|---|
| `rule/AbstractDiagnoseRule.java` | 删除 `resolveTemplate` 里针对老字段（handleState、taskItemCount 等）的变量；改为新版变量（taskState、stuckSeconds、runningItemCount 等） |
| `service/RuleEngineBootstrapService.java` / `RuleEngineSnapshotService.java` / `RulePriorityService.java` / `RuleStatisticsService.java` / `ExpressionRuleRegistryService.java` | **直接删除**（不是改造）。`ConfigurableRuleEngine` 中对这些类的依赖一并移除，统计功能用内存 `ConcurrentHashMap<String, AtomicLong>` 简单替代 |
| `service/DiagnosisFacade.java` | 重写编排逻辑（见第 8 节） |
| `service/DiagnosisContextAssembler.java` | 重写为从 PlatformClient 组装新版 Context |
| `service/LlmDiagnosisEngine.java` | 适配新 Context；Prompt 从 `PromptTemplateLoader` 读取 |
| `service/DiagnosisResponseMapper.java` | 适配新 DiagnoseResponse 字段 |
| `service/DiagnosisService.java` | 作为 `@Deprecated` 包装保留一段时间（兼容旧前端）或直接删除 |
| `controller/DiagnosisController.java` | 只保留 `POST /analyze`，其他方法删除 |
| `config/DiagnosisPromptProperties.java` | 删除（Prompt 走文件，不用 properties） |
| `config/AgentConfig.java` / `AgentProperties.java` / `ChatMemoryConfig.java` / `VectorStoreConfig.java` | 删除（不做 Agent / RAG） |

### 11.4 新增

| 文件/目录 | 用途 |
|---|---|
| `facade/DiagnosisFacade.java` | 唯一编排入口 |
| `client/PlatformClient.java` + `HttpPlatformClient.java` | 平台 HTTP 访问 |
| `client/dto/Platform*.java`（5 个） | 平台返回 DTO |
| `client/EnumReverseMapper.java` | 中文枚举 → code 反向映射 |
| `context/DiagnosisContext.java` | 新版诊断上下文 |
| `context/DiagnosisContextAssembler.java` | 新版组装器 |
| `rule/rules/*`（11 条新规则） | 基于新版状态机 |
| `llm/PromptTemplateLoader.java` | 加载 prompts/ 模板 |
| `config/PlatformProperties.java` | `loong.platform.*` 绑定 |
| `config/DiagnosisProperties.java`（改造现有） | `loong.ai.diagnosis.*` 绑定，字段精简 |
| `src/main/resources/prompts/*.txt` | Prompt 模板 |
| `src/main/resources/diagnosis-rules.yml` | 规则配置 |
| `src/main/resources/diagnosis-core.yml` | 诊断核心配置 |

---

## 12. 分阶段落地计划

### M1：清理与骨架（可验收：编译通过）

1. 删除所有 11.1 列出的文件/目录
2. 建立新包结构（空目录 + README）
3. 新建 `DiagnoseRequest/Response` 最小版本（只保留必需字段）
4. `DiagnosisController` 只保留 `POST /analyze` 返回占位响应
5. `mvn compile` 通过；启动应用不报错

### M2：数据获取层（可验收：能拉到平台数据）

1. 实现 `PlatformClient` 接口
2. 实现 `HttpPlatformClient`：调通 `queryTaskByTaskNo`、`getTaskItemDetails`、`getTasksByGroupCode`
3. 实现 `EnumReverseMapper`
4. 写一个手工测试：给一个真实 taskNo，日志打印拉到的 PlatformTask + PlatformTaskItem + PlatformCommand

### M3：Context 与规则引擎（可验收：至少 1 条规则生效）

1. 实现 `DiagnosisContext` + `DiagnosisContextAssembler`
2. 改造 `ConfigurableRuleEngine`，去掉落库依赖
3. 实现 `WaitSplitStuckRule`（最典型场景）+ `FallbackRule`
4. 接 `DiagnosisFacade`：请求 → 组装 → 规则 → 返回
5. 手工测试：构造一个 WAIT_SPLIT 卡住的任务，能命中规则返回预期结论

### M4：规则全量铺开（可验收：10 条规则都能触发）

为 MVP 10 条规则中剩下的 9 条分别写 Bean + YAML 配置，每条写一个简单的断言测试（Mock PlatformClient 返回数据，验证 `RuleEngine.evaluate()` 结果）。

### M5：LLM 兜底（可验收：未命中时走 LLM 返回结果）

1. 实现 `PromptTemplateLoader`
2. 写 4 个 prompt 文件（基于新版业务背景和决策树）
3. 改造 `LlmDiagnosisEngine` 使用新 Prompt 和 Context
4. `DiagnosisFacade` 接入 LLM 兜底
5. 手工测试：构造一个不在规则覆盖范围的情况（比如 `task_state=RUNNING` 但没有子任务，这是异常情况），走 LLM

### M6：前端联调（可验收：Tauri UI 能触发并展示结果）

1. 确认前端调用路径（`/api/v1/diagnosis/analyze`）和响应格式
2. 联调：前端输入 taskNo → 触发诊断 → 展示结果
3. 验证三种路径都能正常展示：rule / llm / fallback

---

## 13. 非功能要求

### 13.1 性能

- 单次诊断 P95 < 2s（规则路径），< 8s（LLM 路径）
- HTTP 调平台合并并发：`queryTask` 和 `queryTaskItemBundle` 可以并行请求

### 13.2 日志

每次诊断日志打印：
- 请求行：`DIAG_START traceId=xxx taskId=xxx`
- 平台调用：`PLATFORM_CALL traceId=xxx api=queryTask elapsedMs=xxx`
- 规则命中：`RULE_MATCHED traceId=xxx rule=wait-split-stuck`
- 结果行：`DIAG_END traceId=xxx mode=rule|llm|fallback elapsedMs=xxx`

MDC 用 `traceId` 贯穿。

### 13.3 安全

- LLM API Key 走环境变量，不提交仓库
- 平台返回的错误信息直接透传给前端（前端是运维内部使用，不对外）

### 13.4 熔断降级

- 平台接口不可达：直接抛 `PLATFORM_UNAVAILABLE`，前端展示友好提示
- LLM 超时/异常：自动降级 fallback，不阻断请求

---

## 14. 不在 MVP 范围的

- 知识库检索（BM25 / 向量 / 混合检索）
- 历史案例召回
- 任务组的全局关联分析（复杂拓扑诊断）
- 流式诊断（SSE）
- 诊断规则的运行时管理 UI（目前规则走 YAML 热重载）
- 诊断历史记录
- 多任务批量诊断

这些留到 M7+ 阶段。

---

## 15. 开发起步指南

按这个顺序动手最快：

1. **先删**：把 11.1 的所有文件删干净，让工程只剩 MVP 需要的部分（敢删，Git 可追回）
2. **再搭骨架**：新建空的 `facade/`、`client/`、`context/` 包和 `DiagnosisFacade` 占位实现
3. **通数据**：写 `HttpPlatformClient`，用真实 loong-platform 调通一个 taskNo 的数据查询
4. **跑规则**：写通 `WaitSplitStuckRule` 一条，验证规则命中路径
5. **上 LLM**：写通 LLM 兜底路径
6. **铺规则**：剩下 9 条规则批量铺开
7. **接前端**：联调 UI

---

## 16. 对原两份文档的态度

- `loong-ai-diagnosis-可落地设计文档.md`：**归档**，所有实际实施都以本文档为准
- `loong-ai-diagnosis-实施计划.md`：**归档**，同上
- `loong-ai-diagnosis-落地设计-v2.md`：**归档**，同上

这三份文档不作为开发依据，避免并存多个源头造成歧义。
