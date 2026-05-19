package cn.aimstek.loong.aidiag.controller;

import cn.aimstek.loong.aidiag.config.EnvProperties;
import cn.aimstek.loong.aidiag.config.PlatformProperties;
import cn.aimstek.loong.aidiag.common.BaseResponse;
import cn.aimstek.loong.aidiag.common.Response;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 环境配置接口。
 * 供前端查询可用环境列表、切换激活环境、以及运行时更新平台 URL。
 */
@Slf4j
@Tag(name = "环境配置接口", description = "多环境切换支持")
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/env")
public class EnvController {

    private final EnvProperties envProperties;
    private final PlatformProperties platformProperties;

    /**
     * 获取环境列表及当前激活环境，同时返回当前平台 URL。
     */
    @Operation(summary = "获取环境列表")
    @GetMapping("/list")
    public EnvListResponse list() {
        EnvListResponse resp = new EnvListResponse();
        resp.setActive(envProperties.getActive());
        resp.setPlatformUrl(platformProperties.getBaseUrl());
        resp.setMapUrl(platformProperties.getEffectiveMapBaseUrl());
        resp.setEnvs(envProperties.getEnvs().stream().map(e -> {
            EnvItem item = new EnvItem();
            item.setName(e.getName());
            item.setLabel(e.getLabel());
            item.setWcsUrl(e.getWcsUrl());
            item.setApiPrefix(e.getApiPrefix());
            return item;
        }).collect(Collectors.toList()));
        return resp;
    }

    /**
     * 切换当前激活环境，同时将对应环境的 wcsUrl 更新到 PlatformProperties（内存生效）。
     */
    @Operation(summary = "切换激活环境")
    @PostMapping("/switch")
    public EnvListResponse switchEnv(@RequestBody SwitchRequest request) {
        envProperties.getEnvs().stream()
                .filter(e -> e.getName().equals(request.getName()))
                .findFirst()
                .ifPresent(e -> {
                    envProperties.setActive(e.getName());
                    if (e.getWcsUrl() != null && !e.getWcsUrl().isBlank()) {
                        platformProperties.setBaseUrl(e.getWcsUrl());
                        log.info("切换环境 {} → platformUrl={}", e.getName(), e.getWcsUrl());
                    }
                });
        return list();
    }

    /**
     * 直接更新平台 URL（内存生效，重启后恢复配置文件设置）。
     */
    @Operation(summary = "更新平台 URL")
    @PostMapping("/platform-url")
    public Response<Void> updatePlatformUrl(@RequestBody UpdateUrlRequest request) {
        if (request.getBaseUrl() != null && !request.getBaseUrl().isBlank()) {
            platformProperties.setBaseUrl(request.getBaseUrl().trim());
            log.info("平台 URL 已更新为: {}", request.getBaseUrl());
        }
        if (request.getMapBaseUrl() != null) {
            platformProperties.setMapBaseUrl(
                request.getMapBaseUrl().isBlank() ? null : request.getMapBaseUrl().trim()
            );
            log.info("地图 URL 已更新为: {}", request.getMapBaseUrl());
        }
        return BaseResponse.success(null);
    }

    // ===== DTO =====

    @Data
    public static class EnvListResponse {
        private String active;
        private String platformUrl;
        private String mapUrl;
        private List<EnvItem> envs;
    }

    @Data
    public static class EnvItem {
        private String name;
        private String label;
        private String wcsUrl;
        private String apiPrefix;
    }

    @Data
    public static class SwitchRequest {
        private String name;
    }

    @Data
    public static class UpdateUrlRequest {
        private String baseUrl;
        private String mapBaseUrl;
    }
}
