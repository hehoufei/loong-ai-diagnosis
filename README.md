# loong-ai-diagnosis

WCS 任务诊断小工具。输入一个 `taskNo`，输出"这个任务为什么卡住、该怎么办"。

## 特性

- 无状态、不落库、用完即走
- 规则引擎 + LLM 兜底的两级诊断链路
- 覆盖 10 种常见卡任务场景的规则
- 通过 YAML 灵活配置规则输出模板

## 架构

```
REST 入口 (POST /api/v1/diagnosis/analyze)
  → DiagnosisFacade (编排)
    → DiagnosisContextAssembler (组装上下文)
      → PlatformClient (HTTP 调 loong-platform)
    → RuleEngine (规则优先)
    → LlmDiagnosisEngine (规则未命中兜底)
```

## 快速开始

### 环境要求

- JDK 17+
- Maven 3.8+
- 可访问 loong-platform 服务

### 配置

关键环境变量（均有默认值，按需覆盖）：

| 变量 | 说明 | 默认值 |
|---|---|---|
| `PLATFORM_URL` | loong-platform 地址 | `http://127.0.0.1:18081` |
| `LLM_API_KEY` | 智谱 AI Key | 测试 key |
| `LLM_MODEL` | 模型名 | `glm-4.7-flash` |

配置文件：
- `application.yml`：Spring 框架和外部服务连接
- `diagnosis-core.yml`：诊断核心配置（`loong.ai.diagnosis.*` / `loong.platform.*`）
- `diagnosis-rules.yml`：规则配置

### 构建与运行

```bash
mvn clean package -DskipTests
java -jar target/loong-ai-diagnosis.jar
```

启动后：
- 服务端口：`18080`
- Swagger UI：http://localhost:18080/swagger-ui.html
- 诊断接口：`POST http://localhost:18080/api/v1/diagnosis/analyze`

### 调用示例

```bash
curl -X POST http://localhost:18080/api/v1/diagnosis/analyze \
  -H "Content-Type: application/json" \
  -d '{"taskId":"WMS_TASK_001"}'
```

响应示例：

```json
{
  "success": true,
  "code": "SUCCESS",
  "data": {
    "summary": "任务卡在等待拆分阶段 2 分钟",
    "rootCauses": [
      {"title": "引擎未返回拆分结果", "description": "..."}
    ],
    "actions": ["检查引擎服务是否可达", "..."],
    "diagnosisMode": "rule",
    "confidence": 0.95,
    "traceId": "WMS_TASK_001-a1b2c3d4"
  }
}
```

## 诊断规则

10 条内置规则（按优先级）：

| 规则 | 优先级 | 触发条件 |
|---|---|---|
| `wait-split-stuck` | 100 | 大任务 WAIT_SPLIT 卡住 > 60s |
| `wait-plan-stuck` | 90 | WAIT_PLAN 且无 RUNNING 子任务 |
| `running-but-items-waiting` | 85 | RUNNING 但子任务全 WAIT_* |
| `command-not-sent` | 80 | RUNNING 子任务的 Command 全 WAIT |
| `plc-no-response` | 80 | Command SENT 但无 plcTaskNo |
| `command-timeout` | 75 | Command 超 300s 未完成 |
| `all-items-done-but-task-not-finished` | 70 | 子任务全 SUCCESS 但大任务未完成 |
| `has-cancelled-items` | 60 | 大任务未终态但存在 CANCEL 子任务 |
| `task-paused` | 50 | 任务或子任务被暂停 |
| `task-group-not-split` | 40 | 任务组未完成拆分 |
| `fallback` | -100 | 兜底（触发 LLM 分析） |

## 测试

```bash
mvn test
```

## 目录结构

```
src/main/java/cn/aimstek/loong/aidiag/
├── AIDiagnosisApplication       # 启动类
├── controller/                  # REST 入口
├── facade/                      # 编排层
├── context/                     # 诊断上下文
├── client/                      # 平台 HTTP 客户端
│   └── dto/                     # 平台返回 DTO
├── rule/                        # 规则引擎
│   └── rules/                   # 具体规则
├── llm/                         # LLM 兜底
├── dto/                         # 接口 DTO
├── config/                      # 配置绑定
├── common/                      # 通用响应
└── exception/                   # 异常
```
