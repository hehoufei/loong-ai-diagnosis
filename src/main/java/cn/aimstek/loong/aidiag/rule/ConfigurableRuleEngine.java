package cn.aimstek.loong.aidiag.rule;

import cn.aimstek.loong.aidiag.dto.DiagnoseResponse;
import cn.aimstek.loong.aidiag.dto.RootCauseItem;
import cn.aimstek.loong.aidiag.service.RuleEngineBootstrapService;
import cn.aimstek.loong.aidiag.service.RuleEngineSnapshotService;
import cn.aimstek.loong.aidiag.service.RulePriorityService;
import cn.aimstek.loong.aidiag.service.RuleStatisticsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ConfigurableRuleEngine implements RuleEngine {

    private final List<DiagnoseRule> beanRules;
    private final List<DiagnoseRule> allRules;
    private final RuleProperties properties;
    private final RulePriorityService rulePriorityService;
    private final RuleEngineSnapshotService snapshotService;
    private final RuleEngineBootstrapService bootstrapService;
    private final RuleStatisticsService statisticsService;
    private volatile List<DiagnoseRule> sortedRules;
    private final Map<String, RuleStatistics> statisticsMap = new ConcurrentHashMap<>();

    /** 上次生效的配置快照，用于检测变更 */
    private volatile String lastConfigSnapshot;

    /**
     * Spring 自动注入所有 DiagnoseRule Bean，根据 RuleProperties 过滤和排序
     */
    public ConfigurableRuleEngine(List<DiagnoseRule> rules,
                                  RuleProperties properties,
                                  RulePriorityService rulePriorityService,
                                  RuleEngineSnapshotService snapshotService,
                                  RuleEngineBootstrapService bootstrapService,
                                  RuleStatisticsService statisticsService) {
        this.beanRules = new ArrayList<>(rules);
        this.allRules = new ArrayList<>(rules);
        this.properties = properties;
        this.rulePriorityService = rulePriorityService;
        this.snapshotService = snapshotService;
        this.bootstrapService = bootstrapService;
        this.statisticsService = statisticsService;
        rebuildRules();
        log.info("规则引擎初始化完成，共加载 {} 条规则（Bean {} 条，总注册 {} 条）: {}",
                sortedRules.size(), beanRules.size(), allRules.size(),
                sortedRules.stream()
                        .map(r -> r.getName() + "(P" + getEffectivePriority(r, properties) + ")")
                        .collect(Collectors.joining(", ")));
    }

    @Override
    public DiagnoseResponse evaluate(DiagnosisContext context) {
        for (DiagnoseRule rule : sortedRules) {
            RuleStatistics stats = statisticsMap.get(rule.getName());
            try {
                long matchStart = System.currentTimeMillis();
                boolean matched = rule.match(context);
                long matchTime = System.currentTimeMillis() - matchStart;
                if (stats != null) stats.addMatchTime(matchTime);

                if (matched) {
                    log.info("规则命中: {} (优先级: {})", rule.getName(), getEffectivePriority(rule, properties));
                    try {
                        long diagnoseStart = System.currentTimeMillis();
                        DiagnoseResponse response = rule.diagnose(context);
                        long diagnoseTime = System.currentTimeMillis() - diagnoseStart;

                        if (stats != null) {
                            stats.recordHit();
                            stats.addDiagnoseTime(diagnoseTime);
                        }

                        if (response != null) {
                            return response;
                        }
                        log.warn("规则 {} 匹配成功但诊断返回null，继续尝试下一条规则", rule.getName());
                    } catch (Exception e) {
                        log.warn("规则 {} 诊断执行异常: {}，继续尝试下一条规则", rule.getName(), e.getMessage(), e);
                        if (stats != null) stats.recordDiagnoseError();
                    }
                }
            } catch (Exception e) {
                log.warn("规则 {} 匹配检查异常: {}，跳过该规则", rule.getName(), e.getMessage(), e);
                if (stats != null) stats.recordMatchError();
            }
        }

        // 所有规则都未命中或执行异常时的终极兜底
        log.warn("所有规则均未命中，返回终极兜底结果");
        return buildUltimateFallback(context);
    }

    /**
     * 定时检查配置变更，如有变化则重新加载规则（每30秒）
     */
    @Scheduled(fixedRate = 30000)
    public void checkAndReload() {
        String currentSnapshot = buildConfigSnapshot(properties);
        if (!currentSnapshot.equals(lastConfigSnapshot)) {
            log.info("检测到规则引擎配置变更，开始重新加载规则...");
            reloadRules();
        }
    }

    /**
     * 重新加载规则：根据最新配置重新过滤和排序
     */
    public void reloadRules() {
        rebuildRules();
        log.info("规则引擎重新加载完成，当前活跃规则 {} 条: {}",
                sortedRules.size(),
                sortedRules.stream()
                        .map(r -> r.getName() + "(P" + getEffectivePriority(r, properties) + ")")
                        .collect(Collectors.joining(", ")));
    }

    private void rebuildRules() {
        this.sortedRules = buildActiveRules(allRules, properties);
        this.lastConfigSnapshot = buildConfigSnapshot(properties);

        for (DiagnoseRule rule : allRules) {
            statisticsMap.computeIfAbsent(rule.getName(), name -> {
                RuleProperties.RuleConfig config = properties.getRules().get(name);
                boolean enabled = config == null || config.isEnabled();
                return new RuleStatistics(name, getEffectivePriority(rule, properties), enabled);
            });
        }

        Set<String> activeNames = allRules.stream().map(DiagnoseRule::getName).collect(Collectors.toSet());
        statisticsMap.keySet().removeIf(name -> !activeNames.contains(name));
    }

    /**
     * 根据配置过滤并排序规则
     */
    private List<DiagnoseRule> buildActiveRules(List<DiagnoseRule> rules, RuleProperties props) {
        return bootstrapService.buildActiveRules(rules, props);
    }

    /**
     * 获取规则的有效优先级：配置优先级（非-1时）> 代码默认优先级
     */
    private int getEffectivePriority(DiagnoseRule rule, RuleProperties props) {
        return rulePriorityService.getEffectivePriority(rule, props);
    }

    /**
     * 构建配置快照字符串，用于检测变更
     */
    private String buildConfigSnapshot(RuleProperties props) {
        return snapshotService.buildSnapshot(props);
    }

    /**
     * 终极兜底（理论上不应该触发，因为 FallbackRule 优先级 0 会永远匹配）
     */
    private DiagnoseResponse buildUltimateFallback(DiagnosisContext context) {
        DiagnoseResponse resp = new DiagnoseResponse();
        resp.setSummary("系统暂时无法确定具体原因，建议使用AI大模型进行深度分析。");
        List<RootCauseItem> causes = new ArrayList<>();
        causes.add(new RootCauseItem("需要进一步分析", "当前任务状态组合未匹配到已知故障模式，建议使用AI大模型进行深度分析"));
        resp.setRootCauses(causes);
        resp.setActions(Arrays.asList(
                "点击「AI大模型深度分析」按钮获取更详细的诊断",
                "检查WCS系统运行状态",
                "查看相关设备是否正常"
        ));
        return resp;
    }

    public List<DiagnoseRule> getLoadedRules() {
        return Collections.unmodifiableList(sortedRules);
    }

    public List<DiagnoseRule> getAllRules() {
        return Collections.unmodifiableList(allRules);
    }

    public int getRuleCount() {
        return sortedRules.size();
    }

    public List<RuleStatistics> getStatistics() {
        statisticsService.syncMeta(new ArrayList<>(statisticsMap.values()), allRules, properties);
        return new ArrayList<>(statisticsMap.values());
    }

    public void addExpressionRule(String name, RuleProperties.RuleConfig config) {
        if (allRules.stream().anyMatch(r -> r.getName().equals(name))) {
            throw new IllegalArgumentException("规则名称已存在: " + name);
        }
        if (config.getCondition() == null || config.getCondition().isEmpty()) {
            throw new IllegalArgumentException("表达式规则必须配置condition: " + name);
        }
        ExpressionRule rule = new ExpressionRule(name, config);
        allRules.add(rule);
        properties.getRules().put(name, config);
        reloadRules();
        log.info("动态添加表达式规则: {}", name);
    }

    public void removeExpressionRule(String name) {
        if (isBuiltinRule(name)) {
            throw new IllegalArgumentException("不允许删除内置Bean规则: " + name);
        }
        allRules.removeIf(r -> r.getName().equals(name));
        properties.getRules().remove(name);
        statisticsMap.remove(name);
        reloadRules();
        log.info("动态删除表达式规则: {}", name);
    }

    public boolean isBuiltinRule(String name) {
        return beanRules.stream().anyMatch(r -> r.getName().equals(name));
    }

    public void resetStatistics() {
        statisticsMap.values().forEach(RuleStatistics::reset);
        log.info("规则统计数据已重置");
    }
}
