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
 * 兜底规则：当所有精确规则都不匹配时，生成通用摘要。
 * 永远匹配，优先级最低。
 */
@Slf4j
@Component
public class FallbackRule extends AbstractDiagnoseRule {

    public FallbackRule() {
        this.description = "兖底规则，所有精确规则都不匹配时触发，推荐使用AI深度分析";
    }

    @Override
    public String getName() {
        return "fallback";
    }

    @Override
    public int getPriority() {
        return 0;
    }

    @Override
    public boolean match(DiagnosisContext ctx) {
        return true;
    }

    @Override
    public DiagnoseResponse diagnose(DiagnosisContext ctx) {
        // 检查是否有配置覆盖
        DiagnoseResponse configResponse = buildResponseFromConfig(ctx);
        if (configResponse != null) {
            return configResponse;
        }
        // 以下保持原有逻辑不变
        try {
            TaskDetail detail = ctx.getDetail();
            List<TaskDetail.TaskItemDetail> items = ctx.getTaskItems();

            String ts = detail != null && detail.getTaskState() != null ? detail.getTaskState() : "未知";
            String paused = ctx.isPaused() ? "Y" : "N";
            long completedItems = items.stream()
                    .filter(i -> isItemSuccess(i.getTaskItemState())).count();
            long runningItems = items.stream()
                    .filter(i -> "RUNNING".equals(i.getTaskItemState())).count();

            String summary = String.format(
                    "任务当前 task_state=%s（paused=%s），共%d个子任务，其中%d个已完成、%d个运行中。建议使用AI大模型进行深度分析。",
                    ts, paused, items.size(), completedItems, runningItems);

            DiagnoseResponse fallback = new DiagnoseResponse();
            fallback.setSummary(summary);
            fallback.setRootCauses(List.of(new RootCauseItem("需要进一步分析",
                    "当前任务状态组合未匹配到已知故障模式，建议点击下方按钮使用AI大模型进行深度分析")));
            fallback.setActions(List.of("点击「AI大模型深度分析」按钮获取更详细的诊断", "检查系统运行状态", "查看相关设备是否正常"));
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
