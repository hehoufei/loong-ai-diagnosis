package cn.aimstek.loong.aidiag.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * v2 Agent 对话请求
 */
@Data
public class ChatRequest {

    @Schema(description = "会话ID，首次对话可不传，服务端生成")
    private String sessionId;

    @Schema(description = "用户消息内容", requiredMode = Schema.RequiredMode.REQUIRED)
    private String message;
}
