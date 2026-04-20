package cn.aimstek.loong.aidiag.config;

import cn.aimstek.loong.aidiag.service.SystemPromptProvider;
import cn.aimstek.loong.aidiag.tool.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.zhipuai.ZhiPuAiChatModel;
import org.springframework.ai.zhipuai.ZhiPuAiChatOptions;
import org.springframework.ai.zhipuai.api.ZhiPuAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class AgentConfig {

    private final SystemPromptProvider systemPromptProvider;
    private final ModelConfig modelConfig;

    @Value("${spring.ai.zhipuai.api-key:}")
    private String defaultZhipuApiKey;

    @Bean
    public ChatClientProvider chatClientProvider(
            ChatMemory chatMemory,
            TaskDetailTool taskDetailTool,
            TaskItemsTool taskItemsTool,
            TicketsTool ticketsTool,
            SystemLogTool systemLogTool,
            PointConflictTool pointConflictTool,
            DocSearchTool docSearchTool) {
        return new ChatClientProvider(chatMemory, systemPromptProvider,
                taskDetailTool, taskItemsTool, ticketsTool,
                systemLogTool, pointConflictTool, docSearchTool,
                modelConfig, defaultZhipuApiKey);
    }

    public static class ChatClientProvider {
        private final ChatMemory chatMemory;
        private final SystemPromptProvider systemPromptProvider;
        private final Object[] tools;
        private final ModelConfig modelConfig;
        private final String defaultZhipuApiKey;
        private volatile ChatClient cachedClient;
        private volatile String cachedModelName;

        public ChatClientProvider(ChatMemory chatMemory, SystemPromptProvider systemPromptProvider,
                                  TaskDetailTool t1, TaskItemsTool t2, TicketsTool t3,
                                  SystemLogTool t4, PointConflictTool t5, DocSearchTool t6,
                                  ModelConfig modelConfig, String defaultZhipuApiKey) {
            this.chatMemory = chatMemory;
            this.systemPromptProvider = systemPromptProvider;
            this.tools = new Object[]{t1, t2, t3, t4, t5, t6};
            this.modelConfig = modelConfig;
            this.defaultZhipuApiKey = defaultZhipuApiKey;
        }

        public synchronized ChatClient getChatClient() {
            String currentModel = modelConfig.getActiveModelName();
            if (cachedClient != null && currentModel.equals(cachedModelName)) {
                return cachedClient;
            }
            cachedClient = buildChatClient(modelConfig.getActiveModelItem());
            cachedModelName = currentModel;
            log.info("构建 ChatClient: provider={}, model={}",
                    modelConfig.getActiveModelItem().getProvider(),
                    modelConfig.getActiveModelItem().getModel());
            return cachedClient;
        }

        public synchronized void refresh() {
            cachedClient = null;
            cachedModelName = null;
        }

        private ChatClient buildChatClient(ModelConfig.ModelItem item) {
            ChatModel chatModel = createChatModel(item);
            return ChatClient.builder(chatModel)
                    .defaultSystem(systemPromptProvider.buildSystemPrompt())
                    .defaultTools(tools)
                    .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                    .build();
        }

        private ChatModel createChatModel(ModelConfig.ModelItem item) {
            double temp = item.getTemperature() != null ? item.getTemperature() : 0.1;
            RetryTemplate retry = RetryTemplate.builder()
                    .maxAttempts(3).exponentialBackoff(2000, 2, 15000).build();

            if ("zhipuai".equals(item.getProvider())) {
                String apiKey = item.getApiKey() != null && !item.getApiKey().isBlank()
                        ? item.getApiKey() : defaultZhipuApiKey;
                return new ZhiPuAiChatModel(new ZhiPuAiApi(apiKey),
                        ZhiPuAiChatOptions.builder().model(item.getModel()).temperature(temp).build(),
                        retry);
            } else {
                String baseUrl = item.getBaseUrl();
                if (baseUrl == null || baseUrl.isBlank()) baseUrl = "https://api.openai.com";
                return OpenAiChatModel.builder()
                        .openAiApi(OpenAiApi.builder().baseUrl(baseUrl).apiKey(item.getApiKey()).build())
                        .defaultOptions(OpenAiChatOptions.builder().model(item.getModel()).temperature(temp).build())
                        .retryTemplate(retry)
                        .build();
            }
        }
    }
}
