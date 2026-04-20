package cn.aimstek.loong.aidiag.rule;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = "rule-engine")
public class RuleProperties {

    /**
     * 规则配置映射，key 为规则名称（与 DiagnoseRule.getName() 对应）
     */
    private Map<String, RuleConfig> rules = new HashMap<>();

    /**
     * 文档检索配置
     */
    private DocSearchConfig docSearch = new DocSearchConfig();

    @Data
    public static class RuleConfig {
        /** 是否启用该规则 */
        private boolean enabled = true;
        /** 优先级覆盖（-1 表示使用代码中的默认值） */
        private int priority = -1;
        /** 规则特定参数 */
        private Map<String, String> params = new HashMap<>();
        /** 规则中文描述 */
        private String description;
        /** SpEL条件表达式（仅表达式规则使用，内置规则为null） */
        private String condition;
        /** 诊断输出配置 */
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

    @Data
    public static class DocSearchConfig {
        /** 返回文档数量 */
        private int topK = 3;
        /** 相似度阈值 */
        private double similarityThreshold = 0.6;
        /** 是否启用规则前预检索 */
        private boolean preSearchEnabled = true;
        /** 是否启用混合检索 */
        private boolean hybridEnabled = true;
        /** 向量检索权重（用于加权融合，预留） */
        private double vectorWeight = 0.6;
        /** 关键词检索权重（用于加权融合，预留） */
        private double keywordWeight = 0.4;
    }
}
