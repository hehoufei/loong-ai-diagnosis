package cn.aimstek.loong.aidiag.experiment;

import cn.aimstek.loong.aidiag.config.AgentProperties;
import cn.aimstek.loong.aidiag.experiment.ExperimentProfile.*;
import cn.aimstek.loong.aidiag.rule.ConfigurableRuleEngine;
import cn.aimstek.loong.aidiag.rule.RuleProperties;
import cn.aimstek.loong.aidiag.rule.RuleStatistics;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 实验方案管理服务，支持保存/加载/对比规则配置快照
 */
@Slf4j
@Service
public class ExperimentService {

    private final RuleProperties ruleProperties;
    private final ConfigurableRuleEngine ruleEngine;
    private final AgentProperties agentProperties;
    private final ObjectMapper objectMapper;

    public ExperimentService(RuleProperties ruleProperties,
                             ConfigurableRuleEngine ruleEngine,
                             AgentProperties agentProperties) {
        this.ruleProperties = ruleProperties;
        this.ruleEngine = ruleEngine;
        this.agentProperties = agentProperties;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * 将当前运行配置保存为实验方案
     */
    public ExperimentProfile saveCurrentAsProfile(String name, String description) {
        ExperimentProfile profile = ExperimentProfile.builder()
                .id(UUID.randomUUID().toString())
                .name(name)
                .description(description)
                .createdAt(LocalDateTime.now())
                .ruleConfigs(snapshotRuleConfigs())
                .docSearchConfig(snapshotDocSearchConfig())
                .statisticsSnapshot(snapshotStatistics())
                .build();

        saveProfile(profile);
        log.info("已保存实验方案: {} ({})", name, profile.getId());
        return profile;
    }

    /**
     * 列出所有实验方案，按创建时间倒序
     */
    public List<ExperimentProfile> listProfiles() {
        Path dir = getExperimentsDir();
        if (!Files.exists(dir)) {
            return Collections.emptyList();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(p -> p.toString().endsWith(".json"))
                    .map(this::loadProfile)
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(ExperimentProfile::getCreatedAt).reversed())
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.error("列出实验方案失败: {}", e.getMessage(), e);
            throw new RuntimeException("列出实验方案失败", e);
        }
    }

    /**
     * 获取指定实验方案
     */
    public ExperimentProfile getProfile(String id) {
        Path file = getExperimentsDir().resolve(id + ".json");
        if (!Files.exists(file)) {
            throw new RuntimeException("实验方案不存在: " + id);
        }
        ExperimentProfile profile = loadProfile(file);
        if (profile == null) {
            throw new RuntimeException("实验方案加载失败: " + id);
        }
        return profile;
    }

    /**
     * 删除指定实验方案
     */
    public void deleteProfile(String id) {
        Path file = getExperimentsDir().resolve(id + ".json");
        try {
            if (Files.deleteIfExists(file)) {
                log.info("已删除实验方案: {}", id);
            } else {
                throw new RuntimeException("实验方案不存在: " + id);
            }
        } catch (IOException e) {
            log.error("删除实验方案失败: {}", e.getMessage(), e);
            throw new RuntimeException("删除实验方案失败", e);
        }
    }

    /**
     * 应用指定实验方案到当前运行配置
     */
    public synchronized void applyProfile(String id) {
        ExperimentProfile profile = getProfile(id);

        // 清空当前 rules，用快照覆盖
        ruleProperties.getRules().clear();
        profile.getRuleConfigs().forEach((name, snapshot) -> {
            RuleProperties.RuleConfig config = new RuleProperties.RuleConfig();
            config.setEnabled(snapshot.isEnabled());
            config.setPriority(snapshot.getPriority());
            config.setParams(snapshot.getParams() != null ? new HashMap<>(snapshot.getParams()) : new HashMap<>());
            ruleProperties.getRules().put(name, config);
        });

        // 覆盖 docSearch 配置
        DocSearchConfigSnapshot dsc = profile.getDocSearchConfig();
        if (dsc != null) {
            RuleProperties.DocSearchConfig docSearch = ruleProperties.getDocSearch();
            docSearch.setTopK(dsc.getTopK());
            docSearch.setSimilarityThreshold(dsc.getSimilarityThreshold());
            docSearch.setPreSearchEnabled(dsc.isPreSearchEnabled());
            docSearch.setHybridEnabled(dsc.isHybridEnabled());
            docSearch.setVectorWeight(dsc.getVectorWeight());
            docSearch.setKeywordWeight(dsc.getKeywordWeight());
        }

        // 重新加载规则引擎
        ruleEngine.reloadRules();
        log.info("已应用实验方案: {} ({})", profile.getName(), id);
    }

    /**
     * 对比两个实验方案
     */
    public Map<String, Object> compareProfiles(String idA, String idB) {
        ExperimentProfile profileA = getProfile(idA);
        ExperimentProfile profileB = getProfile(idB);

        Map<String, Object> result = new LinkedHashMap<>();

        // 基本信息
        Map<String, Object> infoA = new LinkedHashMap<>();
        infoA.put("name", profileA.getName());
        infoA.put("createdAt", profileA.getCreatedAt() != null
                ? profileA.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null);
        result.put("profileA", infoA);

        Map<String, Object> infoB = new LinkedHashMap<>();
        infoB.put("name", profileB.getName());
        infoB.put("createdAt", profileB.getCreatedAt() != null
                ? profileB.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null);
        result.put("profileB", infoB);

        // 统计数据对比
        result.put("statisticsComparison", buildStatisticsComparison(profileA, profileB));

        // 配置差异
        result.put("configDifferences", buildConfigDifferences(profileA, profileB));

        return result;
    }

    /**
     * 对指定实验方案拍摄当前统计数据快照
     */
    public void takeStatisticsSnapshot(String profileId) {
        ExperimentProfile profile = getProfile(profileId);
        profile.setStatisticsSnapshot(snapshotStatistics());
        saveProfile(profile);
        log.info("已更新实验方案统计快照: {} ({})", profile.getName(), profileId);
    }

    // ======================== 辅助方法 ========================

    /**
     * 获取/创建实验方案存储目录
     */
    private Path getExperimentsDir() {
        String configPath = agentProperties.getVectorStorePath();
        String baseDir;

        if (configPath != null && !configPath.isBlank()) {
            // 使用向量存储路径的父目录
            baseDir = new File(configPath).getParent();
        } else {
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isBlank()) {
                baseDir = appData + File.separator + "wcs-diagnosis";
            } else {
                baseDir = System.getProperty("user.home") + File.separator + ".wcs-diagnosis";
            }
        }

        Path dir = Path.of(baseDir, "experiments");
        if (!Files.exists(dir)) {
            try {
                Files.createDirectories(dir);
                log.info("已创建实验方案存储目录: {}", dir);
            } catch (IOException e) {
                log.error("创建实验方案存储目录失败: {}", e.getMessage(), e);
                throw new RuntimeException("创建实验方案存储目录失败", e);
            }
        }
        return dir;
    }

    /**
     * 将实验方案序列化写入 JSON 文件
     */
    private void saveProfile(ExperimentProfile profile) {
        Path file = getExperimentsDir().resolve(profile.getId() + ".json");
        try {
            objectMapper.writeValue(file.toFile(), profile);
        } catch (IOException e) {
            log.error("保存实验方案失败: {}", e.getMessage(), e);
            throw new RuntimeException("保存实验方案失败", e);
        }
    }

    /**
     * 从 JSON 文件反序列化实验方案
     */
    private ExperimentProfile loadProfile(Path file) {
        try {
            return objectMapper.readValue(file.toFile(), ExperimentProfile.class);
        } catch (IOException e) {
            log.error("加载实验方案失败 [{}]: {}", file.getFileName(), e.getMessage(), e);
            return null;
        }
    }

    // ======================== 快照构建 ========================

    private Map<String, RuleConfigSnapshot> snapshotRuleConfigs() {
        Map<String, RuleConfigSnapshot> snapshot = new LinkedHashMap<>();
        ruleProperties.getRules().forEach((name, config) ->
                snapshot.put(name, RuleConfigSnapshot.builder()
                        .enabled(config.isEnabled())
                        .priority(config.getPriority())
                        .params(config.getParams() != null ? new HashMap<>(config.getParams()) : new HashMap<>())
                        .build()));
        return snapshot;
    }

    private DocSearchConfigSnapshot snapshotDocSearchConfig() {
        RuleProperties.DocSearchConfig ds = ruleProperties.getDocSearch();
        return DocSearchConfigSnapshot.builder()
                .topK(ds.getTopK())
                .similarityThreshold(ds.getSimilarityThreshold())
                .preSearchEnabled(ds.isPreSearchEnabled())
                .hybridEnabled(ds.isHybridEnabled())
                .vectorWeight(ds.getVectorWeight())
                .keywordWeight(ds.getKeywordWeight())
                .build();
    }

    private List<StatisticsSnapshot> snapshotStatistics() {
        return ruleEngine.getStatistics().stream()
                .map(s -> StatisticsSnapshot.builder()
                        .ruleName(s.getRuleName())
                        .priority(s.getPriority())
                        .enabled(s.isEnabled())
                        .hitCount(s.getHitCount().get())
                        .avgMatchTimeMs(s.getAvgMatchTimeMs())
                        .avgDiagnoseTimeMs(s.getAvgDiagnoseTimeMs())
                        .lastHitTime(s.getLastHitTime() != null
                                ? s.getLastHitTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null)
                        .matchErrorCount(s.getMatchErrorCount().get())
                        .diagnoseErrorCount(s.getDiagnoseErrorCount().get())
                        .build())
                .collect(Collectors.toList());
    }

    // ======================== 对比逻辑 ========================

    private List<Map<String, Object>> buildStatisticsComparison(ExperimentProfile a, ExperimentProfile b) {
        Map<String, StatisticsSnapshot> mapA = indexStatistics(a.getStatisticsSnapshot());
        Map<String, StatisticsSnapshot> mapB = indexStatistics(b.getStatisticsSnapshot());

        Set<String> allRules = new LinkedHashSet<>();
        allRules.addAll(mapA.keySet());
        allRules.addAll(mapB.keySet());

        List<Map<String, Object>> comparisons = new ArrayList<>();
        for (String ruleName : allRules) {
            StatisticsSnapshot sa = mapA.get(ruleName);
            StatisticsSnapshot sb = mapB.get(ruleName);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ruleName", ruleName);
            item.put("hitCountA", sa != null ? sa.getHitCount() : 0);
            item.put("hitCountB", sb != null ? sb.getHitCount() : 0);
            item.put("hitCountDiff", (sb != null ? sb.getHitCount() : 0) - (sa != null ? sa.getHitCount() : 0));
            item.put("hitCountChangePercent", calcChangePercent(
                    sa != null ? sa.getHitCount() : 0, sb != null ? sb.getHitCount() : 0));

            item.put("avgMatchTimeMsA", sa != null ? sa.getAvgMatchTimeMs() : 0);
            item.put("avgMatchTimeMsB", sb != null ? sb.getAvgMatchTimeMs() : 0);
            item.put("avgMatchTimeMsDiff",
                    (sb != null ? sb.getAvgMatchTimeMs() : 0) - (sa != null ? sa.getAvgMatchTimeMs() : 0));
            item.put("avgMatchTimeMsChangePercent", calcChangePercent(
                    sa != null ? sa.getAvgMatchTimeMs() : 0, sb != null ? sb.getAvgMatchTimeMs() : 0));

            item.put("avgDiagnoseTimeMsA", sa != null ? sa.getAvgDiagnoseTimeMs() : 0);
            item.put("avgDiagnoseTimeMsB", sb != null ? sb.getAvgDiagnoseTimeMs() : 0);
            item.put("avgDiagnoseTimeMsDiff",
                    (sb != null ? sb.getAvgDiagnoseTimeMs() : 0) - (sa != null ? sa.getAvgDiagnoseTimeMs() : 0));
            item.put("avgDiagnoseTimeMsChangePercent", calcChangePercent(
                    sa != null ? sa.getAvgDiagnoseTimeMs() : 0, sb != null ? sb.getAvgDiagnoseTimeMs() : 0));

            comparisons.add(item);
        }
        return comparisons;
    }

    private List<Map<String, Object>> buildConfigDifferences(ExperimentProfile a, ExperimentProfile b) {
        Map<String, RuleConfigSnapshot> cfgA = a.getRuleConfigs() != null ? a.getRuleConfigs() : Collections.emptyMap();
        Map<String, RuleConfigSnapshot> cfgB = b.getRuleConfigs() != null ? b.getRuleConfigs() : Collections.emptyMap();

        Set<String> allRules = new LinkedHashSet<>();
        allRules.addAll(cfgA.keySet());
        allRules.addAll(cfgB.keySet());

        List<Map<String, Object>> diffs = new ArrayList<>();
        for (String ruleName : allRules) {
            RuleConfigSnapshot ca = cfgA.get(ruleName);
            RuleConfigSnapshot cb = cfgB.get(ruleName);

            List<String> changes = new ArrayList<>();

            if (ca == null) {
                changes.add("仅存在于方案B");
            } else if (cb == null) {
                changes.add("仅存在于方案A");
            } else {
                if (ca.isEnabled() != cb.isEnabled()) {
                    changes.add("enabled: " + ca.isEnabled() + " → " + cb.isEnabled());
                }
                if (ca.getPriority() != cb.getPriority()) {
                    changes.add("priority: " + ca.getPriority() + " → " + cb.getPriority());
                }
                if (!Objects.equals(ca.getParams(), cb.getParams())) {
                    changes.add("params: " + ca.getParams() + " → " + cb.getParams());
                }
            }

            if (!changes.isEmpty()) {
                Map<String, Object> diff = new LinkedHashMap<>();
                diff.put("ruleName", ruleName);
                diff.put("changes", changes);
                diffs.add(diff);
            }
        }
        return diffs;
    }

    private Map<String, StatisticsSnapshot> indexStatistics(List<StatisticsSnapshot> list) {
        if (list == null) return Collections.emptyMap();
        Map<String, StatisticsSnapshot> map = new LinkedHashMap<>();
        for (StatisticsSnapshot s : list) {
            map.put(s.getRuleName(), s);
        }
        return map;
    }

    private String calcChangePercent(double base, double current) {
        if (base == 0 && current == 0) return "0%";
        if (base == 0) return "+∞";
        double pct = ((current - base) / base) * 100;
        return String.format("%+.1f%%", pct);
    }
}
