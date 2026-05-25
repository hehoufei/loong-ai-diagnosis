package cn.aimstek.loong.aidiag.rule;

import cn.aimstek.loong.aidiag.dto.DiagnoseResponse;
import cn.aimstek.loong.aidiag.dto.RootCauseItem;
import cn.aimstek.loong.aidiag.dto.TaskDetail;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 诊断规则抽象基类，提供通用辅助方法。
 */
@Slf4j
public abstract class AbstractDiagnoseRule implements DiagnoseRule {

    @Autowired(required = false)
    private RuleProperties ruleProperties;

    /** 规则描述（代码默认值，可被YAML配置覆盖） */
    protected String description = "";

    @Override
    public String getDescription() {
        if (ruleProperties != null) {
            RuleProperties.RuleConfig config = ruleProperties.getRules().get(getName());
            if (config != null && config.getDescription() != null && !config.getDescription().isEmpty()) {
                return config.getDescription();
            }
        }
        return this.description;
    }

    /**
     * 获取规则的自定义参数值
     */
    protected String getParam(String key, String defaultValue) {
        if (ruleProperties == null) return defaultValue;
        RuleProperties.RuleConfig config = ruleProperties.getRules().get(getName());
        if (config == null || config.getParams() == null) return defaultValue;
        return config.getParams().getOrDefault(key, defaultValue);
    }

    /**
     * 获取规则的自定义参数值（int类型）
     */
    protected int getIntParam(String key, int defaultValue) {
        String value = getParam(key, null);
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 快速构建 DiagnoseResponse（单根因）
     */
    protected DiagnoseResponse buildResponse(String summary, String rootCauseTitle,
                                              String rootCauseDesc, String... actions) {
        DiagnoseResponse resp = new DiagnoseResponse();
        resp.setSummary(summary);
        List<RootCauseItem> causes = new ArrayList<>();
        causes.add(new RootCauseItem(rootCauseTitle, rootCauseDesc));
        resp.setRootCauses(causes);
        resp.setActions(new ArrayList<>(Arrays.asList(actions)));
        return resp;
    }

    /**
     * 快速构建 DiagnoseResponse（多根因）
     */
    protected DiagnoseResponse buildResponse(String summary, List<RootCauseItem> rootCauses,
                                              String... actions) {
        DiagnoseResponse resp = new DiagnoseResponse();
        resp.setSummary(summary);
        resp.setRootCauses(rootCauses != null ? rootCauses : new ArrayList<>());
        resp.setActions(new ArrayList<>(Arrays.asList(actions)));
        return resp;
    }

    /**
     * 检查子任务/命令是否处于成功完成状态。
     * 适配新状态体系：SUCCESS / MANUAL_SUCCESS（兼容旧值 AUTO_SUCCESS）。
     */
    protected boolean isItemSuccess(String state) {
        return "SUCCESS".equals(state)
                || "MANUAL_SUCCESS".equals(state)
                || "AUTO_SUCCESS".equals(state);
    }

    /** null 安全字符串转换（null → 空串） */
    protected String n(String v) {
        return v == null ? "" : v;
    }

    /**
     * 从配置的OutputConfig构建DiagnoseResponse（支持模板变量替换）
     * @return 如果OutputConfig存在且summary非空，返回构建的响应；否则返回null（让子类用原有逻辑）
     */
    protected DiagnoseResponse buildResponseFromConfig(DiagnosisContext context) {
        if (ruleProperties == null) return null;
        RuleProperties.RuleConfig config = ruleProperties.getRules().get(getName());
        if (config == null || config.getOutput() == null) return null;
        RuleProperties.OutputConfig output = config.getOutput();
        if (output.getSummary() == null || output.getSummary().isEmpty()) return null;

        DiagnoseResponse resp = new DiagnoseResponse();
        resp.setSummary(resolveTemplate(output.getSummary(), context));

        if (output.getRootCauses() != null) {
            List<RootCauseItem> causes = output.getRootCauses().stream()
                    .map(rc -> new RootCauseItem(
                            resolveTemplate(rc.getTitle(), context),
                            resolveTemplate(rc.getDescription(), context)))
                    .collect(Collectors.toList());
            resp.setRootCauses(causes);
        }

        if (output.getActions() != null) {
            List<String> actions = output.getActions().stream()
                    .map(a -> resolveTemplate(a, context))
                    .collect(Collectors.toList());
            resp.setActions(actions);
        }

        return resp;
    }

    /**
     * 替换模板中的 {variable} 占位符为DiagnosisContext中的实际值
     */
    protected String resolveTemplate(String template, DiagnosisContext context) {
        if (template == null || template.isEmpty()) return template;

        String result = template;
        result = result.replace("{taskState}", n(context.getTaskState()));
        result = result.replace("{paused}", context.isPaused() ? "Y" : "N");
        result = result.replace("{errorMessage}", n(context.getErrorMessage()));

        List<TaskDetail.TaskItemDetail> items = context.getTaskItems();
        result = result.replace("{taskItemCount}", String.valueOf(items.size()));

        long completedCount = items.stream()
                .filter(i -> isItemSuccess(i.getTaskItemState()))
                .count();
        long runningCount = items.stream()
                .filter(i -> "RUNNING".equals(i.getTaskItemState()))
                .count();
        long waitSplitCount = items.stream()
                .filter(i -> "WAIT_SPLIT".equals(i.getTaskItemState()))
                .count();
        long waitPlanCount = items.stream()
                .filter(i -> "WAIT_PLAN".equals(i.getTaskItemState()))
                .count();
        long pausedCount = items.stream()
                .filter(i -> "PAUSED".equals(i.getTaskItemState()))
                .count();
        long cancelledCount = items.stream()
                .filter(i -> "CANCEL".equals(i.getTaskItemState()))
                .count();
        long commandCount = context.getCommands().size();
        long failedCommandCount = context.getCommands().stream()
                .filter(c -> "FAILED".equals(c.getCommandState()))
                .count();

        result = result.replace("{completedCount}", String.valueOf(completedCount));
        result = result.replace("{runningCount}", String.valueOf(runningCount));
        result = result.replace("{waitSplitCount}", String.valueOf(waitSplitCount));
        result = result.replace("{waitPlanCount}", String.valueOf(waitPlanCount));
        result = result.replace("{pausedCount}", String.valueOf(pausedCount));
        result = result.replace("{cancelledCount}", String.valueOf(cancelledCount));
        result = result.replace("{commandCount}", String.valueOf(commandCount));
        result = result.replace("{failedCommandCount}", String.valueOf(failedCommandCount));

        return result;
    }
}
