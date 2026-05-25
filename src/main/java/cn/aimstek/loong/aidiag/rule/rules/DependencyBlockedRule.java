package cn.aimstek.loong.aidiag.rule.rules;

import cn.aimstek.loong.aidiag.dto.DiagnoseResponse;
import cn.aimstek.loong.aidiag.dto.RootCauseItem;
import cn.aimstek.loong.aidiag.rule.AbstractDiagnoseRule;
import cn.aimstek.loong.aidiag.rule.DiagnosisContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 场景：任务处于 WAIT_PLAN / WAIT_SPLIT 阶段且存在前置依赖任务（preStartTaskNo / preEndTaskNo），
 * 任务被依赖关系阻塞，需等待依赖任务完成。
 */
@Slf4j
@Component
public class DependencyBlockedRule extends AbstractDiagnoseRule {

    public DependencyBlockedRule() {
        this.description = "检测任务因前置依赖（preStartTaskNo/preEndTaskNo）未完成而阻塞";
    }

    @Override
    public String getName() {
        return "dependency-blocked";
    }

    @Override
    public int getPriority() {
        return 950;
    }

    @Override
    public boolean match(DiagnosisContext ctx) {
        String ts = ctx.getTaskState();
        if (!"WAIT_PLAN".equals(ts) && !"WAIT_SPLIT".equals(ts)) {
            return false;
        }
        Map<String, String> deps = ctx.getDependencies();
        return notBlank(deps.get("preStartTaskNo")) || notBlank(deps.get("preEndTaskNo"));
    }

    @Override
    public DiagnoseResponse diagnose(DiagnosisContext ctx) {
        DiagnoseResponse configResponse = buildResponseFromConfig(ctx);
        if (configResponse != null) {
            return configResponse;
        }
        Map<String, String> deps = ctx.getDependencies();
        List<String> hints = new ArrayList<>();
        if (notBlank(deps.get("preStartTaskNo"))) {
            hints.add("preStartTaskNo=" + deps.get("preStartTaskNo"));
        }
        if (notBlank(deps.get("preEndTaskNo"))) {
            hints.add("preEndTaskNo=" + deps.get("preEndTaskNo"));
        }
        if (notBlank(deps.get("parentTaskNo"))) {
            hints.add("parentTaskNo=" + deps.get("parentTaskNo"));
        }

        DiagnoseResponse resp = new DiagnoseResponse();
        resp.setSummary("任务等待依赖任务完成（task_state=" + n(ctx.getTaskState()) + "，依赖："
                + String.join(", ", hints) + "）");
        resp.setRootCauses(List.of(new RootCauseItem("等待依赖任务完成",
                "当前任务的前置依赖任务尚未完成或未释放，调度器无法对其拆分/规划。"
                        + "依赖关系：" + String.join("; ", hints))));
        resp.setActions(List.of(
                "查询前置依赖任务的当前状态",
                "若依赖任务卡住，先排查依赖任务的故障",
                "确认依赖关系配置是否合理"));
        return resp;
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
