package cn.aimstek.loong.aidiag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * AI 诊断核心配置。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "loong.ai.diagnosis")
public class DiagnosisProperties {

    /** 是否启用 LLM 兜底 */
    private boolean enableLlmFallback = true;

    /** LLM 调用超时（毫秒） */
    private int llmTimeoutMs = 5000;

    /** LLM 最大重试次数 */
    private int llmMaxRetry = 1;

    /** 置信度配置 */
    private Confidence confidence = new Confidence();

    @Data
    public static class Confidence {
        /** 规则命中置信度 */
        private double ruleMatch = 0.95;
        /** LLM 命中置信度 */
        private double llmMatch = 0.7;
        /** 兜底置信度 */
        private double fallback = 0.3;
    }
}
