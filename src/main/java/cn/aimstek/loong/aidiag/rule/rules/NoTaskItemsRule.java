package cn.aimstek.loong.aidiag.rule.rules;

import cn.aimstek.loong.aidiag.dto.DiagnoseResponse;
import cn.aimstek.loong.aidiag.dto.RootCauseItem;
import cn.aimstek.loong.aidiag.rule.AbstractDiagnoseRule;
import cn.aimstek.loong.aidiag.rule.DiagnosisContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 场景：主任务 task_state=RUNNING 但没有任何子任务，数据异常。
 */
@Slf4j
@Component
public class NoTaskItemsRule extends AbstractDiagnoseRule {

    public NoTaskItemsRule() {
        this.description = "检查主任务已进入 RUNNING 但未生成子任务的异常情况";
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
        return "RUNNING".equals(ctx.getTaskState()) && ctx.getTaskItems().isEmpty();
    }

    @Override
    public DiagnoseResponse diagnose(DiagnosisContext ctx) {
        DiagnoseResponse configResponse = buildResponseFromConfig(ctx);
        if (configResponse != null) {
            return configResponse;
        }
        DiagnoseResponse resp = new DiagnoseResponse();
        resp.setSummary("主任务已进入 RUNNING 但未生成任何子任务，数据异常");
        resp.setRootCauses(List.of(new RootCauseItem("子任务未生成",
                "task_state=RUNNING 表示任务应已进入执行阶段，但 task_items 为空，可能是任务拆分逻辑异常或子任务持久化失败")));
        resp.setActions(List.of("查看任务拆分服务日志",
                "检查 sc_task_item 表是否有对应数据",
                "确认引擎返回的路径数据是否正常"));
        return resp;
    }
}
