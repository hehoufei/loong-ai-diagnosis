package cn.aimstek.loong.aidiag.controller;

import cn.aimstek.loong.aidiag.config.EnvConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * 代理地图 API 请求，避免前端跨域问题
 */
@RestController
@RequestMapping("/api/v1/map")
@RequiredArgsConstructor
public class MapProxyController {

    private final EnvConfig envConfig;
    private final RestTemplate restTemplate;

    @GetMapping("/viewList")
    public ResponseEntity<String> getViewList() {
        String url = envConfig.getWcsUrl() + "/api/map/config/queryViewList";
        ResponseEntity<String> resp = restTemplate.getForEntity(url, String.class);
        return ResponseEntity.ok(resp.getBody());
    }

    @PostMapping("/viewDetail")
    public ResponseEntity<String> getViewDetail(@RequestBody(required = false) Map<String, Object> body) {
        String url = envConfig.getWcsUrl() + "/api/map/config/queryViewDetail";
        ResponseEntity<String> resp = restTemplate.postForEntity(url, body, String.class);
        return ResponseEntity.ok(resp.getBody());
    }

    @PostMapping("/nodeList")
    public ResponseEntity<String> getNodeList(@RequestBody(required = false) Map<String, Object> body) {
        String url = envConfig.getWcsUrl() + "/api/map/node/queryBasicNodeList";
        ResponseEntity<String> resp = restTemplate.postForEntity(url, body, String.class);
        return ResponseEntity.ok(resp.getBody());
    }
}
