package cn.aimstek.loong.aidiag.llm;

import cn.aimstek.loong.aidiag.exception.AiDiagnosisException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 基于 Spring AI 的 LLM 客户端实现。
 */
@Slf4j
@Component
public class SpringAiLlmClient implements LlmClient {

    private final ChatClient chatClient;

    @Autowired
    public SpringAiLlmClient(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel).build();
    }

    @Override
    public String call(String systemPrompt, String userPrompt) {
        try {
            var spec = chatClient.prompt();
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                spec = spec.system(systemPrompt);
            }
            String result = spec.user(userPrompt).call().content();
            if (result == null || result.isBlank()) {
                throw new AiDiagnosisException("LLM_RESPONSE_EMPTY", "LLM 返回内容为空");
            }
            return result;
        } catch (AiDiagnosisException e) {
            throw e;
        } catch (Exception e) {
            log.error("LLM 调用失败: {}", e.getMessage());
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("429")) {
                throw new AiDiagnosisException("LLM_RATE_LIMIT", "LLM 请求过于频繁，请稍后重试", e);
            }
            throw new AiDiagnosisException("LLM_CALL_FAILED", "LLM 调用失败: " + msg, e);
        }
    }
}
