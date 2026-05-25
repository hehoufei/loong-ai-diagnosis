package cn.aimstek.loong.aidiag.rule.rules;

import cn.aimstek.loong.aidiag.dto.DiagnoseResponse;
import cn.aimstek.loong.aidiag.dto.PointConflict;
import cn.aimstek.loong.aidiag.dto.RootCauseItem;
import cn.aimstek.loong.aidiag.dto.TaskDetail;
import cn.aimstek.loong.aidiag.rule.AbstractDiagnoseRule;
import cn.aimstek.loong.aidiag.rule.DiagnosisContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 场景：路径节点冲突，且存在 RUNNING 状态的子任务，节点资源被占用导致设备无法推进。
 */
@Slf4j
@Component
public class PointConflictRule extends AbstractDiagnoseRule {

    public PointConflictRule() {
        this.description = "检测路径节点（startNode/endNode）被其他任务占用导致当前任务无法推进";
    }

    @Override
    public String getName() {
        return "point-conflict";
    }

    @Override
    public int getPriority() {
        return 930;
    }

    @Override
    public boolean match(DiagnosisContext ctx) {
        if (!ctx.hasConflicts()) return false;
        return ctx.getTaskItems().stream().anyMatch(i -> "RUNNING".equals(i.getTaskItemState()));
    }

    @Override
    public DiagnoseResponse diagnose(DiagnosisContext ctx) {
        DiagnoseResponse configResponse = buildResponseFromConfig(ctx);
        if (configResponse != null) {
            return configResponse;
        }
        List<TaskDetail.TaskItemDetail> items = ctx.getTaskItems();
        List<PointConflict> conflicts = ctx.getConflicts();

        TaskDetail.TaskItemDetail runningItem = items.stream()
                .filter(i -> "RUNNING".equals(i.getTaskItemState())).findFirst().orElse(null);
        if (runningItem == null) {
            return buildResponse("节点冲突检测异常", "节点冲突", "未找到 RUNNING 状态的子任务",
                    "检查子任务状态", "检查节点占用情况");
        }

        StringBuilder conflictDesc = new StringBuilder();
        for (PointConflict c : conflicts) {
            conflictDesc.append("节点").append(c.getLockValue())
                    .append("被任务").append(c.getOccupiedBy()).append("占用; ");
        }

        String itemKey = runningItem.getTaskItemNo() != null ? runningItem.getTaskItemNo() : runningItem.getId();
        DiagnoseResponse resp = new DiagnoseResponse();
        resp.setSummary("子任务" + itemKey + "正在执行中(设备:" + n(runningItem.getDeviceCode())
                + "，起点:" + n(runningItem.getStartNode()) + " → 终点:" + n(runningItem.getEndNode())
                + ")，但路径节点被其他任务占用，可能导致设备无法移动");
        resp.setRootCauses(List.of(new RootCauseItem("节点冲突",
                "子任务" + itemKey + " 状态为 RUNNING，路径上存在节点冲突：" + conflictDesc
                        + "设备可能因节点被占用而无法推进")));
        resp.setActions(List.of(
                "检查占用节点的任务是否也卡住",
                "如占用任务已完成但未释放锁，手动清理节点锁",
                "考虑取消冲突任务释放路径"));
        return resp;
    }
}
