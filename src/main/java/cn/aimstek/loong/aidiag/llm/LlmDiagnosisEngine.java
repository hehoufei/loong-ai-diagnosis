package cn.aimstek.loong.aidiag.llm;

import cn.aimstek.loong.aidiag.config.DiagnosisProperties;
import cn.aimstek.loong.aidiag.context.DiagnosisContext;
import cn.aimstek.loong.aidiag.dto.DiagnoseResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * LLM 诊断引擎。
 * 负责构建 prompt、调用 LLM、解析响应，带超时和重试。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmDiagnosisEngine {

    private final LlmClient llmClient;
    private final PromptTemplateLoader promptLoader;
    private final DiagnosisResponseMapper responseMapper;
    private final DiagnosisProperties properties;

    public DiagnoseResponse diagnose(DiagnosisContext context) {
        String systemPrompt = promptLoader.getSystemPrompt();
        String userPrompt = promptLoader.buildUserPrompt(context);

        log.info("LLM_DIAGNOSE_START traceId={} promptLength={}",
                context.getTraceId(), userPrompt.length());

        int maxRetry = properties.getLlmMaxRetry();
        long timeoutMs = properties.getLlmTimeoutMs();

        Exception lastError = null;
        for (int attempt = 0; attempt <= maxRetry; attempt++) {
            try {
                String output = callWithTimeout(systemPrompt, userPrompt, timeoutMs);
                return responseMapper.fromLlmOutput(output);
            } catch (Exception e) {
                lastError = e;
                log.warn("LLM 调用失败 attempt={}, error={}", attempt, e.getMessage());
                if (attempt < maxRetry) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        log.error("LLM 调用最终失败 traceId={}, error={}", context.getTraceId(),
                lastError != null ? lastError.getMessage() : "unknown");
        return null;
    }

    private String callWithTimeout(String systemPrompt, String userPrompt, long timeoutMs) throws Exception {
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() ->
                llmClient.call(systemPrompt, userPrompt));
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new Exception("LLM 调用超时 " + timeoutMs + "ms");
        }
    }
}
