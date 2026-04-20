package cn.aimstek.loong.aidiag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 行为参数配置
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    /** 最大工具调用轮次 */
    private int maxToolCallRounds = 10;

    /** 对话记忆消息上限 */
    private int maxMemoryMessages = 50;

    /** LLM 温度参数 */
    private double temperature = 0.1;

    /** RAG 检索返回条数 */
    private int ragTopK = 5;

    /** 向量存储持久化路径（留空则使用默认路径） */
    private String vectorStorePath = "";
}
