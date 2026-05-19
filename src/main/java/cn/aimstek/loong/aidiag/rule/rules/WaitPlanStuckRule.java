package cn.aimstek.loong.aidiag.rule.rules;

import cn.aimstek.loong.aidiag.context.DiagnosisContext;
import cn.aimstek.loong.aidiag.dto.DiagnoseResponse;
import cn.aimstek.loong.aidiag.rule.AbstractDiagnoseRule;
import org.springframework.stereotype.Component;

/**
 * 规则：task_state=WAIT_PLAN 且所有子任务都不是 RUNNING。
 */
@Component
public class WaitPlanStuckRule extends AbstractDiagnoseRule {

    @Override
    public String getName() {
        return "wait-plan-stuck";
    }

    @Override
    public int getPriority() {
        return 90;
    }

    @Override
    public boolean match(DiagnosisContext context) {
        if (context.getTask() == null) return false;
        if (!"WAIT_PLAN".equals(context.getTask().getTaskState())) return false;

        int thresholdSeconds = getIntParam("threshold-seconds", 60);
        if (context.stuckSeconds() < thresholdSeconds) return false;

        return context.runningItems().isEmpty();
    }

    @Override
    public DiagnoseResponse diagnose(DiagnosisContext context) {
        return buildResponseFromConfig(context);
    }
}
