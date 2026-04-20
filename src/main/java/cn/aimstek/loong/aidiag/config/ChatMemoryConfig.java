package cn.aimstek.loong.aidiag.config;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 对话记忆配置：使用内存存储 + 滑动窗口策略。
 * maxMessages 从 AgentProperties 读取，默认 50。
 */
@Configuration
@RequiredArgsConstructor
public class ChatMemoryConfig {

    private final AgentProperties agentProperties;

    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(agentProperties.getMaxMemoryMessages())
                .build();
    }
}
