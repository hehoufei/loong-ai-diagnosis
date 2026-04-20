package cn.aimstek.loong.aidiag.client;

import reactor.core.publisher.Flux;

public interface LlmClient {

    String call(String prompt);

    /**
     * 带 system message 的调用，system 约束输出格式，user 提供数据
     */
    String call(String systemPrompt, String userPrompt);

    /**
     * 流式调用 - 逐 token 返回
     */
    default Flux<String> callStream(String systemPrompt, String userPrompt) {
        // 默认降级为同步调用
        return Flux.just(call(systemPrompt, userPrompt));
    }
}
