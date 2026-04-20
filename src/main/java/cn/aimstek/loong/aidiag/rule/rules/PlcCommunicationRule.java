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
 * 场景C：子任务已下发到调度(ISSUED_DCS)，但对应执行单仍为INIT状态，PLC未接收任务。
 */
@Slf4j
@Component
public class PlcCommunicationRule extends AbstractDiagnoseRule {

    public PlcCommunicationRule() {
        this.description = "检测子任务已下发但PLC未接收，设备通信异常";
    }

    @Override
    public String getName() {
        return "plc-communication";
    }

    @Override
    public int getPriority() {
        return 900;
    }

    @Override
    public boolean match(DiagnosisContext ctx) {
        try {
            Set<String> issuedItemIds = new HashSet<>();
            for (TaskDetail.TaskItemDetail item : ctx.getTaskItems()) {
                if ("ISSUED_DCS".equals(item.getTaskState())) {
                    issuedItemIds.add(item.getId());
                }
            }
            if (issuedItemIds.isEmpty()) return false;

            return ctx.getTickets().stream()
                .anyMatch(t -> "INIT".equals(t.getTaskState()) && issuedItemIds.contains(t.getTaskItemId()));
        } catch (Exception e) {
            log.warn("场景C(PLC通信检测)匹配异常: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public DiagnoseResponse diagnose(DiagnosisContext ctx) {
        // 检查是否有配置覆盖
        DiagnoseResponse configResponse = buildResponseFromConfig(ctx);
        if (configResponse != null) {
            return configResponse;
        }
        // 以下保持原有逻辑不变
        Set<String> issuedItemIds = new HashSet<>();
        for (TaskDetail.TaskItemDetail item : ctx.getTaskItems()) {
            if ("ISSUED_DCS".equals(item.getTaskState())) {
                issuedItemIds.add(item.getId());
            }
        }

        List<TaskDetail.TicketDetail> noPclTickets = ctx.getTickets().stream()
            .filter(t -> "INIT".equals(t.getTaskState()) && issuedItemIds.contains(t.getTaskItemId()))
            .collect(Collectors.toList());

        StringBuilder desc = new StringBuilder();
        for (TaskDetail.TicketDetail t : noPclTickets) {
            desc.append("执行单").append(n(t.getId()))
                .append("(设备:").append(n(t.getDeviceCode())).append(") ");
        }

        DiagnoseResponse resp = new DiagnoseResponse();
        resp.setSummary("子任务已下发到调度(ISSUED_DCS)，但对应执行单仍为INIT状态，PLC未接收任务");
        resp.setRootCauses(List.of(new RootCauseItem("设备通信异常",
            "以下执行单已下发但PLC未接收：" + desc + "。可能是PLC通信异常或设备不在线")));
        resp.setActions(List.of("检查PLC通信连接状态", "确认相关设备是否在线", "设备点位是否被占用", "点位状态是否正常"));
        return resp;
    }
}
