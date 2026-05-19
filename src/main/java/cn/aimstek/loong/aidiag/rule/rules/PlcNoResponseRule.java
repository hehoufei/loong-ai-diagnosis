package cn.aimstek.loong.aidiag.rule.rules;

import cn.aimstek.loong.aidiag.context.DiagnosisContext;
import cn.aimstek.loong.aidiag.dto.DiagnoseResponse;
import cn.aimstek.loong.aidiag.rule.AbstractDiagnoseRule;
import org.springframework.stereotype.Component;

/**
 * 规则：存在 Command 处于 SENT 状态但无 plcTaskNo。
 */
@Component
public class PlcNoResponseRule extends AbstractDiagnoseRule {

    @Override
    public String getName() {
        return "plc-no-response";
    }

    @Override
    public int getPriority() {
        return 80;
    }

    @Override
    public boolean match(DiagnosisContext context) {
        return context.allCommands().stream().anyMatch(cmd ->
                "SENT".equals(cmd.getCommandState())
                        && (cmd.getPlcTaskNo() == null || cmd.getPlcTaskNo().isEmpty()));
    }

    @Override
    public DiagnoseResponse diagnose(DiagnosisContext context) {
        return buildResponseFromConfig(context);
    }
}
