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
public class HandleStateInitRule extends AbstractDiagnoseRule {

    public HandleStateInitRule() {
        this.description = "检查任务是否卡在初始化(INIT)阶段，WCS尚未调用引擎进行关键点规划";
    }

    @Override
    public String getName() {
        return "handle-state-init";
    }

    @Override
    public int getPriority() {
        return 1000;
    }

    @Override
    public boolean match(DiagnosisContext ctx) {
        return getParam("target-state", "INIT").equals(ctx.getHandleState());
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
        resp.setSummary("任务卡在初始阶段，WCS还未调用引擎进行关键点规划，handle_state=INIT");
        resp.setRootCauses(List.of(new RootCauseItem("任务未开始规划",
            "大任务handle_state仍为INIT，WCS尚未调用引擎获取关键点。可能原因：调度排队等待、系统繁忙、起终点配置异常、引擎服务不可用")));
        resp.setActions(List.of("检查引擎服务是否正常", "检查起终点配置是否正确", "查看系统日志是否有引擎调用失败记录"));
        return resp;
    }
}
