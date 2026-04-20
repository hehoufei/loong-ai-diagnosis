package cn.aimstek.loong.aidiag.service;

import cn.aimstek.loong.aidiag.client.LlmClient;
import cn.aimstek.loong.aidiag.client.LogClient;
import cn.aimstek.loong.aidiag.client.TaskClient;
import cn.aimstek.loong.aidiag.config.DiagnosisPromptProperties;
import cn.aimstek.loong.aidiag.dto.*;
import cn.aimstek.loong.aidiag.exception.AiDiagnosisException;
import cn.aimstek.loong.aidiag.rule.ConfigurableRuleEngine;
import cn.aimstek.loong.aidiag.rule.DiagnosisContext;
import cn.aimstek.loong.aidiag.rule.RuleProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.sql.Timestamp;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class DiagnosisStreamService {

    private final TaskClient taskClient;
    private final LogClient logClient;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final DiagnosisPromptProperties promptProps;

    @Autowired
    private ConfigurableRuleEngine ruleEngine;

    @Autowired
    private DocSearchService docSearchService;

    @Autowired
    private RuleProperties ruleProperties;

    /**
     * 按需触发 LLM 深度分析（同步调用，非流式）
     */
    public DiagnoseResponse aiAnalyze(String taskId, String env) {
        // 1. 获取任务详情
        TaskDetail detail = taskClient.getTaskDetail(taskId, env);

        // 2. 获取执行单并检查点位冲突
        List<TaskDetail.TicketDetail> tickets = detail.getTickets();
        List<PointConflict> conflicts = checkPointConflicts(detail, tickets);

        // 3. 获取日志
        List<String> logs = logClient.queryLogs(
            detail.getWmsTaskNo() != null ? detail.getWmsTaskNo() : taskId, env);

        // 4. 构建提示词
        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(detail, logs, conflicts);

        // 5. 调用 LLM
        String llmOutput = llmClient.call(systemPrompt, userPrompt);

        // 6. 解析结果
        return parseResponse(llmOutput);
    }

    @Async
    public void aiAnalyzeStream(SseEmitter emitter, String taskId, String env) {
        try {
            // 1. 获取任务详情（与 aiAnalyze 相同的数据获取逻辑）
            TaskDetail detail = taskClient.getTaskDetail(taskId, env);
            List<TaskDetail.TicketDetail> tickets = detail.getTickets();
            List<PointConflict> conflicts = checkPointConflicts(detail, tickets);
            List<String> logs = logClient.queryLogs(
                detail.getWmsTaskNo() != null ? detail.getWmsTaskNo() : taskId, env);

            // 2. 构建提示词
            String systemPrompt = buildSystemPrompt();
            String userPrompt = buildUserPrompt(detail, logs, conflicts);

            // 3. 流式调用 LLM
            StringBuilder fullContent = new StringBuilder();

            llmClient.callStream(systemPrompt, userPrompt)
                .doOnNext(token -> {
                    fullContent.append(token);
                    // 发送每个 token 给前端
                    try {
                        emitter.send(SseEmitter.event()
                            .name("ai-token")
                            .data(token));
                    } catch (IOException e) {
                        log.warn("发送 ai-token 失败", e);
                    }
                })
                .doOnComplete(() -> {
                    try {
                        // 流式完成后，解析完整结果并发送结构化数据
                        DiagnoseResponse result = parseResponse(fullContent.toString());
                        String resultJson = objectMapper.writeValueAsString(result);
                        emitter.send(SseEmitter.event()
                            .name("ai-result")
                            .data(resultJson));
                        emitter.complete();
                    } catch (Exception e) {
                        log.error("发送最终结果失败", e);
                        emitter.completeWithError(e);
                    }
                })
                .doOnError(err -> {
                    log.error("LLM 流式调用异常: taskId={}", taskId, err);
                    try {
                        emitter.send(SseEmitter.event()
                            .name("ai-error")
                            .data("AI分析异常: " + err.getMessage()));
                        emitter.complete();
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                })
                .blockLast();  // 在 @Async 线程中阻塞等待流完成

        } catch (Exception e) {
            log.error("AI 流式分析异常: taskId={}", taskId, e);
            try {
                emitter.send(SseEmitter.event()
                    .name("ai-error")
                    .data("AI分析失败: " + e.getMessage()));
                emitter.complete();
            } catch (IOException ex) {
                emitter.completeWithError(ex);
            }
        }
    }

    @Async
    public void diagnoseAsync(SseEmitter emitter, String taskId, String env) {
        try {
            // Step 1: 查询主任务
            sendEvent(emitter, "step", "{\"step\":1,\"label\":\"正在查询主任务...\"}");

            TaskDetail detail = taskClient.getTaskDetail(taskId, env);

            // 发送主任务数据（不含子任务和执行单，前端分步展示）
            TaskDetail taskOnly = new TaskDetail();
            taskOnly.setTaskId(detail.getTaskId());
            taskOnly.setWmsTaskNo(detail.getWmsTaskNo());
            taskOnly.setTaskSource(detail.getTaskSource());
            taskOnly.setBusinessType(detail.getBusinessType());
            taskOnly.setTaskType(detail.getTaskType());
            taskOnly.setTaskState(detail.getTaskState());
            taskOnly.setHandleState(detail.getHandleState());
            taskOnly.setContainerCode(detail.getContainerCode());
            taskOnly.setBusinessFrom(detail.getBusinessFrom());
            taskOnly.setDefiniteFrom(detail.getDefiniteFrom());
            taskOnly.setBusinessTo(detail.getBusinessTo());
            taskOnly.setDefiniteTo(detail.getDefiniteTo());
            taskOnly.setErrorMessage(detail.getErrorMessage());
            taskOnly.setPriority(detail.getPriority());
            taskOnly.setCreateTime(detail.getCreateTime());
            taskOnly.setStartTime(detail.getStartTime());
            taskOnly.setFinishTime(detail.getFinishTime());

            String taskJson = objectMapper.writeValueAsString(taskOnly);
            sendEvent(emitter, "task", taskJson);
            Thread.sleep(300);

            // Step 2: 子任务
            sendEvent(emitter, "step", "{\"step\":2,\"label\":\"正在查询子任务...\"}");
            Thread.sleep(200);

            List<TaskDetail.TaskItemDetail> items = detail.getTaskItems();
            String itemsJson = objectMapper.writeValueAsString(items);
            sendEvent(emitter, "taskItems", itemsJson);
            Thread.sleep(300);

            // Step 3: 执行单
            sendEvent(emitter, "step", "{\"step\":3,\"label\":\"正在查询执行单...\"}");
            Thread.sleep(200);

            List<TaskDetail.TicketDetail> tickets = detail.getTickets();
            String ticketsJson = objectMapper.writeValueAsString(tickets);
            sendEvent(emitter, "tickets", ticketsJson);
            Thread.sleep(300);

            // Step 4: 路径占用检查
            sendEvent(emitter, "step", "{\"step\":4,\"label\":\"正在检查路径点位占用...\"}");
            Thread.sleep(200);

            List<PointConflict> conflicts = checkPointConflicts(detail, tickets);
            String conflictsJson = objectMapper.writeValueAsString(conflicts);
            sendEvent(emitter, "conflicts", conflictsJson);
            Thread.sleep(300);

            // Step 5: 系统日志
            sendEvent(emitter, "step", "{\"step\":5,\"label\":\"正在查询系统日志...\"}");
            Thread.sleep(200);

            List<String> logs = logClient.queryLogs(
                detail.getWmsTaskNo() != null ? detail.getWmsTaskNo() : taskId, env);
            String logsJson = objectMapper.writeValueAsString(logs);
            sendEvent(emitter, "logs", logsJson);
            Thread.sleep(300);

            // Step 6: 规则引擎分析
            if (isCompleted(detail.getTaskState())) {
                DiagnoseResponse resp = new DiagnoseResponse();
                resp.setSummary("任务已正常完成，无需诊断。状态: " + detail.getTaskState());
                String aiJson = objectMapper.writeValueAsString(resp);
                sendEvent(emitter, "step", "{\"step\":6,\"label\":\"任务已完成，无需分析\"}");
                sendEvent(emitter, "ai", aiJson);
            } else {
                sendEvent(emitter, "step", "{\"step\":6,\"label\":\"规则引擎分析中...\"}");

                // 构建诊断上下文（含预检索文档）
                List<String> relevantDocs = List.of();
                if (ruleProperties.getDocSearch().isPreSearchEnabled()) {
                    relevantDocs = docSearchService.preSearchDocs(detail, logs);
                }

                DiagnosisContext context = DiagnosisContext.builder()
                    .detail(detail)
                    .conflicts(conflicts)
                    .logs(logs)
                    .relevantDocs(relevantDocs)
                    .build();

                // 使用规则引擎评估
                DiagnoseResponse ruleResult = ruleEngine.evaluate(context);

                // 文档增强（后置补充，兼容原有行为）
                docSearchService.enrichWithDocSearch(ruleResult, detail, logs);

                String aiJson = objectMapper.writeValueAsString(ruleResult);
                sendEvent(emitter, "ai", aiJson);
            }

            sendEvent(emitter, "done", "{}");
            emitter.complete();
        } catch (AiDiagnosisException e) {
            sendErrorAndComplete(emitter, e.getUserMessage());
        } catch (Exception e) {
            log.error("流式诊断异常", e);
            sendErrorAndComplete(emitter, "诊断过程出错: " + e.getMessage());
        }
    }

    private void sendEvent(SseEmitter emitter, String name, String data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (Exception e) {
            log.warn("SSE发送失败: {}", e.getMessage());
        }
    }

    private void sendErrorAndComplete(SseEmitter emitter, String message) {
        try {
            sendEvent(emitter, "error", "{\"message\":\"" + message.replace("\"", "\\\"") + "\"}");
            emitter.complete();
        } catch (Exception ex) {
            log.warn("SSE错误发送失败", ex);
        }
    }

    private boolean isCompleted(String taskState) {
        return "AUTO_SUCCESS".equals(taskState) || "MANUAL_SUCCESS".equals(taskState);
    }

    /**
     * 检查执行单路径上的点位占用情况。
     * loong_lock 表结构:
     *   parent_lock_key: JOB_任务ID (如 JOB_MM001520013)
     *   lock_type: NODE
     *   lock_key: NODE_点位编码 (如 NODE_ND_20013)
     *   lock_value: 点位编码 (如 ND_20013)
     *
     * 执行单的起终点可能是纯数字(20013)或带前缀(ND_20013)，需要都匹配。
     */
    private List<PointConflict> checkPointConflicts(TaskDetail detail, List<TaskDetail.TicketDetail> tickets) {
        Set<String> rawPoints = new LinkedHashSet<>();
        for (TaskDetail.TicketDetail t : tickets) {
            if ("RUNNING".equals(t.getTaskState()) || "INIT".equals(t.getTaskState())) {
                if (t.getStartPoint() != null) rawPoints.add(t.getStartPoint());
                if (t.getEndPoint() != null) rawPoints.add(t.getEndPoint());
            }
        }
        if (rawPoints.isEmpty()) {
            return Collections.emptyList();
        }

        // lock_value 可能是 ND_20013 格式，执行单起终点可能是 20013 或 ND_20013
        // 构建两种匹配: 原始值 和 带前缀的值
        Set<String> matchValues = new LinkedHashSet<>();
        for (String p : rawPoints) {
            matchValues.add(p);                    // 原始值，如 ND_20013
            matchValues.add("ND_" + p);            // 加前缀，如 ND_20013（当原始值是 20013 时）
        }

        String currentTaskId = detail.getTaskId();
        String currentWmsNo = detail.getWmsTaskNo();

        String placeholders = String.join(",", Collections.nCopies(matchValues.size(), "?"));
        String sql = "SELECT lock_value, lock_type, parent_lock_key, expiration_time " +
                     "FROM loong_lock WHERE lock_value IN (" + placeholders + ") " +
                     "AND expiration_time > NOW()";

        List<PointConflict> conflicts = new ArrayList<>();
        try {
            List<PointConflict> allLocks = jdbcTemplate.query(sql, (rs, rowNum) -> {
                PointConflict c = new PointConflict();
                c.setLockValue(rs.getString("lock_value"));
                c.setLockType(rs.getString("lock_type"));
                String parentKey = rs.getString("parent_lock_key");
                // parent_lock_key 格式: JOB_任务ID，去掉 JOB_ 前缀得到任务ID
                String occupiedBy = parentKey;
                if (parentKey != null && parentKey.startsWith("JOB_")) {
                    occupiedBy = parentKey.substring(4);
                }
                c.setOccupiedBy(occupiedBy);
                Timestamp ts = rs.getTimestamp("expiration_time");
                c.setExpirationTime(ts != null ? ts.toLocalDateTime() : null);
                return c;
            }, matchValues.toArray());

            // 过滤掉当前任务自身的锁
            for (PointConflict c : allLocks) {
                String occ = c.getOccupiedBy();
                if (occ != null
                    && !occ.equals(currentTaskId)
                    && !occ.equals(currentWmsNo)) {
                    conflicts.add(c);
                }
            }
        } catch (Exception e) {
            log.warn("查询点位占用失败: {}", e.getMessage());
        }
        return conflicts;
    }



    /**
     * 构建 system prompt：角色设定、知识框架、输出格式约束
     */
    private String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();

        // 角色设定
        sb.append(promptProps.getRole()).append("\n\n");

        // 业务背景
        String ctx = promptProps.getBusinessContext();
        if (ctx != null && !ctx.isBlank()) {
            sb.append(ctx).append("\n\n");
        }

        // 任务生命周期
        sb.append("## 任务正常流转顺序\n");
        for (DiagnosisPromptProperties.LifecycleRule rule : promptProps.getLifecycle()) {
            sb.append(rule.getContent()).append("\n");
        }
        sb.append("\n");

        // 诊断决策树
        sb.append("## 诊断决策树（按此顺序逐步排查）\n");
        for (DiagnosisPromptProperties.DecisionStep step : promptProps.getDecisionTree()) {
            sb.append("第").append(step.getStep()).append("步: ").append(step.getTitle()).append("\n");
            for (DiagnosisPromptProperties.DecisionRule rule : step.getRules()) {
                sb.append("  - ").append(rule.getCondition()).append(" → ").append(rule.getConclusion());
                if (rule.getCauses() != null && !rule.getCauses().isEmpty()) {
                    sb.append("，可能原因: ").append(rule.getCauses());
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        // 常见故障场景
        sb.append("## 常见故障场景\n");
        for (DiagnosisPromptProperties.FaultScenario scenario : promptProps.getFaultScenarios()) {
            sb.append("- ").append(scenario.getName()).append(": ").append(scenario.getDescription()).append("\n");
        }
        sb.append("\n");

        // 输出格式约束
        sb.append("## 输出要求\n");
        sb.append(promptProps.getOutputRequirement()).append("\n");

        return sb.toString();
    }

    /**
     * 构建 user prompt：仅包含当前任务的动态数据
     */
    private String buildUserPrompt(TaskDetail task, List<String> logs, List<PointConflict> conflicts) {
        StringBuilder sb = new StringBuilder();

        sb.append("请诊断以下任务，直接返回JSON结果。\n\n");

        // 主任务
        sb.append("## 主任务\n");
        sb.append("ID=").append(n(task.getTaskId()));
        sb.append(", WMS=").append(n(task.getWmsTaskNo()));
        sb.append(", 状态=").append(n(task.getTaskState()));
        sb.append(", 处理状态=").append(n(task.getHandleState()));
        sb.append(", 业务类型=").append(n(task.getBusinessType()));
        sb.append(", 起点=").append(n(task.getBusinessFrom())).append("/").append(n(task.getDefiniteFrom()));
        sb.append(", 终点=").append(n(task.getBusinessTo())).append("/").append(n(task.getDefiniteTo()));
        sb.append(", 错误=").append(n(task.getErrorMessage()));
        sb.append(", 创建=").append(task.getCreateTime()).append("\n\n");

        // 子任务
        if (!task.getTaskItems().isEmpty()) {
            sb.append("## 子任务(").append(task.getTaskItems().size()).append("个)\n");
            for (TaskDetail.TaskItemDetail item : task.getTaskItems()) {
                sb.append("ID=").append(n(item.getId()));
                sb.append(", 状态=").append(n(item.getTaskState()));
                sb.append(", 起点=").append(n(item.getStartPoint()));
                sb.append(", 终点=").append(n(item.getEndPoint()));
                sb.append(", 设备=").append(n(item.getDeviceCode()));
                sb.append(", 检查=").append(n(item.getCheckStatus())).append("\n");
            }
            sb.append("\n");
        }

        // 执行单
        if (!task.getTickets().isEmpty()) {
            sb.append("## 执行单(").append(task.getTickets().size()).append("个)\n");
            for (TaskDetail.TicketDetail t : task.getTickets()) {
                sb.append("ID=").append(n(t.getId()));
                sb.append(", 状态=").append(n(t.getTaskState()));
                sb.append(", 起终=").append(n(t.getStartPoint())).append("→").append(n(t.getEndPoint()));
                sb.append(", 设备=").append(n(t.getDeviceCode()));
                sb.append(", PLC=").append(n(t.getPlcTaskId())).append("\n");
            }
            sb.append("\n");
        }

        // 日志
        sb.append("## 日志\n");
        if (logs != null && !logs.isEmpty()) {
            logs.forEach(l -> sb.append(l).append("\n"));
        } else {
            sb.append("无\n");
        }

        // 点位占用
        if (conflicts != null && !conflicts.isEmpty()) {
            sb.append("\n## 路径点位占用情况\n");
            for (PointConflict c : conflicts) {
                sb.append("点位=").append(n(c.getLockValue()));
                sb.append(", 锁类型=").append(n(c.getLockType()));
                sb.append(", 被任务占用=").append(n(c.getOccupiedBy()));
                sb.append(", 过期时间=").append(c.getExpirationTime()).append("\n");
            }
            sb.append("说明: ").append(promptProps.getConflictHint()).append("\n");
        }

        return sb.toString();
    }

    private DiagnoseResponse parseResponse(String llmOutput) {
        try {
            String cleaned = llmOutput.strip();
            // 去掉 markdown 代码块包裹
            if (cleaned.startsWith("```")) {
                int nl = cleaned.indexOf('\n');
                if (nl > 0) cleaned = cleaned.substring(nl + 1);
                if (cleaned.endsWith("```")) cleaned = cleaned.substring(0, cleaned.length() - 3);
                cleaned = cleaned.strip();
            }
            // 尝试从文本中提取 JSON 对象（LLM 可能在 JSON 前后输出了多余文字）
            if (!cleaned.startsWith("{")) {
                int jsonStart = cleaned.indexOf('{');
                int jsonEnd = cleaned.lastIndexOf('}');
                if (jsonStart >= 0 && jsonEnd > jsonStart) {
                    cleaned = cleaned.substring(jsonStart, jsonEnd + 1);
                } else {
                    // 完全没有 JSON，用原始文本构造兜底结果
                    log.warn("LLM未返回JSON格式，使用原始文本作为摘要");
                    return buildFallbackResponse(llmOutput);
                }
            }
            JsonNode root = objectMapper.readTree(cleaned);
            DiagnoseResponse resp = new DiagnoseResponse();
            resp.setSummary(root.path("summary").asText(""));
            List<RootCauseItem> rcs = new ArrayList<>();
            for (JsonNode n : root.path("rootCauses")) {
                rcs.add(new RootCauseItem(n.path("title").asText(""), n.path("description").asText("")));
            }
            resp.setRootCauses(rcs);
            List<String> actions = new ArrayList<>();
            for (JsonNode n : root.path("actions")) { actions.add(n.asText("")); }
            resp.setActions(actions);
            return resp;
        } catch (Exception e) {
            log.warn("解析LLM JSON失败，尝试兜底处理: {}", e.getMessage());
            return buildFallbackResponse(llmOutput);
        }
    }

    /**
     * LLM 未返回 JSON 时的兜底：截取原始文本前500字作为 summary
     */
    private DiagnoseResponse buildFallbackResponse(String rawOutput) {
        DiagnoseResponse resp = new DiagnoseResponse();
        String text = rawOutput.strip();
        if (text.length() > 500) {
            text = text.substring(0, 500) + "...";
        }
        resp.setSummary(text);
        resp.setRootCauses(List.of(new RootCauseItem("AI输出格式异常", "模型未按要求返回JSON格式，已将原始分析文本作为摘要展示")));
        resp.setActions(List.of("可尝试重新诊断", "如持续出现建议切换更高级别的模型"));
        return resp;
    }


    private String n(String v) { return v == null ? "" : v; }
}
