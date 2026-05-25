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
 * 场景：存在 taskItemState=CANCEL 的子任务，需要查看取消原因。
 */
@Slf4j
@Component
public class TaskItemCancelledRule extends AbstractDiagnoseRule {

    public TaskItemCancelledRule() {
        this.description = "检测存在被取消的子任务（taskItemState=CANCEL），需查看取消原因";
    }

    @Override
    public String getName() {
        return "task-item-cancelled";
    }

    @Override
    public int getPriority() {
        return 925;
    }

    @Override
    public boolean match(DiagnosisContext ctx) {
        try {
            return ctx.getTaskItems().stream().anyMatch(i -> "CANCEL".equals(i.getTaskItemState()));
        } catch (Exception e) {
            log.warn("CANCEL 检测匹配异常: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public DiagnoseResponse diagnose(DiagnosisContext ctx) {
        DiagnoseResponse configResponse = buildResponseFromConfig(ctx);
        if (configResponse != null) {
            return configResponse;
        }
        List<TaskDetail.TaskItemDetail> cancelledItems = ctx.getTaskItems().stream()
                .filter(i -> "CANCEL".equals(i.getTaskItemState())).collect(Collectors.toList());

        StringBuilder desc = new StringBuilder();
        for (TaskDetail.TaskItemDetail ci : cancelledItems) {
            String key = ci.getTaskItemNo() != null ? ci.getTaskItemNo() : n(ci.getId());
            desc.append("子任务").append(key)
                    .append("(设备:").append(n(ci.getDeviceCode()))
                    .append(", 起点:").append(n(ci.getStartNode()))
                    .append("→终点:").append(n(ci.getEndNode())).append(") ");
        }

        DiagnoseResponse resp = new DiagnoseResponse();
        resp.setSummary("任务存在" + cancelledItems.size() + "个被取消的子任务（taskItemState=CANCEL），任务执行未能正常完成");
        resp.setRootCauses(List.of(new RootCauseItem("子任务被取消",
                "以下子任务处于 CANCEL 状态：" + desc + "。可能原因：人工取消、系统异常取消、设备故障导致取消")));
        resp.setActions(List.of("检查子任务取消原因", "确认相关设备是否正常", "如需要可重新下发任务"));
        return resp;
    }
}
