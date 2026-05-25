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
 * 场景：所有子任务和命令都已完成，但主任务 task_state 不在 SUCCESS / MANUAL_SUCCESS，
 * 表明大任务的状态回写发生异常。
 */
@Slf4j
@Component
public class AllCompletedStateAbnormalRule extends AbstractDiagnoseRule {

    public AllCompletedStateAbnormalRule() {
        this.description = "检测所有子任务/命令已完成但主任务未更新为 SUCCESS/MANUAL_SUCCESS 的异常情况";
    }

    @Override
    public String getName() {
        return "all-completed-state-abnormal";
    }

    @Override
    public int getPriority() {
        return 955;
    }

    @Override
    public boolean match(DiagnosisContext ctx) {
        List<TaskDetail.TaskItemDetail> items = ctx.getTaskItems();
        List<TaskDetail.CommandDetail> commands = ctx.getCommands();
        if (items.isEmpty()) return false;

        boolean allItemsSuccess = items.stream().allMatch(i -> isItemSuccess(i.getTaskItemState()));
        boolean allCommandsSuccess = commands.stream().allMatch(c -> isItemSuccess(c.getCommandState()));
        if (!allItemsSuccess || !allCommandsSuccess) return false;

        // 大任务不是成功类终态 → 回写异常
        String taskState = ctx.getTaskState();
        return taskState != null
                && !"SUCCESS".equals(taskState)
                && !"MANUAL_SUCCESS".equals(taskState)
                && !"RUNNING".equals(taskState); // RUNNING 由 all-completed-waiting-wms 处理
    }

    @Override
    public DiagnoseResponse diagnose(DiagnosisContext ctx) {
        DiagnoseResponse configResponse = buildResponseFromConfig(ctx);
        if (configResponse != null) {
            return configResponse;
        }
        String taskState = ctx.getTaskState();

        DiagnoseResponse resp = new DiagnoseResponse();
        resp.setSummary("所有子任务和命令均已完成，但主任务 task_state=" + n(taskState)
                + "（非 SUCCESS/MANUAL_SUCCESS），状态回写异常");
        resp.setRootCauses(List.of(new RootCauseItem("主任务状态回写异常",
                "所有子任务均处于 SUCCESS/MANUAL_SUCCESS，所有命令均已完成，"
                        + "但主任务 task_state=" + n(taskState) + "，预期应为 SUCCESS。"
                        + "可能原因：完成回调失败、状态更新事务回滚、并发冲突")));
        resp.setActions(List.of(
                "检查主任务完成回调的日志记录",
                "确认数据库中 sc_task 是否可手动更新状态",
                "排查状态回写逻辑是否存在异常或事务问题"));
        return resp;
    }
}
