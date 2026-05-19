package cn.aimstek.loong.aidiag.rule.rules;

import cn.aimstek.loong.aidiag.client.dto.PlatformTaskItem;
import cn.aimstek.loong.aidiag.context.DiagnosisContext;
import cn.aimstek.loong.aidiag.dto.DiagnoseResponse;
import cn.aimstek.loong.aidiag.rule.AbstractDiagnoseRule;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 规则：大任务未终态但存在 CANCEL 的子任务。
 */
@Component
public class HasCancelledItemsRule extends AbstractDiagnoseRule {

    @Override
    public String getName() {
        return "has-cancelled-items";
    }

    @Override
    public int getPriority() {
        return 60;
    }

    @Override
    public boolean match(DiagnosisContext context) {
        if (context.isTerminal()) return false;

        List<PlatformTaskItem> items = context.getItems();
        if (items == null || items.isEmpty()) return false;

        return items.stream().anyMatch(item -> "CANCEL".equals(item.getTaskItemState()));
    }

    @Override
    public DiagnoseResponse diagnose(DiagnosisContext context) {
        return buildResponseFromConfig(context);
    }
}
