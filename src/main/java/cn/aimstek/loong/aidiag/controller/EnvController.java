package cn.aimstek.loong.aidiag.controller;

import cn.aimstek.loong.aidiag.config.EnvConfig;
import cn.aimstek.loong.aidiag.service.DataSourceManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/env")
@RequiredArgsConstructor
public class EnvController {

    private final EnvConfig envConfig;
    private final DataSourceManager dataSourceManager;

    @GetMapping("/list")
    public Map<String, Object> list() {
        return Map.of(
            "active", envConfig.getActiveEnv(),
            "envs", envConfig.listEnvs()
        );
    }

    @PostMapping("/switch")
    public Map<String, Object> switchEnv(@RequestBody Map<String, String> body) {
        String name = body.get("name");
        envConfig.switchEnv(name);
        // 切换环境后联动切换数据源；若新环境未配置 JDBC 则回退到默认连接
        try {
            dataSourceManager.switchTo(envConfig.getActiveEnvItem());
        } catch (Exception e) {
            log.warn("切换数据源失败: {}", e.getMessage());
        }
        return Map.of(
            "active", envConfig.getActiveEnv(),
            "wcsUrl", envConfig.getWcsUrl()
        );
    }

    @PostMapping("/save")
    public Map<String, Object> save(@RequestBody EnvConfig.EnvItem item) {
        if (item == null || item.getName() == null || item.getName().isBlank()) {
            return Map.of("success", false, "error", "环境名称不能为空");
        }
        // 合并保存：当字段未提供时，保留既有值。这样前端在编辑时无需回传敏感字段（密码等）。
        EnvConfig.EnvItem existing = envConfig.getEnv(item.getName());
        if (existing != null) {
            if (isBlank(item.getWcsUrl()))     item.setWcsUrl(existing.getWcsUrl());
            if (isBlank(item.getJdbcUrl()))    item.setJdbcUrl(existing.getJdbcUrl());
            if (isBlank(item.getDbUsername())) item.setDbUsername(existing.getDbUsername());
            if (isBlank(item.getDbPassword())) item.setDbPassword(existing.getDbPassword());
            if (isBlank(item.getSshHost()))    item.setSshHost(existing.getSshHost());
            if (item.getSshPort() == null)     item.setSshPort(existing.getSshPort());
            if (isBlank(item.getSshUsername()))item.setSshUsername(existing.getSshUsername());
            if (isBlank(item.getSshPassword()))item.setSshPassword(existing.getSshPassword());
            if (isBlank(item.getLogFilePath()))item.setLogFilePath(existing.getLogFilePath());
        }
        envConfig.saveEnv(item);
        // 若保存的是当前激活环境，立即刷新数据源以应用最新连接信息
        if (item.getName().equals(envConfig.getActiveEnv())) {
            try {
                dataSourceManager.switchTo(envConfig.getActiveEnvItem());
            } catch (Exception e) {
                log.warn("刷新数据源失败: {}", e.getMessage());
            }
        }
        return Map.of("success", true);
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @DeleteMapping("/{name}")
    public Map<String, Object> delete(@PathVariable String name) {
        boolean wasActive = name != null && name.equals(envConfig.getActiveEnv());
        envConfig.deleteEnv(name);
        // 如果删除的是当前激活环境，EnvConfig 会回退到 default，需要联动刷新数据源
        if (wasActive) {
            try {
                dataSourceManager.switchTo(envConfig.getActiveEnvItem());
            } catch (Exception e) {
                log.warn("刷新数据源失败: {}", e.getMessage());
            }
        }
        return Map.of("success", true);
    }
}
