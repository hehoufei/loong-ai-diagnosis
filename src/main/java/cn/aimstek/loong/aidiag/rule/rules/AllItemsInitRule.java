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
 * 场景：所有子任务都处于 WAIT_SPLIT / WAIT_PLAN（尚未进入运行阶段），调度器尚未处理。
 */
@Slf4j
@Component
public class AllItemsInitRule extends AbstractDiagnoseRule {

    public AllItemsInitRule() {
        this.description = "检查子任务全部处于 WAIT_SPLIT/WAIT_PLAN 状态，调度尚未推进";
    }

    @Override
    public String getName() {
        return "all-items-init";
    }

    @Override
    public int getPriority() {
        return 940;
    }

    @Override
    public boolean match(DiagnosisContext ctx) {
        List<TaskDetail.TaskItemDetail> items = ctx.getTaskItems();
        if (items.isEmpty()) return false;
        return items.stream().allMatch(i -> "WAIT_SPLIT".equals(i.getTaskItemState())
                || "WAIT_PLAN".equals(i.getTaskItemState()));
    }

    @Override
    public DiagnoseResponse diagnose(DiagnosisContext ctx) {
        DiagnoseResponse configResponse = buildResponseFromConfig(ctx);
        if (configResponse != null) {
            return configResponse;
        }
        List<TaskDetail.TaskItemDetail> items = ctx.getTaskItems();
        long waitSplit = items.stream().filter(i -> "WAIT_SPLIT".equals(i.getTaskItemState())).count();
        long waitPlan = items.stream().filter(i -> "WAIT_PLAN".equals(i.getTaskItemState())).count();

        DiagnoseResponse resp = new DiagnoseResponse();
        resp.setSummary("所有子任务尚未进入运行阶段（WAIT_SPLIT=" + waitSplit + ", WAIT_PLAN=" + waitPlan
                + "），调度器尚未对子任务进行下发");
        resp.setRootCauses(List.of(new RootCauseItem("子任务未被调度",
                "共" + items.size() + "个子任务均处于 WAIT_SPLIT/WAIT_PLAN 状态，调度器尚未触发拆分或路径规划。"
                        + "可能原因：定时任务未执行、调度队列积压、引擎查询可用设备失败、依赖任务阻塞")));
        resp.setActions(List.of("检查定时调度任务是否正常运行", "查看引擎/规划服务是否可用",
                "检查是否有大量任务排队", "确认子任务是否存在依赖未满足"));
        return resp;
    }
}
