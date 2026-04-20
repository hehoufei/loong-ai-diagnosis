package cn.aimstek.loong.aidiag.rule.rules;

import cn.aimstek.loong.aidiag.dto.DiagnoseResponse;
import cn.aimstek.loong.aidiag.dto.RootCauseItem;
import cn.aimstek.loong.aidiag.rule.AbstractDiagnoseRule;
import cn.aimstek.loong.aidiag.rule.DiagnosisContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 场景D：任务或日志中有明确的错误信息。
 */
@Slf4j
@Component
public class ErrorMessageRule extends AbstractDiagnoseRule {

    public ErrorMessageRule() {
        this.description = "检测系统错误信息或日志中的异常关键词";
    }

    @Override
    public String getName() {
        return "error-message";
    }

    @Override
    public int getPriority() {
        return 890;
    }

    @Override
    public boolean match(DiagnosisContext ctx) {
        try {
            // 检查主任务errorMessage
            String errorMsg = ctx.getErrorMessage();
            if (errorMsg != null && !errorMsg.isBlank()) {
                return true;
            }
            // 检查日志中的错误关键词
            String[] keywords = getParam("keywords", "error,exception,异常,失败").split(",");
            List<String> logs = ctx.getLogs();
            if (logs != null) {
                for (String logLine : logs) {
                    if (logLine != null) {
                        String lower = logLine.toLowerCase();
                        for (String keyword : keywords) {
                            if (lower.contains(keyword.trim().toLowerCase())) {
                                return true;
                            }
                        }
                    }
                }
            }
            return false;
        } catch (Exception e) {
            log.warn("场景D(错误信息检测)匹配异常: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public DiagnoseResponse diagnose(DiagnosisContext ctx) {
        // 检查是否有配置覆盖
        DiagnoseResponse configResponse = buildResponseFromConfig(ctx);
        if (configResponse != null) {
            return configResponse;
        }
        // 以下保持原有逻辑不变
        List<String> errors = new ArrayList<>();
        String errorMsg = ctx.getErrorMessage();
        if (errorMsg != null && !errorMsg.isBlank()) {
            errors.add("主任务错误: " + errorMsg);
        }
        // 从日志中提取错误关键词
        String[] keywords = getParam("keywords", "error,exception,异常,失败").split(",");
        List<String> logs = ctx.getLogs();
        if (logs != null) {
            for (String logLine : logs) {
                if (logLine != null) {
                    String lower = logLine.toLowerCase();
                    for (String keyword : keywords) {
                        if (lower.contains(keyword.trim().toLowerCase())) {
                            errors.add("日志错误: " + (logLine.length() > 200 ? logLine.substring(0, 200) + "..." : logLine));
                            break;
                        }
                    }
                }
            }
        }

        int maxErrors = getIntParam("max-errors", 5);
        String errorSummary = errors.size() > maxErrors
            ? String.join("\n", errors.subList(0, maxErrors)) + "\n...共" + errors.size() + "条错误"
            : String.join("\n", errors);

        DiagnoseResponse resp = new DiagnoseResponse();
        resp.setSummary("系统报告了明确的错误信息，共发现" + errors.size() + "条错误记录");
        resp.setRootCauses(List.of(new RootCauseItem("系统错误信息", errorSummary)));

        // 根据错误类型给出建议
        List<String> actions = new ArrayList<>();
        String allErrors = String.join(" ", errors).toLowerCase();
        if (allErrors.contains("通信") || allErrors.contains("connect") || allErrors.contains("timeout")) {
            actions.add("检查网络和设备通信连接");
        }
        if (allErrors.contains("引擎") || allErrors.contains("engine") || allErrors.contains("规划")) {
            actions.add("检查引擎服务是否正常运行");
        }
        if (allErrors.contains("数据库") || allErrors.contains("sql") || allErrors.contains("database")) {
            actions.add("检查数据库连接和数据一致性");
        }
        actions.add("根据错误信息排查具体问题");
        actions.add("查看完整系统日志获取更多上下文");
        resp.setActions(actions);
        return resp;
    }
}
