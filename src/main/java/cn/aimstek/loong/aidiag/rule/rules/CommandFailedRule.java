package cn.aimstek.loong.aidiag.rule.rules;

import cn.aimstek.loong.aidiag.dto.DiagnoseResponse;
import cn.aimstek.loong.aidiag.dto.RootCauseItem;
import cn.aimstek.loong.aidiag.dto.TaskDetail;
import cn.aimstek.loong.aidiag.rule.AbstractDiagnoseRule;
import cn.aimstek.loong.aidiag.rule.DiagnosisContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 场景：存在 commandState=FAILED 的命令，命令执行失败需要排查。
 */
@Slf4j
@Component
public class CommandFailedRule extends AbstractDiagnoseRule {

    public CommandFailedRule() {
        this.description = "检测存在 commandState=FAILED 的命令";
    }

    @Override
    public String getName() {
        return "command-failed";
    }

    @Override
    public int getPriority() {
        return 920;
    }

    @Override
    public boolean match(DiagnosisContext ctx) {
        return ctx.getCommands().stream().anyMatch(c -> "FAILED".equals(c.getCommandState()));
    }

    @Override
    public DiagnoseResponse diagnose(DiagnosisContext ctx) {
        DiagnoseResponse configResponse = buildResponseFromConfig(ctx);
        if (configResponse != null) {
            return configResponse;
        }
        List<TaskDetail.CommandDetail> failed = ctx.getCommands().stream()
                .filter(c -> "FAILED".equals(c.getCommandState()))
                .collect(Collectors.toList());

        StringBuilder desc = new StringBuilder();
        for (TaskDetail.CommandDetail c : failed) {
            String key = c.getCommandNo() != null ? c.getCommandNo() : n(c.getId());
            desc.append("命令").append(key)
                    .append("(设备:").append(n(c.getDeviceCode()))
                    .append(", 类型:").append(n(c.getCommandType()))
                    .append(", 结果:").append(n(c.getCommandResult()))
                    .append(") ");
        }

        DiagnoseResponse resp = new DiagnoseResponse();
        resp.setSummary("检测到" + failed.size() + "条指令执行失败（commandState=FAILED），需要排查具体原因");
        resp.setRootCauses(List.of(new RootCauseItem("指令执行失败",
                "以下命令处于 FAILED 状态：" + desc
                        + "。可能原因：设备拒绝指令、参数异常、PLC 报错、指令超时被标记失败")));
        resp.setActions(List.of(
                "查看每个 FAILED 命令的 commandResult 详情定位失败原因",
                "检查相关设备运行状态和报警",
                "确认指令参数是否合法",
                "如可重试，可在排除故障后重新下发指令"));
        return resp;
    }
}
