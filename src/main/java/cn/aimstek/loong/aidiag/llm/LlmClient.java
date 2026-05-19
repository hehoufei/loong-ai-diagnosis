package cn.aimstek.loong.aidiag.llm;

/**
 * LLM 客户端接口。
 */
public interface LlmClient {

    /**
     * 调用 LLM，传入 system prompt 和 user prompt。
     * @param systemPrompt 系统提示词
     * @param userPrompt 用户提示词
     * @return LLM 原始输出
     */
    String call(String systemPrompt, String userPrompt);
}
