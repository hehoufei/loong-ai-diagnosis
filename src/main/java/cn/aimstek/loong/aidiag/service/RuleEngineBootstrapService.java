package cn.aimstek.loong.aidiag.service;

import cn.aimstek.loong.aidiag.rule.DiagnoseRule;
import cn.aimstek.loong.aidiag.rule.ExpressionRule;
import cn.aimstek.loong.aidiag.rule.RuleProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 规则引擎引导服务。
 * 负责初始化规则集合、排序和表达式规则注入。
 */
@Service
@RequiredArgsConstructor
public class RuleEngineBootstrapService {

    private final ExpressionRuleRegistryService expressionRuleRegistryService;
    private final RulePriorityService rulePriorityService;

    public List<DiagnoseRule> buildActiveRules(List<DiagnoseRule> beanRules, RuleProperties props) {
        List<DiagnoseRule> allRules = new ArrayList<>(beanRules);
        List<ExpressionRule> expressionRules = expressionRuleRegistryService.loadExpressionRules(beanRules, props);
        allRules.addAll(expressionRules);

        return allRules.stream()
                .filter(r -> {
                    RuleProperties.RuleConfig config = props.getRules().get(r.getName());
                    return config == null || config.isEnabled();
                })
                .sorted((a, b) -> Integer.compare(
                        rulePriorityService.getEffectivePriority(b, props),
                        rulePriorityService.getEffectivePriority(a, props)))
                .collect(Collectors.toList());
    }

    public Set<String> collectActiveNames(List<DiagnoseRule> rules) {
        return rules.stream().map(DiagnoseRule::getName).collect(Collectors.toSet());
    }
}
