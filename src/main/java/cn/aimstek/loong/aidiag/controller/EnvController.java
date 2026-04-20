package cn.aimstek.loong.aidiag.controller;

import cn.aimstek.loong.aidiag.config.EnvConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/env")
@RequiredArgsConstructor
public class EnvController {

    private final EnvConfig envConfig;

    @GetMapping("/list")
    public Map<String, Object> list() {
        return Map.of(
            "active", envConfig.getActiveEnv(),
            "envs", envConfig.listEnvs()
        );
    }

    @PostMapping("/switch")
    public Map<String, Object> switchEnv(@RequestBody Map<String, String> body) {
        envConfig.switchEnv(body.get("name"));
        return Map.of("active", envConfig.getActiveEnv(), "wcsUrl", envConfig.getWcsUrl());
    }

    @PostMapping("/save")
    public Map<String, Object> save(@RequestBody EnvConfig.EnvItem item) {
        envConfig.saveEnv(item);
        return Map.of("success", true);
    }

    @DeleteMapping("/{name}")
    public Map<String, Object> delete(@PathVariable String name) {
        envConfig.deleteEnv(name);
        return Map.of("success", true);
    }
}
