package cn.aimstek.loong.aidiag.service;

import cn.aimstek.loong.aidiag.config.AgentConfig.ChatClientProvider;
import cn.aimstek.loong.aidiag.dto.AgentSseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
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

            String messagePreview = message == null ? "" : (message.length() > 100 ? message.substring(0, 100) + "..." : message);
            log.info("开始 Agent 推理, sessionId={}, message={}", sessionId, messagePreview);
            long startTime = System.currentTimeMillis();

            long clientStart = System.currentTimeMillis();
            var chatClient = chatClientProvider.getChatClient();
            log.info("Agent 获取 ChatClient 完成, sessionId={}, cost={}ms", sessionId, System.currentTimeMillis() - clientStart);

            long streamStart = System.currentTimeMillis();
            var flux = chatClient.prompt()
                    .user(message)
                    .advisors(advisor -> advisor.param("chat_memory_conversation_id", sessionId))
                    .stream()
                    .chatResponse();
            log.info("Agent 开始流式订阅, sessionId={}, cost={}ms", sessionId, System.currentTimeMillis() - streamStart);

            CountDownLatch latch = new CountDownLatch(1);
            StringBuilder fullContent = new StringBuilder();
            long[] firstTokenAt = new long[]{-1L};

            Disposable subscription = flux.subscribe(
                    chatResponse -> {
                        if (chatResponse != null && chatResponse.getResult() != null
                                && chatResponse.getResult().getOutput() != null) {
                            String text = chatResponse.getResult().getOutput().getText();
                            if (text != null && !text.isEmpty()) {
                                if (firstTokenAt[0] < 0) {
                                    firstTokenAt[0] = System.currentTimeMillis();
                                    log.info("Agent 首个 token 到达, sessionId={}, firstTokenCost={}ms", sessionId, firstTokenAt[0] - startTime);
                                }
                                fullContent.append(text);
                                sendSseEvent(emitter, "text", Map.of("content", text));
                            }
                        }
                    },
                    error -> {
                        long elapsed = System.currentTimeMillis() - startTime;
                        long firstTokenCost = firstTokenAt[0] > 0 ? (firstTokenAt[0] - startTime) : -1;
                        log.error("Agent 推理异常, sessionId={}, 耗时={}ms, firstTokenCost={}ms, error={}", sessionId, elapsed, firstTokenCost, error.getMessage(), error);
                        sendErrorAndComplete(emitter, "诊断过程出错: " + error.getMessage());
                        latch.countDown();
                    },
                    () -> {
                        long elapsed = System.currentTimeMillis() - startTime;
                        long firstTokenCost = firstTokenAt[0] > 0 ? (firstTokenAt[0] - startTime) : -1;
                        log.info("Agent 推理完成, sessionId={}, 耗时={}ms, firstTokenCost={}ms, 输出长度={}", sessionId, elapsed, firstTokenCost, fullContent.length());

                        if (fullContent.length() == 0) {
                            sendSseEvent(emitter, "text", Map.of("content", "抱歉，未能生成有效的诊断结果，请重试。"));
                        }
                        sendSseEvent(emitter, "done", Collections.emptyMap());
                        try {
                            emitter.complete();
                        } catch (Exception ignored) {
                        }
                        latch.countDown();
                    }
            );

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
