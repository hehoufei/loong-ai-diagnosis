package cn.aimstek.loong.aidiag.rule.rules;

import cn.aimstek.loong.aidiag.client.dto.PlatformTaskItem;
import cn.aimstek.loong.aidiag.context.DiagnosisContext;
import cn.aimstek.loong.aidiag.dto.DiagnoseResponse;
import cn.aimstek.loong.aidiag.rule.AbstractDiagnoseRule;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 规则：大任务或所有 RUNNING 子任务被暂停。
 */
@Component
public class TaskPausedRule extends AbstractDiagnoseRule {

    @Override
    public String getName() {
        return "task-paused";
    }

    @Override
    public int getPriority() {
        return 50;
    }

    @Override
    public boolean match(DiagnosisContext context) {
        // 大任务被暂停
        if (context.getTask() != null && "YES".equals(context.getTask().getPaused())) {
            return true;
        }

        // 所有 RUNNING 子任务都被暂停
        List<PlatformTaskItem> runningItems = context.runningItems();
        if (runningItems.isEmpty()) return false;

        return runningItems.stream().allMatch(item -> "YES".equals(item.getPaused()));
    }

    @Override
    public DiagnoseResponse diagnose(DiagnosisContext context) {
        return buildResponseFromConfig(context);
    }
}
