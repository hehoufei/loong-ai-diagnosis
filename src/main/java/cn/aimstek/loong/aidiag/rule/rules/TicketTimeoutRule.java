package cn.aimstek.loong.aidiag.rule.rules;

import cn.aimstek.loong.aidiag.dto.DiagnoseResponse;
import cn.aimstek.loong.aidiag.dto.RootCauseItem;
import cn.aimstek.loong.aidiag.dto.TaskDetail;
import cn.aimstek.loong.aidiag.rule.AbstractDiagnoseRule;
import cn.aimstek.loong.aidiag.rule.DiagnosisContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 场景B：执行单长时间RUNNING（超过5分钟），超时检测。
 */
@Slf4j
@Component
public class TicketTimeoutRule extends AbstractDiagnoseRule {

    public TicketTimeoutRule() {
        this.description = "检测执行单运行超时，设备可能卡住或故障";
    }

    @Override
    public String getName() {
        return "ticket-timeout";
    }

    @Override
    public int getPriority() {
        return 910;
    }

    @Override
    public boolean match(DiagnosisContext ctx) {
        try {
            for (TaskDetail.TicketDetail t : ctx.getTickets()) {
                if ("RUNNING".equals(t.getTaskState()) && t.getStartTime() != null) {
                    Duration elapsed = Duration.between(t.getStartTime(), LocalDateTime.now());
                    if (elapsed.toMinutes() > getIntParam("timeout-minutes", 5)) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            log.warn("场景B(超时检测)匹配异常: {}", e.getMessage());
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
        List<TaskDetail.TicketDetail> timeoutTickets = new ArrayList<>();
        for (TaskDetail.TicketDetail t : ctx.getTickets()) {
            if ("RUNNING".equals(t.getTaskState()) && t.getStartTime() != null) {
                Duration elapsed = Duration.between(t.getStartTime(), LocalDateTime.now());
                if (elapsed.toMinutes() > getIntParam("timeout-minutes", 5)) {
                    timeoutTickets.add(t);
                }
            }
        }

        StringBuilder desc = new StringBuilder();
        for (TaskDetail.TicketDetail t : timeoutTickets) {
            Duration elapsed = Duration.between(t.getStartTime(), LocalDateTime.now());
            desc.append("执行单").append(n(t.getId()))
                .append("(设备:").append(n(t.getDeviceCode()))
                .append(", 已运行").append(elapsed.toMinutes()).append("分钟) ");
        }

        DiagnoseResponse resp = new DiagnoseResponse();
        resp.setSummary("检测到" + timeoutTickets.size() + "个执行单执行超时（RUNNING超过" + getIntParam("timeout-minutes", 5) + "分钟），设备可能卡住或故障");
        resp.setRootCauses(List.of(new RootCauseItem("执行单执行超时",
            "以下执行单RUNNING时间过长：" + desc + "。设备可能故障、卡住或PLC通信异常")));
        resp.setActions(List.of("检查相关设备运行状态", "确认PLC通信是否正常", "如设备故障需人工干预或手动完成执行单"));
        return resp;
    }
}
