package cn.aimstek.loong.aidiag.rule.rules;

import cn.aimstek.loong.aidiag.dto.DiagnoseResponse;
import cn.aimstek.loong.aidiag.dto.RootCauseItem;
import cn.aimstek.loong.aidiag.dto.TaskDetail;
import cn.aimstek.loong.aidiag.rule.AbstractDiagnoseRule;
import cn.aimstek.loong.aidiag.rule.DiagnosisContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 场景5.2：所有子任务和执行单已完成，但checkStatus非INIT，大任务状态更新失败。
 */
@Slf4j
@Component
public class AllCompletedStateAbnormalRule extends AbstractDiagnoseRule {

    public AllCompletedStateAbnormalRule() {
        this.description = "检测所有子任务已完成但大任务状态更新失败的异常情况";
    }

    @Override
    public String getName() {
        return "all-completed-state-abnormal";
    }

    @Override
    public int getPriority() {
        return 950;
    }

    @Override
    public boolean match(DiagnosisContext ctx) {
        List<TaskDetail.TaskItemDetail> items = ctx.getTaskItems();
        List<TaskDetail.TicketDetail> tickets = ctx.getTickets();
        if (items.isEmpty()) return false;

        boolean allItemsSuccess = items.stream().allMatch(i -> isItemSuccess(i.getTaskState()));
        boolean allTicketsSuccess = tickets.stream().allMatch(t -> isItemSuccess(t.getTaskState()));
        if (!allItemsSuccess || !allTicketsSuccess) return false;

        TaskDetail.TaskItemDetail lastItem = items.get(items.size() - 1);
        return !"INIT".equals(lastItem.getCheckStatus());
    }

    @Override
    public DiagnoseResponse diagnose(DiagnosisContext ctx) {
        // 检查是否有配置覆盖
        DiagnoseResponse configResponse = buildResponseFromConfig(ctx);
        if (configResponse != null) {
            return configResponse;
        }
        // 以下保持原有逻辑不变
        List<TaskDetail.TaskItemDetail> items = ctx.getTaskItems();
        TaskDetail.TaskItemDetail lastItem = items.get(items.size() - 1);
        String checkStatus = lastItem.getCheckStatus();

        DiagnoseResponse resp = new DiagnoseResponse();
        resp.setSummary("所有子任务和执行单已完成，但大任务状态仍为RUNNING，大任务状态更新失败");
        resp.setRootCauses(List.of(new RootCauseItem("大任务状态回写异常",
            "所有子任务均为AUTO_SUCCESS，所有执行单均已完成，"
            + "最后一个子任务check_status=" + n(checkStatus) + "(非INIT)，"
            + "大任务应该已经完成但状态未更新为成功")));
        resp.setActions(List.of(
            "检查大任务完成回调的日志记录",
            "确认数据库中大任务状态是否可手动更新",
            "排查状态回写逻辑是否有异常"));
        return resp;
    }
}
