package cn.aimstek.loong.aidiag.client;

import cn.aimstek.loong.aidiag.config.AgentConfig;
import cn.aimstek.loong.aidiag.exception.AiDiagnosisException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Slf4j
@Primary
@Component
public class SpringAiLlmClient implements LlmClient {

    private final AgentConfig.ChatClientProvider chatClientProvider;

    public SpringAiLlmClient(AgentConfig.ChatClientProvider chatClientProvider) {
        this.chatClientProvider = chatClientProvider;
    }

    @Override
    public String call(String prompt) {
        return call(null, prompt);
    }

    @Override
    public String call(String systemPrompt, String userPrompt) {
        try {
            // 每次调用时动态获取当前活跃模型的 ChatClient
            ChatClient chatClient = chatClientProvider.getChatClient();
            var spec = chatClient.prompt();
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                spec = spec.system(systemPrompt);
            }
            String result = spec
                .user(userPrompt)
                .call()
                .content();
            if (result == null || result.isBlank()) {
                throw new AiDiagnosisException("LLM_RESPONSE_EMPTY",
                    "LLM 返回内容为空，请稍后重试");
            }
            return result;
        } catch (AiDiagnosisException e) {
            throw e;
        } catch (Exception e) {
            log.error("LLM调用失败", e);
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("429")) {
                throw new AiDiagnosisException("LLM_RATE_LIMIT",
                    "LLM 请求过于频繁(429)，请稍后重试", e);
            }
            throw new AiDiagnosisException("LLM_CALL_FAILED",
                "LLM 调用失败: " + msg, e);
        }
    }

    @Override
    public Flux<String> callStream(String systemPrompt, String userPrompt) {
        try {
            ChatClient chatClient = chatClientProvider.getChatClient();
            var spec = chatClient.prompt();
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                spec = spec.system(systemPrompt);
            }
            return spec.user(userPrompt)
                .stream()
                .content();  // 返回 Flux<String>，每个元素是一个 token chunk
        } catch (Exception e) {
            log.error("LLM流式调用初始化失败", e);
            return Flux.error(new AiDiagnosisException("LLM_STREAM_FAILED",
                "LLM 流式调用失败: " + e.getMessage(), e));
        }
    }
}
