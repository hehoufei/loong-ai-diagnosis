package cn.aimstek.loong.aidiag.rule.rules;

import cn.aimstek.loong.aidiag.dto.DiagnoseResponse;
import cn.aimstek.loong.aidiag.dto.RootCauseItem;
import cn.aimstek.loong.aidiag.dto.TaskDetail;
import cn.aimstek.loong.aidiag.rule.AbstractDiagnoseRule;
import cn.aimstek.loong.aidiag.rule.DiagnosisContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 场景：子任务已 RUNNING，但其归属的命令仍处于 CREATED / ISSUING（尚未真正下发到 PLC），
 * 表明 DCS/PLC 通信链路异常。
 */
@Slf4j
@Component
public class PlcCommunicationRule extends AbstractDiagnoseRule {

    public PlcCommunicationRule() {
        this.description = "检测子任务已 RUNNING 但命令仍 CREATED/ISSUING，PLC/调度通信异常";
    }

    @Override
    public String getName() {
        return "plc-communication";
    }

    @Override
    public int getPriority() {
        return 905;
    }

    @Override
    public boolean match(DiagnosisContext ctx) {
        try {
            Set<String> runningItemNos = collectRunningItemNos(ctx);
            if (runningItemNos.isEmpty()) return false;

            return ctx.getCommands().stream()
                    .anyMatch(c -> isPendingDispatch(c.getCommandState())
                            && c.getTaskItemNo() != null
                            && runningItemNos.contains(c.getTaskItemNo()));
        } catch (Exception e) {
            log.warn("PLC 通信检测匹配异常: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public DiagnoseResponse diagnose(DiagnosisContext ctx) {
        DiagnoseResponse configResponse = buildResponseFromConfig(ctx);
        if (configResponse != null) {
            return configResponse;
        }
        Set<String> runningItemNos = collectRunningItemNos(ctx);

        List<TaskDetail.CommandDetail> stuckCommands = ctx.getCommands().stream()
                .filter(c -> isPendingDispatch(c.getCommandState())
                        && c.getTaskItemNo() != null
                        && runningItemNos.contains(c.getTaskItemNo()))
                .collect(Collectors.toList());

        StringBuilder desc = new StringBuilder();
        for (TaskDetail.CommandDetail c : stuckCommands) {
            String key = c.getCommandNo() != null ? c.getCommandNo() : n(c.getId());
            desc.append("命令").append(key)
                    .append("(状态:").append(n(c.getCommandState()))
                    .append(", 设备:").append(n(c.getDeviceCode())).append(") ");
        }

        DiagnoseResponse resp = new DiagnoseResponse();
        resp.setSummary("子任务已进入 RUNNING，但对应命令仍处于 CREATED/ISSUING（尚未下发至 PLC），通信可能异常");
        resp.setRootCauses(List.of(new RootCauseItem("调度/PLC 通信异常",
                "以下命令未能成功下发至 PLC：" + desc + "。可能是 DCS 通信中断、设备离线或调度服务异常")));
        resp.setActions(List.of("检查 PLC 通信连接状态",
                "确认相关设备是否在线",
                "确认 DCS/调度服务是否正常",
                "排查命令下发链路是否存在堵塞"));
        return resp;
    }

    private Set<String> collectRunningItemNos(DiagnosisContext ctx) {
        Set<String> nos = new HashSet<>();
        for (TaskDetail.TaskItemDetail item : ctx.getTaskItems()) {
            if ("RUNNING".equals(item.getTaskItemState()) && item.getTaskItemNo() != null) {
                nos.add(item.getTaskItemNo());
            }
        }
        return nos;
    }

    private boolean isPendingDispatch(String commandState) {
        return "CREATED".equals(commandState) || "ISSUING".equals(commandState);
    }
}
