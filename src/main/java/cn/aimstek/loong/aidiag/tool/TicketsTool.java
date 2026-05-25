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

import java.util.List;

/**
 * Agent 工具：查询指定子任务下的所有执行单
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketsTool {

    private final TaskClient taskClient;
    private final EnvConfig envConfig;
    private final ObjectMapper objectMapper;

    @Tool(description = "查询指定主任务下的所有命令，返回命令状态、PLC任务号、设备信息和时间戳。")
    public String getTickets(@ToolParam(description = "WCS任务ID（主任务ID），将返回该任务下所有命令") String taskId) {
        long start = System.currentTimeMillis();
        try {
            log.info("TicketsTool 开始查询, taskId={}, env={}", taskId, envConfig.getActiveEnv());
            TaskDetail detail = taskClient.getTaskDetail(taskId, envConfig.getActiveEnv());
            List<TaskDetail.CommandDetail> commands = detail.getCommands();
            String result = objectMapper.writeValueAsString(commands);
            log.info("TicketsTool 查询完成, 耗时={}ms, 条数={}", System.currentTimeMillis() - start,
                    commands == null ? 0 : commands.size());
            return result;
        } catch (Exception e) {
            log.warn("查询命令列表失败, 耗时={}ms, error={}", System.currentTimeMillis() - start, e.getMessage(), e);
            return "查询命令列表失败: " + e.getMessage() + "。请检查任务ID是否正确或尝试其他诊断方式。";
        }
    }
}
