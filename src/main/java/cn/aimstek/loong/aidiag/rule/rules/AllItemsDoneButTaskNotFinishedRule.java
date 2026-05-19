package cn.aimstek.loong.aidiag.rule.rules;

import cn.aimstek.loong.aidiag.client.dto.PlatformTaskItem;
import cn.aimstek.loong.aidiag.context.DiagnosisContext;
import cn.aimstek.loong.aidiag.dto.DiagnoseResponse;
import cn.aimstek.loong.aidiag.rule.AbstractDiagnoseRule;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 规则：所有子任务 SUCCESS 但大任务仍 RUNNING/WAIT_PLAN。
 */
@Component
public class AllItemsDoneButTaskNotFinishedRule extends AbstractDiagnoseRule {

    @Override
    public String getName() {
        return "all-items-done-but-task-not-finished";
    }

    @Override
    public int getPriority() {
        return 70;
    }

    @Override
    public boolean match(DiagnosisContext context) {
        if (context.getTask() == null) return false;
        String taskState = context.getTask().getTaskState();
        if (!"RUNNING".equals(taskState) && !"WAIT_PLAN".equals(taskState)) return false;

        List<PlatformTaskItem> items = context.getItems();
        if (items == null || items.isEmpty()) return false;

        return items.stream().allMatch(item -> {
            String state = item.getTaskItemState();
            return "SUCCESS".equals(state) || "MANUAL_SUCCESS".equals(state);
        });
    }

    @Override
    public DiagnoseResponse diagnose(DiagnosisContext context) {
        return buildResponseFromConfig(context);
    }
}
