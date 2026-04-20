package cn.aimstek.loong.aidiag.experiment;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * A/B 测试实验方案，保存规则配置和统计数据的快照
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExperimentProfile {

    /** 方案唯一标识 (UUID) */
    private String id;

    /** 方案名称 */
    private String name;

    /** 方案描述 */
    private String description;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 规则配置快照 — 复制自 RuleProperties 的 rules map */
    private Map<String, RuleConfigSnapshot> ruleConfigs;

    /** 文档检索配置快照 */
    private DocSearchConfigSnapshot docSearchConfig;

    /** 统计数据快照 */
    private List<StatisticsSnapshot> statisticsSnapshot;

    /**
     * 规则配置快照
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class RuleConfigSnapshot {
        private boolean enabled;
        private int priority;
        private Map<String, String> params;
    }

    /**
     * 文档检索配置快照
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DocSearchConfigSnapshot {
        private int topK;
        private double similarityThreshold;
        private boolean preSearchEnabled;
        private boolean hybridEnabled;
        private double vectorWeight;
        private double keywordWeight;
    }

    /**
     * 规则统计数据快照
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class StatisticsSnapshot {
        private String ruleName;
        private int priority;
        private boolean enabled;
        private long hitCount;
        private double avgMatchTimeMs;
        private double avgDiagnoseTimeMs;
        /** 最后命中时间，ISO-8601 字符串 */
        private String lastHitTime;
        private long matchErrorCount;
        private long diagnoseErrorCount;
    }
}
