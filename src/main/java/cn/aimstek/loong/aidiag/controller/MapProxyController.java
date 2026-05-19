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
 * 地图代理 Controller。
 * 透传前端地图请求到 loong-platform 的地图接口，供 map.html 使用。
 */
@Slf4j
@Tag(name = "地图代理接口", description = "透传地图请求到 loong-platform")
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/map")
public class MapProxyController {

    private final PlatformProperties platformProperties;
    private final RestTemplate restTemplate;

    /**
     * 拼接 loong-platform 地图服务完整 URL。
     *
     * @param path 相对路径，如 "/api/map/nodeList"
     * @return 完整 URL
     */
    private String mapUrl(String path) {
        String baseUrl = platformProperties.getEffectiveMapBaseUrl();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return baseUrl + path;
    }

    /**
     * 地图节点列表（代理）。
     * 透传请求到 loong-platform 的地图节点列表接口，供 map.html 使用。
     *
     * @param body 前端传入的 JSON 请求体（直接透传，不做反序列化）
     * @return 平台响应体（直接透传 JSON）
     */
    @Operation(summary = "地图节点列表（代理）")
    @PostMapping("/nodeList")
    public ResponseEntity<String> nodeList(@RequestBody String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(body, headers);

        // TODO: 需要确认 loong-platform 的实际地图节点接口路径
        String url = mapUrl("/api/map/nodeList");
        log.debug("代理请求 -> POST {}", url);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                log.error("地图服务返回非2xx状态: {}, body: {}", response.getStatusCode(), response.getBody());
                throw new RestClientException("地图服务返回非2xx状态: " + response.getStatusCode());
            }

            return ResponseEntity.ok(response.getBody());
        } catch (RestClientException ex) {
            log.error("地图节点列表代理调用失败: {}", ex.getMessage(), ex);
            throw ex;
        }
    }

    /**
     * 地图视图详情（代理）。
     * 透传请求到 loong-platform 的地图视图详情接口，供 map.html 使用。
     *
     * @param body 前端传入的 JSON 请求体（直接透传，不做反序列化）
     * @return 平台响应体（直接透传 JSON）
     */
    @Operation(summary = "地图视图详情（代理）")
    @PostMapping("/viewDetail")
    public ResponseEntity<String> viewDetail(@RequestBody String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(body, headers);

        // TODO: 需要确认 loong-platform 的实际地图视图详情接口路径
        String url = mapUrl("/api/map/viewDetail");
        log.debug("代理请求 -> POST {}", url);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                log.error("地图服务返回非2xx状态: {}, body: {}", response.getStatusCode(), response.getBody());
                throw new RestClientException("地图服务返回非2xx状态: " + response.getStatusCode());
            }

            return ResponseEntity.ok(response.getBody());
        } catch (RestClientException ex) {
            log.error("地图视图详情代理调用失败: {}", ex.getMessage(), ex);
            throw ex;
        }
    }

    /**
     * 代理调用失败时统一返回 503。
     */
    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<Response<Object>> handleProxyException(RestClientException ex) {
        log.error("地图代理调用失败: {}", ex.getMessage(), ex);
        Response<Object> body = BaseResponse.failure("MAP_PROXY_ERROR", "地图服务不可达: " + ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}