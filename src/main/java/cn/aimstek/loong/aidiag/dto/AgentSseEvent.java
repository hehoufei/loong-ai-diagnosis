package cn.aimstek.loong.aidiag.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent SSE 事件封装
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentSseEvent {

    /** 事件类型: thinking | tool_call | tool_result | text | done | error */
    private String type;

    /** 事件数据 */
    private Object data;
}
