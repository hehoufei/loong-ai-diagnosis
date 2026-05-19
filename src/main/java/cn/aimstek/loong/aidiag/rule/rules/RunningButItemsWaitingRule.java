package cn.aimstek.loong.aidiag.rule.rules;

import cn.aimstek.loong.aidiag.client.dto.PlatformTaskItem;
import cn.aimstek.loong.aidiag.context.DiagnosisContext;
import cn.aimstek.loong.aidiag.dto.DiagnoseResponse;
import cn.aimstek.loong.aidiag.rule.AbstractDiagnoseRule;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 规则：task_state=RUNNING 但所有子任务都是 WAIT_*。
 */
@Component
public class RunningButItemsWaitingRule extends AbstractDiagnoseRule {

    @Override
    public String getName() {
        return "running-but-items-waiting";
    }

    @Override
    public int getPriority() {
        return 85;
    }

    @Override
    public boolean match(DiagnosisContext context) {
        if (context.getTask() == null) return false;
        if (!"RUNNING".equals(context.getTask().getTaskState())) return false;

        List<PlatformTaskItem> items = context.getItems();
        if (items == null || items.isEmpty()) return false;

        // 所有子任务都是 WAIT_* 前缀
        return items.stream().allMatch(item ->
                item.getTaskItemState() != null && item.getTaskItemState().startsWith("WAIT_"));
    }

    @Override
    public DiagnoseResponse diagnose(DiagnosisContext context) {
        return buildResponseFromConfig(context);
    }
}
