package cn.aimstek.loong.aidiag.controller;

import cn.aimstek.loong.aidiag.common.BaseResponse;
import cn.aimstek.loong.aidiag.common.Response;
import cn.aimstek.loong.aidiag.config.AgentConfig.ChatClientProvider;
import cn.aimstek.loong.aidiag.config.ModelConfig;
import cn.aimstek.loong.aidiag.dto.ChatRequest;
import cn.aimstek.loong.aidiag.service.AgentChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * v2 Agent 对话控制器：提供 SSE 流式对话、会话管理和模型配置接口。
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v2/diagnosis")
public class AgentChatController {

    private final AgentChatService agentChatService;
    private final ModelConfig modelConfig;
    private final ChatClientProvider chatClientProvider;

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatRequest request) {
        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = UUID.randomUUID().toString();
        }
        SseEmitter emitter = new SseEmitter(180_000L);
        agentChatService.chat(emitter, sessionId, request.getMessage());
        return emitter;
    }

    @DeleteMapping("/session/{sessionId}")
    public Response<Void> clearSession(@PathVariable String sessionId) {
        agentChatService.clearSession(sessionId);
        return BaseResponse.success(null);
    }

    // ---- 模型配置接口 ----

    /** 获取当前激活模型信息 */
    @GetMapping("/model-info")
    public Response<Map<String, String>> modelInfo() {
        ModelConfig.ModelItem item = modelConfig.getActiveModelItem();
        return BaseResponse.success(Map.of(
                "name", item.getName(),
                "displayName", item.getDisplayName() != null ? item.getDisplayName() : item.getModel(),
                "model", item.getModel(),
                "provider", item.getProvider()
        ));
    }

    /** 获取所有模型配置列表 */
    @GetMapping("/models")
    public Response<Map<String, Object>> listModels() {
        List<ModelConfig.ModelItem> models = modelConfig.listModels();
        // 脱敏：不返回完整 API Key
        List<Map<String, Object>> list = models.stream().map(m -> {
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("name", m.getName());
            map.put("displayName", m.getDisplayName());
            map.put("provider", m.getProvider());
            map.put("baseUrl", m.getBaseUrl());
            map.put("model", m.getModel());
            map.put("temperature", m.getTemperature());
            map.put("hasApiKey", m.getApiKey() != null && !m.getApiKey().isBlank());
            return map;
        }).toList();
        return BaseResponse.success(Map.of(
                "active", modelConfig.getActiveModelName(),
                "models", list
        ));
    }

    /** 切换激活模型 */
    @PostMapping("/models/switch")
    public Response<Void> switchModel(@RequestBody Map<String, String> body) {
        String name = body.get("name");
        modelConfig.switchModel(name);
        chatClientProvider.refresh();
        return BaseResponse.success(null);
    }

    /** 保存（新增/更新）模型配置 */
    @PostMapping("/models/save")
    public Response<Void> saveModel(@RequestBody ModelConfig.ModelItem item) {
        if (item.getName() == null || item.getName().isBlank()) {
            return BaseResponse.failure("400", "模型名称不能为空");
        }
        if (item.getModel() == null || item.getModel().isBlank()) {
            return BaseResponse.failure("400", "模型标识不能为空");
        }
        if (item.getProvider() == null) item.setProvider("openai-compatible");
        modelConfig.saveModel(item);
        chatClientProvider.refresh();
        return BaseResponse.success(null);
    }

    /** 删除模型配置 */
    @DeleteMapping("/models/{name}")
    public Response<Void> deleteModel(@PathVariable String name) {
        modelConfig.deleteModel(name);
        chatClientProvider.refresh();
        return BaseResponse.success(null);
    }
}
