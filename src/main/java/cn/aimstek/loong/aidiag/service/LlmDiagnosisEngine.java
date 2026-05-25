package cn.aimstek.loong.aidiag.service;

import cn.aimstek.loong.aidiag.client.LlmClient;
import cn.aimstek.loong.aidiag.rule.DiagnosisContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * LLM 诊断引擎。
 * 负责组装 prompt、调用模型并返回原始输出，由结果映射器统一解析。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmDiagnosisEngine {

    private final LlmClient llmClient;

    public String diagnose(DiagnosisContext context) {
        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(context);
        log.info("开始LLM诊断, traceId={}, taskId={}, promptLength={}",
                context.getTraceId(),
                context.getDetail() != null ? context.getDetail().getTaskId() : "",
                userPrompt.length());
        return llmClient.call(systemPrompt, userPrompt);
    }

    private String buildSystemPrompt() {
        return "你是WCS任务排障专家。请严格根据给定的任务上下文进行分析，只输出JSON，不要输出任何额外文本。";
    }

    private String buildUserPrompt(DiagnosisContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("请根据以下上下文输出诊断结果。\n");
        sb.append("任务状态: ").append(context.getDetail() != null ? context.getDetail().getTaskState() : "").append("\n");
        sb.append("错误信息: ").append(context.getErrorMessage() == null ? "" : context.getErrorMessage()).append("\n");
        sb.append("日志:\n");
        for (String line : context.getLogs()) {
            sb.append(line).append("\n");
        }
        sb.append("\n输出字段必须包含 summary、rootCauses、actions。");
        return sb.toString();
    }
}
