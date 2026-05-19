package cn.aimstek.loong.aidiag.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * PlatformProperties 单元测试。
 * 重点验证 {@link PlatformProperties#getEffectiveMapBaseUrl()} 的回退语义：
 * - mapBaseUrl 未配置（null 或空串）时回退到 baseUrl
 * - mapBaseUrl 配置时使用 mapBaseUrl
 */
class PlatformPropertiesTest {

    @Test
    void getEffectiveMapBaseUrl_fallsBackToBaseUrl_whenMapBaseUrlIsNull() {
        PlatformProperties props = new PlatformProperties();
        props.setBaseUrl("http://platform:18081");
        props.setMapBaseUrl(null);

        assertEquals("http://platform:18081", props.getEffectiveMapBaseUrl());
    }

    @Test
    void getEffectiveMapBaseUrl_fallsBackToBaseUrl_whenMapBaseUrlIsEmptyString() {
        // 对应 YAML 中 ${MAP_BASE_URL:} 环境变量未设置时解析为空字符串的场景
        PlatformProperties props = new PlatformProperties();
        props.setBaseUrl("http://platform:18081");
        props.setMapBaseUrl("");

        assertEquals("http://platform:18081", props.getEffectiveMapBaseUrl());
    }

    @Test
    void getEffectiveMapBaseUrl_fallsBackToBaseUrl_whenMapBaseUrlIsBlank() {
        PlatformProperties props = new PlatformProperties();
        props.setBaseUrl("http://platform:18081");
        props.setMapBaseUrl("   ");

        assertEquals("http://platform:18081", props.getEffectiveMapBaseUrl());
    }

    @Test
    void getEffectiveMapBaseUrl_usesMapBaseUrl_whenConfigured() {
        PlatformProperties props = new PlatformProperties();
        props.setBaseUrl("http://platform:18081");
        props.setMapBaseUrl("http://map-service:28080");

        assertEquals("http://map-service:28080", props.getEffectiveMapBaseUrl());
    }

    @Test
    void getEffectiveMapBaseUrl_usesDefaultBaseUrl_whenNothingConfigured() {
        // 默认 baseUrl 为 http://127.0.0.1:18081，mapBaseUrl 默认 null
        PlatformProperties props = new PlatformProperties();

        assertEquals("http://127.0.0.1:18081", props.getEffectiveMapBaseUrl());
    }
}
