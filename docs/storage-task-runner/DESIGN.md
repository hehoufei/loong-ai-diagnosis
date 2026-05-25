# 立库库位循环任务测试 设计文档

> 项目：`loong-ai-diagnosis`
> 模块：立库库位循环测试 (storage-task-runner)
> 目的：在 UI 上控制 ACS 立库巷道的入库 → 多次移库 → 出库 循环测试，可暂停、可继续、可跳过卡住任务、可断点续跑。

---

## 1. 总览

### 1.1 业务目标

按规则生成立库 8 巷道（可扩展其它巷道）的库位组合，循环下发以下任务到调度服务（loong-platform / scheduler）：

```
入库 (N2S)  ND_11025 → 库位[i]
移库 (S2S)  库位[i]  → 库位[i+1]    （重复 10 次）
出库 (S2N)  库位[末] → ND_11025
```

每完成一个任务必须等到 `sc_task.task_state` 进入终态才下发下一个。终态分两类：
- **可继续**：`SUCCESS`、`MANUAL_SUCCESS`、`CANCEL/CANCELED/CANCELLED`
- **需人工**：`FAIL/FAILED`，自动暂停等待人工

如果某个任务长时间没有终态（默认 30 分钟），UI 标记为 *卡住*，用户可以：
- 在 ACS 系统手动处理掉，让 `task_state` 变成 `MANUAL_SUCCESS` 或 `CANCEL` → 程序会自动识别并继续
- 或在 UI 直接点 *跳过当前*，记录为 `SKIPPED` 后强行进入下一步

### 1.2 关键能力

| 能力 | 说明 |
| --- | --- |
| 暂停 / 继续 | RUNNING ↔ PAUSED；继续时从中断处接着发下一步 |
| 跳过当前 | 任意状态都可跳过当前未完成任务 |
| 重置 | 清空进度（保留配置） |
| 配置可视化 | 巷道、层、抽样比、入库口、容器/货物、轮询间隔等都在 UI 编辑 |
| 重新生成有效库位 | 按规则生成 + DB 校验 |
| 断点续跑 | 进程重启后状态从磁盘恢复，自动进入 PAUSED，等用户点继续 |
| 历史记录 | 最近 200 条任务（任务号、起终点、状态、耗时、卡住标记） |

### 1.3 落地范围

- 后端：新增 1 个 controller、1 个 runner、1 个 generator、1 个 db 客户端、1 个 acs 客户端、3 个 dto
- 前端：新增 1 个 `static/storage-task.html`，挂到顶部导航
- 不引入新依赖（`HttpURLConnection` + `DriverManager`，与现有 Spring Boot 3.4 / Java 17 兼容）
- 持久化文件：
  - `~/.loong-ai-diagnosis/storage-task-config.json`
  - `~/.loong-ai-diagnosis/storage-task-state.json`

---

## 2. 目录结构

```
loong-ai-diagnosis/src/main/java/cn/aimstek/loong/aidiag/
├── controller/
│   └── StorageTaskController.java          REST 接口
└── storagetask/
    ├── StorageTaskRunner.java              核心调度执行器（单线程循环）
    ├── StorageCodeGenerator.java           库位编码生成
    ├── StorageDb.java                      MySQL 短连接（map_storage_area / sc_task）
    ├── StorageAcsClient.java               POST /task/addTask
    └── dto/
        ├── StorageTaskConfig.java          全局配置
        ├── StorageTaskRunnerState.java     运行时状态 + 历史
        └── StorageTaskRecord.java          单条任务记录

loong-ai-diagnosis/src/main/resources/static/
└── storage-task.html                       前端页面（待写）
```

文件级别独立，与现有 `controller`、`client`、`config` 包不耦合，方便后续合并/拆分。


---

## 3. 库位编码规则

### 3.1 编码格式

```
{prefix}{aisle:02d}-{side}-{depth:02d}-{row:02d}-{col:04d}-{layer:02d}
```

| 段 | 含义 | 取值 |
| --- | --- | --- |
| prefix | 固定前缀 | `SL_-WH_001-SA_HSMD_1-AL_L` |
| aisle | 巷道编号，2 位 | 当前测 `08`；可扩展 |
| side | 侧 | `L`（左） / `R`（右） |
| depth | 深度，2 位 | `01`=近伸；`02`=远伸 |
| row | 排号，2 位 | L 侧只有 `01,03`；R 侧只有 `02,04` |
| col | 列号，4 位 | `0001 ~ 0064` |
| layer | 层号，2 位 | `01 ~ 10` |

> row 与 depth 的固定映射：`row 01,02 → depth 01`，`row 03,04 → depth 02`。
> 也就是：从巷道由近到远，行号依次为 `02、01`（L 侧）/ `01、02`（R 侧）/ `03、04`，靠近巷道的是近伸。
> 程序内 `ROW_DEPTH_MAP = {1→1, 2→1, 3→2, 4→2}`。

示例：
```
SL_-WH_001-SA_HSMD_1-AL_L08-L-01-01-0003-03   8 巷道左侧近伸 row01 第 3 列 第 3 层
SL_-WH_001-SA_HSMD_1-AL_L08-R-02-04-0010-10   8 巷道右侧远伸 row04 第 10 列 第 10 层
```

### 3.2 抽样规则

- **全测层**：第 1 层和第 10 层（最高层），全部 64 列
- **其他层**：随机抽样 50%（`SAMPLE_RATIO=0.5`）
- 使用固定随机种子（默认 `20260525`），结果可复现，便于复测同一组库位
- 实现：`java.util.Collections.shuffle(list, new Random(seed))`，取前 `round(maxCol * ratio)` 列

> 程序入口：`StorageCodeGenerator.generate(config)`
> 输出：候选库位列表（按 巷道 → 层 → 侧 → row → col 顺序）

### 3.3 数据库存在性校验

并不是所有规则生成的编码在数据库里都真实存在，必须过滤一次：

```sql
SELECT storage_area_code
FROM map_storage_area
WHERE storage_area_code IN (?, ?, ?, ...)
  AND activate = 'ON'
  AND delete_flag = 0;
```

- 分批 IN 查询，单次 500 个，避免参数过多
- 保留生成顺序（用 `LinkedHashSet`/列表顺序映射）
- 结果存入 `state.validCodes`，后续循环全部基于这份列表


---

## 4. 状态机

### 4.1 总体状态

```
       start
IDLE  ───────►  RUNNING ──── pause ───►  PAUSED
  ▲                │                       │
  │ reset          │ FAIL/error            │ start (resume)
  │                ▼                       │
  └────────────  ERROR  ◄──────────────────┘
                                 skip / start
```

| Status | 含义 |
| --- | --- |
| IDLE | 空闲，未启动或已 reset |
| RUNNING | 工作线程正在轮询/下发任务 |
| PAUSED | 已暂停，工作线程已退出，状态全部保留在内存+磁盘 |
| FINISHED | 预留：当前没有终止条件，循环是无限的，暂时不会用到 |
| ERROR | addTask 调用失败、轮询超时手动失败等不可继续场景 |

### 4.2 主循环伪代码

`StorageTaskRunner.runLoop()` 核心逻辑：

```java
while (!stopRequested) {
    if (pauseRequested) { setStatus(PAUSED); return; }

    // 1) 进入新一轮（如果上一轮刚结束）
    if (currentStepInRound == 0 && currentTask == null) {
        currentRound++;
        currentCursor = currentStartIdx;
        currentHoldPosition = null;
    }

    // 2) 计算下一步要执行什么任务
    StepPlan plan = planNextStep();   // 返回 N2S/S2S/S2N + 起终点
    if (plan == null) {               // 一轮结束
        currentStartIdx = (currentCursor + 1) % validCodes.size();
        currentStepInRound = 0;
        continue;
    }

    // 3) 下发任务（如果当前还没有 currentTask）
    if (currentTask == null) {
        currentTask = newRecord(plan);   // 生成 taskNo、记录起终点
        totalIssued++;
        try {
            acs.addTask(...);
        } catch (Exception e) {
            currentTask.state = "ADD_FAILED";
            archive(); setStatus(ERROR); return;
        }
    }

    // 4) 轮询直到终态
    String final = pollUntilTerminal();
    switch (final) {
        case "__PAUSE__": setStatus(PAUSED); return;
        case "__SKIP__":
            currentTask.state = "SKIPPED";
            archive(); advanceStep(); continue;
        case "FAIL", "FAILED":
            totalFailed++;
            archive(); setStatus(PAUSED); errorMessage = "..."; return;
        default:   // SUCCESS / MANUAL_SUCCESS / CANCEL...
            totalSuccess++ / totalManualSuccess++ / totalCanceled++;
            archive(); advanceStep();
    }
}
```

`pollUntilTerminal()`：

```java
while (true) {
    if (stopRequested) return "__PAUSE__";
    if (skipCurrentRequested) { skipCurrentRequested=false; return "__SKIP__"; }
    if (pauseRequested) return "__PAUSE__";

    String dbState = db.queryTaskState(rec.taskNo);
    rec.dbTaskState = dbState;
    if (dbState != null) {
        String s = dbState.toUpperCase();
        if (s in {SUCCESS, MANUAL_SUCCESS, CANCEL, CANCELED, CANCELLED}) return s;
        if (s in {FAIL, FAILED}) return s;
    }

    // 卡住标记（不会中止循环，只是给 UI 看）
    if (elapsed > taskStuckThresholdSeconds * 1000) rec.stuck = true;

    pauseLock.wait(pollIntervalSeconds * 1000);
}
```

### 4.3 步骤推进 advanceStep

每完成一个任务后，根据当前是入库 / 移库 / 出库，推进 `currentCursor`、`currentHoldPosition`、`currentStepInRound`：

| 当前 step | step 后动作 |
| --- | --- |
| `0`（入库） | `currentCursor = startIdx`, `currentHoldPosition = codes[startIdx]` |
| `1..N`（移库） | `currentCursor = (currentCursor+1) % size`，跳过相同库位；`currentHoldPosition = codes[currentCursor]` |
| `N+1`（出库） | `currentHoldPosition = null` |

一轮结束后：
```
currentStartIdx = (currentCursor + 1) % size;
currentStepInRound = 0;
currentTask = null;
```

> **顺序滚动**：每一轮的入库点比上一轮顺延 1 位，避免反复使用同一个库位。

### 4.4 信号量与并发

- 工作线程：`Thread workerThread`（单线程，`storage-task-runner`）
- 控制信号都是 `volatile boolean`：`pauseRequested / skipCurrentRequested / stopRequested`
- 阻塞唤醒：`Object pauseLock` + `wait/notifyAll`
- Controller 与 Runner 之间所有公共方法都是 `synchronized`，避免状态写入时并发冲突


---

## 5. 数据模型

### 5.1 StorageTaskConfig（全局配置，可在 UI 编辑）

| 字段 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| dbUrl | String | `jdbc:mysql://localhost:3306/loong-platform?...` | 数据库 JDBC URL |
| dbUsername | String | `root` | DB 账号 |
| dbPassword | String | `root` | DB 密码 |
| acsAddTaskUrl | String | `http://10.15.58.249:8088/task/addTask` | 调度服务 addTask 地址 |
| httpTimeoutSeconds | int | `15` | HTTP 连接 + 读取超时 |
| storagePrefix | String | `SL_-WH_001-SA_HSMD_1-AL_L` | 库位编码前缀（一般不改） |
| aisles | List&lt;Integer&gt; | `[8]` | 要测的巷道列表 |
| totalLayers | int | `10` | 总层数 |
| maxCol | int | `64` | 最大列数 |
| fullTestLayers | List&lt;Integer&gt; | `[1, 10]` | 全测的层 |
| sampleRatio | double | `0.5` | 非全测层抽样比例 |
| randomSeed | Long | `20260525` | 随机种子（null=每次随机） |
| inboundStartNode | String | `ND_11025` | 入库口/出库口节点 |
| taskSource | String | `WMS` | 任务来源 |
| taskBizType | String | `默认` | 业务类型 |
| containerCode | String | `C_1224` | 容器号（固定） |
| goodsCode | String | `GS_1223` | 货物号（固定） |
| shuffleTimesPerRound | int | `10` | 每轮移库次数 |
| pollIntervalSeconds | int | `3` | DB 状态轮询间隔（秒） |
| taskStuckThresholdSeconds | long | `1800` | 任务超过该时长仍未终态 → 标记为卡住 |
| taskNoPrefix | String | `ZDYNDTASK_` | 任务号前缀 |

> 任务号 = `taskNoPrefix + yyyyMMddHHmmssSSS + "_" + 序号(4位)`，例如 `ZDYNDTASK_20260525143012345_0007`。

### 5.2 StorageTaskRecord（单个任务记录）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| seq | long | 全局递增序号 |
| round | int | 第几轮 |
| step | String | 步骤标签：`入库` / `移库 1/10` / ... / `出库` |
| taskNo | String | 任务号 |
| taskType | String | `N2S` / `S2S` / `S2N` |
| startNode | String | 起点 |
| endNode | String | 终点 |
| submittedAt | String | 下发时间 `yyyy-MM-dd HH:mm:ss` |
| finishedAt | String | 完成时间（终态/跳过时填） |
| state | String | 程序内状态：`ISSUED / SUCCESS / MANUAL_SUCCESS / CANCEL / FAIL / SKIPPED / ADD_FAILED / PAUSED` |
| dbTaskState | String | 实时从 `sc_task` 查到的原始 task_state |
| remark | String | 备注/错误信息 |
| stuck | boolean | 是否已超过卡住阈值 |

### 5.3 StorageTaskRunnerState（运行时状态）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| status | enum | `IDLE / RUNNING / PAUSED / FINISHED / ERROR` |
| errorMessage | String | 错误信息（status=ERROR 或 PAUSED 自动暂停时填） |
| validCodes | List&lt;String&gt; | 数据库中存在的有效库位 |
| currentRound | int | 当前轮次 |
| currentStepInRound | int | 当前轮步骤索引 0..N+1 |
| currentStartIdx | int | 当前轮起始库位下标 |
| currentCursor | int | 滚动指针 |
| currentHoldPosition | String | 当前持有货物的库位 |
| currentTask | StorageTaskRecord | 当前未完成的任务 |
| globalSeq | long | 全局序号 |
| recentTasks | List&lt;Record&gt; | 最近 200 条任务（倒序） |
| totalIssued | long | 已下发数 |
| totalSuccess | long | SUCCESS 终态数 |
| totalManualSuccess | long | MANUAL_SUCCESS 终态数 |
| totalCanceled | long | CANCEL 终态数 |
| totalFailed | long | FAIL 终态数 |

### 5.4 持久化文件

| 文件 | 内容 |
| --- | --- |
| `~/.loong-ai-diagnosis/storage-task-config.json` | StorageTaskConfig（每次 PUT /config 后写一次） |
| `~/.loong-ai-diagnosis/storage-task-state.json` | StorageTaskRunnerState（每次状态变更/任务推进都写一次） |

> 程序启动时若 `state.status == RUNNING`，自动改成 `PAUSED`，等待用户点继续，避免重启后一边消费旧状态一边出现并发问题。


---

## 6. REST API

统一前缀 `/api/v1/storage-task`，统一返回 `Response<T>`（与项目其他接口一致）：

```json
{ "success": true, "code": "SUCCESS", "message": "ok", "data": ... }
```

### 6.1 GET /api/v1/storage-task/config

获取当前配置。

返回：`Response<StorageTaskConfig>`

### 6.2 PUT /api/v1/storage-task/config

更新配置。**仅在非 RUNNING 状态下允许**，避免运行中改坏关键参数。

请求体：完整 `StorageTaskConfig`（前端获取后改字段再 PUT）

返回：`Response<Void>`

错误：`code=INVALID_STATE` 当 status=RUNNING

### 6.3 GET /api/v1/storage-task/state

获取运行时状态（前端轮询用）。

返回：`Response<StorageTaskRunnerState>`

> 前端建议轮询频率：1~2 秒。状态变化频繁，但每次返回数据不超过 ~50KB（含最近 200 条任务）。

### 6.4 POST /api/v1/storage-task/start

启动或继续。
- 状态为 IDLE/FINISHED：从头开始。先调 regenerateValidCodes，重置进度
- 状态为 PAUSED/ERROR：从中断处继续

返回：`Response<Void>`

错误：`code=INVALID_STATE` 当 status=RUNNING

### 6.5 POST /api/v1/storage-task/pause

请求暂停。工作线程会在轮询循环或下一次 wait 唤醒时检测到，退出并设置 status=PAUSED。

返回：`Response<Void>`

### 6.6 POST /api/v1/storage-task/skip

跳过当前未完成任务。
- RUNNING：发信号给工作线程，标记 SKIPPED，advanceStep
- PAUSED / ERROR：直接归档当前任务并 advanceStep（不会自动启动）

返回：`Response<Void>`

错误：`code=INVALID_STATE` 当 status=IDLE 且无 currentTask

### 6.7 POST /api/v1/storage-task/reset

清空所有进度（保留配置）。要求当前不在 RUNNING。

返回：`Response<Void>`

错误：`code=INVALID_STATE` 当 status=RUNNING

### 6.8 POST /api/v1/storage-task/regenerate-codes

按当前配置重新生成候选库位 + DB 校验，写入 `state.validCodes`。

返回：`Response<Integer>`（有效库位数量）

### 6.9 POST /api/v1/storage-task/test-db

测试数据库连接（执行 `SELECT 1`）。

返回：`Response<Void>`

错误：`code=DB_ERROR` 当连接失败

### 6.10 接口调用样例（curl）

```bash
# 获取配置
curl http://localhost:18080/api/v1/storage-task/config

# 修改 8 巷道为 [8, 9]
curl -X PUT http://localhost:18080/api/v1/storage-task/config \
  -H "Content-Type: application/json" \
  -d '{ "aisles":[8,9], "...": "..." }'

# 启动
curl -X POST http://localhost:18080/api/v1/storage-task/start

# 暂停
curl -X POST http://localhost:18080/api/v1/storage-task/pause

# 跳过当前
curl -X POST http://localhost:18080/api/v1/storage-task/skip

# 实时拉状态
curl http://localhost:18080/api/v1/storage-task/state
```


---

## 7. 后端代码（已落地，可直接复制）

> 已落地到工程：`loong-ai-diagnosis/src/main/java/cn/aimstek/loong/aidiag/storagetask/*`、`controller/StorageTaskController.java`。
> 不需要新增 Maven 依赖，使用现有 Spring Boot 3.4 / Java 17 / Jackson / Lombok。

### 7.1 dto/StorageTaskConfig.java

```java
package cn.aimstek.loong.aidiag.storagetask.dto;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class StorageTaskConfig {
    // 数据库
    private String dbUrl = "jdbc:mysql://localhost:3306/loong-platform"
            + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false";
    private String dbUsername = "root";
    private String dbPassword = "root";
    // ACS
    private String acsAddTaskUrl = "http://10.15.58.249:8088/task/addTask";
    private int httpTimeoutSeconds = 15;
    // 编码规则
    private String storagePrefix = "SL_-WH_001-SA_HSMD_1-AL_L";
    private List<Integer> aisles = new ArrayList<>(List.of(8));
    private int totalLayers = 10;
    private int maxCol = 64;
    private List<Integer> fullTestLayers = new ArrayList<>(List.of(1, 10));
    private double sampleRatio = 0.5;
    private Long randomSeed = 20260525L;
    // 任务参数
    private String inboundStartNode = "ND_11025";
    private String taskSource = "WMS";
    private String taskBizType = "默认";
    private String containerCode = "C_1224";
    private String goodsCode = "GS_1223";
    // 循环 / 轮询
    private int shuffleTimesPerRound = 10;
    private int pollIntervalSeconds = 3;
    private long taskStuckThresholdSeconds = 30L * 60L;
    private String taskNoPrefix = "ZDYNDTASK_";
}
```

### 7.2 dto/StorageTaskRecord.java

```java
package cn.aimstek.loong.aidiag.storagetask.dto;

import lombok.Data;

@Data
public class StorageTaskRecord {
    private long seq;
    private int round;
    private String step;
    private String taskNo;
    private String taskType;
    private String startNode;
    private String endNode;
    private String submittedAt;
    private String finishedAt;
    private String state;
    private String dbTaskState;
    private String remark;
    private boolean stuck;
}
```

### 7.3 dto/StorageTaskRunnerState.java

```java
package cn.aimstek.loong.aidiag.storagetask.dto;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class StorageTaskRunnerState {
    public enum Status { IDLE, RUNNING, PAUSED, FINISHED, ERROR }

    private Status status = Status.IDLE;
    private String errorMessage;
    private List<String> validCodes = new ArrayList<>();
    private int currentRound;
    private int currentStepInRound;
    private int currentStartIdx;
    private int currentCursor;
    private String currentHoldPosition;
    private StorageTaskRecord currentTask;
    private long globalSeq;
    private List<StorageTaskRecord> recentTasks = new ArrayList<>();
    private long totalIssued;
    private long totalSuccess;
    private long totalManualSuccess;
    private long totalCanceled;
    private long totalFailed;
}
```


### 7.4 StorageCodeGenerator.java

```java
package cn.aimstek.loong.aidiag.storagetask;

import cn.aimstek.loong.aidiag.storagetask.dto.StorageTaskConfig;
import java.util.*;

public final class StorageCodeGenerator {

    private static final Map<String, int[]> SIDE_ROW_MAP = Map.of(
            "L", new int[]{1, 3},
            "R", new int[]{2, 4}
    );
    private static final Map<Integer, Integer> ROW_DEPTH_MAP = Map.of(
            1, 1, 2, 1, 3, 2, 4, 2
    );

    private StorageCodeGenerator() {}

    public static String buildCode(StorageTaskConfig cfg, int aisle, String side, int row, int col, int layer) {
        int depth = ROW_DEPTH_MAP.getOrDefault(row, 1);
        return String.format("%s%02d-%s-%02d-%02d-%04d-%02d",
                cfg.getStoragePrefix(), aisle, side, depth, row, col, layer);
    }

    public static List<String> generate(StorageTaskConfig cfg) {
        Random random = (cfg.getRandomSeed() != null) ? new Random(cfg.getRandomSeed()) : new Random();
        List<String> codes = new ArrayList<>();
        List<Integer> aisles = cfg.getAisles() == null ? List.of() : cfg.getAisles();
        List<Integer> fullLayers = cfg.getFullTestLayers() == null ? List.of() : cfg.getFullTestLayers();

        for (Integer aisle : aisles) {
            for (int layer = 1; layer <= cfg.getTotalLayers(); layer++) {
                boolean fullTest = fullLayers.contains(layer);
                for (Map.Entry<String, int[]> entry : SIDE_ROW_MAP.entrySet()) {
                    String side = entry.getKey();
                    for (int row : entry.getValue()) {
                        List<Integer> chosen = pickColumns(random, cfg.getMaxCol(), fullTest, cfg.getSampleRatio());
                        for (int col : chosen) {
                            codes.add(buildCode(cfg, aisle, side, row, col, layer));
                        }
                    }
                }
            }
        }
        return codes;
    }

    private static List<Integer> pickColumns(Random random, int maxCol, boolean fullTest, double sampleRatio) {
        List<Integer> all = new ArrayList<>(maxCol);
        for (int c = 1; c <= maxCol; c++) all.add(c);
        if (fullTest) return all;
        int sampleN = Math.max(1, (int) Math.round(maxCol * sampleRatio));
        Collections.shuffle(all, random);
        List<Integer> chosen = new ArrayList<>(all.subList(0, sampleN));
        Collections.sort(chosen);
        return chosen;
    }
}
```

### 7.5 StorageDb.java

```java
package cn.aimstek.loong.aidiag.storagetask;

import cn.aimstek.loong.aidiag.storagetask.dto.StorageTaskConfig;
import lombok.extern.slf4j.Slf4j;

import java.sql.*;
import java.util.*;

@Slf4j
public class StorageDb {

    private final StorageTaskConfig cfg;

    public StorageDb(StorageTaskConfig cfg) { this.cfg = cfg; }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(cfg.getDbUrl(), cfg.getDbUsername(), cfg.getDbPassword());
    }

    public void ping() throws SQLException {
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement("SELECT 1")) {
            ps.executeQuery();
        }
    }

    public List<String> filterExistingCodes(List<String> codes) {
        if (codes == null || codes.isEmpty()) return Collections.emptyList();
        Set<String> existing = new HashSet<>();
        int batch = 500;
        try (Connection conn = open()) {
            for (int i = 0; i < codes.size(); i += batch) {
                List<String> sub = codes.subList(i, Math.min(i + batch, codes.size()));
                StringBuilder sb = new StringBuilder(
                        "SELECT storage_area_code FROM map_storage_area "
                                + "WHERE activate='ON' AND delete_flag=0 AND storage_area_code IN (");
                for (int j = 0; j < sub.size(); j++) { if (j > 0) sb.append(','); sb.append('?'); }
                sb.append(')');
                try (PreparedStatement ps = conn.prepareStatement(sb.toString())) {
                    for (int j = 0; j < sub.size(); j++) ps.setString(j + 1, sub.get(j));
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) existing.add(rs.getString(1));
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询 map_storage_area 失败: " + e.getMessage(), e);
        }
        List<String> result = new ArrayList<>(codes.size());
        for (String c : codes) if (existing.contains(c)) result.add(c);
        return result;
    }

    public String queryTaskState(String taskNo) {
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT task_state FROM sc_task WHERE task_no = ? ORDER BY create_time DESC LIMIT 1")) {
            ps.setString(1, taskNo);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        } catch (SQLException e) {
            log.warn("查询 sc_task 状态失败 taskNo={}: {}", taskNo, e.getMessage());
        }
        return null;
    }
}
```

### 7.6 StorageAcsClient.java

```java
package cn.aimstek.loong.aidiag.storagetask;

import cn.aimstek.loong.aidiag.storagetask.dto.StorageTaskConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Slf4j
public class StorageAcsClient {

    private final StorageTaskConfig cfg;
    private final ObjectMapper mapper;

    public StorageAcsClient(StorageTaskConfig cfg, ObjectMapper mapper) {
        this.cfg = cfg; this.mapper = mapper;
    }

    public String addTask(String taskNo, String taskType, String startNode, String endNode, String remark) {
        Map<String, Object> body = buildBody(taskNo, taskType, startNode, endNode, remark);
        String json;
        try { json = mapper.writeValueAsString(body); }
        catch (Exception e) { throw new RuntimeException("addTask 序列化失败: " + e.getMessage(), e); }

        HttpURLConnection conn = null;
        try {
            URL url = URI.create(cfg.getAcsAddTaskUrl()).toURL();
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(cfg.getHttpTimeoutSeconds() * 1000);
            conn.setReadTimeout(cfg.getHttpTimeoutSeconds() * 1000);
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            String respBody = readBody(conn);
            if (code >= 300) throw new RuntimeException("addTask HTTP " + code + ": " + respBody);
            log.info("addTask {} -> HTTP {}", taskNo, code);
            return respBody;
        } catch (IOException e) {
            throw new RuntimeException("addTask 调用失败: " + e.getMessage(), e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private Map<String, Object> buildBody(String taskNo, String taskType, String startNode, String endNode, String remark) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskNo", taskNo);
        body.put("taskSource", cfg.getTaskSource());
        body.put("taskType", taskType);
        body.put("bizType", cfg.getTaskBizType());
        body.put("bizPriority", 0);
        body.put("independentRun", "");
        body.put("preStartTaskNo", "");
        body.put("preEndTaskNo", "");
        body.put("startNode", startNode);
        body.put("endNode", endNode);
        body.put("requiredFunctionList", new ArrayList<>());

        Map<String, Object> container = new LinkedHashMap<>();
        container.put("containerCode", cfg.getContainerCode());
        container.put("containerType", "PALLET");
        container.put("size", Map.of("length","1160","width","1160","height","16","unit","cm"));
        container.put("weight", Map.of("value","22","unit","kg"));
        container.put("innerSize", Map.of("length","25","width","35","height","15","unit","cm"));
        container.put("loadHeightOffset", Map.of("length","50","unit","cm"));
        body.put("containerList", List.of(container));

        Map<String, Object> goods = new LinkedHashMap<>();
        goods.put("goodsCode", cfg.getGoodsCode());
        goods.put("goodsType", "GOODS");
        goods.put("size", Map.of("length","80","width","90","height","10","unit","cm"));
        goods.put("weight", Map.of("value","22","unit","kg"));
        body.put("goodsInfoList", List.of(goods));

        body.put("expectedStartTime", "");
        body.put("expectedFinishTime", "");
        body.put("remark", remark);
        return body;
    }

    private String readBody(HttpURLConnection conn) {
        try {
            java.io.InputStream is = (conn.getResponseCode() >= 400) ? conn.getErrorStream() : conn.getInputStream();
            if (is == null) return "";
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) { return ""; }
    }
}
```


### 7.7 StorageTaskRunner.java（核心调度器）

> 由于代码较长，分成 3 部分：①类成员 + 公共 API，②主循环 + planNextStep + advanceStep，③持久化 + 工具方法。组合到一起即为完整文件。

#### 7.7.1 类成员 + 公共 API

```java
package cn.aimstek.loong.aidiag.storagetask;

import cn.aimstek.loong.aidiag.storagetask.dto.*;
import cn.aimstek.loong.aidiag.storagetask.dto.StorageTaskRunnerState.Status;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Component
public class StorageTaskRunner {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter TASK_NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final Set<String> ADVANCING_STATES = Set.of(
            "SUCCESS", "MANUAL_SUCCESS", "CANCEL", "CANCELED", "CANCELLED");
    private static final Set<String> FAILED_STATES = Set.of("FAIL", "FAILED");

    private final ObjectMapper mapper;
    private volatile StorageTaskConfig config;
    private final StorageTaskRunnerState state = new StorageTaskRunnerState();

    private final Object pauseLock = new Object();
    private volatile boolean pauseRequested = false;
    private volatile boolean skipCurrentRequested = false;
    private volatile boolean stopRequested = false;
    private volatile Thread workerThread;

    @Autowired
    public StorageTaskRunner(ObjectMapper mapper) {
        ObjectMapper m = mapper.copy();
        m.enable(SerializationFeature.INDENT_OUTPUT);
        this.mapper = m;
    }

    @PostConstruct
    public synchronized void init() {
        loadConfig();
        loadState();
        if (state.getStatus() == Status.RUNNING) {
            state.setStatus(Status.PAUSED);
            saveState();
        }
    }

    @PreDestroy
    public synchronized void shutdown() {
        stopRequested = true;
        synchronized (pauseLock) { pauseLock.notifyAll(); }
        if (workerThread != null) workerThread.interrupt();
    }

    public synchronized StorageTaskConfig getConfig() { return config; }

    public synchronized void updateConfig(StorageTaskConfig newConfig) {
        if (state.getStatus() == Status.RUNNING)
            throw new IllegalStateException("当前正在运行, 请先暂停后再修改配置");
        this.config = newConfig;
        saveConfig();
    }

    public synchronized StorageTaskRunnerState getState() { return cloneState(); }

    public synchronized void start() {
        if (state.getStatus() == Status.RUNNING) throw new IllegalStateException("已在运行中");
        if (workerThread != null && workerThread.isAlive())
            throw new IllegalStateException("工作线程仍在运行, 请稍后再试");

        if (state.getStatus() != Status.PAUSED && state.getStatus() != Status.ERROR) {
            regenerateValidCodes();
            state.setCurrentRound(0);
            state.setCurrentStepInRound(0);
            state.setCurrentStartIdx(0);
            state.setCurrentCursor(0);
            state.setCurrentHoldPosition(null);
            state.setCurrentTask(null);
            state.setErrorMessage(null);
        } else {
            state.setErrorMessage(null);
        }
        if (state.getValidCodes() == null || state.getValidCodes().isEmpty())
            throw new IllegalStateException("没有可用库位, 请检查数据库与配置");

        pauseRequested = false;
        skipCurrentRequested = false;
        stopRequested = false;
        state.setStatus(Status.RUNNING);
        saveState();

        workerThread = new Thread(this::runLoop, "storage-task-runner");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    public synchronized void pause() {
        if (state.getStatus() != Status.RUNNING) return;
        pauseRequested = true;
        synchronized (pauseLock) { pauseLock.notifyAll(); }
    }

    public synchronized void skipCurrent() {
        if (state.getStatus() == Status.RUNNING) {
            skipCurrentRequested = true;
            synchronized (pauseLock) { pauseLock.notifyAll(); }
            return;
        }
        if (state.getStatus() == Status.PAUSED || state.getStatus() == Status.ERROR) {
            StorageTaskRecord rec = state.getCurrentTask();
            if (rec == null) throw new IllegalStateException("当前没有未完成的任务可跳过");
            rec.setState("SKIPPED");
            rec.setFinishedAt(LocalDateTime.now().format(TS_FMT));
            rec.setStuck(false);
            String existing = rec.getRemark() == null ? "" : rec.getRemark();
            rec.setRemark((existing.isBlank() ? "" : existing + " | ") +
                    "用户手动跳过 db_state=" + rec.getDbTaskState());
            archiveCurrent();
            advanceStep();
            if (state.getStatus() == Status.ERROR) {
                state.setStatus(Status.PAUSED);
                state.setErrorMessage(null);
            }
            saveState();
            return;
        }
        throw new IllegalStateException("当前状态不允许跳过: " + state.getStatus());
    }

    public synchronized void reset() {
        if (state.getStatus() == Status.RUNNING) throw new IllegalStateException("请先暂停再重置");
        state.setStatus(Status.IDLE);
        state.setCurrentRound(0); state.setCurrentStepInRound(0);
        state.setCurrentStartIdx(0); state.setCurrentCursor(0);
        state.setCurrentHoldPosition(null); state.setCurrentTask(null);
        state.setRecentTasks(new ArrayList<>()); state.setValidCodes(new ArrayList<>());
        state.setGlobalSeq(0);
        state.setTotalIssued(0); state.setTotalSuccess(0);
        state.setTotalManualSuccess(0); state.setTotalCanceled(0); state.setTotalFailed(0);
        state.setErrorMessage(null);
        saveState();
    }

    public synchronized void regenerateValidCodes() {
        StorageDb db = new StorageDb(config);
        List<String> all = StorageCodeGenerator.generate(config);
        List<String> valid;
        try { valid = db.filterExistingCodes(all); }
        catch (Exception e) { throw new RuntimeException("生成/校验库位失败: " + e.getMessage(), e); }
        state.setValidCodes(valid);
        saveState();
        log.info("生成候选库位 {}, 数据库内有效 {}", all.size(), valid.size());
    }

    public synchronized void testDb() {
        try { new StorageDb(config).ping(); }
        catch (Exception e) { throw new RuntimeException("数据库连接失败: " + e.getMessage(), e); }
    }
```


#### 7.7.2 主循环 + 计划下一步 + 推进

```java
    private void runLoop() {
        try {
            StorageDb db = new StorageDb(config);
            StorageAcsClient acs = new StorageAcsClient(config, mapper);

            while (!stopRequested) {
                if (pauseRequested) {
                    log.info("收到暂停请求, 进入 PAUSED");
                    setStatus(Status.PAUSED);
                    return;
                }

                if (state.getCurrentStepInRound() == 0 && state.getCurrentTask() == null) {
                    state.setCurrentRound(state.getCurrentRound() + 1);
                    state.setCurrentCursor(state.getCurrentStartIdx());
                    state.setCurrentHoldPosition(null);
                    saveState();
                    log.info("==================== 第 {} 轮 开始 ====================", state.getCurrentRound());
                }

                StepPlan plan = planNextStep();
                if (plan == null) {
                    int next = (state.getCurrentCursor() + 1) % state.getValidCodes().size();
                    state.setCurrentStartIdx(next);
                    state.setCurrentStepInRound(0);
                    state.setCurrentTask(null);
                    saveState();
                    continue;
                }

                if (state.getCurrentTask() == null) {
                    StorageTaskRecord rec = newRecord(plan);
                    state.setCurrentTask(rec);
                    state.setTotalIssued(state.getTotalIssued() + 1);
                    saveState();
                    try {
                        acs.addTask(rec.getTaskNo(), rec.getTaskType(),
                                rec.getStartNode(), rec.getEndNode(), rec.getRemark());
                    } catch (Exception e) {
                        rec.setState("ADD_FAILED");
                        rec.setRemark(e.getMessage());
                        archiveCurrent();
                        state.setStatus(Status.ERROR);
                        state.setErrorMessage("addTask 调用失败: " + e.getMessage());
                        saveState();
                        log.error("addTask 失败, 进入 ERROR: {}", e.getMessage(), e);
                        return;
                    }
                }

                String finalState = pollUntilTerminal(db);
                StorageTaskRecord rec = state.getCurrentTask();
                if (rec == null) continue;

                if ("__PAUSE__".equals(finalState)) {
                    rec.setState("PAUSED");
                    rec.setRemark("用户暂停时未达终态, 当前 db_state=" + rec.getDbTaskState());
                    saveState();
                    setStatus(Status.PAUSED);
                    return;
                }
                if ("__SKIP__".equals(finalState)) {
                    rec.setState("SKIPPED");
                    rec.setFinishedAt(now());
                    rec.setStuck(false);
                    rec.setRemark("用户手动跳过, db_state=" + rec.getDbTaskState());
                    archiveCurrent();
                    advanceStep();
                    saveState();
                    continue;
                }

                rec.setState(finalState);
                rec.setFinishedAt(now());
                rec.setStuck(false);

                if (FAILED_STATES.contains(finalState)) {
                    state.setTotalFailed(state.getTotalFailed() + 1);
                    archiveCurrent();
                    setStatus(Status.PAUSED);
                    state.setErrorMessage("任务终态失败 (" + finalState + ")，已自动暂停。"
                            + "请人工处理后点击 跳过当前 / 继续");
                    saveState();
                    return;
                }

                if ("MANUAL_SUCCESS".equals(finalState)) state.setTotalManualSuccess(state.getTotalManualSuccess() + 1);
                else if (finalState.startsWith("CANCEL")) state.setTotalCanceled(state.getTotalCanceled() + 1);
                else state.setTotalSuccess(state.getTotalSuccess() + 1);

                archiveCurrent();
                advanceStep();
                saveState();
            }
        } catch (Throwable t) {
            log.error("循环线程异常", t);
            state.setStatus(Status.ERROR);
            state.setErrorMessage("循环异常: " + t.getMessage());
            saveState();
        }
    }

    private String pollUntilTerminal(StorageDb db) {
        StorageTaskRecord rec = state.getCurrentTask();
        long startMs = System.currentTimeMillis();
        long thresholdMs = config.getTaskStuckThresholdSeconds() * 1000L;

        while (true) {
            if (stopRequested) return "__PAUSE__";
            if (skipCurrentRequested) { skipCurrentRequested = false; return "__SKIP__"; }
            if (pauseRequested) return "__PAUSE__";

            String dbState = db.queryTaskState(rec.getTaskNo());
            rec.setDbTaskState(dbState);
            if (dbState != null) {
                String upper = dbState.toUpperCase();
                if (ADVANCING_STATES.contains(upper)) return upper;
                if (FAILED_STATES.contains(upper)) return upper;
            }

            long elapsed = System.currentTimeMillis() - startMs;
            boolean nowStuck = elapsed > thresholdMs;
            if (nowStuck != rec.isStuck()) { rec.setStuck(nowStuck); saveState(); }

            synchronized (pauseLock) {
                try { pauseLock.wait(Math.max(500L, config.getPollIntervalSeconds() * 1000L)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return "__PAUSE__"; }
            }
        }
    }

    private StepPlan planNextStep() {
        List<String> codes = state.getValidCodes();
        if (codes == null || codes.isEmpty()) return null;
        int n = codes.size();
        int step = state.getCurrentStepInRound();
        int totalSteps = 1 + config.getShuffleTimesPerRound() + 1;
        if (step >= totalSteps) return null;

        StepPlan plan = new StepPlan();
        if (step == 0) {
            String pos = codes.get(state.getCurrentStartIdx() % n);
            plan.taskType = "N2S";
            plan.startNode = config.getInboundStartNode();
            plan.endNode = pos;
            plan.stepLabel = "入库";
            plan.remark = "R" + state.getCurrentRound() + "-入库";
        } else if (step <= config.getShuffleTimesPerRound()) {
            int curCursor = state.getCurrentCursor();
            int nextCursor = (curCursor + 1) % n;
            String next = codes.get(nextCursor);
            String cur = state.getCurrentHoldPosition() != null
                    ? state.getCurrentHoldPosition() : codes.get(curCursor);
            if (next.equals(cur)) { nextCursor = (nextCursor + 1) % n; next = codes.get(nextCursor); }
            plan.taskType = "S2S";
            plan.startNode = cur; plan.endNode = next;
            plan.stepLabel = "移库 " + step + "/" + config.getShuffleTimesPerRound();
            plan.remark = "R" + state.getCurrentRound() + "-移库" + step + "/" + config.getShuffleTimesPerRound();
        } else {
            String cur = state.getCurrentHoldPosition() != null
                    ? state.getCurrentHoldPosition() : codes.get(state.getCurrentCursor());
            plan.taskType = "S2N";
            plan.startNode = cur; plan.endNode = config.getInboundStartNode();
            plan.stepLabel = "出库";
            plan.remark = "R" + state.getCurrentRound() + "-出库";
        }
        return plan;
    }

    private void advanceStep() {
        int step = state.getCurrentStepInRound();
        int totalSteps = 1 + config.getShuffleTimesPerRound() + 1;

        if (step == 0) {
            String pos = state.getValidCodes().get(state.getCurrentStartIdx() % state.getValidCodes().size());
            state.setCurrentHoldPosition(pos);
            state.setCurrentCursor(state.getCurrentStartIdx());
        } else if (step <= config.getShuffleTimesPerRound()) {
            int n = state.getValidCodes().size();
            int nextCursor = (state.getCurrentCursor() + 1) % n;
            String next = state.getValidCodes().get(nextCursor);
            String cur = state.getCurrentHoldPosition();
            if (cur != null && next.equals(cur)) {
                nextCursor = (nextCursor + 1) % n;
                next = state.getValidCodes().get(nextCursor);
            }
            state.setCurrentCursor(nextCursor);
            state.setCurrentHoldPosition(next);
        } else if (step == totalSteps - 1) {
            state.setCurrentHoldPosition(null);
        }

        state.setCurrentStepInRound(step + 1);
        if (state.getCurrentStepInRound() >= totalSteps) {
            int n = state.getValidCodes().size();
            int next = (state.getCurrentCursor() + 1) % n;
            state.setCurrentStartIdx(next);
            state.setCurrentStepInRound(0);
            state.setCurrentTask(null);
        } else {
            state.setCurrentTask(null);
        }
    }
```


#### 7.7.3 任务记录、归档、持久化、工具方法

```java
    private StorageTaskRecord newRecord(StepPlan plan) {
        state.setGlobalSeq(state.getGlobalSeq() + 1);
        StorageTaskRecord rec = new StorageTaskRecord();
        rec.setSeq(state.getGlobalSeq());
        rec.setRound(state.getCurrentRound());
        rec.setStep(plan.stepLabel);
        rec.setTaskNo(genTaskNo());
        rec.setTaskType(plan.taskType);
        rec.setStartNode(plan.startNode);
        rec.setEndNode(plan.endNode);
        rec.setSubmittedAt(now());
        rec.setState("ISSUED");
        rec.setRemark(plan.remark);
        return rec;
    }

    private void archiveCurrent() {
        StorageTaskRecord rec = state.getCurrentTask();
        if (rec == null) return;
        LinkedList<StorageTaskRecord> tasks = new LinkedList<>(
                state.getRecentTasks() == null ? new ArrayList<>() : state.getRecentTasks());
        tasks.addFirst(rec);
        while (tasks.size() > 200) tasks.removeLast();
        state.setRecentTasks(new ArrayList<>(tasks));
        state.setCurrentTask(null);
    }

    private void setStatus(Status status) { state.setStatus(status); saveState(); }

    private synchronized StorageTaskRunnerState cloneState() {
        try {
            String json = mapper.writeValueAsString(state);
            return mapper.readValue(json, StorageTaskRunnerState.class);
        } catch (Exception e) { return state; }
    }

    private File homeDir() {
        File dir = new File(System.getProperty("user.home"), ".loong-ai-diagnosis");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }
    private File configFile() { return new File(homeDir(), "storage-task-config.json"); }
    private File stateFile()  { return new File(homeDir(), "storage-task-state.json"); }

    private void loadConfig() {
        File f = configFile();
        if (f.exists()) {
            try { config = mapper.readValue(f, StorageTaskConfig.class); return; }
            catch (Exception e) { log.warn("加载 storage-task 配置失败, 使用默认值: {}", e.getMessage()); }
        }
        config = new StorageTaskConfig();
        saveConfig();
    }

    private void saveConfig() {
        try { mapper.writeValue(configFile(), config); }
        catch (IOException e) { log.error("保存 storage-task 配置失败: {}", e.getMessage(), e); }
    }

    private void loadState() {
        File f = stateFile();
        if (!f.exists()) return;
        try {
            StorageTaskRunnerState loaded = mapper.readValue(f, StorageTaskRunnerState.class);
            if (loaded == null) return;
            state.setStatus(loaded.getStatus());
            state.setErrorMessage(loaded.getErrorMessage());
            state.setValidCodes(loaded.getValidCodes() == null ? new ArrayList<>() : loaded.getValidCodes());
            state.setCurrentRound(loaded.getCurrentRound());
            state.setCurrentStepInRound(loaded.getCurrentStepInRound());
            state.setCurrentStartIdx(loaded.getCurrentStartIdx());
            state.setCurrentCursor(loaded.getCurrentCursor());
            state.setCurrentHoldPosition(loaded.getCurrentHoldPosition());
            state.setCurrentTask(loaded.getCurrentTask());
            state.setGlobalSeq(loaded.getGlobalSeq());
            state.setRecentTasks(loaded.getRecentTasks() == null ? new ArrayList<>() : loaded.getRecentTasks());
            state.setTotalIssued(loaded.getTotalIssued());
            state.setTotalSuccess(loaded.getTotalSuccess());
            state.setTotalManualSuccess(loaded.getTotalManualSuccess());
            state.setTotalCanceled(loaded.getTotalCanceled());
            state.setTotalFailed(loaded.getTotalFailed());
        } catch (Exception e) { log.warn("加载 storage-task 状态失败: {}", e.getMessage()); }
    }

    private synchronized void saveState() {
        try { mapper.writeValue(stateFile(), state); }
        catch (IOException e) { log.error("保存 storage-task 状态失败: {}", e.getMessage(), e); }
    }

    private static String now() { return LocalDateTime.now().format(TS_FMT); }

    private String genTaskNo() {
        String ts = LocalDateTime.now().format(TASK_NO_FMT);
        return config.getTaskNoPrefix() + ts + "_" + String.format("%04d", state.getGlobalSeq());
    }

    private static class StepPlan {
        String taskType;
        String startNode;
        String endNode;
        String stepLabel;
        String remark;
    }
}
```

### 7.8 controller/StorageTaskController.java

```java
package cn.aimstek.loong.aidiag.controller;

import cn.aimstek.loong.aidiag.common.BaseResponse;
import cn.aimstek.loong.aidiag.common.Response;
import cn.aimstek.loong.aidiag.storagetask.StorageTaskRunner;
import cn.aimstek.loong.aidiag.storagetask.dto.StorageTaskConfig;
import cn.aimstek.loong.aidiag.storagetask.dto.StorageTaskRunnerState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/storage-task")
@RequiredArgsConstructor
public class StorageTaskController {

    private final StorageTaskRunner runner;

    @GetMapping("/config")
    public Response<StorageTaskConfig> getConfig() { return BaseResponse.success(runner.getConfig()); }

    @PutMapping("/config")
    public Response<Void> updateConfig(@RequestBody StorageTaskConfig cfg) {
        try { runner.updateConfig(cfg); return BaseResponse.success(null); }
        catch (IllegalStateException e) { return BaseResponse.failure("INVALID_STATE", e.getMessage()); }
        catch (Exception e) { return BaseResponse.failure("UPDATE_CONFIG_ERROR", e.getMessage()); }
    }

    @GetMapping("/state")
    public Response<StorageTaskRunnerState> getState() { return BaseResponse.success(runner.getState()); }

    @PostMapping("/start")
    public Response<Void> start() {
        try { runner.start(); return BaseResponse.success(null); }
        catch (IllegalStateException e) { return BaseResponse.failure("INVALID_STATE", e.getMessage()); }
        catch (Exception e) { return BaseResponse.failure("START_ERROR", e.getMessage()); }
    }

    @PostMapping("/pause")
    public Response<Void> pause() {
        try { runner.pause(); return BaseResponse.success(null); }
        catch (Exception e) { return BaseResponse.failure("PAUSE_ERROR", e.getMessage()); }
    }

    @PostMapping("/skip")
    public Response<Void> skip() {
        try { runner.skipCurrent(); return BaseResponse.success(null); }
        catch (IllegalStateException e) { return BaseResponse.failure("INVALID_STATE", e.getMessage()); }
        catch (Exception e) { return BaseResponse.failure("SKIP_ERROR", e.getMessage()); }
    }

    @PostMapping("/reset")
    public Response<Void> reset() {
        try { runner.reset(); return BaseResponse.success(null); }
        catch (IllegalStateException e) { return BaseResponse.failure("INVALID_STATE", e.getMessage()); }
        catch (Exception e) { return BaseResponse.failure("RESET_ERROR", e.getMessage()); }
    }

    @PostMapping("/regenerate-codes")
    public Response<Integer> regenerateCodes() {
        try { runner.regenerateValidCodes(); return BaseResponse.success(runner.getState().getValidCodes().size()); }
        catch (Exception e) { return BaseResponse.failure("REGENERATE_ERROR", e.getMessage()); }
    }

    @PostMapping("/test-db")
    public Response<Void> testDb() {
        try { runner.testDb(); return BaseResponse.success(null); }
        catch (Exception e) { return BaseResponse.failure("DB_ERROR", e.getMessage()); }
    }
}
```


---

## 8. 前端规格（待实现）

> 路径：`loong-ai-diagnosis/src/main/resources/static/storage-task.html`
> 技术：原生 HTML + JS（参照现有 `rules.html`、`docs.html` 的玻璃态风格），无前端框架。

### 8.1 顶栏

照搬 `rules.html` 的顶栏结构，加一项：
```html
<a href="storage-task.html" class="topbar-link active">🏗️ 立库测试</a>
```
同时在 `index.html`、`rules.html`、`docs.html`、`chat.html` 顶栏 `topbar-right` 中追加同名链接，便于互相跳转。

### 8.2 页面区块

```
┌────────────────────────────────────────────────────────────┐
│ [统计区] 状态徽标 / 当前轮次 / 当前步骤 / 已下发 / 成功 / 卡住 │
├────────────────────────────────────────────────────────────┤
│ [控制区] ▶启动  ⏸暂停  ⏭跳过当前  ↻重置  🔃重新生成库位 │
├────────────────────────────────────────────────────────────┤
│ [当前任务卡片] taskNo / type / start→end / db_state / 已耗时 │
│              （stuck=true 时整张卡片高亮黄色，显示"卡住!" │
│              提示用户去 ACS 处理）                         │
├────────────────────────────────────────────────────────────┤
│ [配置面板] 折叠区, 表单字段:                                │
│   - dbUrl / dbUsername / dbPassword + 测试连接 按钮         │
│   - acsAddTaskUrl / httpTimeoutSeconds                      │
│   - aisles (Tag 输入) / totalLayers / maxCol                │
│   - fullTestLayers / sampleRatio / randomSeed               │
│   - inboundStartNode / containerCode / goodsCode            │
│   - shuffleTimesPerRound / pollIntervalSeconds              │
│   - taskStuckThresholdSeconds / taskNoPrefix                │
│   [保存配置] 按钮                                           │
├────────────────────────────────────────────────────────────┤
│ [任务历史表] 最近 200 条:                                   │
│   #序号 | 轮 | 步骤 | taskNo | 类型 | 起→终 | 状态 | 耗时   │
│   状态着色: SUCCESS=绿 / MANUAL_SUCCESS=蓝 / CANCEL=灰      │
│             FAIL=红 / SKIPPED=橙                            │
└────────────────────────────────────────────────────────────┘
```

### 8.3 数据获取

- 启动后 `setInterval(fetchState, 1500)` 轮询 `GET /api/v1/storage-task/state`
- 控制按钮调用对应 POST 接口，成功后立即触发 `fetchState`
- 配置面板初始化拉一次 `GET /api/v1/storage-task/config`，保存时 `PUT /config`

### 8.4 关键交互

| 操作 | 前端处理 |
| --- | --- |
| 启动 | POST /start，禁用 配置编辑 直到状态变 PAUSED |
| 暂停 | POST /pause |
| 跳过当前 | confirm("确认跳过当前任务?")  → POST /skip |
| 重置 | confirm("会清空进度") → POST /reset |
| 重新生成库位 | POST /regenerate-codes，弹出 toast "X 个有效库位" |
| 测试 DB | POST /test-db，成功/失败弹 toast |
| 配置保存 | PUT /config，状态非 IDLE 提示 "需先暂停" |

### 8.5 状态展示样式

- `status` 用顶部一个胶囊 tag，颜色：IDLE=灰 / RUNNING=绿（脉冲动画）/ PAUSED=黄 / ERROR=红
- `currentTask.stuck=true` 时整张当前任务卡片加红色边框 + 警告 emoji ⚠️
- `errorMessage` 不为空时单独一个 banner 显示在控制区下方

### 8.6 编写要点（参考 rules.html）

1. 复用 `common.css` 玻璃态变量与组件类（`glass-card`、`glass-btn`、`glass-table`、`tag-success` 等）
2. 表格用 `glass-table`，序号倒序、每条 stuck 标记可视
3. 配置面板用两列 grid，关键字段放第一行
4. JS 全部写在底部 `<script>` 内，函数命名遵循 `loadState / startRunner / pauseRunner / skipCurrent / saveConfig` 等
5. 拉取失败时短暂提示，但不打断轮询

---

## 9. 落地步骤（清单）

1. **代码合入**
   - 创建包 `cn.aimstek.loong.aidiag.storagetask`，加入 4 个 java 文件 + 3 个 dto
   - 加入 `controller/StorageTaskController.java`
   - 不需要改 `pom.xml`、`application.yml`
2. **构建**
   - 在 `loong-ai-diagnosis` 根目录：`mvn -DskipTests package`
3. **启动**
   - `java -jar target/loong-ai-diagnosis.jar`
   - 默认端口 18080
4. **接口冒烟**
   - `curl http://localhost:18080/api/v1/storage-task/config` 返回默认配置
   - `curl -X POST http://localhost:18080/api/v1/storage-task/test-db` 验证 DB 通
   - 修改一遍 `aisles`，PUT /config，再 GET 看是否生效
5. **页面接入**
   - 写 `static/storage-task.html`
   - 在已有 4 个页面顶栏加 `🏗️ 立库测试` 入口
6. **手动验证**
   - 修改 `pollIntervalSeconds=2`、`shuffleTimesPerRound=2`、`fullTestLayers=[1]`、`sampleRatio=0.1`、`aisles=[8]`，先跑小集合
   - 确认入库 → 移库 → 出库 三步都按 `task_state=SUCCESS` 推进
7. **暂停/继续/跳过 验证**
   - RUNNING 中点暂停 → 5 秒内进入 PAUSED
   - PAUSED 中点开始 → 状态恢复 RUNNING，从下一步继续
   - 模拟卡住：在 ACS 直接把 task 改成 `MANUAL_SUCCESS` → runner 自动识别并继续
   - 模拟卡住：在 UI 直接点 跳过当前 → 当前任务记录 SKIPPED，下一步继续
   - 模拟失败：把 task 改成 `FAIL` → runner 自动暂停并显示 errorMessage

---

## 10. 已知风险与注意事项

| 风险 | 说明 / 规避 |
| --- | --- |
| `sc_task` 终态命名不一致 | 已兼容 `SUCCESS / MANUAL_SUCCESS / CANCEL / CANCELED / CANCELLED / FAIL / FAILED`。如有新值需要在 `ADVANCING_STATES` / `FAILED_STATES` 中追加 |
| 多实例同时跑会冲突 | 当前是单进程单实例，状态文件是本地的。若部署多副本需要外部锁（Redis / DB） |
| 数据库密码持久化 | 配置文件存在 `~/.loong-ai-diagnosis/storage-task-config.json`，明文。生产环境请用 OS 文件权限隔离或改读 env |
| addTask 接口业务返回码 | 当前只判断 HTTP 非 2xx 抛错。若调度服务返回 200 但 body 有 `code != 0`，需要在 `StorageAcsClient.addTask` 解析 body 后判断 |
| 任务号唯一性 | `yyyyMMddHHmmssSSS_序号(4位)`，单进程内不会重复；多进程需要把 globalSeq 全局化 |
| 同一时间只跑一个 | 工作线程是单线程顺序，不会并发下发任务 |
| 容器/货物号固定 | 多次重复使用同一个容器号可能被调度服务拒绝，若现场报"容器被占用"需要考虑参数化或在每轮入库前手动释放 |
| 进程崩溃 | 状态文件每次推进都写盘；崩溃后下次启动自动转为 PAUSED，用户决定是否继续 |
| `shuffleTimesPerRound` 太大、validCodes 太小 | 会循环复用同一批库位，但移库的起点终点会被算法跳过相同位置，行为正确 |
| 时区 | DB 用 `serverTimezone=Asia/Shanghai`，时间格式 `yyyy-MM-dd HH:mm:ss` 与 sc_task 一致 |


---

## 11. 附录

### 11.1 调试用 SQL

```sql
-- 看候选库位是否在表里
SELECT * FROM map_storage_area
WHERE storage_area_code = 'SL_-WH_001-SA_HSMD_1-AL_L08-L-01-01-0003-03'
  AND activate = 'ON' AND delete_flag = 0;

-- 看任务终态
SELECT task_no, task_state, paused, start_node, end_node, create_time, finish_time
FROM sc_task
WHERE task_no = 'ZDYNDTASK_20260525143012345_0007';

-- 找进行中的任务
SELECT task_no, task_state, start_node, end_node, create_time
FROM sc_task
WHERE task_no LIKE 'ZDYNDTASK_%'
ORDER BY create_time DESC LIMIT 20;

-- 看子任务、指令
SELECT * FROM sc_task_item WHERE task_no = '...';
SELECT * FROM sc_command   WHERE task_no = '...';
```

### 11.2 全套 curl

```bash
BASE=http://localhost:18080/api/v1/storage-task

# 1. 测 DB
curl -X POST $BASE/test-db

# 2. 看默认配置
curl $BASE/config

# 3. 改配置（示例: 只测 8 巷道, 移库 2 次, 抽样 10%）
curl -X PUT $BASE/config -H 'Content-Type: application/json' -d '{
  "dbUrl": "jdbc:mysql://localhost:3306/loong-platform?serverTimezone=Asia/Shanghai",
  "dbUsername": "root",
  "dbPassword": "root",
  "acsAddTaskUrl": "http://10.15.58.249:8088/task/addTask",
  "httpTimeoutSeconds": 15,
  "storagePrefix": "SL_-WH_001-SA_HSMD_1-AL_L",
  "aisles": [8],
  "totalLayers": 10,
  "maxCol": 64,
  "fullTestLayers": [1, 10],
  "sampleRatio": 0.1,
  "randomSeed": 20260525,
  "inboundStartNode": "ND_11025",
  "taskSource": "WMS",
  "taskBizType": "默认",
  "containerCode": "C_1224",
  "goodsCode": "GS_1223",
  "shuffleTimesPerRound": 2,
  "pollIntervalSeconds": 2,
  "taskStuckThresholdSeconds": 1800,
  "taskNoPrefix": "ZDYNDTASK_"
}'

# 4. 重新生成有效库位 (返回数量)
curl -X POST $BASE/regenerate-codes

# 5. 启动
curl -X POST $BASE/start

# 6. 拉状态
curl $BASE/state | jq '{status, currentRound, currentStepInRound, currentTask, totalIssued, totalSuccess, totalFailed}'

# 7. 暂停 / 跳过 / 继续
curl -X POST $BASE/pause
curl -X POST $BASE/skip
curl -X POST $BASE/start
```

### 11.3 前端 storage-task.html 骨架（可直接补内容）

> 仅给最小骨架，完整样式照 `rules.html` 复制玻璃态那部分进来即可。

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8">
  <title>立库循环任务测试</title>
  <link rel="stylesheet" href="common.css">
</head>
<body>

<nav class="glass-topbar">
  <div class="topbar-logo">🏗️ 立库循环测试</div>
  <div class="topbar-right">
    <a href="index.html"      class="topbar-link">📊 诊断</a>
    <a href="rules.html"      class="topbar-link">📋 规则</a>
    <a href="docs.html"       class="topbar-link">📚 知识库</a>
    <a href="storage-task.html" class="topbar-link active">🏗️ 立库测试</a>
  </div>
</nav>

<div class="container" style="max-width:1180px;margin:0 auto;padding:20px;">

  <!-- 状态徽标 + 控制按钮 -->
  <div class="glass-card section-card">
    <div class="section-header">
      <div class="section-title">
        状态: <span id="statusBadge" class="tag">-</span>
        <span id="errorBanner" style="color:var(--color-error);margin-left:12px;"></span>
      </div>
      <div class="actions-row">
        <button class="glass-btn"         id="btnStart">▶ 启动 / 继续</button>
        <button class="glass-btn-outline" id="btnPause">⏸ 暂停</button>
        <button class="glass-btn-outline" id="btnSkip">⏭ 跳过当前</button>
        <button class="glass-btn-outline" id="btnReset">↻ 重置</button>
        <button class="glass-btn-outline" id="btnRegen">🔃 重生成库位</button>
        <button class="glass-btn-outline" id="btnTestDb">🩺 测 DB</button>
      </div>
    </div>
    <div class="stats-overview">
      <div class="glass-card stat-card"><div class="stat-value" id="statRound">-</div><div class="stat-label">当前轮次</div></div>
      <div class="glass-card stat-card"><div class="stat-value" id="statStep">-</div><div class="stat-label">当前步骤</div></div>
      <div class="glass-card stat-card"><div class="stat-value" id="statValid">-</div><div class="stat-label">有效库位</div></div>
      <div class="glass-card stat-card"><div class="stat-value" id="statIssued">-</div><div class="stat-label">已下发</div></div>
      <div class="glass-card stat-card"><div class="stat-value" id="statSuccess">-</div><div class="stat-label">成功</div></div>
      <div class="glass-card stat-card"><div class="stat-value" id="statFailed">-</div><div class="stat-label">失败</div></div>
    </div>
  </div>

  <!-- 当前任务 -->
  <div class="glass-card section-card" id="currentTaskCard">
    <div class="section-title">当前任务</div>
    <div id="currentTaskBody" class="empty-hint">空闲</div>
  </div>

  <!-- 配置面板 -->
  <div class="glass-card section-card">
    <div class="section-header">
      <div class="section-title">⚙ 配置</div>
      <button class="glass-btn-outline" id="btnSaveCfg">💾 保存配置</button>
    </div>
    <div id="cfgForm" class="config-grid"></div>
  </div>

  <!-- 任务历史 -->
  <div class="glass-card section-card">
    <div class="section-title">任务历史 (最近 200 条)</div>
    <div class="table-wrapper">
      <table class="glass-table">
        <thead>
          <tr>
            <th>#</th><th>轮</th><th>步骤</th><th>taskNo</th><th>类型</th>
            <th>起→终</th><th>状态</th><th>提交</th><th>完成</th><th>备注</th>
          </tr>
        </thead>
        <tbody id="recentTasksTbody"></tbody>
      </table>
    </div>
  </div>
</div>

<script>
const API = '/api/v1/storage-task';
const $ = id => document.getElementById(id);

async function api(path, opts={}) {
  const res = await fetch(API + path, { headers: {'Content-Type':'application/json'}, ...opts });
  const json = await res.json();
  if (!json.success) throw new Error(json.message || '请求失败');
  return json.data;
}

let CFG = {};
async function loadConfig() {
  CFG = await api('/config');
  renderCfgForm(CFG);
}

function renderCfgForm(cfg) {
  const fields = [
    ['dbUrl','数据库 URL','text'],
    ['dbUsername','DB 账号','text'],
    ['dbPassword','DB 密码','password'],
    ['acsAddTaskUrl','addTask 地址','text'],
    ['httpTimeoutSeconds','HTTP 超时(秒)','number'],
    ['aisles','巷道(逗号分隔)','text'],
    ['totalLayers','总层数','number'],
    ['maxCol','最大列数','number'],
    ['fullTestLayers','全测层(逗号)','text'],
    ['sampleRatio','抽样比例 0~1','number'],
    ['randomSeed','随机种子','number'],
    ['inboundStartNode','入库口','text'],
    ['containerCode','容器号','text'],
    ['goodsCode','货物号','text'],
    ['shuffleTimesPerRound','每轮移库次数','number'],
    ['pollIntervalSeconds','轮询间隔(秒)','number'],
    ['taskStuckThresholdSeconds','卡住阈值(秒)','number'],
    ['taskNoPrefix','任务号前缀','text']
  ];
  $('cfgForm').innerHTML = fields.map(([k, label, type]) => {
    let v = cfg[k];
    if (Array.isArray(v)) v = v.join(',');
    return `<div class="config-item"><label class="config-label">${label}</label>
      <input class="config-input" data-key="${k}" data-type="${type}" type="${type==='password'?'password':'text'}" value="${v??''}"></div>`;
  }).join('');
}

function readForm() {
  const out = {...CFG};
  document.querySelectorAll('#cfgForm input').forEach(i => {
    const key = i.dataset.key, type = i.dataset.type;
    let v = i.value;
    if (key === 'aisles' || key === 'fullTestLayers') {
      out[key] = v.split(',').map(s=>parseInt(s.trim(),10)).filter(n=>!isNaN(n));
    } else if (type === 'number') {
      out[key] = v === '' ? null : Number(v);
    } else {
      out[key] = v;
    }
  });
  return out;
}

async function saveCfg() {
  try { await api('/config', {method:'PUT', body: JSON.stringify(readForm())}); toast('配置已保存','success'); CFG = readForm(); }
  catch (e) { toast(e.message, 'error'); }
}

async function fetchState() {
  let s;
  try { s = await api('/state'); } catch (e) { return; }
  $('statusBadge').textContent = s.status;
  $('statusBadge').className = 'tag tag-' + (s.status==='RUNNING'?'success':s.status==='ERROR'?'error':s.status==='PAUSED'?'warn':'init');
  $('errorBanner').textContent = s.errorMessage || '';
  $('statRound').textContent = s.currentRound;
  $('statStep').textContent = s.currentStepInRound + '/' + (1 + (CFG.shuffleTimesPerRound||0) + 1);
  $('statValid').textContent = s.validCodes.length;
  $('statIssued').textContent = s.totalIssued;
  $('statSuccess').textContent = s.totalSuccess + (s.totalManualSuccess?(' (+'+s.totalManualSuccess+'人工)'):'');
  $('statFailed').textContent = s.totalFailed;
  renderCurrent(s.currentTask);
  renderRecent(s.recentTasks||[]);
}

function renderCurrent(t) {
  if (!t) { $('currentTaskBody').className='empty-hint'; $('currentTaskBody').textContent='空闲'; return; }
  $('currentTaskBody').className = '';
  const stuck = t.stuck ? '<span class="tag tag-error">⚠ 卡住</span>' : '';
  $('currentTaskBody').innerHTML = `
    ${stuck}
    <div><b>${t.taskNo}</b> &nbsp; ${t.taskType} &nbsp; <span class="tag tag-running">${t.state}</span></div>
    <div>${t.step} | ${t.startNode} → ${t.endNode}</div>
    <div>db_state: ${t.dbTaskState||'-'} | 提交: ${t.submittedAt}</div>
    <div>备注: ${t.remark||''}</div>`;
}

function renderRecent(list) {
  $('recentTasksTbody').innerHTML = list.map(t => {
    const tag = t.state==='SUCCESS'?'success': t.state==='MANUAL_SUCCESS'?'running': t.state.startsWith('CANCEL')?'init': t.state==='SKIPPED'?'warn':'error';
    return `<tr>
      <td>${t.seq}</td><td>${t.round}</td><td>${t.step}</td>
      <td>${t.taskNo}</td><td>${t.taskType}</td>
      <td>${t.startNode}<br>→ ${t.endNode}</td>
      <td><span class="tag tag-${tag}">${t.state}</span></td>
      <td>${t.submittedAt||''}</td><td>${t.finishedAt||''}</td>
      <td>${t.remark||''}</td></tr>`;
  }).join('');
}

function toast(msg, kind) {
  const el = document.createElement('div');
  el.className = 'toast toast-' + (kind||'success');
  el.textContent = msg;
  document.body.appendChild(el);
  setTimeout(()=>el.remove(), 2200);
}

['Start','Pause','Skip','Reset','Regen','TestDb'].forEach(k => {
  const map = {Start:'/start', Pause:'/pause', Skip:'/skip', Reset:'/reset', Regen:'/regenerate-codes', TestDb:'/test-db'};
  $('btn'+k).onclick = async () => {
    if (k==='Reset' && !confirm('会清空所有进度,确定?')) return;
    if (k==='Skip'  && !confirm('确认跳过当前任务?')) return;
    try { const data = await api(map[k], {method:'POST'}); toast(k + ' OK' + (typeof data==='number'?(' '+data+'个'):''), 'success'); fetchState(); }
    catch (e) { toast(e.message, 'error'); }
  };
});
$('btnSaveCfg').onclick = saveCfg;

(async () => {
  await loadConfig();
  await fetchState();
  setInterval(fetchState, 1500);
})();
</script>
</body>
</html>
```

---

## 12. 后续可扩展

- 多巷道并行：每个巷道一个独立 runner（需要把 `state` 拆成 map）
- 任务号全局唯一：把 `globalSeq` 写到 DB 的某张计数表，多实例可用
- 接入 SSE：把 state 推送改成 SSE，前端不再轮询
- 把卡住任务的设备号、当前节点拉到 UI 一并展示，便于 ACS 侧排查
- 跑批任务对账：跑完后下载 csv（任务号 / 状态 / 开始时间 / 结束时间）

---

> 文档结束。所有 Java 代码已经落地到工程，前端页面 `storage-task.html` 按附录骨架实现即可。
