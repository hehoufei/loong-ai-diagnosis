package cn.aimstek.loong.aidiag.service;

import cn.aimstek.loong.aidiag.dto.DiagnoseRequest;
import cn.aimstek.loong.aidiag.dto.DiagnoseResponse;
import cn.aimstek.loong.aidiag.exception.AiDiagnosisException;
import cn.aimstek.loong.aidiag.rule.DiagnosisContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;


/**
 * 统一诊断编排入口。
 * 负责协调上下文构建、规则诊断以及后续大模型诊断。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiagnosisFacade {

    private final DiagnosisContextAssembler contextAssembler;
    private final RuleDiagnosisExecutor ruleDiagnosisExecutor;
    private final LlmDiagnosisEngine llmDiagnosisEngine;
    private final DiagnosisResponseMapper responseMapper;
    private final GlobalDiagnosisService globalDiagnosisService;

    public DiagnoseResponse diagnose(DiagnoseRequest request) {
        validate(request);

        if (isGlobalScope(request)) {
            return globalDiagnosisService.diagnose(request);
        }

        DiagnosisContext context = contextAssembler.assemble(request);
        RuleDiagnosisResult ruleResult = ruleDiagnosisExecutor.evaluate(context);
        if (ruleResult != null && ruleResult.isMatched() && ruleResult.getResponse() != null) {
            DiagnoseResponse response = responseMapper.normalize(ruleResult.getResponse());
            response.setAnalysisScope("single");
            response.setTraceId(context.getTraceId());
            response.setDiagnosisMode("rule");
            response.setConfidence(0.95);
            return responseMapper.withDocs(response, context.getRelevantDocs());
        }

        // 第一阶段先保留原有 LLM 兜底路径，后续再扩展为混合增强诊断
        String llmOutput = llmDiagnosisEngine.diagnose(context);
        DiagnoseResponse response = responseMapper.fromLlmOutput(llmOutput);
        response.setAnalysisScope("single");
        response.setTraceId(context.getTraceId());
        if (response.getDiagnosisMode() == null || response.getDiagnosisMode().isBlank()) {
            response.setDiagnosisMode("llm");
        }
        if (response.getConfidence() == null || response.getConfidence() <= 0) {
            response.setConfidence(0.7);
        }
        return responseMapper.withDocs(response, context.getRelevantDocs());
    }

    private boolean isGlobalScope(DiagnoseRequest request) {
        return request != null && "global".equalsIgnoreCase(request.getScope());
    }

    private void validate(DiagnoseRequest request) {
        if (request == null || !StringUtils.hasText(request.getTaskId())) {
            throw new AiDiagnosisException("VALIDATION_ERROR", "taskId不能为空", null);
        }
    }
}
