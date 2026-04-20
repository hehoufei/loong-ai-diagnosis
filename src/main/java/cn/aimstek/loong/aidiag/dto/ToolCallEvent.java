package cn.aimstek.loong.aidiag.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 工具调用事件
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ToolCallEvent {

    /** 工具名称 */
    private String tool;

    /** 调用参数 */
    private Map<String, Object> args;
}
