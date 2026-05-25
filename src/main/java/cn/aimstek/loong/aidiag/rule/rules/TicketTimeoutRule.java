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
 * 场景：命令长时间处于 RUNNING 状态（超过阈值，默认 5 分钟），疑似设备卡住或故障。
 * 旧 TicketTimeoutRule 的等价场景。
 */
@Slf4j
@Component
public class TicketTimeoutRule extends AbstractDiagnoseRule {

    public TicketTimeoutRule() {
        this.description = "检测命令长时间运行超时（commandState=RUNNING 且超过阈值），设备可能卡住或故障";
    }

    @Override
    public String getName() {
        return "command-timeout";
    }

    @Override
    public int getPriority() {
        return 910;
    }

    @Override
    public boolean match(DiagnosisContext ctx) {
        try {
            int threshold = getIntParam("timeout-minutes", 5);
            for (TaskDetail.CommandDetail c : ctx.getCommands()) {
                if ("RUNNING".equals(c.getCommandState()) && c.getStartTime() != null) {
                    Duration elapsed = Duration.between(c.getStartTime(), LocalDateTime.now());
                    if (elapsed.toMinutes() > threshold) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            log.warn("命令超时匹配异常: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public DiagnoseResponse diagnose(DiagnosisContext ctx) {
        DiagnoseResponse configResponse = buildResponseFromConfig(ctx);
        if (configResponse != null) {
            return configResponse;
        }
        int threshold = getIntParam("timeout-minutes", 5);
        List<TaskDetail.CommandDetail> timeoutCommands = new ArrayList<>();
        for (TaskDetail.CommandDetail c : ctx.getCommands()) {
            if ("RUNNING".equals(c.getCommandState()) && c.getStartTime() != null) {
                Duration elapsed = Duration.between(c.getStartTime(), LocalDateTime.now());
                if (elapsed.toMinutes() > threshold) {
                    timeoutCommands.add(c);
                }
            }
        }

        StringBuilder desc = new StringBuilder();
        for (TaskDetail.CommandDetail c : timeoutCommands) {
            Duration elapsed = Duration.between(c.getStartTime(), LocalDateTime.now());
            String key = c.getCommandNo() != null ? c.getCommandNo() : n(c.getId());
            desc.append("命令").append(key)
                    .append("(设备:").append(n(c.getDeviceCode()))
                    .append(", 已运行").append(elapsed.toMinutes()).append("分钟) ");
        }

        DiagnoseResponse resp = new DiagnoseResponse();
        resp.setSummary("检测到" + timeoutCommands.size() + "个命令执行超时（commandState=RUNNING 超过"
                + threshold + "分钟），设备可能卡住或故障");
        resp.setRootCauses(List.of(new RootCauseItem("命令执行超时",
                "以下命令长时间停留在 RUNNING 状态：" + desc + "。设备可能故障、卡住或 PLC 通信异常")));
        resp.setActions(List.of("检查相关设备运行状态", "确认 PLC 通信是否正常",
                "如设备故障需人工干预或手动完成命令"));
        return resp;
    }
}
