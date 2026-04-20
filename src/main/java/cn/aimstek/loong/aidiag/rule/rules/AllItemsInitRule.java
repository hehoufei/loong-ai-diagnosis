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
 * 场景6：所有子任务都是INIT，还没开始调度。
 */
@Slf4j
@Component
public class AllItemsInitRule extends AbstractDiagnoseRule {

    public AllItemsInitRule() {
        this.description = "检查子任务全部处于初始状态，定时调度尚未处理";
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
        return items.stream().allMatch(i -> "INIT".equals(i.getTaskState()));
    }

    @Override
    public DiagnoseResponse diagnose(DiagnosisContext ctx) {
        // 检查是否有配置覆盖
        DiagnoseResponse configResponse = buildResponseFromConfig(ctx);
        if (configResponse != null) {
            return configResponse;
        }
        // 以下保持原有逻辑不变
        List<TaskDetail.TaskItemDetail> items = ctx.getTaskItems();

        DiagnoseResponse resp = new DiagnoseResponse();
        resp.setSummary("子任务已生成但全部处于INIT状态，定时调度任务尚未处理");
        resp.setRootCauses(List.of(new RootCauseItem("子任务未被调度",
            "共" + items.size() + "个子任务均为INIT状态，定时任务尚未调用引擎查询可用设备。"
            + "可能原因：定时任务未执行、调度队列积压、引擎查询可用设备失败")));
        resp.setActions(List.of("检查定时调度任务是否正常运行", "查看引擎服务是否可用", "检查是否有大量任务排队"));
        return resp;
    }
}
