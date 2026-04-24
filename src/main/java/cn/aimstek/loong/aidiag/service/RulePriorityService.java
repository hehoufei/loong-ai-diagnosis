package cn.aimstek.loong.aidiag.service;

import cn.aimstek.loong.aidiag.rule.DiagnoseRule;
import cn.aimstek.loong.aidiag.rule.RuleProperties;
import org.springframework.stereotype.Service;

/**
 * 规则优先级服务。
 * 统一管理规则最终生效优先级，避免规则引擎内部散落优先级判断逻辑。
 */
@Service
public class RulePriorityService {

    public int getEffectivePriority(DiagnoseRule rule, RuleProperties props) {
        RuleProperties.RuleConfig config = props.getRules().get(rule.getName());
        if (config != null && config.getPriority() != -1) {
            return config.getPriority();
        }
        return rule.getPriority();
    }
}
