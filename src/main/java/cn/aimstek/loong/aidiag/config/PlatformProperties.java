package cn.aimstek.loong.aidiag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * loong-platform HTTP 客户端配置。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "loong.platform")
public class PlatformProperties {

    /** 平台服务基础 URL，如 http://127.0.0.1:18081 */
    private String baseUrl = "http://127.0.0.1:18081";

    /** HTTP 连接超时（毫秒） */
    private int connectTimeoutMs = 3000;

    /** HTTP 读取超时（毫秒） */
    private int readTimeoutMs = 5000;

    /** 地图服务基础 URL，默认与 platform baseUrl 相同 */
    private String mapBaseUrl;

    /**
     * 获取地图服务的有效基础 URL。
     * 如果 mapBaseUrl 未配置（null 或空字符串），则回退到 baseUrl。
     * 注意：YAML 中 {@code ${MAP_BASE_URL:}} 在环境变量未设置时会解析为空字符串，
     * 因此此处必须同时处理 null 与空字符串两种情况。
     */
    public String getEffectiveMapBaseUrl() {
        if (mapBaseUrl == null || mapBaseUrl.trim().isEmpty()) {
            return baseUrl;
        }
        return mapBaseUrl;
    }
}
