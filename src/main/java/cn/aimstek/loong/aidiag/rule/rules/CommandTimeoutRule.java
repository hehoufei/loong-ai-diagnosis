package cn.aimstek.loong.aidiag.rule.rules;

import cn.aimstek.loong.aidiag.client.dto.PlatformCommand;
import cn.aimstek.loong.aidiag.context.DiagnosisContext;
import cn.aimstek.loong.aidiag.dto.DiagnoseResponse;
import cn.aimstek.loong.aidiag.rule.AbstractDiagnoseRule;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 规则：存在 Command 的 startTime 距今超阈值（默认300s）仍未完成。
 */
@Component
public class CommandTimeoutRule extends AbstractDiagnoseRule {

    @Override
    public String getName() {
        return "command-timeout";
    }

    @Override
    public int getPriority() {
        return 75;
    }

    @Override
    public boolean match(DiagnosisContext context) {
        int threshold = getIntParam("threshold-seconds", 300);
        LocalDateTime now = context.getNow() != null ? context.getNow() : LocalDateTime.now();

        return context.allCommands().stream().anyMatch(cmd ->
                isInFlight(cmd)
                        && cmd.getStartTime() != null
                        && Duration.between(cmd.getStartTime(), now).getSeconds() > threshold);
    }

    private boolean isInFlight(PlatformCommand cmd) {
        String state = cmd.getCommandState();
        return "SENT".equals(state) || "ACKED".equals(state);
    }

    @Override
    public DiagnoseResponse diagnose(DiagnosisContext context) {
        return buildResponseFromConfig(context);
    }
}
