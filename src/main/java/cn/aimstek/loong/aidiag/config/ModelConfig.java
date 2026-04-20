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
 * 模型配置管理：支持多模型供应商切换，配置持久化到本地文件。
 * 支持 ZhiPuAI（内置）和任何兼容 OpenAI 协议的模型。
 */
@Slf4j
@Component
public class ModelConfig {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, ModelItem> models = new ConcurrentHashMap<>();
    private volatile String activeModel = "zhipuai-default";

    @Data
    public static class ModelItem {
        private String name;          // 唯一标识，如 "zhipuai-default"
        private String displayName;   // 显示名称，如 "智谱 GLM-4.7-Flash"
        private String provider;      // "zhipuai" 或 "openai-compatible"
        private String baseUrl;       // API 地址（OpenAI 兼容模式需要）
        private String apiKey;        // API Key
        private String model;         // 模型名称，如 "glm-4.7-flash"、"deepseek-chat"
        private Double temperature;   // 温度参数（可选，默认 0.1）
    }

    @PostConstruct
    public void init() {
        File file = getConfigFile();
        if (file.exists()) {
            try {
                ConfigData data = objectMapper.readValue(file, ConfigData.class);
                if (data.models != null) data.models.forEach(m -> models.put(m.getName(), m));
                if (data.activeModel != null) activeModel = data.activeModel;
                log.info("加载模型配置: {} 个模型, 当前: {}", models.size(), activeModel);
            } catch (Exception e) {
                log.warn("加载模型配置失败: {}", e.getMessage());
            }
        }
        // 确保有默认的 ZhiPuAI 模型
        if (!models.containsKey("zhipuai-default")) {
            ModelItem def = new ModelItem();
            def.setName("zhipuai-default");
            def.setDisplayName("智谱 GLM-4.7-Flash");
            def.setProvider("zhipuai");
            def.setModel("glm-4.7-flash");
            def.setTemperature(0.1);
            models.put("zhipuai-default", def);
            save();
        }
    }

    public ModelItem getActiveModelItem() {
        ModelItem item = models.get(activeModel);
        return item != null ? item : models.values().iterator().next();
    }

    public String getActiveModelName() {
        return activeModel;
    }

    public List<ModelItem> listModels() {
        return new ArrayList<>(models.values());
    }

    public void switchModel(String name) {
        if (models.containsKey(name)) {
            activeModel = name;
            save();
            log.info("切换模型: {}", name);
        }
    }

    public void saveModel(ModelItem item) {
        models.put(item.getName(), item);
        save();
    }

    public void deleteModel(String name) {
        if (!"zhipuai-default".equals(name)) {
            models.remove(name);
            if (activeModel.equals(name)) activeModel = "zhipuai-default";
            save();
        }
    }

    private void save() {
        try {
            ConfigData data = new ConfigData();
            data.activeModel = activeModel;
            data.models = new ArrayList<>(models.values());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(getConfigFile(), data);
        } catch (Exception e) {
            log.warn("保存模型配置失败: {}", e.getMessage());
        }
    }

    private File getConfigFile() {
        String home = System.getProperty("user.home");
        File dir = new File(home, ".loong-ai-diagnosis");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "model-config.json");
    }

    @Data
    static class ConfigData {
        String activeModel;
        List<ModelItem> models;
    }
}
