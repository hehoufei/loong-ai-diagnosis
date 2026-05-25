package cn.aimstek.loong.aidiag.client;

import cn.aimstek.loong.aidiag.config.EnvConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Primary
@Component
@RequiredArgsConstructor
public class HttpLogClient implements LogClient {

    private final EnvConfig envConfig;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FMT_MS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    @Override
    public List<String> queryLogs(String taskNo, String env) {
        if (taskNo == null || taskNo.isBlank()) {
            return new ArrayList<>();
        }

        // 方式1：尝试 /task/queryCommandByTaskNo
        List<String> logs = tryQueryCommandLogs(
                envConfig.getWcsUrl() + "/task/queryCommandByTaskNo?taskNo=" + taskNo);
        if (!logs.isEmpty()) {
            return logs;
        }

        // 方式2：备用 /task/queryBatchCommand
        logs = tryQueryBatchCommandLogs(taskNo);
        if (!logs.isEmpty()) {
            return logs;
        }

        log.info("任务{}未查询到指令日志数据", taskNo);
        return new ArrayList<>();
    }

    private List<String> tryQueryCommandLogs(String url) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>("{}", headers);
            String json = restTemplate.postForObject(url, entity, String.class);
            JsonNode root = objectMapper.readTree(json);
            JsonNode data = root.path("data");
            return extractLogLines(data);
        } catch (Exception e) {
            log.warn("查询指令日志失败({}): {}", url, e.getMessage());
            return new ArrayList<>();
        }
    }

    private List<String> tryQueryBatchCommandLogs(String taskNo) {
        String url = envConfig.getWcsUrl() + "/task/queryBatchCommand";
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            Map<String, Object> body = new HashMap<>();
            body.put("taskNo", taskNo);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            String json = restTemplate.postForObject(url, entity, String.class);
            JsonNode root = objectMapper.readTree(json);
            JsonNode data = root.path("data");
            JsonNode records = data.isArray() ? data : data.path("records");
            return extractLogLines(records);
        } catch (Exception e) {
            log.warn("备用指令日志查询失败({}): {}", url, e.getMessage());
            return new ArrayList<>();
        }
    }

    private List<String> extractLogLines(JsonNode dataNode) {
        List<String> logs = new ArrayList<>();
        if (dataNode != null && dataNode.isArray()) {
            for (JsonNode node : dataNode) {
                String line = buildLogLine(node);
                if (line != null) {
                    logs.add(line);
                }
            }
        }
        return logs;
    }

    /**
     * 将单条指令记录转化为日志行：
     * "yyyy-MM-dd HH:mm:ss [设备:DV_001][指令:CMD001] 状态: ISSUED"
     */
    private String buildLogLine(JsonNode node) {
        LocalDateTime time = parseTime(text(node, "createTime"));
        if (time == null) {
            time = parseTime(text(node, "startTime"));
        }
        String deviceCode = text(node, "deviceCode");
        String commandNo = text(node, "commandNo");
        String taskItemNo = text(node, "taskItemNo");
        String commandState = text(node, "commandState");
        String commandResult = text(node, "commandResult");
        String reportState = text(node, "reportState");

        StringBuilder sb = new StringBuilder();
        if (time != null) {
            sb.append(time.format(FMT));
        }
        if (deviceCode != null && !deviceCode.isEmpty()) {
            sb.append(" [设备:").append(deviceCode).append("]");
        }
        if (taskItemNo != null && !taskItemNo.isEmpty()) {
            sb.append("[子任务:").append(taskItemNo).append("]");
        }
        if (commandNo != null && !commandNo.isEmpty()) {
            sb.append("[指令:").append(commandNo).append("]");
        }
        if (commandState != null) {
            sb.append(" 状态: ").append(commandState);
        }
        if (reportState != null) {
            sb.append(" 上报: ").append(reportState);
        }
        if (commandResult != null && !commandResult.isEmpty()) {
            sb.append(" 结果: ").append(commandResult);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v != null && !v.isNull()) ? v.asText() : null;
    }

    private LocalDateTime parseTime(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return LocalDateTime.parse(s, FMT_MS);
        } catch (Exception e) {
            try {
                return LocalDateTime.parse(s, FMT);
            } catch (Exception e2) {
                try {
                    return LocalDateTime.parse(s);
                } catch (Exception ex) {
                    return null;
                }
            }
        }
    }
}
