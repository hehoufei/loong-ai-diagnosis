package cn.aimstek.loong.aidiag.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 环境配置管理：支持多环境切换，配置持久化到本地文件
 */
@Slf4j
@Component
public class EnvConfig {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, EnvItem> envs = new ConcurrentHashMap<>();
    private volatile String activeEnv = "default";

    @Data
    public static class EnvItem {
        private String name;
        private String wcsUrl;  // WCS 服务地址，如 http://192.168.53.206:8088
    }

    @PostConstruct
    public void init() {
        File file = getConfigFile();
        if (file.exists()) {
            try {
                ConfigData data = objectMapper.readValue(file, ConfigData.class);
                if (data.envs != null) data.envs.forEach(e -> envs.put(e.getName(), e));
                if (data.activeEnv != null) activeEnv = data.activeEnv;
                log.info("加载环境配置: {} 个环境, 当前: {}", envs.size(), activeEnv);
            } catch (Exception e) {
                log.warn("加载环境配置失败: {}", e.getMessage());
            }
        }
        if (envs.isEmpty()) {
            EnvItem def = new EnvItem();
            def.setName("default");
            def.setWcsUrl("http://192.168.53.206:8088");
            envs.put("default", def);
            save();
        }
    }

    public String getWcsUrl() {
        EnvItem item = envs.get(activeEnv);
        return item != null ? item.getWcsUrl() : "http://localhost:8088";
    }

    public String getActiveEnv() {
        return activeEnv;
    }

    public List<EnvItem> listEnvs() {
        return new ArrayList<>(envs.values());
    }

    public void switchEnv(String name) {
        if (envs.containsKey(name)) {
            activeEnv = name;
            save();
        }
    }

    public void saveEnv(EnvItem item) {
        envs.put(item.getName(), item);
        save();
    }

    public void deleteEnv(String name) {
        if (!"default".equals(name)) {
            envs.remove(name);
            if (activeEnv.equals(name)) activeEnv = "default";
            save();
        }
    }

    private void save() {
        try {
            ConfigData data = new ConfigData();
            data.activeEnv = activeEnv;
            data.envs = new ArrayList<>(envs.values());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(getConfigFile(), data);
        } catch (Exception e) {
            log.warn("保存环境配置失败: {}", e.getMessage());
        }
    }

    private File getConfigFile() {
        String home = System.getProperty("user.home");
        File dir = new File(home, ".loong-ai-diagnosis");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "env-config.json");
    }

    @Data
    static class ConfigData {
        String activeEnv;
        List<EnvItem> envs;
    }
}
