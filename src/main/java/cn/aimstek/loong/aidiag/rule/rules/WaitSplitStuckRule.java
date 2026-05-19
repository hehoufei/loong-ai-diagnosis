package cn.aimstek.loong.aidiag.rule.rules;

import cn.aimstek.loong.aidiag.context.DiagnosisContext;
import cn.aimstek.loong.aidiag.dto.DiagnoseResponse;
import cn.aimstek.loong.aidiag.rule.AbstractDiagnoseRule;
import org.springframework.stereotype.Component;

/**
 * 规则：大任务长时间停在 WAIT_SPLIT 状态。
 * 排除任务组未拆完的场景（由 TaskGroupNotSplitRule 处理）。
 */
@Component
public class WaitSplitStuckRule extends AbstractDiagnoseRule {

    @Override
    public String getName() {
        return "wait-split-stuck";
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public boolean match(DiagnosisContext context) {
        if (context.getTask() == null) return false;
        if (!"WAIT_SPLIT".equals(context.getTask().getTaskState())) return false;

        int thresholdSeconds = getIntParam("threshold-seconds", 60);
        if (context.stuckSeconds() < thresholdSeconds) return false;

        // 排除任务组未拆完场景：如果是任务组且拆分未完成，交给 TaskGroupNotSplitRule
        if (context.isGroupTask() && context.getGroup() != null
                && "NOT_FINISH".equals(context.getGroup().getSplitFinish())) {
            return false;
        }

        return true;
    }

    @Override
    public DiagnoseResponse diagnose(DiagnosisContext context) {
        return buildResponseFromConfig(context);
    }
}
