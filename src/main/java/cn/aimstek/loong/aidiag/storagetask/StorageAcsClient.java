package cn.aimstek.loong.aidiag.storagetask;

import cn.aimstek.loong.aidiag.storagetask.dto.StorageTaskConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 调度服务 addTask 接口客户端
 * 用 HttpURLConnection, 不引新的依赖.
 */
@Slf4j
public class StorageAcsClient {

    /** addTask 调用业务失败时抛出, 调用方可据此判断是否重试 */
    public static class AddTaskException extends RuntimeException {
        private final String code;
        private final boolean retryable;
        public AddTaskException(String code, String message, boolean retryable) {
            super(message);
            this.code = code;
            this.retryable = retryable;
        }
        public String getCode() { return code; }
        public boolean isRetryable() { return retryable; }
    }

    private final StorageTaskConfig cfg;
    private final ObjectMapper mapper;

    public StorageAcsClient(StorageTaskConfig cfg, ObjectMapper mapper) {
        this.cfg = cfg;
        this.mapper = mapper;
    }

    /**
     * 下发任务. 成功返回响应正文, 失败抛异常.
     */
    public String addTask(String taskNo, String taskType, String startNode, String endNode, String remark) {
        Map<String, Object> body = buildBody(taskNo, taskType, startNode, endNode, remark);
        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException("addTask 序列化失败: " + e.getMessage(), e);
        }

        HttpURLConnection conn = null;
        try {
            URL url = URI.create(cfg.getAcsAddTaskUrl()).toURL();
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(cfg.getHttpTimeoutSeconds() * 1000);
            conn.setReadTimeout(cfg.getHttpTimeoutSeconds() * 1000);
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            String respBody = readBody(conn);
            if (code >= 300) {
                throw new AddTaskException("HTTP_" + code,
                        "addTask HTTP " + code + ": " + respBody, false);
            }
            // 解析 body, 业务码非 0/200/SUCCESS 视为失败
            checkBusinessCode(respBody);
            log.info("addTask {} -> HTTP {} body={}", taskNo, code, abbreviate(respBody, 200));
            return respBody;
        } catch (AddTaskException e) {
            throw e;
        } catch (IOException e) {
            // 网络层异常, 视为可重试
            throw new AddTaskException("NETWORK", "addTask 网络异常: " + e.getMessage(), true);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 检查响应体业务码. 接口返回 {"code":"500","msg":"xxx",...} 时抛出 AddTaskException.
     * 仅当 code 为 0 / 200 / SUCCESS / null 视为成功.
     */
    private void checkBusinessCode(String respBody) {
        if (respBody == null || respBody.isBlank()) return;
        try {
            JsonNode root = mapper.readTree(respBody);
            JsonNode codeNode = root.get("code");
            if (codeNode == null || codeNode.isNull()) return;
            String code = codeNode.asText();
            if (code == null || code.isBlank()) return;
            // 成功值
            if ("0".equals(code) || "200".equals(code) || "SUCCESS".equalsIgnoreCase(code)) return;
            JsonNode msgNode = root.get("msg");
            String msg = msgNode == null ? "" : msgNode.asText();
            // body 业务码非成功 -> 抛错; 这种情况一律标记 retryable=true, 由用户手动决定是否重试
            throw new AddTaskException("BIZ_" + code, "ACS 业务失败 code=" + code + " msg=" + msg, true);
        } catch (AddTaskException e) {
            throw e;
        } catch (Exception e) {
            // 解析失败, 不阻塞 (兼容老接口可能返回非 json)
            log.debug("解析 addTask 响应体失败 (忽略): {}", e.getMessage());
        }
    }

    private Map<String, Object> buildBody(String taskNo, String taskType, String startNode, String endNode, String remark) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskNo", taskNo);
        body.put("taskSource", cfg.getTaskSource());
        body.put("taskType", taskType);
        body.put("bizType", cfg.getTaskBizType());
        body.put("bizPriority", 0);
        body.put("independentRun", "");
        body.put("preStartTaskNo", "");
        body.put("preEndTaskNo", "");
        body.put("startNode", startNode);
        body.put("endNode", endNode);
        body.put("requiredFunctionList", new ArrayList<>());

        // container
        Map<String, Object> container = new LinkedHashMap<>();
        container.put("containerCode", cfg.getContainerCode());
        container.put("containerType", "PALLET");
        container.put("size", Map.of("length", "1160", "width", "1160", "height", "16", "unit", "cm"));
        container.put("weight", Map.of("value", "22", "unit", "kg"));
        container.put("innerSize", Map.of("length", "25", "width", "35", "height", "15", "unit", "cm"));
        container.put("loadHeightOffset", Map.of("length", "50", "unit", "cm"));
        body.put("containerList", List.of(container));

        // goods
        Map<String, Object> goods = new LinkedHashMap<>();
        goods.put("goodsCode", cfg.getGoodsCode());
        goods.put("goodsType", "GOODS");
        goods.put("size", Map.of("length", "80", "width", "90", "height", "10", "unit", "cm"));
        goods.put("weight", Map.of("value", "22", "unit", "kg"));
        body.put("goodsInfoList", List.of(goods));

        body.put("expectedStartTime", "");
        body.put("expectedFinishTime", "");
        body.put("remark", remark);
        return body;
    }

    private String readBody(HttpURLConnection conn) {
        try {
            java.io.InputStream is = (conn.getResponseCode() >= 400) ? conn.getErrorStream() : conn.getInputStream();
            if (is == null) return "";
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
