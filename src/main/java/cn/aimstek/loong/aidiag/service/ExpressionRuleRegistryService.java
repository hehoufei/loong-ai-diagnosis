package cn.aimstek.loong.aidiag.service;

import cn.aimstek.loong.aidiag.rule.DiagnoseRule;
import cn.aimstek.loong.aidiag.rule.ExpressionRule;
import cn.aimstek.loong.aidiag.rule.RuleProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 表达式规则注册服务。
 * 负责从配置中识别并构建非 Spring Bean 的动态规则。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExpressionRuleRegistryService {

    public List<ExpressionRule> loadExpressionRules(List<DiagnoseRule> beanRules, RuleProperties properties) {
        Set<String> beanRuleNames = beanRules.stream()
                .map(DiagnoseRule::getName)
                .collect(Collectors.toSet());

        List<ExpressionRule> expressionRules = new ArrayList<>();
        properties.getRules().forEach((name, config) -> {
            if (config.getCondition() != null && !config.getCondition().isEmpty()
                    && !beanRuleNames.contains(name)) {
                ExpressionRule rule = new ExpressionRule(name, config);
                expressionRules.add(rule);
                log.debug("加载表达式规则: {} (condition={})", name, config.getCondition());
            }
        });
        return expressionRules;
    }
}
