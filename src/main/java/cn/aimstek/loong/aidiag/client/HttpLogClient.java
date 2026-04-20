package cn.aimstek.loong.aidiag.client;

import cn.aimstek.loong.aidiag.config.EnvConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Primary
@Component
@RequiredArgsConstructor
public class HttpLogClient implements LogClient {

    private final EnvConfig envConfig;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public List<String> queryLogs(String wmsTaskNo, String env) {
        List<String> logs = new ArrayList<>();
        String url = envConfig.getWcsUrl() + "/sys/log/getLogByWmsTaskNO/" + wmsTaskNo;
        try {
            String json = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(json);
            JsonNode data = root.path("data");
            if (data.isArray()) {
                for (JsonNode node : data) {
                    String time = node.has("createTime") && !node.get("createTime").isNull()
                        ? node.get("createTime").asText() : "";
                    String itemNo = node.has("tasItemNo") && !node.get("tasItemNo").isNull()
                        ? node.get("tasItemNo").asText() : "";
                    String ticketNo = node.has("ticketNo") && !node.get("ticketNo").isNull()
                        ? node.get("ticketNo").asText() : "";
                    String message = node.has("message") && !node.get("message").isNull()
                        ? node.get("message").asText() : "";

                    StringBuilder sb = new StringBuilder();
                    sb.append(time);
                    if (!itemNo.isEmpty()) sb.append(" [子任务:").append(itemNo).append("]");
                    if (!ticketNo.isEmpty()) sb.append("[执行单:").append(ticketNo).append("]");
                    sb.append(" ").append(message);
                    logs.add(sb.toString());
                }
            }
        } catch (Exception e) {
            log.warn("查询日志失败: {}", e.getMessage());
        }
        return logs;
    }
}
