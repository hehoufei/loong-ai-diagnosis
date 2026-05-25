package cn.aimstek.loong.aidiag.rule.rules;

import cn.aimstek.loong.aidiag.dto.DiagnoseResponse;
import cn.aimstek.loong.aidiag.dto.RootCauseItem;
import cn.aimstek.loong.aidiag.dto.TaskDetail;
import cn.aimstek.loong.aidiag.rule.AbstractDiagnoseRule;
import cn.aimstek.loong.aidiag.rule.DiagnosisContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 检测主任务 task_state 不在已知枚举范围内（数据异常或新增状态未纳入规则）。
 * 已知 taskState：WAIT_SPLIT/WAIT_PLAN/RUNNING/SUCCESS/CANCEL/MANUAL_SUCCESS。
 */
@Slf4j
@Component
public class HandleStateUnknownRule extends AbstractDiagnoseRule {

    private static final Set<String> KNOWN_TASK_STATES = Set.of(
            "WAIT_SPLIT", "WAIT_PLAN", "RUNNING", "SUCCESS", "CANCEL", "MANUAL_SUCCESS"
    );

    public HandleStateUnknownRule() {
        this.description = "检测任务处于未知 taskState 阶段，需要 AI 大模型深度分析";
    }

    @Override
    public String getName() {
        return "task-state-unknown";
    }

    @Override
    public int getPriority() {
        return 870;
    }

    @Override
    public boolean match(DiagnosisContext ctx) {
        String ts = ctx.getTaskState();
        if (ts == null || ts.isBlank()) return true;
        return !KNOWN_TASK_STATES.contains(ts);
    }

    @Override
    public DiagnoseResponse diagnose(DiagnosisContext ctx) {
        DiagnoseResponse configResponse = buildResponseFromConfig(ctx);
        if (configResponse != null) {
            return configResponse;
        }
        try {
            TaskDetail detail = ctx.getDetail();
            List<TaskDetail.TaskItemDetail> items = ctx.getTaskItems();

            String ts = detail != null && detail.getTaskState() != null ? detail.getTaskState() : "未知";
            long completedItems = items.stream()
                    .filter(i -> isItemSuccess(i.getTaskItemState())).count();
            long runningItems = items.stream()
                    .filter(i -> "RUNNING".equals(i.getTaskItemState())).count();

            String summary = String.format(
                    "任务 task_state=%s 不在已知枚举范围内，共%d个子任务，其中%d个已完成、%d个运行中。建议使用 AI 大模型进行深度分析。",
                    ts, items.size(), completedItems, runningItems);

            DiagnoseResponse fallback = new DiagnoseResponse();
            fallback.setSummary(summary);
            fallback.setRootCauses(List.of(new RootCauseItem("未知 taskState",
                    "当前任务状态组合未匹配到已知故障模式，可能是新增的业务状态尚未纳入规则。建议使用 AI 大模型进行深度分析")));
            fallback.setActions(List.of("点击「AI大模型深度分析」按钮获取更详细的诊断",
                    "检查 task_state 是否为新增枚举值",
                    "确认相关设备和系统是否正常"));
            return fallback;
        } catch (Exception e) {
            log.warn("兜底分析异常: {}", e.getMessage());
            DiagnoseResponse resp = new DiagnoseResponse();
            resp.setSummary("规则分析过程出现异常，建议使用AI大模型进行深度分析");
            resp.setRootCauses(List.of(new RootCauseItem("分析异常", "规则引擎处理时出现异常: " + e.getMessage())));
            resp.setActions(List.of("点击「AI大模型深度分析」按钮", "检查系统运行状态"));
            return resp;
        }
    }
}
