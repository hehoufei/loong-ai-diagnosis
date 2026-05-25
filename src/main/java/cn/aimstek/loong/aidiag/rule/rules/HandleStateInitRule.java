package cn.aimstek.loong.aidiag.rule.rules;

import cn.aimstek.loong.aidiag.dto.DiagnoseResponse;
import cn.aimstek.loong.aidiag.dto.RootCauseItem;
import cn.aimstek.loong.aidiag.rule.AbstractDiagnoseRule;
import cn.aimstek.loong.aidiag.rule.DiagnosisContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 检查任务是否卡在 WAIT_SPLIT 阶段（任务尚未拆分子任务）。
 * 旧 handleState=INIT 的等价场景：调度器尚未对任务进行拆分。
 */
@Slf4j
@Component
public class HandleStateInitRule extends AbstractDiagnoseRule {

    public HandleStateInitRule() {
        this.description = "检查任务是否卡在 WAIT_SPLIT 阶段，调度器尚未对任务进行拆分";
    }

    @Override
    public String getName() {
        return "task-state-wait-split";
    }

    @Override
    public int getPriority() {
        return 1000;
    }

    @Override
    public boolean match(DiagnosisContext ctx) {
        return getParam("target-state", "WAIT_SPLIT").equals(ctx.getTaskState());
    }

    @Override
    public DiagnoseResponse diagnose(DiagnosisContext ctx) {
        // 检查是否有配置覆盖
        DiagnoseResponse configResponse = buildResponseFromConfig(ctx);
        if (configResponse != null) {
            return configResponse;
        }
        DiagnoseResponse resp = new DiagnoseResponse();
        resp.setSummary("任务卡在 WAIT_SPLIT 阶段，调度器尚未对任务进行拆分（task_state=WAIT_SPLIT）");
        resp.setRootCauses(List.of(new RootCauseItem("任务未被拆分",
                "主任务 task_state 仍为 WAIT_SPLIT，调度器尚未将任务拆分为子任务。"
                        + "可能原因：调度排队等待、系统繁忙、起终点(startNode/endNode)配置异常、拆分服务不可用、依赖任务未完成")));
        resp.setActions(List.of("检查任务拆分服务是否正常", "确认 startNode/endNode 配置是否正确",
                "查看是否存在依赖任务（preStartTaskNo/preEndTaskNo）阻塞", "查看系统日志是否有拆分失败记录"));
        return resp;
    }
}
