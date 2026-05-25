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
 *
 * 配置文件路径：~/.loong-ai-diagnosis/env-config.json
 * 文件结构：
 *   {
 *     "activeEnv": "default",
 *     "envs": [
 *       { "name": "default", "wcsUrl": "http://host:port" }
 *     ]
 *   }
 *
 * 兼容性说明：
 *   wcsUrl 字段名保留（已被多个 Client 引用，避免破坏既有持久化配置），
 *   但在 loong-platform 重构版本中，该地址实际指向调度服务（scheduler）
 *   的 HTTP 入口，而不再是旧 WCS 服务。
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
        /**
         * 外部服务基础地址，如 http://192.168.53.206:8088
         * 字段名保留 wcsUrl 以兼容既有 env-config.json 持久化文件，
         * 实际指向 loong-platform 调度服务（scheduler）的 HTTP 入口。
         */
        private String wcsUrl;
        /**
         * 环境对应的数据库 JDBC URL，例如 jdbc:mysql://192.168.53.222:3306/loong_platform
         * 为空时回退到 application.yml 中的默认 spring.datasource.url。
         */
        private String jdbcUrl;
        /** 数据库用户名，为空时回退默认配置。*/
        private String dbUsername;
        /** 数据库密码，为空时回退默认配置。*/
        private String dbPassword;
        /** SSH 主机（默认同 wcsUrl 中的 host） */
        private String sshHost;
        /** SSH 端口（默认 22） */
        private Integer sshPort;
        /** SSH 用户名 */
        private String sshUsername;
        /** SSH 密码 */
        private String sshPassword;
        /** 日志文件路径（默认 /aims/loong/loong-main/logs/schedule_loong-platform_all.log） */
        private String logFilePath;
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

    /**
     * 获取当前激活环境的外部服务地址。
     * 方法名保留 getWcsUrl 以兼容 Client 层调用，实际返回的是
     * loong-platform 调度服务（scheduler）的基础 URL。
     */
    public String getWcsUrl() {
        EnvItem item = envs.get(activeEnv);
        return item != null ? item.getWcsUrl() : "http://localhost:8088";
    }

    public String getActiveEnv() {
        return activeEnv;
    }

    /** 返回当前激活环境（可能为 null）。*/
    public EnvItem getActiveEnvItem() {
        return envs.get(activeEnv);
    }

    /** 按名称获取环境配置。*/
    public EnvItem getEnv(String name) {
        return name == null ? null : envs.get(name);
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
