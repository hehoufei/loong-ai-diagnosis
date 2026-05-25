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
 * 场景：存在 RUNNING 状态的子任务，且 executedIndex &lt; issuedIndex（已下发未执行完毕），
 * 且子任务 startTime 距今超过阈值（默认 5 分钟），表明执行进度停滞。
 */
@Slf4j
@Component
public class ProgressStalledRule extends AbstractDiagnoseRule {

    public ProgressStalledRule() {
        this.description = "检测子任务 RUNNING 但执行进度停滞（executedIndex < issuedIndex 且超时）";
    }

    @Override
    public String getName() {
        return "progress-stalled";
    }

    @Override
    public int getPriority() {
        return 900;
    }

    @Override
    public boolean match(DiagnosisContext ctx) {
        try {
            int threshold = getIntParam("stall-minutes", 5);
            for (TaskDetail.TaskItemDetail item : ctx.getTaskItems()) {
                if (isStalled(item, threshold)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.warn("进度停滞匹配异常: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public DiagnoseResponse diagnose(DiagnosisContext ctx) {
        DiagnoseResponse configResponse = buildResponseFromConfig(ctx);
        if (configResponse != null) {
            return configResponse;
        }
        int threshold = getIntParam("stall-minutes", 5);
        List<TaskDetail.TaskItemDetail> stalled = new ArrayList<>();
        for (TaskDetail.TaskItemDetail item : ctx.getTaskItems()) {
            if (isStalled(item, threshold)) stalled.add(item);
        }

        StringBuilder desc = new StringBuilder();
        for (TaskDetail.TaskItemDetail item : stalled) {
            String key = item.getTaskItemNo() != null ? item.getTaskItemNo() : n(item.getId());
            long mins = Duration.between(item.getStartTime(), LocalDateTime.now()).toMinutes();
            desc.append("子任务").append(key)
                    .append("(设备:").append(n(item.getDeviceCode()))
                    .append(", planIndex=").append(item.getPlanIndex())
                    .append(", issuedIndex=").append(item.getIssuedIndex())
                    .append(", executedIndex=").append(item.getExecutedIndex())
                    .append(", 已运行").append(mins).append("分钟) ");
        }

        DiagnoseResponse resp = new DiagnoseResponse();
        resp.setSummary("检测到" + stalled.size() + "个子任务执行进度停滞（executedIndex < issuedIndex 超过 "
                + threshold + " 分钟）");
        resp.setRootCauses(List.of(new RootCauseItem("执行进度停滞",
                "以下子任务 issuedIndex 已推进但 executedIndex 未跟上：" + desc
                        + "。可能原因：设备执行慢/卡住、PLC 反馈延迟、命令执行异常无回写")));
        resp.setActions(List.of(
                "检查相关设备和命令的实际执行情况",
                "确认 PLC 是否有反馈延迟",
                "如设备故障考虑人工干预或手动推进进度"));
        return resp;
    }

    private boolean isStalled(TaskDetail.TaskItemDetail item, int thresholdMinutes) {
        if (!"RUNNING".equals(item.getTaskItemState())) return false;
        Integer issued = item.getIssuedIndex();
        Integer executed = item.getExecutedIndex();
        if (issued == null || executed == null) return false;
        if (executed >= issued) return false;
        if (item.getStartTime() == null) return false;
        return Duration.between(item.getStartTime(), LocalDateTime.now()).toMinutes() > thresholdMinutes;
    }
}
