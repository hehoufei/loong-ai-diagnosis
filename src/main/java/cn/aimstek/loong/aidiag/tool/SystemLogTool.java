package cn.aimstek.loong.aidiag.tool;

import cn.aimstek.loong.aidiag.client.LogClient;
import cn.aimstek.loong.aidiag.config.EnvConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

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
        long start = System.currentTimeMillis();
        try {
            log.info("SystemLogTool 开始查询, wmsTaskNo={}, env={}", wmsTaskNo, envConfig.getActiveEnv());
            // 日志已由下游按时间正序返回；不要再对字符串排序，否则会把毫秒/级别字段位置打散
            List<String> logs = logClient.queryLogs(wmsTaskNo, envConfig.getActiveEnv());
            String result = objectMapper.writeValueAsString(logs);
            log.info("SystemLogTool 查询完成, 耗时={}ms, 条数={}", System.currentTimeMillis() - start, logs.size());
            return result;
        } catch (Exception e) {
            log.warn("查询系统日志失败, 耗时={}ms, error={}", System.currentTimeMillis() - start, e.getMessage(), e);
            return "查询系统日志失败: " + e.getMessage() + "。请检查任务号是否正确或尝试其他诊断方式。";
        }
    }
}
