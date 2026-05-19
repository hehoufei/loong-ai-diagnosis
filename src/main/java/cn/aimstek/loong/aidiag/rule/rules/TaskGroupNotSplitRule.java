package cn.aimstek.loong.aidiag.rule.rules;

import cn.aimstek.loong.aidiag.context.DiagnosisContext;
import cn.aimstek.loong.aidiag.dto.DiagnoseResponse;
import cn.aimstek.loong.aidiag.rule.AbstractDiagnoseRule;
import org.springframework.stereotype.Component;

/**
 * 规则：任务组长时间未完成拆分。
 */
@Component
public class TaskGroupNotSplitRule extends AbstractDiagnoseRule {

    @Override
    public String getName() {
        return "task-group-not-split";
    }

    @Override
    public int getPriority() {
        return 40;
    }

    @Override
    public boolean match(DiagnosisContext context) {
        if (!context.isGroupTask()) return false;
        if (context.getGroup() == null) return false;

        int thresholdSeconds = getIntParam("threshold-seconds", 60);
        if (context.stuckSeconds() < thresholdSeconds) return false;

        return "NOT_FINISH".equals(context.getGroup().getSplitFinish());
    }

    @Override
    public DiagnoseResponse diagnose(DiagnosisContext context) {
        return buildResponseFromConfig(context);
    }
}
