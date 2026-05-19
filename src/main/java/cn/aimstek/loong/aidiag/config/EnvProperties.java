package cn.aimstek.loong.aidiag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * 多环境配置绑定。
 * 支持配置多个环境（开发/测试/生产），前端可切换 API 请求前缀。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "loong.env")
public class EnvProperties {

    /** 当前激活的环境名称 */
    private String active = "default";

    /** 环境列表 */
    private List<EnvConfig> envs = new ArrayList<>();

    @Data
    public static class EnvConfig {
        /** 环境名称，如 dev / test / prod */
        private String name;
        /** 环境显示标签 */
        private String label;
        /** WCS 平台服务地址（前端展示用） */
        private String wcsUrl;
        /** 诊断服务 API 前缀（前端请求时拼接，默认空） */
        private String apiPrefix = "";
    }
}
