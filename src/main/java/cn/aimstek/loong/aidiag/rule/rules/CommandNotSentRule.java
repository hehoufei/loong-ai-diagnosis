package cn.aimstek.loong.aidiag.rule.rules;

import cn.aimstek.loong.aidiag.client.dto.PlatformTaskItem;
import cn.aimstek.loong.aidiag.context.DiagnosisContext;
import cn.aimstek.loong.aidiag.dto.DiagnoseResponse;
import cn.aimstek.loong.aidiag.rule.AbstractDiagnoseRule;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 规则：存在 RUNNING 子任务，但其下 Command 全部 WAIT。
 */
@Component
public class CommandNotSentRule extends AbstractDiagnoseRule {

    @Override
    public String getName() {
        return "command-not-sent";
    }

    @Override
    public int getPriority() {
        return 80;
    }

    @Override
    public boolean match(DiagnosisContext context) {
        List<PlatformTaskItem> runningItems = context.runningItems();
        if (runningItems.isEmpty()) return false;

        return runningItems.stream().anyMatch(item ->
                item.getCommands() != null && !item.getCommands().isEmpty()
                        && item.getCommands().stream().allMatch(cmd -> "WAIT".equals(cmd.getCommandState())));
    }

    @Override
    public DiagnoseResponse diagnose(DiagnosisContext context) {
        return buildResponseFromConfig(context);
    }
}
