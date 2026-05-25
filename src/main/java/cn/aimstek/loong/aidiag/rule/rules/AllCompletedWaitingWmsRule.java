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
 * 场景：所有子任务和命令均已成功完成（SUCCESS/MANUAL_SUCCESS），
 * 但主任务 task_state 仍为 RUNNING —— 等待上游 WMS 下发后续指令的正常等待状态。
 */
@Slf4j
@Component
public class AllCompletedWaitingWmsRule extends AbstractDiagnoseRule {

    public AllCompletedWaitingWmsRule() {
        this.description = "检测所有子任务/命令已完成且主任务仍 RUNNING，等待上游 WMS 下发后续任务";
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
        List<TaskDetail.CommandDetail> commands = ctx.getCommands();
        if (items.isEmpty()) return false;

        boolean allItemsSuccess = items.stream().allMatch(i -> isItemSuccess(i.getTaskItemState()));
        boolean allCommandsSuccess = commands.stream().allMatch(c -> isItemSuccess(c.getCommandState()));
        if (!allItemsSuccess || !allCommandsSuccess) return false;

        return "RUNNING".equals(ctx.getTaskState());
    }

    @Override
    public DiagnoseResponse diagnose(DiagnosisContext ctx) {
        DiagnoseResponse configResponse = buildResponseFromConfig(ctx);
        if (configResponse != null) {
            return configResponse;
        }
        List<TaskDetail.TaskItemDetail> items = ctx.getTaskItems();
        TaskDetail.TaskItemDetail lastItem = items.get(items.size() - 1);

        DiagnoseResponse resp = new DiagnoseResponse();
        resp.setSummary("所有子任务和命令均已完成（SUCCESS/MANUAL_SUCCESS），主任务 task_state=RUNNING，"
                + "正在等待上游 WMS 下发后续任务（最后一个子任务: "
                + n(lastItem.getTaskItemNo() != null ? lastItem.getTaskItemNo() : lastItem.getId()) + "）");
        resp.setRootCauses(List.of(new RootCauseItem("等待上游下发后续任务",
                "当前所有子任务和命令均已成功完成，主任务尚未结束，正在等待 WMS 下发后续指令以驱动新子任务。"
                        + "这通常是检查点类任务的正常业务等待状态。")));
        resp.setActions(List.of(
                "确认 WMS 侧是否已收到任务完成通知",
                "检查 WMS 是否有待下发的更新任务",
                "如长时间未下发，检查 WMS 与本系统之间的通信链路"));
        return resp;
    }
}
