package cn.aimstek.loong.aidiag.tool;

import cn.aimstek.loong.aidiag.client.TaskClient;
import cn.aimstek.loong.aidiag.config.EnvConfig;
import cn.aimstek.loong.aidiag.dto.TaskDetail;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Agent 工具：查询指定主任务下的所有子任务列表
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskItemsTool {

    private final TaskClient taskClient;
    private final EnvConfig envConfig;
    private final ObjectMapper objectMapper;

    @Tool(description = "查询指定主任务下的所有子任务列表，返回每个子任务的状态、设备信息、起终点和时间戳")
    public String getTaskItems(@ToolParam(description = "WCS任务ID") String taskId) {
        long start = System.currentTimeMillis();
        try {
            log.info("TaskItemsTool 开始查询, taskId={}, env={}", taskId, envConfig.getActiveEnv());
            TaskDetail detail = taskClient.getTaskDetail(taskId, envConfig.getActiveEnv());
            String result = objectMapper.writeValueAsString(detail.getTaskItems());
            log.info("TaskItemsTool 查询完成, 耗时={}ms, 条数={}", System.currentTimeMillis() - start,
                    detail.getTaskItems() == null ? 0 : detail.getTaskItems().size());
            return result;
        } catch (Exception e) {
            log.warn("查询子任务列表失败, 耗时={}ms, error={}", System.currentTimeMillis() - start, e.getMessage(), e);
            return "查询子任务列表失败: " + e.getMessage() + "。请检查任务ID是否正确或尝试其他诊断方式。";
        }
    }
}
