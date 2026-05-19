package cn.aimstek.loong.aidiag.rule;

import cn.aimstek.loong.aidiag.context.DiagnosisContext;
import cn.aimstek.loong.aidiag.dto.DiagnoseResponse;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 可配置规则引擎。
 * 按优先级排序执行规则，首个命中即返回。
 * 统计用内存 ConcurrentHashMap，不落库。
 */
@Slf4j
@Service
public class ConfigurableRuleEngine implements RuleEngine {

    private List<DiagnoseRule> sortedRules;
    private final List<DiagnoseRule> allRules;
    private final RuleProperties properties;

    /** 每条规则的运行时统计 */
    private final Map<String, RuleStats> ruleStatsMap = new ConcurrentHashMap<>();
    /** 总诊断次数 */
    private final AtomicLong totalDiagnosisCount = new AtomicLong();
    /** 总诊断耗时（纳秒） */
    private final AtomicLong totalDiagnosisDurationNanos = new AtomicLong();
    /** 最近命中的规则名列表（最多保留10条） */
    private final LinkedList<String> recentMatchedRules = new LinkedList<>();

    /** 运行时优先级覆盖（内存生效，不持久化） */
    private final Map<String, Integer> priorityOverrides = new ConcurrentHashMap<>();
    /** 运行时启用/禁用覆盖（内存生效，不持久化） */
    private final Map<String, Boolean> enabledOverrides = new ConcurrentHashMap<>();

    public ConfigurableRuleEngine(List<DiagnoseRule> rules, RuleProperties properties) {
        this.properties = properties;
        this.allRules = new ArrayList<>(rules);
        this.sortedRules = buildActiveRules(rules);
        log.info("规则引擎初始化完成，共加载 {} 条规则: {}",
                sortedRules.size(),
                sortedRules.stream()
                        .map(r -> r.getName() + "(P" + getEffectivePriority(r) + ")")
                        .collect(Collectors.joining(", ")));
    }

    @Override
    public DiagnoseResponse evaluate(DiagnosisContext context) {
        long startNanos = System.nanoTime();
        totalDiagnosisCount.incrementAndGet();

        try {
            for (DiagnoseRule rule : sortedRules) {
                try {
                    if (rule.match(context)) {
                        long ruleStartNanos = System.nanoTime();
                        log.info("RULE_MATCHED traceId={} rule={}", context.getTraceId(), rule.getName());

                        DiagnoseResponse response = rule.diagnose(context);
                        long ruleDurationNanos = System.nanoTime() - ruleStartNanos;

                        // 更新规则统计
                        ruleStatsMap.computeIfAbsent(rule.getName(), k -> new RuleStats())
                                .recordHit(ruleDurationNanos);

                        // 记录最近命中
                        synchronized (recentMatchedRules) {
                            recentMatchedRules.addFirst(rule.getName());
                            if (recentMatchedRules.size() > 10) {
                                recentMatchedRules.removeLast();
                            }
                        }

                        if (response != null) {
                            return response;
                        }
                        log.warn("规则 {} 匹配成功但诊断返回 null，继续下一条", rule.getName());
                    }
                } catch (Exception e) {
                    log.warn("规则 {} 执行异常: {}，跳过", rule.getName(), e.getMessage(), e);
                }
            }
            return null;
        } finally {
            totalDiagnosisDurationNanos.addAndGet(System.nanoTime() - startNanos);
        }
    }

    // ==================== 统计数据暴露 ====================

    /**
     * 获取所有规则的运行时信息（含统计）。
     */
    public List<RuleInfo> getRuleInfoList() {
        return allRules.stream().map(rule -> {
            RuleInfo info = new RuleInfo();
            info.setName(rule.getName());
            info.setPriority(getEffectivePriority(rule));
            info.setEnabled(isEnabled(rule));
            info.setDescription(rule.getDescription());

            RuleStats stats = ruleStatsMap.get(rule.getName());
            if (stats != null) {
                info.setHitCount(stats.getHitCount());
                info.setAvgDurationMs(stats.getAvgDurationMs());
                info.setLastHitAt(stats.getLastHitAt());
            }
            return info;
        }).collect(Collectors.toList());
    }

    /**
     * 获取规则引擎聚合统计。
     */
    public EngineStats getEngineStats() {
        EngineStats stats = new EngineStats();
        stats.setActiveRuleCount((int) allRules.stream().filter(this::isEnabled).count());
        stats.setTotalDiagnosisCount(totalDiagnosisCount.get());

        long totalCount = totalDiagnosisCount.get();
        if (totalCount > 0) {
            stats.setAvgDurationMs((double) totalDiagnosisDurationNanos.get() / totalCount / 1_000_000.0);
        }

        synchronized (recentMatchedRules) {
            stats.setRecentMatchedRules(new ArrayList<>(recentMatchedRules));
        }
        return stats;
    }

    /**
     * 获取规则命中统计（兼容旧接口）。
     */
    public Map<String, AtomicLong> getHitCountMap() {
        Map<String, AtomicLong> map = new ConcurrentHashMap<>();
        ruleStatsMap.forEach((name, stats) -> map.put(name, new AtomicLong(stats.getHitCount())));
        return map;
    }

    /**
     * 获取已加载的规则列表。
     */
    public List<DiagnoseRule> getLoadedRules() {
        return sortedRules;
    }

    // ==================== 运行时管理 ====================

    /**
     * 更新规则优先级（内存生效，不持久化）。
     */
    public void setRulePriority(String ruleName, int priority) {
        priorityOverrides.put(ruleName, priority);
        rebuildSortedRules();
        log.info("规则 {} 优先级已更新为 {}", ruleName, priority);
    }

    /**
     * 启用/禁用规则（内存生效，不持久化）。
     */
    public void setRuleEnabled(String ruleName, boolean enabled) {
        enabledOverrides.put(ruleName, enabled);
        rebuildSortedRules();
        log.info("规则 {} 已{}", ruleName, enabled ? "启用" : "禁用");
    }

    /**
     * 重新从 YAML 加载规则配置（清除运行时覆盖）。
     */
    public void reloadRules() {
        priorityOverrides.clear();
        enabledOverrides.clear();
        rebuildSortedRules();
        log.info("规则配置已重新加载，当前活跃规则 {} 条", sortedRules.size());
    }

    /**
     * 重置所有命中统计计数器。
     */
    public void resetStats() {
        ruleStatsMap.clear();
        totalDiagnosisCount.set(0);
        totalDiagnosisDurationNanos.set(0);
        synchronized (recentMatchedRules) {
            recentMatchedRules.clear();
        }
        log.info("规则引擎统计已重置");
    }

    /**
     * 检查规则是否存在。
     */
    public boolean ruleExists(String ruleName) {
        return allRules.stream().anyMatch(r -> r.getName().equals(ruleName));
    }

    // ==================== 内部方法 ====================

    private void rebuildSortedRules() {
        this.sortedRules = buildActiveRules(allRules);
    }

    private List<DiagnoseRule> buildActiveRules(List<DiagnoseRule> rules) {
        return rules.stream()
                .filter(this::isEnabled)
                .sorted(Comparator.comparingInt(this::getEffectivePriority).reversed())
                .collect(Collectors.toList());
    }

    private boolean isEnabled(DiagnoseRule rule) {
        // 运行时覆盖优先
        Boolean override = enabledOverrides.get(rule.getName());
        if (override != null) {
            return override;
        }
        RuleProperties.RuleConfig config = properties.getRules().get(rule.getName());
        return config == null || config.isEnabled();
    }

    private int getEffectivePriority(DiagnoseRule rule) {
        // 运行时覆盖优先
        Integer override = priorityOverrides.get(rule.getName());
        if (override != null) {
            return override;
        }
        RuleProperties.RuleConfig config = properties.getRules().get(rule.getName());
        if (config != null && config.getPriority() != -1) {
            return config.getPriority();
        }
        return rule.getPriority();
    }

    // ==================== 内部数据类 ====================

    /**
     * 单条规则的运行时统计。
     */
    @Data
    public static class RuleStats {
        private long hitCount;
        private long totalDurationNanos;
        private Instant lastHitAt;

        public synchronized void recordHit(long durationNanos) {
            hitCount++;
            totalDurationNanos += durationNanos;
            lastHitAt = Instant.now();
        }

        public double getAvgDurationMs() {
            return hitCount > 0 ? (double) totalDurationNanos / hitCount / 1_000_000.0 : 0;
        }
    }

    /**
     * 规则信息 DTO（供 Controller 使用）。
     */
    @Data
    public static class RuleInfo {
        private String name;
        private int priority;
        private boolean enabled;
        private String description;
        private long hitCount;
        private double avgDurationMs;
        private Instant lastHitAt;
    }

    /**
     * 规则引擎聚合统计 DTO。
     */
    @Data
    public static class EngineStats {
        private int activeRuleCount;
        private long totalDiagnosisCount;
        private double avgDurationMs;
        private List<String> recentMatchedRules;
    }
}
