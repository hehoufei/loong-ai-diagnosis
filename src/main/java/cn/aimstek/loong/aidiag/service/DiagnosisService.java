package cn.aimstek.loong.aidiag.service;

import cn.aimstek.loong.aidiag.client.LlmClient;
import cn.aimstek.loong.aidiag.client.LogClient;
import cn.aimstek.loong.aidiag.client.TaskClient;
import cn.aimstek.loong.aidiag.dto.DiagnoseRequest;
import cn.aimstek.loong.aidiag.dto.DiagnoseResponse;
import cn.aimstek.loong.aidiag.dto.RootCauseItem;
import cn.aimstek.loong.aidiag.dto.TaskDetail;
import cn.aimstek.loong.aidiag.exception.AiDiagnosisException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DiagnosisService {

    private final LlmClient llmClient;
    private final TaskClient taskClient;
    private final LogClient logClient;
    private final ObjectMapper objectMapper;

    public DiagnoseResponse diagnose(DiagnoseRequest request) {
        validate(request);

        TaskDetail taskDetail = taskClient.getTaskDetail(request.getTaskId(), request.getEnv());

        // 任务已成功完成，无需诊断
        if (isCompleted(taskDetail.getTaskState())) {
            DiagnoseResponse resp = new DiagnoseResponse();
            resp.setSummary("任务已正常完成，无需诊断。状态: " + taskDetail.getTaskState()
                + "，完成时间: " + (taskDetail.getFinishTime() != null ? taskDetail.getFinishTime() : "未知"));
            return resp;
        }

        List<String> logs = logClient.queryLogs(request.getTaskId(), request.getEnv());
        String prompt = buildPrompt(taskDetail, logs);
        log.info("诊断任务: {}, prompt长度: {}", request.getTaskId(), prompt.length());
        String llmOutput = llmClient.call(prompt);
        return parseResponse(llmOutput);
    }

    private void validate(DiagnoseRequest request) {
        if (request == null || !StringUtils.hasText(request.getTaskId())) {
            throw new IllegalArgumentException("taskId不能为空");
        }
    }

    private boolean isCompleted(String taskState) {
        return "AUTO_SUCCESS".equals(taskState) || "MANUAL_SUCCESS".equals(taskState);
    }

    private String buildPrompt(TaskDetail task, List<String> logs) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
            你是WCS(仓库控制系统)任务排障专家。请严格按照下面的任务生命周期和诊断决策树，逐步分析任务卡在哪个阶段。

            ## 任务生命周期（正常流转顺序）
            主任务创建 → 关键点规划 → 路径规划并生成子任务 → 子任务拆分执行单 → 执行单下发PLC → 设备执行 → 完成
            对应状态变化:
            1. 主任务 handle_state: INIT(新建,还没开始规划) → KEY_PLANNING(关键点规划中) → PATH_PLANNING(路径规划完成,已生成子任务)
            2. 主任务 task_state: INIT → RUNNING → AUTO_SUCCESS/MANUAL_SUCCESS/CANCEL/CLOSE/PAUSE
            3. 子任务 task_state: INIT → PLANNED(已规划) → ISSUED_DCS(已下发) → RUNNING → AUTO_SUCCESS/MANUAL_SUCCESS/CANCEL
            4. 执行单 task_state: INIT → RUNNING → AUTO_SUCCESS/MANUAL_SUCCESS/CANCEL
            5. 执行单有 plc_task_id 表示已下发给PLC设备

            ## 诊断决策树（按此顺序逐步排查）
            第1步: 看主任务 handle_state
              - INIT → 任务还没开始规划，可能原因: 调度排队、系统繁忙、前置条件不满足、起终点异常
              - KEY_PLANNING → 卡在关键点规划阶段，可能原因: 点位被占用、路径不通、容器未找到、设备不可用
              - PATH_PLANNING → 规划已完成，进入第2步

            第2步: 看子任务是否生成
              - 无子任务 → 路径规划完成但未拆分子任务，异常情况，查日志
              - 有子任务 → 检查子任务状态，进入第3步

            第3步: 看子任务状态
              - INIT → 子任务未开始执行
              - PLANNED → 已规划但未下发
              - ISSUED_DCS → 已下发DCS，进入第4步
              - RUNNING → 正在执行中，进入第4步
              - CANCEL → 子任务被取消，查原因

            第4步: 看执行单
              - 无执行单 → 子任务未拆分执行单，异常
              - 有执行单但无 plc_task_id → 执行单未下发给PLC，可能设备离线或PLC通信异常
              - 有执行单且有 plc_task_id 但状态非完成 → 设备任务还在执行中，可能设备故障、执行超时、PLC无反馈
              - 执行单全部完成但子任务/主任务未完成 → 状态回写异常

            第5步: 看日志中的异常信息，补充上述分析

            ## 常见故障场景
            - 调度超时: 任务长时间停在INIT
            - 点位冲突: 规划阶段报点位被占用
            - 设备离线: 执行单无法下发
            - PLC通信异常: 有plc_task_id但设备无响应
            - 路径不通: 关键点规划失败
            - 容器未找到: 起点无对应容器

            """);

        sb.append("## 主任务信息\n");
        sb.append("任务ID: ").append(nullSafe(task.getTaskId())).append("\n");
        sb.append("WMS任务号: ").append(nullSafe(task.getWmsTaskNo())).append("\n");
        sb.append("任务来源: ").append(nullSafe(task.getTaskSource())).append("\n");
        sb.append("业务类型: ").append(nullSafe(task.getBusinessType())).append("\n");
        sb.append("任务类型: ").append(task.getTaskType() != null ? task.getTaskType() : "").append("\n");
        sb.append("执行状态: ").append(nullSafe(task.getTaskState())).append("\n");
        sb.append("处理状态: ").append(nullSafe(task.getHandleState())).append("\n");
        sb.append("容器号: ").append(nullSafe(task.getContainerCode())).append("\n");
        sb.append("业务起点: ").append(nullSafe(task.getBusinessFrom())).append("\n");
        sb.append("规划起点: ").append(nullSafe(task.getDefiniteFrom())).append("\n");
        sb.append("业务终点: ").append(nullSafe(task.getBusinessTo())).append("\n");
        sb.append("规划终点: ").append(nullSafe(task.getDefiniteTo())).append("\n");
        sb.append("错误信息: ").append(nullSafe(task.getErrorMessage())).append("\n");
        sb.append("优先级: ").append(task.getPriority() != null ? task.getPriority() : "").append("\n");
        sb.append("创建时间: ").append(task.getCreateTime() != null ? task.getCreateTime() : "").append("\n");
        sb.append("规划时间: ").append(task.getDefiniteTime() != null ? task.getDefiniteTime() : "").append("\n");
        sb.append("拆分时间: ").append(task.getSplitTime() != null ? task.getSplitTime() : "").append("\n");
        sb.append("开始时间: ").append(task.getStartTime() != null ? task.getStartTime() : "").append("\n");
        sb.append("完成时间: ").append(task.getFinishTime() != null ? task.getFinishTime() : "").append("\n\n");

        if (!task.getTaskItems().isEmpty()) {
            sb.append("## 子任务列表 (共").append(task.getTaskItems().size()).append("个)\n");
            for (int i = 0; i < task.getTaskItems().size(); i++) {
                TaskDetail.TaskItemDetail item = task.getTaskItems().get(i);
                sb.append("子任务").append(i + 1).append(": ");
                sb.append("ID=").append(nullSafe(item.getId()));
                sb.append(", 状态=").append(nullSafe(item.getTaskState()));
                sb.append(", 起点=").append(nullSafe(item.getStartPoint()));
                sb.append(", 终点=").append(nullSafe(item.getEndPoint()));
                sb.append(", 设备=").append(nullSafe(item.getDeviceCode()));
                sb.append("(").append(nullSafe(item.getDeviceType())).append(")");
                sb.append(", 检查状态=").append(nullSafe(item.getCheckStatus()));
                sb.append(", 创建=").append(item.getCreateTime() != null ? item.getCreateTime() : "");
                sb.append(", 开始=").append(item.getStartTime() != null ? item.getStartTime() : "");
                sb.append(", 完成=").append(item.getFinishTime() != null ? item.getFinishTime() : "");
                sb.append("\n");
            }
            sb.append("\n");
        } else {
            sb.append("## 子任务: 无（未生成子任务）\n\n");
        }

        if (!task.getTickets().isEmpty()) {
            sb.append("## 执行单列表 (共").append(task.getTickets().size()).append("个)\n");
            for (int i = 0; i < task.getTickets().size(); i++) {
                TaskDetail.TicketDetail t = task.getTickets().get(i);
                sb.append("执行单").append(i + 1).append(": ");
                sb.append("ID=").append(nullSafe(t.getId()));
                sb.append(", 子任务ID=").append(nullSafe(t.getTaskItemId()));
                sb.append(", 状态=").append(nullSafe(t.getTaskState()));
                sb.append(", 功能=").append(nullSafe(t.getFunction()));
                sb.append(", 起点=").append(nullSafe(t.getStartPoint()));
                sb.append(", 终点=").append(nullSafe(t.getEndPoint()));
                sb.append(", 设备=").append(nullSafe(t.getDeviceCode()));
                sb.append("(").append(nullSafe(t.getDeviceType())).append(")");
                sb.append(", PLC任务号=").append(nullSafe(t.getPlcTaskId()));
                sb.append(", 序号=").append(t.getTaskSort() != null ? t.getTaskSort() : "");
                sb.append(", 创建=").append(t.getCreateTime() != null ? t.getCreateTime() : "");
                sb.append(", 开始=").append(t.getStartTime() != null ? t.getStartTime() : "");
                sb.append(", 完成=").append(t.getFinishTime() != null ? t.getFinishTime() : "");
                sb.append("\n");
            }
            sb.append("\n");
        } else {
            sb.append("## 执行单: 无（未生成执行单）\n\n");
        }

        sb.append("## 系统日志\n");
        if (logs != null && !logs.isEmpty()) {
            for (String line : logs) {
                sb.append(line).append("\n");
            }
        } else {
            sb.append("无相关日志记录\n");
        }

        sb.append("""

            ## 输出要求
            请严格按照诊断决策树的步骤逐步分析，在summary中说明任务当前卡在生命周期的哪个阶段。
            请严格返回JSON，格式如下：
            {
              "summary": "说明任务卡在哪个阶段及原因（中文）",
              "rootCauses": [{"title": "根因标题", "description": "详细描述，包括判断依据和具体数据"}],
              "actions": ["具体可操作的建议1", "具体可操作的建议2"]
            }
            """);

        return sb.toString();
    }

    private DiagnoseResponse parseResponse(String llmOutput) {
        try {
            String cleaned = llmOutput.strip();
            if (cleaned.startsWith("```")) {
                int firstNewline = cleaned.indexOf('\n');
                if (firstNewline > 0) {
                    cleaned = cleaned.substring(firstNewline + 1);
                }
                if (cleaned.endsWith("```")) {
                    cleaned = cleaned.substring(0, cleaned.length() - 3);
                }
                cleaned = cleaned.strip();
            }
            JsonNode root = objectMapper.readTree(cleaned);
            DiagnoseResponse response = new DiagnoseResponse();
            response.setSummary(root.path("summary").asText(""));

            List<RootCauseItem> rootCauseItems = new ArrayList<>();
            JsonNode rootCauses = root.path("rootCauses");
            if (rootCauses.isArray()) {
                for (JsonNode node : rootCauses) {
                    rootCauseItems.add(new RootCauseItem(
                        node.path("title").asText(""),
                        node.path("description").asText("")
                    ));
                }
            }
            response.setRootCauses(rootCauseItems);

            List<String> actions = new ArrayList<>();
            JsonNode actionNode = root.path("actions");
            if (actionNode.isArray()) {
                for (JsonNode node : actionNode) {
                    actions.add(node.asText(""));
                }
            }
            response.setActions(actions);
            return response;
        } catch (Exception e) {
            log.error("解析LLM输出失败, output={}", llmOutput, e);
            throw new AiDiagnosisException("LLM_RESPONSE_PARSE_ERROR", "LLM输出解析失败，请稍后重试", e);
        }
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
