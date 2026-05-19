package cn.aimstek.loong.aidiag.rule;

import cn.aimstek.loong.aidiag.context.DiagnosisContext;
import cn.aimstek.loong.aidiag.dto.DiagnoseResponse;
import cn.aimstek.loong.aidiag.dto.RootCauseItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 诊断规则抽象基类。
 * 提供模板变量替换和从 YAML 配置构建响应的能力。
 */
@Slf4j
public abstract class AbstractDiagnoseRule implements DiagnoseRule {

    @Autowired(required = false)
    protected RuleProperties ruleProperties;

    @Override
    public String getDescription() {
        if (ruleProperties != null) {
            RuleProperties.RuleConfig config = ruleProperties.getRules().get(getName());
            if (config != null && config.getDescription() != null && !config.getDescription().isEmpty()) {
                return config.getDescription();
            }
        }
        return "";
    }

    /**
     * 获取规则参数（字符串）。
     */
    protected String getParam(String key, String defaultValue) {
        if (ruleProperties == null) return defaultValue;
        RuleProperties.RuleConfig config = ruleProperties.getRules().get(getName());
        if (config == null || config.getParams() == null) return defaultValue;
        return config.getParams().getOrDefault(key, defaultValue);
    }

    /**
     * 获取规则参数（int）。
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
     * 从 YAML OutputConfig 构建 DiagnoseResponse（支持模板变量替换）。
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
     * 快速构建 DiagnoseResponse（单根因）。
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
     * 替换模板中的 {variable} 占位符。
     * 支持变量：taskNo, taskState, stuckSeconds, stuckMinutes, itemCount, runningItemCount, errorMessage
     */
    protected String resolveTemplate(String template, DiagnosisContext context) {
        if (template == null || template.isEmpty()) return template;

        String result = template;
        result = result.replace("{taskNo}", safe(context.getTask() != null ? context.getTask().getTaskNo() : null));
        result = result.replace("{taskState}", safe(context.getTask() != null ? context.getTask().getTaskState() : null));
        result = result.replace("{stuckSeconds}", String.valueOf(context.stuckSeconds()));
        result = result.replace("{stuckMinutes}", String.valueOf(context.stuckSeconds() / 60));
        result = result.replace("{itemCount}", String.valueOf(context.getItems() != null ? context.getItems().size() : 0));
        result = result.replace("{runningItemCount}", String.valueOf(context.runningItems().size()));
        result = result.replace("{errorMessage}", safe(context.getTask() != null ? context.getTask().getErrorMessage() : null));

        return result;
    }

    private String safe(String v) {
        return v == null ? "" : v;
    }
}
