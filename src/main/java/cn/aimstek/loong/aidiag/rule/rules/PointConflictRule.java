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
 * 场景7：有点位冲突且有子任务RUNNING。
 */
@Slf4j
@Component
public class PointConflictRule extends AbstractDiagnoseRule {

    public PointConflictRule() {
        this.description = "检测路径点位被其他任务占用导致当前任务无法推进";
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
        return ctx.getTaskItems().stream().anyMatch(i -> "RUNNING".equals(i.getTaskState()));
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
        List<PointConflict> conflicts = ctx.getConflicts();

        TaskDetail.TaskItemDetail runningItem = items.stream()
            .filter(i -> "RUNNING".equals(i.getTaskState())).findFirst().orElse(null);
        if (runningItem == null) {
            return buildResponse("点位冲突检测异常", "点位冲突", "未找到RUNNING状态的子任务",
                "检查子任务状态", "检查点位占用情况");
        }

        StringBuilder conflictDesc = new StringBuilder();
        for (PointConflict c : conflicts) {
            conflictDesc.append("点位").append(c.getLockValue())
                .append("被任务").append(c.getOccupiedBy()).append("占用; ");
        }

        DiagnoseResponse resp = new DiagnoseResponse();
        resp.setSummary("子任务" + runningItem.getId() + "正在执行中(设备:" + n(runningItem.getDeviceCode())
            + ")，但路径点位被其他任务占用，可能导致设备无法移动");
        resp.setRootCauses(List.of(new RootCauseItem("点位冲突",
            "子任务" + runningItem.getId() + "状态为RUNNING，路径上存在点位冲突：" + conflictDesc
            + "设备可能因点位被占用而无法推进")));
        resp.setActions(List.of(
            "检查占用点位的任务是否也卡住",
            "如占用任务已完成但未释放锁，手动清理点位锁",
            "考虑取消冲突任务释放路径"));
        return resp;
    }
}
