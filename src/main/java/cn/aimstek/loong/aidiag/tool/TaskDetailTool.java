package cn.aimstek.loong.aidiag.tool;

import cn.aimstek.loong.aidiag.client.TaskClient;
import cn.aimstek.loong.aidiag.config.EnvConfig;
import cn.aimstek.loong.aidiag.dto.TaskDetail;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Agent 工具：查询 WCS 主任务详情（仅主任务核心字段，不含子任务和执行单）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskDetailTool {

    private final TaskClient taskClient;
    private final EnvConfig envConfig;
    private final ObjectMapper objectMapper;

    @Tool(description = "根据任务ID查询WCS主任务详情，返回任务状态、处理阶段、起终点、容器号、时间戳等核心信息")
    public String getTaskDetail(@ToolParam(description = "WCS任务ID或WMS任务号") String taskId) {
        long start = System.currentTimeMillis();
        try {
            log.info("TaskDetailTool 开始查询, taskId={}, env={}", taskId, envConfig.getActiveEnv());
            TaskDetail detail = taskClient.getTaskDetail(taskId, envConfig.getActiveEnv());
            ObjectNode node = objectMapper.createObjectNode();
            node.put("taskId", detail.getTaskId());
            node.put("taskNo", detail.getTaskNo());
            node.put("taskSource", detail.getTaskSource());
            node.put("bizType", detail.getBizType());
            node.put("taskType", detail.getTaskType());
            node.put("taskState", detail.getTaskState());
            node.put("containerCode", detail.getContainerCode());
            node.put("startNode", detail.getStartNode());
            node.put("endNode", detail.getEndNode());
            node.put("errorMessage", detail.getErrorMessage());
            node.put("priority", detail.getPriority());
            node.put("createTime", str(detail.getCreateTime()));
            node.put("splitTime", str(detail.getSplitTime()));
            node.put("startTime", str(detail.getStartTime()));
            node.put("finishTime", str(detail.getFinishTime()));
            String result = objectMapper.writeValueAsString(node);
            log.info("TaskDetailTool 查询完成, 耗时={}ms", System.currentTimeMillis() - start);
            return result;
        } catch (Exception e) {
            log.warn("查询主任务详情失败, 耗时={}ms, error={}", System.currentTimeMillis() - start, e.getMessage(), e);
            return "查询主任务详情失败: " + e.getMessage() + "。请检查任务ID是否正确或尝试其他诊断方式。";
        }
    }

    private String str(Object obj) {
        return obj != null ? obj.toString() : null;
    }
}
