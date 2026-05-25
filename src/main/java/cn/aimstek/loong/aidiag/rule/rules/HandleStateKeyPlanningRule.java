package cn.aimstek.loong.aidiag.rule.rules;

import cn.aimstek.loong.aidiag.dto.DiagnoseResponse;
import cn.aimstek.loong.aidiag.dto.RootCauseItem;
import cn.aimstek.loong.aidiag.rule.AbstractDiagnoseRule;
import cn.aimstek.loong.aidiag.rule.DiagnosisContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 检查任务是否卡在 WAIT_PLAN 阶段（已拆分但尚未规划路径）。
 * 旧 handleState=KEY_PLANNING 的等价场景。
 */
@Slf4j
@Component
public class HandleStateKeyPlanningRule extends AbstractDiagnoseRule {

    public HandleStateKeyPlanningRule() {
        this.description = "检查任务是否卡在 WAIT_PLAN 阶段，引擎未返回规划结果或处理失败";
    }

    @Override
    public String getName() {
        return "task-state-wait-plan";
    }

    @Override
    public int getPriority() {
        return 990;
    }

    @Override
    public boolean match(DiagnosisContext ctx) {
        return getParam("target-state", "WAIT_PLAN").equals(ctx.getTaskState());
    }

    @Override
    public DiagnoseResponse diagnose(DiagnosisContext ctx) {
        DiagnoseResponse configResponse = buildResponseFromConfig(ctx);
        if (configResponse != null) {
            return configResponse;
        }
        DiagnoseResponse resp = new DiagnoseResponse();
        resp.setSummary("任务卡在 WAIT_PLAN 阶段（task_state=WAIT_PLAN），引擎未返回规划结果或处理失败");
        resp.setRootCauses(List.of(new RootCauseItem("路径规划未完成",
                "调度系统已调用引擎进行路径规划但尚未成功返回。"
                        + "可能原因：引擎响应超时、起终点节点(startNode/endNode)间路径不通、节点被占用、依赖任务未就绪")));
        resp.setActions(List.of("检查路径规划引擎服务状态", "查看日志中引擎调用的返回结果",
                "检查起终点节点之间是否存在可用路径", "确认依赖任务(preStartTaskNo/preEndTaskNo)是否已完成"));
        return resp;
    }
}
