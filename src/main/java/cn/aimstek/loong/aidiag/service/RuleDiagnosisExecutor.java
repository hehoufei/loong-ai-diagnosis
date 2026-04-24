package cn.aimstek.loong.aidiag.service;

import cn.aimstek.loong.aidiag.dto.DiagnoseResponse;
import cn.aimstek.loong.aidiag.rule.ConfigurableRuleEngine;
import cn.aimstek.loong.aidiag.rule.DiagnosisContext;
import cn.aimstek.loong.aidiag.rule.DiagnoseRule;
import cn.aimstek.loong.aidiag.rule.RuleStatistics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 规则诊断执行器。
 * 负责遍历已加载规则、执行匹配和诊断，并封装标准结果。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuleDiagnosisExecutor {

    private final ConfigurableRuleEngine ruleEngine;
    private final RuleStatisticsService ruleStatisticsService;

    public RuleDiagnosisResult evaluate(DiagnosisContext context) {
        List<DiagnoseRule> rules = ruleEngine.getLoadedRules();
        if (rules.isEmpty()) {
            return RuleDiagnosisResult.notMatched();
        }

        List<RuleStatistics> statistics = ruleEngine.getStatistics();

        for (DiagnoseRule rule : rules) {
            RuleStatistics stats = ruleStatisticsService.findByRuleName(statistics, rule.getName());

            try {
                long matchStart = System.currentTimeMillis();
                boolean matched = rule.match(context);
                long matchTime = System.currentTimeMillis() - matchStart;

                if (stats != null) {
                    stats.addMatchTime(matchTime);
                }

                if (!matched) {
                    continue;
                }

                log.info("规则命中: {}", rule.getName());
                long diagnoseStart = System.currentTimeMillis();
                DiagnoseResponse response = rule.diagnose(context);
                long diagnoseTime = System.currentTimeMillis() - diagnoseStart;

                if (stats != null) {
                    stats.recordHit();
                    stats.addDiagnoseTime(diagnoseTime);
                }

                if (response == null) {
                    log.warn("规则 {} 匹配成功但返回空响应", rule.getName());
                    continue;
                }

                RuleDiagnosisResult result = new RuleDiagnosisResult();
                result.setMatched(true);
                result.setRuleName(rule.getName());
                result.setPriority(rule.getPriority());
                result.setResponse(response);
                result.setMatchTimeMs(matchTime);
                result.setDiagnoseTimeMs(diagnoseTime);
                return result;
            } catch (Exception e) {
                if (stats != null) {
                    stats.recordMatchError();
                }
                log.warn("规则 {} 执行异常: {}", rule.getName(), e.getMessage(), e);
            }
        }

        return RuleDiagnosisResult.notMatched();
    }
}
