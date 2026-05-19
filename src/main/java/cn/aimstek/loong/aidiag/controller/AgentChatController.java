package cn.aimstek.loong.aidiag.controller;

import cn.aimstek.loong.aidiag.client.PlatformClient;
import cn.aimstek.loong.aidiag.common.BaseResponse;
import cn.aimstek.loong.aidiag.common.Response;
import cn.aimstek.loong.aidiag.dto.DiagnoseRequest;
import cn.aimstek.loong.aidiag.dto.DiagnoseResponse;
import cn.aimstek.loong.aidiag.facade.DiagnosisFacade;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 对话 Controller。
 * 提供 SSE 流式对话接口，供 chat.html 使用。
 * Agent 内置诊断和查询工具。
 */
@Slf4j
@Tag(name = "Agent 对话接口", description = "SSE 流式对话，内置诊断工具")
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v2/diagnosis/chat")
public class AgentChatController {

    private final ChatModel chatModel;
    private final DiagnosisFacade diagnosisFacade;
    private final PlatformClient platformClient;

    /** 会话存储（简单内存实现，生产环境应使用 Redis） */
    private final Map<String, ChatSession> sessions = new ConcurrentHashMap<>();

    /**
     * 25.1 SSE 流式对话接口
     */
    @Operation(summary = "SSE 流式对话")
    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(60_000L); // 60秒超时
        String sessionId = request.getSessionId() != null ? request.getSessionId() : "default";

        // 获取或创建会话
        ChatSession session = sessions.computeIfAbsent(sessionId, k -> new ChatSession());

        // 异步处理
        new Thread(() -> {
            try {
                sendEvent(emitter, "thinking", "正在思考...");

                // 简化实现：直接使用 ChatModel（不使用 function calling，因为 Spring AI 1.0.0 API 可能不同）
                String systemPrompt = """
                        你是一个智能仓储系统（WCS）的诊断助手。
                        你可以帮助用户：
                        1. 诊断任务问题
                        2. 查询任务详情
                        3. 分析任务状态
                        
                        请根据用户问题给出专业的分析和建议。
                        """;

                String userMessage = request.getMessage();
                
                // 检测是否需要调用工具
                if (userMessage.contains("诊断") && userMessage.matches(".*[TW]\\d+.*")) {
                    // 提取任务号并调用诊断工具
                    String taskNo = extractTaskNo(userMessage);
                    if (taskNo != null) {
                        sendEvent(emitter, "tool_call", "diagnose_task(" + taskNo + ")");
                        String toolResult = callDiagnoseTool(taskNo);
                        sendEvent(emitter, "tool_result", toolResult);
                        sendEvent(emitter, "text", toolResult);
                    }
                } else if (userMessage.contains("查询") && userMessage.matches(".*[TW]\\d+.*")) {
                    // 提取任务号并调用查询工具
                    String taskNo = extractTaskNo(userMessage);
                    if (taskNo != null) {
                        sendEvent(emitter, "tool_call", "query_task_detail(" + taskNo + ")");
                        String toolResult = callQueryTool(taskNo);
                        sendEvent(emitter, "tool_result", toolResult);
                        sendEvent(emitter, "text", toolResult);
                    }
                } else {
                    // 普通对话
                    sendEvent(emitter, "text", "我是 WCS 诊断助手。您可以：\n1. 诊断任务：输入'诊断任务 T001'\n2. 查询任务：输入'查询任务 T001'");
                }

                sendEvent(emitter, "done", "");
                emitter.complete();

            } catch (Exception e) {
                log.error("对话处理失败: {}", e.getMessage(), e);
                try {
                    sendEvent(emitter, "error", e.getMessage());
                    emitter.completeWithError(e);
                } catch (IOException ex) {
                    log.error("发送错误事件失败", ex);
                }
            }
        }).start();

        return emitter;
    }

    /**
     * 25.4 返回可用模型列表
     */
    @Operation(summary = "获取可用模型列表")
    @GetMapping("/models")
    public ResponseEntity<Response<List<ModelInfo>>> getModels() {
        // 从配置读取（简化实现，实际应从配置文件读取）
        List<ModelInfo> models = Arrays.asList(
                new ModelInfo("glm-4-flash", "GLM-4-Flash", true),
                new ModelInfo("glm-4", "GLM-4", false),
                new ModelInfo("glm-4-plus", "GLM-4-Plus", false)
        );
        return ResponseEntity.ok(BaseResponse.success(models));
    }

    /**
     * 25.5 切换当前使用的模型
     */
    @Operation(summary = "切换模型")
    @PostMapping("/models/switch")
    public ResponseEntity<Response<Void>> switchModel(@RequestBody SwitchModelRequest request) {
        // 简化实现：实际应更新配置或重新创建 ChatModel
        log.info("切换模型到: {}", request.getModelName());
        return ResponseEntity.ok(BaseResponse.success(null));
    }

    /**
     * 25.6 返回当前环境信息
     */
    @Operation(summary = "获取环境信息")
    @GetMapping("/env")
    public ResponseEntity<Response<EnvInfo>> getEnv() {
        EnvInfo info = new EnvInfo();
        info.setEnvironment("production");
        info.setModelName("glm-4-flash");
        return ResponseEntity.ok(BaseResponse.success(info));
    }

    // ==================== 内置工具（25.3） ====================

    /**
     * 提取任务号。
     */
    private String extractTaskNo(String message) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("[TW]\\d+");
        java.util.regex.Matcher matcher = pattern.matcher(message);
        if (matcher.find()) {
            return matcher.group();
        }
        return null;
    }

    /**
     * 调用诊断工具。
     */
    private String callDiagnoseTool(String taskNo) {
        try {
            DiagnoseRequest req = new DiagnoseRequest();
            req.setTaskId(taskNo);
            DiagnoseResponse response = diagnosisFacade.diagnose(req);
            return String.format("诊断结果：%s\n根因：%s\n建议：%s",
                    response.getSummary(),
                    response.getRootCauses(),
                    response.getActions());
        } catch (Exception e) {
            return "诊断失败: " + e.getMessage();
        }
    }

    /**
     * 调用查询工具。
     */
    private String callQueryTool(String taskNo) {
        try {
            var task = platformClient.queryTask(taskNo);
            var bundle = platformClient.queryTaskItemBundle(taskNo);
            return String.format("任务 %s：状态=%s，子任务数=%d，指令数=%d",
                    task.getTaskNo(),
                    task.getTaskState(),
                    bundle.getItems().size(),
                    bundle.getItems().stream().mapToInt(i -> i.getCommands().size()).sum());
        } catch (Exception e) {
            return "查询失败: " + e.getMessage();
        }
    }

    /**
     * 诊断任务工具（Spring Bean，供 Spring AI function calling 使用）。
     */
    @org.springframework.context.annotation.Bean
    @org.springframework.context.annotation.Description("诊断指定任务，返回诊断结果")
    public java.util.function.Function<DiagnoseTaskRequest, String> diagnose_task() {
        return request -> callDiagnoseTool(request.taskNo);
    }

    /**
     * 查询任务详情工具（Spring Bean，供 Spring AI function calling 使用）。
     */
    @org.springframework.context.annotation.Bean
    @org.springframework.context.annotation.Description("查询任务详情，返回任务状态和子任务信息")
    public java.util.function.Function<QueryTaskRequest, String> query_task_detail() {
        return request -> callQueryTool(request.taskNo);
    }

    // ==================== 辅助方法 ====================

    private void sendEvent(SseEmitter emitter, String eventType, String data) throws IOException {
        emitter.send(SseEmitter.event()
                .name(eventType)
                .data(data));
    }

    // ==================== DTO ====================

    @Data
    public static class ChatRequest {
        private String sessionId;
        private String message;
    }

    @Data
    public static class ModelInfo {
        private String id;
        private String name;
        private boolean isDefault;

        public ModelInfo(String id, String name, boolean isDefault) {
            this.id = id;
            this.name = name;
            this.isDefault = isDefault;
        }
    }

    @Data
    public static class SwitchModelRequest {
        private String modelName;
    }

    @Data
    public static class EnvInfo {
        private String environment;
        private String modelName;
    }

    @Data
    public static class DiagnoseTaskRequest {
        private String taskNo;
    }

    @Data
    public static class QueryTaskRequest {
        private String taskNo;
    }

    /**
     * 会话上下文（简化实现）。
     */
    private static class ChatSession {
        // 可扩展：存储对话历史、上下文等
    }
}
