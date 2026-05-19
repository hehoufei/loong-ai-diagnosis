package cn.aimstek.loong.aidiag.facade;

import cn.aimstek.loong.aidiag.config.DiagnosisProperties;
import cn.aimstek.loong.aidiag.context.DiagnosisContext;
import cn.aimstek.loong.aidiag.context.DiagnosisContextAssembler;
import cn.aimstek.loong.aidiag.dto.DiagnoseRequest;
import cn.aimstek.loong.aidiag.dto.DiagnoseResponse;
import cn.aimstek.loong.aidiag.dto.RootCauseItem;
import cn.aimstek.loong.aidiag.exception.AiDiagnosisException;
import cn.aimstek.loong.aidiag.rule.RuleEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 诊断编排门面。
 * 唯一对外编排入口，协调上下文组装、规则引擎和 LLM 兜底。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiagnosisFacade {

    private final DiagnosisContextAssembler contextAssembler;
    private final RuleEngine ruleEngine;
    private final DiagnosisProperties diagnosisProperties;

    /**
     * LLM 兜底引擎，可选注入（M5 阶段实现）。
     */
    @Autowired(required = false)
    private cn.aimstek.loong.aidiag.llm.LlmDiagnosisEngine llmDiagnosisEngine;

    public DiagnoseResponse diagnose(DiagnoseRequest request) {
        long startTime = System.currentTimeMillis();

        // 1. 参数校验
        validate(request);

        // 2. 组装上下文
        DiagnosisContext context = contextAssembler.assemble(request.getTaskId());
        MDC.put("traceId", context.getTraceId());

        try {
            // 3. 终态任务直接返回
            if (context.isTerminal()) {
                log.info("任务已终态，无需诊断 traceId={} taskState={}",
                        context.getTraceId(), context.getTask().getTaskState());
                DiagnoseResponse response = buildTerminalResponse(context);
                logEnd(context, "terminal", startTime);
                return response;
            }

            // 4. 规则引擎
            DiagnoseResponse ruleResponse = ruleEngine.evaluate(context);
            if (ruleResponse != null) {
                ruleResponse.setDiagnosisMode("rule");
                ruleResponse.setConfidence(diagnosisProperties.getConfidence().getRuleMatch());
                ruleResponse.setTraceId(context.getTraceId());
                logEnd(context, "rule", startTime);
                return ruleResponse;
            }

            // 5. LLM 兜底
            if (diagnosisProperties.isEnableLlmFallback() && llmDiagnosisEngine != null) {
                try {
                    DiagnoseResponse llmResponse = llmDiagnosisEngine.diagnose(context);
                    if (llmResponse != null) {
                        llmResponse.setDiagnosisMode("llm");
                        if (llmResponse.getConfidence() == null) {
                            llmResponse.setConfidence(diagnosisProperties.getConfidence().getLlmMatch());
                        }
                        llmResponse.setTraceId(context.getTraceId());
                        logEnd(context, "llm", startTime);
                        return llmResponse;
                    }
                } catch (Exception e) {
                    log.warn("LLM 兜底失败，降级为 fallback: traceId={}, error={}",
                            context.getTraceId(), e.getMessage());
                }
            }

            // 6. 最终 fallback
            DiagnoseResponse fallback = buildFallbackResponse(context);
            logEnd(context, "fallback", startTime);
            return fallback;
        } finally {
            MDC.remove("traceId");
        }
    }

    private void validate(DiagnoseRequest request) {
        if (request == null || !StringUtils.hasText(request.getTaskId())) {
            throw new AiDiagnosisException("VALIDATION_ERROR", "taskId不能为空");
        }
    }

    private DiagnoseResponse buildTerminalResponse(DiagnosisContext context) {
        DiagnoseResponse response = new DiagnoseResponse();
        response.setSummary("任务已处于终态 " + context.getTask().getTaskState() + "，无需诊断");
        List<RootCauseItem> causes = new ArrayList<>();
        causes.add(new RootCauseItem("任务已终态", "任务状态为 " + context.getTask().getTaskState() + "，不再需要排障分析"));
        response.setRootCauses(causes);
        response.setActions(new ArrayList<>());
        response.setDiagnosisMode("rule");
        response.setConfidence(1.0);
        response.setTraceId(context.getTraceId());
        return response;
    }

    private DiagnoseResponse buildFallbackResponse(DiagnosisContext context) {
        DiagnoseResponse response = new DiagnoseResponse();
        response.setSummary("系统暂时无法确定具体原因，建议人工排查");
        List<RootCauseItem> causes = new ArrayList<>();
        causes.add(new RootCauseItem("需要进一步分析",
                "当前任务状态组合未匹配到已知故障模式。任务状态: "
                        + (context.getTask() != null ? context.getTask().getTaskState() : "unknown")));
        response.setRootCauses(causes);
        response.setActions(new ArrayList<>(Arrays.asList(
                "检查 WCS 平台运行状态",
                "查看相关设备是否正常",
                "联系平台运维获取详细日志"
        )));
        response.setDiagnosisMode("fallback");
        response.setConfidence(diagnosisProperties.getConfidence().getFallback());
        response.setTraceId(context.getTraceId());
        return response;
    }

    private void logEnd(DiagnosisContext context, String mode, long startTime) {
        long elapsed = System.currentTimeMillis() - startTime;
        log.info("DIAG_END traceId={} mode={} elapsedMs={}", context.getTraceId(), mode, elapsed);
    }
}
