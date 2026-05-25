package cn.aimstek.loong.aidiag.rule.rules;

import cn.aimstek.loong.aidiag.dto.DiagnoseResponse;
import cn.aimstek.loong.aidiag.dto.RootCauseItem;
import cn.aimstek.loong.aidiag.rule.AbstractDiagnoseRule;
import cn.aimstek.loong.aidiag.rule.DiagnosisContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 场景：任务被人工暂停（detail.paused == "Y"）。
 * 适配 loong-platform 新增的 paused 字段。
 */
@Slf4j
@Component
public class TaskPausedRule extends AbstractDiagnoseRule {

    public TaskPausedRule() {
        this.description = "检测主任务被人工暂停（paused=Y）";
    }

    @Override
    public String getName() {
        return "task-paused";
    }

    @Override
    public int getPriority() {
        return 980;
    }

    @Override
    public boolean match(DiagnosisContext ctx) {
        return ctx.isPaused();
    }

    @Override
    public DiagnoseResponse diagnose(DiagnosisContext ctx) {
        DiagnoseResponse configResponse = buildResponseFromConfig(ctx);
        if (configResponse != null) {
            return configResponse;
        }
        DiagnoseResponse resp = new DiagnoseResponse();
        resp.setSummary("任务被人工暂停（paused=Y），等待恢复执行");
        resp.setRootCauses(List.of(new RootCauseItem("任务已被暂停",
                "主任务字段 paused=Y，处于人工暂停状态。任务不会被调度器继续推进，需要人工恢复后才能继续执行")));
        resp.setActions(List.of(
                "确认是哪个操作员或系统调用了暂停",
                "评估业务影响后通过管理界面恢复任务（paused=N）",
                "若无需暂停可直接恢复；若需取消则将任务置为 CANCEL"));
        return resp;
    }
}
