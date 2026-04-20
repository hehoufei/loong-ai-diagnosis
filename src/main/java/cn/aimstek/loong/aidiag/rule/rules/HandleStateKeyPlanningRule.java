package cn.aimstek.loong.aidiag.rule.rules;

import cn.aimstek.loong.aidiag.dto.DiagnoseResponse;
import cn.aimstek.loong.aidiag.dto.RootCauseItem;
import cn.aimstek.loong.aidiag.rule.AbstractDiagnoseRule;
import cn.aimstek.loong.aidiag.rule.DiagnosisContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class HandleStateKeyPlanningRule extends AbstractDiagnoseRule {

    public HandleStateKeyPlanningRule() {
        this.description = "检查任务是否卡在关键点规划(KEY_PLANNING)阶段，引擎未返回结果或处理失败";
    }

    @Override
    public String getName() {
        return "handle-state-key-planning";
    }

    @Override
    public int getPriority() {
        return 990;
    }

    @Override
    public boolean match(DiagnosisContext ctx) {
        return getParam("target-state", "KEY_PLANNING").equals(ctx.getHandleState());
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
        resp.setSummary("任务卡在关键点规划阶段，handle_state=KEY_PLANNING，引擎未返回结果或处理失败");
        resp.setRootCauses(List.of(new RootCauseItem("关键点规划未完成",
            "WCS已调用引擎获取关键点但未成功完成。可能原因：引擎响应超时、路径不通、点位被占用")));
        resp.setActions(List.of("检查引擎服务状态", "查看日志中引擎调用的返回结果", "检查起终点之间是否有可用路径"));
        return resp;
    }
}
