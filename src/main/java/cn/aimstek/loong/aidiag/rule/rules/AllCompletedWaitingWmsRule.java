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
 * 场景5.1：所有子任务和执行单已完成，最后一个子任务checkStatus=INIT，等待WMS下发更新。
 */
@Slf4j
@Component
public class AllCompletedWaitingWmsRule extends AbstractDiagnoseRule {

    public AllCompletedWaitingWmsRule() {
        this.description = "检测所有子任务已完成且正在等待WMS下发更新任务的正常等待状态";
    }

    @Override
    public String getName() {
        return "all-completed-waiting-wms";
    }

    @Override
    public int getPriority() {
        return 960;
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
        return "INIT".equals(lastItem.getCheckStatus());
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

        DiagnoseResponse resp = new DiagnoseResponse();
        resp.setSummary("所有子任务和执行单已完成，最后一个子任务(ID=" + lastItem.getId()
            + ")的check_status=INIT，正在等待WMS下发更新任务");
        resp.setRootCauses(List.of(new RootCauseItem("等待WMS更新任务",
            "最后一个子任务" + lastItem.getId() + "是检查点任务(check_status=INIT)，"
            + "当前所有子任务和执行单均已完成，任务正在等待WMS下发更新指令，"
            + "更新后WCS会生成新的子任务继续执行。这是正常的业务等待状态。")));
        resp.setActions(List.of(
            "确认WMS侧是否已收到任务完成通知",
            "检查WMS是否有待下发的更新任务",
            "如WMS长时间未下发更新，检查WMS与WCS之间的通信是否正常"));
        return resp;
    }
}
