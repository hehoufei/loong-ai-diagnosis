package cn.aimstek.loong.aidiag.controller;

import cn.aimstek.loong.aidiag.common.BaseResponse;
import cn.aimstek.loong.aidiag.common.Response;
import cn.aimstek.loong.aidiag.config.PlatformProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * 平台代理 Controller。
 * 透传前端查询请求到 loong-platform 的 SchedulerAdminController，不做任何业务逻辑。
 */
@Slf4j
@Tag(name = "平台代理接口", description = "透传请求到 loong-platform")
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/platform")
public class PlatformProxyController {

    private final PlatformProperties platformProperties;
    private final RestTemplate restTemplate;

    /**
     * 拼接 loong-platform 完整 URL。
     *
     * @param path 相对路径，如 "/api/admin/scheduler/task/pageTasks"
     * @return 完整 URL
     */
    private String platformUrl(String path) {
        String baseUrl = platformProperties.getBaseUrl();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return baseUrl + path;
    }

    /**
     * 任务分页查询（代理）。
     * 透传请求到 loong-platform 的 POST /api/admin/scheduler/task/pageTasks。
     *
     * @param body 前端传入的 JSON 请求体（直接透传，不做反序列化）
     * @return 平台响应体（直接透传 JSON）
     */
    @Operation(summary = "任务分页查询（代理）")
    @PostMapping("/task/page")
    public ResponseEntity<String> pageTask(@RequestBody String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(body, headers);

        String url = platformUrl("/api/admin/scheduler/task/pageTasks");
        log.debug("代理请求 -> POST {}", url);

        ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

        if (!response.getStatusCode().is2xxSuccessful()) {
            log.error("平台返回非2xx状态: {}, body: {}", response.getStatusCode(), response.getBody());
            throw new RestClientException("平台返回非2xx状态: " + response.getStatusCode());
        }

        return ResponseEntity.ok(response.getBody());
    }

    /**
     * 大任务详情（代理）。
     * 透传请求到 loong-platform 的 GET /api/admin/scheduler/task/detail/getTaskDetail。
     *
     * @param taskNo 任务编号
     * @return 平台响应体（直接透传 JSON）
     */
    @Operation(summary = "大任务详情（代理）")
    @GetMapping("/task/detail")
    public ResponseEntity<String> taskDetail(@RequestParam String taskNo) {
        String url = platformUrl("/api/admin/scheduler/task/detail/getTaskDetail?taskNo=" + taskNo);
        log.debug("代理请求 -> GET {}", url);
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
        return ResponseEntity.ok(response.getBody());
    }

    /**
     * 子任务+指令详情（代理）。
     * 透传请求到 loong-platform 的 GET /api/admin/scheduler/item/getTaskItemDetails。
     *
     * @param taskNo 任务编号
     * @return 平台响应体（直接透传 JSON）
     */
    @Operation(summary = "子任务+指令详情（代理）")
    @GetMapping("/task/items")
    public ResponseEntity<String> taskItems(@RequestParam String taskNo) {
        String url = platformUrl("/api/admin/scheduler/item/getTaskItemDetails?taskNo=" + taskNo);
        log.debug("代理请求 -> GET {}", url);
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
        return ResponseEntity.ok(response.getBody());
    }

    /**
     * 任务组信息（代理）。
     * 透传请求到 loong-platform 的 GET /api/admin/scheduler/task/getTasksByGroupCode。
     *
     * @param groupCode 任务组编码
     * @return 平台响应体（直接透传 JSON）
     */
    @Operation(summary = "任务组信息（代理）")
    @GetMapping("/task/group")
    public ResponseEntity<String> taskGroup(@RequestParam String groupCode) {
        String url = platformUrl("/api/admin/scheduler/task/getTasksByGroupCode?groupCode=" + groupCode);
        log.debug("代理请求 -> GET {}", url);
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
        return ResponseEntity.ok(response.getBody());
    }

    /**
     * 查询下拉参数（代理）。
     * 透传请求到 loong-platform 的 GET /api/admin/scheduler/getTaskQueryParam。
     *
     * @return 平台响应体（直接透传 JSON）
     */
    @Operation(summary = "查询下拉参数（代理）")
    @GetMapping("/task/queryParams")
    public ResponseEntity<String> taskQueryParams() {
        String url = platformUrl("/api/admin/scheduler/getTaskQueryParam");
        log.debug("代理请求 -> GET {}", url);
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
        return ResponseEntity.ok(response.getBody());
    }

    /**
     * 代理调用失败时统一返回 503。
     */
    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<Response<Object>> handleProxyException(RestClientException ex) {
        log.error("平台代理调用失败: {}", ex.getMessage(), ex);
        Response<Object> body = BaseResponse.failure("PLATFORM_PROXY_ERROR", "平台服务不可达: " + ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
