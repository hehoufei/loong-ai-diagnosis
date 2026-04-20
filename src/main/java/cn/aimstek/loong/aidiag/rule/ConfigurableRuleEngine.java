package cn.aimstek.loong.aidiag.rule;

import cn.aimstek.loong.aidiag.dto.DiagnoseResponse;
import cn.aimstek.loong.aidiag.dto.RootCauseItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.Set;

@Slf4j
@Service
public class ConfigurableRuleEngine implements RuleEngine {

    private final List<DiagnoseRule> beanRules;
    private final List<DiagnoseRule> allRules;
    private final RuleProperties properties;
    private volatile List<DiagnoseRule> sortedRules;
    private final Map<String, RuleStatistics> statisticsMap = new ConcurrentHashMap<>();
    /** 动态表达式规则（非Spring Bean） */
    private final List<ExpressionRule> expressionRules = new ArrayList<>();

    /** 上次生效的配置快照，用于检测变更 */
    private volatile String lastConfigSnapshot;

    /**
     * Spring 自动注入所有 DiagnoseRule Bean，根据 RuleProperties 过滤和排序
     */
    public ConfigurableRuleEngine(List<DiagnoseRule> rules, RuleProperties properties) {
        this.beanRules = new ArrayList<>(rules);
        this.allRules = new ArrayList<>(rules);
        this.properties = properties;
        // 扫描配置中的表达式规则
        loadExpressionRules();
        this.sortedRules = buildActiveRules(allRules, properties);
        this.lastConfigSnapshot = buildConfigSnapshot(properties);
        // 初始化统计数据
        for (DiagnoseRule rule : allRules) {
            RuleProperties.RuleConfig config = properties.getRules().get(rule.getName());
            boolean enabled = config == null || config.isEnabled();
            statisticsMap.put(rule.getName(), new RuleStatistics(rule.getName(), getEffectivePriority(rule, properties), enabled));
        }
        log.info("规则引擎初始化完成，共加载 {} 条规则（Bean {} 条 + 表达式 {} 条，总注册 {} 条）: {}",
                sortedRules.size(), beanRules.size(), expressionRules.size(), allRules.size(),
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
            lastConfigSnapshot = currentSnapshot;
        }
    }

    /**
     * 重新加载规则：根据最新配置重新过滤和排序
     */
    public void reloadRules() {
        // 重新扫描表达式规则
        loadExpressionRules();
        List<DiagnoseRule> newSortedRules = buildActiveRules(allRules, properties);
        this.sortedRules = newSortedRules;
        this.lastConfigSnapshot = buildConfigSnapshot(properties);
        // 同步统计条目：为新规则创建统计
        for (DiagnoseRule rule : allRules) {
            statisticsMap.computeIfAbsent(rule.getName(), name -> {
                RuleProperties.RuleConfig config = properties.getRules().get(name);
                boolean enabled = config == null || config.isEnabled();
                return new RuleStatistics(name, getEffectivePriority(rule, properties), enabled);
            });
        }
        // 清理已不存在的表达式规则统计
        Set<String> activeNames = allRules.stream().map(DiagnoseRule::getName).collect(Collectors.toSet());
        statisticsMap.keySet().removeIf(name -> !activeNames.contains(name));
        log.info("规则引擎重新加载完成，当前活跃规则 {} 条: {}",
                newSortedRules.size(),
                newSortedRules.stream()
                        .map(r -> r.getName() + "(P" + getEffectivePriority(r, properties) + ")")
                        .collect(Collectors.joining(", ")));
    }

    /**
     * 根据配置过滤并排序规则
     */
    private List<DiagnoseRule> buildActiveRules(List<DiagnoseRule> rules, RuleProperties props) {
        return rules.stream()
                .filter(r -> {
                    RuleProperties.RuleConfig config = props.getRules().get(r.getName());
                    return config == null || config.isEnabled();  // 默认启用
                })
                .sorted((a, b) -> {
                    int pa = getEffectivePriority(a, props);
                    int pb = getEffectivePriority(b, props);
                    return Integer.compare(pb, pa);  // 降序：优先级高的先执行
                })
                .collect(Collectors.toList());
    }

    /**
     * 获取规则的有效优先级：配置优先级（非-1时）> 代码默认优先级
     */
    private int getEffectivePriority(DiagnoseRule rule, RuleProperties props) {
        RuleProperties.RuleConfig config = props.getRules().get(rule.getName());
        if (config != null && config.getPriority() != -1) {
            return config.getPriority();
        }
        return rule.getPriority();
    }

    /**
     * 构建配置快照字符串，用于检测变更
     */
    private String buildConfigSnapshot(RuleProperties props) {
        StringBuilder sb = new StringBuilder();
        props.getRules().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> {
                    RuleProperties.RuleConfig c = e.getValue();
                    sb.append(e.getKey())
                      .append("=enabled:").append(c.isEnabled())
                      .append(",priority:").append(c.getPriority())
                      .append(",params:").append(c.getParams())
                      .append(",desc:").append(c.getDescription() == null ? "" : c.getDescription())
                      .append(",condition:").append(c.getCondition() == null ? "" : c.getCondition());
                    RuleProperties.OutputConfig out = c.getOutput();
                    if (out != null) {
                        sb.append(",out.summary:").append(out.getSummary() == null ? "" : out.getSummary())
                          .append(",out.rootCauses:").append(out.getRootCauses())
                          .append(",out.actions:").append(out.getActions());
                    }
                    sb.append(";");
                });
        sb.append("docSearch=topK:").append(props.getDocSearch().getTopK())
                .append(",threshold:").append(props.getDocSearch().getSimilarityThreshold())
                .append(",preSearch:").append(props.getDocSearch().isPreSearchEnabled());
        return sb.toString();
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

    /**
     * 获取当前已加载的规则列表（用于管理和调试）
     */
    public List<DiagnoseRule> getLoadedRules() {
        return Collections.unmodifiableList(sortedRules);
    }

    /**
     * 获取所有已注册的规则（包括被禁用的）
     */
    public List<DiagnoseRule> getAllRules() {
        return Collections.unmodifiableList(allRules);
    }

    /**
     * 获取规则数量
     */
    public int getRuleCount() {
        return sortedRules.size();
    }

    /** 获取所有规则的统计数据 */
    public List<RuleStatistics> getStatistics() {
        // 更新每条统计的 priority、enabled 和 description 状态
        for (DiagnoseRule rule : allRules) {
            RuleStatistics stats = statisticsMap.get(rule.getName());
            if (stats != null) {
                stats.setPriority(getEffectivePriority(rule, properties));
                RuleProperties.RuleConfig config = properties.getRules().get(rule.getName());
                stats.setEnabled(config == null || config.isEnabled());
                stats.setDescription(rule.getDescription());
            }
        }
        return new ArrayList<>(statisticsMap.values());
    }

    /**
     * 运行时添加表达式规则
     */
    public void addExpressionRule(String name, RuleProperties.RuleConfig config) {
        // 检查是否已存在同名规则
        if (allRules.stream().anyMatch(r -> r.getName().equals(name))) {
            throw new IllegalArgumentException("规则名称已存在: " + name);
        }
        if (config.getCondition() == null || config.getCondition().isEmpty()) {
            throw new IllegalArgumentException("表达式规则必须配置condition: " + name);
        }
        ExpressionRule rule = new ExpressionRule(name, config);
        expressionRules.add(rule);
        allRules.add(rule);
        // 同步到properties
        properties.getRules().put(name, config);
        reloadRules();
        log.info("动态添加表达式规则: {}", name);
    }

    /**
     * 运行时删除表达式规则（不允许删除Bean规则）
     */
    public void removeExpressionRule(String name) {
        if (isBuiltinRule(name)) {
            throw new IllegalArgumentException("不允许删除内置Bean规则: " + name);
        }
        expressionRules.removeIf(r -> r.getName().equals(name));
        allRules.removeIf(r -> r.getName().equals(name));
        properties.getRules().remove(name);
        statisticsMap.remove(name);
        reloadRules();
        log.info("动态删除表达式规则: {}", name);
    }

    /**
     * 判断某规则是否为内置Bean规则
     */
    public boolean isBuiltinRule(String name) {
        return beanRules.stream().anyMatch(r -> r.getName().equals(name));
    }

    /**
     * 从配置中扫描并加载表达式规则
     */
    private void loadExpressionRules() {
        // 收集Bean规则名称
        Set<String> beanRuleNames = beanRules.stream()
                .map(DiagnoseRule::getName)
                .collect(Collectors.toSet());

        // 清理旧的表达式规则
        expressionRules.clear();
        allRules.removeIf(r -> r instanceof ExpressionRule);

        // 扫描properties中有condition但没有对应Bean的配置项
        properties.getRules().forEach((name, config) -> {
            if (config.getCondition() != null && !config.getCondition().isEmpty()
                    && !beanRuleNames.contains(name)) {
                ExpressionRule rule = new ExpressionRule(name, config);
                expressionRules.add(rule);
                allRules.add(rule);
                log.debug("加载表达式规则: {} (condition={})", name, config.getCondition());
            }
        });
    }

    /** 重置所有统计数据 */
    public void resetStatistics() {
        statisticsMap.values().forEach(RuleStatistics::reset);
        log.info("规则统计数据已重置");
    }
}
