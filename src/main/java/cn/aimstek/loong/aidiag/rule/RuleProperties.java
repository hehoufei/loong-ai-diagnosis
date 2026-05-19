package cn.aimstek.loong.aidiag.rule;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则配置绑定（YAML）。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "rule-engine")
public class RuleProperties {

    private Map<String, RuleConfig> rules = new HashMap<>();

    @Data
    public static class RuleConfig {
        private boolean enabled = true;
        private int priority = -1;
        private String description;
        private Map<String, String> params = new HashMap<>();
        private OutputConfig output;
    }

    @Data
    public static class OutputConfig {
        private String summary;
        private List<RootCauseConfig> rootCauses;
        private List<String> actions;
    }

    @Data
    public static class RootCauseConfig {
        private String title;
        private String description;
    }
}
