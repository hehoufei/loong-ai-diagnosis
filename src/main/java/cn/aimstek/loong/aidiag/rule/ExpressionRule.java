package cn.aimstek.loong.aidiag.rule;

import cn.aimstek.loong.aidiag.dto.DiagnoseResponse;
import cn.aimstek.loong.aidiag.dto.RootCauseItem;
import cn.aimstek.loong.aidiag.dto.TaskDetail;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 基于SpEL表达式的动态规则，完全通过配置驱动。
 * 不是Spring Bean，由ConfigurableRuleEngine动态创建。
 */
@Slf4j
public class ExpressionRule implements DiagnoseRule {

    private static final SpelExpressionParser parser = new SpelExpressionParser();

    @Getter
    private final String name;
    @Getter
    private final String description;
    @Getter
    private final int priority;
    private final String condition;
    private final RuleProperties.OutputConfig output;
    private final Map<String, String> params;

    public ExpressionRule(String name, RuleProperties.RuleConfig config) {
        this.name = name;
        this.description = config.getDescription() != null ? config.getDescription() : "";
        this.priority = config.getPriority() != -1 ? config.getPriority() : 0;
        this.condition = config.getCondition();
        this.output = config.getOutput();
        this.params = config.getParams();
    }

    @Override
    public boolean match(DiagnosisContext context) {
        try {
            EvaluationContext evalContext = buildEvalContext(context);
            Expression expr = parser.parseExpression(condition);
            return Boolean.TRUE.equals(expr.getValue(evalContext, Boolean.class));
        } catch (Exception e) {
            log.warn("表达式规则 {} 的SpEL条件执行出错: {}, condition={}", name, e.getMessage(), condition, e);
            return false;
        }
    }

    @Override
    public DiagnoseResponse diagnose(DiagnosisContext context) {
        if (output == null || output.getSummary() == null || output.getSummary().isEmpty()) {
            log.warn("表达式规则 {} 没有配置输出模板", name);
            return null;
        }

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

    @Override
    public String getDescription() {
        return description;
    }

    private EvaluationContext buildEvalContext(DiagnosisContext context) {
        SimpleEvaluationContext ctx = SimpleEvaluationContext
                .forReadOnlyDataBinding()
                .withInstanceMethods()   // 允许对变量调用实例方法，如 List.contains(), String.isEmpty()
                .build();
        ctx.setVariable("taskItems", context.getTaskItems());
        ctx.setVariable("commands", context.getCommands());
        ctx.setVariable("hasConflicts", context.hasConflicts());
        ctx.setVariable("conflicts", context.getConflicts());
        ctx.setVariable("errorMessage", context.getErrorMessage());
        ctx.setVariable("logs", context.getLogs());
        ctx.setVariable("hasRelevantDocs", context.hasRelevantDocs());
        ctx.setVariable("relevantDocs", context.getRelevantDocs());

        // 大任务状态
        ctx.setVariable("taskState", context.getDetail() != null ? context.getDetail().getTaskState() : null);

        // 子任务状态列表
        ctx.setVariable("taskItemStates", context.getTaskItems().stream()
                .map(TaskDetail.TaskItemDetail::getTaskItemState)
                .collect(Collectors.toList()));

        // 命令状态列表
        ctx.setVariable("commandStates", context.getCommands().stream()
                .map(TaskDetail.CommandDetail::getCommandState)
                .collect(Collectors.toList()));

        // 计算型变量
        List<TaskDetail.TaskItemDetail> items = context.getTaskItems();
        ctx.setVariable("taskItemCount", items.size());
        ctx.setVariable("completedCount", items.stream()
                .filter(i -> "SUCCESS".equals(i.getTaskItemState()) || "MANUAL_SUCCESS".equals(i.getTaskItemState()))
                .count());
        ctx.setVariable("runningCount", items.stream()
                .filter(i -> "RUNNING".equals(i.getTaskItemState()))
                .count());
        ctx.setVariable("initCount", items.stream()
                .filter(i -> "WAIT_PLAN".equals(i.getTaskItemState()) || "WAIT_SPLIT".equals(i.getTaskItemState()))
                .count());
        ctx.setVariable("cancelledCount", items.stream()
                .filter(i -> "CANCEL".equals(i.getTaskItemState()))
                .count());

        // params作为变量暴露
        if (params != null) {
            params.forEach(ctx::setVariable);
        }

        return ctx;
    }

    private String resolveTemplate(String template, DiagnosisContext context) {
        if (template == null || template.isEmpty()) return template;

        String result = template;
        result = result.replace("{errorMessage}", safe(context.getErrorMessage()));

        List<TaskDetail.TaskItemDetail> items = context.getTaskItems();
        result = result.replace("{taskItemCount}", String.valueOf(items.size()));

        long completedCount = items.stream()
                .filter(i -> "SUCCESS".equals(i.getTaskItemState()) || "MANUAL_SUCCESS".equals(i.getTaskItemState()))
                .count();
        long runningCount = items.stream()
                .filter(i -> "RUNNING".equals(i.getTaskItemState()))
                .count();
        long initCount = items.stream()
                .filter(i -> "WAIT_PLAN".equals(i.getTaskItemState()) || "WAIT_SPLIT".equals(i.getTaskItemState()))
                .count();
        long cancelledCount = items.stream()
                .filter(i -> "CANCEL".equals(i.getTaskItemState()))
                .count();

        result = result.replace("{taskState}", context.getDetail() != null && context.getDetail().getTaskState() != null ? context.getDetail().getTaskState() : "");
        result = result.replace("{completedCount}", String.valueOf(completedCount));
        result = result.replace("{runningCount}", String.valueOf(runningCount));
        result = result.replace("{initCount}", String.valueOf(initCount));
        result = result.replace("{cancelledCount}", String.valueOf(cancelledCount));

        return result;
    }

    private String safe(String v) {
        return v == null ? "" : v;
    }
}
