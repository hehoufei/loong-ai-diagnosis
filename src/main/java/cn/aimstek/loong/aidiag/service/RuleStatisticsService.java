package cn.aimstek.loong.aidiag.service;

import cn.aimstek.loong.aidiag.rule.DiagnoseRule;
import cn.aimstek.loong.aidiag.rule.RuleProperties;
import cn.aimstek.loong.aidiag.rule.RuleStatistics;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * 规则统计服务。
 * 负责对规则统计数据做统一读取和元数据同步。
 *
 * 说明：该服务保持无状态，避免与规则引擎形成 Bean 循环依赖。
 */
@Service
public class RuleStatisticsService {

    public List<RuleStatistics> listStatistics(List<RuleStatistics> statistics) {
        return statistics == null ? Collections.emptyList() : statistics;
    }

    public RuleStatistics findByRuleName(List<RuleStatistics> statistics, String ruleName) {
        if (statistics == null || ruleName == null) {
            return null;
        }
        return statistics.stream()
                .filter(stats -> ruleName.equals(stats.getRuleName()))
                .findFirst()
                .orElse(null);
    }

    public void syncMeta(List<RuleStatistics> statistics, List<DiagnoseRule> rules, RuleProperties properties) {
        if (statistics == null || rules == null || properties == null) {
            return;
        }
        for (DiagnoseRule rule : rules) {
            RuleStatistics stats = findByRuleName(statistics, rule.getName());
            if (stats == null) {
                continue;
            }
            stats.setPriority(getEffectivePriority(rule, properties));
            RuleProperties.RuleConfig config = properties.getRules().get(rule.getName());
            stats.setEnabled(config == null || config.isEnabled());
            stats.setDescription(rule.getDescription());
        }
    }

    private int getEffectivePriority(DiagnoseRule rule, RuleProperties props) {
        RuleProperties.RuleConfig config = props.getRules().get(rule.getName());
        if (config != null && config.getPriority() != -1) {
            return config.getPriority();
        }
        return rule.getPriority();
    }
}
