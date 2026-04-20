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
 * 场景A：子任务存在CANCEL状态。
 */
@Slf4j
@Component
public class TaskItemCancelledRule extends AbstractDiagnoseRule {

    public TaskItemCancelledRule() {
        this.description = "检测存在被取消的子任务，需查看取消原因";
    }

    @Override
    public String getName() {
        return "task-item-cancelled";
    }

    @Override
    public int getPriority() {
        return 920;
    }

    @Override
    public boolean match(DiagnosisContext ctx) {
        try {
            return ctx.getTaskItems().stream().anyMatch(i -> "CANCEL".equals(i.getTaskState()));
        } catch (Exception e) {
            log.warn("场景A(CANCEL检测)匹配异常: {}", e.getMessage());
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
        List<TaskDetail.TaskItemDetail> cancelledItems = ctx.getTaskItems().stream()
            .filter(i -> "CANCEL".equals(i.getTaskState())).collect(Collectors.toList());

        StringBuilder desc = new StringBuilder();
        for (TaskDetail.TaskItemDetail ci : cancelledItems) {
            desc.append("子任务").append(n(ci.getId()))
                .append("(设备:").append(n(ci.getDeviceCode()))
                .append(", 起点:").append(n(ci.getStartPoint()))
                .append("→终点:").append(n(ci.getEndPoint())).append(") ");
        }

        DiagnoseResponse resp = new DiagnoseResponse();
        resp.setSummary("任务存在" + cancelledItems.size() + "个被取消的子任务，任务执行未能正常完成");
        resp.setRootCauses(List.of(new RootCauseItem("子任务被取消",
            "以下子任务处于CANCEL状态：" + desc + "。可能原因：人工取消、系统异常取消、设备故障导致取消")));
        resp.setActions(List.of("检查子任务取消原因", "确认相关设备是否正常", "如需要可重新下发任务"));
        return resp;
    }
}
