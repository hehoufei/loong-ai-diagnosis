package cn.aimstek.loong.aidiag.tool;

import cn.aimstek.loong.aidiag.client.LogClient;
import cn.aimstek.loong.aidiag.config.EnvConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Agent 工具：查询指定任务相关的系统日志记录，按时间排序
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemLogTool {

    private final LogClient logClient;
    private final EnvConfig envConfig;
    private final ObjectMapper objectMapper;

    @Tool(description = "查询指定任务相关的系统日志记录，按时间排序，用于分析任务执行过程中的异常和错误")
    public String queryLogs(@ToolParam(description = "WMS任务号") String wmsTaskNo) {
        try {
            List<String> logs = logClient.queryLogs(wmsTaskNo, envConfig.getActiveEnv());
            // LogClient 返回的日志每条以时间戳开头，按自然字符串排序即为时间排序
            Collections.sort(logs);
            return objectMapper.writeValueAsString(logs);
        } catch (Exception e) {
            log.warn("查询系统日志失败: {}", e.getMessage());
            return "查询系统日志失败: " + e.getMessage() + "。请检查任务号是否正确或尝试其他诊断方式。";
        }
    }
}
