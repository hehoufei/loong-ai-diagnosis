package cn.aimstek.loong.aidiag.service;

import cn.aimstek.loong.aidiag.config.AgentConfig.ChatClientProvider;
import cn.aimstek.loong.aidiag.dto.AgentSseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Agent 对话服务：通过 ChatClient stream 模式执行 Agent 推理循环，
 * 实时通过 SSE 推送 text token、done 事件。
 */
@Slf4j
@Service
public class AgentChatService {

    private final ChatClientProvider chatClientProvider;
    private final ChatMemory chatMemory;
    private final ObjectMapper objectMapper;

    public AgentChatService(ChatClientProvider chatClientProvider,
                            ChatMemory chatMemory,
                            ObjectMapper objectMapper) {
        this.chatClientProvider = chatClientProvider;
        this.chatMemory = chatMemory;
        this.objectMapper = objectMapper;
    }

    @Async
    public void chat(SseEmitter emitter, String sessionId, String message) {
        emitter.onTimeout(() -> log.warn("SSE 连接超时, sessionId={}", sessionId));
        emitter.onError(t -> log.warn("SSE 连接异常, sessionId={}: {}", sessionId, t.getMessage()));
        emitter.onCompletion(() -> log.debug("SSE 连接完成, sessionId={}", sessionId));

        try {
            sendSseEvent(emitter, "thinking", Map.of("content", "正在分析您的问题..."));

            log.info("开始 Agent 推理, sessionId={}, message={}", sessionId,
                    message.length() > 100 ? message.substring(0, 100) + "..." : message);
            long startTime = System.currentTimeMillis();

            // 使用 stream() 流式模式，每个 token 实时推送到前端
            var flux = chatClientProvider.getChatClient().prompt()
                    .user(message)
                    .advisors(advisor -> advisor.param("chat_memory_conversation_id", sessionId))
                    .stream()
                    .chatResponse();

            // 用 CountDownLatch 等待流完成（因为我们在 @Async 线程中）
            CountDownLatch latch = new CountDownLatch(1);
            StringBuilder fullContent = new StringBuilder();

            Disposable subscription = flux.subscribe(
                    chatResponse -> {
                        // 每个 chunk 可能包含文本内容
                        if (chatResponse != null && chatResponse.getResult() != null
                                && chatResponse.getResult().getOutput() != null) {
                            String text = chatResponse.getResult().getOutput().getText();
                            if (text != null && !text.isEmpty()) {
                                fullContent.append(text);
                                sendSseEvent(emitter, "text", Map.of("content", text));
                            }
                        }
                    },
                    error -> {
                        long elapsed = System.currentTimeMillis() - startTime;
                        log.error("Agent 推理异常, sessionId={}, 耗时={}ms", sessionId, elapsed, error);
                        sendErrorAndComplete(emitter, "诊断过程出错: " + error.getMessage());
                        latch.countDown();
                    },
                    () -> {
                        long elapsed = System.currentTimeMillis() - startTime;
                        log.info("Agent 推理完成, sessionId={}, 耗时={}ms", sessionId, elapsed);

                        if (fullContent.length() == 0) {
                            sendSseEvent(emitter, "text",
                                    Map.of("content", "抱歉，未能生成有效的诊断结果，请重试。"));
                        }
                        sendSseEvent(emitter, "done", Collections.emptyMap());
                        try { emitter.complete(); } catch (Exception ignored) {}
                        latch.countDown();
                    }
            );

            // 等待流完成，最多等 5 分钟
            if (!latch.await(5, TimeUnit.MINUTES)) {
                log.warn("Agent 推理超时, sessionId={}", sessionId);
                subscription.dispose();
                sendErrorAndComplete(emitter, "诊断超时，请重试");
            }

        } catch (Exception e) {
            log.error("Agent 对话异常, sessionId={}", sessionId, e);
            sendErrorAndComplete(emitter, "诊断过程出错: " + e.getMessage());
        }
    }

    public void clearSession(String sessionId) {
        try {
            chatMemory.clear(sessionId);
            log.info("已清除会话记忆: {}", sessionId);
        } catch (Exception e) {
            log.warn("清除会话记忆失败: sessionId={}, error={}", sessionId, e.getMessage());
        }
    }

    private void sendSseEvent(SseEmitter emitter, String type, Object data) {
        try {
            AgentSseEvent event = new AgentSseEvent(type, data);
            emitter.send(SseEmitter.event()
                    .name(type)
                    .data(objectMapper.writeValueAsString(event)));
        } catch (Exception e) {
            log.warn("SSE 事件发送失败: type={}, error={}", type, e.getMessage());
        }
    }

    private void sendErrorAndComplete(SseEmitter emitter, String message) {
        try {
            sendSseEvent(emitter, "error", Map.of("message", message));
            emitter.complete();
        } catch (Exception e) {
            log.warn("SSE 错误事件发送失败", e);
        }
    }
}
