package cn.aimstek.loong.aidiag.rule.rules;

import cn.aimstek.loong.aidiag.context.DiagnosisContext;
import cn.aimstek.loong.aidiag.dto.DiagnoseResponse;
import cn.aimstek.loong.aidiag.rule.AbstractDiagnoseRule;
import org.springframework.stereotype.Component;

/**
 * 兜底规则：永远匹配，返回 null 表示规则层无结论，交由 LLM 或 fallback 处理。
 */
@Component
public class FallbackRule extends AbstractDiagnoseRule {

    @Override
    public String getName() {
        return "fallback";
    }

    @Override
    public int getPriority() {
        return -100;
    }

    @Override
    public boolean match(DiagnosisContext context) {
        return true;
    }

    @Override
    public DiagnoseResponse diagnose(DiagnosisContext context) {
        // 返回 null 表示规则层无结论，让 DiagnosisFacade 走 LLM 兜底
        return null;
    }
}
