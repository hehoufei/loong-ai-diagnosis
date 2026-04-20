package cn.aimstek.loong.aidiag.rule.rules;

import cn.aimstek.loong.aidiag.dto.DiagnoseResponse;
import cn.aimstek.loong.aidiag.dto.RootCauseItem;
import cn.aimstek.loong.aidiag.rule.AbstractDiagnoseRule;
import cn.aimstek.loong.aidiag.rule.DiagnosisContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 场景4：路径规划已完成但未生成子任务。
 */
@Slf4j
@Component
public class NoTaskItemsRule extends AbstractDiagnoseRule {

    public NoTaskItemsRule() {
        this.description = "检查路径规划完成但未生成子任务的异常情况";
    }

    @Override
    public String getName() {
        return "no-task-items";
    }

    @Override
    public int getPriority() {
        return 970;
    }

    @Override
    public boolean match(DiagnosisContext ctx) {
        return "PATH_PLANNING".equals(ctx.getHandleState()) && ctx.getTaskItems().isEmpty();
    }

    @Override
    public DiagnoseResponse diagnose(DiagnosisContext ctx) {
        // 检查是否有配置覆盖
        DiagnoseResponse configResponse = buildResponseFromConfig(ctx);
        if (configResponse != null) {
            return configResponse;
        }
        // 以下保持原有逻辑不变
        DiagnoseResponse resp = new DiagnoseResponse();
        resp.setSummary("路径规划已完成但未生成子任务，数据异常");
        resp.setRootCauses(List.of(new RootCauseItem("子任务未生成",
            "handle_state=PATH_PLANNING表示路径规划完成，但没有子任务记录，可能是路径拆分逻辑异常")));
        resp.setActions(List.of("查看日志中路径拆分相关记录", "检查引擎返回的路径数据是否正常"));
        return resp;
    }
}
